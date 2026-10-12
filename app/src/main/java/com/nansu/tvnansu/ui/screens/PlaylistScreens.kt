package com.nansu.tvnansu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.SyncState
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.LogoFull
import com.nansu.tvnansu.ui.components.PillButton
import com.nansu.tvnansu.ui.components.Segmented
import com.nansu.tvnansu.ui.components.Spinner
import com.nansu.tvnansu.ui.components.TvTextField
import com.nansu.tvnansu.ui.nav.Dest
import com.nansu.tvnansu.ui.nav.LocalNavigator
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sign-in screen: Xtream Codes account or M3U URL, like TiviMate. */
@Composable
fun AddPlaylistScreen(first: Boolean) {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var m3u by rememberSaveable { mutableStateOf("") }
    var epg by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstField.requestFocus() }
    }

    fun connect() {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                if (mode == 0) repo.addXtream(name, server, user, pass)
                else repo.addM3u(name, m3u, epg)
                nav.resetTo(Dest.Sync)
            } catch (e: Exception) {
                error = e.message ?: "No se pudo conectar"
            } finally {
                busy = false
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 48.dp)
    ) {
        Column(
            Modifier
                .weight(0.9f)
                .padding(top = 40.dp, end = 48.dp)
        ) {
            LogoFull(height = 60.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (first) "Añade tu lista para empezar." else "Añadir otra lista",
                style = Type.Title,
                color = Palette.TextDim,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (mode == 0)
                    "Con Xtream Codes la app recibe canales, guía, películas y series ya organizados, con carátulas y temporadas."
                else
                    "Pega la URL de tu lista M3U. Si es un enlace get.php con usuario y contraseña, se convertirá automáticamente a Xtream Codes.",
                style = Type.Body,
                color = Palette.TextFaint,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Consejo: al editar un campo puedes escribir desde el móvil con la app Google TV.",
                style = Type.Caption,
                color = Palette.TextFaint,
            )
        }

        Column(
            Modifier
                .weight(1.1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Surface)
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Segmented(
                options = listOf("Xtream Codes", "Lista M3U"),
                selected = mode,
                onSelect = { mode = it },
            )
            TvTextField(
                label = "Nombre (opcional)",
                value = name,
                onValueChange = { name = it },
                placeholder = "Mi lista",
                modifier = Modifier.focusRequester(firstField),
            )
            if (mode == 0) {
                TvTextField(
                    label = "Servidor",
                    value = server,
                    onValueChange = { server = it },
                    placeholder = "http://servidor.com:8080",
                    keyboardType = KeyboardType.Uri,
                )
                TvTextField(
                    label = "Usuario",
                    value = user,
                    onValueChange = { user = it },
                    placeholder = "usuario",
                )
                TvTextField(
                    label = "Contraseña",
                    value = pass,
                    onValueChange = { pass = it },
                    placeholder = "contraseña",
                    password = true,
                )
            } else {
                TvTextField(
                    label = "URL de la lista M3U",
                    value = m3u,
                    onValueChange = { m3u = it },
                    placeholder = "http://…/lista.m3u",
                    keyboardType = KeyboardType.Uri,
                )
                TvTextField(
                    label = "URL de la guía EPG (opcional)",
                    value = epg,
                    onValueChange = { epg = it },
                    placeholder = "http://…/guia.xml",
                    keyboardType = KeyboardType.Uri,
                )
            }
            if (error != null) {
                Text(error ?: "", style = Type.Label, color = Palette.Live)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = if (busy) "Conectando…" else "Conectar",
                    onClick = { connect() },
                    primary = true,
                    enabled = !busy,
                )
                if (busy) {
                    Spacer(Modifier.width(16.dp))
                    Spinner(size = 24)
                }
                if (!first) {
                    Spacer(Modifier.width(12.dp))
                    PillButton(text = "Cancelar", onClick = { nav.pop() })
                }
            }
        }
    }
}

