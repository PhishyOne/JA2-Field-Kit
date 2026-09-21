# Transactional save-editing safety model

## Status and scope

This document is the Issue #12 design gate. It specifies the safety contract a
future editor must satisfy; it does not authorize or implement save editing.
There is still no public editor, serializer, writer, export path, inventory
schema, Android write flow, or PC bridge.

The current public core boundary remains the read-only
`Ja2SaveInspector.inspectV01(ByteArray)` facade described in
[architecture.md](architecture.md). Its `SUPPORTED` detector result means only
that the current read path may inspect the evidenced layout. Read support does
not imply edit support. Producer family also remains unknown where the detector
cannot prove it. That facade currently copies an arbitrary caller-owned
`ByteArray` before inspection; the future-editor resource contract below does
not change or harden that existing behavior.

Issue #10 owns the broader import and desktop-return workflow. Issue #13 owns
the local PC bridge protocol. Neither is part of this design. Core remains a
plain Kotlin/JVM module with no Android dependency.

## Safety rule and authority

Every future edit transaction must perform this sequence:

```text
read original
  -> reject source above the core editor byte ceiling
  -> snapshot source and establish runtime-observable identity
  -> apply any tighter capability source bound
  -> admit exact format/version for editing
  -> parse source snapshot
  -> apply typed logical changes in memory
  -> serialize through a bounded transaction-private candidate writer
  -> admit and reparse candidate bytes
  -> verify requested changes and critical unchanged facts
  -> produce a verified candidate
```

The core transaction is the sole authority allowed to construct a successful
edit result. Format adapters may parse, mutate a private working model,
serialize, and supply format-specific verification facts, but they cannot mark
a candidate verified. A UI, command, output adapter, or test cannot skip,
replace, or weaken core verification.

Candidate generation is not success. A candidate remains private transaction
state until every check has passed. Any admission, parse, mutation,
serialization, reparse, integrity, provenance, or verification failure fails
the whole transaction. Failure must not expose candidate bytes through a
success-shaped value, fallback result, callback, temporary output, or public
exception payload.

## State machine

```text
RECEIVED -- source above 16 MiB -----------------------------> FAILED
  | constant-time source byte-count check passes; no copy or proportional work
  v
CORE_SIZE_ADMITTED
  | snapshot bytes; compute SHA-256; establish observable identity
  v
IDENTITY_ESTABLISHED
  +-- no exact edit capability / tighter source bound fails -> FAILED
  | exact edit capability and tighter source bound pass
  v
SOURCE_ADMITTED
  | parse immutable source snapshot
  v
SOURCE_PARSED ------ parse/integrity failure ----------------> FAILED
  | capture logical baseline, critical facts, and preservation map
  v
MUTATED_IN_MEMORY -- invalid/unsupported/conflicting request -> FAILED
  | serialize through strictest-bound transaction-private writer
  +-- bound/serialization/layout failure --------------------> FAILED
  v
CANDIDATE_SERIALIZED
  | compute candidate size and SHA-256
  v
CANDIDATE_REPARSED -- admission/parse/integrity failure -----> FAILED
  | mandatory verifier evaluates the fixed plan
  v
VERIFIED ----------- any mismatch ---------------------------> FAILED
  |
  v
VERIFIED_CANDIDATE
```

`FAILED` and `VERIFIED_CANDIDATE` are terminal. There is no transition from a
failed transaction back to success using the same candidate. Retrying starts a
new transaction from source bytes. Destination placement is deliberately not a
state in this machine.

## Future public editor contract

The following describes type responsibilities, not proposed Kotlin API names
or an implementation commitment.

### Source admission

The editor accepts a byte snapshot plus a typed logical edit request set. It
has an initial core-owned hard source and candidate ceiling of **16 MiB
(16,777,216 bytes)**. Before copying or taking ownership, hashing, parsing,
format detection or admission, or performing any other work proportional to
the source length, the transaction must read the `ByteArray` length and reject
a source above that ceiling. This cannot recover memory already allocated by an
external caller, but it prevents the editor from duplicating or processing an
oversized source. Only after this constant-time check may the editor make its
immutable source snapshot and compute SHA-256 itself. Caller-supplied hashes and
format labels are assertions to check, never authority.

