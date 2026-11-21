package br.xc60.telemetria.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.media.AppLauncher
import br.xc60.telemetria.media.ExternalApp
import br.xc60.telemetria.media.MediaControls
import br.xc60.telemetria.media.MediaSessions
import br.xc60.telemetria.media.NowPlaying
import br.xc60.telemetria.service.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Lê a música atual a cada segundo enquanto a tela estiver visível. */
@Composable
fun rememberNowPlaying(settings: Settings): NowPlaying? {
    val context = LocalContext.current
    var np by remember { mutableStateOf<NowPlaying?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            np = MediaSessions.read(context, settings.musicApp)
            delay(1000)
        }
    }
    return np
}

@Composable
fun MediaScreen(settings: Settings, media: MediaControls) {
    val context = LocalContext.current
    val np = rememberNowPlaying(settings)
    var selected by remember { mutableStateOf(settings.musicApp) }
    media.preferredPkg = selected

    var nav by remember { mutableStateOf(settings.navApp) }
    val pickMusic: (ExternalApp) -> Unit = { app ->
        selected = app.pkg; settings.musicApp = app.pkg
        AppLauncher.open(context, app, sideBySide = false)
    }
    val pickNav: (ExternalApp) -> Unit = { app ->
        nav = app.pkg; settings.navApp = app.pkg
        AppLauncher.open(context, app, sideBySide = true)
    }

    // Sempre cabe na tela: o player ocupa o que sobra; as escolhas viram fileiras de ícones.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp && maxHeight >= 560.dp
        val landscapeShort = maxWidth > 600.dp && maxHeight < 560.dp
        val compactPlayer = maxHeight < 700.dp
        when {
            wide -> Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                NowPlayingCard(np, media, Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppPicker(context, selected) { pickMusic(it) }
                    Spacer(Modifier.height(4.dp))
                    Eyebrow("Navegação · abre ao lado")
                    AppChips(context, ExternalApp.nav, nav, pickNav)
                }
            }
            landscapeShort -> Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NowPlayingCard(np, media, Modifier.weight(1.3f).fillMaxHeight(), compact = true)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("Música")
                    AppChips(context, ExternalApp.music, selected, pickMusic, columns = 2)
                    Eyebrow("Navegação")
                    AppChips(context, ExternalApp.nav, nav, pickNav)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NowPlayingCard(np, media, Modifier.weight(1f).fillMaxWidth(), compact = compactPlayer)
                Eyebrow("Música")
                AppChips(context, ExternalApp.music, selected, pickMusic, columns = 2)
                Eyebrow("Navegação · abre ao lado")
                AppChips(context, ExternalApp.nav, nav, pickNav)
            }
        }
    }
}

/** Fileira(s) de botões compactos com ícone do app. */
@Composable
private fun AppChips(context: Context, apps: List<ExternalApp>, selected: String, onPick: (ExternalApp) -> Unit, columns: Int = apps.size) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { app -> AppChip(context, app, app.pkg == selected, Modifier.weight(1f)) { onPick(app) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppChip(context: Context, app: ExternalApp, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val icon = remember(app) { AppLauncher.icon(context, app)?.asImageBitmap() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(48.dp).clip(shape).background(if (selected) PanelHi else Panel)
            .border(1.dp, if (selected) Accent else Hairline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(PanelHi), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            else Icon(
                if (app.kind == br.xc60.telemetria.media.AppKind.NAV) Icons.Filled.Navigation else Icons.Filled.MusicNote,
                null, tint = Muted, modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(app.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppPicker(context: Context, selected: String, onSelect: (ExternalApp) -> Unit) {
    SectionTitle("Fonte de música", 22.sp)
    Text("Escolha o app. O som vai para a multimídia do carro pelo Bluetooth.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    ExternalApp.music.forEach { app ->
        AppRow(context, app, app.pkg == selected) {
            onSelect(app)
            AppLauncher.open(context, app, sideBySide = false)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun AppRow(context: Context, app: ExternalApp, selected: Boolean, trailing: String? = null, onClick: () -> Unit) {
    val installed = remember(app) { AppLauncher.isInstalled(context, app) }
    val icon = remember(app) { AppLauncher.icon(context, app)?.asImageBitmap() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) PanelHi else Panel)
            .border(1.dp, if (selected) Accent else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(PanelHi), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            else Icon(
                if (app.kind == br.xc60.telemetria.media.AppKind.NAV) Icons.Filled.Navigation else Icons.Filled.MusicNote,
                null, tint = Muted,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 16.sp)
            Text(if (installed) (trailing ?: "Instalado") else "Toque para instalar", color = Muted, fontSize = 12.sp)
        }
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
    }
}

@Composable
fun NowPlayingCard(np: NowPlaying?, media: MediaControls, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(Panel).border(1.dp, Hairline, shape)) {
        // Capa desfocada de fundo dá o "ar" de multimídia premium
        np?.art?.let {
            Image(
                it.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alpha = 0.18f,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.85f)))))
        Column(
            Modifier.fillMaxSize().padding(if (compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (np?.app?.label ?: "Tocando agora").uppercase(), color = Muted, fontSize = 11.sp,
                    letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                // Compacto: só o ponto colorido + texto curto, para não colar no título
                Text(
                    if (media.isOnBluetooth) "● Carro" else if (compact) "Aparelho" else "Som no aparelho",
                    color = if (media.isOnBluetooth) Ok else Muted, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val artSize: Dp = if (compact) 72.dp else 160.dp
                Box(
                    Modifier.size(artSize).clip(RoundedCornerShape(if (compact) 12.dp else 18.dp)).background(PanelHi),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = np?.art
                    if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Filled.MusicNote, null, tint = Muted, modifier = Modifier.size(artSize / 3))
                }
                Spacer(Modifier.width(if (compact) 16.dp else 28.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        np?.title?.ifEmpty { null } ?: if (np?.playing == true) "Tocando agora" else "Nada tocando",
                        fontSize = if (compact) 18.sp else 30.sp, fontWeight = FontWeight.Light,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        np?.artist?.ifEmpty { null } ?: np?.app?.label ?: "Escolha uma fonte de música",
                        color = Muted, fontSize = if (compact) 13.sp else 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact && np != null && np.durationMs > 0) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                            color = Accent, trackColor = Hairline,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(mmss(np.positionMs), color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(mmss(np.durationMs), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            // Os 5 botões encolhem para caber na largura disponível (nunca cortam na borda)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = if (compact) 8.dp else 16.dp
            val s = minOf(if (compact) 48.dp else 64.dp, (maxWidth - gap * 4) / 5.9f)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume -", s * 0.8f) { media.volumeDown() }
                RoundButton(Icons.Filled.SkipPrevious, "Anterior", s) { media.previous() }
                RoundButton(
                    if (np?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Tocar/Pausar", s * 1.3f, primary = true,
                ) { media.playPause(np?.playing == true) }
                RoundButton(Icons.Filled.SkipNext, "Próxima", s) { media.next() }
                RoundButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume +", s * 0.8f) { media.volumeUp() }
            }
            }
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, desc: String, size: Dp, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) Accent else PanelHi)
            .border(1.dp, if (primary) Accent else Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (primary) Bg else TextHi, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun SmallIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) =
    RoundButton(icon, desc, 40.dp, onClick = onClick)

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

