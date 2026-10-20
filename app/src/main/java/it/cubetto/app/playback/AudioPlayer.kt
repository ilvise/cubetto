package it.cubetto.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import it.cubetto.app.data.Playlist
import it.cubetto.app.data.Track
import it.cubetto.app.data.TrackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class QueueItem(val trackId: Long, val title: String)

enum class RepeatMode { OFF, ALL, ONE }

data class NowPlaying(
    val trackId: Long,
    val title: String,
    val playlistId: Long,
    val playlistName: String,
    val color: Int,
    val coverPath: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val index: Int,
    val queue: List<QueueItem>,
    val shuffle: Boolean,
    val repeat: RepeatMode,
)

/**
 * Player delle playlist: parla con [PlaybackService] tramite un MediaController
 * (va usato sul main thread) e pubblica lo stato per la UI.
 */
class AudioPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (player.isPlaying) startTicker()
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { return block(it) }
        pending += block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                pending.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            pending.toList().also { pending.clear() }.forEach { it(c) }
            refresh()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Riproduce i brani pronti della playlist partendo da [startId] (o dal primo). */
    fun play(playlist: Playlist, tracks: List<Track>, startId: Long?) {
        val ready = tracks.filter { it.status == TrackStatus.READY && it.localPath != null }
        if (ready.isEmpty()) return
        val now = _now.value
        if (now != null && now.playlistId == playlist.id && (startId == null || startId == now.trackId) &&
            now.queue.map { it.trackId } == ready.map { it.id }
        ) {
            toggle()
            return
        }
        val start = ready.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        withController { c ->
            c.setMediaItems(ready.map { item(it, playlist) }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    private fun item(t: Track, p: Playlist): MediaItem {
        val cover = p.coverPath?.let(::File)?.takeIf { it.exists() }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(p.name)
            .setAlbumTitle(p.name)
            .setArtworkUri(cover?.let { Uri.fromFile(it) })
            .setExtras(bundleOf(KEY_PLAYLIST to p.id, KEY_COLOR to p.color, KEY_COVER to p.coverPath))
            .build()
        return MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(Uri.fromFile(File(t.localPath!!)))
            .setMediaMetadata(meta)
            .build()
    }

    fun toggle() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) c.pause() else c.play()
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { c -> c.seekTo(index, 0L); c.play() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Toglie dalla coda un brano eliminato. */
    fun remove(trackId: Long) {
        val c = controller ?: return
        (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == trackId.toString() }?.let { c.removeMediaItem(it) }
        if (c.mediaItemCount == 0) stop()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        _now.value = null
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && controller?.isPlaying == true) {
                refresh()
                delay(500)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null || c.mediaItemCount == 0) {
            _now.value = null
            return
        }
        val extras = item.mediaMetadata.extras
        val previous = _now.value
        val queue = if (previous != null && previous.queue.size == c.mediaItemCount && previous.queue.getOrNull(c.currentMediaItemIndex)?.trackId == item.mediaId.toLongOrNull()) {
            previous.queue
        } else {
            (0 until c.mediaItemCount).map { i ->
                val m = c.getMediaItemAt(i)
                QueueItem(m.mediaId.toLongOrNull() ?: -1, m.mediaMetadata.title?.toString().orEmpty())
            }
        }
        _now.value = NowPlaying(
            trackId = item.mediaId.toLongOrNull() ?: -1,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            playlistId = extras?.getLong(KEY_PLAYLIST) ?: -1,
            playlistName = item.mediaMetadata.artist?.toString().orEmpty(),
            color = extras?.getInt(KEY_COLOR) ?: 0,
            coverPath = extras?.getString(KEY_COVER),
            playing = c.playWhenReady && c.playbackState != Player.STATE_ENDED,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            index = c.currentMediaItemIndex,
            queue = queue,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
        )
    }

    private companion object {
        const val KEY_PLAYLIST = "playlistId"
        const val KEY_COLOR = "color"
        const val KEY_COVER = "cover"
    }
}
