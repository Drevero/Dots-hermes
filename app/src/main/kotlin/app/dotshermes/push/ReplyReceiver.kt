package app.dotshermes.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.dotshermes.data.AgentStore
import app.dotshermes.data.model.MAIN_CHAT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Bouton « Répondre » de la notification : envoie le texte à Hermes sans ouvrir l'app. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val chat = intent.getStringExtra(Notifications.EXTRA_CHAT) ?: MAIN_CHAT
        // Notification d'avant le multi-agents : elle venait de l'agent historique.
        val agent = intent.getStringExtra(Notifications.EXTRA_AGENT) ?: AgentStore.PRIMARY_ID
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val delivered = QuickReply.send(agent, text, chat)
                Notifications.showOwnReply(context.applicationContext, agent, text, delivered, chat)
            } finally {
                pending.finish()
            }
        }
    }
}
