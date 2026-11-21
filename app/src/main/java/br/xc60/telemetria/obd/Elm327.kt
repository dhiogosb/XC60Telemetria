package br.xc60.telemetria.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

sealed class ObdResult {
    data class Ok(val responder: Int, val payload: ByteArray) : ObdResult()
    data class Negative(val service: Int, val nrc: Int) : ObdResult()
    object NoData : ObdResult()
    data class Error(val message: String) : ObdResult()
}

/**
 * Protocolo ELM327 sobre o HS-CAN do Volvo P3 (XC60 2009–2017): ISO 15765-4, 11 bits, 500 kbit/s.
 *
 * Cabeçalhos ligados (ATH1) para saber qual módulo respondeu e remontar mensagens
 * de vários quadros (ISO-TP) nós mesmos — mais confiável que confiar no formato dos clones.
 */
class Elm327(private val transport: ObdTransport) {

    private val mutex = Mutex()
    private val requestMutex = Mutex()
    private var currentHeader: Int = -1
    var version: String = ""
        private set

    suspend fun initialize() {
        // "Acorda" o ELM: clones às vezes ignoram o primeiro comando depois de conectar.
        transport.write("\r".toByteArray(Charsets.US_ASCII))
        delay(400)
        transport.drain()

        version = reset()
        ConnLog.add("Adaptador: $version")
        delay(300)

        // Essenciais: sem eles a leitura não funciona.
        for (cmd in listOf("ATE0", "ATH1", "ATSP6")) {
            val r = clean(logged(cmd, 3000))
            if (!r.contains("OK")) throw IllegalStateException("O adaptador recusou $cmd ('$r').")
        }
        // Opcionais: alguns clones não conhecem e respondem '?', o que não atrapalha.
        for (cmd in listOf("ATL0", "ATS0", "ATAT1", "ATCAF1")) {
            try { logged(cmd, 2000) } catch (e: ObdTimeoutException) { ConnLog.add("$cmd sem resposta (ignorado)") }
        }
        currentHeader = -1
        setHeader(ECM)
        // Primeira requisição força o ELM a sincronizar com o barramento. Tenta duas vezes.
        var probe: ObdResult = ObdResult.NoData
        repeat(2) {
            probe = request(ECM, "0100", 8000)
            ConnLog.add("0100 → $probe")
            if (probe is ObdResult.Ok) return
            delay(500)
        }
        val why = when (val p = probe) {
            is ObdResult.Error -> p.message
            is ObdResult.NoData -> "NO DATA"
            else -> p.toString()
        }
        throw IllegalStateException(
            "O adaptador respondeu, mas a central do motor não ($why). A ignição está ligada?"
        )
    }

    /** ATZ com alternativas: clones lentos às vezes não respondem ao primeiro reset. */
    private suspend fun reset(): String {
        val attempts = listOf("ATZ" to 5000L, "ATWS" to 4000L, "ATI" to 3000L, "ATZ" to 6000L)
        for ((cmd, timeout) in attempts) {
            try {
                val r = clean(logged(cmd, timeout))
                if (r.isNotEmpty()) return VERSION.find(r)?.value ?: r
            } catch (e: ObdTimeoutException) {
                // Alguns clones mandam a versão mas esquecem o '>'.
                if (e.partial.contains("ELM", ignoreCase = true)) {
                    ConnLog.add("$cmd: resposta sem '>' (${clean(e.partial)})")
                    transport.drain()
                    return clean(e.partial)
                }
                ConnLog.add("$cmd: sem resposta em ${timeout}ms")
                transport.write("\r".toByteArray(Charsets.US_ASCII))
                delay(500)
                transport.drain()
            }
        }
        throw IllegalStateException(
            "O Bluetooth conectou, mas o adaptador não respondeu. Confira se ele está bem encaixado " +
                "na porta OBD (luz acesa), se nenhum outro app (Torque, Car Scanner) está usando ele " +
                "e tente tirar e pôr de novo na porta."
        )
    }

    private suspend fun logged(cmd: String, timeoutMs: Long): String {
        val r = sendRaw(cmd, timeoutMs)
        ConnLog.add("$cmd → ${clean(r).ifEmpty { "(vazio)" }}")
        return r
    }

    /** Envia texto cru e devolve a resposta até o '>'. */
    suspend fun sendRaw(cmd: String, timeoutMs: Long = 2000): String = mutex.withLock {
        transport.drain()
        transport.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        transport.readUntilPrompt(timeoutMs)
    }

