package com.phishtopia.ja2fieldkit.core;

/** One bounded logical operation, bound to the exact bytes the caller inspected. */
public record SaveEditRequest(SourceIdentity expectedSource, Operation operation) {
    /** An assertion, never admission authority. Core recomputes both fields. */
    public record SourceIdentity(int size, String sha256) {}

    /** Closed operation set; future operations require their own complete verification plan. */
    public sealed interface Operation permits SetHiredMarksmanship {}

    /** Direct set of both base and current values, with a required shared precondition. */
    public record SetHiredMarksmanship(int profileId, int expectedCurrent, int value) implements Operation {}
}
