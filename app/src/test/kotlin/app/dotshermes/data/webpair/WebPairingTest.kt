package app.dotshermes.data.webpair

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPairingTest {

    private val random = SecureRandom()

    /** Le navigateur : sa paire P-256, son `id`, et l'URL de son QR code. */
    private class Browser(val keys: KeyPair, val id: ByteArray, val url: String)

    private fun browser(origin: String = "https://dots.vercel.app"): Browser {
        val keys = WebPairing.generateKeyPair()
        val id = ByteArray(16).also(random::nextBytes)
        val pub = WebPairing.encodePoint((keys.public as ECPublicKey).w)
        return Browser(keys, id, "$origin/pair#v1.${b64url(id)}.${b64url(pub)}")
    }

    private fun valid(raw: String, debug: Boolean = false): PairQr =
        (WebPairing.parse(raw, debug) as? PairQrParse.Valid)?.qr ?: error("QR refusé : ${WebPairing.parse(raw, debug)}")

    private fun rejection(raw: String, debug: Boolean = false): PairQrError? = (WebPairing.parse(raw, debug) as? PairQrParse.Invalid)?.error

    // -- Lecture de l'URL ----------------------------------------------------------------------------

    @Test
    fun `parse lit l'origine, l'id et la cle`() {
        val b = browser()
        val qr = valid(b.url)
        assertEquals("https://dots.vercel.app", qr.origin)
        assertEquals("dots.vercel.app", qr.host)
        assertEquals(22, qr.idText.length)
        assertArrayEquals(b.id, qr.id)
        assertArrayEquals(WebPairing.encodePoint((b.keys.public as ECPublicKey).w), qr.publicKey)
        assertEquals("https://dots.vercel.app/api/pair?id=${qr.idText}", qr.relayUrl)
    }

    @Test
    fun `l'origine est normalisee comme l'en-tete Origin du navigateur`() {
        val b = browser()
        val fragment = b.url.substringAfter('#')
        assertEquals("https://dots.vercel.app", valid("HTTPS://Dots.Vercel.APP/pair#$fragment").origin)
        assertEquals("https://dots.vercel.app", valid("https://dots.vercel.app:443/pair#$fragment").origin)
        assertEquals("https://dots.example.com:8443", valid("https://dots.example.com:8443/pair#$fragment").origin)
        assertEquals("https://dots.vercel.app", valid("  https://dots.vercel.app/pair#$fragment\n").origin)
    }

    @Test
    fun `http refuse en release, admis en debug vers un hote local`() {
        val fragment = browser().url.substringAfter('#')
        for (host in listOf("localhost:5173", "127.0.0.1:5173", "10.0.2.2:5173", "192.168.1.20:5173", "10.1.2.3", "172.16.0.1", "172.31.255.255")) {
            assertEquals(host, PairQrError.InsecureOrigin, rejection("http://$host/pair#$fragment", debug = false))
            assertEquals("http://$host", valid("http://$host/pair#$fragment", debug = true).origin)
        }
        assertEquals("http://localhost", valid("http://localhost:80/pair#$fragment", debug = true).origin)
        for (host in listOf("dots.vercel.app", "172.32.0.1", "172.15.0.1", "192.169.0.1", "8.8.8.8", "11.0.0.1", "localhost.evil.com")) {
            assertEquals(host, PairQrError.InsecureOrigin, rejection("http://$host/pair#$fragment", debug = true))
        }
        // IPv4 invalide : java.net.URI n'y voit pas d'hôte, le QR est refusé d'emblée.
        assertTrue(WebPairing.parse("http://10.0.0.256/pair#$fragment", allowLocalHttp = true) is PairQrParse.Invalid)
        assertFalse(WebPairing.isLocalHost("10.0.0.256"))
    }

    @Test
    fun `autres QR codes refuses`() {
        val b = browser()
        val fragment = b.url.substringAfter('#')
        assertEquals(PairQrError.NotPairing, rejection("WIFI:S:maison;T:WPA;P:secret;;"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/pair"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/other#$fragment"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/pair/#$fragment"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/pair?x=1#$fragment"))
        assertEquals(PairQrError.NotPairing, rejection("https://user@dots.vercel.app/pair#$fragment"))
        assertEquals(PairQrError.NotPairing, rejection("ftp://dots.vercel.app/pair#$fragment"))
        assertEquals(PairQrError.NotPairing, rejection("https://dots.vercel.app/pair#${fragment.substringAfter('.')}"))
        assertEquals(PairQrError.UnsupportedVersion, rejection("https://dots.vercel.app/pair#v2.${fragment.substringAfter('.')}"))
    }

    @Test
    fun `id ou cle mal formes refuses`() {
        val b = browser()
        val (_, id, pub) = b.url.substringAfter('#').split('.')
        val base = "https://dots.vercel.app/pair#v1"
        assertEquals(PairQrError.Malformed, rejection("$base.${id.dropLast(1)}.$pub"))
        // Bourrage `=` toléré au décodage (le QR n'en a pas, mais un encodeur base64url pourrait en mettre).
        assertArrayEquals(b.id, valid("$base.$id==.$pub=").id)
        assertEquals(PairQrError.Malformed, rejection("$base.$id===.$pub"))
        assertEquals(PairQrError.Malformed, rejection("$base.${id.dropLast(2)}==.$pub"))
        assertEquals(PairQrError.Malformed, rejection("$base.${id.dropLast(1)}+.$pub"))
        assertEquals(PairQrError.Malformed, rejection("$base.$id.${pub.dropLast(1)}"))
        // Préfixe 0x02 (point compressé) au lieu de 0x04.
        val compressed = Base64.getUrlDecoder().decode(pub).also { it[0] = 0x02 }
        assertEquals(PairQrError.Malformed, rejection("$base.$id.${b64url(compressed)}"))
        // Point hors de la courbe : Y modifié.
        val offCurve = Base64.getUrlDecoder().decode(pub).also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertEquals(PairQrError.Malformed, rejection("$base.$id.${b64url(offCurve)}"))
    }

    // -- Encodage des points ---------------------------------------------------------------------------

    @Test
    fun `coordonnees completees a gauche sur 32 octets`() {
        val small = WebPairing.encodePoint(ECPoint(BigInteger.ONE, BigInteger.valueOf(0x0102)))
        assertEquals(65, small.size)
        assertEquals(0x04.toByte(), small[0])
        assertArrayEquals(ByteArray(31) + byteArrayOf(1), small.copyOfRange(1, 33))
        assertArrayEquals(ByteArray(30) + byteArrayOf(1, 2), small.copyOfRange(33, 65))

        // Bit de poids fort à 1 : BigInteger.toByteArray() ajoute un octet de signe, qu'il faut retirer.
        val high = BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        assertEquals(33, high.toByteArray().size)
        assertArrayEquals(ByteArray(32) { 0xFF.toByte() }, WebPairing.fixed(high, 32))
        assertEquals(high, WebPairing.decodePoint(WebPairing.encodePoint(ECPoint(high, high))).affineX)
    }

    @Test
    fun `les cles generees font toujours 65 octets`() {
        repeat(200) {
            val point = WebPairing.encodePoint((WebPairing.generateKeyPair().public as ECPublicKey).w)
            assertEquals(65, point.size)
            assertTrue(WebPairing.isOnCurve(point))
        }
    }

    // -- HKDF ------------------------------------------------------------------------------------------

    @Test
    fun `hkdf conforme aux vecteurs de la RFC 5869`() {
        // Cas de test 1 (SHA-256).
        val okm = WebPairing.hkdfSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
        // Cas de test 3 : sel et info vides.
        val noSalt = WebPairing.hkdfSha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), noSalt)
    }

    // -- Chiffrement -----------------------------------------------------------------------------------

    @Test
    fun `aller-retour, le navigateur dechiffre comme WebCrypto`() {
        val b = browser()
        val qr = valid(b.url)
        val message = """{"v":1,"agents":[{"url":"wss://hermes.example.com/dots/ws","name":"Norti ☁️"}],"lang":"fr"}"""
        val deposit = WebPairing.encrypt(qr, message.toByteArray(Charsets.UTF_8))

        assertEquals(65, deposit.epk.size)
        assertEquals(0x04.toByte(), deposit.epk[0])
        assertEquals(12, deposit.iv.size)
        assertEquals(message.toByteArray(Charsets.UTF_8).size + 16, deposit.ct.size)
        assertEquals(message, String(browserDecrypt(b, deposit), Charsets.UTF_8))
    }

    @Test
    fun `chaque depot a sa cle ephemere et son iv`() {
        val b = browser()
        val qr = valid(b.url)
        val first = WebPairing.encrypt(qr, "x".toByteArray())
        val second = WebPairing.encrypt(qr, "x".toByteArray())
        assertFalse(first.epk.contentEquals(second.epk))
        assertFalse(first.iv.contentEquals(second.iv))
    }

    @Test
    fun `un autre navigateur, un autre id ou un chiffre altere ne dechiffrent pas`() {
        val b = browser()
        val deposit = WebPairing.encrypt(valid(b.url), "secret".toByteArray())

        val other = browser()
        assertDecryptFails { browserDecrypt(Browser(other.keys, b.id, b.url), deposit) }
        assertDecryptFails { browserDecrypt(Browser(b.keys, ByteArray(16), b.url), deposit) }
        val tampered = deposit.copy(ct = deposit.ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertDecryptFails { browserDecrypt(b, tampered) }
        assertDecryptFails { browserDecrypt(b, deposit, aad = "dots-pair-v2") }
    }

    @Test
    fun `corps depose au format du relais`() {
        val deposit = PairDeposit(epk = ByteArray(65) { 4 }, iv = ByteArray(12) { 0xFB.toByte() }, ct = ByteArray(20) { 0xFF.toByte() })
        val json = deposit.toJson()
        assertEquals("""{"v":1,"epk":"${b64url(deposit.epk)}","iv":"${b64url(deposit.iv)}","ct":"${b64url(deposit.ct)}"}""", json)
        // base64url sans bourrage : ni `+`, ni `/`, ni `=`.
        assertFalse(json.contains('+') || json.contains('/') || json.contains('='))
    }

    /**
     * Côté navigateur, réécrit indépendamment de [WebPairing.encrypt] comme le fait l'app web :
     * `deriveBits(ECDH, 256)` → HKDF-SHA256 (sel = id, info = "dots-pair-v1") → AES-GCM (aad, tag 128 bits).
     */
    private fun browserDecrypt(b: Browser, deposit: PairDeposit, aad: String = "dots-pair-v1"): ByteArray {
        val params = (b.keys.public as ECPublicKey).params
        val ephemeral = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, deposit.epk.copyOfRange(1, 33)), BigInteger(1, deposit.epk.copyOfRange(33, 65))), params),
        )
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(b.keys.private)
        agreement.doPhase(ephemeral, true)
        val secret = agreement.generateSecret()
        assertEquals(32, secret.size)
        val key = WebPairing.hkdfSha256(secret, b.id, "dots-pair-v1".toByteArray(), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, deposit.iv))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(deposit.ct)
    }

    private fun assertDecryptFails(block: () -> Unit) {
        try {
            block()
            fail("le déchiffrement aurait dû échouer")
        } catch (e: AEADBadTagException) {
            // attendu : tag GCM invalide
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
