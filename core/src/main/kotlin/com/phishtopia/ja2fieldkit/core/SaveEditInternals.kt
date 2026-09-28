package com.phishtopia.ja2fieldkit.core

/** Bounds alone grant no authority; no public editor constructor accepts this descriptor. */
internal class SyntheticSaveEditCapability(
    val maxSourceBytes: Int = Ja2SaveEditor.MAX_SAVE_BYTES,
    val maxCandidateBytes: Int = Ja2SaveEditor.MAX_SAVE_BYTES,
) {
    init {
        require(maxSourceBytes in 0..Ja2SaveEditor.MAX_SAVE_BYTES)
        require(maxCandidateBytes in 0..Ja2SaveEditor.MAX_SAVE_BYTES)
    }
}

internal enum class EditWork {
    SOURCE_SNAPSHOT, SOURCE_HASH, SOURCE_INSPECTION, CANDIDATE_ALLOCATION, CANDIDATE_INSPECTION,
}

internal class CandidateBoundException : IllegalArgumentException("Candidate size/layout limit")
