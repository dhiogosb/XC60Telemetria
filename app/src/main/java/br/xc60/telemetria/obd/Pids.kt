package br.xc60.telemetria.obd

enum class Priority { FAST, SLOW }

/**
 * PID do modo 01 (padrão OBD-II). [decode] recebe só os bytes de dados (A, B, ...).
 */
data class PidDef(
    val key: String,
    val pid: Int,
    val label: String,
    val unit: String,
    val bytes: Int,
    val priority: Priority,
    val warnAbove: Double? = null,
    val warnBelow: Double? = null,
    val decode: (IntArray) -> Double,
)

object Pids {
    val ALL = listOf(
        PidDef("rpm", 0x0C, "Rotação", "rpm", 2, Priority.FAST, warnAbove = 6000.0) { (it[0] * 256 + it[1]) / 4.0 },
        PidDef("speed", 0x0D, "Velocidade", "km/h", 1, Priority.FAST) { it[0].toDouble() },
        PidDef("map", 0x0B, "Pressão coletor", "kPa", 1, Priority.FAST) { it[0].toDouble() },
        PidDef("throttle", 0x11, "Acelerador", "%", 1, Priority.FAST) { it[0] * 100.0 / 255 },
        PidDef("maf", 0x10, "Fluxo de ar", "g/s", 2, Priority.FAST) { (it[0] * 256 + it[1]) / 100.0 },
        PidDef("load", 0x04, "Carga motor", "%", 1, Priority.FAST) { it[0] * 100.0 / 255 },

        PidDef("coolant", 0x05, "Água", "°C", 1, Priority.SLOW, warnAbove = 108.0) { it[0] - 40.0 },
        PidDef("iat", 0x0F, "Ar admissão", "°C", 1, Priority.SLOW, warnAbove = 60.0) { it[0] - 40.0 },
        PidDef("oil", 0x5C, "Óleo", "°C", 1, Priority.SLOW, warnAbove = 130.0) { it[0] - 40.0 },
        PidDef("voltage", 0x42, "Bateria", "V", 2, Priority.SLOW, warnBelow = 12.2, warnAbove = 15.0) { (it[0] * 256 + it[1]) / 1000.0 },
        PidDef("fuel", 0x2F, "Tanque", "%", 1, Priority.SLOW, warnBelow = 10.0) { it[0] * 100.0 / 255 },
        PidDef("baro", 0x33, "Pressão atm.", "kPa", 1, Priority.SLOW) { it[0].toDouble() },
        PidDef("ambient", 0x46, "Externa", "°C", 1, Priority.SLOW) { it[0] - 40.0 },
        PidDef("timing", 0x0E, "Avanço", "°", 1, Priority.SLOW) { it[0] / 2.0 - 64 },
        PidDef("stft1", 0x06, "Correção curta", "%", 1, Priority.SLOW) { (it[0] - 128) * 100.0 / 128 },
        PidDef("ltft1", 0x07, "Correção longa", "%", 1, Priority.SLOW) { (it[0] - 128) * 100.0 / 128 },
        PidDef("fuelRate", 0x5E, "Consumo motor", "L/h", 2, Priority.SLOW) { (it[0] * 256 + it[1]) / 20.0 },
    )

    val byPid = ALL.associateBy { it.pid }
    val byKey = ALL.associateBy { it.key }

    /** Lê as máscaras 0100/0120/0140/0160 e devolve o conjunto de PIDs suportados pela central. */
    suspend fun detectSupported(elm: Elm327, tx: Int = Elm327.ECM): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0x60) {
            val r = elm.request(tx, "01%02X".format(base))
            if (r !is ObdResult.Ok || r.payload.size < 6) break
            val mask = (r.payload[2].u() shl 24) or (r.payload[3].u() shl 16) or
                (r.payload[4].u() shl 8) or r.payload[5].u()
            for (i in 0 until 32) {
                if (mask and (1 shl (31 - i)) != 0) supported += base + i + 1
            }
            if (mask and 1 == 0) break // bit 0 diz se existe a próxima faixa
            base += 0x20
        }
        return supported
    }

    /**
     * Resposta de várias PIDs numa só requisição: 41 0C A B 0D A 0B A ...
     * Menos transações Bluetooth = menos disputa com o áudio.
     */
    fun decodeMulti(payload: ByteArray): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        var i = 1 // pula o 0x41
        while (i < payload.size) {
            val def = byPid[payload[i].u()] ?: break
            if (i + 1 + def.bytes > payload.size) break
            val data = IntArray(def.bytes) { payload[i + 1 + it].u() }
            out[def.key] = def.decode(data)
            i += 1 + def.bytes
        }
        return out
    }
}
