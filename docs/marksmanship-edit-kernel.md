# Issue #36: bounded profile marksmanship rewrite kernel

## Status and semantic boundary

The core kernel exists; **production editing remains disabled**. The public
`Ja2MarksmanshipEditor()` has no enabled capability and cannot return candidate
bytes. `Ja2SaveInspector` and Android retain their read-only behavior. There is
no UI, output adapter, file write, placement, replacement, bridge, or permission
change. This implementation does not enable the normal format for real saves.

The only operation changes serialized `MERCPROFILESTRUCT` profile/base
marksmanship. It does not establish or edit a hired merc's live/current tactical
marksmanship. `SOLDIERTYPE` may hold current values separately; its ciphertext
is preserved. This kernel must not be exposed as a live merc-stat editor.

The governing [Issue #12 contract](transactional-edit-safety.md) remains in force,
including its independent local-evidence and output-adapter gates.

## Evidence and independent expression

Existing repository evidence was rechecked before implementation:

- [Profile decode](profile-decode-v0.1.md) establishes 170 records, 716 bytes per
  record, signed marksmanship at 353, and all currently parsed profile offsets.
- [Rotation recovery](profile-rotation-recovery.md) establishes per-record reset,
  checksum recurrence and offsets, unsigned inventory/count contributions, and
  stored checksum at 696..699. Its immutable upstream factual provenance is
  JA2 Reborn `743f38a6ca86c81893376c2576277db660320170`, notably
  `src/game/Tactical/LoadSaveMercProfile.cc` and `src/game/SaveLoadGame.cc`.
- Existing Kotlin recovery independently checks this recurrence. The approved
  Python recovery fixture supplies 170 independent plaintext, checksum, and
  ciphertext vectors, including signed and unsigned boundary contributions.
- The existing decryptor's project-authored 52-byte known vector crosses the
  49-byte rotation boundary and independently anchors the forward transform.

There is no newly established gameplay range. The API accepts the evidenced
signed INT8 **representation contract**, -128..127, for both old and new values.
It does not assert that all these values are game-valid. In particular, neither
INT8 storage nor game familiarity justifies inventing a 0..100 logical range.
Production remains disabled; a future enablement must independently justify
its admitted values across the entire observable capability domain.

`NormalSaveBlockEncryptor` is the project-authored algebraic inverse:
`c[i] = (p[i] + c[i-1] + rotation[i mod 49]) mod 256`, with preceding ciphertext
zero at the beginning of every call. It validates exact sizes before output
allocation and uses structured plaintext-size exceptions analogous to the
decryptor. It contains no upstream rotation data.

`NormalProfileChecksum.calculate` accepts exactly 716 decrypted bytes. Starting
with 1, it folds the signed pairs `(334,297)`, `(405,335)`, `(296,353)`,
`(261,411)`, `(339,352)` using `(s + a + 1) * (b + 1) mod 2^32`, then adds 19
unsigned little-endian inventory IDs beginning at 416 and 19 unsigned counts
beginning at 377. Long intermediates safely cover signed multiplications.
The stored checksum is not an input. The existing recovery implementation is
unchanged and provides a separate integrity check during admission.

These are expressions of already recorded interoperability equations and
offset facts. No upstream implementation, comments, source-shaped control flow,
tables, or private save bytes were copied. This is not a clean-room or legal
clearance claim; the [licensing boundary](licensing-boundary-review.md) still
requires independent boundary review before merge.

## API and authority

```kotlin
Ja2MarksmanshipEditor().edit(
    source: ByteArray,
    request: MarksmanshipEditRequest,
): MarksmanshipEditResult

MarksmanshipEditRequest(
    profileId: Int,                    // 0..169
    expectedCurrentMarksmanship: Int,  // required; signed representation
    newMarksmanship: Int,              // signed representation
)
```

There is one final fixed-size request, with no collections, nullable
preconditions, generic operations, caller assertions, raw offsets, or callbacks
in the public API. Expected-current is mandatory. A final immutable request
copy is made only after scalar admission. Source size is checked first from
`ByteArray.size`, then all scalars, before copying, hashing, or inspecting source.
Callers must not concurrently mutate the source during the snapshot copy;
after capture, caller mutations cannot change the transaction.

The Java 21 result is JVM-sealed: `MarksmanshipEditResult` permits only the
public final `Failure(stage, reason)` and public sealed `VerifiedCandidate`
read-only interface. The view permits exactly the package-private final
`Ja2MarksmanshipEditor.VerifiedCandidateImpl`. Its constructor is JVM-private
and is called only by its Java nestmate editor, after verification and provenance.
Failure has only enum fields and exposes no bytes. Success copies bytes at
construction and every getter; the explicit 12-relation report uses `List.of`.
Provenance remains the immutable Kotlin value model.

The public final Java editor exposes only the normal no-argument constructor,
`edit(byte[], MarksmanshipEditRequest)`, and the constant `MAX_SAVE_BYTES`.
The capability-bearing constructor, transaction, and candidate-validation
continuation are bytecode-private. There is no companion, public transaction
bridge, synthetic access constructor, or callable capability factory. The writer
is a private Java nested class with private constructor/methods.
Kotlin helpers implement pure verification and format algorithms; none consumes
the capability descriptor or constructs a transaction success.

Tests explicitly use `setAccessible(true)` on the private editor constructor,
supply inspector/bounds/enum-only observer, then invoke ordinary public `edit`.
The immutable `SyntheticMarksmanshipCapability` descriptor alone grants zero
authority. Both bounds must be in 0..16,777,216 and can only tighten core limits.
Identity `PROJECT_AUTHORED_SYNTHETIC_V103`, revision 1, is test evidence, not a
producer-family claim or production enablement. Ordinary Java/reflection,
including synthetic members, cannot reach that constructor or mint success.
Deliberate reflection with access suppression is privileged test machinery;
it is not an application security boundary against arbitrary JVM agents.

Candidate corruption tests separately invoke the bytecode-private validation
continuation with explicit access suppression. They change its actual candidate
input, then exercise mandatory admission, parsing, verification, and provenance.
No production callback carries unverified bytes.

## Exact-byte detector binding

`Ja2SaveInspector.detect`, `inspectV01`, and every layout-specific method share
one checked-detection path:

1. Copy caller bytes into a private `parseSnapshot`.
2. Copy that snapshot into a separate `detectorSnapshot`; only this copy reaches
   the detector.
3. Catch detector `RuntimeException` as the fixed `DETECTION_FAILED` reason.
4. Require byte equality after a normal detector return; any difference discards
   all detector metadata/evidence and returns `DETECTOR_MUTATED_INPUT`.
5. Parse only the untouched `parseSnapshot` after successful admission.

Both detector failures yield UNKNOWN layout, compatibility, family and identity,
empty evidence and default facts. `inspectV01` returns unknown format with
`CORRUPT_INPUT / DETECTION_FAILED`; guarded interpretation throws only sanitized
`SaveInterpretationAdmissionException` codes. Mutation followed by an exception
uses `DETECTION_FAILED`. No raw exception text survives. A retained detector
array cannot later modify caller bytes, parser input, or returned models.
Non-mutating production detection keeps the same interpretation behavior.
This adds no inspector-wide size limit; editor size admission still precedes
all editor-owned source copying, hashing, detection, and parsing.

## Transaction, preservation, and verification

After structural admission, core snapshots and hashes the source, requires a
capability and its tighter source bound, and calls the public inspection and
profile-table entry points. Admission requires exactly `SUPPORTED`, normal
non-Linux v103, build label `04.12.02`; read support alone is insufficient.
All 170 profiles and the campaign/roster baseline are retained as logical facts.
The old-value precondition must match before mutation.

Framing and bounded recovery supply an invocation-local rotation. The mutation
decrypts one target record, changes byte 353, rewrites its four checksum bytes,
and re-encrypts exactly that record. No rotation enters results or a cache.
A fixed-capacity private writer checks core, capability, and exact predicted
source-length bounds before allocation, and checks every range/copy using
remaining capacity. Overflow, underfill, reuse, and invalid ranges are terminal.

The candidate is hashed, then mandatorily re-admitted and reparsed through
`inspectV01` and `parseBuild041202NormalNonLinuxProfiles` on the same inspector.
The verifier checks these fixed aggregate relations over their complete domains:

| Relation | Expected and observed passing condition |
| --- | --- |
| Exact format identity | Candidate equals admitted source layout/version/build/format facts |
| Exact size and layout | Same length, profile boundaries, counts, and framing metadata |
| Requested profile value | Reparsed and decrypted target equals new value; source equals precondition |
| All 170 profile facts | Full value equality after substituting only target marksmanship |
| All campaign facts | Full campaign-summary equality |
| All roster identities and stats | Ordered full equality after substituting target profile/base marksmanship if present |
| Target checksum | Stored checksum equals recomputation; public admission also verifies all profile checksums |
| Target plaintext envelope | Equality except byte 353 and bytes 696..699 |
| Ciphertext prefix | Target bytes before 353 are identical |
| All outside ciphertext | All bytes outside the exact target record are identical |
| No-op byte identity | An identical-value request produces byte-identical output through the same pipeline |
| Hash bindings | Recomputed source/candidate hashes and canonical request agree with provenance |

The allowed plaintext changes are an allowed set, not a requirement that each
checksum byte differ. The ciphertext comparison conservatively requires equality
everywhere except target offsets 353..715. Model equality includes every current
data-class property, including names, without publishing those values in reports.
New model coverage requires explicit capability review and a verification revision.

Successful provenance binds source and candidate SHA-256 and sizes, exact admitted
format, the immutable request, a canonical 24-character encoding (three signed
32-bit little-endian integers in field order), capability identity/revision,
verification identity/outcome, and transaction model version 1. The fixed report
identifies each relation above with a passing observed result; it publishes no
names, source payloads, offsets, rotation bytes, or raw diagnostics. No timestamps
or random identifiers are used, so identical transactions have equal provenance.

## Finite resource contract

| Resource | Core-owned bound |
| --- | --- |
| Source and candidate | 16,777,216 bytes each; capability may tighten |
| Candidate layout | Exactly source length, with one 716-byte replacement |
| Raw requests | Exactly one; no collection overload |
| Caller assertions / variable components / nesting | Zero; absent from the API |
| Request representation | Three fixed 32-bit integers; 12 canonical bytes / 24 hex characters |
| Target domain | 170 records; one target |
| Expanded verification plan / report | Exactly 12 aggregate relations, no caller expansion; each traverses only the fixed domain or bounded arrays |
| Logical comparison domain | 170 profiles, at most 20 roster entries, one campaign/format summary |
| Profile text retained internally | At most 30 name + 10 nickname UTF-16 code units per profile, bounded by existing parser |
| Public evidence | Two 64-character hashes, 24-character canonical request, fixed identities/enums/integers; no variable payload values |

The fixed plan is code, not a dynamically expanded collection. Report construction
allocates exactly 12 typed entries; provenance's fixed text and scalar content
fits below 2 KiB in an explicit UTF-8 representation (excluding object headers).
There is no public general-purpose report serializer. Unbounded user text,
variable numeric precision, duplicate requests, collection growth, and recursive
assertions cannot be represented. The bounds are structural, not post-allocation
truncation checks, and missing capabilities always refuse.

Byte work is linear in the admitted source size. Read-boundary reuse repeats
bounded recovery (at most nine invocations in a successful transaction); each has
the existing 13,940 zero constraints and maximum 43,520 record decryptions, or
31,160,320 decrypted bytes. Total worst-case recovery work is therefore bounded
by 391,680 record decryptions, sequentially processed, not retained together.
Each of the four checked inspector calls in a successful transaction (source
inspection/profiles and candidate inspection/profiles) uses a parse snapshot and
an additional detector snapshot, each at most 16 MiB, plus one linear equality
comparison. Compared with the prior single-snapshot inspector this adds at most
64 MiB of cumulative allocation and four linear comparisons over 64 MiB of
byte positions (reading both arrays) per maximum-size transaction, with at most
32 MiB of snapshot arrays per active inspector
call (excluding detector/parser working buffers and GC-delayed reclamation).
Source, candidate writer, both inspection snapshots, and success ownership copies
are each individually bounded; there is no unbounded intermediate serialization.
This conservative cost model is for this kernel only, not production performance
qualification. Local workload/peak-memory evidence remains part of enablement.

## Tests and outstanding gates

New tests cover independent encryption vectors, inverse/reset properties,
structured size diagnostics, signed checksum wrap, all 170 manifested Python
vectors, roster/non-roster/first/last targets, signed representation endpoints,
stale preconditions, invalid source/capability, scalar admission before work,
exact and excessive byte limits, tighter bounds, writer terminal failure,
immutable inputs/outputs, complete preservation, deterministic provenance,
mandatory candidate reparse failures, logical drift, no-op identity, and the
byte-free failure surface. Fixtures remain unchanged and are accessed through
the existing validator-controlled resource pipeline.

All main/test Kotlin and Java sources compile locally with cached Kotlin 2.4.10
and JDK 21 (`--release 21` for Java). Main Kotlin resolves Java sources before
`javac` compiles the shell; test Kotlin/Java compile separately against main,
with Kotlin friend paths for internal descriptors and verification helpers. This exercises mixed
language interoperability; Gradle/CI task execution itself remains unverified.

The 14 resource-independent tests pass using the cached JUnit Platform/Jupiter
launcher: four admission/writer tests, four primitive encryption/checksum tests,
two inspector isolation tests, and four JVM authority tests. The latter include
16 rejected adversarial Java compilations across both the core and an external
package, two successful public-API control compilations, and the prior ordinary
reflection constructor/companion/transaction probes without `setAccessible`.
`javap -p -v` confirms classfile version 65, private authority constructors and
methods, a private success constructor with Java nestmate access, the exact
sealed `PermittedSubclasses`, and no public synthetic authority bridge.

Full `:core:test --no-daemon` was attempted normally with
Gradle 9.5.0/JDK 21 but failed before configuration:

```text
Could not create service of type FileLockContentionHandler using BasicGlobalScopeServices.createFileLockContentionHandler().
  > Could not determine a usable wildcard IP for this machine.
```

Both approved fixture materialization requests (header and profile recovery)
failed with `ValidationError: fixture synthetic-build-04.12.02-header-v1 generator
failed in its exact snapshot`. Resource-dependent whole-save, retained-array,
conceal-damage, candidate-reparse, and manifested-vector tests therefore remain
unexecuted locally. No isolation/admission boundary was weakened or bypassed.

The source-derived encryption/checksum additions still require the separate
Issue-8-style **pre-merge licensing boundary review** mandated by
[licensing-boundary-review.md](licensing-boundary-review.md). This technical
construction does not satisfy that gate and adds no license or upstream text.

Before production enablement, run the complete focused/full suite with working
fixture isolation, then obtain authorized local no-op, preservation, integrity,
and serialize/reparse evidence under the fixture policy for the entire
runtime-indistinguishable admission domain. Independently qualify the gameplay
range, resource costs, and profile/current-stat relationship before any product
editor claim. Synthetic passing tests alone do not satisfy those gates.
