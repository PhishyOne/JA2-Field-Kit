# Issue #72: bounded inventory construction

This slice adds `ClearSlot` and `SetSimpleItem` to the existing source-bound
transaction. Production capability remains disabled, v102 remains read-only,
and Android has no editing or placement path. All tests use project-authored
synthetic data. No private save was opened, copied, hashed or modified.

## Closed admission

Both operations target **one uniquely hired, non-vehicle player merc**, in
canonical slot **7, 8, 9 or 10** (the four big pockets). Indices outside 0..18
are structurally invalid; other canonical roles are deliberately unsupported.
The expected item ID/count/status must agree in the profile and live object.
The request also binds the exact source size and SHA-256, covering all other
object facts, slot identity and campaign state.

| Operation | Admitted current state | Result |
| --- | --- | --- |
| `ClearSlot` | Canonical empty, or one plain item from the table below | Empty in both representations |
| `SetSimpleItem` | Canonical empty, or one plain item from the table below | One requested plain item, including replacement or condition change |

| Item ID | Upstream identity | Exact class | Base weight / pocket capacity metadata |
| ---: | --- | --- | --- |
| 201 | FIRSTAIDKIT | IC_MEDKIT (4096) | 5 / 4 |
| 202 | MEDICKIT | IC_MEDKIT (4096) | 18 / 0 |
| 203 | TOOLKIT | IC_KIT (8192) | 50 / 0 |

These are three individually admitted definitions, **not all members** of either
class. The fixed ID guard is independent of the label catalog. Count must be
exactly **1** and status **1..100**. These status bounds come from `CreateItem`;
Field Kit rejects invalid inputs instead of applying the game's fallback.
Explicit clear uses item/count/status 0/0/0. `SetSimpleItem` cannot use item 0.
No stacks are admitted even where `CreateItems` can construct one.

An occupied source must match the complete canonical 36-byte representation:
item/count/status, zero remaining status bytes, zero attachments, zero flags,
mission/trap/imprint/used fields and zero padding, with serialized weight 255.
Empty sources require zero in every byte except weight, which may be 0 or 1.
That narrowly accepts zero-initialized empty slots as well as pinned serialized
empty slots. Clearing either empty representation preserves it byte-for-byte.
An identical set also preserves the whole save byte-for-byte after full reparse.
Noncanonical occupied weights (including legacy 0.1kg representations) are
rejected; this is not normalization of arbitrary historical saves.

The profile undroppable byte is preserved. For the four admitted pockets its
bits are 4..7 respectively, as established by the upstream slot mapping.
A set target bit or live object flag bit 0 rejects either operation, including
no-ops. No attempt is made to clear that flag. Other profile bits are untouched.

## Upstream investigation and reassessment

