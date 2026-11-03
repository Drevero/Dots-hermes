package app.dotshermes.push

import app.dotshermes.AppContainer
import app.dotshermes.data.model.MAIN_CHAT
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Réception FCM. Le plugin « dots » envoie des messages *data* uniquement (kind, id, title, body) :
 * c'est l'app qui compose la notification, pour garder le style conversation et la réponse rapide.
 */
class HermesMessagingService : FirebaseMessagingService() {

    private companion object {
        /** Préfixe des plugins « dots » d'avant la traduction : du protocole, pas un texte de l'app. */
        const val LEGACY_APPROVAL_PREFIX = "Feu vert demandé : "
    }

    override fun onNewToken(token: String) {
        AppContainer.chatRepository.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Le gateway ne pousse qu'app cachée, mais un push peut croiser le retour au premier plan.
        if (AppContainer.inForeground) return
        val body = message.data["body"].orEmpty()
        if (body.isBlank()) return
        val kind = message.data["kind"].orEmpty()
        val id = message.data["id"].orEmpty()
        if (kind == "approval" && id.isNotEmpty()) {
            // Le plugin envoie la commande seule ; les anciennes versions la préfixaient : on retire ce préfixe.
            Notifications.showApproval(this, id, body.removePrefix(LEGACY_APPROVAL_PREFIX))
        } else {
            Notifications.showAgentMessage(
                this, kind = kind, body = body,
                chat = message.data["chat"].orEmpty().ifEmpty { MAIN_CHAT }, title = message.data["title"].orEmpty(),
            )
        }
    }
}
