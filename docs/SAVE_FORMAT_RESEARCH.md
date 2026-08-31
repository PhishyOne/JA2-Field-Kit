# JA2 save-format research

Status: **research notes / implementation input**, not a complete file-format specification.

This document records the independently written observations used to bootstrap JA2 Field Kit. It intentionally describes behavior and data layout rather than copying source from Jagged Alliance 2, JA2 Stracciatella, or JA2 Reborn.

## Research baseline

The first successful validation used a JA2 Reborn save reporting:

- saved-game version: `103`
- game build label: `04.12.02`
- normal Windows/Stracciatella-style header

A private real-world save was decoded successfully enough to recover the player's roster and persistent mercenary statistics. The private save and its player-specific contents are **not** part of this repository.

Primary compatibility reference during the initial investigation:

- repository: `RealTommyGreen/JA2-Reborn`
- reference commit: `743f38a6ca86c81893376c2576277db660320170`

Useful upstream reference files at that commit include:

- `src/game/SaveLoadGame.cc`
- `src/game/SaveLoadGame.h`
- `src/game/Tactical/Tactical_Save.cc`
- `src/game/Tactical/Tactical_Save.h`
- `src/game/Tactical/LoadSaveMercProfile.cc`
- `src/game/Tactical/LoadSaveSoldierType.cc`
- `src/game/Tactical/Soldier_Profile_Type.h`
- `src/game/Tactical/Soldier_Control.h`

These references are evidence for compatibility behavior. Field Kit should implement its own parser/serializer code.

## Header

The normal Windows/Stracciatella header occupies **432 bytes**.

For save version 103, the observed logical layout is:

| Offset | Size | Meaning |
| ---: | ---: | --- |
| 0 | 4 | saved-game version (`uint32`) |
| 4 | 16 | game version/build string |
| 20 | 256 | save description, 128 UTF-16 code units |
| 276 | 4 | reserved/legacy |
| 280 | 4 | world day |
| 284 | 1 | hour |
| 285 | 1 | minute |
| 286 | 2 | sector X |
| 288 | 2 | sector Y |
| 290 | 1 | sector Z |
| 291 | 1 | number of mercs on player team |
| 292 | 4 | current balance (`int32`) |
| 296 | 4 | current/previous screen id |
| 300 | 1 | alternate-sector flag |
| 301 | 1 | world-loaded flag |
| 302 | 1 | load-screen id |
| 303 | 12 | initial game options block |
| 315 | 1 | reserved |
| 316 | 4 | per-save random value |
| 320 | 4 | save-state size (present in v102+) |
| 324 | 108 | reserved/legacy tail |

The game-options block contains five one-byte values followed by seven reserved bytes:

1. Gun Nut flag
2. Sci-Fi flag
3. difficulty level
4. turn-time-limit flag
5. game-save-mode value
6. seven reserved bytes

There is also a historical Stracciatella Linux header size of **688 bytes**. Treat it as a distinct compatibility path rather than assuming the 432-byte layout.

## Save encryption selection

The save uses one of a family of rotation tables. The table choice is derived from header values, so the header must be parsed before encrypted blocks can be decoded.

For the normal non-German path, the observed selection procedure is equivalent to:

1. Start with `balance * (merc_count + 1)`.
2. Add `sector_z * 3` and the load-screen id.
3. Add 7 when the alternate-sector flag is set.
4. Inspect the save's random value:
   - if divisible by 2, add 1;
   - inside that case, if also divisible by 7, add another 1;
   - if also divisible by 23, add another 1;
   - if also divisible by 79, add another 2.
5. Reduce modulo 10.
6. Add `floor(world_day / 10)` and reduce modulo 19.
7. Add option-dependent banks of 19 tables:
   - Gun Nut: `+19 * 6`
   - Sci-Fi: `+19 * 3`
   - medium difficulty: `+19`
   - hard difficulty: `+19 * 2`
   - easy difficulty: no difficulty offset

The table family therefore contains `19 * 12` selectable rotation tables. Each table contains **49 bytes**.

German-version handling modifies the intermediate selector before the modulo/bank steps and should be implemented only when that compatibility target is tested.

## Encrypted block transform

Encrypted blocks use a byte-wise feedback transform. Each encrypted read/write operation starts with:

- rotation index = 0
- previous ciphertext byte = 0

For byte `i`, conceptually:

```text
cipher[i] = (plain[i] + previous_cipher + rotation[i mod 49]) mod 256
previous_cipher = cipher[i]
```

Decryption reverses that relation:

```text
plain[i] = (cipher[i] - previous_cipher - rotation[i mod 49]) mod 256
previous_cipher = cipher[i]
```

