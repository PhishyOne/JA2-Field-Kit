# Profile economics — issue #77

Construction starts at `5e7f038e300b78ec9aedcc7dc1af7d08391cdc12` on Draft #76.
Authoritative main: `79751a7721357cfaada4ce6a896d1adcfd6dfee9`.
Evidence authority: JA2 Reborn `743f38a6ca86c81893376c2576277db660320170`.
The audit reads immutable local Git objects at that revision, never saves. No upstream
source, metadata identity table, game assets, or private saves are committed.

## Exact admitted facts and labels

Offsets are decimal within each normal non-Linux 716-byte profile. The 170-entry table
index is the profile ID. Multibyte integers are little-endian. `ProfileEconomicsFacts`
is immutable and contains only the nine raw facts below. The parser reads every record,
regardless of category; Android alone controls presentation.

| Serialized field | Offset | Type → Kotlin | Exact row label | Shown for |
|---|---:|---|---|---|
| `uiDayBecomesAvailable` | 292 | UINT32 → Long | Availability delay counter (days, profile) | AIM, MERC |
| `sSalary` | 332 | INT16 → Int | Daily salary (profile) | AIM, MERC |
| `uiWeeklySalary` | 540 | UINT32 → Long | Weekly salary (7-day package) | AIM |
| `uiBiWeeklySalary` | 544 | UINT32 → Long | Two-week salary (14-day package) | AIM |
| `bMedicalDeposit` | 548 | INT8 → raw Int | Medical deposit required | AIM |
| `sMedicalDepositAmount` | 552 | UINT16 → Int | Medical deposit amount (profile) | AIM |
| `usOptionalGearCost` | 574 | UINT16 → Int | Optional gear cost (profile) | AIM |
| `bMercStatus` | 652 | INT8 → raw Int | Recorded profile status | AIM, MERC |
| `iMercMercContractLength` | 704 | INT32 → Int | M.E.R.C. billing days since payment (profile) | MERC |

`uiTotalCostToDate` at 708 (UINT32 → Long) remains solely in the career model and
**Record → Total cost paid (salary)**. There is no economics copy.

The dossier section is **Profile economics**, with this note above its rows:

> Recorded profile values. Hiring and current contract details depend on other game state.

Other categories show their category but no new economics section. RPC daily salary
is used upstream but intentionally omitted from this agency-only slice. The compact
current-squad screen is unchanged.

## Standard category, not serialized category

Every named dossier shows **Standard profile category: …**. `StandardProfileCategory`
resolves the following complete default mapping from profile ID:

| IDs | Category | Display label |
|---|---|---|
| 0–39 | AIM | A.I.M. profile |
| 40–50 | MERC | M.E.R.C. profile |
| 51–56 | IMP | I.M.P. profile |
| 57–74 | RPC | RPC profile |
| 75–159 | NPC | NPC profile |
| 160–163 | VEHICLE | Vehicle profile |
| 164–169 | NOT_USED | Reserved profile |

These seven ranges reproduce all 170 pinned `mercs-profile-info.json` entries. The
upstream `MercType` enum assigns NOT_USED=0, AIM=1, MERC=2, IMP=3, RPC=4, NPC=5,
VEHICLE=6. Field Kit uses symbolic categories, not enum ordinals as serialized values.
Upstream predicates depend on `MercProfileInfo::load` and external metadata.
**Mods/external metadata could override category outside this pinned default content.**
Save admission does not establish that overrides were absent. This category does not
establish a hireable listing, employment, or squad membership.

## Status and raw-value rules

