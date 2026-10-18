package it.cubetto.app

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(val trackId: Long, val title: String, val subtitle: String, val playing: Boolean)

/** Anteprima audio dei brani scaricati, per il mini player. */
class PreviewPlayer {
    private var player: MediaPlayer? = null
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    fun play(trackId: Long, path: String, title: String, subtitle: String) {
        if (_now.value?.trackId == trackId) {
            toggle()
            return
        }
        release()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { _now.value = _now.value?.copy(playing = false) }
                prepare()
                start()
            }
            _now.value = NowPlaying(trackId, title, subtitle, true)
        }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        _now.value = _now.value?.copy(playing = p.isPlaying)
    }

    fun stop() {
        release()
        _now.value = null
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
    }
}
