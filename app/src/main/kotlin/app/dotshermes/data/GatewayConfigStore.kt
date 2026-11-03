package app.dotshermes.data

import android.content.Context
import app.dotshermes.data.model.GatewayConfig

/**
 * Appairage mémorisé pour se reconnecter au lancement. Préférences privées de l'app
 * (sauvegarde Android désactivée dans le manifeste pour que le jeton ne quitte pas le téléphone).
 */
class GatewayConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun load(): GatewayConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return GatewayConfig(url, token)
    }

    fun save(config: GatewayConfig) = prefs.edit().putString(KEY_URL, config.url).putString(KEY_TOKEN, config.token).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
    }
}
