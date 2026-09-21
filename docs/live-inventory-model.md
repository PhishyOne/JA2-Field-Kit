# Live inventory read model (Issue #38)

This slice reads the live SOLDIERTYPE inventory of each hired, non-vehicle
player merc in the supported normal non-Linux v103 / Build 04.12.02 domain.
It does not read inventory from the profile table. There is no editing or write
capability, inventory serialization, checksum regeneration, output placement,
bridge, Android UI change, item catalog, or 1.13/NIV support.

## API and validation authority

`Ja2SaveInspector.inspectLiveInventory(bytes)` returns
`LiveInventoryInspectionResult.Success(format, inventories)` or
`Failure(format, failure)`. Failures reuse the existing bounded presentation
codes; parser exceptions, absolute offsets, soldier checksums, rotation/key
material, and private detector evidence do not cross this boundary.

Each `MercInventoryEntry` contains the existing roster `profileIndex`, `name`,
`nickname`, and exactly 19 ordered `InventorySlot` values, each with `role` and
`objectRecord`. `InventoryObject` exposes common facts and an `InventoryPayload`.
Collections are immutable snapshots. `rawRecord` returns a new 36-byte copy on
every access; `Unknown.rawPayload` similarly returns a new 12-byte copy. Neither
the complete save nor a full SOLDIERTYPE record is retained in the public result.

The inspector uses the same checked detection and supported-layout gate as
`inspectV01`, against an untouched private input snapshot. Both presentation
methods share failure sanitization. `NormalNonLinuxRosterDecoder` contains one
private scan used by roster and inventory reads. It retains only existing roster
identity and a private, owned 684-byte inventory snapshot per validated merc.
No result is returned until all 20 player slots, the existing identity/checksum
checks, path/keyring framing, duplicate-profile rules, vehicle exclusion, and
header-count cross-check have succeeded. The checksum formula and membership
semantics are unchanged. Each call performs one scan; asking for roster and
inventory separately performs a scan for each call.

## Classification boundary

The internal `InventoryObjectParser` accepts exactly 36 bytes, rejects other
sizes with `InventoryObjectException` (`WRONG_RECORD_SIZE`, actual and expected
sizes only), snapshots the input, and uses bounded little-endian reads. It reads
common fields before resolving payload semantics.

Internal `ItemSerializationResolver` maps item ID to `ItemSerializationKind`:
`GENERIC_STATUS`, `AMMO`, `GUN`, `KEY`, `MONEY`, `ACTION_OR_SWITCH`, `OWNERSHIP`, or
`UNKNOWN`. No classifier parameter is exposed on the inspector. Possessing a
resolver grants read-only interpretation, never write authority. Tests use only
project-authored synthetic IDs; production has no item metadata catalog.

ID 0 with count 0 yields `Empty` without consulting the resolver. Every other
production record yields `Unknown`. Noncanonical ID 0/count nonzero and ID
nonzero/count 0 combinations also remain `Unknown`, even with a test resolver;
all their facts and bytes are preserved. Payload byte patterns never select a
class. Unknown or invalid IDs are not normalized. Serialized weight is not
recalculated. Modern Stracciatella normalization of unknown IDs and weight is
explicitly not adopted for Field Kit read preservation. `Empty` also retains
any residual payload/reserved bytes through its parent object's raw snapshot.

## Canonical slots

There are exactly 19 records. The first begins at SOLDIERTYPE-relative byte 12;
record `i` begins at `12 + 36 * i`. The block covers bytes 12..695 inclusive.
The keyring tail is a separate structure, not an extra inventory slot, and its
contents remain out of scope. Existing keyring framing validation is retained.

| Index | Role |
| --- | --- |
| 0 | HELMET |
| 1 | VEST |
| 2 | LEGS |
| 3 | HEAD_1 |
| 4 | HEAD_2 |
| 5 | MAIN_HAND |
| 6 | OFF_HAND |
| 7 | BIG_POCKET_1 |
| 8 | BIG_POCKET_2 |
| 9 | BIG_POCKET_3 |
| 10 | BIG_POCKET_4 |
| 11 | SMALL_POCKET_1 |
| 12 | SMALL_POCKET_2 |
| 13 | SMALL_POCKET_3 |
| 14 | SMALL_POCKET_4 |
| 15 | SMALL_POCKET_5 |
| 16 | SMALL_POCKET_6 |
| 17 | SMALL_POCKET_7 |
| 18 | SMALL_POCKET_8 |

