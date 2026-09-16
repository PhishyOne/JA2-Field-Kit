# Build 04.12.02 save header

## Scope and evidence

This is the 432-byte normal Windows/Stracciatella-style header map evidenced
for saved-game version `103` and game-version string `Build 04.12.02`. It is an
interoperability layout, not a save-family detector. A file having 432 bytes,
version `103`, or this string is not by itself proof that the file is a JA2
Reborn save.

The map was re-derived for this change from two independent evidence lines:

- the project's independently written
  [save-format research](https://github.com/PhishyOne/JA2-Field-Kit/blob/47afb4f8dc99f416bcf8d6f391d12254f4bb052f/docs/SAVE_FORMAT_RESEARCH.md)
  and
  [research provenance](https://github.com/PhishyOne/JA2-Field-Kit/blob/47afb4f8dc99f416bcf8d6f391d12254f4bb052f/docs/RESEARCH_PROVENANCE.md)
  at immutable commit `47afb4f8dc99f416bcf8d6f391d12254f4bb052f`,
  which record the prior private-save decode without publishing its bytes or
  campaign values; and
- the field order and serialized primitive declarations at immutable JA2
  Reborn commit
  [`743f38a6ca86c81893376c2576277db660320170`](https://github.com/RealTommyGreen/JA2-Reborn/commit/743f38a6ca86c81893376c2576277db660320170),
  particularly
  [`SaveLoadGame.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.cc#L219-L247),
  [`SaveLoadGame.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.h#L17-L53),
  [`GameSettings.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/GameSettings.h#L76-L101),
  [`GameVersion.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/GameVersion.cc),
  [`LoadSaveData.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/sgp/LoadSaveData.h),
  and
  [`Types.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/sgp/Types.h#L28-L53).

Multi-byte numbers in this normal header are little-endian. The distinct
historical 688-byte Stracciatella Linux header is outside this parser's scope.

## Field map

Offsets are zero-based and end offsets are exclusive. `BOOLEAN` is serialized
as one unsigned byte. The parser exposes `0` as false and `1` as true; it keeps
any other raw byte without assigning it a Boolean meaning.

| Range | Width | Representation | Meaning |
| ---: | ---: | --- | --- |
| `0..4` | 4 | unsigned 32-bit, little-endian | Saved-game serialization version; exactly `103` for this parser |
| `4..20` | 16 | fixed single-byte, NUL-terminated string | Game-version string; exactly `Build 04.12.02` for this parser |
| `20..276` | 256 | 128 UTF-16LE code units, NUL-terminated | Save description |
| `276..280` | 4 | opaque bytes | Unknown/reserved legacy range |
| `280..284` | 4 | unsigned 32-bit, little-endian | World day |
| `284..285` | 1 | unsigned 8-bit | World hour |
| `285..286` | 1 | unsigned 8-bit | World minute |
| `286..288` | 2 | signed 16-bit, little-endian | Sector X |
| `288..290` | 2 | signed 16-bit, little-endian | Sector Y |
| `290..291` | 1 | signed 8-bit | Sector Z |
| `291..292` | 1 | unsigned 8-bit | Mercenaries on the player's team |
| `292..296` | 4 | signed 32-bit, little-endian | Current balance |
| `296..300` | 4 | unsigned 32-bit, little-endian | Screen ID stored by the save writer |
| `300..301` | 1 | unsigned 8-bit `BOOLEAN` | Alternate-sector flag |
| `301..302` | 1 | unsigned 8-bit `BOOLEAN` | World-loaded flag |
| `302..303` | 1 | unsigned 8-bit | Load-screen ID |
| `303..304` | 1 | unsigned 8-bit `BOOLEAN` | Gun Nut option |
| `304..305` | 1 | unsigned 8-bit `BOOLEAN` | Sci-Fi option |
| `305..306` | 1 | unsigned 8-bit | Difficulty level |
| `306..307` | 1 | unsigned 8-bit `BOOLEAN` | Turn-time-limit option |
| `307..308` | 1 | unsigned 8-bit | Game-save mode |
| `308..315` | 7 | opaque bytes | Reserved portion of the initial-options block |
| `315..316` | 1 | opaque byte | Unknown/reserved |
| `316..320` | 4 | unsigned 32-bit, little-endian | Per-save random value; merely preserved as a field here |
| `320..324` | 4 | unsigned 32-bit, little-endian | Save-state byte size, present for version 102 and later |
| `324..432` | 108 | opaque bytes | Unknown/reserved legacy tail |

The known and opaque ranges exactly partition all 432 header bytes. The four
opaque ranges are returned verbatim. The parser reads exactly the first 432
bytes, rejects every shorter input, and rejects any version/build identity
other than `103` / `Build 04.12.02`. Successful parsing does not select a save
family, infer that later blocks exist, or choose an encryption table.

## Synthetic coverage and remaining evidence blocker

Public unit tests use the project-authored deterministic 432-byte sentinel
`synthetic-build-04.12.02-header-v1`, registered in
`fixtures/provenance-manifest.json`. Its generator uses literal documented
offsets so parser-offset drift fails field assertions. It reads no input and
contains no real-save bytes or real campaign values.

Gradle does not execute the generator directly. Test-resource preparation asks
the fixture validator to materialize the public generated fixture. That narrow
interface validates the current tracked worktree and runs every manifested
generator through the existing exact-snapshot, read-only Bubblewrap authority,
including network-namespace, seccomp, resource, size, and SHA-256 checks. A host
that cannot establish that authority fails closed.

The cataloged real save `ja2-reborn-04.12.02-known-save-01` remains local-only,
non-redistributable, and pending exact identity. Full real-save validation is
blocked until authorized bytes exist locally and evidence supplies their exact
size, SHA-256, and expected non-sensitive header results. None of those values
is guessed by this change.
