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

import static com.phishtopia.ja2fieldkit.core.SaveEditResult.*;
import static com.phishtopia.ja2fieldkit.core.SaveEditResult.Reason.*;
import static com.phishtopia.ja2fieldkit.core.SaveEditResult.Stage.*;

/**
 * Source-bound, in-memory transaction. Placement belongs to a separately qualified adapter.
 * Production capability is disabled; callers must not mutate source during snapshot capture.
 */
public final class Ja2SaveEditor {
    public static final int MAX_SAVE_BYTES = 16_777_216;
    private final Ja2SaveInspector inspector;
    private final SyntheticSaveEditCapability capability;
    private final Consumer<EditWork> observe;

    public Ja2SaveEditor() { this(new Ja2SaveInspector(), null, work -> {}); }

    // Deliberate access suppression is required by synthetic tests; no public capability route.
    private Ja2SaveEditor(Ja2SaveInspector inspector, SyntheticSaveEditCapability capability,
            Consumer<EditWork> observe) {
        this.inspector = Objects.requireNonNull(inspector);
        this.capability = capability;
        this.observe = Objects.requireNonNull(observe);
    }

    public SaveEditResult edit(byte[] source, SaveEditRequest request) {
        Stage stage = STRUCTURE;
        if (source == null) return failure(stage, INVALID_REQUEST);
        if (source.length > MAX_SAVE_BYTES) return failure(stage, SOURCE_TOO_LARGE);
        if (!validRequest(request)) return failure(stage, INVALID_REQUEST);
        // Records contain only final scalars, a bounded String and a sealed scalar operation.
        try {
            stage = SOURCE_IDENTITY;
            observe.accept(EditWork.SOURCE_SNAPSHOT);
            byte[] snapshot = source.clone();
            observe.accept(EditWork.SOURCE_HASH);
            var identity = identity(snapshot);
            if (!identity.equals(request.expectedSource())) return failure(stage, SOURCE_IDENTITY_MISMATCH);
            stage = CAPABILITY;
            if (capability == null) return failure(stage, CAPABILITY_DISABLED);
            if (snapshot.length > capability.getMaxSourceBytes()) return failure(stage, CAPABILITY_SIZE_LIMIT);
            stage = SOURCE_PARSE;
            observe.accept(EditWork.SOURCE_INSPECTION);
            var parsed = inspector.inspectV01(snapshot);
            if (!(parsed instanceof SaveInspectionV01Result.Success baseline)) return failure(stage, INVALID_SOURCE);
            if (!exactFormat(baseline.getFormat())) return failure(stage, UNSUPPORTED_FORMAT);
            var profiles = inspector.parseBuild041202NormalNonLinuxProfiles(snapshot);
            var liveParsed = inspector.inspectLiveMercState(snapshot);
            if (!(liveParsed instanceof LiveMercStateInspectionResult.Success live)
                    || !baseline.getFormat().equals(live.getFormat()) || profiles.size() != 170
                    || baseline.getRoster().size() > 20 || live.getMercs().size() > 20) return failure(stage, INVALID_SOURCE);
            for (int id = 0; id < 170; id++) {
                if (profiles.get(id).getProfileId() != id) return failure(stage, INVALID_SOURCE);
            }
            var locations = NormalNonLinuxRosterDecoder.INSTANCE.validatedRecordLocations(snapshot);
            var operation = request.operation();
            int id = operation.profileId();
            stage = PRECONDITION;
            var targets = locations.stream().filter(it -> it.getProfileIndex() == id).toList();
            var liveTargets = live.getMercs().stream().filter(it -> it.getProfileIndex() == id).toList();
            if (targets.size() != 1 || !targets.getFirst().getPlayerMerc() || liveTargets.size() != 1
                    || baseline.getRoster().stream().filter(it -> it.getProfileIndex() == id).count() != 1) {
                return failure(stage, TARGET_NOT_UNIQUE_HIRED_MERC);
            }
            if (operation instanceof SaveEditRequest.SetHiredStat stat
                    && (stat.stat().profileValue(profiles.get(id)) != stat.expectedCurrent()
                    || stat.stat().liveValue(liveTargets.getFirst().getStats()) != stat.expectedCurrent())) {
                return failure(stage, EXPECTED_CURRENT_MISMATCH);
            }
            var frame = NormalNonLinuxProfileFramer.INSTANCE.frameBuild041202(snapshot);
            var rotation = NormalProfileRotationRecovery.INSTANCE.recoverBuild041202(frame.getEncryptedProfileBytes());
            int profileStart = frame.getProfileStartOffset() + id * 716;
            int soldierStart = targets.getFirst().getAbsoluteOffset();
            // The closed operation contributes two fixed record rewrites to the fixed verification plan.
            byte[] profile = decrypt(snapshot, profileStart, 716, rotation);
            byte[] soldier = decrypt(snapshot, soldierStart, 2328, rotation);
            if (operation instanceof SaveEditRequest.SetHiredStat stat) {
                if (!stat.stat().admitsInjuryState(soldier)) return failure(stage, STAT_INJURY_PRESENT);
                profile[stat.stat().profileOffset()] = (byte) stat.value();
                soldier[stat.stat().soldierOffset()] = (byte) stat.value();
            } else if (operation instanceof SaveEditRequest.InventoryOperation inventory) {
                var rejection = InventoryMutation.admit(inventory, profile, soldier);
                if (rejection != null) return failure(stage, rejection);
                InventoryMutation.apply(inventory, profile, soldier);
            } else return failure(stage, INVALID_REQUEST);
            putChecksum(profile, 696, NormalProfileChecksum.INSTANCE.calculate(profile));
            putChecksum(soldier, 2208, NormalSoldierChecksum.INSTANCE.calculate(soldier));
            stage = SERIALIZATION;
            var writer = new BoundedCandidateWriter(snapshot.length, capability.getMaxCandidateBytes(),
                    () -> observe.accept(EditWork.CANDIDATE_ALLOCATION));
            writer.append(snapshot, 0, profileStart);
            byte[] encryptedProfile = NormalSaveBlockEncryptor.INSTANCE.encryptBlock(profile, 716, rotation);
            writer.append(encryptedProfile, 0, 716);
            writer.append(snapshot, profileStart + 716, soldierStart);
            byte[] encryptedSoldier = NormalSaveBlockEncryptor.INSTANCE.encryptBlock(soldier, 2328, rotation);
            writer.append(encryptedSoldier, 0, 2328);
            writer.append(snapshot, soldierStart + 2328, snapshot.length);
            return validateCandidate(snapshot, writer.finish(), request, baseline, profiles, live, rotation);
        } catch (CandidateBoundException ignored) {
            return failure(stage, CANDIDATE_SIZE_LIMIT);
        } catch (RuntimeException ignored) {
            return failure(stage, stage == SOURCE_PARSE ? INVALID_SOURCE : INTERNAL_FAILURE);
        }
    }

