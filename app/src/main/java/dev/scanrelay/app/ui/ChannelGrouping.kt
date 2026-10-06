package dev.scanrelay.app.ui

import dev.scanrelay.app.model.TalkgroupConfig

internal data class TalkgroupTagGroup(
    val tag: String,
    val talkgroups: List<TalkgroupConfig>
)

internal fun groupTalkgroupsByTag(talkgroups: List<TalkgroupConfig>): List<TalkgroupTagGroup> =
    talkgroups
        .groupBy { talkgroup -> talkgroup.tag.trim().ifBlank { "Untagged" } }
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<TalkgroupConfig>>> { it.key == "Untagged" }
                .thenBy { it.key.lowercase() }
        )
        .map { (tag, groupedTalkgroups) ->
            TalkgroupTagGroup(tag = tag, talkgroups = groupedTalkgroups)
        }
