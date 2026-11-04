package app.dotshermes.update

/** Une release GitHub de l'app : sa version et les liens de son APK (et de son empreinte SHA-256). */
data class Release(
    /** « 0.2.0.12 », sans le « v » du tag. */
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Fichier `<apk>.sha256` publié à côté par la CI ; null pour une release sans empreinte. */
    val sha256Url: String?,
    val pageUrl: String,
)

/** « v0.2.0.12 » → [0, 2, 0, 12] ; null si ce n'est pas un numéro de version. */
internal fun versionParts(version: String): List<Int>? =
    version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

/**
 * [remote] est-elle plus récente que [local] ? Comparaison chiffre par chiffre, un chiffre manquant
 * valant 0 (« 0.2.0 » < « 0.2.0.3 »). Une version illisible n'est jamais proposée.
 */
internal fun isNewer(remote: String, local: String): Boolean {
    val r = versionParts(remote) ?: return false
    val l = versionParts(local) ?: return true
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
