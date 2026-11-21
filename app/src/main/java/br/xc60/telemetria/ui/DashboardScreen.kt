package br.xc60.telemetria.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import br.xc60.telemetria.media.NowPlaying
import br.xc60.telemetria.service.Settings
import br.xc60.telemetria.service.Telemetry
import br.xc60.telemetria.service.TelemetryState

/**
 * Tela principal — SEMPRE cabe na tela, sem rolar. O espaço disponível é dividido por pesos
 * e os elementos encolhem para caber:
 *  - Tablet (largo e alto): painel completo.
 *  - Celular deitado (largo e baixo): mostradores à esquerda, resumo à direita.
 *  - Celular em pé / tela dividida (estreito): tudo empilhado em proporção.
 */
@Composable
fun DashboardScreen(state: TelemetryState, media: MediaControls, settings: Settings) {
    val np = rememberNowPlaying(settings)
    media.preferredPkg = settings.musicApp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            maxWidth > 840.dp && maxHeight >= 560.dp -> TabletLayout(state, media, settings, np)
            maxWidth > 600.dp && maxHeight < 560.dp -> LandscapePhoneLayout(state, media, settings, np)
            else -> PortraitLayout(state, media, settings, np)
        }
    }
}

@Composable
private fun TabletLayout(state: TelemetryState, media: MediaControls, settings: Settings, np: NowPlaying?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.weight(1.15f), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            FitGauge(Modifier.weight(1f).fillMaxHeight()) { SpeedGauge(state, it) }
            Card(Modifier.weight(0.9f).fillMaxHeight(), padding = 24.dp) { VitalBars(state) }
            FitGauge(Modifier.weight(1f).fillMaxHeight()) { RpmGauge(state, it) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) { Trip(state) }
            NowPlayingCard(np, media, Modifier.weight(1.25f).fillMaxHeight(), compact = true)
            Card(Modifier.weight(0.85f).fillMaxHeight()) { Navigation(settings) }
        }
    }
}

@Composable
private fun LandscapePhoneLayout(state: TelemetryState, media: MediaControls, settings: Settings, np: NowPlaying?) {
    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FitGauge(Modifier.weight(1f).fillMaxHeight()) { SpeedGauge(state, it) }
        FitGauge(Modifier.weight(1f).fillMaxHeight()) { RpmGauge(state, it) }
        Column(Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VitalsGrid(state, Modifier.weight(1.1f))
            TripStrip(state, Modifier.weight(0.9f))
            MiniPlayer(np, media, settings, Modifier.fillMaxWidth().height(56.dp))
        }
    }
}

@Composable
private fun PortraitLayout(state: TelemetryState, media: MediaControls, settings: Settings, np: NowPlaying?) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1.15f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitGauge(Modifier.weight(1f).fillMaxHeight()) { SpeedGauge(state, it) }
            FitGauge(Modifier.weight(1f).fillMaxHeight()) { RpmGauge(state, it) }
        }
        VitalsGrid(state, Modifier.weight(0.9f))
        TripStrip(state, Modifier.weight(0.75f))
        MiniPlayer(np, media, settings, Modifier.fillMaxWidth().height(64.dp))
    }
}

/** Dá ao mostrador o maior quadrado que cabe no espaço. */
@Composable
private fun FitGauge(modifier: Modifier, gauge: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        gauge(Modifier.size(minOf(maxWidth, maxHeight)))
    }
}

@Composable
private fun SpeedGauge(state: TelemetryState, modifier: Modifier) =
    ArcGauge(state.values["speed"], 220.0, "Velocidade", "km/h", modifier, majorTicks = 11, valueSize = 76.sp)

@Composable
private fun RpmGauge(state: TelemetryState, modifier: Modifier) =
    ArcGauge(
        state.values["rpm"]?.let { it / 1000 }, 7.0, "Rotação", "x1000 rpm", modifier,
        decimals = 1, redFrom = 6.0, majorTicks = 7, valueSize = 76.sp,
    )

// ---------- Indicadores ----------

@Composable
private fun ColumnScope.VitalBars(state: TelemetryState) {
    val v = state.values
    val boost = state.boostBar
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        MiniBar("Turbo", boost, -1.0, 1.5, "${fmt(boost, 2)} bar", warn = (boost ?: 0.0) > 1.4)
        MiniBar("Água", v["coolant"], 40.0, 120.0, "${fmt(v["coolant"])} °C", warn = (v["coolant"] ?: 0.0) > 108)
        if (v.containsKey("oil")) MiniBar("Óleo", v["oil"], 40.0, 140.0, "${fmt(v["oil"])} °C", warn = (v["oil"] ?: 0.0) > 130)
        MiniBar("Bateria", v["voltage"], 11.0, 15.5, "${fmt(v["voltage"], 1)} V", warn = (v["voltage"] ?: 13.0) < 12.2)
        MiniBar("Tanque", v["fuel"], 0.0, 100.0, "${fmt(v["fuel"])} %", warn = (v["fuel"] ?: 100.0) < 10)
    }
}

private data class Vital(val label: String, val value: String, val unit: String, val warn: Boolean)

