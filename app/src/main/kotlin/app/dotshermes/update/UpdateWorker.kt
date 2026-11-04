package app.dotshermes.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dotshermes.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Vérification en arrière-plan, deux fois par jour avec du réseau : une nouvelle version donne une
 * notification (une seule par version). Le téléchargement attend que l'utilisateur le demande.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = AppContainer.updater
        val release = updater.check(force = true) ?: return Result.success()
        if (!AppContainer.inForeground && updater.markNotified(release)) {
            UpdateNotifications.showAvailable(applicationContext, release)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        /** Idempotent : garde la planification existante, à appeler à chaque démarrage. */
        fun schedule(context: Context) {
            if (!AppContainer.updater.enabled) return
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
