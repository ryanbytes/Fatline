package dev.scanrelay.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagColorsTest {
    @Test
    fun normalizesThreeAndSixDigitHex() {
        assertEquals("#aabbcc", TagColors.normalizeHex("#abc"))
        assertEquals("#2979ff", TagColors.normalizeHex("2979FF"))
        assertNull(TagColors.normalizeHex("orange"))
    }

    @Test
    fun userColorOverridesSemanticDefault() {
        val colors = mapOf("fire dispatch" to "#00e5ff")
        assertEquals("#00e5ff", TagColors.resolvedHex("Fire Dispatch", colors))
        assertEquals("#2979ff", TagColors.resolvedHex("Law Dispatch", emptyMap()))
        assertEquals("#ffffff", TagColors.resolvedHex("Unknown", emptyMap()))
    }
}