Important: the feedback state resets for each encrypted block operation. Do not assume one continuous cipher stream across the entire `.sav` file.

## High-level save order

The save is not simply `header -> profiles -> soldiers`. Several systems are serialized first. At a high level the writer places data in this order:

1. 432-byte header reservation
2. tactical status
3. game clock
4. strategic events
5. laptop state
6. mercenary profiles
7. active soldier structures
8. finance/history/files/email state
9. strategic/map/underground/squad/movement data
10. map temporary files, quests, opposition information, messages, NPC data, keys, and additional game state
11. final header rewrite

Field Kit v0.1 does not need to decode every section. It only needs reliable navigation to the sections required for the read-only feature set.

## Mercenary profiles

The game maintains **170 mercenary profiles**.

In the tested format, every profile is serialized as an encrypted **716-byte** record.

The profile structure contains enough information for a useful read-only companion view, including:

- full name and nickname
- current/max life
- agility
- dexterity
- strength
- leadership
- wisdom
- experience level
- marksmanship
- explosives
- mechanical
- medical
- personality and skill traits
- salary/contract-related data
- strategic sector
- profile inventory ids/counts/status
- kills and assists
- shots fired / shots hit
- battles fought
- times wounded
- days served

The initial proof-of-concept successfully recovered real roster names and these persistent stats from a private save.

### Profile names

The current normal writer stores profile names as UTF-16 fixed-width fields:

- full name: 30 code units
- nickname: 10 code units

Older or alternate encodings exist in compatibility paths, so decoding should be version/format aware.

## Active soldiers

Persistent profile data is not sufficient for a complete current-state view. Live campaign state is carried by active `SOLDIERTYPE` records.

For every soldier slot, the save first writes a **one-byte active flag**.

If the slot is active, the tested format then writes:

1. an encrypted **2328-byte** soldier record;
2. a plaintext path-node count (`uint32`);
3. zero or more 20-byte path nodes;
4. a one-byte keyring-present flag;
5. when present, the keyring payload.

Each path node stores a sector id in its first four bytes; the remaining 16 bytes in the serialized node are reserved/legacy data.

The keyring supports 64 key slots. A serialized key entry is two one-byte values: key id and quantity.

### Useful current-state fields

The soldier record contains the data needed for later Field Kit milestones, including:

- display name
- team and active state
- current/max life and bleeding
- breath
- temporary stat damage
- current attributes and skills
- skill traits
- current sector X/Y/Z
- strategic assignment
- training stat
- movement group / between-sectors state
- morale
- inventory objects
- profile id
- current tactical location and tactical state

This is the section that should eventually power current inventory, health, assignment, and sector views.

## Checksums and write support

Both profile and soldier structures have checksum-related logic in the game code. Field Kit should not introduce write/edit support until it can:

1. decode a supported save;
2. serialize an unchanged logical representation;
3. reread that serialization;
4. verify checksums/invariants;
5. prove that an unchanged round trip preserves game-observable state.

The editor should write to a **new save file by default**, never destructively overwrite the source save.

For any requested edit, the safest workflow is:

```text
source save
  -> parse
  -> apply logical mutation
  -> write new save
  -> parse new save again
  -> compare requested change + unaffected invariants
  -> report success only after verification
```

## Compatibility expectations

Initial targets:

1. JA2 Reborn saves matching the tested modern Stracciatella-derived layout.
2. Normal JA2 Stracciatella Windows saves.
3. Classic JA2 saves where the version-specific layouts can be verified.

Not assumed compatible:

- JA2 1.13
- large mods that change save structures
- unknown forks with altered serialization

Unsupported saves should fail closed with a useful format/version report rather than attempting a best-effort write.

## Open research questions

Before a production parser/editor, resolve and test:

- exact section offsets/navigation for every targeted save version;
- format detection between classic Windows and historical Stracciatella Linux layouts;
- German-version encryption selector behavior and identification;
- exact inventory object serialization needed for safe item editing;
- checksum validation and regeneration rules;
- version gates inside profile/soldier extraction;
- compatibility boundaries for classic JA2 versus modern Stracciatella;
- whether Reborn-specific extensions add data outside upstream Stracciatella structures;
- preservation requirements for unknown/trailing fields when rewriting a save.

## Legal/implementation boundary

These notes describe interoperability facts learned by examining publicly available source and validating behavior against a privately supplied save. Field Kit should contain independently authored implementation code and should avoid copying JA2/Reborn source code into this repository.

The repository intentionally has no software license selected yet. A licensing review should happen before publishing substantial implementation code.
