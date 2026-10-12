package com.nansu.tvnansu.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.SyncState
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.Spinner
import com.nansu.tvnansu.ui.components.TabPill
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import com.nansu.tvnansu.ui.toast
import kotlinx.coroutines.delay

enum class MainTab(val label: String) {
    HOME("Inicio"),
    LIVE("En vivo"),
    MOVIES("Películas"),
    SERIES("Series"),
    SEARCH("Buscar"),
    SETTINGS("Ajustes"),
}

@Composable
fun MainScreen() {
    val repo = LocalRepository.current
    val context = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedTab by remember { mutableIntStateOf(tabIndex) }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    val tabRequesters = remember { MainTab.entries.map { FocusRequester() } }
    val tabHolder = rememberSaveableStateHolder()

    // Switch content shortly after a tab gains focus (Google TV behaviour).
    LaunchedEffect(focusedTab) {
        if (focusedTab != tabIndex) {
            delay(180)
            tabIndex = focusedTab
        }
    }

    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            delay(60)
            runCatching { tabRequesters[tabIndex].requestFocus() }
            initialFocusDone = true
        }
    }

    BackHandler {
        when {
            !tabsHaveFocus -> runCatching { tabRequesters[tabIndex].requestFocus() }
            tabIndex != 0 -> {
                focusedTab = 0
                tabIndex = 0
                runCatching { tabRequesters[0].requestFocus() }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) {
                    (context as? Activity)?.finish()
                } else {
                    lastBack = now
                    context.toast("Pulsa atrás otra vez para salir")
                }
            }
        }
    }

    val tab = MainTab.entries[tabIndex]
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            tabHolder.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.HOME -> HomeScreen()
                    MainTab.LIVE -> LiveScreen()
                    MainTab.MOVIES -> BrowseScreen(isSeries = false)
                    MainTab.SERIES -> BrowseScreen(isSeries = true)
                    MainTab.SEARCH -> SearchScreen()
                    MainTab.SETTINGS -> SettingsScreen()
                }
            }
        }

        // Top bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Palette.Bg.copy(alpha = 0.92f),
                        1f to Color.Transparent,
                    )
                )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TVnansu", style = Type.Title, color = Palette.Text)
                Spacer(Modifier.width(36.dp))
                Row(
                    Modifier
                        .focusRestorer()
                        .focusGroup()
                        .onFocusChanged { tabsHaveFocus = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MainTab.entries.forEachIndexed { i, t ->
                        TabPill(
                            text = t.label,
                            selected = i == tabIndex,
                            onClick = {
                                focusedTab = i
                                tabIndex = i
                            },
                            modifier = Modifier
                                .focusRequester(tabRequesters[i])
                                .onFocusChanged { if (it.isFocused) focusedTab = i },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                StatusArea()
            }
        }
    }
}

@Composable
private fun StatusArea() {
    val repo = LocalRepository.current
    val sync by repo.syncState.collectAsState()
    val epg by repo.epgState.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val running = (sync as? SyncState.Running)?.message ?: (epg as? SyncState.Running)?.message
        if (running != null) {
            Spinner(size = 16)
            Spacer(Modifier.width(8.dp))
            Text(running, style = Type.Caption, color = Palette.TextDim, maxLines = 1)
            Spacer(Modifier.width(18.dp))
        }
        Text(Fmt.clock(now), style = Type.Section, color = Palette.Text)
    }
}
