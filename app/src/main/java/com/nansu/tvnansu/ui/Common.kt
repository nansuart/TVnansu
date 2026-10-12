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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalRepository = staticCompositionLocalOf<Repository> { error("Repository not provided") }

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

object Fmt {
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun clock(ms: Long): String = if (ms <= 0) "" else clockFormat.format(Date(ms))

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
    LaunchedEffect(Unit) {
        if (memory.value == key) {
            delay(30)
            runCatching { requester.requestFocus() }
        }
    }
    this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.value = key }
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
