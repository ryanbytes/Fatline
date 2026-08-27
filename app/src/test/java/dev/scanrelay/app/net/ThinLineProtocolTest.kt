package dev.scanrelay.app.net

import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinLineProtocolTest {
    @Test fun pinIsBase64Encoded() {
        assertEquals("[\"PIN\",\"MTIzNA==\"]", ThinLineProtocol.pin("1234"))
    }

    @Test fun livefeedUses64BitRadioReferencesAndExplicitBooleans() {
        val systemRef = 4_294_967_299L
        val enabledRef = 8_589_934_599L
        val disabledRef = 8_589_934_600L
        val systems = listOf(
            SystemConfig(
                systemRef,
                "Large",
                listOf(
                    TalkgroupConfig(systemRef, enabledRef, "Dispatch", enabled = true),
                    TalkgroupConfig(systemRef, disabledRef, "Tac", enabled = false)
                )
            )
        )
        val parsed = ThinLineProtocol.parseEnvelope(ThinLineProtocol.livefeed(systems))
        val map = parsed.payload as JSONObject
        val talkgroups = map.getJSONObject(systemRef.toString())
        assertTrue(talkgroups.getBoolean(enabledRef.toString()))
        assertFalse(talkgroups.getBoolean(disabledRef.toString()))
    }

    @Test fun bareLivefeedCommandMatchesThinLinePauseWireType() {
        assertEquals("[\"LFM\"]", ThinLineProtocol.command(ThinLineProtocol.LIVEFEED_MAP))
    }

    @Test fun listCallUsesDocumentedFields() {
        val parsed = ThinLineProtocol.parseEnvelope(ThinLineProtocol.listCalls(100, 200, -1, 1, listOf(101, 102)))
        val payload = parsed.payload as JSONObject
        assertEquals(100, payload.getInt("limit"))
        assertEquals(200, payload.getInt("offset"))
        assertEquals(-1, payload.getInt("sort"))
        assertEquals(1L, payload.getLong("system"))
        assertEquals(2, payload.getJSONArray("talkgroups").length())
    }

    @Test fun callIdMatchesThinLineStringWireType() {
        assertEquals("[\"CAL\",\"42\"]", ThinLineProtocol.call(42))
    }

    @Test fun callDownloadFlagIsThirdEnvelopeElement() {
        assertEquals("[\"CAL\",\"42\",\"d\"]", ThinLineProtocol.call(42, true))
    }
}
