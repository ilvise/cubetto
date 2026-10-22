package it.cubetto.app.ytdlp

import android.content.Context
import it.cubetto.app.data.AudioOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Converte i file audio scelti dal dispositivo con lo stesso ffmpeg di yt-dlp
 * (quello estratto da youtubedl-android): MP3 44,1 kHz, volume uniformato
 * e tag ID3 ripuliti, con il solo titolo.
 */
class LocalConverter(private val context: Context, private val ytDlp: YtDlpManager) {
    private val running = ConcurrentHashMap<String, Process>()

    private val ffmpeg get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private val libraryPath: String
        get() {
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            return listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        }

    suspend fun convert(
        source: File,
        dir: File,
        title: String,
        durationSec: Int,
        options: AudioOptions,
        processId: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ytDlp.awaitReady()
        if (!source.exists()) error("File non trovato")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, "audio.mp3")
        // Il volume uniformato è sempre attivo per i file del dispositivo
        val audio = YtDlpManager.ffmpegArgs(options.copy(normalize = true), durationSec, splitChapters = false)
        val command = buildList {
            add(ffmpeg.absolutePath)
            addAll(listOf("-hide_banner", "-nostdin", "-y", "-i", source.absolutePath))
            // Solo la prima traccia audio: niente copertine, capitoli né metadati originali
            addAll(listOf("-map", "0:a:0", "-map_metadata", "-1", "-map_chapters", "-1"))
            // "-ac 1 -ar 44100 -af a,b": i filtri non contengono spazi
            addAll(audio.split(' ').filter(String::isNotBlank))
            addAll(listOf("-c:a", "libmp3lame", "-b:a", "${options.bitrateKbps}k")) // CBR come gli originali
            addAll(listOf("-id3v2_version", "3", "-write_id3v1", "0", "-metadata", "title=$title"))
            addAll(listOf("-progress", "pipe:1", "-nostats", out.absolutePath))
        }
        val process = ProcessBuilder(command).apply {
            environment()["LD_LIBRARY_PATH"] = libraryPath
            redirectErrorStream(true)
        }.start()
        running[processId] = process
        val log = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("out_time_us=") && durationSec > 0) {
                    line.substringAfter('=').toLongOrNull()?.let { us ->
                        onProgress((us / 10_000 / durationSec).toInt().coerceIn(0, 99))
                    }
                } else if ('=' !in line || ' ' in line) {
                    log.addLast(line)
                    if (log.size > 20) log.removeFirst()
                }
            }
            val code = process.waitFor()
            if (!running.containsKey(processId)) error("Annullato")
            if (code != 0 || !out.exists()) error(log.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit $code")
            onProgress(100)
            out
        } finally {
            running.remove(processId)
        }
    }

    fun cancel(processId: String) {
        running.remove(processId)?.destroy()
    }
}
