package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId")]
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val playlistId: Int,
    val videoId: String,
    val displayOrder: Int
)

data class PlaylistWithItems(
    @Embedded val playlist: Playlist,
    @Relation(
        parentColumn = "id",
        entityColumn = "playlistId"
    )
    val items: List<PlaylistItem>
)

@Dao
interface PlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY timestamp DESC")
    fun getAllPlaylistsSync(): List<PlaylistWithItems>

    @Query("SELECT * FROM playlists ORDER BY timestamp DESC")
    fun getAllPlaylistsFlow(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlists ORDER BY timestamp DESC")
    fun getAllPlaylistsWithItemsFlow(): Flow<List<PlaylistWithItems>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSync(playlist: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    fun deletePlaylistSync(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistItemSync(item: PlaylistItem)
    
    @Query("SELECT IFNULL(MAX(displayOrder), -1) FROM playlist_items WHERE playlistId = :playlistId")
    fun getMaxDisplayOrderSync(playlistId: Int): Int

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    fun removePlaylistItemSync(playlistId: Int, videoId: String)
}

@Database(entities = [Playlist::class, PlaylistItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "playlist_database"
                )
                .allowMainThreadQueries() // Allow for simplicity in synchronous API threads
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
