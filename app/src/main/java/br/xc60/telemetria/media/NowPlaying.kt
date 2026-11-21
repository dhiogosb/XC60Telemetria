package br.xc60.telemetria.media

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager

/*
 * Sem acesso às notificações de propósito: no Brasil, a "proteção avançada contra fraudes" do
 * Google Play Protect bloqueia a instalação (fora da Play Store) de apps que pedem esse acesso.
 * Por isso o app não lê nome/capa da música; só sabe se há áudio tocando e qual app é o preferido.
 */

data class NowPlaying(
    val app: ExternalApp?,
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

object MediaSessions {
    /** Estado básico: app preferido + se há música tocando agora. */
    fun read(context: Context, preferredPkg: String?): NowPlaying {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val app = ExternalApp.byPkg(preferredPkg)
        return NowPlaying(
            app = app, pkg = preferredPkg.orEmpty(), title = "", artist = "", art = null,
            playing = audio.isMusicActive, positionMs = 0, durationMs = 0,
        )
    }
}
