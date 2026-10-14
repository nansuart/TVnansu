package com.nansu.tvnansu.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nansu.tvnansu.data.Repository
import com.nansu.tvnansu.ui.nav.LocalNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalRepository = staticCompositionLocalOf<Repository> { error("Repository not provided") }

/** Left margin of screen content: wider inside the main screen, where the side rail sits. */
val LocalEdge = staticCompositionLocalOf { 48.dp }

/** Space above the first element of a screen. */
val LocalTopInset = staticCompositionLocalOf { 84.dp }

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

object Fmt {
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun clock(ms: Long): String = if (ms <= 0) "" else clockFormat.format(Date(ms))

    private val longDateFormat = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))

    /** "miércoles 14 de octubre" */
    fun longDate(ms: Long): String = longDateFormat.format(Date(ms))

    /** "hace 12 min", "hace 3 h", "ayer". */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - ms) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace $min min"
            min < 24 * 60 -> "hace ${min / 60} h"
            min < 48 * 60 -> "ayer"
            else -> "hace ${min / (24 * 60)} días"
        }
    }

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun minutesLeft(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(1)
        return if (min >= 60) "Quedan ${min / 60} h ${min % 60} min" else "Quedan $min min"
    }
}

/** Saved key of the last focused item on a screen, so focus returns there after going back. */
@Composable
fun rememberFocusMemory(): MutableState<String?> = rememberSaveable { mutableStateOf<String?>(null) }

/**
 * Records focus on this element under [key] and, when the screen is shown again,
 * puts focus back on it if it was the last one focused.
 */
fun Modifier.focusMemory(key: String, memory: MutableState<String?>): Modifier = composed {
    val requester = remember { FocusRequester() }
    val nav = LocalNavigator.current
    LaunchedEffect(Unit) {
        if (memory.value == key && nav.restorePending) {
            delay(30)
            runCatching { requester.requestFocus() }
            nav.restorePending = false
        }
    }
    this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.value = key }
}

/**
 * For the large block at the top of a screen: whenever focus enters it, the list
 * goes back to the very start so the block is seen whole. It runs just after the
 * system's own scroll-to-focus (which would otherwise leave the block cut off) and
 * checks again a moment later.
 */
fun Modifier.keepAtTop(listState: androidx.compose.foundation.lazy.LazyListState): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val inside = remember { mutableStateOf(false) }
    onFocusChanged { st ->
        inside.value = st.hasFocus
        if (st.hasFocus) scope.launch {
            delay(40)
            if (inside.value) listState.animateScrollToItem(0)
            delay(350)
            if (inside.value && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                listState.animateScrollToItem(0)
            }
        }
    }
}

/**
 * Vertical scrolling for screens with a large block on top: nothing moves while the
 * focused item is already fully on screen; otherwise it is brought to [top] from the
 * top edge. The top block itself scrolls back to the very start (see the screens).
 */
@Composable
fun RevealScroll(top: Dp, bottom: Dp = 28.dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    val spec = remember(topPx, bottomPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (offset >= topPx && offset + size <= containerSize - bottomPx) return 0f
                return if (size > containerSize - topPx) offset else offset - topPx
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { content() }
}

/**
 * Keeps the focused child of a scrolling list at a fixed offset from the start,
 * the way Apple TV and Google TV rows move.
 */
@Composable
fun PivotScroll(offset: Dp = 48.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { offset.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val target = px
                return if (size > containerSize - target) offset else offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { content() }
}
