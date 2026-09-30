package com.phishtopia.ja2fieldkit.core;

/** One bounded logical operation, bound to the exact bytes the caller inspected. */
public record SaveEditRequest(SourceIdentity expectedSource, Operation operation) {
    /** An assertion, never admission authority. Core recomputes both fields. */
    public record SourceIdentity(int size, String sha256) {}

    /** Closed operation set; future operations require their own complete verification plan. */
    public sealed interface Operation permits SetHiredStat, InventoryOperation {
        int profileId();
    }

    /** Plain slot assertion. The source identity binds every remaining object byte. */
    public record ExpectedSlot(int itemId, int count, int status) {}

    public sealed interface InventoryOperation extends Operation permits ClearSlot, SetSimpleItem {
        int slot();
        ExpectedSlot expected();
    }

    /** Clear a canonical empty or admitted plain single-item big-pocket slot. */
    public record ClearSlot(int profileId, int slot, ExpectedSlot expected) implements InventoryOperation {}

    /** Closed catalog: 201/202 (medical kits), 203 (toolkit); count one, status 1..100. */
    public record SetSimpleItem(int profileId, int slot, ExpectedSlot expected,
            int itemId, int count, int status) implements InventoryOperation {}

    /** Direct set of both base and current values, with a required shared precondition. */
    public record SetHiredStat(int profileId, HiredMercStat stat, int expectedCurrent, int value) implements Operation {}
}
