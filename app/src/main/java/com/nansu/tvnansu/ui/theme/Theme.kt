package com.nansu.tvnansu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    val Bg = Color(0xFF0A0A0D)
    val Surface = Color(0xFF17171C)
    val Surface2 = Color(0xFF23232B)
    val Surface3 = Color(0xFF2E2E38)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFFA6A6B0)
    val TextFaint = Color(0xFF6E6E7A)
    val Accent = Color(0xFFEF4E58)
    val AccentSoft = Color(0x33EF4E58)
    val Focus = Color(0xFFFFFFFF)
    val Live = Color(0xFFEF4E58)
}

object Type {
    val Display = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val Section = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val Label = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
}

@Composable
fun TVnansuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.White,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface2,
            onSurfaceVariant = Palette.TextDim,
        ),
        content = content,
    )
}