    /** Define o módulo de destino (ATSH) e filtra a resposta dele (ATCRA = destino + 8). */
    suspend fun setHeader(tx: Int) {
        if (tx == currentHeader) return
        sendRaw("ATSH%03X".format(tx))
        if (tx == FUNCTIONAL) sendRaw("ATCRA") else sendRaw("ATCRA%03X".format(tx + 8))
        currentHeader = tx
    }

    /** Requisição de diagnóstico (ex.: "010C", "22XXXX") para o módulo [tx]. */
    suspend fun request(tx: Int, hex: String, timeoutMs: Long = 2000, allowClearDtc: Boolean = false): ObdResult {
        // TRAVA DE SEGURANÇA: todo comando para o carro passa por aqui. Só serviços de leitura saem.
        SafeCommand.check(hex, allowClearDtc)?.let { return ObdResult.Error("Bloqueado por segurança: $it") }
        // Cabeçalho + comando são atômicos: a leitura contínua e as ferramentas podem rodar juntas.
        return requestMutex.withLock {
            try {
                setHeader(tx)
                val raw = sendRaw(hex, timeoutMs)
                interpret(hex, raw)
            } catch (e: ObdTimeoutException) {
                ObdResult.Error(e.message ?: "timeout")
            }
        }
    }

    private fun interpret(requestHex: String, raw: String): ObdResult {
        val text = clean(raw)
        ERRORS.firstOrNull { text.contains(it) }?.let {
            return if (it == "NO DATA") ObdResult.NoData else ObdResult.Error(it)
        }
        val service = requestHex.take(2).toInt(16)
        val messages = parseCanResponse(raw)
        for ((responder, payload) in messages) {
            if (payload.isEmpty()) continue
            val first = payload[0].u()
            if (first == service + 0x40) return ObdResult.Ok(responder, payload)
            if (first == 0x7F && payload.size >= 3 && payload[1].u() == service) {
                return ObdResult.Negative(service, payload[2].u())
            }
        }
        return ObdResult.Error("Resposta inesperada: $text")
    }

    companion object {
        const val FUNCTIONAL = 0x7DF
        /** Central do motor (ECM) */
        const val ECM = 0x7E0
        /** Central do câmbio (TCM) */
        const val TCM = 0x7E1

        private val ERRORS = listOf(
            "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUFFER FULL",
            "STOPPED", "BUS ERROR", "DATA ERROR", "FB ERROR", "?"
        )
        private val HEX_LINE = Regex("^[0-9A-F]+$")
        private val VERSION = Regex("ELM\\d+\\s*v?[0-9.]+", RegexOption.IGNORE_CASE)

        private fun clean(s: String) = s.replace("\r", " ").replace("\n", " ").trim()

        /** Linhas no formato "7E8064100BE3EB811" → mensagens remontadas por módulo. */
        fun parseCanResponse(raw: String): List<Pair<Int, ByteArray>> {
            val frames = mutableListOf<Pair<Int, ByteArray>>()
            for (line0 in raw.split('\r', '\n')) {
                val line = line0.replace(" ", "").uppercase()
                if (line.length < 5 || !HEX_LINE.matches(line)) continue
                val data = line.substring(3)
                if (data.length % 2 != 0) continue
                frames += line.substring(0, 3).toInt(16) to hexToBytes(data)
            }
            val out = mutableListOf<Pair<Int, ByteArray>>()
            for ((header, list) in frames.groupBy({ it.first }, { it.second })) {
                for (msg in reassemble(list)) {
                    // Ignora "aguarde" (7F xx 78) e fica com a resposta real.
                    if (msg.size >= 3 && msg[0].u() == 0x7F && msg[2].u() == 0x78) continue
                    out += header to msg
                }
            }
            return out
        }

        /** Remontagem ISO-TP: quadro único (0x), primeiro quadro (1x) + consecutivos (2x). */
        private fun reassemble(frames: List<ByteArray>): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            var buf: ByteArrayOutputStream? = null
            var expected = 0
            for (f in frames) {
                if (f.isEmpty()) continue
                val pci = f[0].u()
                when (pci shr 4) {
                    0 -> {
                        val len = pci and 0x0F
                        out += f.copyOfRange(1, minOf(1 + len, f.size))
                    }
                    1 -> {
                        if (f.size < 2) continue
                        expected = ((pci and 0x0F) shl 8) or f[1].u()
                        buf = ByteArrayOutputStream().apply { write(f, 2, f.size - 2) }
                    }
                    2 -> buf?.write(f, 1, f.size - 1)
                }
                val b = buf
                if (b != null && b.size() >= expected) {
                    out += b.toByteArray().copyOf(expected)
                    buf = null
                }
            }
            return out
        }

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

fun Byte.u(): Int = toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.u()) }
