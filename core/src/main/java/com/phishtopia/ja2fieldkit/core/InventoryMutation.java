package com.phishtopia.ja2fieldkit.core;

import java.util.Arrays;
import static com.phishtopia.ja2fieldkit.core.SaveEditRequest.*;
import static com.phishtopia.ja2fieldkit.core.SaveEditResult.Reason.*;

/** Fixed interoperability facts, not a general item serializer. See inventory-edit-evidence.md. */
final class InventoryMutation {
    private InventoryMutation() {}

    static boolean simpleItem(int id) { return id == 201 || id == 202 || id == 203; }

    static boolean valid(InventoryOperation op) {
        var e = op.expected();
        if (op.slot() < 0 || op.slot() > 18 || e == null || e.itemId() < 0 || e.itemId() > 65535) return false;
        if (e.itemId() == 0 ? e.count() != 0 || e.status() != 0 : e.count() != 1 || e.status() < 1 || e.status() > 100) return false;
        return !(op instanceof SetSimpleItem set) || (set.itemId() > 0 && set.itemId() <= 65535
                && set.count() == 1 && set.status() >= 1 && set.status() <= 100);
    }

    static ExpectedSlot desired(InventoryOperation op) {
        return op instanceof SetSimpleItem set ? new ExpectedSlot(set.itemId(), set.count(), set.status()) : new ExpectedSlot(0, 0, 0);
    }

    static SaveEditResult.Reason admit(InventoryOperation op, byte[] profile, byte[] soldier) {
        if (op.slot() < 7 || op.slot() > 10) return UNSUPPORTED_INVENTORY_SLOT;
        int slot = op.slot(), at = 12 + 36 * slot;
        // The four big pockets map to bits 4..7, not to their slot index.
        if (((profile[412] & 255) & (1 << (slot - 3))) != 0 || (soldier[at + 28] & 1) != 0) return INVENTORY_UNDROPPABLE;
        var e = op.expected();
        if (u16(profile, 416 + 2 * slot) != e.itemId() || (profile[377 + slot] & 255) != e.count()
                || (profile[358 + slot] & 255) != e.status() || u16(soldier, at) != e.itemId()
                || (soldier[at + 2] & 255) != e.count() || (soldier[at + 4] & 255) != e.status()) return EXPECTED_CURRENT_MISMATCH;
        if ((e.itemId() != 0 && !simpleItem(e.itemId()))
                || (op instanceof SetSimpleItem set && !simpleItem(set.itemId()))) return UNSUPPORTED_INVENTORY_OBJECT;
        byte[] canonical = object(e);
        // Accept the zero-initialized empty representation too; an empty clear is byte-identical.
        if (e.itemId() == 0 && soldier[at + 32] == 0) canonical[32] = 0;
        for (int i = 0; i < 36; i++) if (soldier[at + i] != canonical[i]) return UNSUPPORTED_INVENTORY_OBJECT;
        return null;
    }

    static byte[] object(ExpectedSlot state) {
        byte[] bytes = new byte[36];
        bytes[0] = (byte) state.itemId(); bytes[1] = (byte) (state.itemId() >>> 8);
        bytes[2] = (byte) state.count(); bytes[4] = (byte) state.status();
        // Pinned InjectObject clamps Weight (grams) to 1..255. All three kits exceed 255g.
        bytes[32] = (byte) (state.itemId() == 0 ? 1 : 255);
        return bytes;
    }

    static void apply(InventoryOperation op, byte[] profile, byte[] soldier) {
        if (op.expected().equals(desired(op))) return;
        var state = desired(op);
        int slot = op.slot();
        profile[416 + 2 * slot] = (byte) state.itemId();
        profile[417 + 2 * slot] = (byte) (state.itemId() >>> 8);
        profile[377 + slot] = (byte) state.count(); profile[358 + slot] = (byte) state.status();
        System.arraycopy(object(state), 0, soldier, 12 + 36 * slot, 36);
    }

    static boolean verify(InventoryOperation op, byte[] oldProfile, byte[] newProfile, byte[] oldSoldier, byte[] newSoldier) {
        if (admit(op, oldProfile, oldSoldier) != null) return false;
        var state = desired(op);
        int slot = op.slot(), at = 12 + 36 * slot;
        if (u16(newProfile, 416 + 2 * slot) != state.itemId() || (newProfile[377 + slot] & 255) != state.count()
                || (newProfile[358 + slot] & 255) != state.status()) return false;
        byte[] expected = op.expected().equals(state) ? Arrays.copyOfRange(oldSoldier, at, at + 36) : object(state);
        return Arrays.equals(expected, Arrays.copyOfRange(newSoldier, at, at + 36));
    }

    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
}