| Raw | Constant | Display |
|---:|---|---|
| 0 | MERC_OK | Ready status recorded |
| -1 | MERC_HAS_NO_TEXT_FILE | Missing dialogue text |
| -2 | MERC_ANNOYED_BUT_CAN_STILL_CONTACT | Annoyed; contact still permitted (AIM only) |
| -3 | MERC_ANNOYED_WONT_CONTACT | Annoyed; contact refused (AIM only) |
| -4 | MERC_HIRED_BUT_NOT_ARRIVED_YET | Hired, awaiting arrival status |
| -5 | MERC_IS_DEAD | Dead status |
| -6 | MERC_RETURNING_HOME | Returning home status |
| -7 | MERC_WORKING_ELSEWHERE | Working elsewhere status |
| -8 | MERC_FIRED_AS_A_POW | Fired while POW |

The parenthetical AIM qualification above is a presentation rule, not appended label
text. For MERC, -2/-3 display `Unknown (raw: N)`. Unknown negatives -128…-9 use the
same raw label. Positive values 1…127 display `Positive profile status (N)`, never
remaining days or current contract length. Hiring and arrival write contextual lengths
into the status byte, so it cannot independently describe live contract state.

Negative salary or billing days display `Uninterpreted (raw: N)` without clamping.
Deposit 0/1 display No/Yes; all other signed values display `Unknown (raw: N)`.
The deposit amount remains independent even when the flag is zero or unknown.
Unsigned values retain their full U16/U32 ranges. Zero values stay literal.

The daily handler decrements `uiDayBecomesAvailable`, including outside the agency-only
branch. It is a delay counter, not a date. Zero does not establish available, alive,
unlocked, or unhired. Weekly and two-week salary values are package totals. Profile
medical amount is not the soldier's paid deposit or refundable amount. Gear cost can
be cleared during hiring and is not current inventory value. M.E.R.C. billing days
accumulate until settlement and can outlive employment.

No hireability/current-employment flag, remaining days, contract expiry, availability
date, refund, debt calculation, or write authority is introduced. Current squad markers
continue to come exclusively from the validated live-roster join. No new persistence,
raw-byte exposure, permissions, report/export fields, or source snapshot is introduced.

## Source evidence and version admission

All links are pinned to the authority revision:

