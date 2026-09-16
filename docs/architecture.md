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

The current core implements the non-destructive structural probe, general
little-endian primitives, a narrow parser for the evidenced v103 /
`Build 04.12.02` normal header, and the source-derived normal encryption
selector and block transform. The parser is layout-specific and intentionally
does not pretend that file length or header identity identifies a save family.
Rotation-table data stays behind a provider boundary: production tables are not
bundled, and the transform is currently tested only with project-authored
synthetic data rather than an admitted real encrypted block.

## Planned boundaries

### `core.io`

Bounds-checked binary primitives and, later, seekable save input abstractions. No JA2 game semantics.

### `core.format`

Known layout facts, format/version detection, encryption selection, and family-specific adapters.

A future internal adapter contract should expose logical parsing, not raw structure details, for example:

```text
probe -> detect -> parseCampaignSummary -> parseRoster
```

Do not create separate adapters until fixture evidence shows the formats actually diverge.

### `core.model`

Immutable logical data consumed by UI: campaign summary, roster, merc stats, and later condition/inventory/economy models.

### future Android `app`

Owns Android Storage Access Framework integration, permissions, document picker, screens, and sharing/export. It should not know binary offsets or encryption rules.

## Failure policy

- Unknown version: return unsupported/unknown, not guessed data.
- Truncated block: stop parsing that logical result and report a structured error.
- Invalid stat/range: preserve evidence for diagnostics; do not silently clamp during parsing.
- Editing (future): write a new file, reread it, verify requested logical changes, then report success.
- Never overwrite the original save by default.

## What we are deliberately not building yet

- Android UI
- inventory/item databases
- active soldier decoding
- save rewriting
- 1.13 compatibility
- a generic binary-schema framework
- dependency injection/framework plumbing
- persistence/database/networking

Each of those adds surface area before the first useful read-only path is proven.
