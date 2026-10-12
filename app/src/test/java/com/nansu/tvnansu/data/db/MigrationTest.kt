package com.nansu.tvnansu.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a database with the exact v1 schema (as installed by versions 0.1.x)
 * and checks that the v1 → v2 migration keeps playlists and passes Room's
 * schema validation. A failure here would mean a crash on update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {

    private val v1 = listOf(
        "CREATE TABLE IF NOT EXISTS `playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `server` TEXT NOT NULL, `username` TEXT NOT NULL, `password` TEXT NOT NULL, `m3uUrl` TEXT NOT NULL, `epgUrl` TEXT NOT NULL, `lastSync` INTEGER NOT NULL, `lastEpgSync` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_category_playlistId_kind` ON `category` (`playlistId`, `kind`)",
        "CREATE TABLE IF NOT EXISTS `channel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `logo` TEXT, `categoryId` TEXT NOT NULL, `epgId` TEXT, `url` TEXT NOT NULL, `number` INTEGER NOT NULL, `catchup` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_channel_playlistId_categoryId` ON `channel` (`playlistId`, `categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_channel_playlistId_remoteId` ON `channel` (`playlistId`, `remoteId`)",
        "CREATE TABLE IF NOT EXISTS `movie` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `poster` TEXT, `backdrop` TEXT, `categoryId` TEXT NOT NULL, `rating` REAL NOT NULL, `year` TEXT, `added` INTEGER NOT NULL, `url` TEXT NOT NULL, `plot` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_movie_playlistId_categoryId` ON `movie` (`playlistId`, `categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_movie_playlistId_added` ON `movie` (`playlistId`, `added`)",
        "CREATE INDEX IF NOT EXISTS `index_movie_playlistId_remoteId` ON `movie` (`playlistId`, `remoteId`)",
        "CREATE TABLE IF NOT EXISTS `series` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `poster` TEXT, `backdrop` TEXT, `categoryId` TEXT NOT NULL, `rating` REAL NOT NULL, `year` TEXT, `added` INTEGER NOT NULL, `plot` TEXT, `genre` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_series_playlistId_categoryId` ON `series` (`playlistId`, `categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_series_playlistId_added` ON `series` (`playlistId`, `added`)",
        "CREATE INDEX IF NOT EXISTS `index_series_playlistId_remoteId` ON `series` (`playlistId`, `remoteId`)",
        "CREATE TABLE IF NOT EXISTS `episode` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `seriesId` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `image` TEXT, `plot` TEXT, `durationSecs` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_episode_playlistId_seriesId` ON `episode` (`playlistId`, `seriesId`)",
        "CREATE TABLE IF NOT EXISTS `programme` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `channelEpgId` TEXT NOT NULL, `start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_programme_playlistId_channelEpgId_start` ON `programme` (`playlistId`, `channelEpgId`, `start`)",
        "CREATE TABLE IF NOT EXISTS `progress` (`uid` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `image` TEXT, `url` TEXT NOT NULL, `seriesId` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
        "CREATE TABLE IF NOT EXISTS `favorite` (`uid` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `title` TEXT NOT NULL, `image` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
        "CREATE TABLE IF NOT EXISTS `recent_channel` (`uid` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `watchedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'v1-hash')",
    )

    @Test
    fun migrate1To2KeepsDataAndPassesValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v1.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO playlist (id, name, type, server, username, password, m3uUrl, epgUrl, lastSync, lastEpgSync) VALUES (1, 'Mi lista', 'XTREAM', 'http://x', 'u', 'p', '', '', 100, 0)")
            db.execSQL("INSERT INTO favorite (uid, playlistId, kind, remoteId, title, image, addedAt) VALUES ('1:MOVIE:55', 1, 'MOVIE', '55', 'Peli', NULL, 1)")
            db.execSQL("INSERT INTO progress (uid, playlistId, kind, remoteId, title, subtitle, image, url, seriesId, positionMs, durationMs, updatedAt) VALUES ('1:MOVIE:55', 1, 'MOVIE', '55', 'Peli', NULL, NULL, 'http://x/m.mp4', NULL, 60000, 600000, 1)")
            db.execSQL("INSERT INTO recent_channel (uid, playlistId, remoteId, watchedAt) VALUES ('1:LIVE:9', 1, '9', 1)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .addCallback(AppDatabase.SEED_PROFILE)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val dao = room.dao()
                assertEquals(1, dao.playlists().size)
                assertEquals(1, dao.profiles().size)
                assertEquals(1L, dao.profiles().first().id)
                val favs = dao.favorites(1L, 1L, "MOVIE")
                assertEquals(1, favs.size)
                assertEquals(uidOf(1L, 1L, Kind.MOVIE, "55"), favs.first().uid)
                assertTrue(dao.progress(uidOf(1L, 1L, Kind.MOVIE, "55")) != null)
                assertEquals(1, dao.recents(1L, 1L, 10).size)
                assertEquals(0, dao.favorites(2L, 1L, "MOVIE").size)
                dao.upsertIntroMarker(IntroMarkerEntity("1:s1", 1_000, 60_000))
                assertEquals(60_000L, dao.introMarker("1:s1")?.endMs)
                dao.allNowPlaying(1L, System.currentTimeMillis())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun freshInstallHasDefaultProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SEED_PROFILE)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking { assertEquals(1, room.dao().profiles().size) }
        } finally {
            room.close()
        }
    }
}
