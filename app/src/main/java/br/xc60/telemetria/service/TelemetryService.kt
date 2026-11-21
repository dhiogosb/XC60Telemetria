package br.xc60.telemetria.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import br.xc60.telemetria.obd.BluetoothSppTransport
import br.xc60.telemetria.obd.Elm327
import br.xc60.telemetria.obd.ObdTransport
import br.xc60.telemetria.obd.SimulatedTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mantém a conexão OBD viva em segundo plano (tela apagada, outro app na frente),
 * com reconexão automática. O áudio segue pelo A2DP do sistema, independente daqui.
 */
class TelemetryService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        br.xc60.telemetria.park.ParkStore.load(this)
        if (job?.isActive != true) job = scope.launch { connectLoop() }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectLoop() {
        val settings = Settings(this)
        val demo = settings.demoMode
        val address = settings.deviceAddress
        if (!demo && address == null) {
            Telemetry.state.update { it.copy(conn = ConnState.ERROR, message = "Escolha o adaptador em Config") }
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        while (true) {
            val transport: ObdTransport =
                if (demo) SimulatedTransport()
                else BluetoothSppTransport(
                    adapter?.getRemoteDevice(address!!) ?: error("Este aparelho não tem Bluetooth")
                )
            try {
                Telemetry.state.update { it.copy(conn = ConnState.CONNECTING, message = "") }
                transport.connect()
                val engine = TelemetryEngine(this, Elm327(transport), settings)
                Telemetry.engine = engine
                engine.run()
            } catch (e: CancellationException) {
                transport.close()
                throw e
            } catch (e: Exception) {
                Telemetry.state.update {
                    it.copy(conn = ConnState.ERROR, message = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                Telemetry.engine = null
                transport.close()
            }
            delay(3000) // tenta de novo (ex.: carro desligado e religado)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Telemetria", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("XC60 Telemetria")
            .setContentText("Lendo dados do carro pela OBD")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    override fun onDestroy() {
        scope.cancel()
        Telemetry.engine = null
        Telemetry.state.update { it.copy(conn = ConnState.IDLE, message = "") }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "telemetria"
        const val ACTION_STOP = "br.xc60.telemetria.STOP"

        fun start(context: Context) {
            val i = Intent(context, TelemetryService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TelemetryService::class.java).setAction(ACTION_STOP))
        }

        fun restart(context: Context) {
            context.stopService(Intent(context, TelemetryService::class.java))
            start(context)
        }
    }
}