    // Private continuation is also the corruption-injection boundary for privileged tests.
    private SaveEditResult validateCandidate(byte[] source, byte[] candidate, SaveEditRequest request,
            SaveInspectionV01Result.Success baseline, List<MercProfile> profiles,
            LiveMercStateInspectionResult.Success live, SaveRotationTable rotation) {
        Stage stage = CANDIDATE_PARSE;
        try {
            if (capability == null) return failure(CAPABILITY, CAPABILITY_DISABLED);
            if (candidate.length > MAX_SAVE_BYTES || candidate.length > capability.getMaxCandidateBytes()) {
                return failure(stage, CANDIDATE_SIZE_LIMIT);
            }
            var candidateIdentity = identity(candidate);
            observe.accept(EditWork.CANDIDATE_INSPECTION);
            var parsed = inspector.inspectV01(candidate);
            if (!(parsed instanceof SaveInspectionV01Result.Success after)) return failure(stage, INVALID_CANDIDATE);
            var candidateProfiles = inspector.parseBuild041202NormalNonLinuxProfiles(candidate);
            var liveParsed = inspector.inspectLiveMercState(candidate);
            if (!(liveParsed instanceof LiveMercStateInspectionResult.Success afterLive)) return failure(stage, INVALID_CANDIDATE);
            stage = VERIFICATION;
            if (!SaveEditVerificationKt.verifySaveEdit(source, candidate,
                    request.operation(), baseline, after,
                    profiles, candidateProfiles, live, afterLive, rotation)) return failure(stage, VERIFICATION_MISMATCH);
            stage = PROVENANCE;
            var provenance = new Provenance(identity(source), candidateIdentity, baseline.getFormat(), request,
                    canonicalDigest(request), "PROJECT_AUTHORED_SYNTHETIC_V103", 1,
                    request.operation() instanceof SaveEditRequest.InventoryOperation ? "inventory-v1" : "hired-stat-v2",
                    request.operation() instanceof SaveEditRequest.InventoryOperation ? 3 : 2);
            if (!provenance.source().equals(request.expectedSource())
                    || !provenance.candidate().equals(identity(candidate))
                    || !provenance.requestSha256().equals(canonicalDigest(provenance.request()))) {
                return failure(stage, VERIFICATION_MISMATCH);
            }
            return new VerifiedCandidateImpl(candidate, provenance);
        } catch (RuntimeException ignored) {
            return failure(stage, stage == CANDIDATE_PARSE ? INVALID_CANDIDATE : INTERNAL_FAILURE);
        }
    }

