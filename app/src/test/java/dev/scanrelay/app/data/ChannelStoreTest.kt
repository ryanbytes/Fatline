package dev.scanrelay.app.data

import dev.scanrelay.app.model.ChannelKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelStoreTest {
    private val a = ChannelKey(1, 101)
    private val b = ChannelKey(1, 102)
    private val c = ChannelKey(1, 103)

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
