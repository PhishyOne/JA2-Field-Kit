package com.phishtopia.ja2fieldkit.core;

import java.util.List;
import java.util.Objects;

/** JVM-sealed result boundary. Only the editor's private transaction can mint success. */
public sealed interface MarksmanshipEditResult
        permits MarksmanshipEditResult.Failure, MarksmanshipEditResult.VerifiedCandidate {
    final class Failure implements MarksmanshipEditResult {
        private final MarksmanshipEditStage stage;
        private final MarksmanshipEditReason reason;

        public Failure(MarksmanshipEditStage stage, MarksmanshipEditReason reason) {
            this.stage = Objects.requireNonNull(stage);
            this.reason = Objects.requireNonNull(reason);
        }

        public MarksmanshipEditStage getStage() { return stage; }
        public MarksmanshipEditReason getReason() { return reason; }
    }

    /** Read-only view, with no constructor, factory, or externally implementable subtype. */
    sealed interface VerifiedCandidate extends MarksmanshipEditResult
            permits Ja2MarksmanshipEditor.VerifiedCandidateImpl {
        byte[] getCandidateBytes();
        MarksmanshipEditProvenance getProvenance();
        List<MarksmanshipCheckResult> getVerification();
    }
}
