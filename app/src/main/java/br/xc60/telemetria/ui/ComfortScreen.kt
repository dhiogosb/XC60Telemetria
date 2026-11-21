package br.xc60.telemetria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.comfort.ComfortAction
import br.xc60.telemetria.comfort.ComfortController
import br.xc60.telemetria.comfort.ComfortState
import br.xc60.telemetria.comfort.Window
import kotlinx.coroutines.launch

/**
 * Conforto — sempre cabe na tela, sem rolar.
 * Tela grande: os 4 cartões em grade 2×2 dividindo a altura.
 * Tela pequena (celular, tela dividida): abas Clima · Travas · Vidros · Teto, um cartão por vez ocupando o espaço.
 */
@Composable
fun ComfortScreen(comfort: ComfortController) {
    val s by comfort.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    val run: (ComfortAction) -> Unit = { a ->
        scope.launch { message = comfort.execute(a).exceptionOrNull()?.message ?: "" }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = maxWidth > 700.dp && maxHeight >= 560.dp
        val twoPerRow = maxWidth > 500.dp
        val tallEnough = maxHeight > 420.dp
        if (grid) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Header(comfort, message, true)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ClimateCard(s, run, true, Modifier.weight(1f).fillMaxHeight())
                    LocksCard(s, run, Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.weight(1.2f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    WindowsCard(s, run, true, Modifier.weight(1f).fillMaxHeight())
                    RoofCard(s, run, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Clima", "Travas", "Vidros", "Teto").forEachIndexed { i, t ->
                        Pill(t, tab == i, Modifier.weight(1f)) { tab = i }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.ifEmpty { if (comfort.available) "Demonstração · porta-malas e destravar só parado" else comfort.reason },
                        color = if (message.isNotEmpty()) Warn else Muted, fontSize = 11.sp, maxLines = 2,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val m = Modifier.weight(1f).fillMaxWidth()
                when (tab) {
                    0 -> ClimateCard(s, run, tallEnough, m)
                    1 -> LocksCard(s, run, m)
                    2 -> WindowsCard(s, run, twoPerRow, m)
                    else -> RoofCard(s, run, m)
                }
            }
        }
    }
}

@Composable
private fun Header(comfort: ComfortController, message: String, wide: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Conforto", if (wide) 26.sp else 22.sp)
        Spacer(Modifier.weight(1f))
        if (!comfort.available) Eyebrow("Indisponível", Warn) else Eyebrow("Demonstração", Accent)
    }
    Text(
        if (comfort.available) "Simulação. Porta-malas e destravar só com o carro parado." else comfort.reason,
        color = Muted, fontSize = 13.sp,
    )
    if (message.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(message, color = Warn, fontSize = 14.sp)
    }
}

@Composable
private fun ClimateCard(s: ComfortState, run: (ComfortAction) -> Unit, wide: Boolean, modifier: Modifier) {
    Card(modifier) {
        Eyebrow("Clima")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TempControl("Motorista", s.tempLeft, wide, { run(ComfortAction.ClimateTemp(true, -1)) }, { run(ComfortAction.ClimateTemp(true, 1)) })
            TempControl("Passageiro", s.tempRight, wide, { run(ComfortAction.ClimateTemp(false, -1)) }, { run(ComfortAction.ClimateTemp(false, 1)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocksCard(s: ComfortState, run: (ComfortAction) -> Unit, modifier: Modifier) {
    Card(modifier) {
        Eyebrow("Travas")
        Spacer(Modifier.height(10.dp))
        Text(if (s.locked) "Travado" else "Destravado", fontSize = 30.sp, fontWeight = FontWeight.Thin, color = if (s.locked) TextHi else Accent)
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Travar", s.locked) { run(ComfortAction.Locks(true)) }
            Pill("Destravar", !s.locked) { run(ComfortAction.Locks(false)) }
        }
        Spacer(Modifier.height(18.dp))
        Eyebrow("Porta-malas")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.trunkOpen) "Aberto" else "Fechado", fontSize = 18.sp, modifier = Modifier.weight(1f))
            Pill("Abrir", s.trunkOpen) { run(ComfortAction.TrunkOpen) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowsCard(s: ComfortState, run: (ComfortAction) -> Unit, wide: Boolean, modifier: Modifier) {
    Card(modifier) {
        Eyebrow("Vidros")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Fechar todos", false) { run(ComfortAction.AllWindows(true)) }
            Pill("Abrir todos", false) { run(ComfortAction.AllWindows(false)) }
        }
        Spacer(Modifier.height(12.dp))
        // Larga: 2 vidros por linha. Estreita: 1 por linha.
        Window.entries.chunked(if (wide) 2 else 1).forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                group.forEach { w ->
                    Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MiniBar(w.label, (s.windows[w] ?: 0).toDouble(), 0.0, 100.0, "${s.windows[w]}%", Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        SmallIconButton(Icons.Filled.KeyboardArrowUp, "Subir ${w.label}") { run(ComfortAction.WindowMove(w, true)) }
                        Spacer(Modifier.width(6.dp))
                        SmallIconButton(Icons.Filled.KeyboardArrowDown, "Descer ${w.label}") { run(ComfortAction.WindowMove(w, false)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoofCard(s: ComfortState, run: (ComfortAction) -> Unit, modifier: Modifier) {
    Card(modifier) {
        Eyebrow("Teto solar")
        Spacer(Modifier.height(10.dp))
        MiniBar("Abertura", s.sunroof.toDouble(), 0.0, 100.0, if (s.sunroofTilted) "Inclinado" else "${s.sunroof}%")
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Fechar", s.sunroof == 0 && !s.sunroofTilted) { run(ComfortAction.SunroofSet(0)) }
            Pill("Inclinar", s.sunroofTilted) { run(ComfortAction.SunroofTilt) }
            Pill("Abrir", s.sunroof == 100) { run(ComfortAction.SunroofSet(100)) }
        }
        Spacer(Modifier.height(18.dp))
        Eyebrow("Cortina")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Fechada", !s.shadeOpen) { run(ComfortAction.ShadeSet(false)) }
            Pill("Aberta", s.shadeOpen) { run(ComfortAction.ShadeSet(true)) }
        }
    }
}

@Composable
private fun TempControl(label: String, temp: Double, wide: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text("${fmt(temp, 1)}°", fontSize = if (wide) 52.sp else 34.sp, fontWeight = FontWeight.Thin, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp)) {
            val b = if (wide) 52.dp else 44.dp
            RoundButton(Icons.Filled.Remove, "Diminuir $label", b, onClick = onDown)
            RoundButton(Icons.Filled.Add, "Aumentar $label", b, onClick = onUp)
        }
    }
}
