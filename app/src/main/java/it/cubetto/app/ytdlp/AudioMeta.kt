package it.cubetto.app.ytdlp

import android.media.MediaMetadataRetriever
import java.io.File

/** Titolo, artista e durata letti dai tag di un file audio. */
data class AudioMeta(val title: String?, val artist: String?, val durationSec: Int) {
    companion object {
        fun read(file: File): AudioMeta {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                AudioMeta(
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null },
                    ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt(),
                )
            } catch (e: Exception) {
                AudioMeta(null, null, 0)
            } finally {
                runCatching { r.release() }
            }
        }
    }
}