- [Extractor/injector](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/LoadSaveMercProfile.cc): exact layout and signedness.
- [Profile declarations/status constants](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Soldier_Profile_Type.h): raw field declarations and status IDs.
- [Default metadata](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/assets/externalized/mercs-profile-info.json), [metadata enum](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/externalized/mercs/MercProfileInfo.h), [predicates](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/externalized/mercs/MercProfile.cc#L47-L89), [content loading](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/externalized/DefaultContentManager.cc#L1241-L1255): external category dependency.
- [Save loading](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.cc): common profile load at lines 1291–1306, pre-v77 bookkeeping migration at 1145–1150, pre-v83 salary-cost backfill at 1398–1409.
- [Daily handling](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Strategic/Strategic_Merc_Handler.cc#L209-L329): RPC salary and broader availability countdown.
- [AIM pricing](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Laptop/AIMMembers.cc#L1371-L1393): salary packages and separate deposit/equipment charges.
- [Hiring](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Merc_Hiring.cc#L114-L200): status assignment, soldier deposit capture, billing initialization; arrival also assigns status at line 315.
- [M.E.R.C. daily bookkeeping](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Laptop/Mercs.cc#L513-L532), [account settlement](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Laptop/Mercs_Account.cc#L219-L287): billing days, not remaining contract days.
- [Departure/refunds](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Strategic/Merc_Contract.cc#L659-L731) and [gear clearing](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Soldier_Create.cc#L2040-L2053): limits on interpreting profile amounts.

Both admitted normal Build 04.12.02 versions 102 and 103 use the same profile serializer
and load semantics. Neither pre-v77 bookkeeping migration nor pre-v83 total-cost backfill
applies. Existing header/layout/rotation/roster admission and v103-only editing remain
unchanged. No admission for other producers or Linux layouts is added.

`tools/check_personnel_offsets.py` verifies full extract/inject layout parity, all candidate
offsets/types, exact status constants, all 170 metadata entries against seven ranges,
metadata enum/dependency, and both migration thresholds. Ten negative probes mutate
padding or the signedness of each new field and must fail. The audit prints pinned blob
IDs; metadata blobs are `be9ad89df915be352d48b0f0f907802f5c757c24` (JSON),
`1479c1976f104c941a532294396791d54946632a` (enum),
`fca0822594453b552e0f752cb6947cce132a3929` (predicates), and
`03d360b8aada60e6f3df372b62e4007e1aab45ff` (content manager).
Other shared source blob IDs are recorded in [personnel evidence](personnel-dossier-evidence.md).

## Reproduce construction gates

Use the existing JDK 21, Gradle 9.5.0, pinned Android SDK, and CPython 3.12 environment
with `requirements/fixture-validation.lock`. Set `JAVA_HOME`, `ANDROID_HOME`, and
`FIXTURE_VALIDATOR_PYTHON` accordingly. The validator requires its bubblewrap sandbox.

```sh
gradle :core:test --tests '*ProfileEconomicsTest' --tests '*Ja2SaveInspectorV01Test' :android-app:testDebugUnitTest --tests '*ProfileEconomicsPresentationTest' --tests '*Personnel*Test' --offline --no-daemon
gradle :core:test :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --offline --no-daemon
"$FIXTURE_VALIDATOR_PYTHON" -B -m unittest discover -s tools/tests -v
"$FIXTURE_VALIDATOR_PYTHON" -B -c 'from tools import validate_fixture_manifest as p; p.validate(p.load_json(p.ROOT / p.MANIFEST_PATH), False)'
python3 -B tools/check_personnel_offsets.py /path/to/pinned-upstream
python3 -B tools/check_live_location_offsets.py /path/to/pinned-upstream
python3 -B tools/check_hired_stat_offsets.py /path/to/pinned-upstream
python3 -B tools/check_inventory_layout.py /path/to/pinned-upstream
git diff --check
```

Tests use independent literal offsets and encrypted synthetic v102/v103 inspection
vectors. Coverage includes signed extrema, U16/U32 high bits, record isolation, detached
facts, complete category coverage, all signed statuses and deposit flags, exact category
row sets, negative raw labels, literal zero delay, unduplicated cost, UI visibility/note
ordering, and unchanged roster-derived squad markers.

## Construction results — 2026-10-09

- Focused core: 11 passed; focused Android: 9 passed.
- Complete `:core:test`: 219 passed, zero failures/errors/skips.
- Complete `:android-app:testDebugUnitTest`: 193 passed, zero failures/errors/skips.
- `:android-app:lintDebug` and `:android-app:assembleDebug`: passed.
- Complete fixture/privacy/workflow suite: 69 passed.
- Worktree fixture validation with `check_local_files=False`: passed.
- Personnel, live-location, hired-stat, and inventory audits: passed against the pinned
  upstream commit. Personnel includes all ten signedness/padding negative probes.
- `git diff --check`: passed.

Initial local setup needed Gradle cache/native-service access outside the outer sandbox
and the existing fixture-validator Python environment. Fixture validation used its own
bubblewrap namespaces. No parser/editor/location tests were weakened, and no production
defects required repair. This is local draft construction, not device qualification or
APK publication. No private/local-only fixtures were accessed.

Changed files (Kotlin paths use the existing package roots):

- Core main: `model/ProfileEconomicsFacts.kt`, `model/MercProfile.kt`,
  `format/NormalMercProfileParser.kt`.
- Core tests: `format/ProfileEconomicsTest.kt`, `Ja2SaveInspectorV01Test.kt`.
- Android main: `presentation/PersonnelPresentation.kt`, `MainActivity.kt`.
- Android tests: `presentation/ProfileEconomicsPresentationTest.kt`, `PersonnelWiringTest.kt`.
- Audit/docs: `tools/check_personnel_offsets.py`, `docs/profile-economics-evidence.md`,
  `docs/architecture.md`, `README.md`.
