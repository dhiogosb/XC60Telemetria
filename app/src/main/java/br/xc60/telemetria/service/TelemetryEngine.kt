package br.xc60.telemetria.service

import android.content.Context
import br.xc60.telemetria.obd.Dtc
import br.xc60.telemetria.obd.Elm327
import br.xc60.telemetria.obd.ObdResult
import br.xc60.telemetria.obd.PidDef
import br.xc60.telemetria.obd.Pids
import br.xc60.telemetria.obd.Priority
import br.xc60.telemetria.obd.SafeCommand
import br.xc60.telemetria.obd.toHex
import br.xc60.telemetria.obd.u
import br.xc60.telemetria.park.ParkStore
import br.xc60.telemetria.park.SensorPos
import br.xc60.telemetria.park.SignalSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState(val label: String) {
    IDLE("Desconectado"),
    CONNECTING("Conectando ao adaptador…"),
    INITIALIZING("Iniciando ELM327…"),
    CONNECTED("Conectado"),
    ERROR("Erro"),
}

data class TripData(
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val elapsedS: Double = 0.0,
    val litersPerHour: Double? = null,
    val instantKmL: Double? = null,
) {
    val avgKmL: Double? get() = if (fuelL > 0.05) distanceKm / fuelL else null
    val avgSpeed: Double? get() = if (elapsedS > 10) distanceKm / (elapsedS / 3600) else null
}

data class TelemetryState(
    val conn: ConnState = ConnState.IDLE,
    val message: String = "",
    val elmVersion: String = "",
    val vin: String = "",
    val values: Map<String, Double> = emptyMap(),
    val supported: Set<Int> = emptySet(),
    val trip: TripData = TripData(),
    val requestsPerSecond: Double = 0.0,
    val boostBar: Double? = null,
    /** Ré engatada (só quando o apoio de estacionamento está ligado e calibrado). */
    val reverse: Boolean = false,
    /** Distância em cm por sensor; null = sem obstáculo ou sem leitura. */
    val park: Map<SensorPos, Double?> = emptyMap(),
)

/** Ponto único de acesso da interface ao estado e ao motor de telemetria. */
object Telemetry {
    val state = MutableStateFlow(TelemetryState())
    @Volatile var engine: TelemetryEngine? = null
}

