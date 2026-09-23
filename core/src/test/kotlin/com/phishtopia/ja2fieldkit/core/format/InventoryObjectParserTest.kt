package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

/** Entirely project-authored synthetic records and synthetic item classifications. */
class InventoryObjectParserTest {
    private val kinds = ItemSerializationKind.entries.withIndex().associate { (index, kind) -> 50_000 + index to kind }
    private val resolver = ItemSerializationResolver { kinds[it] ?: ItemSerializationKind.UNKNOWN }

    @Test
    fun rejectsWrongSizesWithBoundedDiagnosticsBeforeResolving() {
        for (size in listOf(0, 1, 12, 35, 37, 72, 4096)) {
            val error = assertFailsWith<InventoryObjectException> {
                InventoryObjectParser.parse(ByteArray(size), ItemSerializationResolver { error("must not resolve") })
            }
            assertEquals(InventoryObjectFailure.WRONG_RECORD_SIZE, error.reason)
            assertEquals(size, error.actualSize)
            assertEquals(36, error.expectedSize)
            assertNull(error.cause)
        }
    }

    @Test
    fun exactCommonFactsAndDefensiveOwnership() {
        val bytes = byteArrayOf(
            0x34, 0x92.toByte(), 0xfe.toByte(), 0xa3.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            0x34, 0x12, 0x78, 0x56, 0xbc.toByte(), 0x9a.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x80.toByte(), 0xff.toByte(), 0, 0x7f,
            0x81.toByte(), 0x82.toByte(), 0x83.toByte(), 0x84.toByte(),
            0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
        )
        val original = bytes.copyOf()
        val result = InventoryObjectParser.parse(bytes)
        assertContentEquals(original, bytes)
        assertEquals(0x9234, result.itemId)
        assertEquals(254, result.objectCount)
        assertEquals(163, result.reservedByte3)
        assertEquals(listOf(0x1234, 0x5678, 0x9abc, 0xffff), result.attachments.map { it.itemId })
        assertEquals(listOf(-128, -1, 0, 127), result.attachments.map { it.status })
        assertEquals(listOf(129, 130, -125, 132, 133, 134),
            listOf(result.flags, result.mission, result.trap, result.imprint, result.serializedWeight, result.used))
        assertEquals(listOf(135, 136), result.finalReservedBytes)
        assertContentEquals(original, result.rawRecord)
        bytes.fill(0)
        result.rawRecord.fill(0)
        assertContentEquals(original, result.rawRecord)
        val unknown = assertIs<InventoryPayload.Unknown>(result.payload)
        unknown.rawPayload.fill(0)
        assertContentEquals(original.copyOfRange(4, 16), unknown.rawPayload)
        immutable(result.attachments)
        immutable(result.finalReservedBytes)
    }

    @Test
    fun genericAndAmmoUseDifferentSignednessForIdenticalPayloadBytes() {
        val generic = assertIs<InventoryPayload.GenericStatus>(parse(ItemSerializationKind.GENERIC_STATUS).payload)
        val ammo = assertIs<InventoryPayload.Ammo>(parse(ItemSerializationKind.AMMO).payload)
        assertEquals(listOf(-128, -1, 0, 127, -2, -36, -91, 90), generic.statuses)
        assertEquals(listOf(128, 255, 0, 127, 254, 220, 165, 90), ammo.shotsLeft)
        assertEquals(listOf(193, 194, 195, 196), generic.reserved)
        assertEquals(generic.reserved, ammo.reserved)
        immutable(generic.statuses)
        immutable(generic.reserved)
        immutable(ammo.shotsLeft)
        immutable(ammo.reserved)
    }

    @Test
    fun gunPreservesConditionJamMagazineAndAllReservedBytes() {
        val gun = assertIs<InventoryPayload.Gun>(parse(ItemSerializationKind.GUN).payload)
        assertEquals(-128, gun.status)
        assertEquals(255, gun.ammoType)
        assertEquals(0, gun.shotsLeft)
        assertEquals(127, gun.reservedByte7)
        assertEquals(0xdcfe, gun.loadedMagazineItemId)
        assertEquals(-91, gun.ammoStatus)
        assertEquals(listOf(90, 193, 194, 195, 196), gun.reserved)
        immutable(gun.reserved)
    }

    @Test
    fun keyPreservesSixSignedStatusesIdAndReservedBytes() {
        val key = assertIs<InventoryPayload.Key>(parse(ItemSerializationKind.KEY).payload)
        assertEquals(listOf(-128, -1, 0, 127, -2, -36), key.statuses)
        assertEquals(165, key.keyId)
        assertEquals(listOf(90, 193, 194, 195, 196), key.reserved)
        immutable(key.statuses)
        immutable(key.reserved)
    }

