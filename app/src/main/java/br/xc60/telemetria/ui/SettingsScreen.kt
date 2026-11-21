package br.xc60.telemetria.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.service.FuelType
import br.xc60.telemetria.service.Settings
import br.xc60.telemetria.service.TelemetryService
import br.xc60.telemetria.service.TelemetryState

private data class Paired(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Paired> = try {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    adapter?.bondedDevices.orEmpty()
        .map { Paired(it.name ?: "(sem nome)", it.address) }
        // Adaptadores OBD costumam aparecer como OBDII, OBD2, V-LINK, ELM327…
        .sortedByDescending { it.name.contains("OBD", true) || it.name.contains("ELM", true) }
} catch (_: SecurityException) {
    emptyList()
}

@Composable
fun SettingsScreen(state: TelemetryState, settings: Settings) {
    val context = LocalContext.current
    var device by remember { mutableStateOf(settings.deviceAddress) }
    var fuel by remember { mutableStateOf(settings.fuelType) }
    var gap by remember { mutableFloatStateOf(settings.requestGapMs.toFloat()) }
    var multi by remember { mutableStateOf(settings.multiPid) }
    var csv by remember { mutableStateOf(settings.logCsv) }
    var demo by remember { mutableStateOf(settings.demoMode) }
    val devices = remember { pairedDevices(context) }

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            SectionTitle("Adaptador OBD2", 20.sp)
            Text(
                "Pareie o ELM327 nas configurações de Bluetooth do Android (PIN 1234 ou 0000) e escolha aqui. " +
                    "Não escolha a multimídia do carro: ela fica pareada à parte, só para o som.",
                color = Muted, fontSize = 13.sp,
            )
            if (devices.isEmpty()) Text("Nenhum aparelho pareado (ou permissão de Bluetooth negada).", color = Warn)
            devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { device = d.address; settings.deviceAddress = d.address },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(device == d.address, { device = d.address; settings.deviceAddress = d.address })
                    Column {
                        Text(d.name)
                        Text(d.address, color = Muted, fontSize = 12.sp)
                    }
                }
            }
            SwitchRow("Modo demonstração (carro simulado, sem Bluetooth)", demo) {
                demo = it; settings.demoMode = it
                TelemetryService.restart(context)
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { TelemetryService.restart(context) }, enabled = device != null || demo) { Text("Conectar") }
                OutlinedButton(onClick = { TelemetryService.stop(context) }) { Text("Desconectar") }
            }
            SectionTitle("Status")
            Info("Conexão", state.conn.label + if (state.message.isNotEmpty()) " — ${state.message}" else "")
            Info("ELM327", state.elmVersion.ifEmpty { "—" })
            Info("Chassi (VIN)", state.vin.ifEmpty { "—" })
            Info("Sensores suportados", if (state.supported.isEmpty()) "—" else "${state.supported.size} PIDs")
            Info("Requisições/s", fmt(state.requestsPerSecond, 1))
            Info("Versão do app", br.xc60.telemetria.BuildConfig.VERSION_NAME)
            br.xc60.telemetria.update.UpdateCheckRow()
        }

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            SectionTitle("Combustível", 20.sp)
            Text("Usado no cálculo de consumo pelo fluxo de ar (MAF).", color = Muted, fontSize = 13.sp)
            FuelType.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { fuel = f; settings.fuelType = f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(fuel == f, { fuel = f; settings.fuelType = f })
                    Text(f.label)
                }
            }
            if (fuel == FuelType.DIESEL) {
                Text(
                    "No diesel o consumo só aparece se a central informar o PID 5E (consumo do motor).",
                    color = Muted, fontSize = 13.sp,
                )
            }

            SectionTitle("Convivência com o som Bluetooth", 20.sp)
            Text(
                "Pausa entre requisições: ${gap.toInt()} ms. Se a música engasgar, aumente. " +
                    "Menor = painel mais rápido.",
                fontSize = 14.sp,
            )
            Slider(
                value = gap, onValueChange = { gap = it },
                onValueChangeFinished = { settings.requestGapMs = gap.toLong() },
                valueRange = 0f..250f,
            )
            SwitchRow("Várias leituras por requisição (mais rápido, menos tráfego)", multi) {
                multi = it; settings.multiPid = it
            }
            SwitchRow("Gravar viagem em CSV", csv) { csv = it; settings.logCsv = it }
            Text("Mudanças nestas opções valem ao reconectar.", color = Muted, fontSize = 12.sp)

            SectionTitle("Registro da conexão", 20.sp)
            Text(
                "Se não conectar, tire um print desta parte: mostra onde o adaptador parou de responder.",
                color = Muted, fontSize = 12.sp,
            )
            val log by br.xc60.telemetria.obd.ConnLog.lines.collectAsState()
            if (log.isEmpty()) Text("—", color = Muted, fontSize = 12.sp)
            log.takeLast(14).forEach {
                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
