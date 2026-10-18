package it.cubetto.app.ytdlp

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.cubetto.app.CubettoApp
import it.cubetto.app.R
import it.cubetto.app.data.Track
import it.cubetto.app.data.TrackStatus
import java.io.File

/** Scarica in background, uno alla volta, tutti i brani in coda. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as CubettoApp).container
    private val dao = container.db.dao()

    override suspend fun doWork(): Result {
        runCatching { setForeground(foregroundInfo(applicationContext.getString(R.string.notif_downloading), 0)) }
        container.ytDlp.updateIfNeeded()
        while (true) {
            val track = dao.nextPending() ?: break
            process(track)
        }
        notifyDone()
        return Result.success()
    }

    private suspend fun process(track: Track) {
        dao.update(track.copy(status = TrackStatus.DOWNLOADING, progress = 0, error = null))
        val dir = File(applicationContext.filesDir, "audio/${track.id}")
        val options = container.settings.state.value.audio
        var lastSaved = -1
        try {
            val result = container.ytDlp.downloadMp3(track.sourceUrl, dir, track.durationSec, options, track.id.toString()) { progress, _ ->
                val pct = progress.toInt().coerceIn(0, 100)
                if (pct != lastSaved && (pct - lastSaved >= 5 || pct == 100)) {
                    lastSaved = pct
                    val status = if (pct >= 100) TrackStatus.CONVERTING else TrackStatus.DOWNLOADING
                    kotlinx.coroutines.runBlocking { dao.getTrack(track.id)?.let { dao.update(it.copy(progress = pct, status = status)) } }
                    runCatching { setForegroundAsync(foregroundInfo(track.title, pct)) }
                }
            }
            val current = dao.getTrack(track.id) ?: return // brano eliminato nel frattempo
            if (result.files.size > 1) {
                val chapters = result.files.mapIndexed { i, f ->
                    val name = result.chapters.getOrNull(i)?.title?.takeIf { it.isNotBlank() } ?: "${track.title} (${i + 1})"
                    current.copy(id = 0, title = name, localPath = f.absolutePath, status = TrackStatus.READY, progress = 100, durationSec = 0)
                }
                dao.replaceWithChapters(current, chapters)
            } else {
                dao.update(current.copy(localPath = result.files.first().absolutePath, status = TrackStatus.READY, progress = 100))
            }
        } catch (e: Exception) {
            dao.getTrack(track.id)?.let { dao.update(it.copy(status = TrackStatus.ERROR, error = e.message?.take(300))) }
        }
    }

    private fun foregroundInfo(text: String, progress: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CubettoApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_downloading))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, progress == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun notifyDone() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(applicationContext, CubettoApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_done_title))
            .setContentText(applicationContext.getString(R.string.notif_done_text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID + 1, n) }
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val WORK = "downloads"

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>().build(),
            )
        }
    }
}
