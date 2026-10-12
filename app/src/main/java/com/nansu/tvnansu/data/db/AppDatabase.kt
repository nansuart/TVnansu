package com.nansu.tvnansu.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        ProfileEntity::class,
        IntroMarkerEntity::class,
        SeriesMetaEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /**
         * v1 → v2: profiles and intro markers. Existing progress, favourites and
         * recents move to the default profile (id 1); playlists are untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `avatar` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO `profile` (`id`, `name`, `avatar`, `createdAt`) VALUES (1, 'Principal', 0, ${System.currentTimeMillis()})")
                for (table in listOf("progress", "favorite", "recent_channel")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `profileId` INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("UPDATE `$table` SET `uid` = '1|' || `uid`")
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS `intro_marker` (`seriesKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, PRIMARY KEY(`seriesKey`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_programme_playlistId_start` ON `programme` (`playlistId`, `start`)")
            }
        }

        /** v2 → v3: streaming platform per series. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `series_meta` (`seriesKey` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `seriesId` TEXT NOT NULL, `platform` TEXT, `source` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`seriesKey`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_meta_playlistId_platform` ON `series_meta` (`playlistId`, `platform`)")
            }
        }

        /** Fresh installs start with one profile. */
        val SEED_PROFILE = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT OR IGNORE INTO `profile` (`id`, `name`, `avatar`, `createdAt`) VALUES (1, 'Principal', 0, ${System.currentTimeMillis()})")
            }
        }
    }
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

    @Query("SELECT * FROM progress WHERE profileId = :prof AND playlistId = :pid AND kind IN ('MOVIE', 'SERIES') ORDER BY updatedAt DESC LIMIT :limit")
    fun continueFlow(prof: Long, pid: Long, limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE profileId = :prof AND playlistId = :pid AND kind = 'EPISODE' AND seriesId = :sid")
    suspend fun episodeProgress(prof: Long, pid: Long, sid: String): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE uid = :uid")
    suspend fun deleteProgress(uid: String)

    // ---- Favorites ----
    @Upsert
    suspend fun upsertFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE uid = :uid")
    suspend fun deleteFavorite(uid: String)

    @Query("SELECT * FROM favorite WHERE profileId = :prof AND playlistId = :pid ORDER BY addedAt DESC")
    fun favoritesFlow(prof: Long, pid: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE profileId = :prof AND playlistId = :pid AND kind = :kind ORDER BY addedAt DESC")
    suspend fun favorites(prof: Long, pid: Long, kind: String): List<FavoriteEntity>

    @Query("SELECT uid FROM favorite WHERE profileId = :prof AND playlistId = :pid")
    fun favoriteUidsFlow(prof: Long, pid: Long): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM favorite WHERE uid = :uid")
    suspend fun isFavorite(uid: String): Int

    // ---- Recent channels ----
    @Upsert
    suspend fun upsertRecent(r: RecentChannelEntity)

    @Query("SELECT * FROM recent_channel WHERE profileId = :prof AND playlistId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    fun recentFlow(prof: Long, pid: Long, limit: Int): Flow<List<RecentChannelEntity>>

    @Query("SELECT * FROM recent_channel WHERE profileId = :prof AND playlistId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recents(prof: Long, pid: Long, limit: Int): List<RecentChannelEntity>

    // ---- Profiles ----
    @Query("SELECT * FROM profile ORDER BY id")
    fun profilesFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile ORDER BY id")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun profile(id: Long): ProfileEntity?

    @Insert
    suspend fun insertProfile(p: ProfileEntity): Long

    @Update
    suspend fun updateProfile(p: ProfileEntity)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("DELETE FROM progress WHERE profileId = :prof")
    suspend fun clearProfileProgress(prof: Long)

    @Query("DELETE FROM favorite WHERE profileId = :prof")
    suspend fun clearProfileFavorites(prof: Long)

    @Query("DELETE FROM recent_channel WHERE profileId = :prof")
    suspend fun clearProfileRecents(prof: Long)

    // ---- Series platforms ----
    @Upsert
    suspend fun upsertSeriesMeta(items: List<SeriesMetaEntity>)

    @Query("SELECT seriesKey FROM series_meta WHERE playlistId = :pid AND (platform IS NOT NULL OR checkedAt > :since)")
    suspend fun settledMetaKeys(pid: Long, since: Long): List<String>

    @Query("SELECT remoteId, name, year, categoryId FROM series WHERE playlistId = :pid ORDER BY added DESC, id DESC")
    suspend fun seriesLite(pid: Long): List<SeriesLite>

    @Query("SELECT s.*, m.platform AS platform FROM series s JOIN series_meta m ON m.seriesKey = (s.playlistId || ':' || s.remoteId) WHERE s.playlistId = :pid AND m.platform IS NOT NULL ORDER BY s.added DESC, s.id DESC")
    suspend fun seriesWithPlatform(pid: Long): List<SeriesWithPlatform>

    @Query("SELECT s.*, m.platform AS platform FROM series s JOIN series_meta m ON m.seriesKey = (s.playlistId || ':' || s.remoteId) WHERE s.playlistId = :pid AND m.platform = :platform ORDER BY s.added DESC, s.id DESC")
    suspend fun seriesOfPlatform(pid: Long, platform: String): List<SeriesWithPlatform>

    @Query("SELECT COUNT(*) FROM series_meta WHERE playlistId = :pid AND platform IS NOT NULL")
    suspend fun classifiedCount(pid: Long): Int

    @Query("DELETE FROM series_meta WHERE playlistId = :pid")
    suspend fun clearSeriesMeta(pid: Long)

    @Query("DELETE FROM series_meta WHERE playlistId = :pid AND source = 'tvmaze' AND platform IS NULL")
    suspend fun clearUnmatchedMeta(pid: Long)

    // ---- Movie index for collections ----
    @Query("SELECT * FROM movie WHERE playlistId = :pid")
    suspend fun allMovies(pid: Long): List<MovieEntity>

    // ---- Intro markers ----
    @Query("SELECT * FROM intro_marker WHERE seriesKey = :key")
    suspend fun introMarker(key: String): IntroMarkerEntity?

    @Upsert
    suspend fun upsertIntroMarker(m: IntroMarkerEntity)

    @Query("DELETE FROM intro_marker WHERE seriesKey = :key")
    suspend fun deleteIntroMarker(key: String)

    // ---- Live now (home) ----
    @Query("SELECT * FROM programme WHERE playlistId = :pid AND start <= :now AND stop > :now")
    suspend fun allNowPlaying(pid: Long, now: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND epgId IN (:epgIds) ORDER BY number, id")
    suspend fun channelsByEpg(pid: Long, epgIds: List<String>): List<ChannelEntity>

    @Query("SELECT c.* FROM channel c JOIN category k ON k.playlistId = c.playlistId AND k.kind = 'LIVE' AND k.remoteId = c.categoryId WHERE c.playlistId = :pid AND (lower(k.name) LIKE '%evento%' OR lower(k.name) LIKE '%event%' OR lower(k.name) LIKE '%ppv%') ORDER BY c.number, c.id LIMIT 200")
    suspend fun eventChannels(pid: Long): List<ChannelEntity>

    @Query("SELECT c.* FROM channel c JOIN category k ON k.playlistId = c.playlistId AND k.kind = 'LIVE' AND k.remoteId = c.categoryId WHERE c.playlistId = :pid AND (lower(k.name) LIKE '%noticia%' OR lower(k.name) LIKE '%news%' OR lower(c.name) LIKE '%noticia%' OR lower(c.name) LIKE '%news%' OR lower(c.name) LIKE '%24h%' OR lower(c.name) LIKE '%24 h%') ORDER BY c.number, c.id LIMIT 60")
    suspend fun newsChannels(pid: Long): List<ChannelEntity>
}
