package it.cubetto.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import it.cubetto.app.R
import it.cubetto.app.data.CubettoDao
import it.cubetto.app.data.Playlist
import it.cubetto.app.data.Track
import it.cubetto.app.data.TrackStatus
import it.cubetto.app.faba.Mp3Frames
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BoxFigure(val id: String, val files: Int, val bytes: Long)

/** [scanning]: lettura delle cartelle in corso ([scanned] su [total]); [figures] cresce man mano. */
data class BoxInfo(
    val label: String,
    val capacity: Long,
    val free: Long,
    val figures: List<BoxFigure>,
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
)

sealed interface BoxState {
    data object Disconnected : BoxState
    data object NeedsPermission : BoxState
    data object Connecting : BoxState
    data class Connected(val info: BoxInfo) : BoxState
    /** Chiuso dall'utente: si può scollegare. Si riapre solo ricollegandolo o con "Ricollega". */
    data object Ejected : BoxState
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
 * che Android monti il dispositivo. I brani vanno in MKI01/K{ID}/CPnn.MKI, con nomi
 * corti 8.3 veri (vedi [FatShortNames]).
 */
class FabaUsb(private val context: Context, private val dao: CubettoDao) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    // Un errore USB (es. box scollegato a metà lettura) non deve mai far chiudere l'app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "usb", e) })
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BoxState>(BoxState.Disconnected)
    val state: StateFlow<BoxState> = _state.asStateFlow()
    private val _sync = MutableStateFlow<SyncProgress?>(null)
    val sync: StateFlow<SyncProgress?> = _sync.asStateFlow()

    private var device: UsbMassStorageDevice? = null
    private var fs: FileSystem? = null
    private var partition: BlockDeviceDriver? = null

    /** Messo a true appena arriva lo scollegamento, prima di avere il lock: interrompe la lettura. */
    @Volatile private var detached = false

    /** Chiamato quando ci sono brani da ricodificare prima di poterli copiare. */
    var onNeedsReencode: (() -> Unit)? = null

