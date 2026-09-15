# Build 04.12.02 save header

## Scope and confidence

This is the 432-byte normal Windows/Stracciatella-style header map used by the
examined JA2 Reborn Build `04.12.02` save, which reports saved-game version
`103`. It is an interoperability layout, not a save-family detector. A file
having 432 bytes, version `103`, or the expected build string is not by itself
proof that the file is a JA2 Reborn save.

The map is supported by two evidence lines:

- the independently written
  [repository research](https://github.com/PhishyOne/JA2-Field-Kit/blob/47afb4f8dc99f416bcf8d6f391d12254f4bb052f/docs/SAVE_FORMAT_RESEARCH.md)
  and [provenance record](https://github.com/PhishyOne/JA2-Field-Kit/blob/47afb4f8dc99f416bcf8d6f391d12254f4bb052f/docs/RESEARCH_PROVENANCE.md)
  at immutable commit `47afb4f8dc99f416bcf8d6f391d12254f4bb052f`,
  which record a successful private-save decode; and
- the field order and serialized primitive types at immutable JA2 Reborn
  commit
  [`743f38a6ca86c81893376c2576277db660320170`](https://github.com/RealTommyGreen/JA2-Reborn/commit/743f38a6ca86c81893376c2576277db660320170),
  particularly
  [`SaveLoadGame.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.cc#L219-L247),
  [`SaveLoadGame.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.h#L17-L53),
  [`GameSettings.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/GameSettings.h#L76-L101),
  [`GameVersion.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/GameVersion.cc),
  [`LoadSaveData.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/sgp/LoadSaveData.h),
  and
  [`Types.h`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/sgp/Types.h#L28-L53).

The multi-byte values below are little-endian in the examined normal save.
The cited serializer writes native-width values, so this statement must not be
generalized to the distinct historical 688-byte Stracciatella Linux header.

## Field map

Offsets are zero-based and end offsets are exclusive. `BOOLEAN` is serialized
as one unsigned byte. The parser exposes `0` as false, `1` as true, and retains
any other raw value without assigning it a Boolean meaning.

| Range | Width | Representation | Meaning |
| ---: | ---: | --- | --- |
| `0..4` | 4 | unsigned 32-bit, little-endian | Saved-game serialization version; `103` for the evidenced build |
| `4..20` | 16 | fixed single-byte, NUL-terminated string | Game version/build string; `Build 04.12.02` for the evidenced build |
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
| `305..306` | 1 | unsigned 8-bit | Difficulty level (`1` easy, `2` medium, `3` hard) |
| `306..307` | 1 | unsigned 8-bit `BOOLEAN` | Turn-time-limit option |
| `307..308` | 1 | unsigned 8-bit | Game-save mode (`0` can-save, `1` Iron Man, `2` Dead Is Dead) |
| `308..315` | 7 | opaque bytes | Reserved part of the initial-options block |
| `315..316` | 1 | opaque byte | Unknown/reserved |
| `316..320` | 4 | unsigned 32-bit, little-endian | Per-save random value; an input to later encryption selection |
| `320..324` | 4 | unsigned 32-bit, little-endian | Save-state byte size, present for saved-game version 102 and later |
| `324..432` | 108 | opaque bytes | Unknown/reserved legacy tail |

The four opaque ranges are returned verbatim by `SaveHeaderParser`; no names or
values are inferred for their contents. The parser reads exactly the first 432
bytes, rejects every shorter input, and rejects a version/build identity other
than `103` / `Build 04.12.02`. Successful parsing still does not select a save
family or an encryption table.

## Reproducible validation and remaining fixture blocker

Public unit tests use a project-authored synthetic 432-byte sentinel vector.
Its field writes use literal documented offsets, so moving a parser offset
breaks the assertions. The vector is registered as `synthetic-build-04.12.02-header-v1` in
`fixtures/provenance-manifest.json`. The side-effect-free Python generator
`tools/generate_build041202_header.py` preserves the original sentinel recipe.
Core test resource preparation validates the manifest and verifies the generated
size and SHA-256 before parser tests consume the bytes. Python 3 is therefore
required for core tests. The vector contains no bytes or campaign values from a
real save.

The known real save remains cataloged as
`ja2-reborn-04.12.02-known-save-01`, local-only, non-redistributable, and with a
pending identity. Consequently this tranche cannot yet run the real-save
campaign assertion required for full issue #4 acceptance. That gate needs all
of the following, without publishing the save:

1. the authorized file at
   `fixtures/private/ja2-reborn/04.12.02/known-save-01.sav`;
2. its exact byte size and lowercase SHA-256 recorded as a verified identity;
3. independently verified expected day, hour, minute, sector X/Y/Z, player
   merc count, balance, all five initial option bytes, per-save random value,
   and save-state size added to the manifest; and
4. an opt-in local test that validates the identity before opening the file
   read-only and comparing those logical values.

Until those inputs exist, synthetic structural coverage must not be described
as validation against the real Build 04.12.02 save.
