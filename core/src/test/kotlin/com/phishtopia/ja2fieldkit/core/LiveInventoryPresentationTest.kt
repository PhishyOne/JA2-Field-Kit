package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.InventoryObjectParser
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.*
import java.lang.reflect.Modifier
import kotlin.test.*

class LiveInventoryPresentationTest {
    @Test
    fun publicInventoryPropertiesContainOnlyLogicalFactsAndDefensiveObjectBytes() {
        val allowed = mapOf(
            MercInventoryEntry::class.java to setOf("profileIndex", "name", "nickname", "slots"),
            InventorySlot::class.java to setOf("role", "objectRecord"),
            InventoryAttachment::class.java to setOf("itemId", "status"),
            InventoryObject::class.java to setOf("itemId", "objectCount", "reservedByte3", "attachments", "flags",
                "mission", "trap", "imprint", "serializedWeight", "used", "finalReservedBytes", "payload", "rawRecord"),
            InventoryPayload.Unknown::class.java to setOf("rawPayload"),
            LiveInventoryInspectionResult.Success::class.java to setOf("format", "inventories"),
            LiveInventoryInspectionResult.Failure::class.java to setOf("format", "failure"),
        )
        for ((type, properties) in allowed) {
            val getters = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("get") }
            assertEquals(properties, getters.map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }.toSet())
            assertTrue(type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                .all { Modifier.isPrivate(it.modifiers) && Modifier.isFinal(it.modifiers) })
            assertTrue(type.declaredMethods.none { it.name.startsWith("set") })
        }
        assertEquals(listOf(ByteArray::class.java), Ja2SaveInspector::class.java.methods
            .single { it.name == "inspectLiveInventory" }.parameterTypes.toList())
    }

    @Test
    fun presentationCollectionsSnapshotTheirSourcesAndRawAccessorsNeverAlias() {
        val bytes = ByteArray(36) { (it + 1).toByte() }
        val original = bytes.copyOf()
        val record = InventoryObjectParser.parse(bytes)
        val slots = InventorySlotRole.entries.map { InventorySlot(it, record) }.toMutableList()
        val merc = MercInventoryEntry(7, "Synthetic 7", "P7", slots)
        val entries = mutableListOf(merc)
        val result = LiveInventoryInspectionResult.Success(
            SaveInspectionFormat(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                SaveCompatibility.SUPPORTED, SaveFamily.UNKNOWN, 103, "04.12.02"),
            entries,
        )
        slots.clear()
        entries.clear()
        bytes.fill(0)
        assertEquals(1, result.inventories.size)
        assertEquals(19, result.inventories.single().slots.size)
        assertFailsWith<UnsupportedOperationException> { (result.inventories as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (merc.slots as MutableList).clear() }
        assertNotSame(record.rawRecord, record.rawRecord)
        record.rawRecord.fill(0)
        assertContentEquals(original, record.rawRecord)
        val payload = assertIs<InventoryPayload.Unknown>(record.payload)
        assertNotSame(payload.rawPayload, payload.rawPayload)
        payload.rawPayload.fill(0)
        assertContentEquals(original.copyOfRange(4, 16), payload.rawPayload)
    }
}