class TelemetryEngine(
    private val context: Context,
    private val elm: Elm327,
    private val settings: Settings,
) {
    private val pauseCount = AtomicInteger(0)
    private var multiPid = settings.multiPid
    private var multiFailures = 0
    private var consecutiveErrors = 0
    private var logger: CsvLogger? = null

    /** Inicializa e roda o laço de leitura até cancelar ou perder a conexão. */
    suspend fun run() {
        Telemetry.state.update { it.copy(conn = ConnState.INITIALIZING, message = "") }
        elm.initialize()
        val supported = Pids.detectSupported(elm)
        val vin = readVin()
        Telemetry.state.update {
            it.copy(conn = ConnState.CONNECTED, elmVersion = elm.version, vin = vin, supported = supported)
        }
        if (settings.logCsv) logger = CsvLogger(context)

        val fast = Pids.ALL.filter { it.priority == Priority.FAST && it.pid in supported }
        val slow = Pids.ALL.filter { it.priority == Priority.SLOW && it.pid in supported }
        var slowIdx = 0
        var lastTick = System.nanoTime()
        var reqCount = 0
        var rateWindowStart = System.currentTimeMillis()

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (pauseCount.get() > 0) { delay(100); continue }

                val values = mutableMapOf<String, Double>()
                reqCount += readGroup(fast, values)
                // Lentos: um pequeno bloco por ciclo, em rodízio.
                if (slow.isNotEmpty()) {
                    val chunk = (0 until minOf(3, slow.size)).map { slow[(slowIdx + it) % slow.size] }
                    slowIdx = (slowIdx + chunk.size) % slow.size
                    reqCount += readGroup(chunk, values)
                }

                val now = System.nanoTime()
                val dt = (now - lastTick) / 1e9
                lastTick = now

                Telemetry.state.update { s ->
                    val merged = s.values + values
                    val trip = updateTrip(s.trip, merged, dt)
                    val boost = merged["map"]?.let { map -> (map - (merged["baro"] ?: 100.0)) / 100.0 }
                    s.copy(values = merged, trip = trip, boostBar = boost)
                }
                parkStep()
                logger?.write(Telemetry.state.value)

                val elapsed = System.currentTimeMillis() - rateWindowStart
                if (elapsed >= 2000) {
                    val rps = reqCount * 1000.0 / elapsed
                    Telemetry.state.update { it.copy(requestsPerSecond = rps) }
                    reqCount = 0
                    rateWindowStart = System.currentTimeMillis()
                }
                delay(settings.requestGapMs)
            }
        } finally {
            logger?.close()
            logger = null
        }
    }

    // ---------- Apoio de estacionamento (somente leitura, serviço 22) ----------

    private var inReverse = false
    private var lastReverseCheck = 0L

    /** Fora da ré: confere a marcha a cada ~0,5 s. Na ré: lê ré + sensores a cada ciclo. */
    private suspend fun parkStep() {
        val pc = ParkStore.config.value
        val rev = pc.reverse
        if (!pc.enabled || rev == null) {
            if (Telemetry.state.value.reverse) Telemetry.state.update { it.copy(reverse = false, park = emptyMap()) }
            inReverse = false
            return
        }
        val now = System.currentTimeMillis()
        if (!inReverse && now - lastReverseCheck < 500) return
        lastReverseCheck = now

        val cache = mutableMapOf<Pair<Int, Int>, ByteArray?>()
        suspend fun read(s: SignalSource): ByteArray? = cache.getOrPut(s.header to s.did) {
            val r = elm.request(s.header, "22%04X".format(s.did), 600)
            if (r is ObdResult.Ok && r.payload.size > 3) r.payload.copyOfRange(3, r.payload.size) else null
        }

        inReverse = read(rev)?.let { rev.extract(it) } == pc.reverseValue
        val dist: Map<SensorPos, Double?> = if (inReverse) {
            pc.sensors
                .filter { (pc.showFront || !it.pos.front) && it.source != null }
                .associate { s -> s.pos to read(s.source!!)?.let { d -> s.source.extract(d) }?.let { s.toCm(it) } }
        } else emptyMap()
        Telemetry.state.update { it.copy(reverse = inReverse, park = dist) }
    }

    /** Lê um grupo de PIDs. Devolve quantas requisições foram feitas. */
    private suspend fun readGroup(defs: List<PidDef>, into: MutableMap<String, Double>): Int {
        if (defs.isEmpty()) return 0
        var requests = 0
        if (multiPid) {
            for (chunk in defs.chunked(6)) { // CAN aceita até 6 PIDs por requisição
                val hex = "01" + chunk.joinToString("") { "%02X".format(it.pid) }
                val r = elm.request(Elm327.ECM, hex)
                requests++
                val decoded = if (r is ObdResult.Ok) Pids.decodeMulti(r.payload) else emptyMap()
                if (decoded.isEmpty()) {
                    handleResult(r)
                    if (++multiFailures >= 3) multiPid = false // clone não suporta: cai para uma por vez
                } else {
                    consecutiveErrors = 0
                    into += decoded
                }
            }
            if (multiPid) return requests
        }
        for (d in defs) {
            val r = elm.request(Elm327.ECM, "01%02X".format(d.pid))
            requests++
            handleResult(r)
            if (r is ObdResult.Ok && r.payload.size >= 2 + d.bytes) {
                into[d.key] = d.decode(IntArray(d.bytes) { r.payload[2 + it].u() })
            }
            delay(settings.requestGapMs / 2)
        }
        return requests
    }

    private fun handleResult(r: ObdResult) {
        if (r is ObdResult.Error) {
            if (++consecutiveErrors > 8) throw IOException("Comunicação perdida: ${r.message}")
        } else {
            consecutiveErrors = 0
        }
    }

    private fun updateTrip(t: TripData, v: Map<String, Double>, dt: Double): TripData {
        if (dt <= 0 || dt > 5) return t // pausa longa (ex.: scanner): não integra
        val speed = v["speed"] ?: 0.0
        val fuel = settings.fuelType
        val lph: Double? = v["fuelRate"]
            ?: v["maf"]?.takeIf { fuel.mafValid }?.let { maf -> maf * 3600.0 / (fuel.afr * fuel.densityGPerL) }
        val dist = t.distanceKm + speed * dt / 3600.0
        val liters = t.fuelL + (lph ?: 0.0) * dt / 3600.0
        val instant = if (lph != null && lph > 0.05 && speed > 3) speed / lph else null
        return t.copy(
            distanceKm = dist, fuelL = liters, elapsedS = t.elapsedS + dt,
            litersPerHour = lph, instantKmL = instant,
        )
    }

    fun resetTrip() = Telemetry.state.update { it.copy(trip = TripData()) }

    private suspend fun readVin(): String {
        val r = elm.request(Elm327.ECM, "0902", 4000)
        if (r !is ObdResult.Ok || r.payload.size < 4) return ""
        return r.payload.drop(3).map { it.u().toChar() }.filter { it.isLetterOrDigit() }.joinToString("")
    }

    // ---------- Ferramentas (pausam a leitura contínua) ----------

    private suspend fun <T> paused(block: suspend () -> T): T {
        pauseCount.incrementAndGet()
        try {
            delay(150) // deixa a requisição em andamento terminar
            return block()
        } finally {
            pauseCount.decrementAndGet()
        }
    }

    data class DtcReport(val confirmed: List<String>, val pending: List<String>, val error: String?)

    suspend fun readDtcs(tx: Int): DtcReport = paused {
        val c = Dtc.read(elm, tx, "03")
        val p = Dtc.read(elm, tx, "07")
        DtcReport(
            c.getOrDefault(emptyList()), p.getOrDefault(emptyList()),
            (c.exceptionOrNull() ?: p.exceptionOrNull())?.message,
        )
    }

    /** Modo 04: apaga falhas do motor. Só pelo botão, com confirmação do usuário. */
    suspend fun clearDtcs(): String = paused {
        when (val r = elm.request(Elm327.ECM, "04", 5000, allowClearDtc = true)) {
            is ObdResult.Ok -> "Falhas apagadas. Se a causa continuar, a luz volta a acender."
            is ObdResult.Negative -> "A central recusou (NRC %02X). Desligue o motor e deixe só a ignição ligada.".format(r.nrc)
            is ObdResult.NoData -> "Sem resposta da central"
            is ObdResult.Error -> r.message
        }
    }

    data class DidHit(val did: Int, val data: ByteArray)

    /**
     * Varre identificadores do serviço 22 (ler dado por DID) numa central da Volvo.
     * Só LEITURA. As que respondem viram candidatas a novos sensores no painel.
     */
    suspend fun scanDids(
        tx: Int, start: Int, end: Int,
        onProgress: (Int) -> Unit, onHit: (DidHit) -> Unit,
    ) = paused {
        for (did in start..end) {
            currentCoroutineContext().ensureActive()
            val r = elm.request(tx, "22%04X".format(did), 800)
            if (r is ObdResult.Ok && r.payload.size > 3) {
                onHit(DidHit(did, r.payload.copyOfRange(3, r.payload.size)))
            }
            if (did % 16 == 0) onProgress(did)
        }
        onProgress(end)
    }

    suspend fun readDid(tx: Int, did: Int): ByteArray? = paused {
        val r = elm.request(tx, "22%04X".format(did), 800)
        if (r is ObdResult.Ok && r.payload.size > 3) r.payload.copyOfRange(3, r.payload.size) else null
    }

    /** Console manual, protegido pela trava de leitura [SafeCommand]. */
    suspend fun console(tx: Int, cmd: String): String {
        SafeCommand.check(cmd)?.let { return "BLOQUEADO: $it" }
        val c = cmd.replace(" ", "").uppercase()
        return paused {
            if (c.startsWith("AT")) elm.sendRaw(c).trim()
            else when (val r = elm.request(tx, c, 3000)) {
                is ObdResult.Ok -> "%03X: %s".format(r.responder, r.payload.toHex())
                is ObdResult.Negative -> "Negativa: serviço %02X, NRC %02X (%s)".format(r.service, r.nrc, nrcText(r.nrc))
                is ObdResult.NoData -> "NO DATA"
                is ObdResult.Error -> "Erro: ${r.message}"
            }
        }
    }

    private fun nrcText(nrc: Int) = when (nrc) {
        0x11 -> "serviço não suportado"
        0x12 -> "subfunção não suportada"
        0x13 -> "tamanho inválido"
        0x22 -> "condições não atendidas"
        0x31 -> "fora da faixa / DID inexistente"
        0x33 -> "acesso de segurança necessário"
        else -> "desconhecido"
    }
}

/** Gravação em CSV (~1 linha por segundo) em Android/data/br.xc60.telemetria/files/logs. */
class CsvLogger(context: Context) {
    private val keys = Pids.ALL.map { it.key }
    private val writer: FileWriter
    private var lastWrite = 0L
    val file: File

    init {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        file = File(dir, "xc60_$name.csv")
        writer = FileWriter(file)
        writer.write("timestamp," + keys.joinToString(",") + ",boost_bar,lph,km_l\n")
    }

    fun write(s: TelemetryState) {
        val now = System.currentTimeMillis()
        if (now - lastWrite < 1000) return
        lastWrite = now
        val row = keys.joinToString(",") { k -> s.values[k]?.let { "%.2f".format(Locale.US, it) } ?: "" }
        fun f(v: Double?) = v?.let { "%.3f".format(Locale.US, it) } ?: ""
        writer.write("$now,$row,${f(s.boostBar)},${f(s.trip.litersPerHour)},${f(s.trip.instantKmL)}\n")
        writer.flush()
    }

    fun close() = try { writer.close() } catch (_: IOException) {}
}
