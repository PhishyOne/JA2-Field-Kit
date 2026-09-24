package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.InventoryObject
import com.phishtopia.ja2fieldkit.core.model.InventoryPayload
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.LiveMercStateInspectionResult
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionDiagnostic
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailure
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailureKind
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class InventoryPresentationMapperTest {
    private val source = ImportedSaveProvenance(
        "slot01.sav",
        SourceProvenance.DOCUMENT_PICKER,
        null,
        2_563_321,
        null,
        "00".repeat(32),
    )
    private val format = SaveInspectionFormat(
        SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
        SaveCompatibility.SUPPORTED,
        SaveFamily.UNKNOWN,
        103,
        "04.12.02",
    )

    @Test
    fun mapsAllCanonicalSlotsInStableOrderWithEmptyAndExactOccupiedFacts() {
        val inspection = inspectionSuccess(format, listOf(7))
        val inventory = inventorySuccess(
            inspection,
            records = mapOf((7 to InventorySlotRole.MAIN_HAND) to (321 to 4)),
        )

        val state = assertIs<InspectionScreenState.Success>(
            InspectionPresentationMapper.map(source, inspection, inventory),
        )
        val slots = state.roster.single().inventory

        assertEquals(
            listOf(
                "Helmet", "Vest", "Legs", "Head 1", "Head 2", "Main hand", "Off hand",
                "Big pocket 1", "Big pocket 2", "Big pocket 3", "Big pocket 4",
                "Small pocket 1", "Small pocket 2", "Small pocket 3", "Small pocket 4",
                "Small pocket 5", "Small pocket 6", "Small pocket 7", "Small pocket 8",
            ),
            slots.map { it.label },
        )
        assertEquals(19, slots.size)
        assertEquals(InventorySlotRole.entries, slots.map { it.role })
        assertEquals(InventoryContentsPresentation.Empty, slots.first().contents)
        assertEquals(
            InventoryContentsPresentation.Occupied(itemId = 321, objectCount = 4),
            slots[InventorySlotRole.MAIN_HAND.slotIndex].contents,
        )
    }

    @Test
    fun bindsTwoMercInventoriesByProfileIndexWhenInventoryOrderDiffers() {
        val inspection = inspectionSuccess(format, listOf(4, 9))
        val inventory = inventorySuccess(
            inspection,
            profileOrder = listOf(9, 4),
            records = mapOf(
                (4 to InventorySlotRole.HELMET) to (104 to 1),
                (9 to InventorySlotRole.HELMET) to (109 to 2),
            ),
        )

        val state = assertIs<InspectionScreenState.Success>(
            InspectionPresentationMapper.map(source, inspection, inventory),
        )

        assertEquals("4", state.roster[0].liveStats.first().value)
        assertEquals("9", state.roster[1].liveStats.first().value)
        assertEquals(
            InventoryContentsPresentation.Occupied(104, 1),
            state.roster[0].inventory[0].contents,
        )
        assertEquals(
            InventoryContentsPresentation.Occupied(109, 2),
            state.roster[1].inventory[0].contents,
        )
    }

    @Test
    fun duplicateMissingExtraAndMismatchedProfileIdentitiesFailClosed() {
        val cases = listOf(
            listOf(4, 4),
            listOf(4),
            listOf(4, 9, 12),
            listOf(4, 12),
        )

        cases.forEach { inventoryIds ->
            val inspection = inspectionSuccess(format, listOf(4, 9))
            val inventory = inventorySuccess(
                format,
                inventoryIds.map { inventoryEntry(it) },
            )
            assertCoherenceFailure(InspectionPresentationMapper.map(source, inspection, inventory))
        }

        val duplicateRoster = inspectionSuccess(format, listOf(4, 4))
        assertCoherenceFailure(
            InspectionPresentationMapper.map(
                source,
                duplicateRoster,
                inventorySuccess(format, listOf(inventoryEntry(4), inventoryEntry(9))),
            ),
        )
    }

    @Test
    fun inventoryInspectionFailureCannotProduceSuccessOrPartialInventory() {
        val inspection = inspectionSuccess(format, listOf(7))
        val failure = LiveMercStateInspectionResult.Failure(
            format,
            SaveInspectionFailure(
                SaveInspectionFailureKind.CORRUPT_INPUT,
                SaveInspectionDiagnostic.CONTENT_CORRUPT,
            ),
        )

        val state = assertIs<InspectionScreenState.Failure>(
            InspectionPresentationMapper.map(source, inspection, failure),
        )

        assertEquals("CORRUPT_INPUT", state.failureKind)
        assertEquals("CONTENT_CORRUPT", state.diagnostic)
    }

    @Test
    fun retainedPresentationContainsNoRawRecordPayloadOrSaveByteArrays() {
        val inspection = inspectionSuccess(format, listOf(7, 9))
        val state = assertIs<InspectionScreenState.Success>(
            InspectionPresentationMapper.map(
                source,
                inspection,
                inventorySuccess(
                    inspection,
                    records = mapOf((7 to InventorySlotRole.VEST) to (88 to 3)),
                ),
            ).withSelectedMerc(9),
        )

        assertEquals(9, state.selectedMerc?.profileIndex)
        // Walk actual retained values, including list elements, rather than only erased field types.
        fun assertSafe(value: Any?) {
            when (value) {
                null, is String, is Number, is Boolean, is Enum<*> -> return
                is List<*> -> value.forEach(::assertSafe)
                else -> {
                    assertFalse(value is ByteArray || value is InventoryObject || value is InventoryPayload)
                    value.javaClass.declaredFields.filter {
                        !java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }.forEach { field ->
                        field.isAccessible = true
                        assertSafe(field.get(value))
                    }
                }
            }
        }
        assertSafe(state)

        val retainedTypes = listOf(
            state.javaClass,
            state.selectedMerc!!.javaClass,
            state.selectedMerc!!.inventory.first().javaClass,
            state.selectedMerc!!.inventory[1].contents.javaClass,
        ).flatMap { type -> type.declaredFields.map { it.type } }
        assertFalse(retainedTypes.contains(ByteArray::class.java))
        assertFalse(retainedTypes.contains(InventoryObject::class.java))
        assertFalse(retainedTypes.contains(InventoryPayload.Unknown::class.java))
        assertFalse(state.toString().contains("rawRecord"))
        assertFalse(state.toString().contains("rawPayload"))
    }

    @Test
    fun formatAndNoncanonicalSlotOrderFailClosed() {
        val inspection = inspectionSuccess(format, listOf(7))
        val slots = InventorySlotRole.entries.map { inventorySlot(it, 0, 0) }
        for (invalid in listOf(slots.reversed(), slots.dropLast(1), slots + slots.first(),
            slots.dropLast(1) + slots.first())) {
            assertCoherenceFailure(InspectionPresentationMapper.map(source, inspection,
                inventorySuccess(format, listOf(inventoryEntry(7, invalid)))))
        }
        assertCoherenceFailure(InspectionPresentationMapper.map(source, inspection,
            inventorySuccess(format.copy(buildLabel = "different"), listOf(inventoryEntry(7)))))
    }

    @Test
    fun liveAndProfileStatsStayDistinctAndPreserveSignedValues() {
        val inspection = inspectionSuccess(format, listOf(7))
        val merc = assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(
            source, inspection, inventorySuccess(inspection))).roster.single()
        assertEquals(listOf("Life", "Max life", "Agility", "Dexterity", "Strength", "Experience",
            "Marksmanship", "Mechanical", "Explosives", "Medical"), merc.liveStats.map { it.label })
        assertEquals(listOf("7", "99", "-128", "-2", "127", "-3", "-4", "-5", "-6", "-7"),
            merc.liveStats.map { it.value })
        assertEquals(listOf("84", "85"), merc.profileStats.filter {
            it.label in listOf("Leadership", "Wisdom") }.map { it.value })
        assertEquals("80", merc.profileStats.first().value)
    }

    private fun assertCoherenceFailure(state: InspectionScreenState) {
        state as InspectionScreenState.Failure
        assertEquals("INCONSISTENT_INPUT", state.failureKind)
        assertEquals("CONTENT_INCONSISTENT", state.diagnostic)
    }
}
