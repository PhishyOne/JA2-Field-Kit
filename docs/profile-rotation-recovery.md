# Bounded normal-profile rotation recovery

This is the bounded successor to issue #5 / frozen Draft PR #20 at
`d74917225bd74e2f32b73ac91afe1615bf824ccb`, implementing the
[banked architecture decision](https://github.com/PhishyOne/JA2-Field-Kit/issues/5#issuecomment-5702806657).
`NormalProfileRotationRecovery.recoverBuild041202` accepts only an
**already-framed exact 121720-byte block**, comprising 170 separately encrypted
716-byte canonical normal v103 / Build `04.12.02` profile-table entries.
These are not hired roster members. The method does not inspect a full save.

The caller must independently establish the layout and framing. Exact length
does not identify the family or prove the boundary. There is no offset scan,
name search, framing parser, header selector integration, provider lookup,
global/indexed key cache, Android integration, or save-writing API here.
PR20 source files and their explicit-table decryptor remain unchanged.

## Format facts and algorithm

All offsets below are zero-based within a record; interval ends are exclusive.
Encryption feedback and rotation position both restart at zero for every
record. Rotation length is 49. At a canonical zero byte `i`, the rotation
constraint is `(cipher[i] - previousCipher) mod 256` at residue `i mod 49`;
the preceding ciphertext is zero at the start of a record.

The reserved zero map is:

```text
[80,108)   [236,238) [240,242) [270,280)
[309,310)  [354,355) [356,358) [407,408)
[415,416)  [454,474) [525,529) [539,540)
[550,552)  [573,574) [680,682) [712,716)
```

These 82 positions cover exactly 48 residues, excluding residue 10. Every
constraint across all 170 records must agree: no majority vote or salvage.
The input is copied after the exact-size check; all later reads use that
snapshot. Callers must not mutate the array concurrently during the copy.

The checksum reads the following raw signed 8-bit pairs, in order:

| First field / offset | Second field / offset |
| --- | --- |
| life / 334 | lifeMax / 297 |
| agility / 405 | dexterity / 335 |
| strength / 296 | marksmanship / 353 |
| medical / 261 | mechanical / 411 |
| explosives / 339 | experienceLevel / 352 |

Starting at `s=1`, each pair `(a,b)` replaces `s` with
`(s+a+1)*(b+1) mod 2^32`. Then add nineteen unsigned 16-bit little-endian
inventory IDs at `[416,454)` and nineteen unsigned 8-bit counts at `[377,396)`,
modulo `2^32`. Compare with unsigned 32-bit little-endian storage at `[696,700)`.
There is no stat clamping. Long intermediates safely hold each multiplication;
explicit masks implement the unsigned 32-bit result.

All 256 values of residue 10 are enumerated. Each candidate uses the existing
explicit-table `NormalSaveBlockDecryptor` separately for each record, including
that candidate's marksmanship, inventory and stored-checksum bytes. A checksum
mismatch discards that candidate immediately. A surviving candidate must pass
the final record. Enumeration continues after successes, and only exactly one
whole-table survivor returns an immutable, defensively copied `SaveRotationTable`.
No plaintext, partial key, or early success escapes recovery.

The fixed work bound is 13940 reserved constraints and at most 43520 record
decryptions, or 31160320 decrypted bytes, plus small checksum work. Storage is
`O(170*716 + 49)` live bytes with bounded temporary record allocations; candidates
are processed sequentially. Nothing persists between invocations.

## Failures and limits

`ProfileRotationRecoveryException.reason` distinguishes:

| Reason | Meaning |
| --- | --- |
| `INVALID_BLOCK_SIZE` | Empty, truncated, or oversized input; includes actual size |
| `CONFLICTING_RESERVED_CONSTRAINT` | Two canonical zero constraints disagree; includes record/offset |
| `NO_CANDIDATE` | No full-table checksum survivor |
| `AMBIGUOUS` | Multiple full-table survivors; includes the complete candidate count |

Diagnostics contain structural metadata only. Neither exception text nor table
`toString()` dumps payloads or rotation bytes.

Consistency is not authentication, global upstream table identity, family
detection, whole-file integrity, name validation, stat validity, or roster
identification. A changed name character leaves the limited checksum unchanged;
the test deliberately verifies successful recovery after that change. Malformed
or malicious inputs are not universally detected. Future semantic parsing owns
encodings and game-valid ranges, independently of this raw checksum.

## Evidence and authorship

The supplied interoperability facts cite immutable JA2 Reborn commit
`743f38a6ca86c81893376c2576277db660320170`:

- `src/game/Tactical/LoadSaveMercProfile.cc`: record serialization and checksum.
- `src/sgp/LoadSaveData.cc`: skipped serialization bytes are zero-filled.
- `src/game/SaveLoadGame.cc`: per-record encryption/reset.

These paths are factual provenance, not copied implementation. This worker did
not fetch those sources, rotation arrays, APK contents, private saves, or host
research. The Kotlin and Python implementations are independently project-authored
from the supplied mathematics; this is not a clean-room or legal-clearance claim.
No project license is introduced.

The banked independent Python probe reports 48 consistent residues across all
170 entries, exhaustive residue-10 enumeration with one unique value per record
and unanimous agreement, and 170 verified checksums. It reports unchanged private
manifest identity. That evidence covers **one Stracciatella fixture**; historical
Reborn validation is separately pending. It does not establish that this Kotlin
implementation ran on real bytes, or compatibility with other platforms/layouts.

## Public synthetic experiment

`tools/generate_build041202_profile_recovery.py` has no inputs and emits only its
deterministic synthetic vector. It implements checksum and forward encryption
independently from production Kotlin. Byte packaging is:

| Range | Contents |
| --- | --- |
| `[0,49)` | Original key `(73*i+19) mod 256` |
| `[49,121769)` | 170 expected plaintext records |
| `[121769,243489)` | 170 independently encrypted records |
| `[243489,244205)` | Ambiguity witness plaintext |
| `[244205,244921)` | Ambiguity witness ciphertext |

Record `r`, byte `i` begins as `(31*r+17*i+3) mod 256`, then reserved ranges
are zeroed and the independently calculated checksum is inserted. This fills
raw signed and unsigned fields with artificial sentinels, not campaign values.
The ambiguity witness starts at zero and sets `405=252`, `335=127`, `296=128`,
`451=1`; its checksum is 3. Repeated across all 170 records it has 129 survivors
and must fail `AMBIGUOUS`. It is deliberately unusual signed-field data.

Tests consume the generated resource only through
`materialize_public_generated_fixture`. In-memory boundary and perturbation
variants follow the existing synthetic-header test pattern. Rekey variants use
cumulative key differences on the independent ciphertext, testing all 256 actual
residue-10 values, zero/255/high-bit keys, and successive independent calls. Other
variants cover first/final-record checksum and zero-constraint failures, exact
lengths, all-zero input, erroneous continuous whole-table encryption, defensive
copies, sanitized diagnostics, and the name-field blind spot. These are synthetic
experiments only, never real-save evidence or a public encryption-for-write API.

## Verification record and successor gates

The following is historical verification evidence from the pre-rebase Draft
candidate. It is not acceptance evidence for the current candidate head:

- The synthetic fixture identity is verified at 244921 bytes with SHA-256
  `0962938b53f4e54976b2d5f8a4b380f32e8b57155d89dd5fe3e002f301832f80`.
  The identity was reproduced by validator-controlled, exact-snapshot Bubblewrap
  materialization.
- All 65 fixture-policy regressions passed on host authority.
- Kotlin/JVM `:core:test` passed with JDK 21.0.12.1 and Gradle 9.5.0 after both
  resources were materialized through the validator.
- Immutable admission passed on
  `05cc93ae477a86651041077d4e55201e9b7ed08e`: 18 reachable commit trees were
  inspected, and two exact-head generator outputs were independently measured.
- Hosted Core CI #30 passed both the fixture-policy and test jobs on that exact
  head, and a fresh immutable-head Codex review returned `CLEAN`.

Authoritative acceptance or merge evidence must be regenerated and checked on
the exact current candidate head after any mutation. These checks do not
establish checksum authenticity or family detection, and this work does not
complete issue #5. Full-save framing/locator work, historical Reborn evidence,
real-save Kotlin end-to-end validation, semantic profiles/roster, and full
inspection remain separate gates. Later framing work must not hardcode an
observed private profile offset or guess unsupported tails. The recovered key
must remain bound to the inspection that supplied the block.
