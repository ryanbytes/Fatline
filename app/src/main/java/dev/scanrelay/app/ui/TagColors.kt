package dev.scanrelay.app.ui

data class TagColorChoice(
    val label: String,
    val hex: String
)

internal object TagColors {
    val choices: List<TagColorChoice> = listOf(
        TagColorChoice("White", "#ffffff"),
        TagColorChoice("Red", "#ff1744"),
        TagColorChoice("Orange", "#ff9100"),
        TagColorChoice("Yellow", "#ffea00"),
        TagColorChoice("Green", "#00e676"),
        TagColorChoice("Cyan", "#00e5ff"),
        TagColorChoice("Blue", "#2979ff"),
        TagColorChoice("Magenta", "#d500f9"),
        TagColorChoice("Gray", "#9e9e9e")
    )

    fun normalizeHex(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val hex = if (value.startsWith("#")) value else "#$value"
        return when {
            Regex("^#[0-9a-f]{3}$").matches(hex) ->
                "#${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}"
            Regex("^#[0-9a-f]{6}$").matches(hex) -> hex
            else -> null
        }
    }

    fun customColor(tag: String, colors: Map<String, String>): String? =
        colors[tag.trim().lowercase()]?.let(::normalizeHex)

    fun resolvedHex(tag: String, colors: Map<String, String>): String {
        val key = tag.trim().lowercase()
        customColor(key, colors)?.let { return it }

        return when {
            key == "red" || key.contains("fire") -> "#ff1744"
            key == "blue" || key.contains("law") || key.contains("police") -> "#2979ff"
            key == "green" || key.contains("public works") || key == "works" -> "#00e676"
            key == "orange" || key.contains("tac") -> "#ff9100"
            key == "gray" || key.contains("jail") || key.contains("correction") -> "#9e9e9e"
            key == "cyan" -> "#00e5ff"
            key == "magenta" -> "#d500f9"
            key == "yellow" -> "#ffea00"
            key == "white" || key.contains("ems") || key.contains("medical") -> "#ffffff"
            else -> "#ffffff"
        }
    }

    /**
     * One source of truth for the playing-card tint and the lock-screen
     * notification accent. Never use a previously received or queued call:
     * callers pass only the call that Media3 reports as actually playing.
     */
    fun playingCallColor(
        call: dev.scanrelay.app.model.RadioCall?,
        server: dev.scanrelay.app.model.ServerScannerState?
    ): UiAccentRgb? {
        if (call == null || server == null || call.profileId != server.profile.id) return null
        val tag = server.systems
            .firstOrNull { it.systemRef == call.systemRef }
            ?.talkgroups
            ?.firstOrNull { it.talkgroupRef == call.talkgroupRef }
            ?.tag
            ?.takeIf(String::isNotBlank)
            ?: return null
        return rgb(tag, server.tagColors)
    }

    fun rgb(tag: String, colors: Map<String, String>): UiAccentRgb {
        val value = resolvedHex(tag, colors).removePrefix("#").toInt(16)
        return UiAccentRgb(
            red = (value shr 16) and 0xff,
            green = (value shr 8) and 0xff,
            blue = value and 0xff
        )
    }
}
