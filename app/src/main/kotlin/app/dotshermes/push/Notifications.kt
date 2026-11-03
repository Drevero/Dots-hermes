package app.dotshermes.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.dotshermes.AppContainer
import app.dotshermes.MainActivity
import app.dotshermes.R
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.ui.dots.appearance
import app.dotshermes.ui.dot.art
import androidx.compose.ui.graphics.toArgb

/** Notifications de conversation : une seule, empilée façon messagerie, avec réponse rapide. */
object Notifications {
    private const val CHANNEL = "hermes_messages"
    private const val CONVERSATION_ID = 1
    const val KEY_REPLY = "reply_text"
    const val EXTRA_CHAT = "chat"

    private val Accent = 0xFFFF8A3D.toInt()
    private val MarkdownMarks = Regex("""\*\*|`|^#{1,6}\s+""", RegexOption.MULTILINE)

    fun createChannel(context: Context) {
        // Recréé à chaque lancement : le nom et la description suivent la langue du téléphone.
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.notif_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Message d'un dot. Tous les dots partagent un seul fil de notification, chacun sous son nom :
     * [title] vient du gateway pour un sous-dot (vide pour le principal, dont le nom est choisi ici).
     */
    fun showAgentMessage(context: Context, kind: String, body: String, chat: String = MAIN_CHAT, title: String = "") {
        val text = when (kind) {
            "approval" -> "🔐 $body"
            "clarify" -> "❓ $body"
            else -> body
        }.replace(MarkdownMarks, "")
        post(context, alert = true, chat = chat) { style -> style.addMessage(text, System.currentTimeMillis(), sender(context, chat, title)) }
    }

    /** La réponse rapide s'ajoute au fil de la notification, sans re-sonner. */
    fun showOwnReply(context: Context, text: String, delivered: Boolean, chat: String = MAIN_CHAT) {
        val shown = if (delivered) text else context.getString(R.string.notif_reply_not_sent, text)
        post(context, alert = false, chat = chat) { style -> style.addMessage(shown, System.currentTimeMillis(), null as Person?) }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(CONVERSATION_ID)

    /**
     * Demande de feu vert : notification à part (une par commande), avec ses deux boutons.
     * La commande s'affiche en entier une fois dépliée : on décide en connaissance de cause.
     */
    fun showApproval(context: Context, approvalId: String, command: String) {
        if (!canNotify(context)) return
        fun answer(approved: Boolean) = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_hermes, context.getString(if (approved) R.string.action_approve else R.string.action_decline),
            PendingIntent.getBroadcast(
                context, (approvalId + approved).hashCode(),
                Intent(context, ApprovalReceiver::class.java)
                    .putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
                    .putExtra(ApprovalReceiver.EXTRA_APPROVED, approved),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setShowsUserInterface(false).build()
        val text = command.replace(MarkdownMarks, "")
        val notification = approvalBuilder(context, context.getString(R.string.notif_approval_title, agentName()), text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(answer(false))
            .addAction(answer(true))
            .setAutoCancel(false)
            .build()
        NotificationManagerCompat.from(context).notify(approvalNotificationId(approvalId), notification)
    }

    /** Juste après l'appui : les boutons disparaissent pendant l'envoi (pas de double réponse). */
    fun showApprovalPending(context: Context, approvalId: String, approved: Boolean) {
        if (!canNotify(context)) return
        val notification = approvalBuilder(
            context,
            context.getString(if (approved) R.string.notif_approval_approving else R.string.notif_approval_declining),
            context.getString(R.string.notif_approval_sending, agentName()),
        )
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        NotificationManagerCompat.from(context).notify(approvalNotificationId(approvalId), notification)
    }

    /** Résultat : confirmation qui s'efface seule, ou invitation à ouvrir l'app si l'envoi a échoué. */
    fun showApprovalOutcome(context: Context, approvalId: String, approved: Boolean, delivered: Boolean) {
        if (!canNotify(context)) return
        val builder = if (delivered) {
            approvalBuilder(
                context,
                context.getString(if (approved) R.string.notif_approval_approved else R.string.notif_approval_declined),
                context.getString(if (approved) R.string.notif_approval_approved_text else R.string.notif_approval_declined_text, agentName()),
            ).setTimeoutAfter(4_000)
        } else {
            approvalBuilder(context, context.getString(R.string.notif_approval_not_sent), context.getString(R.string.notif_approval_not_sent_text))
        }
        NotificationManagerCompat.from(context).notify(approvalNotificationId(approvalId), builder.setOnlyAlertOnce(true).build())
    }

    /** Réglé ailleurs (dans l'app, sur un autre appareil) : la notification n'a plus lieu d'être. */
    fun cancelApproval(context: Context, approvalId: String) =
        NotificationManagerCompat.from(context).cancel(approvalNotificationId(approvalId))

    private fun approvalNotificationId(approvalId: String) = 1_000 + (approvalId.hashCode() and 0x0FFFFFFF)

    private fun approvalBuilder(context: Context, title: String, text: String) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setColor(Accent)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(openApp(context))

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [chat] : conversation du dernier message ; « Répondre » et l'ouverture de l'app y mènent. */
    private fun post(context: Context, alert: Boolean, chat: String, add: (NotificationCompat.MessagingStyle) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val style = currentStyle(context) ?: NotificationCompat.MessagingStyle(me(context))
        add(style)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CHAT, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Mutable : le système doit pouvoir y glisser le texte saisi (RemoteInput).
        val reply = PendingIntent.getBroadcast(
            context, 1, Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CHAT, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_hermes, context.getString(R.string.action_reply), reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.composer_placeholder, dotName(chat))).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setColor(Accent)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .build()
        NotificationManagerCompat.from(context).notify(CONVERSATION_ID, notification)
    }

    /** Reprend le fil déjà affiché pour y empiler le nouveau message. */
    private fun currentStyle(context: Context): NotificationCompat.MessagingStyle? {
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == CONVERSATION_ID } ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)
    }

    private fun me(context: Context) = Person.Builder().setName(context.getString(R.string.notif_me)).setKey("me").build()

    /** Avatar mis en cache pour une mascotte + couleur ; recalculé seulement si l'apparence change. */
    private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, IconCompat>()

    /**
     * L'expéditeur : le dot principal sous le nom, la mascotte et la couleur choisis dans l'app, ou un
     * sous-dot avec les siens (connus si l'app tourne ; sinon son nom envoyé par le gateway suffit).
     */
    private fun sender(context: Context, chat: String, title: String): Person {
        val main = AppContainer.appearance.state.value
        val look = AppContainer.gateway.dots.value.firstOrNull { it.id == chat }?.appearance(main) ?: main
        val name = if (chat == MAIN_CHAT) main.displayName else title.ifBlank { look.displayName }
        val key = "${look.species.name}/${look.palette.id}"
        val icon = avatarCache[key] ?: IconCompat.createWithBitmap(avatar(context, look)).also { avatarCache[key] = it }
        return Person.Builder().setName(name).setKey(chat).setBot(true).setIcon(icon).build()
    }

    private fun dotName(chat: String): String =
        AppContainer.gateway.dots.value.firstOrNull { it.id == chat }?.name ?: agentName()

    private fun agentName() = AppContainer.appearance.state.value.displayName

    /** Avatar : le dot en pâte à modeler (corps, yeux, sourire) recadré sur un disque clair. */
    private fun avatar(context: Context, look: DotAppearance): Bitmap {
        val art = look.species.art
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = look.palette.light.toArgb()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        // Calques de 640 px : carré centré sur le corps, un peu d'air autour.
        val mid = ((art.bodyTop + art.bodyBottom) / 2 * 640).toInt()
        val src = Rect(110, mid - 210, 530, mid + 210)
        val dst = Rect(0, 0, size, size)
        for (layer in listOf(art.body(look.palette.id), art.eyesOpen, art.mouthSmile)) {
            BitmapFactory.decodeResource(context.resources, layer)?.let { canvas.drawBitmap(it, src, dst, paint) }
        }
        return out
    }
}
