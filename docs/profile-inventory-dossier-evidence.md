# Profile inventory dossiers — issue #78

Construction starts at `dbd3d38a48cfc7580d702347d2274054b356d02a` on Draft #76.
Public source authority remains JA2 Reborn
`743f38a6ca86c81893376c2576277db660320170`.

## Shared slot order and serialized fields

`Soldier_Control.h::InvSlotPos` starts at zero and runs through Helmet, Vest,
Legs, Head 1, Head 2, Main hand, Off hand, Big pocket 1–4, Small pocket 1–8.
Both the profile's 19-element arrays and `SOLDIERTYPE::inv[NUM_INV_SLOTS]`
use this order. `Soldier_Create.cc::CopyProfileItems` reads profile item, count
and status at index `i` to create an object in `s.inv[i]` in its non-player
branch. This establishes index correspondence, not ongoing value equality.
The soldier serializer iterates the live array from beginning to end.

`tools/check_inventory_layout.py` now independently checks that enum sequence,
its zero origin, array declarations, traversal and same-index creation against
pinned git objects. It also retains its cumulative profile offset audit:

| Profile field | Offset | Serialized representation |
|---|---:|---|
| `bInvStatus[19]` | 358 | U8 |
| `bInvNumber[19]` | 377 | U8 |
| `ubInvUndroppable` | 412 | U8, display deferred |
| `inv[19]` | 416 | U16 little-endian |

Offsets are within the existing 716-byte normal non-Linux profile. The existing
v102/v103 admission and parser are unchanged. Synthetic encrypted tests exercise
all slots and unsigned boundaries in both versions. No new save traversal or
raw fields are added.

Relevant source blobs: `Soldier_Control.h`
`4e117cbccedb15a6408b0cb0490cbb2b671191a4`, `Soldier_Profile_Type.h`
`04c52fbb558e759d5396c9f810a72b31d9872a02`, `Soldier_Create.cc`
`ae33325c12bb3da7aa07869f88da5dd8c2a40ff9`, `LoadSaveMercProfile.cc`
`3b38fe4c9d873b2bcfd0872178bc251ad066878e`.

## Lifecycle and presentation

Profile entries are recorded character-profile facts and may differ from tactical
inventory. They are not described as historical or compared with live objects.
Current squad membership remains derived only from the validated roster join.
Current-squad dossiers retain no profile inventory rows and display:
“Current tactical inventory is shown in this character's roster detail.”
Existing roster detail continues to show live inventory unchanged.

Other dossiers show **Profile inventory**, preceded by:
“Recorded in this save's character profile. These entries may differ from the
character's tactical inventory.” Only exact `0/0/0` slots are omitted. An empty
list displays “No items recorded in profile inventory.” Any source extent other
than exactly 19 makes this section unavailable before assigning roles; there is
no padding or truncation.

Each displayed entry retains its canonical role and numeric item ID, count and
status. The UI shares the live inventory role-label function. Count and **Profile
status** preserve raw 0–255 values: no zero-to-one substitution, clamping,
percentage, progress bar or item-class interpretation. Item IDs always remain
visible, including unknown IDs and ID 0.

“Unusual recorded values; shown unchanged.” appears if item is nonzero and count
is zero, item is zero with nonzero count/status, or count exceeds eight. Eight
is ordinary for this display policy; this is not validation of pocket capacity
or item metadata. These entries neither fail inspection nor authorize repair.
Undroppable interpretation is deferred.

## Catalog and ownership boundary

`PersonnelPresentation` retains an immutable snapshot of numeric presentation
facts, with no `MercProfile`, parser, raw-byte or catalog-object retention.
Rendering uses the existing ViewModel-owned CatalogSession / CatalogPresentation /
CatalogNames lifecycle. Optional sanitized names are labelled Base catalog and
are decoration only. Catalog replacement changes rendered names without mapping
or parsing the save again. ID 0 never receives name decoration. Catalog content
cannot validate count, status, slot, item class or editability.

There are no new edit actions, imports, persistence, exports, permissions or
changes to editor transactions, roster coherence, catalog admission or privacy
checks. No private saves are used for construction or verification.

## Construction validation — 2026-10-09

Using local JDK 21, Gradle 9.5.0, pinned Android SDK and the existing CPython 3.12
fixture-validator environment:

- Focused `:core:test --tests '*Ja2SaveInspectorV01Test'`: 8 passed.
- Focused `:android-app:testDebugUnitTest --tests '*ProfileInventoryPresentationTest'
  --tests '*Personnel*Test'`: 11 passed.
- Complete `:core:test`: 219 passed, including existing editor transactions.
- Complete `:android-app:testDebugUnitTest`: 198 passed, including existing
  personnel, economics, location, catalog, editor and privacy boundary tests.
- `:android-app:lintDebug`: passed, zero errors, nine warnings.
- `:android-app:assembleDebug`: passed; local build only.
- `python3 -B -m unittest discover -s tools/tests -v`: 69 passed.
- Worktree fixture validation with `check_local_files=False`: passed.
- Inventory, personnel, live-location and hired-stat pinned-source audits: passed.
  Additional inventory negative probes rejected changed enum origin, array extent
  and profile-to-live index correspondence.
- `git diff --check`: passed.

All test suites had zero failures/errors/skips in their final runs. One synthetic
fixture defect was repaired: inventory changes required updating its recorded
checksum. Production checksum and admission checks were unchanged. Initial tool
setup failures were resolved by using the existing validator environment and
permitted local Gradle/bwrap access. No push, publication, device installation,
private-save inspection, icon change or editor expansion was performed.
