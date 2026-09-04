# JA2 Field Kit

Android-first companion and save-game inspector/editor for Jagged Alliance 2.

JA2 Field Kit is intentionally separate from JA2 Reborn and from other game projects. The phone is where the tool runs; supported save files may originate on PC, JA2 Reborn, Stracciatella, or other compatible builds.

## Current status

The project is at the read-only core/scaffolding stage.

Milestone v0.1 is deliberately narrow:

`open .sav -> detect format/version -> campaign summary -> roster -> merc stats`

No save-writing API exists yet. Editing will only be added after reliable parse/rewrite/reread validation exists.

## Architecture

- `core/` is a pure Kotlin/JVM library with no Android dependencies.
- Android UI will be added later as a consumer of `core`.
- Save-family/version-specific logic belongs behind format adapters rather than leaking into UI code.
- Binary parsing must be bounds-checked and fail closed on unknown/truncated input.
- Original save files must never be overwritten by default once editing exists.

See:

- `docs/architecture.md`
- `docs/fixture-policy.md`
- `docs/save-format.md`
- `docs/v0.1-scope.md`
- `fixtures/README.md`

## Build

Requires JDK 21. CI pins Gradle 9.5.0 and Kotlin 2.4.10.

```bash
gradle :core:test --no-daemon
python3 tools/validate_fixture_manifest.py
```

A Gradle wrapper will be added once the initial build is validated, rather than committing an unverified generated wrapper binary.

## Compatibility targets

1. JA2 Reborn
2. Normal JA2 Stracciatella / compatible PC saves
3. Classic JA2 where practical
4. JA2 1.13 and heavily modified formats are separate future targets

## Licensing boundary

This repository intentionally has **no license yet**.

JA2/JA2 Reborn source is under the Strategy First Source Code License and carries restrictions that are not compatible with casually treating derived implementation code as MIT/Apache code. Field Kit should therefore be implemented as original code that understands the save-file format. Source code may be consulted to understand the format, but implementation code should not be copied into this repository.

A licensing-boundary review is required before choosing an open-source license.
