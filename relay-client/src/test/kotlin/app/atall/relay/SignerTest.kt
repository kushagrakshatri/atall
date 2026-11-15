package app.atall.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerTest {
    private val key = SoftwareDeviceKey()

    @Test fun signedStringIsExactlyTheContractFormat() {
        val s = RequestSigner.signedString("post", "/phone/tasks", 1763229600, """{"a":1}""".toByteArray())
        assertEquals("POST\n/phone/tasks\n1763229600\nAVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX-GI", s)
    }

    @Test fun emptyBodyHashesTheEmptyString() {
        val s = RequestSigner.signedString("GET", "/phone/agents", 1, ByteArray(0))
        assertEquals("GET\n/phone/agents\n1\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", s)
    }

    @Test fun queryStringIsPartOfThePath() {
        val s = RequestSigner.signedString("GET", "/phone/events?after=17", 5, ByteArray(0))
        assertTrue(s.startsWith("GET\n/phone/events?after=17\n5\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathMustBeRelativeToAtall() { RequestSigner.signedString("GET", "phone/agents", 1, ByteArray(0)) }

    @Test fun signatureIsBase64UrlDerAndVerifiesWithThePairedKey() {
        val body = """{"ref":"ab12"}""".toByteArray()
        val signed = RequestSigner(key).sign("dev_1", "POST", "/phone/tasks", body, 1763229600)
        assertFalse(signed.sig.contains('=') || signed.sig.contains('+') || signed.sig.contains('/'))
        val sig = B64.decodeAny(signed.sig)
        assertEquals(0x30.toByte(), sig[0]) // DER
        val pub = Ecdsa.publicKey(key.publicKeySpki)
        assertTrue(Ecdsa.verify(pub, signed.signedString.toByteArray(), sig))
        // The relay may convert to raw r||s (WebCrypto); that verifies too.
        assertTrue(Ecdsa.verify(pub, signed.signedString.toByteArray(), Ecdsa.derToRaw(sig)))
        assertFalse(Ecdsa.verify(pub, signed.signedString.replace("1763229600", "1763229601").toByteArray(), sig))
        assertEquals(mapOf("X-Atall-Device" to "dev_1", "X-Atall-Ts" to "1763229600", "X-Atall-Sig" to signed.sig), signed.headers())
    }

    @Test fun publicKeyIsP256Spki() {
        val spki = key.publicKeySpki
        assertEquals(91, spki.size) // SEQUENCE { id-ecPublicKey, prime256v1 } + 65-byte uncompressed point
        assertEquals("EC", Ecdsa.publicKey(spki).algorithm)
    }

    @Test fun derAndRawRoundTrip() {
        repeat(20) {
            val der = key.sign("x$it".toByteArray())
            val raw = Ecdsa.derToRaw(der)
            assertEquals(64, raw.size)
            assertTrue(Ecdsa.verify(Ecdsa.publicKey(key.publicKeySpki), "x$it".toByteArray(), Ecdsa.rawToDer(raw)))
        }
    }
}
