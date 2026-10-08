package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVolumeStoreTest {
    @Test
    fun outputVolumeDefaultsToFullVolume() {
        assertEquals(100, PlaybackVolumePolicy.DEFAULT_PERCENT)
        assertEquals(1f, PlaybackVolumePolicy.gain(PlaybackVolumePolicy.DEFAULT_PERCENT), 0.0001f)
    }

    @Test
    fun outputVolumeClampsToSupportedRange() {
        assertEquals(0, PlaybackVolumePolicy.clamp(-12))
        assertEquals(100, PlaybackVolumePolicy.clamp(180))
        assertEquals(0f, PlaybackVolumePolicy.gain(-10), 0.0001f)
        assertEquals(1f, PlaybackVolumePolicy.gain(200), 0.0001f)
    }

    @Test
    fun outputVolumeControlsOnlyPlayerGain() {
        assertEquals(0.25f, PlaybackVolumePolicy.gain(25), 0.0001f)
        assertEquals(0.73f, PlaybackVolumePolicy.gain(73), 0.0001f)
    }
}