The 16 MiB ceiling is part of the future core editor contract. Core owns and
enforces it without an Android dependency. Android's current
`BoundedSaveReader` independently accepts and retains payloads up to the same
size, which is supporting product alignment rather than authority for core.
This design does not change `BoundedSaveReader` or the current read-only
`Ja2SaveInspector` behavior. Raising the core ceiling requires a separately
reviewed contract change; no adapter, capability, caller, or platform layer may
raise it.

After enough bounded work has established runtime-observable serialized
identity, the matching format/edit capability may impose a tighter maximum on
source and candidate bytes. The transaction must enforce that tighter source
maximum before format/edit admission or full parsing. A capability cannot use a
producer assertion or other non-observable label to select the limit.

Byte-size admission and format/edit admission are distinct gates. Passing the
core and capability byte limits only permits further admission work; it does
not establish that the source is valid, supported, or editable. Any source or
candidate byte-limit refusal is a terminal bounded failure; it cannot produce
or expose candidate bytes as a successful result.

Admission requires an exact edit capability keyed by all runtime-observable
serialized identity that can affect serialization, including layout and save
version/build identity. Producer labels may be retained as provenance or
context, but they are assertions to check rather than enforcement authority
when the runtime cannot distinguish them from the bytes. That capability must
have passed the no-op gate below. Unknown, candidate, truncated, inconsistent,
read-only-supported, or otherwise unproven variants are rejected before
mutation. A current read detector returning `SUPPORTED` is necessary where
applicable but is not sufficient.

The admitted source is parsed once into an immutable logical baseline and a
private format representation capable of preserving uninterpreted bytes. The
transaction records the admitted identity rather than trusting that a later
candidate has the same identity.

### Typed request and in-memory mutation

An edit request set is a closed collection of typed logical operations defined
by an admitted format capability. It identifies the intended logical target,
old-value precondition where needed, and requested new value. Arbitrary byte
patches, raw offsets, untyped field paths, and caller-provided serializers are
outside the public editor contract.

The transaction rejects unsupported operations, invalid values, contradictory
requests, unmet preconditions, and requests whose complete verification cannot
be expressed. Changes apply only to a private in-memory working model derived
from the source snapshot. Source bytes and caller-owned arrays are never
mutated.

### Candidate serialization and mandatory reparse

Serialization writes only to a bounded, transaction-private sink or writer,
never to the source or a destination. That writer is the sole candidate byte
accumulator; a serializer cannot first construct an unbounded candidate or
intermediate candidate buffer and then pass it to the writer. Before accepting
each growth operation, the writer must use overflow-safe accounting and refuse
any write that would exceed the strictest applicable bound:

- the core hard ceiling of 16 MiB;
- the admitted capability's tighter candidate maximum, when present; and
- the exact or predicted candidate size/layout bound for this edit request.

The bound check must occur before growing or copying into the candidate buffer;
serializing first and rejecting the completed array is insufficient. The format
capability must preserve all required opaque regions and report the layout
effects it expected from this exact edit set. A bound refusal, serialization
error, predicted-size mismatch, or incomplete preservation proof is terminal.
No partially serialized candidate may escape through a callback, exception, or
failure value.

The candidate is hashed, passed through the normal fail-closed format admission
path, and parsed from its serialized bytes. Verification must use this reparsed
candidate model, not the pre-serialization working model. Candidate format
identity must equal the admitted source identity unless the capability and the
request explicitly support a separately proven conversion; format conversion
is not authorized by this issue.

### Verification plan and report

The core constructs the verification plan before mutation from three sources:

1. Every typed edit operation contributes mandatory requested-change
   assertions, including target identity and expected post-edit value.
