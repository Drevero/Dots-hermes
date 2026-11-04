package app.dotshermes.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dotshermes.MainActivity
import app.dotshermes.R

/** Notifications de mise à jour, sur un canal à part : on peut les couper sans perdre les messages. */
object UpdateNotifications {
    private const val CHANNEL = "hermes_updates"
    private const val ID = 2
    /** Extra de [MainActivity] : ouvrir le dialogue de mise à jour. */
    const val EXTRA_OPEN_UPDATE = "open_update"

    private val Accent = 0xFFFF8A3D.toInt()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.update_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** « Version X disponible » : un appui ouvre l'app sur le dialogue de mise à jour. */
    fun showAvailable(context: Context, release: Release) = post(
        context,
        context.getString(R.string.update_notif_available_title, release.version),
        context.getString(R.string.update_notif_available_text),
        openUpdate = true,
    )

    /** Après l'installation : Android a arrêté l'app, on confirme que la nouvelle version est là. */
    fun showUpdated(context: Context, version: String) = post(
        context,
        context.getString(R.string.update_notif_updated_title, version),
        context.getString(R.string.update_notif_updated_text),
        openUpdate = false,
    )

    private fun post(context: Context, title: String, text: String, openUpdate: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATE, openUpdate)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setColor(Accent)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }
}