Authority is JA2-Reborn revision
[`743f38a6ca86c81893376c2576277db660320170`](https://github.com/RealTommyGreen/JA2-Reborn/tree/743f38a6ca86c81893376c2576277db660320170),
the exact revision already pinned by this repository. Public local git objects
were inspected and compared with the working tree; no game/save data was read.
This records interoperability facts, not copied upstream implementation.

Three substantive findings required reassessment before implementation:
class-dependent union payloads; weight recomputation during serialization;
and equipment/slot compatibility beyond byte framing. The resulting slice is
restricted to the three single kits in big pockets. It does not broaden into
an arbitrary item editor or equipment simulation.

Evidence, all paths relative to that revision:

| Source | Blob | Reviewed facts |
| --- | --- | --- |
| `src/game/Tactical/LoadSaveMercProfile.cc` | `3b38fe4c9d873b2bcfd0872178bc251ad066878e` | Inject/extract profile arrays, undroppable, money, checksum |
| `src/game/Tactical/LoadSaveSoldierType.cc` | `2dbe7b38c3494b25f7628138bf28732ffa35c907` | 19 object records, soldier framing and checksum |
| `src/game/Tactical/LoadSaveObjectType.cc` | `98fcbb367ddf6546e738bf8bed8722bd0bc2ef77` | Inject/extract class dispatch, padding, recomputed weight |
| `src/game/Tactical/Item_Types.h` | `63477c71f30c0acaf67bb9b3ab7857cd278178f8` | Union members, 8-object/4-attachment limits, flags and classes |
| `src/game/Tactical/Items.cc` | `1643c31cc3a283ef749431deb84a3ee5482b1813` | CreateItem/CreateItems, DeleteObj, RemoveObjectFromSlot, profile placement/removal, Weight and slot restrictions |
| `src/game/Tactical/Soldier_Create.cc` | `ae33325c12bb3da7aa07869f88da5dd8c2a40ff9` | Profile-to-live initialization, special keys/money/imprint and undroppable mapping |
| `src/externalized/ItemModel.cc` | `4cff413d93a5cf0b411f29a504b9ffe6388aa9a8` | Default zero weight, item flags/metadata loading |
| `src/externalized/DefaultContentManager.cc` | `03d360b8aada60e6f3df372b62e4007e1aab45ff` | Construction of NOTHING and externalized item definitions |
| `src/sgp/LoadSaveData.cc` | `4b7783f447aa17b0adbd97eb293729e8540efcbd` | Writer skip bytes are zero-filled |
| `assets/externalized/items.json` | `0dadf618bc66f85d97aa72604c3e1d96aed3e43e` | Three IDs' exact class, weight, capacity and absent default-undroppable/attachment flags |

`Items.cc` lines 2570–2640 establish deterministic creation for these definitions:
a fresh object has one item and one condition, with the remaining object fields
zero. None of the three definitions has a default-undroppable flag. `DeleteObj`
(lines 870–873) resets the object, and `RemoveObjectFromSlot` (2287–2300) uses
that reset. Profile removal (2930–2950) resets the inventory ID/status/count.
Profile placement (2850–2925) and soldier initialization (1980–2029) establish
the profile/live mapping. The editor uses explicit slot identity and requires
agreement; it does not imitate upstream's search-by-item removal.

`Weight` (819–840) derives grams from definition weight, with extra rules for
attachments and loaded guns. `InjectObject` serializes that value clamped to
1..255, despite the historical weight field's 0.1kg description. Thus all three
kits serialize weight **255**, and NOTHING (default weight 0) serializes **1**.
Writing 36 zero bytes on clear would not reproduce this pinned serializer.
The otherwise-zero fresh/delete forms follow creation/reset semantics and the
serializer's explicit zero padding; they are not guessed union payloads.

## Serialization facts and verification

All offsets are relative to decrypted normal non-Linux records.

| Record / fact | Offset / size |
| --- | --- |
| Profile record | 716 bytes |
| Profile status array | 358 + slot, unsigned byte, 19 entries |
| Profile count array | 377 + slot, unsigned byte, 19 entries |
| Profile undroppable | 412, unsigned byte |
| Profile item IDs | 416 + 2×slot, LE unsigned 16-bit, 19 entries |
| Profile money (preserved) | 684..687 |
| Profile checksum | 696..699 |
| Soldier record | 2328 bytes |
| Soldier inventory | [12,696), 19 × 36-byte slots |
| Soldier checksum | 2208..2211 |

Within an object: item at 0..1; count at 2; padding at 3; class-dependent union
at 4..15; four attachment IDs at 16..23 and their statuses at 24..27; flags at
28, mission at 29, trap at 30, imprint at 31, serialized weight at 32, used at
33 and padding at 34..35. Plain status occupies 4..11 (eight signed bytes),
followed by four padding bytes. Only the first status is nonzero in our subset.

The existing live parser agrees with this layout but defaults to **Unknown**
for occupied payload classification. Its resolver is read authority only.
The GOG item-description catalog supplies labels, not classes, weights, pocket
limits, attachment rules or mutation authority. Neither is promoted into a
write catalog. `MercProfile` now exposes an immutable parsed 19-slot list of
unsigned ID/count/status facts and the undroppable byte through the existing
public profile parser; Android presentation is unchanged.

The engine still rewrites exactly two fixed records. It recalculates both
checksums and reparses through the three existing public inspection surfaces.
The verifier compares all 170 profile models (including all inventory facts),
ordered roster/live models, campaign facts, format and record locations. Only
four target profile bytes, the target 36-byte object and the two checksums may
differ in plaintext. Every other byte in these records, and all bytes outside
their possible encryption suffixes, must match. It independently rechecks
source admission and the requested full candidate object. Checksums alone do
not cover status, attachments or all auxiliary fields; full preservation and
object comparison are mandatory. Corruption with freshly repaired checksums
is rejected. Failure has no candidate accessor.

Inventory requests are immutable scalar records plus one fixed `ExpectedSlot`.
Their canonical digest has version 3 and exactly 76 bytes: LE version, source
SHA-256 bytes, source size, operation tag (2 clear / 3 set), profile, slot,
expected ID/count/status, desired ID/count/status. Plan is `inventory-v1`,
model version 3. The existing stat encoding/plan remains unchanged.
The 16 MiB ceilings, single-operation rule, fixed writer and disabled capability
are unchanged. The new read facts add exactly 170×19 scalar slot objects per
profile parse; public inspection calls and recovery budgets are unchanged.

## Deliberately rejected/deferred

- **Guns, ammo and launchers:** gun creation uses weapon/calibre/default-magazine
  metadata, ammo type and shots; magazines use capacity rather than condition.
  Launchers and explosive guns have further loaded-ammo behavior.
- **Money, gold/silver:** amount is separate from condition; profile `uiMoney`
  participates in initialization/placement. No amount or balance is changed.
- **Keys:** key ID is distinct from item ID; profile initialization has
  character-specific rules. Keyring tails are preserved, not inventory slots.
- **Bombs, traps, action/switch/ownership objects:** detonator, frequency/delay,
  owner and other union fields cannot be inferred from a simple status.
- **Attachments, imprinted/flagged/used objects:** the complete plain-object
  guard rejects these even on one of the admitted kit IDs. Replacement never
  carries a stale payload into a new class.
- **Stacks:** `CreateItems` supports up to eight entries, but per-pocket limits
  and profile's single status versus per-object conditions need a separate
  operation/precondition design. This slice admits only count 1.
- **Armor, face equipment, other tools/utilities and other classes:** some may
  have deterministic creation, but their individual metadata and equipment
  effects are not qualified here. No blanket IC_MEDKIT/IC_KIT/IC_MISC admission.
- **LBE/modded content:** no LBE class appears in this pinned class definition.
  No 1.13 or external content-pack identity is inferred from save layout.
- **Hands, worn slots and small pockets:** equipment state and slot capacity
  need additional qualification. All writes, including empty clears, reject
  these roles in this first slice.

Changing only an item ID leaves bytes interpreted under the old class in the
new union, possibly fabricating ammo, amounts, keys or trap state. Generating a
zero-filled object also misses deterministic fields such as magazine capacity
and serialized weight. Neither technique is an arbitrary-item implementation.

## Reproducible construction checks

```sh
python3 -B tools/check_inventory_layout.py /path/to/pinned/JA2-Reborn
python3 -B tools/check_hired_stat_offsets.py /path/to/pinned/JA2-Reborn
```

The inventory audit reads immutable public git blobs, cumulatively checks
profile/live framing and the generic object serializer, then checks the three
JSON item definitions. It retains no upstream source. It checks five profile
offsets, live inventory [12,696), eleven object field offsets/36-byte extent,
and three item definitions. The original 54-stat-offset audit also passes.
Two isolated audit-script counting defects were corrected during construction:
a missing two-byte array extent and omission of signed bytes in a money-tail
count. The mutation implementation passed its first focused run unchanged.

The first focused transaction/authority run passed **78 tests** (75 transaction,
3 authority). Final validation with JDK 21, cached Gradle 9.5.0 and the pinned Python environment:

- `gradle :core:test :android-app:testDebugUnitTest --offline --no-daemon`:
  **206 core tests in 19 classes**, **101 Android unit tests in 18 classes**;
  zero failures, errors or skipped tests.
- `python -B -m unittest discover -s tools/tests -v`: **69 passed**.
- Both deterministic upstream audits: passed (inventory counts above, 54 stat offsets).
- `git diff --check`: passed.
- Fresh header/profile fixture materialization passed through the unchanged
  validator/Bubblewrap pipeline. No private fixtures were used.

Gradle required host execution for its lock socket; the restricted attempt
failed before building. The fixture/privacy suite also used its required host
namespace path. No test, parser, checksum, preservation or admission rule was
weakened to accommodate the environment. No remote CI or game-load result is
claimed for this commit.

## Next-slice gates

This is synthetic construction evidence, not production/game-load qualification.
Content-pack identity, legacy-weight policy, runtime interactions (including
active medical/repair assignments), and broader slot/object semantics require
separate evidence before expanding the operation set. Strict profile/live
agreement will reject saves where inventories have legitimately diverged.
Production memory/latency qualification, authorized real-save/game-load testing,
licensing review and qualified Save As placement remain required. None is
supplied by this audit or by passing synthetic tests.
