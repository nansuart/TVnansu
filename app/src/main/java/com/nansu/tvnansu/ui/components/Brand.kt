package com.nansu.tvnansu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nansu.tvnansu.R

/** Full "nansu tv" wordmark, for welcome and loading screens. */
@Composable
fun LogoFull(height: Dp = 56.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_full),
        contentDescription = "nansu tv",
        modifier = modifier
            .height(height)
            .aspectRatio(250.21f / 80.11f),
    )
}

/** Compact mark + "tv" pill, readable at small sizes (top bar). */
@Composable
fun LogoCompact(height: Dp = 30.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_compact),
        contentDescription = "nansu tv",
        modifier = modifier
            .height(height)
            .aspectRatio(112.11f / 52.63f),
    )
}