2. The exact format capability contributes mandatory critical-unchanged
   assertions and structural/integrity checks.
3. A caller or test may add typed unchanged-field assertions for logical facts
   it cares about.

Caller assertions are additive. No caller, adapter, or UI may remove requested
change checks or format-mandated unchanged checks. At minimum, the capability's
critical set must cover format identity and all parsed campaign facts that the
request does not intentionally change. It must also cover roster membership and
identity, and any other parsed state whose silent drift could corrupt or alter
campaign meaning. As model coverage grows, each format capability must extend
this set explicitly; an empty or unavailable critical set is not editable.

The report records each assertion's typed identity, expected value or relation,
observed result, and pass/fail status, plus integrity, preservation, and
size/layout checks. Values included in a public report must follow the existing
privacy boundary; internal verification may compare private logical values
without publishing them. Every mandatory check must pass. Missing, unevaluable,
duplicate-ambiguous, or contradictory assertions fail closed.

### Result type and provenance

The future public result must be a closed sum with two non-interchangeable
outcomes:

- A verified-candidate outcome is the only outcome that can contain candidate
  bytes. Its constructor is private to the core verifier. It contains immutable
  candidate bytes and completed provenance.
- A failure outcome contains a bounded failure stage and reason. It contains no
  candidate bytes and cannot be promoted to success by a cast, flag, nullable
  report, or caller-supplied boolean.

Successful provenance binds, in one immutable record:

- source SHA-256 and byte size;
- exact admitted source format identity and edit-capability revision;
- a canonical, order-stable representation or digest of the typed edit request
  set, without silently dropping duplicate requests;
- candidate SHA-256 and byte size;
- verification-plan identity;
- the complete verification result, which must be passing; and
- the transaction/model version needed to interpret the record.

The core recomputes hashes over the exact source and candidate snapshots and
checks all internal references before constructing success. A provenance/hash
mismatch is a transaction failure. Provenance describes the source-to-candidate
derivation; it is not proof that a later destination still contains those
bytes.

## Format enablement gate

Editing is disabled per exact format/version until all of the following are
reviewed and tested for that capability:

1. Read admission and complete parsing of every fact required for mutation,
   integrity, and verification are reliable.
2. A no-op serialize/reparse round trip succeeds on admitted evidence.
3. Unknown-byte preservation and layout accounting are proven for the regions
   the serializer can affect.
4. Format integrity rules can be regenerated and independently verified after
   reparse.
5. Critical unchanged facts and every enabled edit operation have complete,
   typed verification rules.

The default no-op requirement is byte identity:

```text
serialize(parse(source), no changes) == source
```

It also requires equal SHA-256, equal size/layout accounting, successful
candidate admission and reparse, equality of all parsed logical facts, and
passing integrity checks. A format may define a narrower no-op exception only
when authoritative evidence proves that particular known bytes must be
regenerated non-deterministically or canonically. Such an exception must name
the exact byte regions and allowed relation, preserve every opaque byte, be
independent of private fixture values, and have positive and negative tests.
An undocumented or unexplained no-op mismatch keeps editing disabled.

A capability is enabled only for the exact runtime-observable serialized
identities covered by its evidence. Producer claims that are
byte-indistinguishable under the current admission and detection evidence form
one edit-capability domain; evidence must justify editing across the whole
indistinguishable set that the runtime would admit under that capability.
Evidence for one version, platform layout, or other runtime-distinguishable
identity does not enable a neighboring variant. If evidence cannot justify the
whole indistinguishable domain, editing remains disabled until a reliable
producer discriminator or a narrower observable serialized identity is
established.

## Unknown-byte preservation

The preservation map partitions the admitted source into understood fields,
format-defined regenerated integrity fields, and opaque regions. Opaque bytes
must be copied exactly from the source snapshot and checked against the
candidate. Known but untouched fields must retain their logical value and,
unless a documented format rule requires regeneration, their serialized bytes.

