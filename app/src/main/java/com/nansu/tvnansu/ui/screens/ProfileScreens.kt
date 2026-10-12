package com.nansu.tvnansu.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.db.ProfileEntity
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.Avatars
import com.nansu.tvnansu.ui.components.LogoFull
import com.nansu.tvnansu.ui.components.PillButton
import com.nansu.tvnansu.ui.components.ProfileAvatar
import com.nansu.tvnansu.ui.components.TvTextField
import com.nansu.tvnansu.ui.nav.Dest
import com.nansu.tvnansu.ui.nav.LocalNavigator
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_PROFILES = 6

/** "Who's watching?" — shown at launch when there is more than one profile. */
@Composable
fun ProfilePickerScreen() {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val profiles by repo.profiles.collectAsState(initial = emptyList())
    val activeId by repo.profileId.collectAsState()
    val activeRequester = remember { FocusRequester() }

    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isNotEmpty()) {
            delay(80)
            runCatching { activeRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoFull(height = 34.dp)
            Spacer(Modifier.height(36.dp))
            Text("¿Quién está viendo?", style = Type.Display, color = Palette.Text)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                profiles.forEach { p ->
                    ProfileTile(
                        name = p.name,
                        onClick = {
                            repo.setProfile(p.id)
                            nav.resetTo(Dest.Main)
                        },
                        onLongClick = { nav.push(Dest.ProfileEdit(p.id)) },
                        modifier = if (p.id == activeId || (profiles.none { it.id == activeId } && p == profiles.first()))
                            Modifier.focusRequester(activeRequester) else Modifier,
                    ) { ProfileAvatar(p.name, p.avatar, 132.dp) }
                }
                if (profiles.size < MAX_PROFILES) {
                    ProfileTile(
                        name = "Añadir perfil",
                        onClick = { nav.push(Dest.ProfileEdit(null)) },
                        dim = true,
                    ) {
                        Box(
                            Modifier
                                .size(132.dp)
                                .background(Palette.Surface2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+", style = Type.Display.copy(fontSize = Type.Display.fontSize * 1.6f), color = Palette.TextDim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                "Mantén pulsado OK sobre un perfil para cambiar su nombre o color, o eliminarlo.",
                style = Type.Caption,
                color = Palette.TextFaint,
            )
        }
    }
}

@Composable
private fun ProfileTile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    dim: Boolean = false,
    size: Dp = 132.dp,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(size * 0.24f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .size(size)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Palette.Focus), shape = shape),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
        ) { content() }
        Spacer(Modifier.height(16.dp))
        Text(
            text = name,
            style = Type.Section,
            color = when {
                focused -> Palette.Text
                dim -> Palette.TextFaint
                else -> Palette.TextDim
            },
        )
    }
}

/** Create or edit a profile: name and colour. */
@Composable
fun ProfileEditScreen(profileId: Long?) {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(profileId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableIntStateOf(-1) }
    var canDelete by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameRequester = remember { FocusRequester() }

    LaunchedEffect(profileId) {
        val count = repo.profileCount()
        if (profileId != null) {
            val p: ProfileEntity? = repo.profile(profileId)
            if (p != null && avatar == -1) {
                name = p.name
                avatar = p.avatar
            }
            canDelete = count > 1
        } else if (avatar == -1) {
            avatar = count % Avatars.gradients.size
        }
        loaded = true
        delay(80)
        runCatching { nameRequester.requestFocus() }
    }

    if (!loaded) return
    val shownAvatar = avatar.coerceAtLeast(0)

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(260.dp)) {
            ProfileAvatar(name.ifBlank { "?" }, shownAvatar, 180.dp)
            Spacer(Modifier.height(18.dp))
            Text(name.ifBlank { "Nuevo perfil" }, style = Type.Title, color = Palette.Text)
        }
        Spacer(Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (profileId == null) "Añadir perfil" else "Editar perfil",
                style = Type.Display,
                color = Palette.Text,
            )
            Spacer(Modifier.height(24.dp))
            TvTextField(
                label = "Nombre",
                value = name,
                onValueChange = { name = it.take(20) },
                placeholder = "Escribe un nombre",
                modifier = Modifier
                    .width(420.dp)
                    .focusRequester(nameRequester),
            )
            Spacer(Modifier.height(24.dp))
            Text("Color", style = Type.Caption, color = Palette.TextDim)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatars.gradients.indices.forEach { i ->
                    val selected = i == shownAvatar
                    Surface(
                        onClick = { avatar = i },
                        modifier = Modifier.size(56.dp),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                        border = ClickableSurfaceDefaults.border(
                            border = if (selected) Border(BorderStroke(2.dp, Palette.TextDim), shape = RoundedCornerShape(14.dp))
                            else Border.None,
                            focusedBorder = Border(BorderStroke(3.dp, Palette.Focus), shape = RoundedCornerShape(14.dp)),
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                        ),
                    ) {
                        ProfileAvatar(name.ifBlank { "?" }, i, 56.dp)
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    text = "Guardar",
                    primary = true,
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            repo.saveProfile(profileId, name, shownAvatar)
                            nav.pop()
                        }
                    },
                )
                PillButton(text = "Cancelar", onClick = { nav.pop() })
                if (profileId != null && canDelete) {
                    PillButton(
                        text = if (confirmDelete) "Pulsa otra vez para eliminar" else "Eliminar perfil",
                        onClick = {
                            if (!confirmDelete) {
                                confirmDelete = true
                            } else {
                                scope.launch {
                                    repo.deleteProfile(profileId)
                                    nav.pop()
                                }
                            }
                        },
                    )
                }
            }
            if (profileId != null && canDelete) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Al eliminar un perfil se borran su Seguir viendo, Mi lista y canales recientes.",
                    style = Type.Caption,
                    color = Palette.TextFaint,
                )
            }
        }
    }
}
