package dev.scanrelay.app.net

import dev.scanrelay.app.model.ScanList
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanListOrderTest {
    private fun list(id: String) = ScanList(id = id, name = id, channels = emptyList())

    @Test
    fun movesScanListsAndKeepsEveryList() {
        val input = listOf(list("a"), list("b"), list("c"))

        val moved = ScannerRepository.reorderedScanLists(input, 2, 0)

        assertEquals(listOf("c", "a", "b"), moved.map { it.id })
        assertEquals(input.toSet(), moved.toSet())
    }

    @Test
    fun ignoresOutOfRangeMoves() {
        val input = listOf(list("a"), list("b"))

        assertEquals(input, ScannerRepository.reorderedScanLists(input, -1, 0))
        assertEquals(input, ScannerRepository.reorderedScanLists(input, 0, 2))
        assertEquals(input, ScannerRepository.reorderedScanLists(input, 1, 1))
    }
}