/** First download of the catalogue, with live progress. */
@Composable
fun SyncScreen() {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val state by repo.syncState.collectAsState()
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val retryRequester = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        failed = false
        val p = repo.active()
        if (p == null) {
            nav.resetTo(Dest.AddPlaylist(first = true))
            return@LaunchedEffect
        }
        val ok = repo.sync(p.id)
        if (ok) nav.resetTo(Dest.Main) else failed = true
    }
    LaunchedEffect(failed) {
        if (failed) {
            delay(100)
            runCatching { retryRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoFull(height = 60.dp)
            Spacer(Modifier.height(32.dp))
            if (!failed) {
                Spinner()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = (state as? SyncState.Running)?.message ?: "Preparando…",
                    style = Type.Body,
                    color = Palette.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "La primera carga puede tardar un par de minutos con listas grandes.",
                    style = Type.Caption,
                    color = Palette.TextFaint,
                )
            } else {
                Text(
                    text = (state as? SyncState.Failed)?.message ?: "No se pudo descargar la lista",
                    style = Type.Body,
                    color = Palette.Live,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        text = "Reintentar",
                        onClick = { attempt++ },
                        primary = true,
                        modifier = Modifier.focusRequester(retryRequester),
                    )
                    PillButton(
                        text = "Cambiar datos",
                        onClick = { nav.resetTo(Dest.AddPlaylist(first = true)) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val playlists by repo.playlists.collectAsState(initial = emptyList())
    val activeId by repo.activeId.collectAsState()
    val sync by repo.syncState.collectAsState()
    var liveFormat by remember { mutableStateOf(repo.prefs.liveFormat) }
    var reduceMotion by remember { mutableStateOf(repo.prefs.reduceMotion) }
    var refreshHours by remember { mutableIntStateOf(repo.prefs.autoRefreshHours) }
    var subtitleSize by remember { mutableIntStateOf(repo.prefs.subtitleSize) }
    var subtitleBackground by remember { mutableStateOf(repo.prefs.subtitleBackground) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 84.dp, start = 48.dp, end = 48.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Perfil", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Cambiar de perfil", onClick = { nav.push(Dest.Profiles) })
            PillButton("Editar este perfil", onClick = { nav.push(Dest.ProfileEdit(repo.profileId.value)) })
            PillButton("Añadir perfil", leading = "+", onClick = { nav.push(Dest.ProfileEdit(null)) })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Cada perfil guarda su Seguir viendo, Mi lista, canales recientes, subtítulos y animaciones.",
            style = Type.Caption,
            color = Palette.TextFaint,
        )

        Spacer(Modifier.height(32.dp))
        Text("Listas", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(12.dp))
        playlists.forEach { p ->
            val isActive = p.id == activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isActive) Palette.Surface2 else Palette.Surface)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = p.name + if (isActive) "  ·  en uso" else "",
                        style = Type.Section,
                        color = Palette.Text,
                    )
                    val type = if (p.type == "XTREAM") "Xtream Codes · ${p.username}" else "Lista M3U"
                    val updated = if (p.lastSync > 0) "actualizada " + java.text.SimpleDateFormat(
                        "d MMM, HH:mm", java.util.Locale("es", "ES")
                    ).format(java.util.Date(p.lastSync)) else "sin descargar"
                    Text("$type  ·  $updated", style = Type.Caption, color = Palette.TextDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isActive) {
                        PillButton("Usar", onClick = {
                            repo.setActive(p.id)
                            if (p.lastSync == 0L) nav.resetTo(Dest.Sync)
                        })
                    }
                    PillButton(
                        text = "Actualizar",
                        enabled = sync !is SyncState.Running,
                        onClick = { repo.syncInBackground(p.id) },
                    )
                    PillButton("Eliminar", onClick = {
                        scope.launch {
                            repo.deletePlaylist(p.id)
                            if (repo.active() == null) nav.resetTo(Dest.AddPlaylist(first = true))
                        }
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        PillButton("Añadir lista", leading = "+", onClick = { nav.push(Dest.AddPlaylist(first = false)) })

        Spacer(Modifier.height(32.dp))
        Text("Reproducción", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Formato de TV en vivo (Xtream)",
            description = "TS arranca más rápido; HLS funciona mejor con algunos proveedores. Se aplica al volver a abrir un canal.",
        ) {
            Segmented(
                options = listOf("TS", "HLS"),
                selected = if (liveFormat == "m3u8") 1 else 0,
                onSelect = {
                    liveFormat = if (it == 1) "m3u8" else "ts"
                    repo.prefs.liveFormat = liveFormat
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        SettingRow(
            title = "Tamaño de los subtítulos",
            description = "También se cambia durante la reproducción, en el panel de audio y subtítulos.",
        ) {
            Segmented(
                options = com.nansu.tvnansu.ui.player.SubtitleStyle.labels,
                selected = subtitleSize,
                onSelect = {
                    subtitleSize = it
                    repo.prefs.subtitleSize = it
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(
            title = "Estilo de los subtítulos",
            description = "Contorno negro sobre la imagen, o caja oscura detrás del texto para leer mejor en escenas claras.",
        ) {
            Segmented(
                options = listOf("Contorno", "Fondo"),
                selected = if (subtitleBackground) 1 else 0,
                onSelect = {
                    subtitleBackground = it == 1
                    repo.prefs.subtitleBackground = subtitleBackground
                },
            )
        }

        Spacer(Modifier.height(32.dp))
        Text("Interfaz y datos", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Reducir animaciones",
            description = "Desactiva el zoom lento y los fundidos del fondo. Útil en aparatos poco potentes.",
        ) {
            Segmented(
                options = listOf("No", "Sí"),
                selected = if (reduceMotion) 1 else 0,
                onSelect = {
                    reduceMotion = it == 1
                    repo.prefs.reduceMotion = reduceMotion
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        val refreshOptions = listOf(0, 12, 24, 72)
        SettingRow(
            title = "Actualizar la lista automáticamente",
            description = "Al abrir la app, si la última descarga es más antigua que este intervalo.",
        ) {
            Segmented(
                options = listOf("Nunca", "12 h", "1 día", "3 días"),
                selected = refreshOptions.indexOf(refreshHours).coerceAtLeast(0),
                onSelect = {
                    refreshHours = refreshOptions[it]
                    repo.prefs.autoRefreshHours = refreshHours
                },
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "nansu tv ${com.nansu.tvnansu.BuildConfig.VERSION_NAME}  ·  Reproductor personal. La app no incluye contenido.",
            style = Type.Caption,
            color = Palette.TextFaint,
        )
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Text(title, style = Type.Section, color = Palette.Text)
            Text(description, style = Type.Caption, color = Palette.TextDim)
        }
        control()
    }
}
