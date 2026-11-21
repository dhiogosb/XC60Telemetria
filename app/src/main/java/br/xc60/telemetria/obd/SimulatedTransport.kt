package br.xc60.telemetria.obd

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ELM327 falso para o modo demonstração. Responde como um XC60 T5 rodando:
 * mesmo formato de quadros CAN (cabeçalho + ISO-TP) que o carro de verdade,
 * então todo o caminho do app é exercitado — painel, falhas e varredura Volvo.
 */
class SimulatedTransport : ObdTransport {

    private var connected = false
    private var header = 0x7E0
    private var pending: String? = null
    private var dtcCleared = false
    private val t0 = System.currentTimeMillis()

    override val isConnected get() = connected

    override suspend fun connect() {
        delay(600)
        connected = true
    }

    override suspend fun write(data: ByteArray) {
        if (!connected) throw IOException("Simulador desligado")
        pending = String(data, Charsets.US_ASCII).trim().uppercase().replace(" ", "")
    }

    override fun drain() {}

    override fun close() { connected = false }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        delay(15) // latência típica de um ELM327 por Bluetooth
        val cmd = pending ?: return "?\r\r"
        pending = null
        return respond(cmd) + "\r\r"
    }

    private fun respond(cmd: String): String {
        if (cmd.startsWith("AT")) {
            return when {
                cmd == "ATZ" -> "ELM327 v1.5 (simulado)"
                cmd == "ATI" -> "ELM327 v1.5"
                cmd == "ATRV" -> "%.1fV".format(sim().voltage)
                cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)"
                cmd.startsWith("ATSH") -> { header = cmd.substring(4).toInt(16); "OK" }
                else -> "OK"
            }
        }
        val bytes = Elm327.hexToBytes(cmd)
        val payload = when (header) {
            0x7E0 -> ecm(bytes)
            0x7E1 -> tcm(bytes)
            0x7E5 -> park(bytes) // módulo de estacionamento FICTÍCIO, só do modo demo
            else -> null
        } ?: return "NO DATA"
        return frames(header + 8, payload)
    }

    // ---------- Central do motor ----------

    private fun ecm(req: ByteArray): ByteArray? {
        val service = req[0].u()
        return when (service) {
            0x01 -> mode01(req)
            0x03 -> if (dtcCleared) bytes(0x43, 0x00)
                    else bytes(0x43, 0x01, 0x04, 0x20) // P0420: catalisador abaixo da eficiência
            0x07 -> bytes(0x47, 0x00)
            0x04 -> { dtcCleared = true; bytes(0x44) }
            0x09 -> if (req.size >= 2 && req[1].u() == 0x02)
                bytes(0x49, 0x02, 0x01) + "DEMO0XC60SIMULADO".toByteArray() else null
            0x22 -> did(req, mapOf(
                0x0010 to { s: Sim -> bytes((s.coolant + 40).roundToInt()) },
                0x0024 to { s: Sim -> word(s.rpm.roundToInt()) },
                0x0042 to { s: Sim -> bytes((s.boost * 100 + 100).roundToInt()) },
                0xF190 to { _: Sim -> "DEMO0XC60SIMULADO".toByteArray() },
            ))
            else -> bytes(0x7F, service, 0x11)
        }
    }

    private fun tcm(req: ByteArray): ByteArray? = when (req[0].u()) {
        0x03 -> bytes(0x43, 0x00)
        0x07 -> bytes(0x47, 0x00)
        0x22 -> did(req, mapOf(
            0x0005 to { s: Sim -> bytes((s.atf + 40).roundToInt()) },
            0x0011 to { s: Sim -> bytes(if (s.reverse) 0x52 else s.gear) }, // 0x52 = "R"
        ))
        else -> bytes(0x7F, req[0].u(), 0x11)
    }

    /** Distâncias simuladas: bruto = cm / 2; 0xFF = nada detectado. */
    private fun park(req: ByteArray): ByteArray? = when (req[0].u()) {
        0x22 -> did(req, mapOf(0x4010 to { s: Sim -> s.parkRaw }))
        else -> bytes(0x7F, req[0].u(), 0x11)
    }

    private fun did(req: ByteArray, table: Map<Int, (Sim) -> ByteArray>): ByteArray {
        if (req.size < 3) return bytes(0x7F, 0x22, 0x13)
        val id = (req[1].u() shl 8) or req[2].u()
        val f = table[id] ?: return bytes(0x7F, 0x22, 0x31)
        return bytes(0x62, req[1].u(), req[2].u()) + f(sim())
    }

    private fun mode01(req: ByteArray): ByteArray {
        val s = sim()
        var out = bytes(0x41)
        for (i in 1 until req.size) {
            val pid = req[i].u()
            val data: ByteArray = when (pid) {
                // Máscaras de PIDs suportados (bit mais alto = PID base+1)
                0x00 -> bytes(0x1E, 0x3F, 0x80, 0x01) // 04-07, 0B-10, 11, +próxima faixa
                0x20 -> bytes(0x00, 0x02, 0x20, 0x01) // 2F, 33, +próxima faixa
                0x40 -> bytes(0x44, 0x00, 0x00, 0x10) // 42, 46, 5C
                0x04 -> bytes(pct(s.load))
                0x05 -> bytes((s.coolant + 40).roundToInt())
                0x06 -> bytes((s.stft * 128 / 100 + 128).roundToInt())
                0x07 -> bytes((2.3 * 128 / 100 + 128).roundToInt())
                0x0B -> bytes(s.map.roundToInt())
                0x0C -> word((s.rpm * 4).roundToInt())
                0x0D -> bytes(s.speed.roundToInt())
                0x0E -> bytes(((s.timing + 64) * 2).roundToInt())
                0x0F -> bytes((s.iat + 40).roundToInt())
                0x10 -> word((s.maf * 100).roundToInt())
                0x11 -> bytes(pct(s.throttle))
                0x2F -> bytes(pct(62.0))
                0x33 -> bytes(95) // ~ altitude de Brasília/Goiânia
                0x42 -> word((s.voltage * 1000).roundToInt())
                0x46 -> bytes(28 + 40)
                0x5C -> bytes((s.oil + 40).roundToInt())
                else -> continue // não suportado: fica fora da resposta, como no carro
            }
            out += bytes(pid) + data
        }
        return out
    }

    // ---------- Física simplificada de um ciclo de rodagem ----------

    private class Sim(
        val speed: Double, val rpm: Double, val throttle: Double, val load: Double,
        val map: Double, val boost: Double, val maf: Double, val coolant: Double,
        val oil: Double, val iat: Double, val atf: Double, val timing: Double,
        val stft: Double, val voltage: Double, val gear: Int,
        val reverse: Boolean = false, val parkRaw: ByteArray = ByteArray(8) { 0xFF.toByte() },
    )

    private fun sim(): Sim {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        // Ciclo de 100 s: 80 s rodando (arranca, ~110 km/h, para) + 20 s de manobra em ré.
        val cyc = t % 100.0
        if (cyc >= 80) return reverseSim(t, (cyc - 80) / 20)
        val phase = cyc / 80.0
        val speed = max(0.0, 110 * sin(PI * phase) + 6 * sin(t / 3))
        val accel = 110 * PI / 80 * kotlin.math.cos(PI * phase)
        val throttle = (12 + accel * 9 + 4 * sin(t * 1.7)).coerceIn(0.0, 100.0)
        val gear = when { speed < 1 -> 0; speed < 20 -> 1; speed < 40 -> 2; speed < 60 -> 3; speed < 80 -> 4; speed < 100 -> 5; else -> 6 }
        val ratio = doubleArrayOf(0.0, 120.0, 70.0, 47.0, 36.0, 29.0, 24.0)[gear]
        val rpm = if (gear == 0) 750 + 20 * sin(t * 4) else max(800.0, speed * ratio)
        val boost = ((throttle - 20) / 60 * 1.1).coerceIn(-0.6, 1.1)
        val map = 95 + boost * 100
        // ~2,5 g/s em marcha lenta, ~15 g/s a 100 km/h constante (≈ 10–12 km/L)
        val maf = rpm / 1000 * (2.5 + throttle * 0.18)
        val warm = min(1.0, t / 120)
        return Sim(
            speed = speed, rpm = rpm, throttle = throttle, load = (throttle * 0.9 + 15).coerceAtMost(100.0),
            map = map, boost = boost, maf = maf,
            coolant = 35 + 55 * warm + 1.5 * sin(t / 20), oil = 30 + 70 * warm,
            iat = 34 + boost * 8, atf = 30 + 52 * warm, timing = 18 - boost * 10,
            stft = 1.5 * sin(t * 2.3), voltage = 14.1 + 0.1 * sin(t / 7), gear = gear,
        )
    }

    /** Manobra de ré: aproxima de um obstáculo atrás (centro chega a ~25 cm) e passa perto de algo à frente-esquerda. */
    private fun reverseSim(t: Double, p: Double): Sim {
        val warm = min(1.0, t / 120)
        val center = 200 - 175 * p
        fun raw(cm: Double) = if (cm > 200) 0xFF else (cm / 2).roundToInt().coerceIn(0, 254)
        val park = intArrayOf(
            raw(center + 70), raw(center + 15), raw(center), raw(center + 55), // traseiros E, CE, CD, D
            raw(95 + 20 * sin(t)), 0xFF, 0xFF, 0xFF,                              // dianteiros
        )
        return Sim(
            speed = 3.0, rpm = 820 + 15 * sin(t * 4), throttle = 3.0, load = 18.0,
            map = 35.0, boost = -0.6, maf = 2.4,
            coolant = 35 + 55 * warm, oil = 30 + 70 * warm, iat = 30.0, atf = 30 + 52 * warm,
            timing = 10.0, stft = 0.5, voltage = 14.0, gear = 0,
            reverse = true, parkRaw = ByteArray(8) { park[it].toByte() },
        )
    }

    // ---------- Montagem dos quadros CAN no formato do ELM (ATH1 ATS0) ----------

    private fun frames(rx: Int, payload: ByteArray): String {
        val h = "%03X".format(rx)
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.u()) }
        if (payload.size <= 7) return h + "%02X".format(payload.size) + hex(payload)
        val lines = mutableListOf<String>()
        lines += h + "1%01X%02X".format(payload.size shr 8, payload.size and 0xFF) + hex(payload.copyOfRange(0, 6))
        var i = 6
        var seq = 1
        while (i < payload.size) {
            val end = min(i + 7, payload.size)
            lines += h + "2%01X".format(seq and 0x0F) + hex(payload.copyOfRange(i, end))
            i = end
            seq++
        }
        return lines.joinToString("\r")
    }

    private fun pct(v: Double) = (v.coerceIn(0.0, 100.0) * 255 / 100).roundToInt()
    private fun word(v: Int) = bytes((v shr 8) and 0xFF, v and 0xFF)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
