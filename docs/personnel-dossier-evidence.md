# Personnel dossier — issue #75

Construction base: `79751a7721357cfaada4ce6a896d1adcfd6dfee9`.
Authority: JA2 Reborn commit `743f38a6ca86c81893376c2576277db660320170`.
The local upstream checkout's HEAD was verified before inspection; the executable audit
reads that commit's git objects, independent of the checkout's working files and of
Field Kit parser constants. No game saves or game assets are needed by this audit.

## Serialized fields admitted

Offsets below are **decimal, zero-based within each 716-byte normal non-Linux profile**.
The table has 170 records; its index is the profile ID. Multibyte counters are little-endian.

| Field | Offset | Encoding | Public meaning |
|---|---:|---|---|
| `usKills` | 310 | U16 | Kills |
| `usAssists` | 312 | U16 | Assists |
| `usShotsFired` | 314 | U16 | Shots fired |
| `usShotsHit` | 316 | U16 | Shots hit |
| `usBattlesFought` | 318 | U16 | Battles fought |
| `usTimesWounded` | 320 | U16 | Times wounded |
| `usTotalDaysServed` | 322 | U16 | Total days served |
| `bPersonalityTrait` | 336 | I8 | Personality trait |
| `bSkillTrait` | 337 | I8 | Skill trait 1 |
| `bSkillTrait2` | 340 | I8 | Skill trait 2 |
| `bBuddy[0..2]` | 342, 343, 344 | I8 each | Friend 1, friend 2, established learned friend |
| `bHated[0..2]` | 347, 348, 349 | I8 each | Enemy 1, enemy 2, established learned enemy |
| `bAttitude` | 549 | I8 | Attitude |
| `uiTotalCostToDate` | 708 | U32 | Salary paid to this person |

`tools/check_personnel_offsets.py` independently walks **all** serializer statements
from the name/nickname prefix through the trailing padding, counts array extents from
the pinned structure declarations, and compares both extractor and injector offsets,
widths and total extent. It checks the enum sequences and the loader's version decision.
The career model keeps U16 in Int (0–65535) and U32 in Long (0–4294967295).
Accuracy is presentation-only: `shotsHit / shotsFired * 100`, one decimal place, labelled
“derived”, and omitted when shots fired is zero. Counters are preserved without clamping
or inventing consistency; even a ratio above 100% remains the literal derived ratio.

## Version admission

Pinned `SaveLoadGame.cc::LoadSavedMercProfiles` selects the encrypted reader only at
version 87. Both 102 and 103 use `NewJA2EncryptedFileRead`, the same normal 716-byte size,
and `ExtractMercProfile(..., stracLinuxFormat, ..., true)`. The extractor has no save
version parameter or numeric-field version branch. Its string-layout branch is already
excluded by Field Kit's normal non-Linux admission. No new field changes semantics in
this shared pinned load path. Field Kit still requires its existing exact Build 04.12.02
header/layout/rotation evidence and complete validated current-player roster. The later
soldier-load salary backfill applies only to versions before 83, not 102/103. This is
not a claim about other builds, Linux layouts, or other producers.

Tests exercise synthetic encrypted v102 and v103 saves with identical nonzero dossier
values, full profile-table equality, source isolation, and rejected duplicate/invalid
roster identities. Existing write admission remains v103-only, with no editor changes.

## Enum labels and unknowns

The pinned `Soldier_Profile_Type.h` enums establish these numeric orders:

- Skill 0–15: None, Lockpicking, Hand to hand, Electronics, Night operations, Throwing,
  Teaching, Heavy weapons, Automatic weapons, Stealth, Ambidextrous, Thief, Martial arts,
  Knifing, On roof, Camouflaged.
- Personality 0–7: None, Heat intolerant, Nervous, Claustrophobic, Nonswimmer,
  Fear of insects, Forgetful, Psycho.
- Attitude 0–9: Normal, Friendly, Loner, Optimist, Pessimist, Aggressive, Arrogant,
  Big shot, Asshole, Coward.

These are project-authored labels with explicit numeric IDs. Signed values outside the
known enum domain display `Unknown (raw)` without rejecting the inspection. Constructors
bound IDs to I8. Both skill slots remain independent; no universal expert inference.

## Relationships: narrowly admitted

`Soldier_Profile_Type.h` declares five-byte buddy/hated arrays initialized to `-1` and
explicitly restricts meaningful indices to 0, 1 and 2. The BuddySlot/HatedSlot enums and
`WhichBuddy`/`WhichHated` loops corroborate those three slots. The strategic handler
assigns the learned slot only when the learned relationship becomes established.

- `-1` is absent. Other negative bytes are unknown, never unsigned-converted into IDs.
- Nonnegative I8 values (0–127) are profile IDs, resolved only in the same admitted table.
  A blank target name keeps its profile ID; no name is invented. A missing target is unknown.
- Slots 3–4 at 345–346 and 350–351 are not exposed.
- Learning targets `bLearnToHate` at 235 and `bLearnToLike` at 554, and `bMercOpinion[75]`
  at 576–650, were offset-audited but **deferred**. Opinion entries are not relationship
  IDs. No progress percentage, learning-time interpretation, or opinions appear in the UI.

No field marked unused is surfaced. `usStatChangeChances` and `usStatChangeSuccesses`
remain opaque balancing counters, explicitly described upstream as never shown.

## Data ownership and UI

The existing admitted inspection now returns an immutable 170-profile database alongside
its validated roster, using the same decode/roster scan and profile table for both. Parser
models contain no squad flag or raw bytes. The presentation layer joins by profile ID and
fails closed on duplicate, out-of-range, or missing identities before filtering names.

