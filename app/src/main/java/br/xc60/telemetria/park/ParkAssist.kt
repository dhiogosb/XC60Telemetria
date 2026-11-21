package br.xc60.telemetria.park

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * APOIO VISUAL DE ESTACIONAMENTO — somente leitura.
 * Não substitui os bipes nem a tela original do carro (que continuam funcionando).
 * As fontes de dado (módulo + DID + byte) são calibradas pelo usuário em Ajustes,
 * porque os identificadores da Volvo não são públicos e variam por versão.
 */

enum class SensorPos(val label: String, val front: Boolean) {
    REAR_LEFT("Traseiro esq.", false),
    REAR_LEFT_CENTER("Traseiro centro-esq.", false),
    REAR_RIGHT_CENTER("Traseiro centro-dir.", false),
    REAR_RIGHT("Traseiro dir.", false),
    FRONT_LEFT("Dianteiro esq.", true),
    FRONT_LEFT_CENTER("Dianteiro centro-esq.", true),
    FRONT_RIGHT_CENTER("Dianteiro centro-dir.", true),
    FRONT_RIGHT("Dianteiro dir.", true),
}

/** Onde ler um valor: módulo (cabeçalho CAN), DID do serviço 22, posição e tamanho no dado. */
data class SignalSource(val header: Int, val did: Int, val byteIndex: Int, val bytes: Int = 1) {
    fun extract(data: ByteArray): Int? {
        if (byteIndex < 0 || byteIndex + bytes > data.size) return null
        var v = 0
        for (i in 0 until bytes) v = (v shl 8) or (data[byteIndex + i].toInt() and 0xFF)
        return v
    }
}

data class SensorConfig(
    val pos: SensorPos,
    val source: SignalSource? = null,
    /** distância (cm) = bruto × fator + deslocamento */
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    /** Acima disso (cm) considera "sem obstáculo". */
    val noObstacleAboveCm: Double = 200.0,
) {
    fun toCm(raw: Int): Double? = (raw * factor + offset).takeIf { it <= noObstacleAboveCm }?.coerceAtLeast(0.0)
}

data class ParkConfig(
    val enabled: Boolean = false,
    val showFront: Boolean = true,
    val reverse: SignalSource? = null,
    val reverseValue: Int = 0,
    val sensors: List<SensorConfig> = SensorPos.entries.map { SensorConfig(it) },
) {
    fun sensor(p: SensorPos) = sensors.firstOrNull { it.pos == p } ?: SensorConfig(p)
    val isConfigured get() = reverse != null

    companion object {
        /**
         * Exemplo usado pelo MODO DEMONSTRAÇÃO (carro simulado). Não são valores do XC60 real:
         * no carro, use a calibração em Ajustes → Estacionamento.
         */
        val DEMO = ParkConfig(
            enabled = true,
            showFront = true,
            reverse = SignalSource(0x7E1, 0x0011, 0),
            reverseValue = 0x52,
            sensors = SensorPos.entries.mapIndexed { i, p ->
                SensorConfig(p, SignalSource(0x7E5, 0x4010, i), factor = 2.0, offset = 0.0, noObstacleAboveCm = 200.0)
            },
        )
    }
}

/** Zonas de cor do desenho. */
enum class Zone { NONE, FAR, MID, NEAR, STOP }

fun zoneFor(cm: Double?): Zone = when {
    cm == null -> Zone.NONE
    cm < 30 -> Zone.STOP
    cm < 60 -> Zone.NEAR
    cm < 110 -> Zone.MID
    else -> Zone.FAR
}

object ParkStore {
    private val _config = MutableStateFlow(ParkConfig())
    val config: StateFlow<ParkConfig> = _config

    fun load(context: Context) {
        val json = context.getSharedPreferences("xc60", Context.MODE_PRIVATE).getString("park", null)
        _config.value = json?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: ParkConfig()
    }

    fun save(context: Context, cfg: ParkConfig) {
        _config.value = cfg
        context.getSharedPreferences("xc60", Context.MODE_PRIVATE).edit().putString("park", toJson(cfg).toString()).apply()
    }

    private fun srcJson(s: SignalSource?) = s?.let {
        JSONObject().put("h", it.header).put("d", it.did).put("i", it.byteIndex).put("n", it.bytes)
    }

    private fun srcFrom(o: JSONObject?) = o?.let { SignalSource(it.getInt("h"), it.getInt("d"), it.getInt("i"), it.optInt("n", 1)) }

    private fun toJson(c: ParkConfig) = JSONObject()
        .put("enabled", c.enabled).put("front", c.showFront)
        .put("rev", srcJson(c.reverse)).put("revVal", c.reverseValue)
        .put("sensors", JSONArray().apply {
            c.sensors.forEach { s ->
                put(JSONObject().put("pos", s.pos.name).put("src", srcJson(s.source))
                    .put("f", s.factor).put("o", s.offset).put("max", s.noObstacleAboveCm))
            }
        })

    private fun fromJson(o: JSONObject): ParkConfig {
        val arr = o.optJSONArray("sensors") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SensorConfig(
                SensorPos.valueOf(s.getString("pos")), srcFrom(s.optJSONObject("src")),
                s.optDouble("f", 1.0), s.optDouble("o", 0.0), s.optDouble("max", 200.0),
            )
        }
        return ParkConfig(
            enabled = o.optBoolean("enabled"), showFront = o.optBoolean("front", true),
            reverse = srcFrom(o.optJSONObject("rev")), reverseValue = o.optInt("revVal"),
            sensors = SensorPos.entries.map { p -> list.firstOrNull { it.pos == p } ?: SensorConfig(p) },
        )
    }
}
