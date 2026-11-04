package app.dotshermes.data.webpair

import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Appairage de l'app web par QR code, côté téléphone (docs/appairage-web.md, version 1).
 *
 * Pur JVM (java.security, javax.crypto) : testable sans Android. Le schéma doit rester identique
 * octet pour octet à celui de l'app web (WebCrypto) : ECDH P-256 → HKDF-SHA256 → AES-256-GCM.
 */

/** Ce que porte le QR code du navigateur : son origine, l'adresse du dépôt sur le relais et sa clé publique. */
class PairQr(
    /** Origine normalisée, telle que le navigateur l'enverra dans l'en-tête `Origin` (`https://dots.vercel.app`). */
    val origin: String,
    /** L'`id` tel qu'imprimé (22 caractères base64url) : paramètre `id` du relais. */
    val idText: String,
    /** L'`id` décodé (16 octets) : sel HKDF. */
    val id: ByteArray,
    /** Clé publique ECDH du navigateur, point non compressé (65 octets, `0x04‖X‖Y`). */
    val publicKey: ByteArray,
) {
    /** Hôte affiché dans la confirmation (« dots.vercel.app », avec le port s'il y en a un). */
    val host: String get() = origin.substringAfter("://")

    /** Où déposer le message chiffré. */
    val relayUrl: String get() = "$origin/api/pair?id=$idText"
}

/** Pourquoi un QR code est refusé : l'écran le dit en clair. */
enum class PairQrError {
    /** Pas une URL d'appairage Dots (autre QR code, chemin ou fragment inattendu). */
    NotPairing,
    /** Version du QR code inconnue : l'app web est plus récente que l'app. */
    UnsupportedVersion,
    /** `http://` (ou autre) hors de l'exception debug : le jeton partirait en clair. */
    InsecureOrigin,
    /** Identifiant ou clé illisible (longueur, encodage, point hors de la courbe). */
    Malformed,
}

/** Résultat de [WebPairing.parse] : le QR code lu, ou la raison du refus. */
sealed interface PairQrParse {
    data class Valid(val qr: PairQr) : PairQrParse
    data class Invalid(val error: PairQrError) : PairQrParse
}

/** Corps déposé sur le relais : `{v, epk, iv, ct}`, tout en base64url sans `=`. */
data class PairDeposit(val epk: ByteArray, val iv: ByteArray, val ct: ByteArray) {
    fun toJson(): String =
        // Les valeurs base64url n'ont aucun caractère à échapper en JSON : pas besoin d'org.json (absent des tests JVM).
        """{"v":${WebPairing.VERSION},"epk":"${b64url(epk)}","iv":"${b64url(iv)}","ct":"${b64url(ct)}"}"""
}

internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

object WebPairing {
    const val VERSION = 1

    /** `info` HKDF et données associées AES-GCM : lient le chiffré à ce protocole et à sa version. */
    const val CONTEXT = "dots-pair-v1"

    private const val PATH = "/pair"
    private const val ID_BYTES = 16
    private const val ID_CHARS = 22
    private const val POINT_BYTES = 65
    private const val POINT_CHARS = 87
    private const val COORD_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Base64url : l'alphabet seul, une fois le bourrage éventuel retiré par [unpad]. */
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Lit le contenu d'un QR code : `https://<origine>/pair#v1.<id>.<pub>`.
     *
     * [allowLocalHttp] (builds debug seulement) accepte aussi `http://` vers `localhost`, `127.0.0.1`,
     * `10.0.2.2` (l'hôte vu depuis l'émulateur) ou une IPv4 privée : l'app web en développement (`vite`).
     */
    fun parse(raw: String, allowLocalHttp: Boolean = false): PairQrParse {
        val uri = try {
            URI(raw.trim())
        } catch (e: URISyntaxException) {
            return PairQrParse.Invalid(PairQrError.NotPairing)
        }
        val scheme = uri.scheme?.lowercase() ?: return PairQrParse.Invalid(PairQrError.NotPairing)
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return PairQrParse.Invalid(PairQrError.NotPairing)
        if (uri.rawPath != PATH || uri.rawQuery != null || uri.rawUserInfo != null) return PairQrParse.Invalid(PairQrError.NotPairing)
        val fragment = uri.rawFragment ?: return PairQrParse.Invalid(PairQrError.NotPairing)
        when (scheme) {
            "https" -> Unit
            "http" -> if (!allowLocalHttp || !isLocalHost(host)) return PairQrParse.Invalid(PairQrError.InsecureOrigin)
            else -> return PairQrParse.Invalid(PairQrError.NotPairing)
        }

        val parts = fragment.split('.')
        if (parts.size != 3 || !parts[0].startsWith("v")) return PairQrParse.Invalid(PairQrError.NotPairing)
        if (parts[0] != "v$VERSION") return PairQrParse.Invalid(PairQrError.UnsupportedVersion)
        // base64url sans `=` (section 1) ; un bourrage éventuel est toléré, puis retiré (2 `=` au plus).
        val (_, idPadded, pubPadded) = parts
        val idText = unpad(idPadded) ?: return PairQrParse.Invalid(PairQrError.Malformed)
        val pubText = unpad(pubPadded) ?: return PairQrParse.Invalid(PairQrError.Malformed)
        if (idText.length != ID_CHARS || pubText.length != POINT_CHARS || !B64URL.matches(idText) || !B64URL.matches(pubText)) {
            return PairQrParse.Invalid(PairQrError.Malformed)
        }
        val id = decode(idText)?.takeIf { it.size == ID_BYTES } ?: return PairQrParse.Invalid(PairQrError.Malformed)
        val pub = decode(pubText)?.takeIf { it.size == POINT_BYTES && it[0] == 0x04.toByte() } ?: return PairQrParse.Invalid(PairQrError.Malformed)
        if (!isOnCurve(pub)) return PairQrParse.Invalid(PairQrError.Malformed)

        // Le navigateur omet le port par défaut dans `Origin` : on fait de même, sinon le gateway
        // comparerait « https://x:443 » à « https://x » et refuserait.
        val port = uri.port.takeIf { it != -1 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        val origin = "$scheme://$host" + (port?.let { ":$it" } ?: "")
        return PairQrParse.Valid(PairQr(origin, idText, id, pub))
    }

    /** Hôtes de développement admis en `http://` (builds debug). */
    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when (octets[0]) {
            10 -> true
            192 -> octets[1] == 168
            172 -> octets[1] in 16..31
            else -> false
        }
    }

