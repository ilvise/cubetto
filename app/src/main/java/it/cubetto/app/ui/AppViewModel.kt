package it.cubetto.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.cubetto.app.CubettoApp
import it.cubetto.app.data.AudioOptions
import it.cubetto.app.data.Playlist
import it.cubetto.app.data.Track
import it.cubetto.app.data.LOCAL_SOURCE
import it.cubetto.app.data.TrackStatus
import it.cubetto.app.data.isLocal
import it.cubetto.app.data.localSource
import it.cubetto.app.faba.IdAdvisor
import it.cubetto.app.faba.IdOption
import it.cubetto.app.nfc.NfcMode
import it.cubetto.app.ytdlp.AudioMeta
import it.cubetto.app.ytdlp.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlaylistUi(
    val playlist: Playlist,
    val tracks: List<Track>,
    val readyCount: Int,
    val pendingCount: Int,
    val errorCount: Int,
    val durationSec: Int,
    val needsSync: Boolean,
    val onBox: Boolean,
)

data class AddState(val busy: Boolean = false, val added: Int? = null, val error: String? = null, val fileError: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CubettoApp).container
    private val dao = c.db.dao()

    val settings = c.settings.state
    val box = c.usb.state
    val sync = c.usb.sync
    val ytDlp = c.ytDlp.status
    val nfcResult = c.nfc.result
    val nfcMode = c.nfc.mode
    val nowPlaying = c.player.now

    val playlists: StateFlow<List<PlaylistUi>> = combine(dao.playlists(), dao.allTracks(), c.usb.state) { ps, ts, box ->
        val byPlaylist = ts.groupBy { it.playlistId }
        // Finché la lettura del box non è finita vale l'ultima copia registrata
        val onBoxIds = (box as? it.cubetto.app.usb.BoxState.Connected)?.info?.takeIf { !it.scanning }?.figures?.map { it.id }?.toSet()
        ps.map { p ->
            val tracks = byPlaylist[p.id].orEmpty()
            PlaylistUi(
                playlist = p,
                tracks = tracks,
                readyCount = tracks.count { it.status == TrackStatus.READY },
                pendingCount = tracks.count { it.status in PENDING },
                errorCount = tracks.count { it.status == TrackStatus.ERROR },
                durationSec = tracks.sumOf { it.durationSec },
                needsSync = c.usb.needsSync(p, tracks),
                onBox = if (onBoxIds != null) p.figureId in onBoxIds && p.syncedFigureId == p.figureId else p.syncedAt != null,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    fun showMessage(text: String) { _message.value = text }

    // ---- Statuine / playlist ----

    fun idOptions(language: String, excludePlaylist: Long? = null): List<IdOption> {
        val used = playlists.value.filter { it.playlist.id != excludePlaylist }.associate { it.playlist.figureId to it.playlist.name }
        return IdAdvisor.options(c.registry.figures, language, used)
    }

    fun knownName(id: String): String? = c.registry.byId(id).firstOrNull()?.name

    suspend fun createPlaylist(name: String, color: Int, figureId: String): Long =
        dao.insert(Playlist(name = name.trim(), figureId = figureId, color = color))

    fun updatePlaylist(p: Playlist) = viewModelScope.launch { dao.update(p) }

    fun toggleFavorite(p: Playlist) = updatePlaylist(p.copy(favorite = !p.favorite))

    fun deletePlaylist(p: Playlist) = viewModelScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) { if (c.player.now.value?.playlistId == p.id) c.player.stop() }
        dao.getTracks(p.id).forEach { t ->
            cancelWork(t)
            File(getApplication<Application>().filesDir, "audio/${t.id}").deleteRecursively()
        }
        p.coverPath?.let { File(it).delete() }
        dao.delete(p)
    }

    fun setCover(p: Playlist, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        val dest = File(dir, "${p.id}_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        p.coverPath?.let { File(it).delete() }
        dao.update(p.copy(coverPath = dest.absolutePath))
    }

    fun removeCover(p: Playlist) = viewModelScope.launch {
        p.coverPath?.let { File(it).delete() }
        dao.update(p.copy(coverPath = null))
    }

    // ---- Brani ----

    fun addUrl(playlistId: Long, url: String) {
        if (_add.value.busy) return
        _add.value = AddState(busy = true)
        viewModelScope.launch {
            runCatching {
                val videos = c.ytDlp.resolve(url.trim())
                var pos = dao.maxPosition(playlistId)
                dao.insertAll(videos.map { v ->
                    Track(playlistId = playlistId, position = ++pos, title = v.title, sourceUrl = v.url, thumbnailUrl = v.thumbnail, durationSec = v.durationSec)
                })
                DownloadWorker.start(getApplication())
                videos.size
            }.onSuccess { _add.value = AddState(added = it) }
                .onFailure { _add.value = AddState(error = it.message ?: it.javaClass.simpleName) }
        }
    }

    /** File audio scelti dal dispositivo: copiati nell'app e messi in coda per ffmpeg. */
    fun addFiles(playlistId: Long, uris: List<Uri>) {
        if (_add.value.busy || uris.isEmpty()) return
        _add.value = AddState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val dir = File(ctx.filesDir, "import").apply { mkdirs() }
            var added = 0
            var failure: String? = null
            for (uri in uris) {
                val name = displayName(uri) ?: uri.lastPathSegment ?: "audio"
                val copy = File(dir, "${System.nanoTime()}.${name.substringAfterLast('.', "audio").take(8)}")
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    val meta = AudioMeta.read(copy)
                    val title = meta.title?.let { t -> meta.artist?.let { "$it - $t" } ?: t } ?: name.substringBeforeLast('.')
                    dao.insert(Track(
                        playlistId = playlistId, position = dao.maxPosition(playlistId) + 1, title = title,
                        sourceUrl = LOCAL_SOURCE + copy.absolutePath, durationSec = meta.durationSec,
                    ))
                    added++
                }.onFailure { e ->
                    copy.delete()
                    failure = "$name: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (added > 0) DownloadWorker.start(ctx)
            _add.value = if (added == 0 && failure != null) AddState(fileError = failure) else AddState(added = added, fileError = failure)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull()

    private fun cancelWork(t: Track) {
        c.ytDlp.cancel(t.id.toString())
        c.converter.cancel(t.id.toString())
        t.localSource?.delete()
    }

    fun resetAdd() { _add.value = AddState() }

    /** Link arrivato da "Condividi": lo raccoglie la schermata Aggiungi. */
    private val _shared = MutableStateFlow<String?>(null)
    val shared: StateFlow<String?> = _shared.asStateFlow()
    fun share(url: String) { _shared.value = url }
    fun consumeShared() { _shared.value = null }

    fun retry(t: Track) = viewModelScope.launch {
        dao.update(t.copy(status = TrackStatus.QUEUED, progress = 0, error = null))
        DownloadWorker.start(getApplication())
    }

    fun retryAll(playlistId: Long) = viewModelScope.launch {
        dao.getTracks(playlistId).filter { it.status == TrackStatus.ERROR }
            .forEach { dao.update(it.copy(status = TrackStatus.QUEUED, progress = 0, error = null)) }
        DownloadWorker.start(getApplication())
    }

    fun deleteTrack(t: Track) = viewModelScope.launch(Dispatchers.IO) {
        cancelWork(t)
        withContext(Dispatchers.Main) { c.player.remove(t.id) }
        File(getApplication<Application>().filesDir, "audio/${t.id}").deleteRecursively()
        dao.delete(t)
        dao.updateAll(dao.getTracks(t.playlistId).mapIndexed { i, x -> x.copy(position = i + 1) })
    }

    fun renameTrack(t: Track, title: String) = viewModelScope.launch { dao.update(t.copy(title = title.trim())) }

    /** Salva il nuovo ordine dopo un trascinamento. */
    fun reorder(playlistId: Long, orderedIds: List<Long>) = viewModelScope.launch {
        val byId = dao.getTracks(playlistId).associateBy { it.id }
        dao.updateAll(orderedIds.mapIndexedNotNull { i, id -> byId[id]?.copy(position = i + 1) })
    }

    /** Riproduce la playlist da [start] (o dall'inizio), con tutta la coda dei brani pronti. */
    fun play(ui: PlaylistUi, start: Track? = null) = c.player.play(ui.playlist, ui.tracks, start?.id)

    fun togglePlayer() = c.player.toggle()
    fun nextTrack() = c.player.next()
    fun previousTrack() = c.player.previous()
    fun seekTo(ms: Long) = c.player.seekTo(ms)
    fun skipTo(index: Int) = c.player.skipTo(index)
    fun toggleShuffle() = c.player.toggleShuffle()
    fun cycleRepeat() = c.player.cycleRepeat()
    fun stopPlayer() = c.player.stop()

    // ---- Box ----

    fun refreshBox(force: Boolean = false) = c.usb.refresh(force, rescan = force)
    fun ejectBox() = viewModelScope.launch { c.usb.eject() }
    fun requestUsbPermission() = c.usb.requestPermission()
    fun sync(p: Playlist) = viewModelScope.launch { c.usb.sync(p) }
    fun syncAll() = viewModelScope.launch { c.syncAllPending() }
    fun clearSync() = c.usb.clearSyncState()
    fun backups() = c.usb.backups()
    fun restore(b: it.cubetto.app.usb.Backup, done: (Boolean) -> Unit) = viewModelScope.launch { done(c.usb.restore(b)) }
    fun deleteBackup(b: it.cubetto.app.usb.Backup) = c.usb.deleteBackup(b)
    fun backupAll(done: (Int) -> Unit) = viewModelScope.launch { done(c.usb.backupAll()) }

    // ---- NFC ----

    fun startNfcWrite(figureId: String, force: Boolean = false) = c.nfc.start(NfcMode.Write(figureId, force))
    fun startNfcRead() = c.nfc.start(NfcMode.Read)
    fun stopNfc() = c.nfc.stop()

    // ---- Impostazioni ----

    fun setAudio(block: (AudioOptions) -> AudioOptions) = c.settings.update { it.copy(audio = block(it.audio)) }
    fun setAutoSync(on: Boolean) = c.settings.update { it.copy(autoSync = on) }
    fun dismissWarning() = c.settings.update { it.copy(warningDismissed = true) }
    fun updateYtDlp() = viewModelScope.launch { c.ytDlp.updateIfNeeded(force = true) }
    val ytDlpVersion: String? get() = c.ytDlp.version

    fun updateRegistry(done: (Int?) -> Unit) = viewModelScope.launch { done(c.registry.update()) }
    val registryCount: Int get() = c.registry.figures.size

    fun setLanguage(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    // ---- Export / import (solo i link: l'audio si riscarica) ----

    fun export(uri: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                dao.allPlaylists().forEach { p ->
                    val tracks = JSONArray()
                    // I file del dispositivo non hanno un link da cui riscaricarli
                    dao.getTracks(p.id).filterNot { it.isLocal }.forEach { t ->
                        tracks.put(JSONObject().put("title", t.title).put("url", t.sourceUrl).put("duration", t.durationSec).put("thumbnail", t.thumbnailUrl))
                    }
                    arr.put(JSONObject().put("name", p.name).put("figureId", p.figureId).put("color", p.color).put("favorite", p.favorite).put("tracks", tracks))
                }
                val json = JSONObject().put("app", "cubetto").put("version", 1).put("playlists", arr)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
                true
            }.getOrDefault(false)
        }
        done(ok)
    }

    fun import(uri: Uri, done: (Int?) -> Unit) = viewModelScope.launch {
        val count = withContext(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("playlists")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = dao.insert(Playlist(name = o.getString("name"), figureId = o.getString("figureId"), color = o.optInt("color"), favorite = o.optBoolean("favorite")))
                    val tracks = o.getJSONArray("tracks")
                    dao.insertAll((0 until tracks.length()).map { j ->
                        val t = tracks.getJSONObject(j)
                        Track(playlistId = id, position = j + 1, title = t.getString("title"), sourceUrl = t.getString("url"), durationSec = t.optInt("duration"), thumbnailUrl = t.optString("thumbnail").ifBlank { null })
                    })
                }
                arr.length()
            }.getOrNull()
        }
        if (count != null) DownloadWorker.start(getApplication())
        done(count)
    }

    companion object {
        private val PENDING = setOf(TrackStatus.QUEUED, TrackStatus.DOWNLOADING, TrackStatus.CONVERTING)
    }
}
