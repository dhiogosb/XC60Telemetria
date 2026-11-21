package br.xc60.telemetria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.obd.Elm327
import br.xc60.telemetria.obd.Pids
import br.xc60.telemetria.service.Telemetry
import br.xc60.telemetria.service.TelemetryEngine
import br.xc60.telemetria.service.TelemetryState
import kotlinx.coroutines.launch

/** Diagnóstico: todos os sensores, códigos de falha e ferramentas Volvo. Tudo somente leitura. */
@Composable
fun DiagnosticsScreen(state: TelemetryState) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Sensores", sub == 0) { sub = 0 }
            Pill("Falhas", sub == 1) { sub = 1 }
            Pill("Ferramentas Volvo", sub == 2) { sub = 2 }
        }
        Box(Modifier.weight(1f)) {
            when (sub) {
                0 -> SensorsGrid(state)
                1 -> DtcScreen()
                else -> VolvoScreen()
            }
        }
    }
}

@Composable
private fun SensorsGrid(state: TelemetryState) {
    val keys = Pids.ALL.filter { state.supported.isEmpty() || it.pid in state.supported }.map { it.key }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(keys) { k -> PidTile(k, state.values) }
    }
}

@Composable
fun DtcScreen() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ecm by remember { mutableStateOf<TelemetryEngine.DtcReport?>(null) }
    var tcm by remember { mutableStateOf<TelemetryEngine.DtcReport?>(null) }
    var message by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Códigos de falha", 24.sp)
                    Text(
                        "Motor (7E0) e câmbio (7E1). Apagar limpa só os códigos do motor: " +
                            "não muda nenhuma configuração nem o desempenho do carro.",
                        color = Muted, fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.padding(12.dp))
                Pill(if (busy) "Lendo…" else "Ler falhas", false) {
                    val e = Telemetry.engine
                    if (e == null) { message = "Conecte ao carro primeiro"; return@Pill }
                    if (busy) return@Pill
                    busy = true
                    scope.launch {
                        try {
                            ecm = e.readDtcs(Elm327.ECM)
                            tcm = e.readDtcs(Elm327.TCM)
                            message = ""
                        } catch (ex: Exception) {
                            message = ex.message ?: "Erro"
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Pill("Apagar falhas do motor", false) {
                if (Telemetry.engine == null) message = "Conecte ao carro primeiro" else confirmClear = true
            }
            if (message.isNotEmpty()) Text(
                message, color = if (message.startsWith("Falhas apagadas")) Ok else Warn,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                containerColor = Panel,
                title = { Text("Apagar falhas do motor?", fontWeight = FontWeight.Light) },
                text = {
                    Text(
                        "Os códigos e a luz de injeção são apagados. Anote os códigos antes: " +
                            "se o defeito continuar, eles voltam. Os testes de emissão recomeçam e a " +
                            "central reaprende sozinha rodando normalmente.\n\nFaça com o motor desligado e a ignição ligada.",
                        color = Muted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        val e = Telemetry.engine ?: return@TextButton
                        scope.launch {
                            message = e.clearDtcs()
                            ecm = e.readDtcs(Elm327.ECM)
                        }
                    }) { Text("Apagar", color = Warn) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar", color = TextHi) } },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ecm?.let { Report("Motor · ECM 7E0", it, Modifier.weight(1f)) }
            tcm?.let { Report("Câmbio · TCM 7E1", it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Report(title: String, r: TelemetryEngine.DtcReport, modifier: Modifier) {
    Card(modifier) {
        Eyebrow(title)
        Spacer(Modifier.height(10.dp))
        r.error?.let { Text("Aviso: $it", color = Muted, fontSize = 13.sp) }
        if (r.confirmed.isEmpty() && r.pending.isEmpty()) {
            Text("Nenhuma falha", fontSize = 26.sp, fontWeight = FontWeight.Thin, color = Ok)
        }
        r.confirmed.forEach { Text("$it  · confirmada", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Warn) }
        r.pending.forEach { Text("$it  · pendente", fontSize = 22.sp, fontWeight = FontWeight.Light) }
    }
}
