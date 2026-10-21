package com.hispanoiptv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.hispanoiptv.tv.R

/** Hispano IPTV colours: purple to orange, on near-black surfaces with a hint of purple. */
object Palette {
    val Bg = Color(0xFF0B0A10)
    val Surface = Color(0xFF16141D)
    val Surface2 = Color(0xFF221E2B)
    val Surface3 = Color(0xFF2E2939)
    val Text = Color(0xFFF6F4FA)
    val TextDim = Color(0xFFAEA8BC)
    val TextFaint = Color(0xFF746E82)
    val Purple = Color(0xFF880BEB)
    val Orange = Color(0xFFF47F13)
    val Accent = Orange
    val AccentSoft = Color(0x33F47F13)
    val Focus = Color(0xFFFFFFFF)
    val Live = Orange
}

/** The brand gradient (purple bottom-left to orange top-right). */
object Brand {
    val Gradient: Brush = Brush.linearGradient(listOf(Palette.Purple, Color(0xFFC4497A), Palette.Orange))
    val Horizontal: Brush = Brush.horizontalGradient(listOf(Palette.Purple, Color(0xFFC4497A), Palette.Orange))
    val Vertical: Brush = Brush.verticalGradient(listOf(Palette.Orange, Palette.Purple))
    val Diagonal: Brush = Brush.linearGradient(
        colors = listOf(Palette.Purple, Color(0xFFC4497A), Palette.Orange),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f),
    )
}

val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

object Type {
    val Display = TextStyle(fontFamily = Montserrat, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, letterSpacing = (-0.4).sp)
    val Title = TextStyle(fontFamily = Montserrat, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
    val Section = TextStyle(fontFamily = Montserrat, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = Montserrat, fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = Montserrat, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    val Caption = TextStyle(fontFamily = Montserrat, fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
}

@Composable
fun HispanoTheme(content: @Composable () -> Unit) {
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
    ) {
        CompositionLocalProvider(LocalTextStyle provides Type.Body) { content() }
    }
}