Preservation is not proven merely because the candidate parses or has the same
length. It cannot be claimed when framing is ambiguous, a region's boundaries
are unknown, an edit can shift or reinterpret an opaque region without an
evidenced relocation rule, an integrity dependency is unknown, or the serializer
normalizes bytes beyond a reviewed exception. In any of those cases, edits that
could affect the region are refused. The safer response is a narrower edit
capability, not best-effort rewriting.

## Size and layout drift

The default policy requires candidate size, section boundaries, record counts,
and all other format-defined layout measurements to equal the source. A parser
accepting both files does not make drift safe.

A per-format capability may declare an exception only for a specific typed edit
operation. The exception must define the exact affected region, expected size
delta or boundary transformation, limits, relocation/alignment rules, integrity
updates, and preservation checks for shifted opaque bytes. At serialization and
again after reparse, observed layout must match that prediction exactly. Any
unaccounted byte, shifted boundary, changed count, trailing-byte change, or
candidate-size difference fails the transaction.

These layout rules also supply a candidate-writer bound. If an exact candidate
size is known, it is the bound. If the reviewed rule permits a range or maximum,
its predicted maximum is the bound. This request-specific bound can be stricter
than the generic capability maximum and can never relax either the capability
maximum or the core hard ceiling.

No generic "allow resize" or caller-controlled bypass is permitted.

## Output placement and later replacement

Core transaction success means only that verified candidate bytes and their
provenance exist. It does not mean a file was created, exported, transferred,
or installed. Output placement is a separate adapter concern and consumes only
a verified-candidate result.

### Create-new output

The default destination operation, when separately designed, is create-new
output. It must use create-without-overwrite semantics, not an
exists-check-then-write sequence, and must verify the placed bytes against the
verified candidate. An existing destination fails that operation; it does not
implicitly authorize replacement.

### Qualified replacement

Replacement of an expected original is a later, explicit adapter operation,
separate from core edit success. It requires explicit replacement intent, an
exact destination, and a continuous qualified guard from the final
expected-original comparison through commit, post-commit verification, and
outcome recording. The guard must cover relevant competing writers and protect
both destination content and its name/object binding as required by the
platform. Advisory app-only locking is insufficient unless every relevant
writer is proven cooperative. Process-list checks and user confirmation do not
provide continuous exclusion.

If the adapter cannot provide qualified guarded replacement, replacement is
unsupported and fails closed. A separate create-new/Save As operation may remain
available. Java `FileLock`, atomic move/rename, or a pre-commit recheck alone
does not establish generic portable replacement safety. No particular OS/API
primitive is qualified or implemented here; platform-specific adapter
qualification requires later evidence.

The required replacement order is:

1. Receive the core's verified candidate and locally held provenance.
2. Prepare private staging with the exact candidate bytes and verify its size
   and hash against that provenance.
3. Acquire the qualified guard.
4. Compare the current original with the expected state using locally held
   provenance plus platform-appropriate identity/content evidence sufficient
   for that adapter, including the provenance source size and hash. A path,
   display name, timestamp, or remote assertion alone is not overwrite
   authority. Refuse missing evidence, a changed original, or an unexpected
   destination object even at the same name.
5. Preserve and verify an independent backup of the original before any
   destination mutation. It must independently preserve the original bytes;
   another name or handle for the same mutable object is not enough.
6. Atomically commit the staged candidate while the guard remains effective.
   Atomic commit is mandatory for supported replacement; no destructive
   fallback sequence is permitted. The guard cannot be released merely to
   make the commit API work. If commit cannot occur under the required
   protection, the adapter does not qualify.
7. Verify the installed result against the candidate, its destination binding,
   and the adapter's required durability guarantees while still guarded.
8. Record the outcome and recovery evidence while still guarded.
9. Release the guard.

### Failure and recovery

Before the first destination mutation, rejection must leave the destination
untouched. An uncertain post-commit outcome, including failure to verify the
installed result, durability, or outcome recording, must preserve the original
backup, remaining staging, and evidence and report uncertainty. It must not
trigger a blind retry or restore.

