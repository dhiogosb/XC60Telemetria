package br.xc60.telemetria.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap

enum class AppKind { MUSIC, NAV }

/** Apps externos que o painel sabe abrir. Os pacotes precisam estar em <queries> no manifesto. */
enum class ExternalApp(val label: String, val pkg: String, val kind: AppKind) {
    SPOTIFY("Spotify", "com.spotify.music", AppKind.MUSIC),
    YT_MUSIC("YouTube Music", "com.google.android.apps.youtube.music", AppKind.MUSIC),
    DEEZER("Deezer", "deezer.android.app", AppKind.MUSIC),
    YOUTUBE("YouTube", "com.google.android.youtube", AppKind.MUSIC),
    WAZE("Waze", "com.waze", AppKind.NAV),
    MAPS("Google Maps", "com.google.android.apps.maps", AppKind.NAV);

    companion object {
        fun byPkg(pkg: String?) = entries.firstOrNull { it.pkg == pkg }
        val music get() = entries.filter { it.kind == AppKind.MUSIC }
        val nav get() = entries.filter { it.kind == AppKind.NAV }
    }
}

object AppLauncher {

    fun isInstalled(context: Context, app: ExternalApp): Boolean =
        context.packageManager.getLaunchIntentForPackage(app.pkg) != null

    fun icon(context: Context, app: ExternalApp): Bitmap? = try {
        context.packageManager.getApplicationIcon(app.pkg).toBitmap(128, 128)
    } catch (_: Exception) {
        null
    }

    /**
     * Abre o app. Com [sideBySide], pede ao Android para abrir em tela dividida ao lado do painel
     * (tablets com Android 12L+ fazem isso direto; nos mais antigos o painel precisa já estar dividido).
     * Se o app não estiver instalado, abre a página dele na Play Store.
     */
    fun open(context: Context, app: ExternalApp, sideBySide: Boolean) {
        val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
        if (launch == null) {
            openStore(context, app)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sideBySide) {
            launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        try { context.startActivity(launch) } catch (_: ActivityNotFoundException) {}
    }

    private fun openStore(context: Context, app: ExternalApp) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
