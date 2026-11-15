package app.atall.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayVerifierTest {
    private val relay = SoftwareDeviceKey()
    private val verifier = RelayVerifier(relay.publicKeySpki)

    private fun event() = buildJsonObject {
        put("cursor", 7); put("type", "result"); put("task_id", "tk_1"); put("agent_id", "kdot"); put("conn_id", "cn_1")
        put("client_name", "ChatGPT"); put("at", "2026-11-15T18:00:00Z")
        putJsonObject("payload") { put("summary", "Hi — ✓"); put("status", "done") }
    }

    private fun signed(e: JsonObject, raw: Boolean): JsonObject {
        val der = relay.sign(RelayVerifier.signedBytes(e))
        return JsonObject(e + ("relay_sig" to JsonPrimitive(B64.url(if (raw) Ecdsa.derToRaw(der) else der))))
    }

    @Test fun rawRSignatureVerifies() = assertTrue(verifier.verify(signed(event(), raw = true)))

    @Test fun derSignatureVerifies() = assertTrue(verifier.verify(signed(event(), raw = false)))

    @Test fun keyOrderAndWhitespaceOnTheWireDoNotMatter() {
        val wire = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(signed(event(), raw = true).entries.reversed().associate { it.key to it.value }))
        assertTrue(verifier.verify(Json.parseToJsonElement(wire) as JsonObject))
    }

    @Test fun tamperedPayloadFails() {
        val s = signed(event(), raw = true)
        val bad = JsonObject(s + ("payload" to buildJsonObject { put("summary", "Hi — ✓"); put("status", "partial") }))
        assertFalse(verifier.verify(bad))
    }

    @Test fun addedFieldFails() = assertFalse(verifier.verify(JsonObject(signed(event(), raw = true) + ("extra" to JsonPrimitive(1)))))

    @Test fun missingOrGarbageSignatureFails() {
        assertFalse(verifier.verify(event()))
        assertFalse(verifier.verify(JsonObject(event() + ("relay_sig" to JsonPrimitive("not base64!")))))
        assertFalse(verifier.verify(JsonObject(event() + ("relay_sig" to JsonPrimitive(B64.url(ByteArray(64)))))))
    }

    @Test fun otherKeyFails() = assertFalse(RelayVerifier(SoftwareDeviceKey().publicKeySpki).verify(signed(event(), raw = true)))

    @Test fun webCryptoSignedEventVerifies() {
        // Signed by Node's WebCrypto (what a Deno relay uses): raw r||s, base64url, over sorted-key JSON.stringify.
        val fx = Json.parseToJsonElement(javaClass.getResource("/webcrypto-event.json")!!.readText()) as JsonObject
        val v = RelayVerifier(B64.decodeAny((fx["spki"] as JsonPrimitive).content))
        val wire = Json.parseToJsonElement((fx["wire"] as JsonPrimitive).content) as JsonObject
        assertTrue(v.verify(wire))
        assertFalse(v.verify(JsonObject(wire + ("cursor" to JsonPrimitive(43)))))
    }
}
