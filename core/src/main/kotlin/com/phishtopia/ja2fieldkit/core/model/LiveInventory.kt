package com.phishtopia.ja2fieldkit.core.model

import java.util.Collections

/** Serialized order; field/slot facts await the Issue-8-style review documented in live-inventory-model.md. */
enum class InventorySlotRole(val slotIndex: Int) {
    HELMET(0), VEST(1), LEGS(2), HEAD_1(3), HEAD_2(4), MAIN_HAND(5), OFF_HAND(6),
    BIG_POCKET_1(7), BIG_POCKET_2(8), BIG_POCKET_3(9), BIG_POCKET_4(10),
    SMALL_POCKET_1(11), SMALL_POCKET_2(12), SMALL_POCKET_3(13), SMALL_POCKET_4(14),
    SMALL_POCKET_5(15), SMALL_POCKET_6(16), SMALL_POCKET_7(17), SMALL_POCKET_8(18),
}

private fun <T> snapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

data class InventoryAttachment(val itemId: Int, val status: Int)

/** Signed conditions are Int values in -128..127; opaque/reserved bytes are in 0..255. */
sealed interface InventoryPayload {
    data object Empty : InventoryPayload

    class GenericStatus internal constructor(statuses: List<Int>, reserved: List<Int>) : InventoryPayload {
        val statuses: List<Int> = snapshot(statuses)
        val reserved: List<Int> = snapshot(reserved)
    }

    class Ammo internal constructor(shotsLeft: List<Int>, reserved: List<Int>) : InventoryPayload {
        val shotsLeft: List<Int> = snapshot(shotsLeft)
        val reserved: List<Int> = snapshot(reserved)
    }

    class Gun internal constructor(
        val status: Int,
        val ammoType: Int,
        val shotsLeft: Int,
        val reservedByte7: Int,
        val loadedMagazineItemId: Int,
        val ammoStatus: Int,
        reserved: List<Int>,
    ) : InventoryPayload {
        val reserved: List<Int> = snapshot(reserved)
    }

    class Key internal constructor(statuses: List<Int>, val keyId: Int, reserved: List<Int>) : InventoryPayload {
        val statuses: List<Int> = snapshot(statuses)
        val reserved: List<Int> = snapshot(reserved)
    }

    class Money internal constructor(
        val status: Int,
        reservedBeforeAmount: List<Int>,
        val amount: Long,
        reservedAfterAmount: List<Int>,
    ) : InventoryPayload {
        val reservedBeforeAmount: List<Int> = snapshot(reservedBeforeAmount)
        val reservedAfterAmount: List<Int> = snapshot(reservedAfterAmount)
    }

    class ActionOrSwitch internal constructor(
        val bombStatus: Int,
        val detonatorType: Int,
        val bombItemId: Int,
        val delayOrFrequency: Int,
        val owner: Int,
        val actionValue: Int,
        val tolerance: Int,
        reserved: List<Int>,
    ) : InventoryPayload {
        val reserved: List<Int> = snapshot(reserved)
    }

    class Ownership internal constructor(
        val ownerProfile: Int,
        val ownerCivilianGroup: Int,
        reserved: List<Int>,
    ) : InventoryPayload {
        val reserved: List<Int> = snapshot(reserved)
    }

    class Unknown internal constructor(rawPayload: ByteArray) : InventoryPayload {
        private val bytes = rawPayload.copyOf()
        val rawPayload: ByteArray get() = bytes.copyOf()
    }
}

/** Exact read facts, without normalization, weight recomputation, or write authority. */
class InventoryObject internal constructor(
    val itemId: Int,
    val objectCount: Int,
    val reservedByte3: Int,
    attachments: List<InventoryAttachment>,
    val flags: Int,
    val mission: Int,
    val trap: Int,
    val imprint: Int,
    val serializedWeight: Int,
    val used: Int,
    finalReservedBytes: List<Int>,
    val payload: InventoryPayload,
    rawRecord: ByteArray,
) {
    val attachments: List<InventoryAttachment> = snapshot(attachments)
    val finalReservedBytes: List<Int> = snapshot(finalReservedBytes)
    private val bytes = rawRecord.copyOf()
    val rawRecord: ByteArray get() = bytes.copyOf()
}

class InventorySlot internal constructor(val role: InventorySlotRole, val objectRecord: InventoryObject)

class MercInventoryEntry internal constructor(
    val profileIndex: Int,
    val name: String,
    val nickname: String?,
    slots: List<InventorySlot>,
) {
    val slots: List<InventorySlot> = snapshot(slots)
}

/** Presentation-safe result: failures contain only the existing bounded inspection codes. */
sealed interface LiveInventoryInspectionResult {
    val format: SaveInspectionFormat

    class Success internal constructor(
        override val format: SaveInspectionFormat,
        inventories: List<MercInventoryEntry>,
    ) : LiveInventoryInspectionResult {
        val inventories: List<MercInventoryEntry> = snapshot(inventories)
    }

    data class Failure(
        override val format: SaveInspectionFormat,
        val failure: SaveInspectionFailure,
    ) : LiveInventoryInspectionResult
}
