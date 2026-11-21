package br.xc60.telemetria

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.xc60.telemetria.comfort.SafeComfort
import br.xc60.telemetria.comfort.SimulatedComfort
import br.xc60.telemetria.comfort.UnavailableComfort
import br.xc60.telemetria.media.MediaControls
import br.xc60.telemetria.service.ConnState
import br.xc60.telemetria.service.Settings
import br.xc60.telemetria.service.Telemetry
import br.xc60.telemetria.service.TelemetryService
import br.xc60.telemetria.ui.AppTheme
import br.xc60.telemetria.ui.Accent
import br.xc60.telemetria.ui.Bg
import br.xc60.telemetria.ui.ComfortScreen
import br.xc60.telemetria.ui.DashboardScreen
import br.xc60.telemetria.ui.DiagnosticsScreen
import br.xc60.telemetria.ui.Hairline
import br.xc60.telemetria.ui.MediaScreen
import br.xc60.telemetria.ui.Muted
import br.xc60.telemetria.ui.Ok
import br.xc60.telemetria.ui.PanelHi
import br.xc60.telemetria.ui.SettingsHost
import br.xc60.telemetria.ui.ParkAssistView
import br.xc60.telemetria.ui.Pill
import br.xc60.telemetria.park.ParkConfig
import br.xc60.telemetria.park.ParkStore
import br.xc60.telemetria.park.SensorPos
import br.xc60.telemetria.service.TelemetryState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import br.xc60.telemetria.ui.TextHi
import br.xc60.telemetria.ui.Warn
import br.xc60.telemetria.update.AutoUpdateCheck
import br.xc60.telemetria.update.UpdateBanner
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Dest(val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("Início", Icons.Filled.Home),
    Dest("Mídia", Icons.Filled.MusicNote),
    Dest("Conforto", Icons.Filled.DirectionsCar),
    Dest("Diagnóstico", Icons.Filled.Speed),
    Dest("Ajustes", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val bluetoothOk = result[Manifest.permission.BLUETOOTH_CONNECT] != false
        val s = Settings(this)
        if ((bluetoothOk || s.demoMode) && s.canConnect) TelemetryService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = Settings(this)
        val media = MediaControls(this)
        ParkStore.load(this)
        // Modo demonstração sem calibração: usa o exemplo para a tela de ré aparecer no carro simulado
        if (settings.demoMode && !ParkStore.config.value.isConfigured) ParkStore.save(this, ParkConfig.DEMO)

        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        else if (settings.canConnect) TelemetryService.start(this)

        setContent {
            AppTheme {
                val state by Telemetry.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(if (settings.canConnect) 0 else 4) }
                // Conforto: simulado no modo demo, desligado no carro real. Sempre atrás do porteiro de segurança.
                val comfort = remember(settings.demoMode) {
                    SafeComfort(
                        if (settings.demoMode) SimulatedComfort() else UnavailableComfort(),
                        speedKmh = { Telemetry.state.value.values["speed"] },
                    )
                }

                var previewUntil by remember { mutableStateOf(0L) }
                val content: @Composable () -> Unit = {
                    when (tab) {
                        0 -> DashboardScreen(state, media, settings)
                        1 -> MediaScreen(settings, media)
                        2 -> ComfortScreen(comfort)
                        3 -> DiagnosticsScreen(state)
                        else -> SettingsHost(state, settings) { previewUntil = System.currentTimeMillis() + 6000 }
                    }
                }
                AutoUpdateCheck()
                BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
                    if (maxWidth < 600.dp) {
                        // Celular em pé: navegação embaixo, conteúdo ocupa a largura toda
                        Column(Modifier.fillMaxSize().systemBarsPadding()) {
                            TopBar(state.conn, state.message, settings.demoMode)
                            UpdateBanner()
                            Box(Modifier.weight(1f)) { content() }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                            BottomBar(tab) { tab = it }
                        }
                    } else {
                        val shortScreen = maxHeight < 520.dp // celular deitado
                        Row(Modifier.fillMaxSize().systemBarsPadding()) {
                            Rail(tab, compact = shortScreen) { tab = it }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
                            Column(Modifier.fillMaxSize()) {
                                TopBar(state.conn, state.message, settings.demoMode)
                                UpdateBanner()
                                Box(Modifier.weight(1f)) { content() }
                            }
                        }
                    }
                    ReverseOverlay(state, previewUntil) { previewUntil = 0 }
                }
            }
        }
    }
}

