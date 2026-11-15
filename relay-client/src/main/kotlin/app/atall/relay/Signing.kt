package app.atall.relay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.PublicKey

/**
 * Signs phone requests (every /phone/ route except /phone/pair). The signed string is
 * `METHOD\nPATH\nTS\nbase64url(sha256(body))`, PATH being the path after /atall plus the query string.
 */
class RequestSigner(private val key: DeviceKey) {
    data class Signed(val deviceId: String, val ts: Long, val sig: String, val signedString: String) {
        fun headers(): Map<String, String> = mapOf(HEADER_DEVICE to deviceId, HEADER_TS to ts.toString(), HEADER_SIG to sig)
    }

    fun sign(deviceId: String, method: String, pathAndQuery: String, body: ByteArray, ts: Long): Signed {
        val s = signedString(method, pathAndQuery, ts, body)
        return Signed(deviceId, ts, B64.url(key.sign(s.toByteArray(Charsets.UTF_8))), s)
    }

    companion object {
        const val HEADER_DEVICE = "X-Atall-Device"
        const val HEADER_TS = "X-Atall-Ts"
        const val HEADER_SIG = "X-Atall-Sig"

        fun signedString(method: String, pathAndQuery: String, ts: Long, body: ByteArray): String {
            require(pathAndQuery.startsWith("/")) { "path must start with / (the part after /atall)" }
            return "${method.uppercase()}\n$pathAndQuery\n$ts\n${B64.url(sha256(body))}"
        }
    }
}

/** Checks `relay_sig` on events: ES256 by the relay key over the canonical JSON of the event minus `relay_sig`. */
class RelayVerifier(relayPublicKeySpki: ByteArray) {
    private val key: PublicKey = Ecdsa.publicKey(relayPublicKeySpki)

    fun verify(event: JsonObject): Boolean {
        val sig = (event[RELAY_SIG] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        val bytes = runCatching { B64.decodeAny(sig) }.getOrNull() ?: return false
        return Ecdsa.verify(key, signedBytes(event), bytes)
    }

    companion object {
        const val RELAY_SIG = "relay_sig"
        fun signedBytes(event: JsonObject): ByteArray =
            CanonicalJson.encode(JsonObject(event.filterKeys { it != RELAY_SIG } as Map<String, JsonElement>)).toByteArray(Charsets.UTF_8)
    }
}
