package it.cubetto.app.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import it.cubetto.app.data.AudioOptions
import it.cubetto.app.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Un video trovato a partire da un link (singolo o voce di una playlist YouTube). */
data class ResolvedVideo(val url: String, val title: String, val durationSec: Int, val thumbnail: String?)

data class Chapter(val title: String)

data class DownloadResult(val files: List<File>, val chapters: List<Chapter>)

sealed interface YtDlpStatus {
    data object Starting : YtDlpStatus
    data object Checking : YtDlpStatus
    data class UpToDate(val version: String?) : YtDlpStatus
    data class Updated(val version: String?) : YtDlpStatus
    data class Error(val message: String, val version: String?) : YtDlpStatus
}

/**
 * Gestisce yt-dlp: lo scarica/aggiorna dalle release GitHub (yt-dlp/yt-dlp) nei file
 * privati dell'app tramite youtubedl-android. Se è già all'ultima versione non fa nulla.
 */
class YtDlpManager(private val context: Context, private val settings: Settings) {
    private val _status = MutableStateFlow<YtDlpStatus>(YtDlpStatus.Starting)
    val status: StateFlow<YtDlpStatus> = _status.asStateFlow()
    private val ready = CompletableDeferred<Unit>()

    val version: String? get() = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    suspend fun init() = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            ready.complete(Unit)
            _status.value = YtDlpStatus.UpToDate(version)
        }.onFailure {
            Log.e(TAG, "init", it)
            _status.value = YtDlpStatus.Error(it.message ?: it.javaClass.simpleName, null)
            ready.completeExceptionally(it)
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Controlla GitHub al massimo una volta al giorno, a meno che [force]. */
    suspend fun updateIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) {
        awaitReady()
        val now = System.currentTimeMillis()
        if (!force && now - settings.state.value.lastYtDlpCheck < DAY_MS) return@withContext
        _status.value = YtDlpStatus.Checking
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        }.onSuccess { result ->
            settings.update { it.copy(lastYtDlpCheck = now) }
            _status.value = if (result == YoutubeDL.UpdateStatus.DONE) YtDlpStatus.Updated(version) else YtDlpStatus.UpToDate(version)
        }.onFailure {
            Log.w(TAG, "update", it)
            _status.value = YtDlpStatus.Error(it.message ?: it.javaClass.simpleName, version)
        }
    }

    /** Espande un link in uno o più video (le playlist YouTube diventano più brani). */
    suspend fun resolve(url: String): List<ResolvedVideo> = withContext(Dispatchers.IO) {
        awaitReady()
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("-J")
        }
        val json = JSONObject(YoutubeDL.getInstance().execute(req).out)
        if (json.optString("_type") == "playlist") {
            val entries = json.optJSONArray("entries") ?: return@withContext emptyList()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id")
                val link = e.optString("url").ifBlank { e.optString("webpage_url") }.ifBlank {
                    if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else return@mapNotNull null
                }
                ResolvedVideo(link, e.optString("title", link), e.optDouble("duration", 0.0).toInt(), thumbnailOf(e, id))
            }
        } else {
            listOf(
                ResolvedVideo(
                    json.optString("webpage_url", url),
                    json.optString("title", url),
                    json.optDouble("duration", 0.0).toInt(),
                    thumbnailOf(json, json.optString("id")),
                )
            )
        }
    }

    private fun thumbnailOf(o: JSONObject, id: String): String? {
        o.optString("thumbnail").takeIf { it.isNotBlank() }?.let { return it }
        val thumbs = o.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
        return if (id.isNotBlank()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
    }

    /**
     * Scarica l'audio originale (senza ricodificarlo) nella cartella [dir]: la conversione in MP3
     * la fa poi [LocalConverter], con lo stesso comando usato per i file del dispositivo.
     * Con [splitChapters] restituisce un file per capitolo.
     */
    suspend fun downloadAudio(
        url: String,
        dir: File,
        options: AudioOptions,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        awaitReady()
        dir.deleteRecursively()
        dir.mkdirs()
        val split = options.splitChapters
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-f", "bestaudio/best")
            addOption("-o", "${dir.absolutePath}/source.%(ext)s")
            if (split) {
                addOption("--split-chapters")
                addOption("--write-info-json")
                addOption("-o", "chapter:${dir.absolutePath}/ch_%(section_number)03d.%(ext)s")
            }
            if (options.skipSponsors) addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction")
        }
        val response = YoutubeDL.getInstance().execute(req, processId) { progress, _, line -> onProgress(progress, line) }
        if (response.exitCode != 0) error(response.err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp exit ${response.exitCode}")

        fun media(prefix: String) = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.extension !in NOT_MEDIA }
            ?.sortedBy { it.name }.orEmpty()
        val chapterFiles = media("ch_")
        val main = media("source.")
        if (split && chapterFiles.size > 1) {
            val chapters = readChapters(dir)
            main.forEach { it.delete() }
            DownloadResult(chapterFiles, chapterFiles.indices.map { chapters.getOrNull(it) ?: Chapter("") })
        } else {
            chapterFiles.forEach { it.delete() }
            DownloadResult(listOf(main.firstOrNull() ?: error("Audio non trovato")), emptyList())
        }
    }

    private fun readChapters(dir: File): List<Chapter> = runCatching {
        val info = dir.listFiles { f -> f.name.endsWith(".info.json") }?.firstOrNull() ?: return emptyList()
        val arr = JSONObject(info.readText()).optJSONArray("chapters") ?: return emptyList()
        (0 until arr.length()).map { Chapter(arr.getJSONObject(it).optString("title")) }
    }.getOrDefault(emptyList())

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    companion object {
        private const val TAG = "YtDlp"
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private val NOT_MEDIA = setOf("json", "part", "ytdl", "tmp")
    }
}
