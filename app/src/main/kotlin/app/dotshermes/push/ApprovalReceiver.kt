package app.dotshermes.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Boutons « Approuver » / « Refuser » d'une notification de feu vert, sans ouvrir l'app. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        val app = context.applicationContext
        Notifications.showApprovalPending(app, approvalId, approved)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val delivered = QuickReply.answerApproval(approvalId, approved)
                Notifications.showApprovalOutcome(app, approvalId, approved, delivered)
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
