package com.hispanoiptv.tv.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hispanoiptv.tv.data.SyncState
import com.hispanoiptv.tv.ui.Fmt
import com.hispanoiptv.tv.ui.LocalEdge
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.LocalTopInset
import com.hispanoiptv.tv.ui.components.LineIcon
import com.hispanoiptv.tv.ui.components.LivePreviewHub
import com.hispanoiptv.tv.ui.components.LogoCompact
import com.hispanoiptv.tv.ui.components.LogoMark
import com.hispanoiptv.tv.ui.components.NavIcon
import com.hispanoiptv.tv.ui.components.PillShape
import com.hispanoiptv.tv.ui.components.ProfileAvatar
import com.hispanoiptv.tv.ui.components.Spinner
import com.hispanoiptv.tv.ui.components.ThumbnailTour
import com.hispanoiptv.tv.ui.nav.Dest
import com.hispanoiptv.tv.ui.nav.LocalNavigator
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type
import com.hispanoiptv.tv.ui.toast
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

/** Screens set it to true when they scroll down, so the section title slides away. */
val LocalHeaderHidden = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

private val RAIL_CLOSED = 56.dp
private val RAIL_OPEN = 220.dp
private val CONTENT_EDGE = 88.dp

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
    val headerHidden = remember { mutableStateOf(false) }

    // Change section only once the user rests on a menu item (or presses OK), so
    // scrolling through the menu doesn't load every screen on the way.
    LaunchedEffect(focusedTab) {
        if (focusedTab != tabIndex) {
            delay(550)
            tabIndex = focusedTab
        }
    }
    LaunchedEffect(tabIndex) { headerHidden.value = false }

    // Previews stop while the menu is open.
    LaunchedEffect(railHasFocus) { LivePreviewHub.paused.value = railHasFocus }

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
        // Background thumbnail refresh, tucked under the menu.
        Box(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp)) {
            ThumbnailTour(
                enabled = repo.prefs.livePreview && repo.prefs.autoThumbs,
                candidates = { repo.thumbnailCandidates(repo.activeId.value) },
            )
        }

        CompositionLocalProvider(
            LocalEdge provides CONTENT_EDGE,
            LocalTopInset provides 60.dp,
            LocalSelectTab provides selectTabFn,
            LocalHeaderHidden provides headerHidden,
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

        // Section title and clock; slides up out of the way once the screen scrolls.
        val hidden = headerHidden.value
        val headerShift by animateFloatAsState(if (hidden) 1f else 0f, tween(if (reduceMotion) 0 else 280), label = "hdr")
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = 1f - headerShift
                    translationY = -headerShift * 40.dp.toPx()
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Palette.Bg.copy(alpha = 0.85f),
                            0.6f to Palette.Bg.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        )
                    )
            )
            Header(
                title = tab.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CONTENT_EDGE, end = 36.dp, top = 18.dp),
            )
        }

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
        animationSpec = tween(if (reduceMotion) 0 else 320, easing = FastOutSlowInEasing),
        label = "rail",
    )
    val shade by animateFloatAsState(if (open) 1f else 0f, tween(if (reduceMotion) 0 else 320), label = "shade")
    val profileId by repo.profileId.collectAsState()
    val profiles by repo.profiles.collectAsState(initial = emptyList())
    val profile = profiles.firstOrNull { it.id == profileId }

    Box(
        Modifier
            .fillMaxHeight()
            .width(RAIL_CLOSED + 36.dp + (RAIL_OPEN + 84.dp - RAIL_CLOSED - 36.dp) * shade)
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xFF0B0B0E).copy(alpha = 0.92f + 0.05f * shade),
                    (0.42f + 0.25f * shade) to Color(0xFF0B0B0E).copy(alpha = 0.55f + 0.42f * shade),
                    1f to Color.Transparent,
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .padding(vertical = 22.dp)
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
            RailLogo(open = open, reduceMotion = reduceMotion, modifier = Modifier.padding(start = 16.dp, bottom = 30.dp))
            MainTab.entries.forEachIndexed { i, t ->
                RailItem(
                    label = t.label,
                    selected = i == current,
                    open = open,
                    modifier = Modifier
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) onFocusTab(i) },
                    onClick = { onClickTab(i) },
                ) { color -> LineIcon(t.icon, color, size = 20.dp) }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (profile != null) {
                RailItem(
                    label = profile.name,
                    selected = false,
                    open = open,
                    onClick = { nav.push(Dest.Profiles) },
                ) { _ -> ProfileAvatar(profile.name, profile.avatar, 22.dp) }
            }
        }
    }
}

/**
 * The TV of the logo when the menu is closed, the full "Hispano IPTV" logo when it
 * opens: the TV gives a small nod and the wordmark wipes in from the left. Closing
 * plays it back, quicker.
 */
@Composable
private fun RailLogo(open: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val tilt = remember { Animatable(0f) }
    val reveal by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (reduceMotion) tween(0) else tween(
            durationMillis = if (open) 420 else 260,
            delayMillis = if (open) 120 else 0,
            easing = FastOutSlowInEasing,
        ),
        label = "reveal",
    )
    LaunchedEffect(open) {
        if (reduceMotion) return@LaunchedEffect
        tilt.animateTo(if (open) -10f else 6f, tween(140, easing = FastOutSlowInEasing))
        tilt.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    Box(modifier.height(26.dp), contentAlignment = Alignment.CenterStart) {
        val markFade = ((reveal - 0.3f) / 0.7f).coerceIn(0f, 1f)
        LogoMark(
            size = 24.dp,
            modifier = Modifier.graphicsLayer {
                rotationZ = tilt.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                alpha = 1f - markFade
            },
        )
        if (reveal > 0.01f) {
            LogoCompact(
                height = 22.dp,
                modifier = Modifier
                    .offset(x = (-10).dp * (1f - reveal))
                    .graphicsLayer { alpha = reveal.coerceIn(0f, 1f) }
                    .drawWithContent {
                        clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() }
                    },
            )
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
    Box(Modifier.padding(horizontal = 8.dp)) {
        // Accent mark on the current section.
        if (selected && !focused) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 3.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(com.hispanoiptv.tv.ui.theme.Brand.Vertical)
            )
        }
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(38.dp)
                .onFocusChanged { focused = it.isFocused || it.hasFocus },
            shape = ClickableSurfaceDefaults.shape(shape = PillShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Palette.Text else Palette.TextDim,
                focusedContainerColor = Palette.Focus,
                focusedContentColor = Color(0xFF0B0B0E),
            ),
        ) {
            // The surface itself says which colour to use, so text is never white on white.
            val color = LocalContentColor.current
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(color)
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(tween(220, delayMillis = 90)) + slideInHorizontally(tween(260, delayMillis = 60)) { -it / 4 },
                    exit = fadeOut(tween(110)) + slideOutHorizontally(tween(140)) { -it / 4 },
                ) {
                    Text(
                        text = label,
                        style = Type.Section,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, end = 10.dp),
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
        Spacer(Modifier.width(12.dp))
        Text(Fmt.longDate(now), style = Type.Label, color = Palette.TextFaint)
        Spacer(Modifier.weight(1f))
        val running = (sync as? SyncState.Running)?.message ?: (epg as? SyncState.Running)?.message
        if (running != null) {
            Spinner(size = 14)
            Spacer(Modifier.width(8.dp))
            Text(running, style = Type.Caption, color = Palette.TextDim, maxLines = 1)
            Spacer(Modifier.width(16.dp))
        }
        Text(Fmt.clock(now), style = Type.Title, color = Palette.Text)
    }
}
