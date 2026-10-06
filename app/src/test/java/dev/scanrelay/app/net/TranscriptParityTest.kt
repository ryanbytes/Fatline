package dev.scanrelay.app.net

import dev.scanrelay.app.model.ServerProfile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptParityTest {
    @Test
    fun parsesTranscriptRowsAndSortsNewestFirst() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://example.test")
        val raw = JSONArray(
            """
            [
              {
                "callId": 10,
                "systemId": 4,
                "talkgroupId": 9,
                "systemLabel": "County",
                "talkgroupLabel": "Dispatch",
                "transcript": "first",
                "timestamp": 1000
              },
              {
                "callId": 11,
                "systemId": 4,
                "talkgroupId": 12,
                "talkgroupName": "Ops",
                "transcript": "second",
                "reviewedTranscript": "SECOND",
                "timestamp": 2000
              }
            ]
            """.trimIndent()
        )

        val parsed = ScannerRepository.parseTranscripts(profile, raw)

        assertEquals(listOf(11L, 10L), parsed.map { it.callId })
        assertEquals(4L, parsed.first().systemId)
        assertEquals(12L, parsed.first().talkgroupId)
        assertEquals("SECOND", parsed.first().reviewedTranscript)
        assertNull(parsed.first().talkgroupLabel)
    }
}
