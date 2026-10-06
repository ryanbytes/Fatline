package dev.scanrelay.app.ui

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDateTimeTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun formatsArchiveTimestampIn24HourMode() {
        assertEquals(
            "10/06/26 21:14",
            formatServerDateTime(
                "2026-10-06T21:14:30Z",
                time12hFormat = false,
                zoneId = utc
            )
        )
    }

    @Test
    fun formatsArchiveTimestampIn12HourMode() {
        assertEquals(
            "10/06/26 9:14 PM",
            formatServerDateTime(
                "2026-10-06T21:14:30Z",
                time12hFormat = true,
                zoneId = utc
            )
        )
    }

    @Test
    fun nowPlayingTimeOmitsDate() {
        assertEquals(
            "9:14 PM",
            formatServerDateTime(
                "2026-10-06T21:14:30Z",
                time12hFormat = true,
                includeDate = false,
                zoneId = utc
            )
        )
    }

    @Test
    fun malformedTimestampFallsBackToServerText() {
        assertEquals(
            "not-a-time",
            formatServerDateTime("not-a-time", time12hFormat = true, zoneId = utc)
        )
    }
}
