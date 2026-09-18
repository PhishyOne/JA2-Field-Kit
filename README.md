# JA2 Field Kit

Android-first companion and save-game inspector/editor for Jagged Alliance 2.

JA2 Field Kit is intentionally separate from JA2 Reborn and from other game projects. The phone is where the tool runs; supported save files may originate on PC, JA2 Reborn, Stracciatella, or other compatible builds.

## Current status

The project is at the read-only core/scaffolding stage.

Milestone v0.1 is deliberately narrow:

`open .sav -> detect format/version -> campaign summary -> roster -> merc stats`

No save-writing API exists yet. Editing will only be added after reliable parse/rewrite/reread validation exists.

The core v0.1 presentation facade is
`Ja2SaveInspector.inspectV01(ByteArray)`. It returns a sealed, sanitized result
covering format/version/layout compatibility, the minimal campaign summary, and
the verified roster/core stats. Inputs are copied before inspection; no raw
offset, rotation, digest, key, or encryption details cross this facade.

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
- `docs/save-family-detection.md`
- `docs/save-header-04.12.02.md`
- `docs/profile-decode-v0.1.md`
- `docs/v0.1-scope.md`
- `fixtures/README.md`

## Build

The core build requires JDK 21. CI pins Gradle 9.5.0 and Kotlin 2.4.10.
Fixture-policy validation additionally supports CPython 3.12 on Linux x86-64
and uses a fully pinned, hashed dependency lock.

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements/fixture-validation.lock
FIXTURE_VALIDATOR_PYTHON=.venv/bin/python gradle :core:test --no-daemon
.venv/bin/python -m unittest discover -s tools/tests -v
head_oid=$(git rev-parse HEAD)
.venv/bin/python tools/validate_fixture_manifest.py \
  --subject "local-head=$head_oid"
```

Generated-fixture admission also requires a working unprivileged Bubblewrap on
Linux x86-64 so each explicit head can run in its own read-only,
network-namespace-isolated snapshot with socket and io_uring syscalls denied as
a second layer. On the pinned Ubuntu 24.04 runner, CI performs a real
sandbox smoke test. If AppArmor's host-wide user-namespace restriction blocks
an otherwise stock host, setup installs `apparmor-profiles`, verifies and adds
only Ubuntu's packaged `bwrap-userns-restrict` policy, and smokes the sandbox
again. It never disables the host-wide restriction and refuses ambiguous or
conflicting bwrap policy.

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
