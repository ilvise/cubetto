package it.cubetto.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import it.cubetto.app.data.AppDatabase
import it.cubetto.app.data.Settings
import it.cubetto.app.faba.FigureRegistry
import it.cubetto.app.nfc.NfcController
import it.cubetto.app.usb.FabaUsb
import it.cubetto.app.ytdlp.DownloadWorker
import it.cubetto.app.ytdlp.LocalConverter
import it.cubetto.app.ytdlp.YtDlpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Dipendenze condivise dall'app (niente framework di DI: bastano queste). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(app)
    val settings = Settings(app)
    val registry = FigureRegistry(app)
    val ytDlp = YtDlpManager(app, settings)
    val converter = LocalConverter(app, ytDlp)
    val usb = FabaUsb(app, db.dao())
    val nfc = NfcController()
    val player = PreviewPlayer()

    /** Copia sul box tutte le playlist con modifiche (usata dalla sync automatica). */
    suspend fun syncAllPending(): Int {
        val dao = db.dao()
        var count = 0
        for (p in dao.allPlaylists()) {
            if (usb.needsSync(p, dao.getTracks(p.id)) && usb.sync(p)) count++
        }
        return count
    }
}

class CubettoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
        container.usb.onConnected = {
            if (container.settings.state.value.autoSync) container.scope.launch { container.syncAllPending() }
        }
        container.scope.launch(Dispatchers.IO) {
            container.registry.load()
            container.db.dao().resetInterrupted()
            container.ytDlp.init()
            if (container.db.dao().pendingCount() > 0) DownloadWorker.start(this@CubettoApp)
            else container.ytDlp.updateIfNeeded()
        }
        container.usb.register()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
