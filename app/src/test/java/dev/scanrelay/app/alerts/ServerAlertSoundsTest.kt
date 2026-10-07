package dev.scanrelay.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAlertSoundsTest {
    @Test
    fun blankSoundIsSilentAndKnownFilesResolveLabels() {
        assertEquals("None (Silent)", ServerAlertSounds.labelFor(""))
        assertEquals("MDC-1200", ServerAlertSounds.labelFor("mdc-1200.mp3"))
        assertEquals("Alert", ServerAlertSounds.labelFor("alert.wav"))
    }

    @Test
    fun choicesMatchThinLineServerFilenames() {
        val files = ServerAlertSounds.choices.map { it.fileName }.toSet()

        assertTrue("" in files)
        assertTrue("Beep.mp3" in files)
        assertTrue("settle_alert.wav" in files)
        assertTrue("tone.wav" in files)
    }
}
