package com.phishtopia.ja2fieldkit.core;

import com.phishtopia.ja2fieldkit.core.format.*;
import com.phishtopia.ja2fieldkit.core.model.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static com.phishtopia.ja2fieldkit.core.MarksmanshipEditReason.*;
import static com.phishtopia.ja2fieldkit.core.MarksmanshipEditStage.*;
import static com.phishtopia.ja2fieldkit.core.MarksmanshipVerificationCheck.*;

/**
 * Bounded profile/base marksmanship kernel. Production capability remains disabled.
 * Callers must not mutate source concurrently during its snapshot copy.
 * Java private/nestmate access, not Kotlin visibility metadata, guards edit authority.
 */
public final class Ja2MarksmanshipEditor {
    public static final int MAX_SAVE_BYTES = 16_777_216;
    private final Ja2SaveInspector inspector;
    private final SyntheticMarksmanshipCapability capability;
    private final Consumer<EditWork> observe;

    public Ja2MarksmanshipEditor() {
        this(new Ja2SaveInspector(), null, work -> {});
    }

    // Tests must explicitly suppress access checks. No public capability factory or bridge.
    private Ja2MarksmanshipEditor(Ja2SaveInspector inspector,
            SyntheticMarksmanshipCapability capability, Consumer<EditWork> observe) {
        this.inspector = Objects.requireNonNull(inspector);
        this.capability = capability;
        this.observe = Objects.requireNonNull(observe);
    }

    public MarksmanshipEditResult edit(byte[] source, MarksmanshipEditRequest request) {
        return transact(source, request);
    }

    private MarksmanshipEditResult transact(byte[] source, MarksmanshipEditRequest request) {
        MarksmanshipEditStage stage = STRUCTURE;
        if (source.length > MAX_SAVE_BYTES) return failure(stage, SOURCE_TOO_LARGE);
        if (request.getProfileId() < 0 || request.getProfileId() >= 170) {
            return failure(stage, INVALID_PROFILE_ID);
        }
        // Signed INT8 is the representation domain only, not a gameplay range.
        if (request.getExpectedCurrentMarksmanship() < -128 || request.getExpectedCurrentMarksmanship() > 127
                || request.getNewMarksmanship() < -128 || request.getNewMarksmanship() > 127) {
            return failure(stage, INVALID_MARKSMANSHIP);
        }
        var admitted = new MarksmanshipEditRequest(request.getProfileId(),
                request.getExpectedCurrentMarksmanship(), request.getNewMarksmanship());
        try {
            stage = SOURCE_IDENTITY;
            observe.accept(EditWork.SOURCE_SNAPSHOT);
            byte[] snapshot = source.clone();
            observe.accept(EditWork.SOURCE_HASH);
            String sourceHash = sha256(snapshot);
            stage = CAPABILITY;
            if (capability == null) return failure(stage, CAPABILITY_DISABLED);
            if (snapshot.length > capability.getMaxSourceBytes()) return failure(stage, CAPABILITY_SIZE_LIMIT);
            stage = SOURCE_PARSE;
            observe.accept(EditWork.SOURCE_INSPECTION);
            var inspection = inspector.inspectV01(snapshot);
            if (!(inspection instanceof SaveInspectionV01Result.Success baseline)) {
                return failure(stage, INVALID_SOURCE);
            }
            if (!exactFormat(baseline.getFormat())) return failure(stage, UNSUPPORTED_FORMAT);
            var profiles = inspector.parseBuild041202NormalNonLinuxProfiles(snapshot);
            if (profiles.size() != 170) return failure(stage, INVALID_SOURCE);
            for (int id = 0; id < 170; id++) {
                if (profiles.get(id).getProfileId() != id) return failure(stage, INVALID_SOURCE);
            }
            stage = PRECONDITION;
            if (profiles.get(admitted.getProfileId()).getMarksmanship() != admitted.getExpectedCurrentMarksmanship()) {
                return failure(stage, EXPECTED_CURRENT_MISMATCH);
            }
            // Fixed plan: 12 complete relations, 170 profiles, at most 20 roster entries.
            if (baseline.getRoster().size() > 20) return failure(stage, INVALID_SOURCE);
            var frame = NormalNonLinuxProfileFramer.INSTANCE.frameBuild041202(snapshot);
            var rotation = NormalProfileRotationRecovery.INSTANCE.recoverBuild041202(frame.getEncryptedProfileBytes());
            int start = frame.getProfileStartOffset() + admitted.getProfileId() * 716;
            byte[] changed = NormalSaveBlockDecryptor.INSTANCE.decryptBlock(
                    Arrays.copyOfRange(snapshot, start, start + 716), 716, rotation);
            changed[353] = (byte) admitted.getNewMarksmanship();
            long checksum = NormalProfileChecksum.INSTANCE.calculate(changed);
            for (int i = 0; i < 4; i++) changed[696 + i] = (byte) (checksum >>> (8 * i));

            stage = SERIALIZATION;
            var writer = new BoundedCandidateWriter(snapshot.length, capability.getMaxCandidateBytes(),
                    () -> observe.accept(EditWork.CANDIDATE_ALLOCATION));
            writer.append(snapshot, 0, start);
            byte[] encrypted = NormalSaveBlockEncryptor.INSTANCE.encryptBlock(changed, 716, rotation);
            writer.append(encrypted, 0, encrypted.length);
            writer.append(snapshot, start + 716, snapshot.length);
            return validateCandidate(snapshot, writer.finish(), admitted, baseline, profiles, rotation, sourceHash);
        } catch (CandidateBoundException ignored) {
            return failure(stage, CANDIDATE_SIZE_LIMIT);
        } catch (RuntimeException ignored) {
            return failure(stage, stage == SOURCE_PARSE ? INVALID_SOURCE : INTERNAL_FAILURE);
        }
    }

