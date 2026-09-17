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
identities instead of guessing. Compatibility detection now binds that selected
index to the recovered body rotation through a non-secret digest oracle. This
binding establishes layout compatibility, not producer family.

The transform accepts an exact expected block size, including a zero-byte
no-op; zero is an API-valid block length, not a claim about a real serialized
block. Tests use project-authored synthetic selector and decryption vectors.
The repository does **not** contain the production 49-byte rotation-table
contents or an independently admissible real encrypted-block vector. It stores
one SHA-256 identity for selector index `139`, derived from immutable upstream
source, without storing table bytes. The rotation itself is recovered from each
framed save and remains scoped to that inspection. Full-save encrypted block
locations and merc-profile field offsets were not established by the earlier
encryption slice; the bounded framing and profile decoding sections below
independently establish those later slices.

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

The profile output is the complete profile table and is deliberately not called
a roster: no profile-only hired/player-membership predicate has been
independently established.

## Membership-only soldier crossing

For this same exact layout, the soldier stream begins at
`EncryptedProfileFrame.profileEndExclusive`. The roster API validates the
already-loaded tactical-status player range at absolute offsets 436 and 437 as
exactly first ID `0` and last ID `19`; these offsets are zero-based absolute
save offsets. It then walks exactly those 20 slots. Each slot begins with a
plaintext active marker that must be `0` or `1`. An inactive slot ends there.
Each active slot has one separately encrypted 2328-byte normal `SOLDIERTYPE`
record, followed by a plaintext little-endian `u32` path-node count, exactly
`count * 20` opaque path bytes, a plaintext `0`/`1` keyring marker, and 128
opaque keyring bytes when the marker is `1`.

Only these zero-based offsets within a decrypted soldier record are public
format semantics for this slice:

| Offset | Encoding | Membership/check purpose |
| ---: | --- | --- |
| 0 | `u8` | soldier ID; must equal outer slot |
| 8 | LE `u32` | require `SOLDIER_PC` (`0x00000008`); classify `SOLDIER_VEHICLE` (`0x00008000`) |
| 751 | `i8` | inner active; must be `1` |
| 752 | `i8` | team; must be `OUR_TEAM` (`0`) |
| 1825 | `u8` | profile record index, `0..169`, for non-vehicles |
| 2208 | LE `u32` | mandatory soldier checksum |

The checksum recurrence additionally reads signed stat bytes at 868, 917, 880,
840, 886, 1377, 1372, 916, 1378, and 849 in the documented source order. Its
19 inventory checksum inputs are the LE `u16` item at `12 + 36*j` and unsigned
count at `14 + 36*j`. These are opaque integrity inputs only: the roster API
does not return soldier condition or inventory data.

Active vehicles are structurally validated, including checksum and framing,
but excluded from roster membership. Non-vehicle profile IDs must be unique and
are joined directly to the already-parsed record-indexed profile table. After
all 20 slots have been scanned, the derived non-vehicle count must equal header
`ubNumOfMercsOnPlayersTeam`. That header field is a cross-check only; it never
controls slot framing or scan length. This explicitly scoped API does not claim
save-family detection.

## Compatibility strategy

The implemented v0.1 decision matrix and deferred-family boundary are recorded
in [`save-family-detection.md`](save-family-detection.md). Only a complete v103 /
`Build 04.12.02` normal non-Linux path whose recovered rotation matches the
admitted digest for its header-selected index is classified as compatible and
`SUPPORTED`. Producer family remains `UNKNOWN`: the examined Reborn and
Stracciatella evidence has no byte discriminator at this layer. Missing-oracle,
shared-header, truncated, contradictory, 1.13, and modded inputs do not fall
through to support.

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
