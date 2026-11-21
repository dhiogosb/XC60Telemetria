package br.xc60.telemetria.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.obd.toHex
import br.xc60.telemetria.park.ParkConfig
import br.xc60.telemetria.park.ParkStore
import br.xc60.telemetria.park.SensorConfig
import br.xc60.telemetria.park.SensorPos
import br.xc60.telemetria.park.SignalSource
import br.xc60.telemetria.service.Telemetry
import br.xc60.telemetria.service.TelemetryState
import kotlinx.coroutines.launch
import java.util.Locale

// ---------- Rascunho editável (texto), convertido para ParkConfig ao salvar ----------

private class SrcEdit(src: SignalSource?) {
    var header by mutableStateOf(src?.let { "%03X".format(it.header) } ?: "")
    var did by mutableStateOf(src?.let { "%04X".format(it.did) } ?: "")
    var byte by mutableStateOf(src?.byteIndex?.toString() ?: "")
    var bytes by mutableStateOf(src?.bytes?.toString() ?: "1")

    fun toSource(): SignalSource? {
        val h = header.toIntOrNull(16)?.takeIf { it in 0x700..0x7FF } ?: return null
        val d = did.toIntOrNull(16)?.takeIf { it in 0..0xFFFF } ?: return null
        val b = byte.toIntOrNull()?.takeIf { it in 0..60 } ?: return null
        val n = bytes.toIntOrNull()?.takeIf { it in 1..2 } ?: 1
        return SignalSource(h, d, b, n)
    }
}

private class SensorEdit(c: SensorConfig) {
    val pos = c.pos
    val src = SrcEdit(c.source)
    var factor by mutableStateOf(num(c.factor))
    var offset by mutableStateOf(num(c.offset))
    var max by mutableStateOf(num(c.noObstacleAboveCm))
    var live by mutableStateOf("")

    fun toConfig() = SensorConfig(
        pos, src.toSource(),
        factor.replace(',', '.').toDoubleOrNull() ?: 1.0,
        offset.replace(',', '.').toDoubleOrNull() ?: 0.0,
        max.replace(',', '.').toDoubleOrNull() ?: 200.0,
    )
}

