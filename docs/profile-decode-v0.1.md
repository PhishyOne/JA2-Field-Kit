# Normal profile decode v0.1

This slice composes the existing whole-save framer, save-derived rotation
recovery, and explicit-table block decryptor, then parses a deliberately small
read-only model. It supports only the normal non-Linux saved-game version 103 /
Build `04.12.02` path already accepted by the framer. It does not detect a save
family.

## Serialization evidence

The format facts were rechecked directly at immutable JA2 Reborn commit
`743f38a6ca86c81893376c2576277db660320170`:

- `src/game/Tactical/Soldier_Profile_Type.h` defines `NUM_PROFILES` as 170,
  `NAME_LENGTH` as 30, and `NICKNAME_LENGTH` as 10.
- `src/game/Tactical/LoadSaveMercProfile.h` defines the normal serialized
  profile size as 716 bytes.
- `src/game/Tactical/LoadSaveMercProfile.cc` explicitly serializes every field
  and asserts 716 consumed bytes; this sequence, rather than native structure
  layout, establishes the offsets below.
- `src/sgp/LoadSaveData.h` and `.cc` establish the fixed-code-unit string read;
  the normal non-Linux path uses UTF-16, while the excluded historical Linux
  path uses UTF-32 and a 796-byte record.
- `src/game/SaveLoadGame.cc` reads and verifies each profile separately, and
  the existing recovery evidence documents the per-record encryption reset.

All offsets are zero-based within one decrypted record:

| Model field | Serialized field | Offset | Encoding |
| --- | --- | ---: | --- |
| name | `zName` | 0 | 30 UTF-16LE code units, first NUL terminates |
| nickname | `zNickname` | 60 | 10 UTF-16LE code units, first NUL terminates |
| medical | `bMedical` | 261 | signed 8-bit |
| strength | `bStrength` | 296 | signed 8-bit |
| lifeMax | `bLifeMax` | 297 | signed 8-bit |
| life | `bLife` | 334 | signed 8-bit |
| dexterity | `bDexterity` | 335 | signed 8-bit |
| explosives | `bExplosive` | 339 | signed 8-bit |
| leadership | `bLeadership` | 341 | signed 8-bit |
| experienceLevel | `bExpLevel` | 352 | signed 8-bit |
| marksmanship | `bMarksmanship` | 353 | signed 8-bit |
| wisdom | `bWisdom` | 355 | signed 8-bit |
| agility | `bAgility` | 405 | signed 8-bit |
| mechanical | `bMechanical` | 411 | signed 8-bit |

There is no independently evidenced serialized profile-ID field. The public
`profileId` is therefore exactly the record index `0..169`.

## API and failure behavior

`Ja2SaveInspector.parseBuild041202NormalNonLinuxProfiles` is the public
whole-save entry point. For each invocation it:

1. frames the exact 121720-byte encrypted table;
2. recovers one invocation-owned 49-byte rotation table;
3. decrypts 170 separate 716-byte operations, resetting feedback and rotation
   position through the existing decryptor on each record; and
4. parses and returns an unmodifiable list of exactly 170 `MercProfile` values.

`NormalMercProfileParser.parseBuild041202` is the lower-level boundary for an
already-decrypted table. It accepts exactly 121720 bytes, snapshots them before
reading, and rejects malformed UTF-16 surrogate sequences. Its structured
`MercProfileParseException` reports only size, record, field, and relative byte
offset metadata. Signed fields are returned without range validation or
clamping. The existing structured header, framing, recovery, and decryptor
failures propagate unchanged.

Bytes outside the listed fields remain opaque: this is not a full structure
translation and there is no write or round-trip API. The rotation table is a
local variable scoped to one decode; it is never placed in a provider or global
cache. No production rotation table is shipped.

## Roster boundary

The table contains all 170 profiles, not the player's hired roster. Although
the profile structure contains assorted status and flags, the pinned evidence
does not independently establish a profile-only predicate that reproduces
current hired/player membership. Upstream code uses active `SOLDIERTYPE`
records for such runtime membership checks.

The separate public
`Ja2SaveInspector.parseBuild041202NormalNonLinuxRoster` method now performs the
justified membership-only crossing for the exact supported layout. One internal
invocation context frames the save and recovers the rotation once; profile and
soldier records use that same invocation-local table, and every record remains
a separate decrypt operation/reset. The table is neither returned nor cached.

The crossing scans canonical slots 0 through 19, requires the plaintext and
inner active values to agree, verifies soldier ID, `OUR_TEAM`, `SOLDIER_PC`, and
the mandatory wrapping `u32` soldier checksum, excludes vehicles, requires
unique profile indices `0..169`, and joins by record index. Path and keyring
payloads are bounds-checked framing only. Header player-merc count is checked
against the final derived non-vehicle count after all slots; it is not used to
decide how many slots to read.

Full active-condition parsing remains outside v0.1: no current life or other
condition, assignment, sector, tactical inventory, path, or key semantics are
modeled or returned. The stat and inventory bytes involved in the source
checksum remain opaque integrity inputs. Roster `MercStats.health` is the core
profile attribute `bLifeMax`; the separately parsed profile `bLife` is not used
as active-soldier condition. This method is explicitly layout scoped and makes
no save-family-detection claim. Issue #6 remains open pending candidate and
authorized private acceptance of the known roster.

## Public tests and privacy

Tests use only the admitted project-authored synthetic header and recovery
vectors. A test-owned forward transform assembles a complete synthetic save
with valid synthetic UTF-16 names, then exercises framing, recovery,
per-record decryption, and parsing through the public inspector. Separate
test-owned numeric format oracles anchor every parsed offset, exact 170-record
iteration, signed-byte behavior, exact-size rejection, malformed UTF-16, and a
one-byte wrong-alignment rejection. No real save bytes, private names, private
paths, production rotations, binaries, or extracted proprietary content are
included.

Roster tests add a test-owned soldier serializer with literal offsets, checksum
recurrence, and forward encryption; production parser constants are not reused.
They cover sparse and exactly 18-member teams, vehicle exclusion, profile joins,
record resets, every identity/checksum failure, malformed markers and tails,
large path counts, truncation, duplicate/invalid profiles, canonical team range,
header-count cross-checking, immutability, and payload-free diagnostics. They do
not interpret condition, inventory, path, or key semantics.
