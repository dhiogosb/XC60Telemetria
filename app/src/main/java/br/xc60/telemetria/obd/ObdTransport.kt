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

class ObdTimeoutException(msg: String) : IOException(msg)

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
 */
class BluetoothSppTransport(private val device: BluetoothDevice) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        close()
        val s = tryConnect { device.createRfcommSocketToServiceRecord(SPP_UUID) }
            ?: tryConnect { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            // Alguns clones ELM327 só aceitam o canal RFCOMM 1 direto.
            ?: tryConnect {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
            ?: throw IOException("Não foi possível conectar ao adaptador OBD")
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    private fun tryConnect(factory: () -> BluetoothSocket): BluetoothSocket? = try {
        factory().also { it.connect() }
    } catch (e: Exception) {
        null
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Não conectado")
        out.write(data)
        out.flush()
    }

    override fun drain() {
        val inp = input ?: return
        try {
            while (inp.available() > 0) inp.read()
        } catch (_: IOException) {
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: throw IOException("Não conectado")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) throw IOException("Conexão encerrada")
                val c = b.toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                if (System.currentTimeMillis() > deadline) {
                    throw ObdTimeoutException("Sem resposta do ELM327 em ${timeoutMs}ms")
                }
                delay(5)
            }
        }
        sb.toString()
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
