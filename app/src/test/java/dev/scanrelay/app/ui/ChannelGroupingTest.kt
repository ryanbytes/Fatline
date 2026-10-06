package dev.scanrelay.app.ui

import dev.scanrelay.app.model.TalkgroupConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelGroupingTest {
    private fun talkgroup(ref: Long, tag: String) = TalkgroupConfig(
        systemRef = 1,
        talkgroupRef = ref,
        label = "TG $ref",
        tag = tag
    )

    @Test
    fun groupsTagsAlphabeticallyWithUntaggedLast() {
        val groups = groupTalkgroupsByTag(
            listOf(
                talkgroup(10, "TAC"),
                talkgroup(11, ""),
                talkgroup(12, "Fire"),
                talkgroup(13, "  "),
                talkgroup(14, "EMS")
            )
        )

        assertEquals(listOf("EMS", "Fire", "TAC", "Untagged"), groups.map { it.tag })
        assertEquals(listOf(11L, 13L), groups.last().talkgroups.map { it.talkgroupRef })
    }

    @Test
    fun preservesEveryTalkgroupExactlyOnce() {
        val input = listOf(
            talkgroup(10, "Fire"),
            talkgroup(11, "Fire"),
            talkgroup(12, "Law")
        )

        val grouped = groupTalkgroupsByTag(input).flatMap { it.talkgroups }

        assertEquals(input, grouped.sortedBy { it.talkgroupRef })
    }
}