    /** Private continuation: corruption tests invoke this explicitly with access suppression. */
    private MarksmanshipEditResult validateCandidate(byte[] source, byte[] candidate,
            MarksmanshipEditRequest request, SaveInspectionV01Result.Success baseline,
            List<MercProfile> profiles, SaveRotationTable rotation, String sourceHash) {
        MarksmanshipEditStage stage = CANDIDATE_PARSE;
        try {
            if (capability == null) return failure(CAPABILITY, CAPABILITY_DISABLED);
            if (candidate.length > MAX_SAVE_BYTES || candidate.length > capability.getMaxCandidateBytes()) {
                return failure(stage, CANDIDATE_SIZE_LIMIT);
            }
            String candidateHash = sha256(candidate);
            observe.accept(EditWork.CANDIDATE_INSPECTION);
            var inspection = inspector.inspectV01(candidate);
            if (!(inspection instanceof SaveInspectionV01Result.Success reparsed)) {
                return failure(stage, INVALID_CANDIDATE);
            }
            var candidateProfiles = inspector.parseBuild041202NormalNonLinuxProfiles(candidate);
            stage = VERIFICATION;
            if (!MarksmanshipEditModelsKt.verifyMarksmanshipCandidate(source, candidate, request,
                    baseline, reparsed, profiles, candidateProfiles, rotation)) {
                return failure(stage, VERIFICATION_MISMATCH);
            }
            stage = PROVENANCE;
            var provenance = new MarksmanshipEditProvenance(sourceHash, source.length, candidateHash,
                    candidate.length, baseline.getFormat(), request, canonicalRequest(request),
                    MarksmanshipCapabilityIdentity.PROJECT_AUTHORED_SYNTHETIC_V103, 1,
                    "profile-marksmanship-verification-v1", EditVerificationOutcome.PASSED, 1);
            if (!provenance.getSourceSha256().equals(sha256(source))
                    || !provenance.getCandidateSha256().equals(sha256(candidate))
                    || !provenance.getCanonicalRequest().equals(canonicalRequest(provenance.getRequest()))) {
                return failure(stage, VERIFICATION_MISMATCH);
            }
            // Sole success construction site, after every mandatory check and provenance binding.
            return new VerifiedCandidateImpl(candidate, provenance);
        } catch (RuntimeException ignored) {
            return failure(stage, stage == CANDIDATE_PARSE ? INVALID_CANDIDATE : INTERNAL_FAILURE);
        }
    }

