package com.nansu.tvnansu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Colour avatars with the profile's initial. Index = ProfileEntity.avatar.
 * Illustrated avatars can be added later as extra entries.
 */
object Avatars {
    val gradients: List<Pair<Color, Color>> = listOf(
        Color(0xFFEF4E58) to Color(0xFFA9243A),
        Color(0xFFFF9F43) to Color(0xFFD9602A),
        Color(0xFFF7C948) to Color(0xFFCC8F17),
        Color(0xFF3DD68C) to Color(0xFF1B8F5A),
        Color(0xFF2EC4D6) to Color(0xFF16779A),
        Color(0xFF5B8DEF) to Color(0xFF2F4BBE),
        Color(0xFFA66CFF) to Color(0xFF6236C9),
        Color(0xFFFF6FB5) to Color(0xFFBF3480),
    )

    fun of(index: Int) = gradients[((index % gradients.size) + gradients.size) % gradients.size]
}

@Composable
fun ProfileAvatar(name: String, avatar: Int, size: Dp, modifier: Modifier = Modifier) {
    val (top, bottom) = Avatars.of(avatar)
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "?" },
            style = TextStyle(
                fontSize = (size.value * 0.46f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            ),
        )
    }
}