/** Sobe por cima de qualquer tela quando a ré é engatada (ou no "Testar tela"). */
@Composable
private fun ReverseOverlay(state: TelemetryState, previewUntil: Long, onClosePreview: () -> Unit) {
    val cfg by ParkStore.config.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var dismissed by remember { mutableStateOf(false) }
    val previewing = previewUntil > now
    LaunchedEffect(previewUntil) {
        while (previewUntil > System.currentTimeMillis()) { delay(250); now = System.currentTimeMillis() }
        now = System.currentTimeMillis()
    }
    // "Ocultar" vale até a ré ser desengatada
    LaunchedEffect(state.reverse) { if (!state.reverse) dismissed = false }

    val show = previewing || (cfg.enabled && state.reverse && !dismissed)
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            val data = if (previewing) PREVIEW else state.park
            ParkAssistView(
                data, cfg.showFront, Modifier.fillMaxSize(),
                subtitle = if (previewing) "Pré-visualização com dados de exemplo." else null,
            )
            Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                Pill("Ocultar", false) { if (previewing) onClosePreview() else dismissed = true }
            }
        }
    }
}

private val PREVIEW = mapOf(
    SensorPos.REAR_LEFT to 140.0, SensorPos.REAR_LEFT_CENTER to 62.0,
    SensorPos.REAR_RIGHT_CENTER to 45.0, SensorPos.REAR_RIGHT to null,
    SensorPos.FRONT_LEFT to 95.0, SensorPos.FRONT_LEFT_CENTER to null,
    SensorPos.FRONT_RIGHT_CENTER to null, SensorPos.FRONT_RIGHT to null,
)

@Composable
private fun Rail(selected: Int, compact: Boolean = false, onSelect: (Int) -> Unit) {
    // compact = celular deitado: pouca altura, então sem o título e com itens mais baixos
    Column(
        Modifier.fillMaxHeight().width(if (compact) 80.dp else 104.dp).padding(vertical = if (compact) 6.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.SpaceEvenly else Arrangement.spacedBy(8.dp),
    ) {
        if (!compact) {
            Text("XC60", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
            Spacer(Modifier.height(24.dp))
        }
        DESTS.forEachIndexed { i, d ->
            val sel = i == selected
            Column(
                Modifier
                    .width(if (compact) 68.dp else 84.dp)
                    .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
                    .background(if (sel) PanelHi else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = if (compact) 6.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(d.icon, d.label, tint = if (sel) Accent else Muted, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
                Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
                Text(d.label, color = if (sel) TextHi else Muted, fontSize = if (compact) 10.sp else 11.sp, letterSpacing = 0.3.sp, maxLines = 1)
            }
        }
    }
}

/** Navegação inferior para celular em pé. */
@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        DESTS.forEachIndexed { i, d ->
            val sel = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sel) PanelHi else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(d.icon, d.label, tint = if (sel) Accent else Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(d.label, color = if (sel) TextHi else Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopBar(conn: ConnState, message: String, demo: Boolean) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val color = when (conn) {
        ConnState.CONNECTED -> Ok
        ConnState.ERROR -> Warn
        ConnState.IDLE -> Muted
        else -> Color(0xFFE5B53D)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(
            (if (demo) "Demonstração · " else "OBD · ") + conn.label + if (message.isNotEmpty()) " · $message" else "",
            fontSize = 12.sp, color = Muted, letterSpacing = 0.5.sp, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            fontSize = 20.sp, fontWeight = FontWeight.Light, color = TextHi,
        )
    }
}
