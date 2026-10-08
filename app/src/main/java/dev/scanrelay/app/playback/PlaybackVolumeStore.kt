package dev.scanrelay.app.playback

import android.content.Context

/** FatLine-only ExoPlayer volume. Never modifies Android's shared media stream. */
internal object PlaybackVolumePolicy {
    const val DEFAULT_PERCENT = 100
    fun clamp(percent: Int): Int = percent.coerceIn(0, 100)
    fun gain(percent: Int): Float = clamp(percent) / 100f
}

internal class PlaybackVolumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_playback_volume", Context.MODE_PRIVATE)

    fun percent(): Int =
        PlaybackVolumePolicy.clamp(prefs.getInt("output_percent", PlaybackVolumePolicy.DEFAULT_PERCENT))

    fun save(percent: Int) {
        prefs.edit().putInt("output_percent", PlaybackVolumePolicy.clamp(percent)).apply()
    }
}
