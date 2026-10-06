package dev.scanrelay.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiAccentTest {
    @Test
    fun normalizesThreeAndSixDigitHex() {
        assertEquals("#55ccff", normalizeUiAccentColor("#5cf"))
        assertEquals("#38e0a4", normalizeUiAccentColor("38E0A4"))
    }

    @Test
    fun invalidServerAccentFallsBackToThinLineDefault() {
        assertEquals(DEFAULT_UI_ACCENT, normalizeUiAccentColor("not-a-color"))
    }

    @Test
    fun absentServerAccentDoesNotOverrideAppTheme() {
        assertNull(normalizeUiAccentColor(null))
        assertNull(normalizeUiAccentColor("   "))
    }

    @Test
    fun convertsNormalizedAccentToRgb() {
        assertEquals(UiAccentRgb(255, 91, 46), uiAccentRgb("#ff5b2e"))
    }
}