The successful inspection has a Personnel button. Personnel lists sanitized nonblank
names/nicknames with IDs and Current squad markers, without calling everyone a merc.
Each entry opens Attributes, Traits, Personality, Record and Relationships sections.
Attributes are explicitly profile values, so they are not confused with live tactical
stats. Buttons and system Back return from dossier to list to inspection. Navigation
survives Activity recreation in the existing ViewModel and resets on a new inspection.

Only display facts survive for browsing. No additional source snapshot, disk persistence,
history, permissions, networking, or report/export payload is introduced. The existing
ephemeral editor snapshot and editor/import/export controls retain their lifecycle.

## Pinned source blobs

Git blob IDs at the authority commit:

| File | Blob |
|---|---|
| `src/game/Tactical/Soldier_Profile_Type.h` | `04c52fbb558e759d5396c9f810a72b31d9872a02` |
| `src/game/Tactical/LoadSaveMercProfile.cc` | `3b38fe4c9d873b2bcfd0872178bc251ad066878e` |
| `src/sgp/LoadSaveData.h` | `06fc7447a80b83f93f495f2e5c97c533b46471b7` |
| `src/sgp/LoadSaveData.cc` | `4b7783f447aa17b0adbd97eb293729e8540efcbd` |
| `src/game/SaveLoadGame.cc` | `e35282dd25e77f0a0c706c4c336040613a09d860` |
| `src/game/Tactical/Overhead_Types.h` | `5f2d420e259c9273920b4c230d8aa2f3f9d5d7a7` |
| `src/game/Tactical/LoadSaveMercProfile.h` | `ab0a36d256d1dfe8a5120ade95393ad0d8b27d7f` |
| `src/game/Tactical/Soldier_Profile.cc` | `9d70349155cc86377fd4f146cd08622805f2e2e4` |
| `src/game/Tactical/Soldier_Profile.h` | `264aa75406391f6e16cb3b9a945db5fa41f3b034` |
| `src/game/Strategic/Strategic_Merc_Handler.cc` | `45d7a4b954a39adea6ea12943e5ebdc6e4a03de2` |

## Reproduce local gates

Use JDK 21, Gradle 9.5.0, the existing pinned Android SDK and fixture-validator environment:

```sh
gradle :core:test :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --offline --no-daemon
python3 -B -m unittest discover -s tools/tests -v
python3 -B -c 'from tools import validate_fixture_manifest as p; p.validate(p.load_json(p.ROOT / p.MANIFEST_PATH), False); print("Worktree fixture validation passed")'
python3 -B tools/check_personnel_offsets.py /path/to/pinned-upstream
python3 -B tools/check_hired_stat_offsets.py /path/to/pinned-upstream
python3 -B tools/check_inventory_layout.py /path/to/pinned-upstream
git diff --check
```

This is the next update's local construction slice. It does not replace the deployed
exact-main debug build and does not include icon artwork. Publication and immutable-head
candidate qualification remain outside this run.

## Changed files

Paths below are relative to the repository; Kotlin paths share the usual
`src/{main,test}/kotlin/com/phishtopia/ja2fieldkit/{core,android}/` package roots.

- Core main: `Ja2SaveInspector.kt`, `format/NormalMercProfileParser.kt`,
  `format/NormalNonLinuxRosterDecoder.kt`, `model/MercProfile.kt`, `model/SaveModels.kt`,
  new `model/ProfileDossier.kt`.
- Android main: `MainActivity.kt`, `InspectionViewModel.kt`,
  `presentation/InspectionPresentation.kt`, new `presentation/PersonnelPresentation.kt`.
- Core tests: `Ja2SaveInspectorV01Test.kt`, new `format/ProfileDossierTest.kt`.
- Android tests: `SuccessRenderOrderTest.kt`, `presentation/InspectionPresentationMapperTest.kt`,
  `presentation/InventoryTestFixtures.kt`, new `PersonnelWiringTest.kt`,
  new `presentation/PersonnelPresentationTest.kt`.
- Audit/docs: new `tools/check_personnel_offsets.py`, this evidence document, `README.md`.

Editor source and transaction tests, manifests, build configuration, fixtures and artwork
are unchanged.

## Construction gate results — 2026-09-30

- Complete `:core:test`: **214 passed**, zero failures/errors/skips, including all 75
  existing `SaveEditTransactionTest` cases without changing that test file.
- Complete `:android-app:testDebugUnitTest`: **188 passed**, zero failures/errors/skips.
  Includes existing editor/import/export, manifest/security, and artwork-integrity checks.
- `:android-app:lintDebug`: **passed**, zero errors, nine warnings (UseRequiresApi,
  DataExtractionRules, ObsoleteSdkInt, IconLauncherShape, UseKtx, four SetTextI18n).
- `:android-app:assembleDebug`: **passed**; local build only, not installed/published.
- Complete fixture/privacy/workflow Python suite: **69 passed**.
- Worktree-only fixture validation: **passed**, with `check_local_files=False`.
  No local-only/private saves were inspected. Immutable-head qualification was not run.
- New personnel audit: **passed**, complete 716-byte extraction/injection parity,
  17 audited field offsets/widths (including three deferred fields), enum and version checks,
  relationship slot/sentinel checks. Negative probes rejected changed padding, career
  signedness, and relationship array extent.
- Existing hired-stat audit: **passed**, 34 profile and 20 soldier offsets.
- Existing inventory audit: **passed**, profile inventory/money, live/object extents and
  the closed three-item metadata subset.
- `git diff --check`: **passed**.

Gradle required access to its local lock socket outside the outer execution sandbox;
fixture sandbox tests ran with their own bwrap namespaces. Early runs found and repaired
two test-fixture issues (a roster-count-dependent selector and a Kotlin Int/Long argument).
No production write safety was relaxed. These are local construction results, not device
qualification or immutable publication-head qualification. No construction blocker remains.