/** Indicadores em grade (celular): 3 em cima, 2 embaixo; o texto encolhe com a altura. */
@Composable
private fun VitalsGrid(state: TelemetryState, modifier: Modifier) {
    val v = state.values
    val b = state.boostBar
    val items = listOfNotNull(
        Vital("Turbo", fmt(b, 2), "bar", (b ?: 0.0) > 1.4),
        Vital("Água", fmt(v["coolant"]), "°C", (v["coolant"] ?: 0.0) > 108),
        if (v.containsKey("oil")) Vital("Óleo", fmt(v["oil"]), "°C", (v["oil"] ?: 0.0) > 130) else null,
        Vital("Bateria", fmt(v["voltage"], 1), "V", (v["voltage"] ?: 13.0) < 12.2),
        Vital("Tanque", fmt(v["fuel"]), "%", (v["fuel"] ?: 100.0) < 10),
    )
    val rows = items.chunked(3)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { VitalCell(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun VitalCell(v: Vital, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(
        modifier.clip(shape).background(Panel).border(1.dp, Hairline, shape).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val valueSize = (maxHeight.value * 0.38f).coerceIn(14f, 30f).sp
        Column {
            Text(v.label.uppercase(), color = if (v.warn) Warn else Muted, fontSize = 9.sp, letterSpacing = 1.5.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(v.value, fontSize = valueSize, fontWeight = FontWeight.Light, color = if (v.warn) Warn else TextHi, maxLines = 1)
                Text(" ${v.unit}", color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

// ---------- Computador de bordo ----------

@Composable
private fun Trip(state: TelemetryState) {
    val t = state.trip
    Row(verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("Computador de bordo", modifier = Modifier.weight(1f))
        TextButton(onClick = { Telemetry.engine?.resetTrip() }) { Text("Zerar", color = Accent, fontSize = 13.sp) }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(fmt(t.avgKmL, 1), fontSize = 44.sp, fontWeight = FontWeight.Thin)
        Text("  km/L médio", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    TripLine("Instantâneo", "${fmt(t.instantKmL, 1)} km/L")
    TripLine("Distância", "${fmt(t.distanceKm, 1)} km")
    TripLine("Vel. média", "${fmt(t.avgSpeed)} km/h")
    val min = (t.elapsedS / 60).toInt()
    TripLine("Tempo", "%d h %02d min".format(min / 60, min % 60))
}

@Composable
private fun TripLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp)
    }
}

/** Computador de bordo em uma faixa: média em destaque + instantâneo, distância e tempo. */
@Composable
private fun TripStrip(state: TelemetryState, modifier: Modifier) {
    val t = state.trip
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Hairline, shape).padding(horizontal = 12.dp, vertical = 6.dp)) {
        val big = (maxHeight.value * 0.42f).coerceIn(18f, 40f).sp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MÉDIA", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("Zerar", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable { Telemetry.engine?.resetTrip() })
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(t.avgKmL, 1), fontSize = big, fontWeight = FontWeight.Thin, maxLines = 1)
                    Text(" km/L", color = Muted, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            val min = (t.elapsedS / 60).toInt()
            TripMini("Agora", "${fmt(t.instantKmL, 1)}", "km/L", Modifier.weight(1f))
            TripMini("Distância", fmt(t.distanceKm, 1), "km", Modifier.weight(1f))
            TripMini("Tempo", "%d:%02d".format(min / 60, min % 60), "h", Modifier.weight(0.8f))
        }
    }
}

@Composable
private fun TripMini(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 1)
            Text(" $unit", color = Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

// ---------- Música + navegação em uma barra ----------

@Composable
private fun MiniPlayer(np: NowPlaying?, media: MediaControls, settings: Settings, modifier: Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.clip(shape).background(Panel).border(1.dp, Hairline, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(PanelHi), contentAlignment = Alignment.Center) {
            val art = np?.art
            if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.MusicNote, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(np?.title?.ifEmpty { null } ?: if (np?.playing == true) "Tocando agora" else "Nada tocando", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(np?.artist?.ifEmpty { null } ?: (np?.app?.label ?: "Toque em Mídia"), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(Icons.Filled.SkipPrevious, "Anterior", 36.dp) { media.previous() }
        Spacer(Modifier.width(4.dp))
        RoundButton(if (np?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Tocar/Pausar", 42.dp, primary = true) {
            media.playPause(np?.playing == true)
        }
        Spacer(Modifier.width(4.dp))
        RoundButton(Icons.Filled.SkipNext, "Próxima", 36.dp) { media.next() }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(Hairline))
        Spacer(Modifier.width(8.dp))
        NavIconButton(ExternalApp.byPkg(settings.navApp) ?: ExternalApp.WAZE, 38.dp) {
            AppLauncher.open(context, it, sideBySide = true)
        }
    }
}

@Composable
private fun NavIconButton(app: ExternalApp, size: Dp, onClick: (ExternalApp) -> Unit) {
    val context = LocalContext.current
    val icon = remember(app) { AppLauncher.icon(context, app)?.asImageBitmap() }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(PanelHi).clickable { onClick(app) },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon, app.label, Modifier.size(size * 0.8f))
        else Icon(Icons.Filled.Navigation, app.label, tint = Accent, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun Navigation(settings: Settings) {
    val context = LocalContext.current
    Eyebrow("Navegação")
    Spacer(Modifier.height(4.dp))
    Text("Abre ao lado do painel, em tela dividida.", color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    ExternalApp.nav.forEach { app ->
        AppRow(context, app, app.pkg == settings.navApp, trailing = "Abrir ao lado") {
            settings.navApp = app.pkg
            AppLauncher.open(context, app, sideBySide = true)
        }
        Spacer(Modifier.height(10.dp))
    }
}
