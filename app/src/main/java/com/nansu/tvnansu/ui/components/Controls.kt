package com.nansu.tvnansu.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

val PillShape = RoundedCornerShape(50)

/** Rounded button: white when focused, like tvOS. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    leading: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = ClickableSurfaceDefaults.shape(shape = PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.Surface3 else Palette.Surface2,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
            disabledContainerColor = Palette.Surface,
            disabledContentColor = Palette.TextFaint,
        ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Text(leading, style = Type.Section)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = Type.Section, maxLines = 1)
        }
    }
}

/** Top navigation tab. Selected tab keeps a small accent dot. */
@Composable
fun TabPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(
            text = text,
            style = Type.Label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 18.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Type.Section,
        color = Palette.Text,
        modifier = modifier,
    )
}

/**
 * Text field for the remote. Shows the value as a button; pressing OK switches to
 * real editing with the system keyboard (which also accepts typing from the phone app).
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val fieldRequester = remember { FocusRequester() }
    val buttonRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var returnFocus by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(label, style = Type.Caption, color = Palette.TextDim)
        Spacer(Modifier.height(6.dp))
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            BackHandler { editing = false; returnFocus = true }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.Body.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    returnFocus = true
                    keyboard?.hide()
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Surface2)
                    .border(BorderStroke(2.dp, Palette.Accent), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 13.dp)
                    .focusRequester(fieldRequester)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus && editing) editing = false
                    },
            )
            LaunchedEffect(Unit) {
                delay(50)
                runCatching { fieldRequester.requestFocus() }
                delay(100)
                keyboard?.show()
            }
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRequester(buttonRequester),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Palette.Surface2,
                    contentColor = Palette.Text,
                    focusedContainerColor = Palette.Surface3,
                    focusedContentColor = Palette.Text,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = BorderStroke(2.dp, Palette.Focus),
                        shape = RoundedCornerShape(10.dp),
                    )
                ),
            ) {
                val shown = when {
                    value.isEmpty() -> placeholder
                    password -> "•".repeat(value.length.coerceAtMost(24))
                    else -> value
                }
                Text(
                    text = shown,
                    style = Type.Body,
                    color = if (value.isEmpty()) Palette.TextFaint else Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 16.dp),
                )
            }
            if (returnFocus) {
                LaunchedEffect(Unit) {
                    delay(50)
                    runCatching { buttonRequester.requestFocus() }
                    returnFocus = false
                }
            }
        }
    }
}

/** Two-option segmented control. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(PillShape)
            .background(Palette.Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            TabPill(text = label, selected = i == selected, onClick = { onSelect(i) })
        }
    }
}

/**
 * Loading indicator: the nansu X turning a quarter at a time, with a small wind-up
 * before each turn and a soft overshoot at the end, then a short rest.
 */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Int = 36) {
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val base = rotation.value
            rotation.animateTo(base - 14f, tween(170, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            rotation.animateTo(
                base + 90f,
                androidx.compose.animation.core.spring(dampingRatio = 0.52f, stiffness = 320f),
            )
            delay(240)
        }
    }
    LogoMark(
        size = size.dp,
        modifier = modifier.graphicsLayer { rotationZ = rotation.value },
    )
}

@Composable
fun LoadingView(message: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner()
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                Text(message, style = Type.Body, color = Palette.TextDim)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = Type.Title, color = Palette.Text)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = Type.Body, color = Palette.TextDim)
            }
        }
    }
}

@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Palette.Live)
    )
}
