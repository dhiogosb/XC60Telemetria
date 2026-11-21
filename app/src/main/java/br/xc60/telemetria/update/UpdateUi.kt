package br.xc60.telemetria.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.xc60.telemetria.service.Telemetry
import br.xc60.telemetria.ui.Accent
import br.xc60.telemetria.ui.Muted
import br.xc60.telemetria.ui.PanelHi
import br.xc60.telemetria.ui.Pill
import br.xc60.telemetria.ui.TextHi
import br.xc60.telemetria.ui.Warn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val release: Release) : UpdateState()
    data class Downloading(val release: Release, val progress: Float) : UpdateState()
    data class Ready(val release: Release, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

object UpdateController {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var dismissedVersion: String? = null

    suspend fun check(manual: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = try {
            Updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
        } catch (e: Exception) {
            // Sem internet no carro é normal: só mostra erro quando o usuário pediu.
            if (manual) UpdateState.Failed("Não foi possível verificar: ${e.message ?: "sem conexão"}") else UpdateState.Idle
        }
    }

    suspend fun download(context: Context, release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        _state.value = try {
            val file = Updater.download(context, release) { p -> _state.value = UpdateState.Downloading(release, p) }
            UpdateState.Ready(release, file)
        } catch (e: Exception) {
            UpdateState.Failed("Falha ao baixar: ${e.message ?: "erro"}")
        }
    }

    fun dismiss(version: String) { dismissedVersion = version; _state.value = UpdateState.Idle }
    fun isDismissed(version: String) = dismissedVersion == version
}

/** Procura atualização ao abrir o app e depois a cada 6 horas. */
@Composable
fun AutoUpdateCheck() {
    LaunchedEffect(Unit) {
        delay(5_000) // deixa o app conectar ao carro primeiro
        while (true) {
            UpdateController.check()
            delay(6 * 60 * 60 * 1000L)
        }
    }
}

/** Faixa discreta no topo quando há versão nova. Instalar só com o carro parado. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by UpdateController.state.collectAsStateWithLifecycle()
    val tel = Telemetry.state.collectAsStateWithLifecycle().value
    val demo = remember { br.xc60.telemetria.service.Settings(context).demoMode }
    // Só vale a velocidade lida agora do carro real: a última leitura fica guardada depois que a
    // conexão cai, e no modo demonstração o "carro" simulado está sempre andando.
    val moving = !demo && tel.conn == br.xc60.telemetria.service.ConnState.CONNECTED &&
        (tel.values["speed"] ?: 0.0) > 3

    val (text, action, onAction) = when (val s = st) {
        is UpdateState.Available -> {
            if (UpdateController.isDismissed(s.release.version)) return
            Triple("Nova versão ${s.release.version} disponível", "Baixar") { scope.launch { UpdateController.download(context, s.release) } }
        }
        is UpdateState.Downloading -> Triple("Baixando versão ${s.release.version}… ${(s.progress * 100).toInt()}%", null, {})
        is UpdateState.Ready -> when {
            moving -> Triple("Versão ${s.release.version} pronta · instale com o carro parado", null, {})
            Updater.needsInstallPermission(context) ->
                Triple("Permita que o app instale atualizações", "Permitir") { Updater.openInstallPermissionSettings(context) }
            else -> Triple("Versão ${s.release.version} pronta para instalar", "Instalar") { Updater.install(context, s.file) }
        }
        is UpdateState.Failed -> Triple(s.message, "Ok") { UpdateController.dismiss("") }
        else -> return
    }

    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape).background(PanelHi).border(1.dp, if (st is UpdateState.Failed) Warn else Accent, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, color = TextHi, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null) Pill(action, true) { onAction() }
        val s = st
        if (s is UpdateState.Available) {
            Text(
                "Depois", color = Muted, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clickable { UpdateController.dismiss(s.release.version) }.padding(6.dp),
            )
        }
    }
}

/** Em Ajustes: botão para procurar atualização na hora e o resultado. */
@Composable
fun UpdateCheckRow() {
    val scope = rememberCoroutineScope()
    val st by UpdateController.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (val s = st) {
                UpdateState.Checking -> "Procurando…"
                UpdateState.UpToDate -> "Você está na versão mais nova"
                is UpdateState.Available -> "Versão ${s.release.version} disponível (aviso no topo)"
                is UpdateState.Downloading -> "Baixando…"
                is UpdateState.Ready -> "Pronta para instalar (aviso no topo)"
                is UpdateState.Failed -> s.message
                UpdateState.Idle -> "Atualizações pelo GitHub"
            },
            color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Pill("Procurar atualização", false) { scope.launch { UpdateController.check(manual = true) } }
    }
}
