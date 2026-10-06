package dev.scanrelay.app.ui

internal const val DEFAULT_UI_ACCENT = "#ff5b2e"

internal data class UiAccentRgb(
    val red: Int,
    val green: Int,
    val blue: Int
)

internal fun normalizeUiAccentColor(raw: String?): String? {
    val value = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hex = if (value.startsWith("#")) value else "#$value"
    return when {
        Regex("^#[0-9a-f]{3}$").matches(hex) ->
            "#${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}"
        Regex("^#[0-9a-f]{6}$").matches(hex) -> hex
        else -> DEFAULT_UI_ACCENT
    }
}

internal fun uiAccentRgb(raw: String?): UiAccentRgb? {
    val normalized = normalizeUiAccentColor(raw) ?: return null
    val value = normalized.removePrefix("#").toInt(16)
    return UiAccentRgb(
        red = (value shr 16) and 0xff,
        green = (value shr 8) and 0xff,
        blue = value and 0xff
    )
}
