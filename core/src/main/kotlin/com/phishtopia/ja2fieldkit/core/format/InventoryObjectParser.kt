package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader
import com.phishtopia.ja2fieldkit.core.model.InventoryAttachment
import com.phishtopia.ja2fieldkit.core.model.InventoryObject
import com.phishtopia.ja2fieldkit.core.model.InventoryPayload

internal enum class ItemSerializationKind {
    GENERIC_STATUS, AMMO, GUN, KEY, MONEY, ACTION_OR_SWITCH, OWNERSHIP, UNKNOWN,
}

/** Item metadata alone selects semantics; this seam confers read authority only. */
internal fun interface ItemSerializationResolver {
    fun resolve(itemId: Int): ItemSerializationKind
}

internal enum class InventoryObjectFailure { WRONG_RECORD_SIZE }

internal class InventoryObjectException(val actualSize: Int) : IllegalArgumentException(
    "Inventory object: ${InventoryObjectFailure.WRONG_RECORD_SIZE} (size=$actualSize, expected=36)",
) {
    val reason = InventoryObjectFailure.WRONG_RECORD_SIZE
    val expectedSize = 36
}

/** Project-authored interpretation of the Issue #38 fact specification; no item catalog. */
internal object InventoryObjectParser {
    fun parse(
        bytes: ByteArray,
        resolver: ItemSerializationResolver = ItemSerializationResolver { ItemSerializationKind.UNKNOWN },
    ): InventoryObject {
        if (bytes.size != 36) throw InventoryObjectException(bytes.size)
        val record = bytes.copyOf()
        val reader = LittleEndianReader(record)
        val itemId = reader.u16(0)
        val count = reader.u8(2)
        val reservedByte3 = reader.u8(3)
        val attachments = List(4) { InventoryAttachment(reader.u16(16 + 2 * it), reader.i8(24 + it).toInt()) }
        val flags = reader.u8(28)
        val mission = reader.u8(29)
        val trap = reader.i8(30).toInt()
        val imprint = reader.u8(31)
        val weight = reader.u8(32)
        val used = reader.u8(33)
        val finalReserved = List(2) { reader.u8(34 + it) }
        fun unsigned(start: Int, count: Int) = List(count) { reader.u8(start + it) }
        fun signed(start: Int, count: Int) = List(count) { reader.i8(start + it).toInt() }

        val payload = if (itemId == 0 && count == 0) {
            InventoryPayload.Empty
        } else {
            // Noncanonical zero ID/count combinations preserve facts without assigning semantics.
            val kind = if (itemId == 0 || count == 0) ItemSerializationKind.UNKNOWN else resolver.resolve(itemId)
            when (kind) {
                ItemSerializationKind.GENERIC_STATUS -> InventoryPayload.GenericStatus(signed(4, 8), unsigned(12, 4))
                ItemSerializationKind.AMMO -> InventoryPayload.Ammo(unsigned(4, 8), unsigned(12, 4))
                ItemSerializationKind.GUN -> InventoryPayload.Gun(
                    reader.i8(4).toInt(), reader.u8(5), reader.u8(6), reader.u8(7),
                    reader.u16(8), reader.i8(10).toInt(), unsigned(11, 5),
                )
                ItemSerializationKind.KEY -> InventoryPayload.Key(signed(4, 6), reader.u8(10), unsigned(11, 5))
                ItemSerializationKind.MONEY -> InventoryPayload.Money(
                    reader.i8(4).toInt(), unsigned(5, 3), reader.u32(8), unsigned(12, 4),
                )
                ItemSerializationKind.ACTION_OR_SWITCH -> InventoryPayload.ActionOrSwitch(
                    reader.i8(4).toInt(), reader.u8(5), reader.u16(6), reader.u8(8),
                    reader.u8(9), reader.u8(10), reader.u8(11), unsigned(12, 4),
                )
                ItemSerializationKind.OWNERSHIP -> InventoryPayload.Ownership(reader.u8(4), reader.u8(5), unsigned(6, 10))
                ItemSerializationKind.UNKNOWN -> InventoryPayload.Unknown(reader.bytes(4, 12))
            }
        }
        return InventoryObject(
            itemId, count, reservedByte3, attachments, flags, mission, trap, imprint,
            weight, used, finalReserved, payload, record,
        )
    }
}
