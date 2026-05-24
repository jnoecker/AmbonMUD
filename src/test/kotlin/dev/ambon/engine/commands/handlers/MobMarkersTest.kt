package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.dialogue.DialogueNode
import dev.ambon.engine.dialogue.DialogueTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MobMarkersTest {
    private fun mob(
        dialogue: DialogueTree? = null,
        aggressive: Boolean = false,
    ): MobState =
        MobState(
            id = MobId("zone:bob"),
            name = "bob",
            roomId = RoomId("zone:room1"),
            dialogue = dialogue,
            aggressive = aggressive,
        )

    private val dialogue =
        DialogueTree(
            rootNodeId = "root",
            nodes = mapOf("root" to DialogueNode(text = "hi", choices = emptyList())),
        )

    @Test
    fun `no flags produces no marker`() {
        assertEquals("", mobMarkers(mob(), hasQuestAvailable = false, hasQuestTurnIn = false))
    }

    @Test
    fun `quest available produces (!) marker`() {
        val markers = mobMarkers(mob(), hasQuestAvailable = true, hasQuestTurnIn = false)
        assertEquals("{c:quest}(!){/c}", markers)
    }

    @Test
    fun `turn-in beats quest-available in marker precedence`() {
        val markers = mobMarkers(mob(), hasQuestAvailable = true, hasQuestTurnIn = true)
        assertEquals("{c:turnin}(?){/c}", markers)
    }

    @Test
    fun `dialogue marker shows only when no quest marker applies`() {
        val withDialogueOnly = mobMarkers(mob(dialogue = dialogue), hasQuestAvailable = false, hasQuestTurnIn = false)
        assertEquals("{c:dialogue}(*){/c}", withDialogueOnly)

        // Quest marker subsumes the dialogue hint — talking is how you'd get the quest anyway.
        val withQuestAndDialogue =
            mobMarkers(mob(dialogue = dialogue), hasQuestAvailable = true, hasQuestTurnIn = false)
        assertEquals("{c:quest}(!){/c}", withQuestAndDialogue)
    }

    @Test
    fun `aggressive marker stacks with other markers`() {
        val aggroAlone = mobMarkers(mob(aggressive = true), hasQuestAvailable = false, hasQuestTurnIn = false)
        assertEquals("{c:aggro}[A]{/c}", aggroAlone)

        val questAndAggro =
            mobMarkers(mob(aggressive = true), hasQuestAvailable = true, hasQuestTurnIn = false)
        assertEquals("{c:quest}(!){/c} {c:aggro}[A]{/c}", questAndAggro)
    }
}