private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(Locale.US, "%.2f", d)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkSettingsScreen(state: TelemetryState, onPreview: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { ParkStore.config.value }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var showFront by remember { mutableStateOf(initial.showFront) }
    var rev by remember { mutableStateOf(SrcEdit(initial.reverse)) }
    var revValue by remember { mutableStateOf(if (initial.reverse != null) "%02X".format(initial.reverseValue) else "") }
    var revLive by remember { mutableStateOf("") }
    var sensors by remember { mutableStateOf(initial.sensors.map { SensorEdit(it) }) }
    var saved by remember { mutableStateOf("") }

    fun load(cfg: ParkConfig) {
        enabled = cfg.enabled; showFront = cfg.showFront
        rev = SrcEdit(cfg.reverse)
        revValue = if (cfg.reverse != null) "%02X".format(cfg.reverseValue) else ""
        sensors = cfg.sensors.map { SensorEdit(it) }
    }

    fun save() {
        val cfg = ParkConfig(
            enabled = enabled, showFront = showFront,
            reverse = rev.toSource(), reverseValue = revValue.toIntOrNull(16) ?: 0,
            sensors = sensors.map { it.toConfig() },
        )
        ParkStore.save(context, cfg)
        saved = if (cfg.enabled && cfg.reverse == null) "Salvo, mas falta configurar a detecção da ré." else "Configuração salva."
    }

    /** Lê o DID agora (pausa a leitura contínua por um instante). */
    fun readNow(src: SignalSource?, onResult: (String, Int?) -> Unit) {
        val e = Telemetry.engine
        if (e == null) { onResult("Conecte ao carro primeiro", null); return }
        if (src == null) { onResult("Preencha módulo, DID e byte", null); return }
        scope.launch {
            val data = e.readDid(src.header, src.did)
            if (data == null) onResult("Sem resposta (DID não existe nesse módulo?)", null)
            else {
                val v = src.extract(data)
                onResult("Bytes: ${data.toHex()}  →  valor ${v?.let { "%d (0x%X)".format(it, it) } ?: "fora do tamanho"}", v)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 24.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // ---- Geral ----
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Apoio de estacionamento", 22.sp)
                        Text(
                            "Mostra o carro e os sensores quando a ré é engatada. Apoio visual apenas: " +
                                "os bipes e a tela originais continuam valendo. Só lê dados do carro.",
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                    Switch(enabled, { enabled = it })
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mostrar sensores dianteiros", modifier = Modifier.weight(1f))
                    Switch(showFront, { showFront = it })
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Salvar", true) { save() }
                    Pill("Testar tela", false) { onPreview() }
                    Pill("Exemplo da demonstração", false) { load(ParkConfig.DEMO) ; saved = "Exemplo carregado. Toque em Salvar." }
                }
                if (saved.isNotEmpty()) Text(saved, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            // ---- Ré ----
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("1 · Detecção da ré", modifier = Modifier.weight(1f))
                    Eyebrow(if (state.reverse) "● Ré detectada" else "Ré não detectada", if (state.reverse) Ok else Muted)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Como achar: em Diagnóstico → Ferramentas Volvo, varra o câmbio (7E1) em ponto morto e depois " +
                        "com a ré engatada (pé no freio, carro parado). O byte que muda é o da marcha.",
                    color = Muted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SrcFields(rev)
                    Field("Valor ré (hex)", revValue, 120.dp, hex = true) { revValue = it.uppercase().take(4) }
                    Pill("Ler agora", false) { readNow(rev.toSource()) { t, _ -> revLive = t } }
                    Pill("Usar leitura como ré", false) {
                        readNow(rev.toSource()) { t, v -> revLive = t; if (v != null) revValue = "%02X".format(v) }
                    }
                }
                if (revLive.isNotEmpty()) Text(revLive, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            // ---- Sensores ----
            Card(Modifier.fillMaxWidth()) {
                Eyebrow("2 · Sensores")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Distância (cm) = valor × fator + deslocamento. Acima do máximo conta como livre. " +
                        "Com a ré engatada, aproxime-se de uma parede e compare com a fita métrica para acertar o fator.",
                    color = Muted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Pill("Mesmo módulo e DID para todos (bytes 0 a 7)", false) {
                    val first = sensors.first().src
                    sensors.forEachIndexed { i, s ->
                        s.src.header = first.header; s.src.did = first.did; s.src.byte = i.toString()
                        s.factor = sensors.first().factor; s.offset = sensors.first().offset; s.max = sensors.first().max
                    }
                }
                Spacer(Modifier.height(10.dp))
                sensors.filter { showFront || !it.pos.front }.forEach { s ->
                    val liveCm = state.park[s.pos]
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.pos.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (state.reverse) liveCm?.let { "${fmt(it)} cm" } ?: "livre" else "",
                                color = Accent, fontSize = 14.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SrcFields(s.src)
                            Field("Fator", s.factor, 90.dp) { s.factor = it }
                            Field("Desloc.", s.offset, 90.dp) { s.offset = it }
                            Field("Máx. cm", s.max, 100.dp) { s.max = it }
                            Pill("Ler", false) {
                                readNow(s.src.toSource()) { t, v ->
                                    val cm = v?.let { s.toConfig().toCm(it) }
                                    s.live = t + (if (v != null) "  →  " + (cm?.let { "${fmt(it)} cm" } ?: "livre") else "")
                                }
                            }
                        }
                        if (s.live.isNotEmpty()) Text(s.live, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SrcFields(src: SrcEdit) {
    Field("Módulo", src.header, 100.dp, hex = true) { src.header = it.uppercase().take(3) }
    Field("DID", src.did, 100.dp, hex = true) { src.did = it.uppercase().take(4) }
    Field("Byte", src.byte, 80.dp) { src.byte = it.filter(Char::isDigit).take(2) }
}

@Composable
private fun Field(label: String, value: String, width: Dp, hex: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        modifier = Modifier.width(width),
        keyboardOptions = if (hex) KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
        else KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Ajustes com duas seções: geral e estacionamento. */
@Composable
fun SettingsHost(state: TelemetryState, settings: br.xc60.telemetria.service.Settings, onPreview: () -> Unit) {
    var sub by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Geral", sub == 0) { sub = 0 }
            Pill("Estacionamento", sub == 1) { sub = 1 }
        }
        Spacer(Modifier.height(4.dp))
        if (sub == 0) SettingsScreen(state, settings) else ParkSettingsScreen(state, onPreview)
    }
}
