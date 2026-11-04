package app.dotshermes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.content.Intent
import android.os.Bundle
import app.dotshermes.push.Notifications
import app.dotshermes.update.UpdateNotifications
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.dotshermes.ui.HermesApp

class MainActivity : ComponentActivity() {

    /** Notification touchée : on ouvre la conversation du dot qui a écrit. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openChatFrom(intent)
    }

    private fun openChatFrom(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_CHAT)?.let { AppContainer.chatRepository.selectChat(it) }
        // Notification « mise à jour disponible » : l'interface ouvre le dialogue.
        if (intent?.getBooleanExtra(UpdateNotifications.EXTRA_OPEN_UPDATE, false) == true) AppContainer.updater.requestPrompt()
    }

    // Refus sans conséquence : l'app marche, on ne reçoit simplement pas de push.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barres système transparentes à icônes sombres : l'interface reste claire de bord à bord.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { HermesApp() }
        openChatFrom(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
