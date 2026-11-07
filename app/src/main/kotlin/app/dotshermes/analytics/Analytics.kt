package app.dotshermes.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Google Analytics (via Firebase) : utilisateurs actifs, temps réel compris, et écrans consultés.
 * Construite sans google-services.json, l'app n'a pas Firebase et rien n'est envoyé.
 *
 * Seuls des noms d'écran fixes partent : jamais un message, un nom d'agent ni l'adresse d'un gateway.
 */
object Analytics {
    private var lastScreen: String? = null

    fun screen(context: Context, name: String) {
        if (name == lastScreen || FirebaseApp.getApps(context).isEmpty()) return
        lastScreen = name
        FirebaseAnalytics.getInstance(context).logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, "HermesApp")
            },
        )
    }
}
