package dev.scanrelay.app.net

import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinLineProtocolTest {
    @Test fun pinIsBase64Encoded() {
        assertEquals("[\"PIN\",\"MTIzNA==\"]", ThinLineProtocol.pin("1234"))
    }

    @Test fun livefeedUses64BitRadioReferences() {
        val systems = listOf(
            SystemConfig(
                4_294_967_299L,
                "Large",
                listOf(TalkgroupConfig(4_294_967_299L, 8_589_934_599L, "Dispatch", enabled = true))
            )
        )
        val parsed = ThinLineProtocol.parseEnvelope(ThinLineProtocol.livefeed(systems))
        val map = parsed.payload as JSONObject
        assertTrue(map.getJSONObject("4294967299").getBoolean("8589934599"))
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

    @Test fun callDownloadFlagIsThirdEnvelopeElement() {
        assertEquals("[\"CAL\",42,\"d\"]", ThinLineProtocol.call(42, true))
    }
}
