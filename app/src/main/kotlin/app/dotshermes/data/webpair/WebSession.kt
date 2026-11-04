package app.dotshermes.data.webpair

import java.time.Instant

/** Un navigateur appairé avec un agent (trame `web_sessions`) : révocable depuis l'app. */
data class WebSession(
    val id: String,
    /** Choisi par le téléphone à l'appairage : « dots.vercel.app · 4 nov. 2026 18:05 ». */
    val label: String,
    val origin: String,
    val createdAt: Instant?,
    /** Dernière connexion du navigateur (le gateway la met à jour à chaque socket ouverte). */
    val lastSeen: Instant?,
)

/** Réponse d'un gateway à `web_token_create`. */
sealed interface WebTokenResult {
    /** Jeton web propre à ce navigateur, et l'identifiant de sa session (pour la révoquer). */
    data class Granted(val token: String, val sessionId: String) : WebTokenResult

    /** Le gateway ne fait pas confiance à cette origine : il faut l'ajouter à `DOTS_WEB_ORIGINS`. */
    data class OriginNotAllowed(val origin: String) : WebTokenResult

    /** Plugin trop ancien : il ne connaît pas la trame (`error: unknown_type`). */
    data object Unsupported : WebTokenResult

    /** Pas connecté, ou pas de réponse à temps. */
    data object Unavailable : WebTokenResult
}
