package br.xc60.telemetria.comfort

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/*
 * CONFORTO — regra de segurança do projeto:
 *
 *  1. Só existem as ações desta lista fechada (vidros, clima, teto/cortina, travas, porta-malas).
 *     Não há como enviar comando livre. Nada de motor, câmbio, freio, direção ou airbag.
 *  2. Porta-malas e destravar só com o carro PARADO (velocidade 0 lida da OBD).
 *  3. No carro real, o controle fica DESLIGADO: no XC60 P3 essas funções estão na rede
 *     MS-CAN (125 kbit/s), que o ELM327 genérico não alcança, e os comandos da central
 *     de conforto (CEM) são proprietários. Só ativar com adaptador MS-CAN e comandos
 *     confirmados no carro — nunca com comandos "chutados".
 */

enum class Window(val label: String) { FL("Diant. esq."), FR("Diant. dir."), RL("Tras. esq."), RR("Tras. dir.") }

sealed class ComfortAction(val needsStopped: Boolean = false) {
    data class WindowMove(val window: Window, val up: Boolean) : ComfortAction()
    data class AllWindows(val up: Boolean) : ComfortAction()
    data class ClimateTemp(val left: Boolean, val deltaHalfDegrees: Int) : ComfortAction()
    data class SunroofSet(val openPercent: Int) : ComfortAction()
    object SunroofTilt : ComfortAction()
    data class ShadeSet(val open: Boolean) : ComfortAction()
    data class Locks(val lock: Boolean) : ComfortAction(needsStopped = !lock)
    object TrunkOpen : ComfortAction(needsStopped = true)
}

data class ComfortState(
    val windows: Map<Window, Int> = Window.entries.associateWith { 0 }, // 0 = fechado, 100 = aberto
    val tempLeft: Double = 21.0,
    val tempRight: Double = 21.0,
    val sunroof: Int = 0,
    val sunroofTilted: Boolean = false,
    val shadeOpen: Boolean = false,
    val locked: Boolean = true,
    val trunkOpen: Boolean = false,
)

interface ComfortController {
    val available: Boolean
    val reason: String
    val state: StateFlow<ComfortState>
    suspend fun execute(action: ComfortAction): Result<Unit>
}

/** Porteiro: aplica as regras de segurança antes de qualquer controlador. */
class SafeComfort(private val inner: ComfortController, private val speedKmh: () -> Double?) : ComfortController by inner {
    override suspend fun execute(action: ComfortAction): Result<Unit> {
        if (!inner.available) return Result.failure(IllegalStateException(inner.reason))
        if (action.needsStopped) {
            val v = speedKmh()
            if (v == null) return Result.failure(IllegalStateException("Sem leitura de velocidade: ação bloqueada por segurança"))
            if (v > 0.5) return Result.failure(IllegalStateException("Só com o carro parado"))
        }
        if (action is ComfortAction.ClimateTemp && action.deltaHalfDegrees !in -2..2) {
            return Result.failure(IllegalArgumentException("Ajuste de temperatura inválido"))
        }
        return inner.execute(action)
    }
}

/** Carro real: ainda indisponível (ver regra 3 acima). */
class UnavailableComfort : ComfortController {
    override val available = false
    override val reason =
        "Indisponível neste carro: requer adaptador com acesso à rede MS-CAN e comandos da central de conforto confirmados."
    override val state: StateFlow<ComfortState> = MutableStateFlow(ComfortState())
    override suspend fun execute(action: ComfortAction) = Result.failure<Unit>(IllegalStateException(reason))
}

/** Modo demonstração: simula o carro respondendo, com tempos realistas. */
class SimulatedComfort : ComfortController {
    override val available = true
    override val reason = ""
    private val s = MutableStateFlow(ComfortState())
    override val state: StateFlow<ComfortState> = s

    override suspend fun execute(action: ComfortAction): Result<Unit> {
        when (action) {
            is ComfortAction.WindowMove -> animateWindow(action.window, if (action.up) 0 else 100)
            is ComfortAction.AllWindows -> repeat(11) {
                s.update { st -> st.copy(windows = st.windows.mapValues { (_, v) -> step(v, if (action.up) 0 else 100) }) }
                delay(120)
            }
            is ComfortAction.ClimateTemp -> s.update {
                val d = action.deltaHalfDegrees * 0.5
                if (action.left) it.copy(tempLeft = (it.tempLeft + d).coerceIn(16.0, 28.0))
                else it.copy(tempRight = (it.tempRight + d).coerceIn(16.0, 28.0))
            }
            is ComfortAction.SunroofSet -> {
                val target = action.openPercent.coerceIn(0, 100)
                if (target > 0) s.update { it.copy(shadeOpen = true) } // cortina abre junto, como no carro
                while (s.value.sunroof != target) {
                    s.update { it.copy(sunroof = step(it.sunroof, target), sunroofTilted = false) }
                    delay(90)
                }
            }
            ComfortAction.SunroofTilt -> s.update { it.copy(sunroofTilted = !it.sunroofTilted, sunroof = 0) }
            is ComfortAction.ShadeSet -> { delay(400); s.update { it.copy(shadeOpen = action.open) } }
            is ComfortAction.Locks -> { delay(250); s.update { it.copy(locked = action.lock) } }
            ComfortAction.TrunkOpen -> { delay(600); s.update { it.copy(trunkOpen = true, locked = false) } }
        }
        return Result.success(Unit)
    }

    private suspend fun animateWindow(w: Window, target: Int) {
        while (s.value.windows[w] != target) {
            s.update { st -> st.copy(windows = st.windows + (w to step(st.windows[w] ?: 0, target))) }
            delay(100)
        }
    }

    private fun step(v: Int, target: Int) = when {
        v < target -> minOf(target, v + 10)
        v > target -> maxOf(target, v - 10)
        else -> v
    }
}
