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
  -> structurally admit requests/assertions into an immutable bounded snapshot
  -> snapshot source and establish runtime-observable identity
  -> apply any tighter capability source bound
  -> admit exact format/version for editing
  -> parse source snapshot
  -> semantically admit typed requests and bound/build the verification plan
  -> apply typed logical changes in memory
  -> serialize through a bounded transaction-private candidate writer
  -> admit and reparse candidate bytes
  -> verify requested changes and critical unchanged facts
  -> complete bounded provenance and produce a verified candidate
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
  | bounded structural request/assertion admission and immutable snapshot
  +-- missing required limit / structural budget failure ----> FAILED
  v
REQUEST_STRUCTURE_ADMITTED
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
  | semantically admit operations, ranges, preconditions, and conflicts
  +-- invalid/unsupported/conflicting request ---------------> FAILED
  | bound plan expansion before construction; build fixed verification plan
  +-- plan budget / complete-verification failure -----------> FAILED
  v
REQUEST_AND_PLAN_ADMITTED
  | apply typed logical changes to private working model
  v
MUTATED_IN_MEMORY -- mutation failure -----------------------> FAILED
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
  | complete bounded report/provenance; check internal references
  +-- budget / provenance failure ---------------------------> FAILED
  v
VERIFIED_CANDIDATE
```

`FAILED` and `VERIFIED_CANDIDATE` are terminal. There is no transition from a
failed transaction back to success using the same candidate. Retrying starts a
new transaction from source bytes. Destination placement is deliberately not a
state in this machine.

Any resource-budget failure at any stage is terminal, including report growth
during verification. The structural gate precedes caller-proportional editor
work other than the bounded admission traversal defined below.

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

### Structural request admission

Core owns structural request admission alongside source-byte admission. The
16 MiB source/candidate ceiling does not bound edit collections, caller-added
assertions, typed values, canonicalization, plans, reports, or provenance.
Before bulk copying caller collections or values, hashing/canonicalization,
verification-plan construction, mutation, execution, or other work proportional
to caller-controlled input, core must structurally admit the input under finite
core-owned limits. Required resource limits cover:

- raw edit operation count and caller-added assertion count, counting every
  duplicate before deduplication;
- every variable-sized typed component, including target identifiers,
  preconditions, requested and expected values, and strings, byte values, or
  collections if future types permit them; nested collection count/depth and
  numeric precision must also be bounded where applicable;
- aggregate admitted-input size and canonical-representation size; and
- the total expanded verification plan and verification-report/provenance
  sizes, including mandatory capability checks and observed-value
  representations.

Admission itself must be bounded. Use trustworthy constant-time lengths where
available, otherwise bounded traversal with early termination; never
materialize an unlimited collection, string, or encoding merely to measure it.
Use overflow-safe accounting before allocation or growth. Produce an immutable
admitted input snapshot whose exact contents satisfy every limit: bounded
incremental capture may occur only after checking the applicable component and
aggregate budgets. A check followed by an unchecked copy of mutable caller data
does not qualify. Later caller mutation must not alter admitted inputs or bypass
checks; all subsequent work uses the admitted snapshot.

Structural admission must bound canonical size by bounded measurement or a safe
upper bound before encoding or hashing. After capability selection and parsing,
the total expansion budgets must also be enforced before constructing the plan
or allocating report/provenance growth, as specified below.

The future public editor implementation contract must fix concrete finite
values for every required limit before any edit capability may be enabled.
Values must be justified by supported workloads, worst-case expansion, and
memory/CPU evidence, including adversarial processing cost. This design does
not invent numeric operation/assertion/value budgets. Any unspecified required
limit keeps editing disabled. Core owns and enforces these limits; callers and
adapters cannot raise them, and capabilities may only tighten them. The existing
16 MiB source/candidate hard ceiling remains unchanged.

Structural admission is not semantic edit admission. After observable identity
and capability selection and necessary parsing, core enforces any tighter
capability limits and the capability's supported operations, logical ranges,
preconditions, conflicts, and ability to verify the complete edit before
mutation. Passing structural limits never authorizes an edit.

Duplicates count before deduplication. Ambiguous or contradictory duplicates
fail closed; accepted duplicates must remain represented in canonical
provenance, including their multiplicity, even if execution can safely coalesce
them. Canonical hashing does not waive size or computation limits. Any budget
failure terminates the transaction with bounded diagnostics and no candidate
success; it must not drop mandatory checks or truncate success evidence.

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

After structural and semantic request admission, core bounds the total expanded
verification plan before constructing it and before mutation. It includes three
sources:

1. Every typed edit operation contributes mandatory requested-change
   assertions, including target identity and expected post-edit value.
2. The exact format capability contributes mandatory critical-unchanged
   assertions and structural/integrity checks.
3. A caller or test may add typed unchanged-field assertions for logical facts
   it cares about.

Expansion accounting includes all three sources, even checks not supplied by
the caller. Report and provenance growth, including expected/observed value
representations and canonical encodings, must be bounded before allocation.
Use overflow-safe accounting throughout construction and execution; inability
to fit the complete plan or evidence is a terminal failure, never permission to
omit a check or shorten evidence into a successful result.

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
6. The public editor contract fixes all finite structural resource limits, with
   workload, worst-case expansion, and memory/CPU evidence; core enforcement
   and any tighter capability limits pass the resource-admission tests below.

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
output (including Save As). It consumes only a verified candidate; core
candidate creation remains completely separate from placement. The adapter must:

1. Write the exact candidate bytes to **private staging** in a location/domain
   that supports the qualified final publication semantics.
2. Verify staged byte size and SHA-256 against candidate provenance before
   publication. Protect the staged object from modification between this
   verification and publication.
3. Atomically bind the complete staged object to an **absent destination**, using
   qualified no-replace publication or a proven platform-equivalent operation.
   Refuse an existing or concurrently-created destination without changing it;
   neither case implicitly authorizes replacement.
4. After atomic publication, verify published bytes against candidate size/hash
   and verify the destination name/object binding. Establish the adapter's
   required data and namespace durability and record the outcome before
   reporting placement success.

No direct streaming into the final destination, exists-check-then-write,
overwrite-capable fallback, partial copy into the final name, copy/delete
fallback, or destructive fallback qualifies. Qualification must address actual
filesystem/provider constraints, including whether staging and final destination
must share a filesystem or transactional provider domain. Cross-filesystem or
cross-provider transfer must stage in the destination domain or prove equivalent
semantics; a generic "move" is insufficient. If atomic no-replace publication or
a platform-equivalent operation cannot be proven, create-new/Save As is
**unsupported and fails closed**. No particular OS/API primitive is qualified
here.

Atomic visibility and durability are separate requirements. Each adapter must
define its supported interruption/crash model and the required data plus
namespace durability guarantees, including the ordering of persistence steps
around publication. Atomic publication alone is not proof of crash durability.

Before publication, failure leaves the final destination absent or untouched by
this operation and may clean only staging proven to belong to this operation.
Once publication may have occurred, failed verification, durability, or outcome
recording is an **uncertain placement** outcome. Preserve available staging and
recovery evidence and reconcile the actual destination before any further
mutation. No blind retry, deletion, overwrite, or cleanup of a possibly competing
final object is allowed. Uncertainty must remain reported until reconciliation
establishes the actual outcome; verified-candidate success does not resolve it.

Create-new does not replace an expected original. Replacement's expected-original
comparison, continuous qualified guard, independent backup, and guarded
restoration remain separate requirements below.

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
available only if it independently meets the create-new qualification above.
Java `FileLock`, atomic move/rename, or a pre-commit recheck alone
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

### Replacement failure and recovery

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

Required structural request/assertion resource coverage includes:

- exact-limit and one-over-limit raw operation counts;
- exact-limit and over-limit caller-added assertion counts;
- oversized individual variable values and nested components, including
  collection count/depth and numeric precision where applicable;
- aggregate admitted-input and canonical-encoding budget overflow;
- total verification-plan expansion overflow, including mandatory capability
  checks, and report/provenance expansion overflow, including observed values;
- duplicate and contradictory inputs counting before deduplication, with
  accepted duplicates retained in canonical provenance;
- mutable caller inputs during admission and after snapshot capture;
- overflow-safe accounting before every allocation/growth boundary; and
- adversarial processing-cost cases, including bounded admission traversal and
  canonicalization.

Instrumentation must prove refusal before forbidden copying, allocation,
hashing/canonicalization, plan construction, or other proportional work. Tests
must also prove bounded failure diagnostics, no omitted mandatory checks or
truncated success evidence on budget failure, and disabled editing when any
required implementation-contract limit is unspecified.

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

Placement evidence is separate from core transaction tests: a placement
refusal does not invalidate the already verified candidate or imply placement
success. Before a create-new adapter is supported, platform-specific evidence
must exercise:

- an existing destination and a concurrent creator;
- namespace substitution as applicable, including destination binding checks;
- staged-byte tampering between verification and publication;
- concurrent reader observations, which must never expose a partial final file;
- interruption/crash throughout staging and publication under the declared model;
- publication succeeding but verification, durability, or outcome recording
  failing;
- required data and namespace durability, as applicable;
- orphan staging recovery/cleanup with proof of operation ownership;
- unsupported filesystems/providers and cross-domain publication attempts.

The required result is a complete published candidate or an untouched existing
or absent final destination, preserving any competing object's data. After an
ambiguous publication outcome, preserve uncertainty and recovery evidence until
the actual destination is reconciled; tests must reject blind retry and unsafe
cleanup. Platform qualification must establish both visibility and the declared
durability guarantees, not infer one from the other.

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