Restoration is itself a replacement: it requires the same qualified guard and
proof that the current destination is the exact state authorized for
replacement, with atomic commit, verification, and outcome recording under that
guard. Backup existence alone is not overwrite authority. After a crash or lost
guard, reacquire a qualified guard and reconcile the actual destination with
retained evidence before any further mutation. If authority or the actual
outcome cannot be established, refuse mutation and preserve competing data and
recovery evidence.

A source-conflict failure does not invalidate the already verified in-memory
candidate, but it must fail that replacement attempt and must never silently
overwrite the changed source. Android document placement, desktop return, and
bridge transport require their own later designs under Issues #10 and #13.
The future Issue #13 PC bridge resolves destinations locally; a remote path or
assertion cannot authorize local replacement. This gate adds no bridge or
runtime output implementation.

## Test obligations

Before any format/version is editable, public synthetic tests and authorized
local evidence under [fixture-policy.md](fixture-policy.md) must exercise the
same public transaction boundary. Tests must be able to declare typed edit
requests, expected requested changes, and explicit unchanged-field assertions.
They must prove that core-added critical assertions remain mandatory and that
only the verified outcome exposes bytes.

Required positive coverage includes no-op round trips, each enabled edit kind,
all mandatory unchanged facts, opaque-byte preservation, integrity regeneration,
deterministic provenance, and every declared size/layout exception.

Required resource-boundary coverage includes:

- a source exactly at the 16 MiB core limit proceeding to further admission,
  subject to independent format validity;
- a source one byte over the core limit being rejected before snapshot copying,
  hashing, parsing, detection, or other proportional editor work;
- a capability-specific tighter source and candidate bound;
- a candidate writer accepting an exact-limit candidate;
- a candidate writer refusing an attempted one-byte overflow before growth;
- a predicted layout/size bound stricter than the generic capability bound; and
- every oversize failure being terminal and exposing no candidate success
  bytes.

Additional required negative coverage includes:

- unsupported or merely read-supported format/version;
- source parse or integrity failure;
- serialization or bounded-writer failure;
- candidate admission or reparse failure;
- requested-change mismatch;
- critical or caller-requested unchanged-field drift;
- source/candidate hash or provenance mismatch;
- unexpected size, boundary, record-count, or opaque-byte drift; and
- no-op byte, logical, integrity, or preservation mismatch.

Each injected failure must demonstrate a terminal failure result with no
candidate bytes exposed as a successful product. Private real save bytes remain
local-only and must never enter the repository, logs, test output, caches, or
artifacts.

### Future output-adapter evidence

Placement evidence is separate from core transaction tests: a replacement
refusal does not invalidate the already verified candidate or imply placement
success. Create-new evidence must prove create-without-overwrite behavior when
a competing creator claims the destination and verification of placed bytes.

Before a replacement adapter is supported, its platform-specific qualification
must exercise:

- a competing writer before the final expected-original comparison;
- a competing writer in the comparison-to-commit window;
- guard bypass, alternate handles, or namespace substitution as applicable;
- a same-length content change;
- destination object replacement at the same name;
- a competing Field Kit instance;
- a crash or uncertain outcome around commit, including reconciliation after
  guard loss; and
- a competing writer before restoration.

Evidence must establish continuous guard coverage through atomic commit,
post-commit verification, and outcome recording, independent preservation of
original backup bytes, and guarded restoration only from an authorized current
state. The required outcome is either a protected commit or fail-closed refusal
preserving competing data, never silent lost progress. Uncertain outcomes must
retain recovery evidence and remain reported as uncertain until reconciled.

## Design gate

This document defines conditions for a future implementation review. It neither
declares the currently detected layout editable nor grants permission to add a
serializer, editor API, output adapter, Android permission, manifest entry,
dependency, fixture, bridge, or runtime behavior. Those changes require
separate scoped work after format-specific evidence satisfies this gate.
