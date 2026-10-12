package com.nansu.tvnansu.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Kind { LIVE, MOVIE, SERIES, EPISODE }

object SourceType {
    const val XTREAM = "XTREAM"
    const val M3U = "M3U"
}

/** Key for per-profile rows (progress, favourites, recents). */
fun uidOf(profileId: Long, playlistId: Long, kind: Kind, remoteId: String) =
    "$profileId|$playlistId:${kind.name}:$remoteId"

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the avatar palette (see ProfileAvatar). */
    val avatar: Int,
    val createdAt: Long,
)

/** Where the opening titles of a series start and end, learned from the user. */
@Entity(tableName = "intro_marker")
data class IntroMarkerEntity(
    /** "playlistId:seriesId" */
    @PrimaryKey val seriesKey: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
)

@Entity(
    tableName = "category",
    indices = [Index(value = ["playlistId", "kind"])]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: String,
    val remoteId: String,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "channel",
    indices = [
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "remoteId"]),
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val url: String,
    val number: Int,
    val catchup: Boolean,
)

@Entity(
    tableName = "movie",
    indices = [
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "added"]),
        Index(value = ["playlistId", "remoteId"]),
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val categoryId: String,
    val rating: Float,
    val year: String?,
    val added: Long,
    val url: String,
    val plot: String?,
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["playlistId", "categoryId"]),
        Index(value = ["playlistId", "added"]),
        Index(value = ["playlistId", "remoteId"]),
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val categoryId: String,
    val rating: Float,
    val year: String?,
    val added: Long,
    val plot: String?,
    val genre: String?,
)

@Entity(
    tableName = "episode",
    indices = [Index(value = ["playlistId", "seriesId"])]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val seriesId: String,
    val remoteId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val image: String?,
    val plot: String?,
    val durationSecs: Int,
)

@Entity(
    tableName = "programme",
    indices = [
        Index(value = ["playlistId", "channelEpgId", "start"]),
        Index(value = ["playlistId", "start"]),
    ]
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelEpgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val playlistId: Long,
    val kind: String,
    val remoteId: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val seriesId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val playlistId: Long,
    val kind: String,
    val remoteId: String,
    val title: String,
    val image: String?,
    val addedAt: Long,
)

@Entity(tableName = "recent_channel")
data class RecentChannelEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val playlistId: Long,
    val remoteId: String,
    val watchedAt: Long,
)
