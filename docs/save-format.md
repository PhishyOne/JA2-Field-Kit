# Save-format knowledge

This document records current evidence without treating every observation as a universal JA2 invariant.

## Confirmed from prior source study + real save decoding

A real JA2 Reborn Build `04.12.02` save reporting saved-game version `103`
was successfully decoded before this repository was created. That work
established the parser concept and produced an 18-merc roster with stats.

For the examined normal Windows/Stracciatella-style layout used by that save:

- Save header: **432 bytes**.
- The header contains save version, day/time, sector, player merc count, balance, game options, random/encryption-related state, and other fields.
- Reborn uses rotating save encryption selected from header state.
- Encrypted merc profile table: **170 `MERCPROFILESTRUCT` records**.
- Serialized merc profile record size: **716 bytes each**.
- Encrypted active soldier records serialize `SOLDIERTYPE` at **2328 bytes each**.
- Merc profiles contain names, base/profile stats, traits, combat history, salary, profile inventory, location, and additional data.
- Active soldier records contain current stats/condition, sector, assignment, tactical inventory, paths, and additional runtime state.

## Encoded structural slice

The exact v103 / `Build 04.12.02` normal-header offsets and the narrow,
layout-only parser are recorded in
[`save-header-04.12.02.md`](save-header-04.12.02.md). Public tests use an
admitted synthetic vector because real-save evidence remains local-only. A
separate authorized Android Stracciatella save now backs structural header
validation; the historical JA2 Reborn entry remains pending independently. The
parser does not identify a save family by itself.

The normal, non-German encryption selector and byte-wise block transform are
now implemented from source-derived format facts rechecked at immutable JA2
Reborn commit `743f38a6ca86c81893376c2576277db660320170`. The selector uses explicit
32-bit unsigned wrapping, the evidenced nested random branches, and the 19-entry
option/difficulty banks. Header-to-selector conversion rejects non-`0`/`1`
selector booleans, unsupported difficulty identities, and unsupported header
identities instead of guessing. This conversion remains separate from save
family detection.

The transform accepts an exact expected block size, including a zero-byte
no-op; zero is an API-valid block length, not a claim about a real serialized
block. Tests use project-authored synthetic selector and decryption vectors.
The repository does **not** contain the production 49-byte rotation-table
contents or an independently admissible real encrypted-block vector. The
rotation is instead recovered from each framed save and remains scoped to that
inspection. Full-save encrypted block locations and merc-profile field offsets
were not established by the earlier encryption slice; the bounded framing and
profile decoding sections below independently establish those later slices.

## Bounded encrypted-profile framing

For the explicitly normal, non-Linux v103 / `Build 04.12.02` layout, the
read-only framer now advances through the 432-byte header, 316-byte tactical
status, 5-byte current sector, 62-byte game clock, little-endian `u32` event
count, `count * 28` strategic-event bytes, and 7440-byte fixed laptop block.
With both laptop used counts zero, the encrypted 170-by-716-byte profile table
therefore begins at `8259 + count * 28` and occupies exactly 121720 bytes.
The frame's `profileStartOffset` and `profileEndExclusive` are zero-based
absolute byte offsets into the supplied save; the start is inclusive and the
end is exclusive. Truncation diagnostics likewise report the zero-based
absolute exclusive boundary as `requiredEndExclusive`.

Every boundary is checked before narrowing an offset or copying bytes. A
nonzero Bobby Ray order-used count or insurance payout-used count is reported
as an unsupported dynamic laptop tail; the framer does not skip or infer those
platform-layout-dependent structures. Later save sections may trail the exact
profile range. The framer itself does not detect save families or Linux layouts,
scan for ciphertext, decrypt profiles, parse roster semantics, or support
dynamic laptop tails.

## Read-only profile decode

The whole-save profile API now composes the framer with the save-derived
rotation recovery and explicit-table decryptor. It decrypts each of the 170
records as a separate 716-byte operation and parses only names, profile life
and maximum life, and the requested core stats. Exact offsets, encodings,
structured parse failures, public synthetic coverage, and upstream provenance
are recorded in
[`profile-decode-v0.1.md`](profile-decode-v0.1.md).

The output is the complete profile table. It is deliberately not called a
roster: no profile-only hired/player-membership predicate has been independently
established, and this slice does not inspect `SOLDIERTYPE`.

## Compatibility strategy

Target order:

1. JA2 Reborn
2. Normal JA2 Stracciatella / compatible PC saves
3. Classic JA2 where practical
4. JA2 1.13 and heavily modified formats as separate future adapters

Do not assume two formats are different merely because their product names differ. Detect divergence from bytes/fixtures and share parsing code where layouts are actually identical.

## Fixture policy

Real save fixtures are the ground truth for parser behavior, but they are not
automatically safe to publish. The authoritative admission, provenance,
hashing, and CI rules are in [`fixture-policy.md`](fixture-policy.md). The
machine-readable registry and contract live under [`fixtures/`](../fixtures/).

At minimum, each supported family/version needs:

- source/build identification,
- expected header fields,
- expected campaign summary,
- expected roster size,
- several known merc names/stats,
- truncated/corrupted variants for failure tests.

If redistribution of a save fixture is legally or personally questionable, keep the fixture private and store only non-sensitive hashes/expected logical assertions in the public repository.

## Licensing boundary

JA2/JA2 Reborn source may be consulted to understand serialized structures and algorithms. Field Kit implementation should be independently written from those facts rather than copied from restricted source.

Before adding any copied constants, tables, code-shaped logic, or a repository license, review whether the material is factual format information or protected implementation expression.
