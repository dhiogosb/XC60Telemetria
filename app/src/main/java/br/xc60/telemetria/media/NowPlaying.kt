package br.xc60.telemetria.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Serviço vazio: só existe para o Android liberar a leitura das sessões de mídia
 * (nome da música, capa, controles diretos do Spotify/YouTube Music/Deezer).
 * Não lê o conteúdo de nenhuma notificação.
 */
class MediaAccessService : NotificationListenerService()

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

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun requestAccess(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun controllers(context: Context): List<MediaController> = try {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(context, MediaAccessService::class.java))
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Sessão mais relevante: a que está tocando; se nenhuma, a do app preferido; senão a primeira. */
    fun active(context: Context, preferredPkg: String?): MediaController? {
        val list = controllers(context)
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.packageName == preferredPkg }
            ?: list.firstOrNull()
    }

    fun read(context: Context, preferredPkg: String?): NowPlaying? {
        val c = active(context, preferredPkg) ?: return null
        val md = c.metadata
        val st = c.playbackState
        // Posição atual estimada a partir do último update da sessão
        val pos = st?.let {
            if (it.state == PlaybackState.STATE_PLAYING && it.lastPositionUpdateTime > 0)
                it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
            else it.position
        } ?: 0L
        return NowPlaying(
            app = ExternalApp.byPkg(c.packageName),
            pkg = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = pos.coerceAtLeast(0),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }
}