    private static boolean validRequest(SaveEditRequest request) {
        if (request == null || request.expectedSource() == null
                || request.operation() == null) return false;
        var source = request.expectedSource();
        if (source.size() < 0 || source.size() > MAX_SAVE_BYTES || source.sha256() == null
                || source.sha256().length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = source.sha256().charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        var op = request.operation();
        if (op.profileId() < 0 || op.profileId() >= 170) return false;
        if (op instanceof SaveEditRequest.SetHiredStat stat) return stat.stat() != null
                && stat.stat().contains(stat.expectedCurrent()) && stat.stat().contains(stat.value());
        return op instanceof SaveEditRequest.InventoryOperation inventory && InventoryMutation.valid(inventory);
    }

    private static String canonicalDigest(SaveEditRequest request) {
        if (request.operation() instanceof SaveEditRequest.InventoryOperation inventory) {
            var e = inventory.expected();
            var value = InventoryMutation.desired(inventory);
            return sha256(ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
                    .put(HexFormat.of().parseHex(request.expectedSource().sha256())).putInt(request.expectedSource().size())
                    .putInt(inventory instanceof SaveEditRequest.ClearSlot ? 2 : 3)
                    .putInt(inventory.profileId()).putInt(inventory.slot())
                    .putInt(e.itemId()).putInt(e.count()).putInt(e.status())
                    .putInt(value.itemId()).putInt(value.count()).putInt(value.status()).array());
        }
        var op = (SaveEditRequest.SetHiredStat) request.operation();
        // Version, source digest/size, operation tag, stat tag, target, precondition, value; exactly 60 bytes.
        return sha256(ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
                .put(HexFormat.of().parseHex(request.expectedSource().sha256())).putInt(request.expectedSource().size())
                .putInt(1).putInt(op.stat().tag()).putInt(op.profileId()).putInt(op.expectedCurrent()).putInt(op.value()).array());
    }

    private static byte[] decrypt(byte[] bytes, int start, int size, SaveRotationTable rotation) {
        return NormalSaveBlockDecryptor.INSTANCE.decryptBlock(Arrays.copyOfRange(bytes, start, start + size), size, rotation);
    }
    private static void putChecksum(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
    private static boolean exactFormat(SaveInspectionFormat format) {
        return format.getCompatibility() == SaveCompatibility.SUPPORTED
                && format.getLayout() == SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX
                && Integer.valueOf(103).equals(format.getSaveVersion()) && "04.12.02".equals(format.getBuildLabel());
    }
    private static SaveEditRequest.SourceIdentity identity(byte[] bytes) {
        return new SaveEditRequest.SourceIdentity(bytes.length, sha256(bytes));
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static Failure failure(Stage stage, Reason reason) { return new Failure(stage, reason); }

    static final class VerifiedCandidateImpl implements VerifiedCandidate {
        private final byte[] snapshot;
        private final Provenance provenance;
        private VerifiedCandidateImpl(byte[] bytes, Provenance provenance) {
            this.snapshot = bytes.clone();
            this.provenance = provenance;
        }
        public byte[] getCandidateBytes() { return snapshot.clone(); }
        public Provenance getProvenance() { return provenance; }
        public List<Check> getVerification() {
            return List.of(Check.FORMAT, Check.LAYOUT, Check.REQUESTED_VALUES, Check.PROFILE_FACTS,
                    Check.CAMPAIGN_FACTS, Check.ROSTER_FACTS, Check.LIVE_FACTS, Check.RECORD_INTEGRITY,
                    Check.PLAINTEXT_PRESERVATION, Check.CIPHERTEXT_PRESERVATION, Check.NO_OP_IDENTITY, Check.HASH_BINDINGS);
        }
    }

    /** Sole whole-candidate accumulator; checks every bound before allocation or copying. */
    private static final class BoundedCandidateWriter {
        private final byte[] bytes;
        private int written;
        private boolean terminal;
        private BoundedCandidateWriter(int size, int maximum, Runnable beforeAllocation) {
            if (size < 0 || maximum < 0 || size > Math.min(MAX_SAVE_BYTES, maximum)) throw new CandidateBoundException();
            beforeAllocation.run();
            bytes = new byte[size];
        }
        private void append(byte[] source, int start, int end) {
            if (terminal || start < 0 || end < start || end > source.length || end - start > bytes.length - written) reject();
            System.arraycopy(source, start, bytes, written, end - start);
            written += end - start;
        }
        private byte[] finish() {
            if (terminal || written != bytes.length) reject();
            terminal = true;
            return bytes;
        }
        private void reject() { terminal = true; throw new CandidateBoundException(); }
    }
}
