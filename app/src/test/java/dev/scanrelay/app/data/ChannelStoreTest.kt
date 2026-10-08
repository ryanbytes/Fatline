package dev.scanrelay.app.data

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelStoreTest {
    private val a = ChannelKey(1, 101)
    private val b = ChannelKey(1, 102)
    private val c = ChannelKey(1, 103)

    private val monitoringScope = listOf(
        SystemConfig(
            systemRef = 1,
            label = "County",
            talkgroups = listOf(
                TalkgroupConfig(1, 101, "Dispatch"),
                TalkgroupConfig(1, 102, "Tactical")
            )
        ),
        SystemConfig(
            systemRef = 2,
            label = "Mutual Aid",
            talkgroups = listOf(TalkgroupConfig(2, 201, "Mutual Aid"))
        )
    )

    @Test
    fun reconnectRetainsAuthorizedHoldAndAvoids() {
        val saved = MonitoringOverrides(
            hold = b,
            avoided = setOf(a, ChannelKey(2, 201))
        )
        assertEquals(saved, reconcileMonitoringOverrides(saved, monitoringScope))
    }

    @Test
    fun scopeChangesRemoveStaleHoldsAndAvoidsWithoutChangingValidSelections() {
        val saved = MonitoringOverrides(
            hold = ChannelKey(99, 123),
            holdSystemRef = 77,
            avoided = setOf(a, ChannelKey(99, 456))
        )
        assertEquals(
            MonitoringOverrides(avoided = setOf(a)),
            reconcileMonitoringOverrides(saved, monitoringScope)
        )
    }

    @Test
    fun systemHoldRemainsWhenAuthorizedButDropsWhenSystemRemoved() {
        val saved = MonitoringOverrides(holdSystemRef = 2, avoided = setOf(a))
        assertEquals(saved, reconcileMonitoringOverrides(saved, monitoringScope))
        assertEquals(
            MonitoringOverrides(),
            reconcileMonitoringOverrides(saved, emptyList())
        )
    }

    @Test
    fun newlyScopedChannelsStayOffWhenServerPolicyIsDisabled() {
        assertEquals(
            setOf(a),
            reconcileChannelSelection(
                currentScope = setOf(a, b, c),
                savedSelection = setOf(a),
                knownScope = setOf(a, b),
                autoEnableNewTalkgroups = false
            )
        )
    }

    @Test
    fun newlyScopedChannelsTurnOnWhenServerPolicyIsEnabled() {
        assertEquals(
            setOf(a, c),
            reconcileChannelSelection(
                currentScope = setOf(a, b, c),
                savedSelection = setOf(a),
                knownScope = setOf(a, b),
                autoEnableNewTalkgroups = true
            )
        )
    }

    @Test
    fun channelsThatLeaveScopeArePrunedFromSavedSelection() {
        assertEquals(
            setOf(a),
            reconcileChannelSelection(
                currentScope = setOf(a, c),
                savedSelection = setOf(a, b),
                knownScope = setOf(a, b, c),
                autoEnableNewTalkgroups = false
            )
        )
    }

    @Test
    fun baselineMigrationDoesNotBulkEnableCurrentDisabledChannels() {
        assertEquals(
            setOf(a),
            reconcileChannelSelection(
                currentScope = setOf(a, b, c),
                savedSelection = setOf(a),
                knownScope = setOf(a, b, c),
                autoEnableNewTalkgroups = true
            )
        )
    }
}
