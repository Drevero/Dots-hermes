package app.dotshermes.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import app.dotshermes.AppContainer

/** Retour de la session d'installation : confirmation à afficher, succès ou échec. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android demande à l'utilisateur de confirmer : on ouvre sa fenêtre.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // En cas de succès, Android remplace l'app et arrête ce processus : voir [AppUpdatedReceiver].
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppContainer.updater.onInstallAborted()
            else -> AppContainer.updater.onInstallFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** L'app vient d'être remplacée par une nouvelle version : on le dit, et on relance la vérification périodique. */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateNotifications.showUpdated(context, AppContainer.updater.currentVersion)
        UpdateWorker.schedule(context)
    }
}
