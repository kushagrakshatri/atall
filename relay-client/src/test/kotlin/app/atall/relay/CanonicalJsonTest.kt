package app.atall.relay

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalJsonTest {
    private fun canon(s: String) = CanonicalJson.encode(Json.parseToJsonElement(s))

    @Test fun keysSortedAtEveryLevelNoWhitespace() {
        assertEquals("""{"a":[3,{"x":1,"y":2}],"b":{"c":true,"d":null}}""", canon("""{ "b": {"d": null, "c": true}, "a": [3, {"y":2, "x":1}] }"""))
    }

    @Test fun arraysKeepTheirOrder() = assertEquals("""[3,1,2]""", canon("[3, 1, 2]"))

    @Test fun keysSortByUtf16CodeUnitsLikeJs() {
        // "B" (0x42) < "_" (0x5f) < "a" (0x61) < "é" (0xe9) < "😀" (0xd83d...) < "￿" (0xffff)
        assertEquals("""{"B":1,"_":2,"a":3,"é":4,"😀":5,"￿":6}""", canon("""{"￿":6,"😀":5,"é":4,"a":3,"_":2,"B":1}"""))
    }

    @Test fun stringsEscapedLikeJsonStringify() {
        assertEquals("\"q\\\"b\\\\n\\n\\t\\r\\b\\f\\u0001\\u001f/é—😀\u2028\"", canon("\"q\\\"b\\\\n\\n\\t\\r\\b\\f\\u0001\\u001f\\/\\u00e9—😀\\u2028\""))
    }

    @Test fun numbersLikeEcmaScript() {
        val cases = mapOf(
            "1" to "1", "-0" to "0", "1.0" to "1", "1.50" to "1.5", "1e2" to "100", "0.85" to "0.85", "1e21" to "1e+21",
            "1E-7" to "1e-7", "0.000001" to "0.000001", "123456789012345678" to "123456789012345680", "-2.5e-3" to "-0.0025",
            "1.7976931348623157e308" to "1.7976931348623157e+308", "5e-324" to "5e-324", "100000000000000000000" to "100000000000000000000",
        )
        cases.forEach { (lit, want) -> assertEquals(lit, want, CanonicalJson.number(lit)) }
    }

    @Test fun matchesNodeOutputForARealEvent() {
        val fx = Json.parseToJsonElement(javaClass.getResource("/webcrypto-event.json")!!.readText()) as kotlinx.serialization.json.JsonObject
        val wire = Json.parseToJsonElement((fx["wire"] as kotlinx.serialization.json.JsonPrimitive).content) as kotlinx.serialization.json.JsonObject
        val canonical = (fx["canonical"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(canonical, String(RelayVerifier.signedBytes(wire)))
    }
}
