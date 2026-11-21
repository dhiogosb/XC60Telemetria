package br.xc60.telemetria.obd

/** Códigos de falha (DTC) padrão: modo 03 (confirmados) e 07 (pendentes). */
object Dtc {
    fun decode(b1: Int, b2: Int): String {
        val letter = "PCBU"[(b1 shr 6) and 0x03]
        val d1 = (b1 shr 4) and 0x03
        return "%c%d%X%02X".format(letter, d1, b1 and 0x0F, b2)
    }

    /** Resposta CAN: 43 N  A1 B1  A2 B2 ... */
    fun parse(payload: ByteArray): List<String> {
        if (payload.size < 2) return emptyList()
        val out = mutableListOf<String>()
        var i = 2
        while (i + 1 < payload.size) {
            val a = payload[i].u()
            val b = payload[i + 1].u()
            if (a != 0 || b != 0) out += decode(a, b)
            i += 2
        }
        return out
    }

    suspend fun read(elm: Elm327, tx: Int, mode: String): Result<List<String>> =
        when (val r = elm.request(tx, mode, 4000)) {
            is ObdResult.Ok -> Result.success(parse(r.payload))
            is ObdResult.NoData -> Result.success(emptyList())
            is ObdResult.Negative -> Result.failure(IllegalStateException("Recusado (NRC %02X)".format(r.nrc)))
            is ObdResult.Error -> Result.failure(IllegalStateException(r.message))
        }
}

/**
 * Trava de segurança aplicada a TODO comando enviado ao carro (Elm327.request) e ao console:
 * só deixa passar serviços de LEITURA (+ apagar falhas, quando pedido explicitamente).
 * Bloqueia escrita de configuração, rotinas, reset de central, controle de atuadores,
 * acesso de segurança e programação — tudo que pode impedir o carro de funcionar ou mexer no desempenho.
 */
object SafeCommand {
    private val READ_SERVICES = setOf(
        0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, // OBD-II (exceto 04 = apagar)
        0x19, // UDS: ler DTCs
        0x1A, // KWP: identificação
        0x21, // KWP: ler dados por identificador local
        0x22, // UDS: ler dados por DID
        0x3E, // tester present
    )
    /** Só consultas ao próprio ELM. O módulo de destino é escolhido no campo "Módulo" da tela. */
    private val SAFE_AT = Regex("^AT(I|DP|DPN|RV|@1)$")

    /**
     * [allowClearDtc]: única exceção liberada, o modo 04 (apagar falhas), usado só pelo botão
     * "Apagar falhas" com confirmação. Não altera configuração nem desempenho: a central volta
     * a aprender sozinha. Nunca liberado no console manual.
     */
    fun check(cmd: String, allowClearDtc: Boolean = false): String? {
        val c = cmd.replace(" ", "").uppercase()
        if (allowClearDtc && c == "04") return null
        if (c.isEmpty()) return "Comando vazio"
        if (c.startsWith("AT")) {
            return if (SAFE_AT.matches(c)) null else "Comando AT não permitido no console"
        }
        if (!Regex("^[0-9A-F]+$").matches(c) || c.length % 2 != 0) return "Use hexadecimal, ex.: 22F190"
        val service = c.take(2).toInt(16)
        return if (service in READ_SERVICES) null
        else "Serviço %02X bloqueado (apenas leitura é permitida)".format(service)
    }
}
