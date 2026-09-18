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
collects a sanitized display name and optional provider size, then reads a
maximum of 16 MiB. Only bytes enter `Ja2SaveInspector.inspectV01`; URI and
provider details never enter core. Presentation state contains no offsets,
rotation indexes, digests, keys, ciphertext, or parser exceptions.

The current shell has no permissions, persistence, network, analytics, write,
export, or URI-to-filesystem-path code. Share/export remains future scope.

## Failure policy

- Unknown version: return unsupported/unknown, not guessed data.
- Truncated block: stop parsing that logical result and report a structured error.
- Invalid stat/range: preserve evidence for diagnostics; do not silently clamp during parsing.
- Editing (future): write a new file, reread it, verify requested logical changes, then report success.
- Never overwrite the original save by default.

## What we are deliberately not building yet

- inventory/item databases
- active soldier decoding
- save rewriting
- 1.13 compatibility
- a generic binary-schema framework
- dependency injection/framework plumbing
- persistence/database/networking

Each of those adds surface area before the first useful read-only path is proven.
