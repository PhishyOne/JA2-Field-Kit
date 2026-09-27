# Issue #55: synchronized hired-merc marksmanship kernel

## Status and semantic boundary

The core transaction directly sets **both profile/base and live/current
marksmanship for one currently hired, non-vehicle player merc**.
Production capability remains disabled: the public `Ja2MarksmanshipEditor()`
cannot return candidate bytes. Qualification uses explicit privileged test
reflection only. Synthetic evidence does not enable production editing.

This evolves the Issue #36 profile-only proof; there is no retained profile-only
transaction path. Prior authorized read-only qualification found all 18 hired
mercs initially synchronized, a byte-identical profile-only no-op, and a profile
89→90 candidate whose soldier remained 89. Those supplied observations explain
why profile-only editing must not be enabled. No private save was accessed for
this construction run.

The request retains `profileId` (the validated profile index),
`expectedCurrentMarksmanship`, and `newMarksmanship`. Both values must be in the
audited gameplay domain **0..100**. The target must occur in the exact validated
roster and live identity set, with both values equal to expected-current.
Disagreement, stale expected values, non-roster targets, vehicles, and duplicate
identities fail closed. A no-op follows the same pipeline and must preserve the
whole file byte for byte.

This is direct set, not training simulation. Gain counters, deltas/history,
value-gone-up/UI timing flags, salaries, dialogue and unrelated metadata are
preserved. Android remains read-only. This work adds no UI, output adapter,
filesystem placement, overwrite, other stat, inventory edit, or PC bridge.

## Audited interoperability facts and provenance

The following immutable audit identities were supplied for this construction;
no external source was browsed or upstream expression copied:

- JA2-Reborn revision `743f38a6ca86c81893376c2576277db660320170`.
- Stracciatella revision `8883ac43dc1b2b95a76286476f690526bc858565`.
- `Campaign.h`, blob `d06776b8a48f39ededbca1ce67bda86e91ea94a0`:
  the maximum stat value is 100.
- `Campaign.cc`, blob `38ff561e23cfc3cc043cb749146a167cc63c6a1b`:
  marksmanship actual values reside in the profile and soldier marksmanship
  fields; the minimum is zero and the maximum is 100. Hired stat changes
  synchronize the soldier actual value from the profile actual value.
- Reborn `Soldier_Create.cc`, blob `ae33325c12bb3da7aa07869f88da5dd8c2a40ff9`:
  soldier creation initializes live marksmanship from its profile.
- Reborn `Soldier_Profile.cc`, blob `9d70349155cc86377fd4f146cd08622805f2e2e4`:
  soldier-to-profile synchronization also exists.

These are project-authored statements of interoperability facts, not copied or
translated implementation/comments. They establish the synchronized direct-set
semantics and replace the old proof's INT8-only admission domain. They do not
establish legal clearance, production capability, or training simulation.
The [licensing boundary review](licensing-boundary-review.md) remains required.

Existing [profile decode](profile-decode-v0.1.md) and
[rotation recovery](profile-rotation-recovery.md) document the reviewed profile
framing, 170 records, per-record transform reset and checksum facts. The shared
`NormalNonLinuxRosterDecoder` validates the first 20 player slots and all path/
keyring framing before returning internal record-location descriptors. These
contain only profile identity and record position, never raw record bytes.
There is no second soldier traversal and no new public inspector offset API.

`NormalSoldierChecksum` extracts the existing project-authored checksum
calculation into one authority used by decoder and writer. It folds signed stat
pairs, then includes the unsigned profile ID and inventory item/count facts.
The recurrence, validated identities, vehicle exclusion and public read models
are unchanged.

## Transaction and authority

