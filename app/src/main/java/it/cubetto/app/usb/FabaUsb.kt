package it.cubetto.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import it.cubetto.app.data.CubettoDao
import it.cubetto.app.data.Playlist
import it.cubetto.app.data.Track
import it.cubetto.app.data.TrackStatus
import it.cubetto.app.faba.Id3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BoxFigure(val id: String, val files: Int, val bytes: Long)

data class BoxInfo(val label: String, val capacity: Long, val free: Long, val figures: List<BoxFigure>)

sealed interface BoxState {
    data object Disconnected : BoxState
    data object NeedsPermission : BoxState
    data object Connecting : BoxState
    data class Connected(val info: BoxInfo) : BoxState
    data class Error(val message: String) : BoxState
}

/** Avanzamento della copia sul box, mostrato nella schermata Box. */
data class SyncProgress(
    val playlistName: String,
    val current: Int,
    val total: Int,
    val backupDone: Boolean = false,
    val neededBytes: Long = 0,
    val skipped: Int = 0,
    val finished: Boolean = false,
    val error: SyncError? = null,
)

enum class SyncError { NO_SPACE, TOO_MANY_TRACKS, NO_TRACKS, IO }

data class Backup(val dir: File, val figureId: String, val date: Long, val files: Int)

/**
 * Accesso al box FABA™ di prima generazione collegato in USB OTG: si presenta come
 * una chiavetta (mass storage FAT32). Usa libaums, quindi non serve root e non serve
 * che Android monti il dispositivo. I brani vanno in MKI01/K{ID}/CPnn.MKI.
 */