    @Test
    fun moneyReadsUnsignedLittleEndianAmountAndSignedCondition() {
        val money = assertIs<InventoryPayload.Money>(parse(ItemSerializationKind.MONEY).payload)
        assertEquals(-128, money.status)
        assertEquals(0x5aa5dcfeL, money.amount)
        assertEquals(listOf(255, 0, 127), money.reservedBeforeAmount)
        assertEquals(listOf(193, 194, 195, 196), money.reservedAfterAmount)
        immutable(money.reservedBeforeAmount)
        immutable(money.reservedAfterAmount)
        val high = record(ItemSerializationKind.MONEY).also { for (i in 8..11) it[i] = -1 }
        assertEquals(0xffff_ffffL, assertIs<InventoryPayload.Money>(InventoryObjectParser.parse(high, resolver).payload).amount)
    }

    @Test
    fun actionAndOwnershipPreserveFieldsWithoutInterpretingOpaqueBytes() {
        val action = assertIs<InventoryPayload.ActionOrSwitch>(parse(ItemSerializationKind.ACTION_OR_SWITCH).payload)
        assertEquals(listOf(-128, 255, 0x7f00, 254, 220, 165, 90), listOf(
            action.bombStatus, action.detonatorType, action.bombItemId, action.delayOrFrequency,
            action.owner, action.actionValue, action.tolerance,
        ))
        assertEquals(listOf(193, 194, 195, 196), action.reserved)
        immutable(action.reserved)
        val ownership = assertIs<InventoryPayload.Ownership>(parse(ItemSerializationKind.OWNERSHIP).payload)
        assertEquals(128, ownership.ownerProfile)
        assertEquals(255, ownership.ownerCivilianGroup)
        assertEquals(listOf(0, 127, 254, 220, 165, 90, 193, 194, 195, 196), ownership.reserved)
        immutable(ownership.reserved)
    }

    @Test
    fun emptyAndNoncanonicalZeroCombinationsNeverConsultResolverOrLoseBytes() {
        for ((id, count) in listOf(0 to 0, 0 to 8, 65535 to 0)) {
            val bytes = record(ItemSerializationKind.GUN).also {
                it[0] = id.toByte(); it[1] = (id ushr 8).toByte(); it[2] = count.toByte()
            }
            val parsed = InventoryObjectParser.parse(bytes, ItemSerializationResolver { error("must not resolve") })
            assertEquals(id, parsed.itemId)
            assertEquals(count, parsed.objectCount)
            if (id == 0 && count == 0) assertEquals(InventoryPayload.Empty, parsed.payload)
            else assertIs<InventoryPayload.Unknown>(parsed.payload)
            assertContentEquals(bytes, parsed.rawRecord)
        }
    }

    @Test
    fun noCatalogAndUnsupportedItemsRemainUnknownWithoutNormalization() {
        val bytes = record(ItemSerializationKind.GUN)
        assertIs<InventoryPayload.Unknown>(InventoryObjectParser.parse(bytes).payload)
        bytes[0] = -1; bytes[1] = -1
        val parsed = InventoryObjectParser.parse(bytes, resolver)
        assertEquals(65535, parsed.itemId)
        assertContentEquals(bytes.copyOfRange(4, 16), assertIs<InventoryPayload.Unknown>(parsed.payload).rawPayload)
        assertContentEquals(bytes, parsed.rawRecord)
    }

    @Test
    fun inputIsSnapshottedBeforeResolverRuns() {
        val bytes = record(ItemSerializationKind.GUN)
        val original = bytes.copyOf()
        var seenId = -1
        val parsed = InventoryObjectParser.parse(bytes, ItemSerializationResolver { id ->
            seenId = id
            bytes.fill(0)
            ItemSerializationKind.GUN
        })
        assertEquals(50_002, seenId)
        assertContentEquals(original, parsed.rawRecord)
        assertEquals(-128, assertIs<InventoryPayload.Gun>(parsed.payload).status)
    }

    private fun parse(kind: ItemSerializationKind): InventoryObject {
        val bytes = record(kind)
        val original = bytes.copyOf()
        return InventoryObjectParser.parse(bytes, resolver).also {
            assertContentEquals(original, bytes)
            assertContentEquals(original, it.rawRecord)
        }
    }

    private fun record(kind: ItemSerializationKind): ByteArray = ByteArray(36).also {
        val id = kinds.entries.single { entry -> entry.value == kind }.key
        it[0] = id.toByte(); it[1] = (id ushr 8).toByte(); it[2] = 1
        listOf(128, 255, 0, 127, 254, 220, 165, 90, 193, 194, 195, 196)
            .forEachIndexed { index, value -> it[4 + index] = value.toByte() }
    }

    private fun immutable(values: List<*>) {
        assertFailsWith<UnsupportedOperationException> { (values as MutableList).clear() }
    }
}
