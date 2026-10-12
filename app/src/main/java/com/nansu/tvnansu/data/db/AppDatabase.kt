package com.nansu.tvnansu.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProgrammeEntity::class,
        ProgressEntity::class,
        FavoriteEntity::class,
        RecentChannelEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

@Dao
interface AppDao {

    // ---- Playlists ----
    @Query("SELECT * FROM playlist ORDER BY id")
    fun playlistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist ORDER BY id")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: Long): PlaylistEntity?

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(p: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    // ---- Bulk inserts ----
    @Insert
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert
    suspend fun insertChannels(items: List<ChannelEntity>)

    @Insert
    suspend fun insertMovies(items: List<MovieEntity>)

    @Insert
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Insert
    suspend fun insertEpisodes(items: List<EpisodeEntity>)

    @Insert
    suspend fun insertProgrammes(items: List<ProgrammeEntity>)

    // ---- Clearing ----
    @Query("DELETE FROM category WHERE playlistId = :pid")
    suspend fun clearCategories(pid: Long)

    @Query("DELETE FROM channel WHERE playlistId = :pid")
    suspend fun clearChannels(pid: Long)

    @Query("DELETE FROM movie WHERE playlistId = :pid")
    suspend fun clearMovies(pid: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun clearSeries(pid: Long)

    @Query("DELETE FROM episode WHERE playlistId = :pid")
    suspend fun clearEpisodes(pid: Long)

    @Query("DELETE FROM programme WHERE playlistId = :pid")
    suspend fun clearProgrammes(pid: Long)

    @Query("DELETE FROM progress WHERE playlistId = :pid")
    suspend fun clearProgress(pid: Long)

    @Query("DELETE FROM favorite WHERE playlistId = :pid")
    suspend fun clearFavorites(pid: Long)

    @Query("DELETE FROM recent_channel WHERE playlistId = :pid")
    suspend fun clearRecents(pid: Long)

    // ---- Categories ----
    @Query("SELECT * FROM category WHERE playlistId = :pid AND kind = :kind ORDER BY position")
    suspend fun categories(pid: Long, kind: String): List<CategoryEntity>

    // ---- Channels ----
    @Query("SELECT * FROM channel WHERE playlistId = :pid AND categoryId = :cat ORDER BY number, id")
    suspend fun channelsIn(pid: Long, cat: String): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid ORDER BY number, id LIMIT :limit")
    suspend fun allChannels(pid: Long, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND remoteId IN (:ids)")
    suspend fun channelsByIds(pid: Long, ids: List<String>): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid")
    suspend fun channelCount(pid: Long): Int

    @Query("SELECT DISTINCT epgId FROM channel WHERE playlistId = :pid AND epgId IS NOT NULL AND epgId != ''")
    suspend fun epgIds(pid: Long): List<String>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND name LIKE '%' || :q || '%' ORDER BY number, id LIMIT :limit")
    suspend fun searchChannels(pid: Long, q: String, limit: Int): List<ChannelEntity>

    // ---- Movies ----
    @Query("SELECT * FROM movie WHERE playlistId = :pid AND categoryId = :cat ORDER BY added DESC, id LIMIT :limit")
    suspend fun moviesIn(pid: Long, cat: String, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid ORDER BY added DESC, id DESC LIMIT :limit")
    suspend fun recentMovies(pid: Long, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND remoteId = :rid LIMIT 1")
    suspend fun movie(pid: Long, rid: String): MovieEntity?

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND remoteId IN (:ids)")
    suspend fun moviesByIds(pid: Long, ids: List<String>): List<MovieEntity>

    @Query("SELECT COUNT(*) FROM movie WHERE playlistId = :pid")
    suspend fun movieCount(pid: Long): Int

    @Query("UPDATE movie SET backdrop = :backdrop, plot = :plot WHERE playlistId = :pid AND remoteId = :rid")
    suspend fun updateMovieInfo(pid: Long, rid: String, backdrop: String?, plot: String?)

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND name LIKE '%' || :q || '%' ORDER BY added DESC LIMIT :limit")
    suspend fun searchMovies(pid: Long, q: String, limit: Int): List<MovieEntity>

    // ---- Series ----
    @Query("SELECT * FROM series WHERE playlistId = :pid AND categoryId = :cat ORDER BY added DESC, id LIMIT :limit")
    suspend fun seriesIn(pid: Long, cat: String, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid ORDER BY added DESC, id DESC LIMIT :limit")
    suspend fun recentSeries(pid: Long, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND remoteId = :rid LIMIT 1")
    suspend fun series(pid: Long, rid: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE playlistId = :pid AND remoteId IN (:ids)")
    suspend fun seriesByIds(pid: Long, ids: List<String>): List<SeriesEntity>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    suspend fun seriesCount(pid: Long): Int

    @Query("UPDATE series SET backdrop = :backdrop, plot = :plot WHERE playlistId = :pid AND remoteId = :rid")
    suspend fun updateSeriesInfo(pid: Long, rid: String, backdrop: String?, plot: String?)

    @Query("SELECT * FROM series WHERE playlistId = :pid AND name LIKE '%' || :q || '%' ORDER BY added DESC LIMIT :limit")
    suspend fun searchSeries(pid: Long, q: String, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM episode WHERE playlistId = :pid AND seriesId = :sid ORDER BY season, episode, id")
    suspend fun episodes(pid: Long, sid: String): List<EpisodeEntity>

    // ---- EPG ----
    @Query("SELECT * FROM programme WHERE playlistId = :pid AND channelEpgId = :epg AND stop > :now ORDER BY start LIMIT :limit")
    suspend fun upcoming(pid: Long, epg: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT * FROM programme WHERE playlistId = :pid AND channelEpgId IN (:epgIds) AND start <= :now AND stop > :now")
    suspend fun nowPlaying(pid: Long, epgIds: List<String>, now: Long): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programme WHERE playlistId = :pid")
    suspend fun programmeCount(pid: Long): Int

    // ---- Progress ----
    @Upsert
    suspend fun upsertProgress(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE uid = :uid")
    suspend fun progress(uid: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE playlistId = :pid AND kind IN ('MOVIE', 'SERIES') ORDER BY updatedAt DESC LIMIT :limit")
    fun continueFlow(pid: Long, limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE playlistId = :pid AND kind = 'EPISODE' AND seriesId = :sid")
    suspend fun episodeProgress(pid: Long, sid: String): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE uid = :uid")
    suspend fun deleteProgress(uid: String)

    // ---- Favorites ----
    @Upsert
    suspend fun upsertFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE uid = :uid")
    suspend fun deleteFavorite(uid: String)

    @Query("SELECT * FROM favorite WHERE playlistId = :pid ORDER BY addedAt DESC")
    fun favoritesFlow(pid: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE playlistId = :pid AND kind = :kind ORDER BY addedAt DESC")
    suspend fun favorites(pid: Long, kind: String): List<FavoriteEntity>

    @Query("SELECT uid FROM favorite WHERE playlistId = :pid")
    fun favoriteUidsFlow(pid: Long): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM favorite WHERE uid = :uid")
    suspend fun isFavorite(uid: String): Int

    // ---- Recent channels ----
    @Upsert
    suspend fun upsertRecent(r: RecentChannelEntity)

    @Query("SELECT * FROM recent_channel WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    fun recentFlow(pid: Long, limit: Int): Flow<List<RecentChannelEntity>>

    @Query("SELECT * FROM recent_channel WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recents(pid: Long, limit: Int): List<RecentChannelEntity>
}
