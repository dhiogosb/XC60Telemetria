package br.xc60.telemetria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.obd.Elm327
import br.xc60.telemetria.obd.toHex
import br.xc60.telemetria.service.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private class Hit(val tx: Int, val did: Int, first: ByteArray) {
    var current by mutableStateOf(first)
    var changed by mutableStateOf(false)
    var watch by mutableStateOf(false)
}

/**
 * Ferramentas específicas do Volvo P3: varredura de DIDs (serviço 22) e console de leitura.
 * Fluxo para descobrir um sensor novo: varrer → marcar "monitorar" → mexer no carro
 * (acelerar, ligar o ar, esquentar) → ver quais bytes mudam → virar PID do painel.
 */
@Composable
fun VolvoScreen() {
    val scope = rememberCoroutineScope()
    var module by remember { mutableStateOf(Elm327.ECM) }
    var customHeader by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("0000") }
    var end by remember { mutableStateOf("00FF") }
    var progress by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val hits = remember { mutableStateListOf<Hit>() }
    var cmd by remember { mutableStateOf("") }
    val consoleLog = remember { mutableStateListOf<String>() }
    val lastValues = remember { mutableStateMapOf<Int, String>() }

    val tx = customHeader.toIntOrNull(16)?.takeIf { it in 0x700..0x7FF } ?: module

    // Atualiza os DIDs marcados a cada ~1 s e destaca os que mudaram.
    LaunchedEffect(Unit) {
        while (isActive) {
            val engine = Telemetry.engine
            if (engine != null && scanJob?.isActive != true) {
                for (h in hits.filter { it.watch }) {
                    val data = engine.readDid(h.tx, h.did) ?: continue
                    val hex = data.toHex()
                    h.changed = lastValues[h.did]?.let { it != hex } ?: false
                    lastValues[h.did] = hex
                    h.current = data
                }
            }
            delay(1000)
        }
    }

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.3f).fillMaxHeight()) {
            SectionTitle("Varredura de DIDs (serviço 22)", 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(module == Elm327.ECM && customHeader.isEmpty(), { module = Elm327.ECM; customHeader = "" }, { Text("Motor 7E0") })
                FilterChip(module == Elm327.TCM && customHeader.isEmpty(), { module = Elm327.TCM; customHeader = "" }, { Text("Câmbio 7E1") })
                OutlinedTextField(
                    customHeader, { customHeader = it.uppercase().take(3) },
                    label = { Text("Outro módulo") }, singleLine = true, modifier = Modifier.width(140.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(start, { start = it.uppercase().take(4) }, label = { Text("DID inicial") }, singleLine = true, modifier = Modifier.width(130.dp))
                OutlinedTextField(end, { end = it.uppercase().take(4) }, label = { Text("DID final") }, singleLine = true, modifier = Modifier.width(130.dp))
                if (scanJob?.isActive == true) {
                    OutlinedButton(onClick = { scanJob?.cancel() }) { Text("Parar") }
                } else {
                    Button(onClick = {
                        val e = Telemetry.engine
                        val s = start.toIntOrNull(16)
                        val f = end.toIntOrNull(16)
                        if (e == null) { progress = "Conecte ao carro primeiro"; return@Button }
                        if (s == null || f == null || f < s) { progress = "Faixa inválida"; return@Button }
                        hits.removeAll { it.tx == tx }
                        scanJob = scope.launch {
                            try {
                                e.scanDids(tx, s, f,
                                    onProgress = { progress = "%03X: %04X / %04X".format(tx, it, f) },
                                    onHit = { hit -> hits += Hit(tx, hit.did, hit.data) },
                                )
                                progress += " — concluído"
                            } catch (ex: Exception) {
                                progress = "Parado: ${ex.message ?: ""}"
                            }
                        }
                    }) { Text("Varrer") }
                }
            }
            Text(progress, color = Muted, fontSize = 13.sp)
            Text("${hits.size} DIDs responderam", color = Muted, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(hits, key = { it.tx * 0x10000 + it.did }) { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(h.watch, { h.watch = it })
                        Text("%03X  %04X".format(h.tx, h.did), fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
                        Text(
                            h.current.toHex(),
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = if (h.changed) Accent else androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("Console (somente leitura)", 20.sp)
            Text(
                "Destino: %03X. Exemplos: 0100, 0902, 22F190, ATRV. Escrita, reset e programação são bloqueados.".format(tx),
                color = Muted, fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cmd, { cmd = it.uppercase() }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Comando") })
                Button(onClick = {
                    val e = Telemetry.engine ?: run { consoleLog.add(0, "Conecte ao carro primeiro"); return@Button }
                    val c = cmd
                    scope.launch { consoleLog.add(0, "> $c\n" + e.console(tx, c)) }
                }) { Text("Enviar") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(consoleLog) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
    }
}
