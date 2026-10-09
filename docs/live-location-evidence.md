# Saved-state merc location — issue #65

Construction starts at `5627c4d0b77acabe864e10aaf67d7bfa624ae6eb`, on Draft #76.
Upstream authority is JA2 Reborn `743f38a6ca86c81893376c2576277db660320170`.
This is the location recorded in the imported save, **not a running-game live connection**.

## Serialization evidence

Offsets are decimal, zero-based within the decrypted normal non-Linux 2328-byte
SOLDIERTYPE, excluding its preceding active marker. Multibyte fields are little-endian.

| Field | Offset | Width / encoding |
|---|---:|---|
| fBetweenSectors | 754 | 1 / BOOLEAN, canonical 0 or 1 |
| bAssignment | 1917 | 1 / signed INT8 |
| sSector.x | 1922 | 2 / signed INT16 |
| sSector.y | 1924 | 2 / signed INT16 |
| sSector.z | 1926 | 1 / signed INT8 |

Existing facts used are life at 868/I8, status flags at 8/U32, and the validated
profile ID at 1825/U8. Stored soldier checksum is at 2208/U32.

`tools/check_live_location_offsets.py` reads exact pinned git objects, verifies blob
identity, independently counts both injection and extraction from record start through
sector z, and checks array extents, serialization types, assignments and masks. It
checks the four one-byte stat-damage fields at 820–823: v102 discards their values and
v103 retains them, but both consume the same bytes. No other version decision occurs
in this extractor. Both existing admitted v102/v103 normal read layouts are supported.
No upstream source bytes or game saves are committed.

| Pinned source | Blob |
|---|---|
| Tactical/LoadSaveSoldierType.cc | `2dbe7b38c3494b25f7628138bf28732ffa35c907` |
| Tactical/Soldier_Control.h | `4e117cbccedb15a6408b0cb0490cbb2b671191a4` |
| Strategic/Assignments.h | `54c244d918c5f4a6440e920c1491c9583c9b892a` |

## Ordered interpretation

1. Life zero, assignment 30, or SOLDIER_DEAD (0x80): Dead.
2. Assignment 32: Prisoner, displayed as “POW — location unknown”.
3. Negative life, noncanonical Boolean, or unsupported assignment: Unavailable.
4. Assignment 24 or between-sectors true: In transit. Between-sector X/Y are the last
   sector; they must never be presented as the current sector.
5. Assignment 23, DRIVER (0x08000000), or PASSENGER (0x10000000): In vehicle.
6. Remaining OFF_MAP (0x02000000): Unavailable.
7. Ordinary stationary assignments and x/y 1–16, z 0–3: sector.
8. Otherwise: Unavailable, with no numeric coordinate fallback.

Ordinary assignments are 0–19, 21–22, 25–29, and 33. ON_DUTY=20, unused
UNCONCIOUS=31, EMPTY=34, NO_ASSIGNMENT=127 and all unknown signed values are unavailable.
Low positive life does not imply death. `bInSector=false` alone does not suppress a
strategic sector. Existing PC (0x8) admission and VEHICLE (0x8000) roster exclusion stay
unchanged. Vehicle/group post-load reconstruction is not modeled; neither are town or
assignment names.

Sector letters use y (1=A through 16=P), columns use x, and underground levels append
“-z”: (9,1,0) is A9 and (9,1,1) is A9-1. The selected merc has one Location row near
identity/stats. Personnel dossiers do not currently join live state, so this slice does
not add location there.

## Trust and scope

**Location, assignment, and status fields are not soldier-checksum-covered.** Life is
covered. Existing format, roster identity, framing, and checksum checks still admit the
record; they do not authenticate its location. A synthetic encrypted-save mutation test
changes only location/assignment/status and succeeds with the unchanged checksum.

The existing shared roster scan derives an immutable `LiveMercLocation` after identity
validation. Unsupported semantics affect only location, not roster admission. Only this
derived fact crosses the live-state boundary; no raw assignment, flags, or record bytes
are added to the model or presentation. Inventory/stats parsing and editor admission,
checksum formula, and write scope are unchanged.

## Validation

Synthetic tests exercise both read versions, all surface/underground corners, ordinary
assignments, ordered special-state precedence, malformed values, signed coordinate
limits, sparse roster/profile binding, low positive life, and checksum exclusion.
Android tests check exact display strings, profile-ID joins, public presentation fields,
and the single selected-merc row. Existing stats/inventory/personnel/editor suites remain
part of the complete regression gates.

Reproduce with JDK 21, Gradle 9.5.0 and CI's Android SDK packages:

```sh
gradle :core:test :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --offline --no-daemon
python3 -B -m unittest discover -s tools/tests -v
python3 -B -c 'from tools import validate_fixture_manifest as p; p.validate(p.load_json(p.ROOT / p.MANIFEST_PATH), False)'
python3 -B tools/check_live_location_offsets.py /path/to/pinned-upstream
python3 -B tools/check_hired_stat_offsets.py /path/to/pinned-upstream
python3 -B tools/check_personnel_offsets.py /path/to/pinned-upstream
python3 -B tools/check_inventory_layout.py /path/to/pinned-upstream
git diff --check
```

Changed files (Kotlin paths use the usual `src/main` or `src/test` package roots):

- Core production: `model/LiveMercLocation.kt`, `model/LiveMercState.kt`,
  `format/NormalNonLinuxRosterDecoder.kt`.
- Android production: `MainActivity.kt`, `presentation/InspectionPresentation.kt`,
  `presentation/LiveLocationPresentation.kt`.
- Core tests: `format/NormalNonLinuxRosterDecoderTest.kt`,
  `LiveInventoryPresentationTest.kt`, `SaveEditTransactionTest.kt` (constructor update).
- Android tests: `MercSelectionWiringTest.kt`,
  `presentation/InspectionPresentationMapperTest.kt`, `presentation/InventoryTestFixtures.kt`.
- Evidence/tooling: this document and `tools/check_live_location_offsets.py`.

Local Draft-development gates (2026-10-09): focused core 25/25 and Android 24/24;
complete core 216/216 and Android 190/190, with zero failures/errors/skips; 69/69
fixture/privacy/workflow tests plus worktree fixture validation; location, hired-stat,
personnel and inventory audits; `lintDebug`, `assembleDebug`, and `git diff --check`
all passed. This is ordinary Draft validation, not immutable-head qualification or
device testing. No APK publication is included.
