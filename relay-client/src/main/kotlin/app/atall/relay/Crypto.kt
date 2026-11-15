package app.atall.relay

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The phone's P-256 device key. On a phone this is a non-exportable Android Keystore key
 * (`app.atall.relay.android.KeystoreDeviceKey`); tests use [SoftwareDeviceKey].
 */
interface DeviceKey {
    /** SubjectPublicKeyInfo DER of the P-256 public key (what /phone/pair takes as `public_key_spki_b64`). */
    val publicKeySpki: ByteArray

    /** ECDSA P-256 with SHA-256 over [data]; DER-encoded (the relay accepts DER or raw r||s). */
    fun sign(data: ByteArray): ByteArray
}

/** An in-memory P-256 key. For JVM tests and tools only; the app uses the Android Keystore key. */
class SoftwareDeviceKey(val keyPair: KeyPair = generate()) : DeviceKey {
    override val publicKeySpki: ByteArray get() = keyPair.public.encoded
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(data); sign() }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    }
}

object B64 {
    /** base64url without padding, the encoding every signature and hash in the contract uses. */
    fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Standard base64 with padding (`*_b64` fields such as `public_key_spki_b64`). */
    fun std(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Decodes either alphabet, with or without padding (the relay's fields aren't all pinned down). */
    fun decodeAny(text: String): ByteArray {
        val s = text.trim().replace('-', '+').replace('_', '/').replace("=", "")
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }
}

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** ECDSA P-256 signature encodings: DER (what Java/Android produce) and raw r||s (JOSE ES256). */
object Ecdsa {
    private const val N = 32

    fun publicKey(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    /** Verifies an ES256 signature given as DER or as 64-byte raw r||s. False on any malformed input. */
    fun verify(publicKey: PublicKey, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        val der = if (sig.size == 2 * N && sig[0] != 0x30.toByte()) rawToDer(sig) else sig
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(data); verify(der) }
        // A 64-byte DER-looking blob that fails could still be raw r||s whose r starts with 0x30.
        ok || (sig.size == 2 * N && Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(data); verify(rawToDer(sig)) })
    }.getOrDefault(false)

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 2 * N) { "raw ES256 signature must be 64 bytes" }
        val r = BigInteger(1, raw.copyOfRange(0, N)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(N, 2 * N)).toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(0x02); write(r.size); write(r)
            write(0x02); write(s.size); write(s)
        }.toByteArray()
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER ECDSA signature" }
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): ByteArray {
            require(der[i] == 0x02.toByte()); val len = der[i + 1].toInt() and 0xff
            val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
            require(trimmed.size <= N)
            return ByteArray(N - trimmed.size) + trimmed
        }
        return int() + int()
    }
}
