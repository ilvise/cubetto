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
import it.cubetto.app.data.isLocal
import it.cubetto.app.data.localSource
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
        if (track.isLocal) return convertLocal(track)
        if (track.localPath != null && File(track.localPath).exists()) return reencode(track)
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
                val file = result.files.first()
                dao.update(current.copy(localPath = file.absolutePath, status = TrackStatus.READY, progress = 100, durationSec = current.durationSec.takeIf { it > 0 } ?: durationOf(file)))
            }
        } catch (e: Exception) {
            dao.getTrack(track.id)?.let { dao.update(it.copy(status = TrackStatus.ERROR, error = e.message?.take(300))) }
        }
    }

    /** File scelto dal dispositivo: sempre ricodificato e normalizzato con ffmpeg. */
    private suspend fun convertLocal(track: Track) {
        dao.update(track.copy(status = TrackStatus.CONVERTING, progress = 0, error = null))
        val dir = File(applicationContext.filesDir, "audio/${track.id}")
        val options = container.settings.state.value.audio
        var lastSaved = -1
        try {
            val source = track.localSource!!
            val out = container.converter.convert(source, dir, track.title, track.durationSec, options, track.id.toString()) { pct ->
                if (pct - lastSaved >= 5) {
                    lastSaved = pct
                    kotlinx.coroutines.runBlocking { dao.getTrack(track.id)?.let { dao.update(it.copy(progress = pct)) } }
                    runCatching { setForegroundAsync(foregroundInfo(track.title, pct)) }
                }
            }
            val current = dao.getTrack(track.id) ?: return
            source.delete()
            dao.update(current.copy(localPath = out.absolutePath, status = TrackStatus.READY, progress = 100, durationSec = durationOf(out)))
        } catch (e: Exception) {
            dao.getTrack(track.id)?.let { dao.update(it.copy(status = TrackStatus.ERROR, error = e.message?.take(300))) }
        }
    }

    /**
     * Brano già scaricato ma nel formato sbagliato (VBR, prima della v0.2): si ricodifica
     * il file che c'è già, senza riscaricarlo.
     */
    private suspend fun reencode(track: Track) {
        dao.update(track.copy(status = TrackStatus.CONVERTING, progress = 0, error = null))
        val tmp = File(applicationContext.filesDir, "reencode/${track.id}.mp3").apply { parentFile?.mkdirs() }
        try {
            File(track.localPath!!).copyTo(tmp, overwrite = true)
            val dir = File(applicationContext.filesDir, "audio/${track.id}")
            val options = container.settings.state.value.audio
            var lastSaved = -1
            val out = container.converter.convert(tmp, dir, track.title, track.durationSec, options, track.id.toString()) { pct ->
                if (pct - lastSaved >= 5) {
                    lastSaved = pct
                    kotlinx.coroutines.runBlocking { dao.getTrack(track.id)?.let { dao.update(it.copy(progress = pct)) } }
                }
            }
            val current = dao.getTrack(track.id) ?: return
            if (current.localPath != out.absolutePath) File(current.localPath!!).delete()
            dao.update(current.copy(localPath = out.absolutePath, status = TrackStatus.READY, progress = 100))
        } catch (e: Exception) {
            dao.getTrack(track.id)?.let { dao.update(it.copy(status = TrackStatus.ERROR, error = e.message?.take(300))) }
        } finally {
            tmp.delete()
        }
    }

    private fun durationOf(file: File) = AudioMeta.read(file).durationSec

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
