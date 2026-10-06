package dev.scanrelay.app.ui

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatServerDateTime(
    value: String?,
    time12hFormat: Boolean,
    includeDate: Boolean = true,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return raw

    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { Instant.parse(raw) }
        .getOrNull()
        ?: return raw

    val pattern = when {
        includeDate && time12hFormat -> "MM/dd/yy h:mm a"
        includeDate -> "MM/dd/yy HH:mm"
        time12hFormat -> "h:mm a"
        else -> "HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern, Locale.US)
        .withZone(zoneId)
        .format(instant)
}