The [Issue #12 boundary](transactional-edit-safety.md) remains:

1. Check the 16 MiB source ceiling and fixed scalar request before proportional work.
2. Capture a bounded immutable source/request snapshot and hash it.
3. Require the private synthetic capability, its tighter bounds and exact supported
   normal non-Linux v103 / Build `04.12.02` identity.
4. Parse profiles, `inspectV01`, and `inspectLiveMercState`; establish membership
   and both expected-current preconditions using the shared validated traversal.
5. Build a private candidate with the fixed-capacity writer. Every copy/allocation
   remains bounded; candidate length is exactly source length.
6. Re-admit/reparse through both public inspection surfaces and profile parsing;
   apply all mandatory byte/model checks before provenance and success.

The Java editor's capability-bearing constructor, transaction and validation
continuation remain private. The JVM-sealed success interface permits only the
core-owned implementation with its private constructor. There is no public
capability factory, candidate callback or caller serialization authority.
Failure exposes bounded codes only; success snapshots bytes and returns copies.
Existing adversarial JVM authority tests remain intact. Deliberate test-only
`setAccessible(true)` is privileged instrumentation, not a public capability.

The existing inspector detector isolation is unchanged: parsing uses a private
snapshot, detection receives a separate snapshot, mutations/throwing detectors
fail admission, and raw exceptions never cross the result boundary.

## Exact serialization and mandatory verification

The profile primitive decrypts the validated target's exact 716-byte record,
sets offset 353, recomputes checksum bytes 696..699 and re-encrypts with a
per-record reset. The soldier primitive decrypts the validated target's exact
2328-byte record, sets offset 1377, recomputes checksum bytes 2208..2211 and
re-encrypts with the same reset semantics. Soldier profile identity at 1825 is
validated by the shared traversal; it is never edited.

Verification requires:

- Exact format identity, size and profile framing; identical ordered soldier
  identities and validated record positions.
- All 170 profile models equal except target marksmanship; all campaign facts
  equal; full ordered roster equality except target marksmanship.
- Both public read surfaces succeed with the same format, target identity and
  new value. All live stats and inventory item/count/role facts remain equal
  except target live marksmanship.
- Valid profile and soldier checksums, including mandatory parser checks.
- Exact profile plaintext equality except 353 and 696..699; exact soldier
  plaintext equality except 1377 and 2208..2211. This also preserves opaque
  inventory payload and all unrelated progress metadata.
- Exact bytes outside both encrypted records, including path/keyring tails and
  suffixes; unchanged ciphertext prefixes before 353 and 1377 respectively.
- Whole-file identity for no-op; source/candidate SHA-256, sizes and canonical
  request bindings before success construction.

The fixed report has 15 aggregate relations. Provenance uses synthetic capability
identity/revision 1, verification identity
`synchronized-hired-marksmanship-verification-v2`, and transaction model version
2. The canonical request remains three little-endian signed 32-bit integers
encoded as 24 lowercase hex characters. Provenance is deterministic and bounded:
no names, bytes, offsets, keys, arbitrary text, timestamps or raw diagnostics.

The source/candidate ceiling remains 16,777,216 bytes, with optional tighter
private capability bounds. Only two fixed record buffers are rewritten; there
is no generic serializer or unchecked whole-file intermediate. Parsing repeats
bounded recovery sequentially; the additional live and location reads increase
work over the profile-only proof. Peak-memory and real-save workload qualification
remain post-Draft gates, not claims inferred from synthetic success.

## Early Draft checks and remaining gates

Focused tests cover synchronized edits, no-op, 0/100 acceptance, -1/101 rejection,
unhired/vehicle/duplicate identities, profile/live disagreement, stale expected
values, both checksums, both public reparses, exact plaintext/two-record envelopes,
opaque inventory and path/keyring preservation, immutable source/output,
deterministic provenance, corrupt candidates, detector isolation, bounded
allocation, byte-free failures and closed JVM authority. Read-only roster/live
regressions are included. Fixtures remain project-authored synthetic material.

This run is for early Draft publication only. Hosted CI, independent review,
exact-head authorized private-save qualification, final candidate freeze, Ready
and merge occur after Draft. Production capability stays disabled pending that
separate exact-candidate qualification and a later bounded Draft repair. Neither
this engineering provenance nor synthetic tests satisfy licensing clearance or
production qualification.

### Construction validation (2026-09-27)

- All main/test Kotlin and Java sources compiled with cached Kotlin 2.4.10 and
  JDK 21; the two final changed source/test units were recompiled before the
  final focused run.
- Cached JUnit Platform/Jupiter ran **48 tests, 48 passed, zero skipped** across
  `Ja2MarksmanshipEditorTest`, `MarksmanshipAdmissionTest`, the unchanged
  `MarksmanshipJvmAuthorityTest`, `NormalNonLinuxRosterDecoderTest`,
  `NormalProfileWritePrimitivesTest`, and `LiveInventoryPresentationTest`.
- Gradle `:core:compileKotlin --offline --no-daemon` failed at startup with
  `Could not determine a usable wildcard IP for this machine`. No Gradle task
  success or full hosted CI qualification is claimed.
- Fresh validator materialization failed with `generator failed in its exact
  snapshot`. The local harness used existing cached synthetic header/profile
  test resources, checking their exact lengths and SHA-256 against the current
  verified manifest first (432 and 244921 bytes respectively). No generator,
  validator, fixture manifest, sandbox or test admission code was changed.
  This supplemental run does not qualify fresh fixture materialization; hosted
  CI with the unchanged validator pipeline remains authoritative.
- Text diffs, trailing whitespace and conflict markers were checked against
  pre-edit copies without accessing Git metadata. Commit/push and early Draft
  publication are left to the wrapper under this run's writer restrictions.