class FabaUsb(private val context: Context, private val dao: CubettoDao) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BoxState>(BoxState.Disconnected)
    val state: StateFlow<BoxState> = _state.asStateFlow()
    private val _sync = MutableStateFlow<SyncProgress?>(null)
    val sync: StateFlow<SyncProgress?> = _sync.asStateFlow()

    private var device: UsbMassStorageDevice? = null
    private var fs: FileSystem? = null

    /** Chiamato quando il box viene collegato e il permesso è già concesso. */
    var onConnected: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch { mutex.withLock { closeLocked() } }
                else -> refresh()
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch {
            var newlyConnected = false
            mutex.withLock {
                if (fs != null && _state.value is BoxState.Connected) {
                    readInfoLocked()
                    return@withLock
                }
                val candidate = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull()
                if (candidate == null) {
                    closeLocked()
                    return@withLock
                }
                if (!usbManager.hasPermission(candidate.usbDevice)) {
                    _state.value = BoxState.NeedsPermission
                    return@withLock
                }
                _state.value = BoxState.Connecting
                runCatching {
                    candidate.init()
                    device = candidate
                    fs = candidate.partitions.first().fileSystem
                    readInfoLocked()
                    newlyConnected = true
                }.onFailure {
                    Log.e(TAG, "open", it)
                    runCatching { candidate.close() }
                    device = null; fs = null
                    _state.value = BoxState.Error(it.message ?: it.javaClass.simpleName)
                }
            }
            if (newlyConnected) onConnected?.invoke()
        }
    }

    fun requestPermission() {
        val candidate = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull() ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        usbManager.requestPermission(candidate.usbDevice, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    private fun closeLocked() {
        runCatching { device?.close() }
        device = null
        fs = null
        _state.value = BoxState.Disconnected
    }

    private fun readInfoLocked() {
        val fs = fs ?: return
        val mki = fs.rootDirectory.search(MKI_DIR)
        val figures = mki?.listFiles().orEmpty()
            .filter { it.isDirectory && FIGURE_DIR.matches(it.name) }
            .map { dir ->
                val files = dir.listFiles().filter { !it.isDirectory }
                BoxFigure(dir.name.substring(1), files.size, files.sumOf { it.length })
            }
            .sortedBy { it.id }
        _state.value = BoxState.Connected(BoxInfo(fs.volumeLabel.trim(), fs.capacity, fs.freeSpace, figures))
    }

    fun readyTracks(tracks: List<Track>) =
        tracks.filter { it.status == TrackStatus.READY && it.localPath != null && File(it.localPath).exists() }

    /** True se la playlist ha modifiche non ancora copiate sul box. */
    fun needsSync(p: Playlist, tracks: List<Track>): Boolean {
        val ready = readyTracks(tracks)
        if (ready.isEmpty()) return false
        return p.syncedFigureId != p.figureId || p.syncedTrackIds != ready.joinToString(",") { it.id.toString() }
    }

    /** Copia una playlist sul box: backup, controllo spazio, copia solo dei brani cambiati. */
    suspend fun sync(playlist: Playlist): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fs = fs ?: return@withLock false
            val tracks = readyTracks(dao.getTracks(playlist.id))
            val id = playlist.figureId
            _sync.value = SyncProgress(playlist.name, 0, tracks.size)
            if (tracks.isEmpty()) return@withLock fail(SyncError.NO_TRACKS)
            if (tracks.size > MAX_TRACKS) return@withLock fail(SyncError.TOO_MANY_TRACKS)
            try {
                val root = fs.rootDirectory
                val mki = root.search(MKI_DIR) ?: root.createDirectory(MKI_DIR)

                // Se l'ID è cambiato, la vecchia cartella era nostra: la togliamo
                val oldId = playlist.syncedFigureId
                if (oldId != null && oldId != id) mki.search("K$oldId")?.delete()

                val ours = playlist.syncedFigureId == id && playlist.syncedTrackIds != null
                var folder = mki.search("K$id")
                var backupDone = false
                if (folder != null && !ours) {
                    backupLocked(fs, folder, id)
                    backupDone = true
                    folder.listFiles().forEach { it.delete() }
                }
                if (folder == null) folder = mki.createDirectory("K$id")
                val prevIds = if (ours) playlist.syncedTrackIds!!.split(",") else emptyList()

                val plan = tracks.mapIndexed { i, t ->
                    val name = "CP%02d.MKI".format(i + 1)
                    val size = Id3.encodedSize(File(t.localPath!!), id, i + 1)
                    val existing = folder.search(name)
                    val unchanged = prevIds.getOrNull(i) == t.id.toString() && existing?.length == size
                    Triple(t, name, if (unchanged) -1L else size)
                }
                val needed = plan.sumOf { (_, name, size) -> if (size < 0) 0L else size - (folder.search(name)?.length ?: 0L) }
                _sync.value = _sync.value?.copy(backupDone = backupDone, neededBytes = needed)
                if (needed > fs.freeSpace) return@withLock fail(SyncError.NO_SPACE)

                var skipped = 0
                plan.forEachIndexed { i, (t, name, size) ->
                    _sync.value = _sync.value?.copy(current = i + 1, skipped = skipped)
                    if (size < 0) {
                        skipped++
                        return@forEachIndexed
                    }
                    folder.search(name)?.delete()
                    val file = folder.createFile(name)
                    UsbFileStreamFactory.createBufferedOutputStream(file, fs).use { out ->
                        Id3.writeFaba(File(t.localPath!!), id, i + 1, out)
                    }
                }
                // Tracce in più rimaste da una versione precedente
                folder.listFiles().forEach { f ->
                    val n = CP_FILE.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
                    if (n != null && n > tracks.size) f.delete()
                }
                dao.update(
                    playlist.copy(
                        syncedFigureId = id,
                        syncedTrackIds = tracks.joinToString(",") { it.id.toString() },
                        syncedAt = System.currentTimeMillis(),
                    )
                )
                _sync.value = _sync.value?.copy(current = tracks.size, skipped = skipped, finished = true)
                readInfoLocked()
                true
            } catch (e: Exception) {
                Log.e(TAG, "sync", e)
                fail(SyncError.IO)
            }
        }
    }

    private fun fail(error: SyncError): Boolean {
        _sync.value = _sync.value?.copy(error = error, finished = true)
        return false
    }

    fun clearSyncState() {
        _sync.value = null
    }

    private fun backupLocked(fs: FileSystem, folder: UsbFile, id: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dest = File(backupRoot(), "K${id}_$stamp").apply { mkdirs() }
        folder.listFiles().filter { !it.isDirectory }.forEach { f ->
            UsbFileStreamFactory.createBufferedInputStream(f, fs).use { input ->
                File(dest, f.name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Backup manuale di tutte le statuine presenti sul box. */
    suspend fun backupAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fs = fs ?: return@withLock 0
            val mki = fs.rootDirectory.search(MKI_DIR) ?: return@withLock 0
            val dirs = mki.listFiles().filter { it.isDirectory && FIGURE_DIR.matches(it.name) }
            dirs.forEach { backupLocked(fs, it, it.name.substring(1)) }
            dirs.size
        }
    }

    private fun backupRoot() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backup")

    fun backups(): List<Backup> = backupRoot().listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            val m = Regex("""K(\d{4})_(\d{8}-\d{6})""").matchEntire(dir.name) ?: return@mapNotNull null
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).parse(m.groupValues[2])?.time ?: 0
            Backup(dir, m.groupValues[1], date, dir.listFiles()?.size ?: 0)
        }
        .sortedByDescending { it.date }

    fun deleteBackup(b: Backup) {
        b.dir.deleteRecursively()
    }

    /** Rimette sul box una cartella salvata in precedenza. */
    suspend fun restore(b: Backup): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fs = fs ?: return@withLock false
            runCatching {
                val mki = fs.rootDirectory.search(MKI_DIR) ?: fs.rootDirectory.createDirectory(MKI_DIR)
                mki.search("K${b.figureId}")?.delete()
                val folder = mki.createDirectory("K${b.figureId}")
                b.dir.listFiles().orEmpty().sortedBy { it.name }.forEach { src ->
                    val f = folder.createFile(src.name)
                    UsbFileStreamFactory.createBufferedOutputStream(f, fs).use { out -> src.inputStream().use { it.copyTo(out) } }
                }
                // Le playlist che usavano quell'ID non sono più sul box
                dao.allPlaylists().filter { it.syncedFigureId == b.figureId }
                    .forEach { dao.update(it.copy(syncedFigureId = null, syncedTrackIds = null)) }
                readInfoLocked()
                true
            }.getOrElse {
                Log.e(TAG, "restore", it)
                false
            }
        }
    }

    companion object {
        private const val TAG = "FabaUsb"
        const val MKI_DIR = "MKI01"
        const val MAX_TRACKS = 99
        private const val ACTION_PERMISSION = "it.cubetto.app.USB_PERMISSION"
        private val FIGURE_DIR = Regex("""K\d{4}""")
        private val CP_FILE = Regex("""CP(\d{2})(\.MKI)?""", RegexOption.IGNORE_CASE)
    }
}
