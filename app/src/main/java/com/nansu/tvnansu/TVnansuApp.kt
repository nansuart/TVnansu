package com.nansu.tvnansu

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.nansu.tvnansu.data.Prefs
import com.nansu.tvnansu.data.Repository
import com.nansu.tvnansu.data.db.AppDatabase
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class TVnansuApp : Application(), SingletonImageLoader.Factory {

    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "tvnansu.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .addCallback(AppDatabase.SEED_PROFILE)
            .fallbackToDestructiveMigration(true)
            .build()
        repository = Repository(db, http, Prefs(this))
        repository.collections.dir = java.io.File(filesDir, "colecciones").apply { mkdirs() }
        repository.collections.bundled = { assets.open("colecciones.json").bufferedReader().use { it.readText() } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
}
