package com.nansu.tvnansu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.PivotScroll
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

/** Space kept above the focused card: the row title plus room for the focus zoom. */
val ROW_TOP_MARGIN = 56.dp

/** A titled horizontal shelf of cards. */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(bottom = 14.dp)) {
        Text(
            text = title,
            style = Type.Section,
            color = Palette.Text,
            modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
        )
        PivotScroll(48.dp) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRestorer(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/** Debounced hero: follows focus but waits a moment so fast scrolling stays smooth. */
@Stable
class HeroState {
    var target by mutableStateOf<HeroData?>(null)
    var shown by mutableStateOf<HeroData?>(null)
}

@Composable
fun rememberHeroState(delayMs: Long = 220): HeroState {
    val state = remember { HeroState() }
    val target = state.target
    LaunchedEffect(target) {
        if (state.shown == null) {
            state.shown = target
        } else {
            delay(delayMs)
            state.shown = target
        }
    }
    return state
}

/** Full-bleed hero on top and a vertical list of shelves underneath. */
@Composable
fun HeroScaffold(
    hero: HeroData?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val reduceMotion = LocalRepository.current.prefs.reduceMotion
    Box(modifier.fillMaxSize()) {
        HeroBackdrop(image = hero?.image, wide = hero?.wide == true, reduceMotion = reduceMotion)
        Column(Modifier.fillMaxSize()) {
            HeroText(
                data = hero,
                modifier = Modifier
                    .padding(start = 48.dp, top = 84.dp, end = 400.dp)
                    .height(176.dp),
            )
            PivotScroll(ROW_TOP_MARGIN) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
                    content = content,
                )
            }
        }
    }
}
