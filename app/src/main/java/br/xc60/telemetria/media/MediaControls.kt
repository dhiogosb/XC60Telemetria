package br.xc60.telemetria.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Controle do player pelas teclas de mídia do sistema: o Android entrega o comando ao app
 * que está tocando (Spotify, YouTube Music, Deezer…). Não precisa de nenhuma permissão especial.
 * O som vai para a multimídia do XC60 pelo A2DP, que o Android gerencia sozinho.
 */
class MediaControls(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var preferredPkg: String? = null

    @Suppress("UNUSED_PARAMETER")
    fun playPause(playing: Boolean) = key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun volumeUp() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    fun volumeDown() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)

    val volumeFraction: Float
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    /** Áudio está saindo pelo Bluetooth (multimídia do carro)? */
    val isOnBluetooth: Boolean
        get() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
