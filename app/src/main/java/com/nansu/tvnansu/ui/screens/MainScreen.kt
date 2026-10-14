package com.nansu.tvnansu.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.SyncState
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalEdge
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.LocalTopInset
import com.nansu.tvnansu.ui.components.LineIcon
import com.nansu.tvnansu.ui.components.LogoMark
import com.nansu.tvnansu.ui.components.NavIcon
import com.nansu.tvnansu.ui.components.PillShape
import com.nansu.tvnansu.ui.components.ProfileAvatar
import com.nansu.tvnansu.ui.components.Spinner
import com.nansu.tvnansu.ui.nav.Dest
import com.nansu.tvnansu.ui.nav.LocalNavigator
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import com.nansu.tvnansu.ui.toast
import kotlinx.coroutines.delay

enum class MainTab(val label: String, val icon: NavIcon) {
    HOME("Inicio", NavIcon.HOME),
    LIVE("En vivo", NavIcon.LIVE),
    MOVIES("Películas", NavIcon.MOVIES),
    SERIES("Series", NavIcon.SERIES),
    SEARCH("Buscar", NavIcon.SEARCH),
    SETTINGS("Ajustes", NavIcon.SETTINGS),
}

/** Lets a screen inside Main jump to another section (e.g. Home → En vivo). */
val LocalSelectTab = staticCompositionLocalOf<(MainTab) -> Unit> { {} }

private val RAIL_CLOSED = 64.dp
private val RAIL_OPEN = 232.dp
private val CONTENT_EDGE = 96.dp

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repo = LocalRepository.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedTab by remember { mutableIntStateOf(tabIndex) }
    var railHasFocus by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    var pendingContentFocus by remember { mutableIntStateOf(0) }
    val tabRequesters = remember { MainTab.entries.map { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }
    val tabHolder = rememberSaveableStateHolder()
    val reduceMotion = repo.prefs.reduceMotion

    // Switch content shortly after a rail item gains focus.
    LaunchedEffect(focusedTab) {
        if (focusedTab != tabIndex) {
            delay(180)
            tabIndex = focusedTab
        }
    }

    // First launch: start inside Home's content, not on the rail.
    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            delay(250)
            if (runCatching { contentRequester.requestFocus() }.isFailure) {
                runCatching { tabRequesters[tabIndex].requestFocus() }
            }
            initialFocusDone = true
        }
    }

    LaunchedEffect(pendingContentFocus) {
        if (pendingContentFocus == 0) return@LaunchedEffect
        delay(220)
        runCatching { contentRequester.requestFocus() }
    }

    fun selectTab(t: MainTab) {
        focusedTab = t.ordinal
        tabIndex = t.ordinal
        pendingContentFocus++
    }

    BackHandler {
        when {
            !railHasFocus -> runCatching { tabRequesters[tabIndex].requestFocus() }
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

    val selectTabFn = remember { { t: MainTab -> selectTab(t) } }
    val tab = MainTab.entries[tabIndex]
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalEdge provides CONTENT_EDGE,
            LocalTopInset provides 64.dp,
            LocalSelectTab provides selectTabFn,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentRequester)
                    .focusRestorer()
                    .focusGroup()
            ) {
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
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Palette.Bg.copy(alpha = 0.92f),
                        0.6f to Palette.Bg.copy(alpha = 0.6f),
                        1f to Color.Transparent,
                    )
                )
        )
        Header(
            title = tab.label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CONTENT_EDGE, end = 40.dp, top = 20.dp),
        )

        SideRail(
            current = tabIndex,
            open = railHasFocus,
            reduceMotion = reduceMotion,
            requesters = tabRequesters,
            onFocusTab = { focusedTab = it },
            onClickTab = { selectTab(MainTab.entries[it]) },
            onFocusChanged = { railHasFocus = it },
            onRight = {
                if (focusedTab != tabIndex) tabIndex = focusedTab
                pendingContentFocus++
            },
        )
    }
}

@Composable
private fun SideRail(
    current: Int,
    open: Boolean,
    reduceMotion: Boolean,
    requesters: List<FocusRequester>,
    onFocusTab: (Int) -> Unit,
    onClickTab: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onRight: () -> Unit,
) {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val width by animateDpAsState(
        targetValue = if (open) RAIL_OPEN else RAIL_CLOSED,
        animationSpec = tween(if (reduceMotion) 0 else 320),
        label = "rail",
    )
    val profileId by repo.profileId.collectAsState()
    val profiles by repo.profiles.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == profileId }

    Box(
        Modifier
            .fillMaxHeight()
            .width(if (open) RAIL_OPEN + 120.dp else RAIL_CLOSED + 40.dp)
            .background(
                if (open) Brush.horizontalGradient(
                    0f to Color(0xF70C0C10),
                    0.66f to Color(0xF70C0C10),
                    1f to Color.Transparent,
                ) else Brush.horizontalGradient(
                    0f to Palette.Bg,
                    0.45f to Palette.Bg.copy(alpha = 0.75f),
                    1f to Color.Transparent,
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .padding(vertical = 26.dp)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                        onRight()
                        true
                    } else false
                }
                .focusRestorer()
                .focusGroup(),
        ) {
            Box(Modifier.padding(start = 19.dp, bottom = 34.dp)) {
                LogoMark(size = 26.dp)
            }
            MainTab.entries.forEachIndexed { i, t ->
                RailItem(
                    label = t.label,
                    selected = i == current,
                    open = open,
                    modifier = Modifier
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) onFocusTab(i) },
                    onClick = { onClickTab(i) },
                ) { color -> LineIcon(t.icon, color, size = 22.dp) }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (profile != null) {
                RailItem(
                    label = profile.name,
                    selected = false,
                    open = open,
                    onClick = { nav.push(Dest.Profiles) },
                ) { _ -> ProfileAvatar(profile.name, profile.avatar, 26.dp) }
            }
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 10.dp)) {
        // Accent mark on the current section.
        if (selected && !focused) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .size(width = 3.dp, height = 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.Accent)
            )
        }
        Surface(
            onClick = onClick,
            modifier = modifier
                .padding(start = 0.dp)
                .fillMaxWidth()
                .height(44.dp)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape = PillShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Palette.Text else Palette.TextDim,
                focusedContainerColor = Palette.Focus,
                focusedContentColor = Color.Black,
            ),
        ) {
            val color = when {
                focused -> Color.Black
                selected -> Palette.Text
                else -> Palette.TextDim
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(color)
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(tween(220, delayMillis = 80)) + slideInHorizontally { -it / 4 },
                    exit = fadeOut(tween(120)) + slideOutHorizontally { -it / 4 },
                ) {
                    Text(
                        text = label,
                        style = Type.Section,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 18.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.Title, color = Palette.Text)
        Spacer(Modifier.width(14.dp))
        Text(Fmt.longDate(now), style = Type.Label, color = Palette.TextFaint)
        Spacer(Modifier.weight(1f))
        val running = (sync as? SyncState.Running)?.message ?: (epg as? SyncState.Running)?.message
        if (running != null) {
            Spinner(size = 14)
            Spacer(Modifier.width(8.dp))
            Text(running, style = Type.Caption, color = Palette.TextDim, maxLines = 1)
            Spacer(Modifier.width(18.dp))
        }
        Text(Fmt.clock(now), style = Type.Title, color = Palette.Text)
    }
}
