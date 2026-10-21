package com.hispanoiptv.tv.data

import com.hispanoiptv.tv.data.db.EpisodeEntity
import com.hispanoiptv.tv.data.db.Kind

/** One playable item. Live queues hold every channel of a category so the user can zap. */
data class PlayItem(
    val uid: String,
    val kind: Kind,
    val remoteId: String,
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val image: String? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seriesImage: String? = null,
    val epgId: String? = null,
    val number: Int = 0,
)

data class PlayRequest(
    val items: List<PlayItem>,
    val index: Int,
    val startPositionMs: Long = 0,
)

data class MovieDetails(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val duration: String?,
    val year: String?,
    val rating: Float,
    val backdrop: String?,
    val poster: String?,
)

data class Season(val number: Int, val name: String, val episodes: List<EpisodeEntity>)

data class SeriesDetails(
    val plot: String?,
    val cast: String?,
    val genre: String?,
    val year: String?,
    val rating: Float,
    val backdrop: String?,
    val poster: String?,
    val seasons: List<Season>,
)

data class NowNext(
    val nowTitle: String?,
    val nowStart: Long,
    val nowStop: Long,
    val nowDescription: String?,
    val nextTitle: String?,
    val nextStart: Long,
)

/** Something airing right now that deserves a place on Home. */
data class LiveEvent(
    val channel: com.hispanoiptv.tv.data.db.ChannelEntity,
    val title: String,
    val start: Long,
    val stop: Long,
    /** "DEPORTES", "EVENTO" or "NOTICIAS". */
    val label: String,
)

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}
