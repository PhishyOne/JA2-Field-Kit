package com.phishtopia.ja2fieldkit.core;

import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat;
import java.util.List;

/** Only the transaction's private success constructor can expose verified bytes. */
public sealed interface SaveEditResult permits SaveEditResult.Failure, SaveEditResult.VerifiedCandidate {
    enum Stage { STRUCTURE, SOURCE_IDENTITY, CAPABILITY, SOURCE_PARSE, PRECONDITION,
        SERIALIZATION, CANDIDATE_PARSE, VERIFICATION, PROVENANCE }
    enum Reason { SOURCE_TOO_LARGE, INVALID_REQUEST, SOURCE_IDENTITY_MISMATCH, CAPABILITY_DISABLED,
        CAPABILITY_SIZE_LIMIT, UNSUPPORTED_FORMAT, INVALID_SOURCE, TARGET_NOT_UNIQUE_HIRED_MERC,
        EXPECTED_CURRENT_MISMATCH, CANDIDATE_SIZE_LIMIT, INVALID_CANDIDATE, VERIFICATION_MISMATCH,
        INTERNAL_FAILURE }
    enum Check { FORMAT, LAYOUT, REQUESTED_VALUES, PROFILE_FACTS, CAMPAIGN_FACTS, ROSTER_FACTS,
        LIVE_FACTS, RECORD_INTEGRITY, PLAINTEXT_PRESERVATION, CIPHERTEXT_PRESERVATION,
        NO_OP_IDENTITY, HASH_BINDINGS }
    record Failure(Stage stage, Reason reason) implements SaveEditResult {}

    /** Fixed-size evidence: no names, paths, raw diagnostics or arbitrary report values. */
    record Provenance(SaveEditRequest.SourceIdentity source, SaveEditRequest.SourceIdentity candidate,
            SaveInspectionFormat format, SaveEditRequest request, String requestSha256,
            String capability, int capabilityRevision, String verificationPlan, int modelVersion) {}

    sealed interface VerifiedCandidate extends SaveEditResult permits Ja2SaveEditor.VerifiedCandidateImpl {
        byte[] getCandidateBytes();
        Provenance getProvenance();
        /** Each listed mandatory relation passed over its complete bounded domain. */
        List<Check> getVerification();
    }
}
