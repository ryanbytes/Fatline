package dev.scanrelay.app.alerts

data class ServerAlertSoundChoice(
    val label: String,
    val fileName: String
)

object ServerAlertSounds {
    val choices: List<ServerAlertSoundChoice> = listOf(
        ServerAlertSoundChoice("None (Silent)", ""),
        ServerAlertSoundChoice("Alert", "alert.wav"),
        ServerAlertSoundChoice("Beep", "Beep.mp3"),
        ServerAlertSoundChoice("Chirp Long", "chirp_long.wav"),
        ServerAlertSoundChoice("Classic", "classic.wav"),
        ServerAlertSoundChoice("Click", "Click.mp3"),
        ServerAlertSoundChoice("Ding", "ding.wav"),
        ServerAlertSoundChoice("Door Bell", "door_bell.wav"),
        ServerAlertSoundChoice("Double Pulse", "double_pulse.wav"),
        ServerAlertSoundChoice("Fast Beep Long", "fast_beep_long.wav"),
        ServerAlertSoundChoice("Fast Beep Short", "fast_beep_short.wav"),
        ServerAlertSoundChoice("Five Beep", "five_beep.wav"),
        ServerAlertSoundChoice("MDC-1200", "MDC-1200.mp3"),
        ServerAlertSoundChoice("Modern", "modern.wav"),
        ServerAlertSoundChoice("Pluck", "pluck.wav"),
        ServerAlertSoundChoice("Pop", "pop.wav"),
        ServerAlertSoundChoice("Quick Beep", "quick_beep.wav"),
        ServerAlertSoundChoice("Quiet", "quiet.wav"),
        ServerAlertSoundChoice("Relaxed", "relaxed.wav"),
        ServerAlertSoundChoice("Settle Alert", "settle_alert.wav"),
        ServerAlertSoundChoice("Simple", "simple.wav"),
        ServerAlertSoundChoice("Smoke Alarm", "smoke_alarm.wav"),
        ServerAlertSoundChoice("Startup", "startup.wav"),
        ServerAlertSoundChoice("Tone", "tone.wav")
    )

    fun labelFor(fileName: String?): String {
        val normalized = fileName.orEmpty().trim()
        if (normalized.isEmpty()) return "None (Silent)"
        return choices.firstOrNull { it.fileName.equals(normalized, ignoreCase = true) }?.label
            ?: normalized
    }
}
