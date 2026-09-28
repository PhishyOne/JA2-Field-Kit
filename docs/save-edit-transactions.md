# Issue #72: source-bound candidate transactions

## Construction boundary

`Ja2SaveEditor` establishes the in-memory transaction boundary on main
`120fdc9aeba70b3243c55bff7a2a89ae338533a1`. Its closed operation set currently
contains a typed synchronized hired-merc stat operation covering ten proven
non-health stats (see [evidence and domains](hired-stat-edit-evidence.md)), plus
closed clear/set inventory operations for three single kits in big pockets
(see [inventory evidence and guards](inventory-edit-evidence.md)). **Production capability
remains disabled.** The no-argument editor fails closed; only deliberately
privileged synthetic tests can inject a capability and synthetic digest oracle.
This slice does not claim real-save qualification or playable-product completion.

The older profile-only proof has been retired. `Ja2SaveEditor` is the sole
editing path; source binding and hired-player synchronization are mandatory.

[Issue #12](transactional-edit-safety.md) governs both candidate creation and
future placement. Core performs no filesystem, URI, process or network writes.
Android remains read-only. No private save was used in this construction.

## Request, identity and authority

```java
new SaveEditRequest(
    new SaveEditRequest.SourceIdentity(expectedSize, expectedSha256),
    new SaveEditRequest.SetHiredStat(profileId, HiredMercStat.MARKSMANSHIP, expectedCurrent, value)
)
```

The identity assertion must describe the exact source the caller inspected.
The transaction checks the 16 MiB source ceiling, then the fixed request shape,
then captures source bytes and recomputes size/SHA-256. A stale source fails even
when its target stat has not changed. Callers must not mutate their array during
capture; after capture, their array cannot affect the transaction. The private
snapshot is never mutated or exposed. Java records and a sealed operation set
make the admitted request transitively immutable, with no caller serializer,
raw offset, callback, variable collection or authority-bearing capability.

The Java-sealed result separates enum-only failure from a read-only verified
candidate. Its sole implementation has a JVM-private constructor owned by the
editor nest. Candidate getters return defensive copies; provenance and the
fixed passing-check list are immutable. No adapter or Kotlin helper can mint
success. Privileged reflection in tests is explicitly outside ordinary JVM
access checks, not a claim of protection against arbitrary agents in the JVM.

The operation set is deliberately closed, rather than a public extensible
serializer registry. Later stat or inventory operations need their own range,
precondition, checksum, preservation and mandatory verification rules. They can
reuse source admission, identity, candidate ownership, result and placement
boundaries. There is no unproven inventory mutation or generic byte-patch API.

## Stat operation and shared transaction

Synthetic capability admission requires `SUPPORTED`, normal non-Linux **v103**,
and Build **04.12.02** through the existing public detector/parser. Readable
v102 is explicitly refused for writes. Neither a caller-supplied identity nor a
synthetic capability bypasses normal source or candidate admission.

Both expected-current and requested values must satisfy the selected stat's
[evidenced domain](hired-stat-edit-evidence.md). Attribute injury counters must
be zero for the edited stat; equality alone does not prove an uninjured target. The target must
be exactly one current, non-vehicle player merc. The shared validated roster
traversal now also supplies internal framing descriptors, including vehicles so
an aliasing vehicle profile ID cannot make an edit ambiguous. Existing read
membership and checksum rules remain intact. Non-player, duplicate, missing,
vehicle-only, and profile/live disagreement cases fail closed.

Serialization sets the selected profile and soldier stat bytes, regenerates profile
checksum bytes 696..699 and soldier checksum bytes 2208..2211, and re-encrypts
those two fixed records (716 and 2328 bytes) with per-record transform reset.
The existing roster checksum verifier is retained independently of the new
writer recurrence. This is a direct set: training counters, deltas, inventory,
UI timing flags, path/keyring data and every other opaque byte are preserved.

Candidate creation is one transaction:

1. Bound request/source, snapshot, hash and compare expected source identity.
2. Require capability and its tighter bounds; inspect source, all profiles and
   live state through the public read surfaces; admit the unique target and
   both expected-current preconditions.
3. Apply the operation's fixed field changes and checksum updates in private records;
   serialize into the sole whole-candidate accumulator, sized before allocation.
4. Hash and re-admit/reparse the serialized candidate through `inspectV01`,
   `parseBuild041202NormalNonLinuxProfiles` and `inspectLiveMercState`.
5. Verify all mandatory relations, bind completed provenance, then construct
   verified success. Every failure exposes only fixed stage/reason codes.

The fixed 12-relation report covers exact format, size/framing and ordered
record locations, both requested values, all 170 profile facts, all campaign
facts, ordered roster facts, all live stats/inventory facts, both checksums,
plaintext envelopes, ciphertext preservation, no-op identity and hash bindings.
For stat edits, only the two stat bytes and their checksum fields may differ in plaintext.
Inventory edits use the four profile bytes and one complete 36-byte object
plus checksums detailed in the [inventory plan](inventory-edit-evidence.md).
Every byte outside the two encrypted suffixes must match, including all unrelated
inventory payloads, tails and suffixes. An identical-value request still
serializes and reparses and must reproduce the entire input byte for byte.

Provenance binds source and candidate size/SHA-256, exact format, immutable
request, canonical-request SHA-256, synthetic capability/revision, verification
plan and transaction model version. The canonical request is version 2, 60 bytes:
little-endian version, 32-byte source digest, source size, operation tag, stable stat tag,
profile ID, expected value and new value (all integers signed 32-bit).
No names, filenames, URIs, diagnostics or rotation bytes enter provenance.
These runtime hashes must not be published for private saves.

## Fixed resource contract

| Resource | Bound |
| --- | --- |
| Source / candidate | 16,777,216 bytes each; private capability can only tighten |
| Candidate layout | Exactly source length; two fixed record replacements |
| Operations | Exactly one, counted structurally; no collection or batch overload |
| Additional assertions / nesting | Inventory adds one fixed three-integer expected-slot record |
| Request components | Stat: four integers and a closed enum; inventory: at most nine integers; both have one exactly 64-character lowercase hex digest |
| Canonical request | Stat: 60 bytes (v2); inventory: 76 bytes (v3), before hashing |
| Plan / report | Exactly 12 aggregate relations, no dynamic expansion |
| Profiles / player slots / inventory slots | 170 / 20 / 19 per profile and player |
| Plaintext edit buffers | 716 + 2328 bytes; encrypted replacements have the same sizes |
| Provenance | Fixed records/enums/integers/digests, less than 2 KiB as explicit UTF-8 fields |

Malformed/oversized requests fail before source copying or hashing. Each writer
allocation/copy is checked against core, capability and exact predicted bounds;
overflow, underfill, reuse or bad ranges make it terminal. The fixed public
shape cannot encode duplicate operations, growing reports or unbounded values.

Recovery remains bounded by the existing maximum 43,520 record decryptions per
invocation. A successful transaction performs at most 16 recovery invocations
(696,320 record decryptions / 498,565,120 decrypted bytes cumulatively), processed
sequentially. Six public inspector calls each use at most two 16 MiB snapshots;
source, writer and success copies are separately bounded. This is an upper cost
bound, not a peak-heap or game-load qualification claim. Synthetic tests exercise
normal workloads and exact/over-limit 16 MiB admission; production memory and
latency qualification remains required before enabling a capability.

## Selective salvage from Draft #56

[Draft #56](https://github.com/PhishyOne/JA2-Field-Kit/pull/56), inspected at
`22f605918cb6383515c4d2c36575375a942f0129`, was not rebased, cherry-picked or
modified. Its useful concepts were independently integrated into current main:
shared validated soldier locations, hired-only membership, profile/live
precondition agreement, synchronized direct set, both checksum updates, and
public live-state reparse plus two-record preservation.

Its recorded interoperability audit supports the 0..100 direct-set domain and
profile/current relationship: Reborn revision
`743f38a6ca86c81893376c2576277db660320170`, Stracciatella revision
`8883ac43dc1b2b95a76286476f690526bc858565`, Campaign.h blob
`d06776b8a48f39ededbca1ce67bda86e91ea94a0`, Campaign.cc blob
`38ff561e23cfc3cc043cb749146a167cc63c6a1b`, Soldier_Create.cc blob
`ae33325c12bb3da7aa07869f88da5dd8c2a40ff9`, and Soldier_Profile.cc blob
`9d70349155cc86377fd4f146cd08622805f2e2e4`.
This carries forward the recorded audit, not new upstream or private-save
qualification. No upstream implementation or rotation tables were copied.
The [licensing boundary review](licensing-boundary-review.md) remains a pre-merge gate.

## Android Save As deferred

The authoritative placement contract requires private staging, verified bytes,
continuous artifact protection, atomic publication to an absent destination,
post-publication verification, and qualified durability/uncertainty handling.
An `ACTION_CREATE_DOCUMENT` result alone does not establish those guarantees
for arbitrary document providers. This slice stops before Android UI or output
plumbing rather than adding an unqualified direct stream to a final URI.
Production enablement, a qualified placement adapter and authorized local
real-save/game-load evidence remain follow-up work. Draft publication is not
candidate completion.

## Initial transaction validation (start HEAD, 2026-09-28)

Using JDK 21, cached Gradle 9.5.0 and the pinned fixture-validator environment:

- `gradle :core:test --tests '*SaveEditTransactionTest' --tests '*SaveEditAuthorityTest' --offline --no-daemon`:
  **14 passed**, zero failed/skipped (11 transaction, 3 JVM authority/writer tests).
- `gradle :core:test --offline --no-daemon`: **162 passed** across 22 test classes,
  zero failed/errors/skipped, including the retained profile-only proof and
  read-only parser regressions.
- `python -B -m unittest discover -s tools/tests -v`: **69 passed**.
- Fresh header/profile resources materialized successfully through the unchanged
  validator/Bubblewrap pipeline during the Gradle runs; no cached-fixture fallback.
- `git diff --check`: passed. No Android files changed, so Android unit/lint/
  assemble gates were not required for this core-only slice.

The restricted execution sandbox blocked Gradle's local socket and the policy
suite's namespace setup. The successful runs used the authorized host execution
path, with fixture isolation and all admission checks unchanged. No production
capability, real-save or placement qualification is inferred from these results.

## Multi-stat continuation

See [hired-stat evidence](hired-stat-edit-evidence.md) for this run's semantic
reassessment, API consolidation, offset audit and validation results.
