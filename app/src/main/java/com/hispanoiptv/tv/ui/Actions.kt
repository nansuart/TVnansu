package com.hispanoiptv.tv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.hispanoiptv.tv.data.PlayItem
import com.hispanoiptv.tv.data.PlayRequest
import com.hispanoiptv.tv.data.Repository
import com.hispanoiptv.tv.data.db.ChannelEntity
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.data.db.MovieEntity
import com.hispanoiptv.tv.data.db.ProgressEntity
import com.hispanoiptv.tv.data.db.SeriesEntity
import com.hispanoiptv.tv.ui.components.LivePreviewHub
import com.hispanoiptv.tv.ui.nav.Dest
import com.hispanoiptv.tv.ui.nav.LocalNavigator
import com.hispanoiptv.tv.ui.nav.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared user actions so every screen opens and plays things the same way. */
class Actions(
    private val nav: Navigator,
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    val playlistId: Long get() = repo.activeId.value

    fun openMovie(m: MovieEntity) = nav.push(Dest.Movie(m.remoteId))
    fun openMovie(remoteId: String) = nav.push(Dest.Movie(remoteId))
    fun openSeries(s: SeriesEntity) = nav.push(Dest.Series(s.remoteId))
    fun openSeries(remoteId: String) = nav.push(Dest.Series(remoteId))
    fun openGrid(kind: Kind, categoryId: String, title: String) = nav.push(Dest.Grid(kind, categoryId, title))
    fun openPlatform(name: String) = nav.push(Dest.Platform(name))

    fun playChannels(channels: List<ChannelEntity>, index: Int) {
        if (channels.isEmpty()) return
        LivePreviewHub.stop()
        nav.push(Dest.Player(PlayRequest(channels.map { repo.channelItem(it) }, index.coerceIn(0, channels.lastIndex))))
    }

    fun play(items: List<PlayItem>, index: Int, startMs: Long = 0) {
        if (items.isEmpty()) return
        LivePreviewHub.stop()
        nav.push(Dest.Player(PlayRequest(items, index.coerceIn(0, items.lastIndex), startMs)))
    }

    fun playMovie(m: MovieEntity, startMs: Long = 0) = play(listOf(repo.movieItem(m)), 0, startMs)

    /** "Continue watching" entry: movies resume directly, series open their page. */
    fun openProgress(p: ProgressEntity) {
        when (p.kind) {
            Kind.MOVIE.name -> play(
                listOf(
                    PlayItem(
                        uid = p.uid, kind = Kind.MOVIE, remoteId = p.remoteId, title = p.title,
                        subtitle = p.subtitle, url = p.url, image = p.image,
                    )
                ),
                0,
                p.positionMs,
            )
            Kind.SERIES.name -> openSeries(p.remoteId)
        }
    }

    fun toggleFavorite(kind: Kind, remoteId: String, title: String, image: String?) {
        scope.launch {
            val added = repo.toggleFavorite(playlistId, kind, remoteId, title, image)
            context.toast(if (added) "Añadido a Mi lista" else "Quitado de Mi lista")
        }
    }

    fun removeFromContinue(p: ProgressEntity) {
        scope.launch {
            repo.removeFromContinue(p.uid)
            context.toast("Quitado de Seguir viendo")
        }
    }
}

@Composable
fun rememberActions(): Actions {
    val nav = LocalNavigator.current
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(nav, repo, scope, context) { Actions(nav, repo, scope, context) }
}