    /** Chiamato quando il box viene collegato e il permesso è già concesso. */
    var onConnected: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val ours = device?.usbDevice
                    if (ours != null && gone != null && gone.deviceName != ours.deviceName) return
                    detached = true
                    _state.value = BoxState.Disconnected
                    scope.launch { mutex.withLock { closeLocked() } }
                }
                else -> refresh(force = true)
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

    /**
     * Apre il box se collegato e ne legge il contenuto; dopo un'espulsione riapre solo con [force]
     * (collegamento, permesso). Se è già aperto rilegge solo con [rescan] ("Rileggi"): il
     * collegamento arriva sia come broadcast sia come intent all'activity, e prima ognuno dei
     * due rileggeva tutto il box.
     */
    fun refresh(force: Boolean = false, rescan: Boolean = false) {
        scope.launch {
            var newlyConnected = false
            mutex.withLock {
                if (_state.value == BoxState.Ejected && !force) return@withLock
                if (fs != null && _state.value is BoxState.Connected) {
                    if (rescan) scanSafelyLocked()
                    return@withLock
                }
                if (!openLocked()) return@withLock
                newlyConnected = scanSafelyLocked()
            }
            if (newlyConnected) onConnected?.invoke()
        }
    }

    /**
     * Apertura con più tentativi. Appena acceso il FABA™ spesso non è pronto e libaums,
     * riprovando, riusa il buffer già avanzato: "newLimit > capacity: (49 > 36)".
     * Si ricomincia quindi da zero con un oggetto dispositivo nuovo.
     */
    private suspend fun openLocked(): Boolean {
        var last: Throwable? = null
        for (attempt in 1..OPEN_ATTEMPTS) {
            val candidate = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull()
            if (candidate == null) {
                closeLocked()
                return false
            }
            if (!usbManager.hasPermission(candidate.usbDevice)) {
                _state.value = BoxState.NeedsPermission
                return false
            }
            _state.value = BoxState.Connecting
            detached = false
            try {
                candidate.init()
                // Dopo l'espulsione il supporto resta "tolto" finché non si stacca il cavo
                if (candidate.partitions.isEmpty()) {
                    runCatching { candidate.close() }
                    _state.value = BoxState.Error(context.getString(R.string.box_replug))
                    return false
                }
                device = candidate
                val first = candidate.partitions.first()
                partition = first
                fs = first.fileSystem
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "open attempt $attempt", e)
                last = e
                runCatching { candidate.close() }
                device = null; fs = null; partition = null
                delay(attempt * 700L)
            }
        }
        _state.value = BoxState.Error(last?.message ?: last?.javaClass?.simpleName.orEmpty())
        return false
    }

    /** Legge il box; se la lettura fallisce riapre il dispositivo e ritenta una volta. */
    private suspend fun scanSafelyLocked(): Boolean {
        repeat(2) { attempt ->
            try {
                scanLocked()
                return !detached
            } catch (e: Throwable) {
                Log.w(TAG, "scan attempt $attempt", e)
                if (detached) {
                    closeLocked()
                    return false
                }
                runCatching { device?.close() }
                device = null; fs = null; partition = null
                if (attempt == 0 && !openLocked()) return false
            }
        }
        closeLocked()
        _state.value = BoxState.Error("Lettura del box non riuscita")
        return false
    }

    /**
     * Espulsione sicura: aspetta la fine di copie e letture, fa scrivere al box la sua cache,
     * lo espelle come fa un computer e solo dopo chiude il dispositivo.
     */
    suspend fun eject() = withContext(Dispatchers.IO) {
        mutex.withLock {
            partition?.let { p -> runCatching { ScsiEject.eject(p) }.onFailure { Log.w(TAG, "eject", it) } }
            closeLocked()
            _state.value = BoxState.Ejected
        }
    }

    fun requestPermission() {
        val candidate = UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull() ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        usbManager.requestPermission(candidate.usbDevice, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    private fun flushLocked() {
        partition?.let { p -> runCatching { ScsiEject.syncCache(p) }.onFailure { Log.w(TAG, "sync cache", it) } }
    }

    private fun closeLocked() {
        runCatching { device?.close() }
        device = null
        fs = null
        partition = null
        _state.value = BoxState.Disconnected
    }

    /**
     * Lettura progressiva: prima spazio e capacità, poi una cartella alla volta, così la
     * schermata Box si riempie mentre legge. Le cartelle con il solo segnaposto non contano.
     */
    private fun scanLocked() {
        val fs = fs ?: return
        val info = BoxInfo(fs.volumeLabel.trim(), fs.capacity, fs.freeSpace, emptyList(), scanning = true)
        _state.value = BoxState.Connected(info)
        val fast = runCatching { fastScanLocked(info) }.onFailure { Log.w(TAG, "fast scan", it) }.getOrDefault(false)
        if (!fast && !detached) libaumsScanLocked(info)
    }

    /**
     * Lettura diretta del FAT32 con [FatScanner]. False se serve la lettura con libaums:
     * niente MKI01 con il nome giusto, o cartelle scritte da vecchie versioni da riparare.
     */
    private fun fastScanLocked(start: BoxInfo): Boolean {
        val scanner = FatScanner(partition ?: return false)
        val mki = scanner.mkiEntry(MKI_DIR) ?: return false
        if (mki.lfn) return false
        var info = start
        val found = mutableListOf<BoxFigure>()
        var repair = false
        scanner.scan(mki, FIGURE_DIR, cancelled = { detached || repair }, onTotal = { total ->
            info = info.copy(total = total)
            _state.value = BoxState.Connected(info)
        }) { folder ->
            if (folder.needsRepair) {
                repair = true
                return@scan
            }
            if (folder.bytes > PLACEHOLDER_MAX_BYTES) {
                found += BoxFigure(folder.name.substring(1), folder.files, folder.bytes)
                found.sortBy { it.id }
            }
            info = info.copy(figures = found.toList(), scanned = info.scanned + 1)
            _state.value = BoxState.Connected(info)
        }
        if (repair || detached) return detached
        _state.value = BoxState.Connected(info.copy(scanning = false))
        return true
    }

    /** Lettura con libaums, cartella per cartella: più lenta, ma ripara i nomi corti sbagliati. */
    private fun libaumsScanLocked(start: BoxInfo) {
        val fs = fs ?: return
        var info = start.copy(figures = emptyList(), scanned = 0)
        _state.value = BoxState.Connected(info)
        val mki = fs.rootDirectory.search(MKI_DIR)
        mki?.let(::repairShortNames)
        val dirs = mki?.listFiles().orEmpty()
            .filter { it.isDirectory && FIGURE_DIR.matches(it.name) }
            .sortedBy { it.name }
        info = info.copy(total = dirs.size)
        _state.value = BoxState.Connected(info)
        val found = mutableListOf<BoxFigure>()
        dirs.forEachIndexed { i, dir ->
            if (detached) return
            figureOf(dir)?.let { found += it }
            info = info.copy(figures = found.toList(), scanned = i + 1)
            _state.value = BoxState.Connected(info)
        }
        _state.value = BoxState.Connected(info.copy(scanning = false))
    }

    /**
     * Le versioni precedenti scrivevano nomi corti generati da libaums, che il FABA™ non
     * trova: si correggono alla lettura. Sui nomi già giusti (file originali) non scrive nulla.
     */
    private fun repairShortNames(file: UsbFile) {
        runCatching { FatShortNames.fix(file) }.onFailure { Log.w(TAG, "short name ${file.name}", it) }
    }

    /** La statuina nella cartella, o null se c'è solo il segnaposto (≤ [PLACEHOLDER_MAX_BYTES]). */
    private fun figureOf(dir: UsbFile): BoxFigure? {
        repairShortNames(dir)
        val files = dir.listFiles().filter { !it.isDirectory }
        files.filter { CP_FILE.matches(it.name) }.forEach(::repairShortNames)
        val bytes = files.sumOf { it.length }
        if (bytes <= PLACEHOLDER_MAX_BYTES) return null
        return BoxFigure(dir.name.substring(1), files.size, bytes)
    }

    /** Dopo una scrittura si rilegge solo la cartella toccata, non tutto il box. */
    private fun updateFiguresLocked(mki: UsbFile, vararg ids: String) {
        val fs = fs ?: return
        val info = (_state.value as? BoxState.Connected)?.info ?: return scanLocked()
        val updated = ids.mapNotNull { id -> mki.search("K$id")?.let(::figureOf) }
        val figures = (info.figures.filter { it.id !in ids } + updated).sortedBy { it.id }
        _state.value = BoxState.Connected(info.copy(free = fs.freeSpace, figures = figures))
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
            // I brani in un formato che il box non riproduce (VBR) tornano in coda per la ricodifica
            val (tracks, wrongFormat) = readyTracks(dao.getTracks(playlist.id)).partition { Mp3Frames.isFabaCompatible(File(it.localPath!!)) }
            if (wrongFormat.isNotEmpty()) {
                wrongFormat.forEach { dao.update(it.copy(status = TrackStatus.QUEUED, progress = 0)) }
                onNeedsReencode?.invoke()
            }
            val id = playlist.figureId
            _sync.value = SyncProgress(playlist.name, 0, tracks.size)
            if (tracks.isEmpty()) return@withLock fail(SyncError.NO_TRACKS)
            if (tracks.size > MAX_TRACKS) return@withLock fail(SyncError.TOO_MANY_TRACKS)
            try {
                val root = fs.rootDirectory
                val mki = root.search(MKI_DIR) ?: root.createDirectory(MKI_DIR)
                val oldId = playlist.syncedFigureId
                val ours = oldId == id && playlist.syncedTrackIds != null
                val writer = FigureWriter(mki, id)
                var backupDone = false
                // Se l'ID è cambiato la vecchia cartella era nostra e si toglie; se la cartella non è
                // nostra si svuota del tutto, dopo il backup (non del solo segnaposto da ~360 KB)
                writer.prepare(oldId, ours) { folder ->
                    val files = folder.listFiles().filter { !it.isDirectory }
                    if (files.sumOf { it.length } > PLACEHOLDER_MAX_BYTES) {
                        backupLocked(fs, folder, id)
                        backupDone = true
                    }
                }
                val prevIds = if (ours) playlist.syncedTrackIds!!.split(",") else emptyList()
                val plan = writer.plan(tracks.map { File(it.localPath!!) }) { i -> prevIds.getOrNull(i) == tracks[i].id.toString() }
                val needed = writer.neededBytes(plan)
                _sync.value = _sync.value?.copy(backupDone = backupDone, neededBytes = needed)
                if (needed > fs.freeSpace) return@withLock fail(SyncError.NO_SPACE)

                var skipped = 0
                writer.write(plan) { i, copied ->
                    _sync.value = _sync.value?.copy(current = i + 1, skipped = skipped)
                    if (!copied) skipped++
                }
                dao.update(
                    playlist.copy(
                        syncedFigureId = id,
                        syncedTrackIds = tracks.joinToString(",") { it.id.toString() },
                        syncedAt = System.currentTimeMillis(),
                    )
                )
                // Anche se poi il box viene staccato senza "Espelli", i dati sono scritti davvero
                flushLocked()
                _sync.value = _sync.value?.copy(current = tracks.size, skipped = skipped, finished = true)
                updateFiguresLocked(mki, *listOfNotNull(id, oldId).toTypedArray())
                true
            } catch (e: Throwable) {
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
            runCatching {
                val mki = fs.rootDirectory.search(MKI_DIR) ?: return@withLock 0
                val dirs = mki.listFiles().filter { it.isDirectory && FIGURE_DIR.matches(it.name) && figureOf(it) != null }
                dirs.forEach { backupLocked(fs, it, it.name.substring(1)) }
                dirs.size
            }.getOrElse {
                Log.e(TAG, "backup", it)
                0
            }
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
                FatShortNames.fix(mki)
                mki.search("K${b.figureId}")?.delete()
                val folder = mki.createDirectory("K${b.figureId}")
                FatShortNames.fix(folder)
                b.dir.listFiles().orEmpty().sortedBy { it.name }.forEach { src ->
                    val f = folder.createFile(src.name.uppercase())
                    FatShortNames.fix(f)
                    UsbFileStreamFactory.createBufferedOutputStream(f, fs).use { out -> src.inputStream().use { it.copyTo(out) } }
                }
                // Le playlist che usavano quell'ID non sono più sul box
                dao.allPlaylists().filter { it.syncedFigureId == b.figureId }
                    .forEach { dao.update(it.copy(syncedFigureId = null, syncedTrackIds = null)) }
                flushLocked()
                updateFiguresLocked(mki, b.figureId)
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

        /** Ogni cartella ha un file segnaposto da ~360 KB: sotto questa soglia la cartella è "vuota". */
        const val PLACEHOLDER_MAX_BYTES = 400 * 1024L
        private const val OPEN_ATTEMPTS = 4
        private const val ACTION_PERMISSION = "it.cubetto.app.USB_PERMISSION"
        private val FIGURE_DIR = Regex("""K\d{4}""")
        private val CP_FILE = Regex("""CP(\d{2})(\.MKI)?""", RegexOption.IGNORE_CASE)
    }
}
