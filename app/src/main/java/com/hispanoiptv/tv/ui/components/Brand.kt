package com.hispanoiptv.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hispanoiptv.tv.R

private const val WORDMARK_RATIO = 4592f / 970f
private const val MARK_RATIO = 1433f / 970f

/** Full "Hispano IPTV" logo with the tagline, for welcome and loading screens. */
@Composable
fun LogoFull(height: Dp = 56.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_full),
        contentDescription = "Hispano IPTV",
        modifier = modifier
            .height(height)
            .aspectRatio(WORDMARK_RATIO),
    )
}

/** The TV of the logo alone (side rail, loading). */
@Composable
fun LogoMark(size: Dp = 26.dp, modifier: Modifier = Modifier, white: Boolean = false) {
    Image(
        painter = painterResource(if (white) R.drawable.logo_mark_white else R.drawable.logo_mark),
        contentDescription = "Hispano IPTV",
        modifier = modifier
            .height(size)
            .aspectRatio(MARK_RATIO),
    )
}

/** Logo without the tagline, readable at small sizes (side rail, watermark). */
@Composable
fun LogoCompact(height: Dp = 30.dp, modifier: Modifier = Modifier, white: Boolean = false) {
    Image(
        painter = painterResource(if (white) R.drawable.logo_compact_white else R.drawable.logo_compact),
        contentDescription = "Hispano IPTV",
        modifier = modifier
            .height(height)
            .aspectRatio(WORDMARK_RATIO),
    )
}
