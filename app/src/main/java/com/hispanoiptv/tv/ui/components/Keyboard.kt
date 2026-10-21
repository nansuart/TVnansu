package com.hispanoiptv.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type

private val rows = listOf(
    "abcdef",
    "ghijkl",
    "mnopqr",
    "stuvwx",
    "yzñ123",
    "456789",
)

/** On-screen keyboard for search, driven entirely by the D-pad. */
@Composable
fun SearchKeyboard(
    onChar: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKeyRequester: FocusRequester? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, ch ->
                    val m = if (r == 0 && c == 0 && firstKeyRequester != null) {
                        Modifier.focusRequester(firstKeyRequester)
                    } else Modifier
                    Key(label = ch.toString(), onClick = { onChar(ch) }, modifier = m)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(label = "0", onClick = { onChar('0') })
            Key(label = "espacio", onClick = onSpace, width = 112.dp)
            Key(label = "⌫", onClick = onDelete, width = 111.dp)
        }
        Key(label = "Borrar todo", onClick = onClear, width = 276.dp)
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 41.dp) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(41.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(label, style = Type.Section, modifier = Modifier.align(Alignment.Center))
    }
}
