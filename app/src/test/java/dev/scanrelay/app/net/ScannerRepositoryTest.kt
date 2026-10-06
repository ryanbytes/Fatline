package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerRepositoryTest {
    private val profile = ServerProfile(id = "test", name = "Test", baseUrl = "https://example.invalid")
    private val systems = listOf(
        SystemConfig(
            systemRef = 1,
            label = "One",
            talkgroups = listOf(
                TalkgroupConfig(1, 11, "One-A", enabled = true),
                TalkgroupConfig(1, 12, "One-B", enabled = true)
            )
        ),
        SystemConfig(
            systemRef = 2,
            label = "Two",
            talkgroups = listOf(
                TalkgroupConfig(2, 21, "Two-A", enabled = true)
            )
        )
    )

    @Test
    fun talkgroupHoldFiltersSubscriptionWithoutMutatingBaseSelection() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            hold = ChannelKey(1, 11)
        )

        val effective = ScannerRepository.effectiveLivefeedSystems(state)

        assertTrue(effective[0].talkgroups[0].enabled)
        assertFalse(effective[0].talkgroups[1].enabled)
        assertFalse(effective[1].talkgroups[0].enabled)
        assertTrue(state.systems[0].talkgroups[1].enabled)
        assertTrue(state.systems[1].talkgroups[0].enabled)
    }

    @Test
    fun systemHoldAndAvoidAreAppliedToSubscription() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            holdSystemRef = 1,
            avoided = setOf(ChannelKey(1, 12))
        )

        val effective = ScannerRepository.effectiveLivefeedSystems(state)

        assertTrue(effective[0].talkgroups[0].enabled)
        assertFalse(effective[0].talkgroups[1].enabled)
        assertFalse(effective[1].talkgroups[0].enabled)
    }
}