## Object record map

All offsets below are relative to a single 36-byte object record. Unsigned bytes
are exposed as `Int` in 0..255; signed bytes as `Int` in -128..127; u16 values as
`Int`; u32 money as `Long`. Trap uses the specified signed byte, preserving
negative values. `used` is the exact unsigned byte, not a normalized Boolean.

| Bytes | Meaning |
| --- | --- |
| 0..1 | itemId, u16 LE |
| 2 | objectCount, u8 |
| 3 | reservedByte3, u8 |
| 4..15 | class-dependent payload, 12 bytes |
| 16..23 | four attachment item IDs, u16 LE, in order |
| 24..27 | four corresponding attachment statuses, signed bytes |
| 28 | flags, u8 |
| 29 | mission, u8 |
| 30 | trap, signed byte |
| 31 | imprint, u8 |
| 32 | serializedWeight, u8 |
| 33 | used, u8 |
| 34..35 | finalReservedBytes, two unsigned bytes |

The inventory contribution to the existing MercChecksum is only item ID plus
object count for each slot. This slice changes neither checksum validation nor
any writer/checksum capability.

## Payload facts

Reserved lists preserve unsigned byte values in serialized order. Bomb status
uses the same signed condition representation as other status fields. The
specification leaves action control-byte signedness unspecified, so detonator,
delay/frequency, owner, action value, and tolerance are exposed as opaque
unsigned bytes, with no inferred meaning or normalization.

| Variant | Fields at object-relative offsets | Reserved bytes |
| --- | --- | --- |
| Empty | ID 0 and count 0; payload uninterpreted | All retained in rawRecord |
| GenericStatus | 4..11: eight signed statuses | 12..15 |
| Ammo | 4..11: eight unsigned shots-left counts | 12..15 |
| Gun | 4: signed status; 5: unsigned ammo type; 6: unsigned shots left; 8..9: loaded magazine ID u16 LE; 10: signed ammo/jam status | 7 (separate field), 11..15 |
| Key | 4..9: six signed statuses; 10: unsigned key ID | 11..15 |
| Money | 4: signed status; 8..11: amount u32 LE | 5..7 and 12..15 (separate lists) |
| ActionOrSwitch | 4: signed bomb status; 5: detonator byte; 6..7: bomb ID u16 LE; 8: delay/frequency byte; 9: owner byte; 10: action value byte; 11: tolerance byte | 12..15 |
| Ownership | 4: unsigned owner profile; 5: unsigned civilian group | 6..15 |
| Unknown | Exact uninterpreted bytes 4..15 | Retained without interpretation |

## Source-expression and review boundary

These narrow slot/field/payload tables and their Kotlin interpretation are
project-authored from the supplied Issue #38 interoperability fact specification,
which reports independent research against current Stracciatella and pinned
JA2-Reborn. No upstream implementation, comments, control flow, item table,
names, descriptions, or assets were fetched or copied for this construction.

The source-derived factual tables and interpretation require a separate
**Issue-8-style licensing review before merge**, under
[licensing-boundary-review.md](licensing-boundary-review.md). Construction and
synthetic tests do not satisfy that review or imply a license determination.

## Construction validation

All core main/test Kotlin and Java sources compile with the cached Kotlin 2.4.10
and JDK 21 toolchain. The cached JUnit Platform/Jupiter harness passes 26
resource-independent tests: 10 object-parser tests, two inventory presentation
privacy/ownership tests, and 14 inherited admission, primitive, detector-mutation,
and JVM authority tests. The mutation test now includes `inspectLiveInventory`.
`git diff --check` passes.

The existing roster test suite now also covers distinct full-record sentinels
at all 19 positions for two mercs, profile binding, source preservation, empty
records, presentation failures, and identical inventory rejection for every
existing roster corruption/path/keyring/checksum case. These resource-dependent
tests compile but remain unexecuted locally: the unchanged fixture materializer
rejects both generated resource requests at the exact-snapshot generator gate.
No fixture-policy bypass or alternative fixture source was used.

Focused Gradle tests and full `:core:test --no-daemon --offline` were attempted
with Gradle 9.5.0/JDK 21; both fail before configuration with
`Could not determine a usable wildcard IP for this machine` while creating
`FileLockContentionHandler`. Cached compilation and focused execution are
supplemental evidence only. CI with working fixture isolation is authoritative
for the complete suite and live-inventory integration claims.