    /**
     * Chiffre [plaintext] pour le navigateur du QR code (ECIES, section 3 du document) :
     * paire éphémère P-256, secret ECDH (coordonnée X, 32 octets), HKDF-SHA256 (sel = `id`,
     * info = [CONTEXT]), puis AES-256-GCM (IV aléatoire de 12 octets, AAD = [CONTEXT], tag en fin).
     */
    fun encrypt(qr: PairQr, plaintext: ByteArray, random: SecureRandom = SecureRandom()): PairDeposit {
        val ephemeral = generateKeyPair(random)
        val params = (ephemeral.public as ECPublicKey).params
        val browserKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(decodePoint(qr.publicKey), params))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(browserKey, true)
        val secret = agreement.generateSecret()
        val key = hkdfSha256(ikm = secret, salt = qr.id, info = CONTEXT.toByteArray(Charsets.UTF_8), length = 32)
        secret.fill(0)

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(CONTEXT.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext)
        key.fill(0)
        return PairDeposit(epk = encodePoint((ephemeral.public as ECPublicKey).w), iv = iv, ct = ct)
    }

    internal fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /**
     * HKDF-SHA256 (RFC 5869), écrit à la main : ni Android ni le JDK ne l'exposent. [length] ≤ 32 × 255.
     * Extract : PRK = HMAC(sel, ikm) ; Expand : T(i) = HMAC(PRK, T(i-1) ‖ info ‖ i).
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        prk.fill(0)
        return out
    }

    /** Point non compressé `0x04‖X‖Y`, chaque coordonnée sur 32 octets (complétée à gauche par des zéros). */
    fun encodePoint(point: ECPoint): ByteArray =
        byteArrayOf(0x04) + fixed(point.affineX, COORD_BYTES) + fixed(point.affineY, COORD_BYTES)

    /** Inverse de [encodePoint] (la longueur et le préfixe `0x04` sont vérifiés par [parse]). */
    fun decodePoint(bytes: ByteArray): ECPoint {
        require(bytes.size == POINT_BYTES && bytes[0] == 0x04.toByte())
        return ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
    }

    /**
     * Entier non signé sur exactement [size] octets, big-endian. `BigInteger.toByteArray()` peut
     * ajouter un octet de signe (33 octets) ou être plus court (coordonnée commençant par des zéros).
     */
    internal fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val trimmed = if (raw.size > size && raw[0] == 0.toByte()) raw.copyOfRange(raw.size - size, raw.size) else raw
        require(trimmed.size <= size) { "valeur trop grande pour $size octets" }
        return ByteArray(size - trimmed.size) + trimmed
    }

    /**
     * La clé du QR est-elle un point de P-256 (y² = x³ + ax + b mod p) ? Refuse d'emblée un QR forgé
     * avec un point invalide, sans dépendre du contrôle (variable selon le fournisseur) de l'accord ECDH.
     */
    internal fun isOnCurve(point: ByteArray): Boolean {
        val params = p256
        val p = (params.curve.field as ECFieldFp).p
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        if (x >= p || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return left == right
    }

    /** Paramètres de secp256r1, tirés d'une clé générée : pas de constantes recopiées à la main. */
    private val p256: ECParameterSpec by lazy { (generateKeyPair().public as ECPublicKey).params }

    /** Retire un bourrage `=` final (2 au plus) ; null s'il y en a davantage. */
    private fun unpad(text: String): String? {
        val bare = text.trimEnd('=')
        return bare.takeIf { text.length - it.length <= 2 }
    }

    private fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
}
