package com.hispanoiptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hispanoiptv.tv.ui.components.LoadingView
import com.hispanoiptv.tv.ui.nav.Dest
import com.hispanoiptv.tv.ui.nav.LocalNavigator
import com.hispanoiptv.tv.ui.nav.Navigator
import com.hispanoiptv.tv.ui.player.PlayerScreen
import com.hispanoiptv.tv.ui.screens.AddPlaylistScreen
import com.hispanoiptv.tv.ui.screens.CategoryGridScreen
import com.hispanoiptv.tv.ui.screens.LiveGuideScreen
import com.hispanoiptv.tv.ui.screens.MainScreen
import com.hispanoiptv.tv.ui.screens.MovieDetailScreen
import com.hispanoiptv.tv.ui.screens.PlatformScreen
import com.hispanoiptv.tv.ui.screens.ProfileEditScreen
import com.hispanoiptv.tv.ui.screens.ProfilePickerScreen
import com.hispanoiptv.tv.ui.screens.SeriesDetailScreen
import com.hispanoiptv.tv.ui.screens.SyncScreen
import com.hispanoiptv.tv.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun AppRoot() {
    val repo = LocalRepository.current
    var start by remember { mutableStateOf<Dest?>(null) }

    LaunchedEffect(Unit) {
        val active = repo.active()
        repo.activeProfile()
        start = when {
            active == null -> Dest.AddPlaylist(first = true)
            active.lastSync == 0L -> Dest.Sync
            repo.profileCount() > 1 -> Dest.Profiles
            else -> Dest.Main
        }
        if (active != null && active.lastSync != 0L) repo.refreshIfStale()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
    ) {
        val s = start
        if (s == null) {
            LoadingView()
            return@Box
        }
        val nav = remember(s) { Navigator(s) }
        val holder = rememberSaveableStateHolder()
        CompositionLocalProvider(LocalNavigator provides nav) {
            BackHandler(enabled = nav.stack.size > 1) {
                nav.pop()?.let { holder.removeState(it.key) }
            }
            LaunchedEffect(nav.stack.size) {
                delay(1_500)
                nav.restorePending = false
            }
            val current = nav.current
            holder.SaveableStateProvider(current.key) {
                when (current) {
                    Dest.Main -> MainScreen()
                    is Dest.AddPlaylist -> AddPlaylistScreen(first = current.first)
                    Dest.Sync -> SyncScreen()
                    Dest.Profiles -> ProfilePickerScreen()
                    is Dest.ProfileEdit -> ProfileEditScreen(current.id)
                    is Dest.Movie -> MovieDetailScreen(current.remoteId)
                    is Dest.Series -> SeriesDetailScreen(current.remoteId)
                    is Dest.Grid -> CategoryGridScreen(current.kind, current.categoryId, current.title)
                    is Dest.Platform -> PlatformScreen(current.name)
                    is Dest.LiveGuide -> LiveGuideScreen(current.start)
                    is Dest.Player -> PlayerScreen(current.request)
                }
            }
        }
    }
}