    /** Fixed-capacity, transaction-private writer; every bound precedes copying/allocation. */
    private static final class BoundedCandidateWriter {
        private final byte[] bytes;
        private int written;
        private boolean finished;
        private boolean failed;

        private BoundedCandidateWriter(int predictedSize, int capabilityMaximum, Runnable beforeAllocation) {
            if (predictedSize < 0 || capabilityMaximum < 0
                    || predictedSize > Math.min(MAX_SAVE_BYTES, capabilityMaximum)) {
                throw new CandidateBoundException();
            }
            beforeAllocation.run();
            bytes = new byte[predictedSize];
        }

        private void append(byte[] source, int start, int end) {
            if (failed || finished || start < 0 || end < start || end > source.length
                    || end - start > bytes.length - written) reject();
            System.arraycopy(source, start, bytes, written, end - start);
            written += end - start;
        }

        private byte[] finish() {
            if (failed || finished || written != bytes.length) reject();
            finished = true;
            return bytes;
        }

        private void reject() {
            failed = true;
            throw new CandidateBoundException();
        }
    }

    static final class VerifiedCandidateImpl implements MarksmanshipEditResult.VerifiedCandidate {
        private final byte[] snapshot;
        private final MarksmanshipEditProvenance provenance;
        private final List<MarksmanshipCheckResult> verification;

        private VerifiedCandidateImpl(byte[] bytes, MarksmanshipEditProvenance provenance) {
            this.snapshot = bytes.clone();
            this.provenance = provenance;
            // Explicit complete plan, never inferred from arbitrary input or enum expansion.
            this.verification = List.of(
                    passed(EXACT_FORMAT_IDENTITY), passed(EXACT_SIZE_AND_LAYOUT), passed(REQUESTED_PROFILE_VALUE),
                    passed(ALL_170_PROFILE_FACTS), passed(ALL_CAMPAIGN_FACTS), passed(ALL_ROSTER_IDENTITIES_AND_STATS),
                    passed(TARGET_CHECKSUM), passed(TARGET_PLAINTEXT_ENVELOPE), passed(CIPHERTEXT_PREFIX),
                    passed(ALL_OUTSIDE_CIPHERTEXT), passed(NO_OP_BYTE_IDENTITY), passed(HASH_BINDINGS));
        }

        public byte[] getCandidateBytes() { return snapshot.clone(); }
        public MarksmanshipEditProvenance getProvenance() { return provenance; }
        public List<MarksmanshipCheckResult> getVerification() { return verification; }
    }

    private static MarksmanshipCheckResult passed(MarksmanshipVerificationCheck relation) {
        return new MarksmanshipCheckResult(relation, EditVerificationOutcome.PASSED);
    }

    private static MarksmanshipEditResult.Failure failure(MarksmanshipEditStage stage, MarksmanshipEditReason reason) {
        return new MarksmanshipEditResult.Failure(stage, reason);
    }

    private static boolean exactFormat(SaveInspectionFormat format) {
        return format.getCompatibility() == SaveCompatibility.SUPPORTED
                && format.getLayout() == SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX
                && Integer.valueOf(103).equals(format.getSaveVersion()) && "04.12.02".equals(format.getBuildLabel());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String canonicalRequest(MarksmanshipEditRequest request) {
        return HexFormat.of().formatHex(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(request.getProfileId()).putInt(request.getExpectedCurrentMarksmanship())
                .putInt(request.getNewMarksmanship()).array());
    }
}
