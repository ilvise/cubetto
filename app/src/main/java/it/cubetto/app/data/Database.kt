package it.cubetto.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Una statuina personalizzata: una playlist con il suo ID FABA™. */
@Entity
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val figureId: String,
    val color: Int = 0,
    val coverPath: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** ID delle tracce, in ordine, all'ultima copia sul box (per la sync differenziale). */
    val syncedTrackIds: String? = null,
    val syncedAt: Long? = null,
    /** ID con cui la playlist è stata copiata l'ultima volta (per ripulire se cambia). */
    val syncedFigureId: String? = null,
)

enum class TrackStatus { QUEUED, DOWNLOADING, CONVERTING, READY, ERROR }

@Entity(indices = [Index("playlistId")])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String? = null,
    val durationSec: Int = 0,
    val localPath: String? = null,
    val status: TrackStatus = TrackStatus.QUEUED,
    val progress: Int = 0,
    val error: String? = null,
)

@Dao
interface CubettoDao {
    @Query("SELECT * FROM Playlist ORDER BY createdAt DESC")
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT * FROM Playlist WHERE id = :id")
    fun playlist(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM Playlist WHERE id = :id")
    suspend fun getPlaylist(id: Long): Playlist?

    @Query("SELECT * FROM Playlist")
    suspend fun allPlaylists(): List<Playlist>

    @Insert
    suspend fun insert(p: Playlist): Long

    @Update
    suspend fun update(p: Playlist)

    @Delete
    suspend fun delete(p: Playlist)

    @Query("SELECT * FROM Track ORDER BY playlistId, position")
    fun allTracks(): Flow<List<Track>>

    @Query("SELECT * FROM Track WHERE playlistId = :playlistId ORDER BY position")
    fun tracks(playlistId: Long): Flow<List<Track>>

    @Query("SELECT * FROM Track WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getTracks(playlistId: Long): List<Track>

    @Query("SELECT * FROM Track WHERE id = :id")
    suspend fun getTrack(id: Long): Track?

    @Query("SELECT * FROM Track WHERE status IN ('QUEUED','DOWNLOADING','CONVERTING') ORDER BY id LIMIT 1")
    suspend fun nextPending(): Track?

    @Query("SELECT COUNT(*) FROM Track WHERE status IN ('QUEUED','DOWNLOADING','CONVERTING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM Track WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insert(t: Track): Long

    @Insert
    suspend fun insertAll(t: List<Track>)

    @Update
    suspend fun update(t: Track)

    @Update
    suspend fun updateAll(t: List<Track>)

    @Delete
    suspend fun delete(t: Track)

    @Query("UPDATE Track SET status = 'QUEUED', progress = 0 WHERE status IN ('DOWNLOADING','CONVERTING')")
    suspend fun resetInterrupted()

    @Transaction
    suspend fun replaceWithChapters(original: Track, chapters: List<Track>) {
        val siblings = getTracks(original.playlistId)
        val shift = chapters.size - 1
        updateAll(siblings.filter { it.position > original.position }.map { it.copy(position = it.position + shift) })
        delete(original)
        insertAll(chapters.mapIndexed { i, c -> c.copy(position = original.position + i) })
    }
}

@Database(entities = [Playlist::class, Track::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CubettoDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "cubetto.db").build()
    }
}
