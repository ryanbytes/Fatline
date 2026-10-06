package dev.scanrelay.app.data

import dev.scanrelay.app.model.ChannelKey
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenSystemSelectionTest {
    @Test
    fun hiddenSystemsAreRemovedFromLiveSelection() {
        val visible = ChannelKey(1, 10)
        val hiddenA = ChannelKey(2, 20)
        val hiddenB = ChannelKey(2, 21)

        val filtered = filterHiddenChannelSelection(
            setOf(visible, hiddenA, hiddenB),
            setOf(2)
        )

        assertEquals(setOf(visible), filtered)
    }

    @Test
    fun noHiddenSystemsPreservesSelection() {
        val selected = setOf(ChannelKey(1, 10), ChannelKey(2, 20))

        assertEquals(selected, filterHiddenChannelSelection(selected, emptySet()))
    }
}
