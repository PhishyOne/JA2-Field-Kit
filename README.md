# JA2 Field Kit

Android-first companion and save-game inspector/editor for Jagged Alliance 2.

JA2 Field Kit is intentionally separate from JA2 Reborn and from other game projects. The phone is where the tool runs; supported save files may originate on PC, JA2 Reborn, Stracciatella, or other compatible builds.

## Current status

The project has a first read-only Android shell over the v0.1 core.

Milestone v0.1 is deliberately narrow:

`open .sav -> detect format/version -> campaign summary -> roster -> merc stats + live inventory`

No save-writing API exists yet. Editing will only be added after reliable parse/rewrite/reread validation exists.

The `android-app/` shell can select a save with Android's document picker and
accept narrowly advertised open-with/share-to content URIs. It accepts and
retains complete payloads up to 16 MiB; an oversized rejected stream may be
transiently read beyond that boundary by up to one current 64 KiB buffer read
and is not retained as an accepted save. It lends one task-local byte snapshot
to `inspectV01` and `inspectLiveMercState`, and displays the source filename,
actual and optional provider sizes, optional provider timestamp,
lowercase SHA-256, format, campaign summary, roster, profile/base stats, verified
live/current tactical stats, 19-slot grouped read-only live inventory, or sanitized
failure codes. Local/Downloads, USB, and cloud documents use Android Storage Access
Framework providers; open-with and share-to converge on the same importer. It
requests no storage permission, retains no URI grant or save bytes, performs no
scanning/upload, and never writes, exports, or modifies a save.
For a completed import that fails inspection, the user can explicitly preview,
copy, or share a versioned privacy-bounded compatibility report; save
contribution remains unimplemented.

The success screen offers **Load item names** / **Replace item names** through a
separate explicit document picker for a user-owned `Binarydata.slf`. The only
admitted catalog is **GOG English v1.12 (Build 04.12.02)**: exactly 2,047,959 bytes,
MD5 `ffd1c49977c891d9c7ffc7756a25f741` (identity only, not authentication).
No catalog ships with Field Kit. Sanitized full English names are optional
**Base catalog** display decoration; mods may override them. Save-derived role,
numeric item ID, and object count remain authoritative and visible. Unknown IDs
remain numeric and empty slots remain empty.

The content-only reader consumes at most 2,047,960 bytes without trusting provider
size. Archive and item-description bytes stay task-local; no URI grants, archive
bytes, or derived catalog files are durably persisted. Immutable decoded names
remain only in ViewModel memory across Activity recreation and later save
inspections, and disappear on clear/process death. Rejected replacements
explicitly leave the previous catalog active; stale requests cannot replace a
later request. Catalog import never invokes save inspection, adds no network or
storage permission, and is not available through Open With or Share To.

The core v0.1 presentation facade is
`Ja2SaveInspector.inspectV01(ByteArray)`. It returns a sealed, sanitized result
covering format/version/layout compatibility, the minimal campaign summary, and
the verified roster/core stats. Inputs are copied before inspection; no raw
offset, rotation, digest, key, or encryption details cross this facade.

The [Issue #72 candidate transaction](docs/save-edit-transactions.md) adds a
source-bound core request/result architecture and privileged synthetic coverage
for ten synchronized hired-merc stats with stat-specific ranges and injury guards.
Production editing remains disabled;
Android Save As awaits a qualified create-new placement adapter.

## Architecture

- `core/` is a pure Kotlin/JVM library with no Android dependencies.
- `android-app/` owns content-URI access and presentation; it contains no parser logic.
- Save-family/version-specific logic belongs behind format adapters rather than leaking into UI code.
- Binary parsing must be bounds-checked and fail closed on unknown/truncated input.
- Original save files must never be overwritten by default once editing exists.

See:

- `docs/architecture.md`
- `docs/transactional-edit-safety.md`
- `docs/fixture-policy.md`
- `docs/save-format.md`
- `docs/save-family-detection.md`
- `docs/save-header-04.12.02.md`
- `docs/profile-decode-v0.1.md`
- `docs/v0.1-scope.md`
- `docs/android-read-only-shell.md`
- `docs/compatibility-report-v0.1.md`
- `docs/release-privacy-checklist.md`
- `docs/licensing-boundary-review.md`
- `fixtures/README.md`

## Build

The build requires JDK 21. CI pins Gradle 9.5.0, Kotlin 2.4.10, Android
Gradle Plugin 9.3.2, Android API 37, and Build Tools 36.0.0.
Fixture-policy validation additionally supports CPython 3.12 on Linux x86-64
and uses a fully pinned, hashed dependency lock.

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements/fixture-validation.lock
FIXTURE_VALIDATOR_PYTHON=.venv/bin/python gradle :core:test --no-daemon
gradle :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --no-daemon
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

Successful main-push CI publishes the test-only debug APK as the Actions artifact
`ja2-field-kit-debug-<exact source SHA>`, retained for 7 days. A dedicated
publication job rebuilds the debug APK after both fixture-policy and test jobs
succeed, including Android unit tests, lint, and debug assembly. Pull requests
and feature-branch pushes do not publish this artifact. Its adjacent
`.apk.provenance.txt` file
records the exact source SHA, APK SHA-256, APK byte size, debug/test-only markers,
and a verified signing certificate summary (the first signer's SHA-256 digest).

This is test delivery, not Play, GitHub Release, or public release distribution.
Only exact-main retained test APKs use a persistent private test signing key;
PR/test builds continue to use ordinary default debug signing without those
credentials. The first migration from old ephemeral-key builds may require one
uninstall, then future retained test APKs can update in place so long as the
persistent test key is preserved. This is NOT production/Play signing. Public
distribution requires separate future authorization.

## Compatibility targets

1. JA2 Reborn
2. Normal JA2 Stracciatella / compatible PC saves
3. Classic JA2 where practical
4. JA2 1.13 and heavily modified formats are separate future targets

## Licensing boundary

This repository intentionally has **no license yet**.

JA2/JA2 Reborn source is under the Strategy First Source Code License and
carries restrictions that are not compatible with casually treating derived
implementation code as MIT/Apache code. Field Kit should use project-authored
expression of reviewed interoperability facts. Upstream implementation code,
comments, and tables must not be copied or translated into this repository.

The current engineering boundary review and its remaining decision gate are in
[`docs/licensing-boundary-review.md`](docs/licensing-boundary-review.md). No
project license has been selected or added.
