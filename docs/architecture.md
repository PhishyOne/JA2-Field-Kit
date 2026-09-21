# Architecture

## Principle

The parser/editor is the product core. Android is a presentation and file-access layer on top of it.

For v0.1, `core` must remain a plain Kotlin/JVM module with no Android SDK dependency. That keeps parsing testable on CI and reusable by future desktop/CLI tooling if useful.

## Read-only data flow

```text
save bytes
  -> structural probe
  -> format/version detector
  -> format adapter
  -> decryption/block readers
  -> immutable logical model
  -> Android UI
```

The current core implements the non-destructive structural probe, an
[evidence-based fail-closed detector](save-family-detection.md), general
little-endian primitives, a narrow parser for the evidenced v103 /
`Build 04.12.02` normal header, and the source-derived normal encryption
selector and block transform. The bounded normal non-Linux path can also frame
the encrypted profile table, recover its per-save rotation, decrypt each record,
and return the minimal immutable profile model. Detection requires header,
selector, framing, bounded profile-consistency evidence, and an independently
admitted digest for the exact selected rotation index; file length or header
identity alone does not establish support. Producer family remains independent
and currently unknown. Production rotation tables are not bundled; non-secret
digests for indexes 124 and 139 are admitted from the same pinned upstream
commit/blob, and recovered rotation data is scoped to one inspection. Every
layout-specific interpretation method on the public inspector enforces that
same detector result before returning parsed data.
The presentation boundary is `Ja2SaveInspector.inspectV01`: it snapshots the
input, detects once, and returns a sealed sanitized success/failure result. Its
models contain logical format facts, a minimal campaign summary, and roster
stats only; structural offsets and cryptographic internals remain below the
boundary.

## Planned boundaries

### `core.io`

Bounds-checked binary primitives and, later, seekable save input abstractions. No JA2 game semantics.

### `core.format`

Known layout facts, format/version compatibility, encryption selection, and
adapters only where byte evidence establishes an actual divergence.

A stable adapter contract exposes logical parsing, not raw structure details:

```text
inspectV01 -> success(format + campaign + roster) | failure(format + bounded codes)
```

Do not create separate adapters until fixture evidence shows the formats actually diverge.

### `core.model`

Immutable logical data consumed by UI: campaign summary, roster, merc stats, and later condition/inventory/economy models.

The current `MercProfile` model is an exact 170-entry profile-table view. It is
not a roster model and carries no inferred hired/player-membership state.

### Android `android-app`

Owns Android Storage Access Framework integration, the document picker,
content-URI reads, presentation mapping, and the screen. Its narrow importer
collects a sanitized display name, source category, optional provider size, and
optional provider last-modified timestamp, then accepts and retains a maximum
complete payload of 16 MiB while counting and SHA-256 hashing the exact bytes.
An oversized rejected stream may be transiently read beyond that boundary by up
to one current 64 KiB buffer read before throwing `SaveTooLargeException` and is
not retained as an accepted save. Provider-declared size is advisory:
it cannot reject readable content or authorize bytes beyond that measured limit.
Only a private, task-local byte snapshot enters `Ja2SaveInspector.inspectV01`;
URI and provider details never enter core. Presentation retains import provenance
(including lowercase SHA-256 for integrity diagnostics), but no save bytes, raw
URI/path, provider-private identifier, offsets, rotation indexes, keys,
ciphertext, or parser exceptions.

Local/Downloads, USB-backed, and cloud-backed documents all use Storage Access
Framework document providers. `ACTION_OPEN_DOCUMENT`, `ACTION_VIEW`, and
`ACTION_SEND` converge on this same importer; there is no filesystem scanning,
provider SDK, persistent URI grant, account access, or automatic upload.

The current shell has no permissions, persistence, network, analytics, save
write/export, or URI-to-filesystem-path code. It can serialize an explicit,
privacy-bounded compatibility report from safe retained provenance and
`inspectV01` failure presentation, then preview and copy it or share only its
text through Android's chooser. Save sharing/contribution remains future scope.

## Failure policy

- Unknown version: return unsupported/unknown, not guessed data.
- Truncated block: stop parsing that logical result and report a structured error.
- Invalid stat/range: preserve evidence for diagnostics; do not silently clamp during parsing.
- Editing (future): enforce core-owned source-byte and structural
  request/assertion admission before bulk copying or proportional work, using
  bounded admission to capture immutable inputs. Establish observable identity,
  enforce tighter capability limits, parse the admitted source, semantically
  admit requests, bound and build the complete verification plan, apply logical
  changes in memory, serialize through a bounded transaction-private writer,
  re-admit and reparse the candidate, and verify requested changes plus critical
  unchanged facts before producing a verified candidate. The initial hard
  source/candidate ceiling is 16 MiB; a format capability or predicted layout
  may tighten but never raise it. Core must also enforce finite counts, typed
  component and aggregate input/canonical budgets, and expanded plan and
  report/provenance budgets. Duplicates count before deduplication; admission
  does not grant semantic edit approval. Required limits must have concrete,
  evidence-backed values in the future public editor contract before any edit
  capability is enabled; callers/adapters cannot raise them.
  A separate output-placement operation may then create a new file by default,
  using protected private staging verified against candidate provenance,
  qualified atomic no-replace publication of the complete object, and subsequent
  byte/binding verification plus required data/namespace durability. Unsupported
  publication semantics fail closed; cross-domain transfer requires destination
  staging or proven equivalent semantics. Ambiguous publication preserves
  uncertainty and recovery evidence until reconciliation before further mutation.
- Never overwrite the original save by default.
- Replacement remains a separate, explicit later operation, supported only
  with immutable core candidate/provenance, exact destination authority, and
  operation-owned private staging whose verified bytes, identity, and binding
  stay protected through atomic installation, without a gap at the destination
  guard handoff. A qualified guard must cover relevant competing writers and
  destination content/name binding continuously from final expected-original
  comparison through backup creation/verification/durability, atomic commit,
  installed verification/durability, persisted outcome/recovery recording, and
  release. Create the independent backup from the guarded original; verify and
  protect its bytes/object/binding, bind it to that exact original and transaction,
  and establish backup data/namespace durability plus recoverable transaction
  association before any destination mutation. Success requires installed
  byte/binding verification, installed data/namespace durability, and persisted
  outcome/recovery recording while guarded; atomic commit alone is insufficient.
  Otherwise it is unsupported and fails closed; separate create-new/Save As
  may remain available only with its own qualified publication contract.
  Pre-mutation failures leave the destination untouched by the operation. Once
  commit may have occurred, missing evidence means UNCERTAIN: retain durable
  backup and available staging/evidence; never blindly retry, restore, delete,
  or clean up. After crash/guard loss, reacquire the guard and reconcile all
  artifact identities/hashes, persisted evidence, and competing changes before
  mutation. Restoration is a separate guarded conditional replacement requiring
  durable verified backup authority bound to the exact original. Cleanup needs
  proven ownership and disposal authority protected through disposal; success
  alone does not authorize backup deletion or end recovery obligations.

The mandatory authority boundary, state machine, format enablement gate, and
output-placement separation for any future editor are defined in
[transactional-edit-safety.md](transactional-edit-safety.md). This is a design
gate only; current `SUPPORTED` detection remains read-only support and does not
authorize serialization or editing.

## What we are deliberately not building yet

- inventory/item databases
- active soldier decoding
- save rewriting
- 1.13 compatibility
- a generic binary-schema framework
- dependency injection/framework plumbing
- persistence/database/networking

Each of those adds surface area before the first useful read-only path is proven.
