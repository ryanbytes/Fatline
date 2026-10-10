package dev.scanrelay.app.playback

import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNotificationPolicyTest {
    @Test
    fun activePlaybackShowsCurrentCall() {
        assertEquals(
            PlaybackNotificationText("Dispatch", "Server · County"),
            PlaybackNotificationPolicy.display(true, "Dispatch", "Server · County")
        )
    }

    @Test
    fun stoppedOrPausedPlaybackDoesNotShowStaleCallMetadata() {
        assertEquals(
            PlaybackNotificationText("FatLine", "Waiting for traffic"),
            PlaybackNotificationPolicy.display(false, "Earlier dispatch", "Earlier server")
        )
    }

    @Test
    fun identicalNotificationTextAndQueueCountDoNotRepublish() {
        val current = PlaybackNotificationText("Dispatch", "County · 2 queued")
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(null, current))
        assertEquals(false, PlaybackNotificationPolicy.needsUpdate(current, current.copy()))
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(
            current, current.copy(subtitle = "County · 3 queued")
        ))
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(
            current, current.copy(title = "Fire dispatch")
        ))
    }

    private fun scanner(id: String, status: ConnectionStatus): ServerScannerState =
        ServerScannerState(
            profile = ServerProfile(id = id, name = id, baseUrl = "https://scanner.invalid"),
            status = status
        )

    private val idle = PlaybackNotificationText("FatLine", "Waiting for traffic")

    @Test
    fun lockScreenShowsScanningBetweenCallsWhenConnected() {
        val one = scanner("one", ConnectionStatus.CONNECTED)
        val display = ScannerLockScreenPolicy.display(
            activeProfiles = setOf("one"),
            state = ScannerState(servers = mapOf("one" to one)),
            pausedProfiles = emptySet(),
            playing = idle,
            isPlaying = false
        )
        assertEquals(PlaybackNotificationText("FatLine · Scanning", "1 scanner connected"), display)
        // Duplicate scanner events retain identical notification content.
        assertEquals(false, PlaybackNotificationPolicy.needsUpdate(display, display.copy()))
    }

    @Test
    fun lockScreenKeepsCurrentCallVisibleWithoutShowingStaleAudio() {
        val one = scanner("one", ConnectionStatus.CONNECTED)
        val state = ScannerState(servers = mapOf("one" to one))
        val playing = PlaybackNotificationText("County Dispatch", "County · Police")
        assertEquals(
            PlaybackNotificationText("FatLine · Scanning", "Playing: County Dispatch · 1 scanner connected"),
            ScannerLockScreenPolicy.display(setOf("one"), state, emptySet(), playing, true)
        )
        assertEquals(
            PlaybackNotificationText("FatLine · Scanning", "1 scanner connected"),
            ScannerLockScreenPolicy.display(setOf("one"), state, emptySet(), playing, false)
        )
    }

    @Test
    fun lockScreenReportsDisconnectedAndPartialConnectionsWithoutClaimingScanning() {
        val one = scanner("one", ConnectionStatus.CONNECTING)
        val two = scanner("two", ConnectionStatus.DISCONNECTED)
        val waiting = ScannerLockScreenPolicy.display(
            setOf("one", "two"),
            ScannerState(servers = mapOf("one" to one, "two" to two)),
            emptySet(), idle, false
        )
        assertEquals(
            PlaybackNotificationText("FatLine · Connecting", "Waiting for scanner connection"), waiting
        )
        val partial = ScannerLockScreenPolicy.display(
            setOf("one", "two"),
            ScannerState(servers = mapOf("one" to one.copy(status = ConnectionStatus.CONNECTED), "two" to two)),
            emptySet(), idle, false
        )
        assertEquals(
            PlaybackNotificationText("FatLine · Scanning", "1 scanner connected · 1 connecting"), partial
        )
        val allConnected = ScannerLockScreenPolicy.display(
            setOf("one", "two"),
            ScannerState(servers = mapOf(
                "one" to one.copy(status = ConnectionStatus.CONNECTED),
                "two" to two.copy(status = ConnectionStatus.CONNECTED)
            )),
            emptySet(), idle, false
        )
        assertEquals(
            PlaybackNotificationText("FatLine · Scanning", "2 scanners connected"), allConnected
        )
    }

    @Test
    fun lockScreenShowsMonitoringWhenLiveAudioIsPausedButConnectionRemains() {
        val one = scanner("one", ConnectionStatus.CONNECTED)
        assertEquals(
            PlaybackNotificationText("FatLine · Monitoring", "1 scanner connected · Live audio paused"),
            ScannerLockScreenPolicy.display(
                setOf("one"), ScannerState(servers = mapOf("one" to one)),
                setOf("one"), idle, false
            )
        )
    }

    @Test
    fun lockScreenDoesNotShowScanningAfterLastScannerDisconnects() {
        val old = scanner("one", ConnectionStatus.CONNECTED)
        assertEquals(
            idle,
            ScannerLockScreenPolicy.display(
                emptySet(), ScannerState(servers = mapOf("one" to old)),
                emptySet(), idle, false
            )
        )
        // Explicit archive playback after disconnect keeps the real title.
        val replay = PlaybackNotificationText("Replay: Dispatch", "County Police")
        assertEquals(
            replay,
            ScannerLockScreenPolicy.display(emptySet(), ScannerState(), emptySet(), replay, true)
        )
    }

    @Test
    fun missingActiveCallMetadataGetsNeutralFallbacks() {
        assertEquals(
            PlaybackNotificationText("Radio traffic", "Listening"),
            PlaybackNotificationPolicy.display(true, "", null)
        )
    }
}
