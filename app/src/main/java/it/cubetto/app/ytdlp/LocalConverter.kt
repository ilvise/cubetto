package it.cubetto.app.ytdlp

import android.content.Context
import it.cubetto.app.data.AudioOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * L'unica codifica audio di Cubetto, con lo stesso ffmpeg di yt-dlp (quello estratto da
 * youtubedl-android). Ogni sorgente, che sia l'audio originale scaricato da YouTube o un
 * file del dispositivo (MP3, M4A, OPUS, WAV, video…), passa per lo stesso identico comando:
 * prima traccia audio, filtri scelti, MP3 CBR joint stereo 44,1 kHz come gli originali FABA™.
 */
class LocalConverter(private val context: Context, private val ytDlp: YtDlpManager) {
    private val running = ConcurrentHashMap<String, Process>()

    private val ffmpeg get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private val libraryPath: String
        get() {
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            return listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        }

    /** Converte [source] in `audio.mp3` dentro [dir], che viene svuotata prima. */
    suspend fun convert(
        source: File,
        dir: File,
        title: String,
        durationSec: Int,
        options: AudioOptions,
        processId: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!source.exists()) error("File non trovato")
        dir.deleteRecursively()
        dir.mkdirs()
        encode(source, File(dir, "audio.mp3"), title, durationSec, options, trimSilence = options.trimSilence, processId, onProgress)
    }

    /** Codifica [source] in [out] con il comando di [ffmpegArgs]. */
    suspend fun encode(
        source: File,
        out: File,
        title: String,
        durationSec: Int,
        options: AudioOptions,
        trimSilence: Boolean,
        processId: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ytDlp.awaitReady()
        if (!source.exists()) error("File non trovato")
        out.delete()
        val command = buildList {
            add(ffmpeg.absolutePath)
            addAll(listOf("-hide_banner", "-nostdin", "-y", "-i", source.absolutePath))
            addAll(ffmpegArgs(options, durationSec, trimSilence, title))
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
            if (code != 0 || !out.exists()) {
                out.delete()
                error(log.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit $code")
            }
            onProgress(100)
            out
        } finally {
            running.remove(processId)
        }
    }

    fun cancel(processId: String) {
        running.remove(processId)?.destroy()
    }

    companion object {
        /** Il taglio del silenzio finale usa areverse, che tiene in memoria la traccia: solo per brani brevi. */
        private const val MAX_END_TRIM_SEC = 6 * 60

        /**
         * Gli argomenti di ffmpeg tra l'input e l'output, uguali per ogni tipo di file.
         * Il box non riproduce gli MP3 mono né i VBR: l'opzione mono unisce i canali ma il file
         * resta joint stereo, e il bitrate è sempre costante (96 kbps come gli originali, 128 in stereo).
         */
        fun ffmpegArgs(options: AudioOptions, durationSec: Int, trimSilence: Boolean, title: String): List<String> {
            val filters = buildList {
                if (options.mono) add("aformat=channel_layouts=mono")
                if (trimSilence) {
                    val trim = "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.2"
                    add(trim)
                    if (durationSec in 1..MAX_END_TRIM_SEC) {
                        add("areverse"); add(trim); add("areverse")
                    }
                }
                if (options.normalize) add("loudnorm=I=-16:TP=-1.5:LRA=11")
                // Sempre in fondo: qualunque sia la sorgente si arriva allo stesso formato
                add("aresample=44100")
                add("aformat=sample_rates=44100:channel_layouts=stereo")
            }
            return listOf(
                // Solo la prima traccia audio: niente video, copertine, capitoli né metadati originali
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
                "-af", filters.joinToString(","),
                "-ac", "2", "-ar", "44100",
                "-c:a", "libmp3lame", "-b:a", "${options.bitrateKbps}k", "-joint_stereo", "1",
                "-id3v2_version", "3", "-write_id3v1", "0", "-write_xing", "1", "-metadata", "title=$title",
                "-f", "mp3",
            )
        }
    }
}
