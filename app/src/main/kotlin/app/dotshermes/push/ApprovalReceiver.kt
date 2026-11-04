package app.dotshermes.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dotshermes.data.AgentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Boutons « Approuver » / « Refuser » d'une notification de feu vert, sans ouvrir l'app. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        val agent = intent.getStringExtra(Notifications.EXTRA_AGENT) ?: AgentStore.PRIMARY_ID
        val app = context.applicationContext
        Notifications.showApprovalPending(app, agent, approvalId, approved)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val delivered = QuickReply.answerApproval(agent, approvalId, approved)
                Notifications.showApprovalOutcome(app, agent, approvalId, approved, delivered)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_APPROVED = "approved"
    }
}
