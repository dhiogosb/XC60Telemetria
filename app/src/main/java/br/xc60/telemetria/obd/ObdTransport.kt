package br.xc60.telemetria.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [partial] = o que chegou antes de estourar o tempo (alguns clones não mandam o '>'). */
class ObdTimeoutException(msg: String, val partial: String = "") : IOException(msg)

/** Canal de bytes até o adaptador. Hoje: Bluetooth clássico (SPP). Dá para criar uma versão BLE depois. */
interface ObdTransport {
    suspend fun connect()
    suspend fun write(data: ByteArray)
    /** Lê até o prompt '>' do ELM327. */
    suspend fun readUntilPrompt(timeoutMs: Long): String
    fun drain()
    fun close()
    val isConnected: Boolean
}

/**
 * ELM327 por Bluetooth clássico (perfil serial SPP).
 * Roda em paralelo com o A2DP (som) sem conflito: são perfis diferentes no mesmo rádio.
 *
 * A leitura é feita por uma thread própria com read() bloqueante: em vários celulares
 * (Samsung inclusive) o available() do socket Bluetooth devolve 0 mesmo com dados chegando.
 *
 * [firstStrategy] escolhe por qual forma de conexão começar; o serviço vai alternando a cada
 * tentativa, porque alguns clones aceitam a conexão "segura" mas só conversam pelo canal 1.
 */
class BluetoothSppTransport(
    private val device: BluetoothDevice,
    private val firstStrategy: Int = 0,
) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val rx = LinkedBlockingQueue<Int>()
    @Volatile private var reader: Thread? = null
    @Volatile private var closed = false

    /** Forma de conexão que funcionou (para o registro). */
    var strategyName: String = ""
        private set

    override val isConnected: Boolean get() = socket?.isConnected == true && !closed

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        close()
        closed = false
        val strategies: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SPP seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "SPP inseguro" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "canal 1" to {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        val order = strategies.indices.map { strategies[(it + firstStrategy) % strategies.size] }
        var last: Exception? = null
        var s: BluetoothSocket? = null
        for ((name, factory) in order) {
            try {
                s = factory().also { it.connect() }
                strategyName = name
                break
            } catch (e: Exception) {
                last = e
            }
        }
        val sock = s ?: throw IOException(
            "Não foi possível conectar ao adaptador OBD (${last?.message ?: "sem detalhe"}). " +
                "Ele está ligado na porta OBD? Outro app está usando ele?"
        )
        socket = sock
        output = sock.outputStream
        startReader(sock.inputStream)
        // O módulo Bluetooth dos clones precisa de um instante antes de aceitar dados.
        delay(500)
    }

    private fun startReader(inp: InputStream) {
        rx.clear()
        reader = Thread({
            val buf = ByteArray(256)
            try {
                while (!closed) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) rx.put(buf[i].toInt() and 0xFF)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            rx.put(EOF)
        }, "elm327-rx").apply { isDaemon = true; start() }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Não conectado")
        out.write(data)
        out.flush()
    }

    override fun drain() {
        // Mantém o aviso de conexão encerrada, se houver.
        val hadEof = rx.contains(EOF)
        rx.clear()
        if (hadEof) rx.put(EOF)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                throw ObdTimeoutException("Sem resposta do ELM327 em ${timeoutMs}ms", sb.toString())
            }
            val b = rx.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (b == EOF) {
                rx.put(EOF)
                throw IOException("Conexão Bluetooth com o adaptador caiu")
            }
            val c = b.toChar()
            if (c == '>') break
            if (b != 0) sb.append(c) // alguns clones mandam bytes nulos
        }
        sb.toString()
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        reader?.interrupt()
        reader = null
        socket = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val EOF = -1
        const val STRATEGIES = 3
    }
}
