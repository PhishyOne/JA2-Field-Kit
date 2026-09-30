# Issue #72: hired-stat serialization and runtime evidence

This is a synthetic construction slice, not real-save/game-load qualification.
Production capability is disabled; v102 is read-only. One request sets one stat
on one uniquely hired player merc. Health, inventory, placement, Android editing
UI and process integration are outside the admitted operation set.

## Upstream authority and offset audit

On 2026-09-28, `git ls-remote` confirmed JA2-Reborn HEAD as
[`743f38a6ca86c81893376c2576277db660320170`](https://github.com/RealTommyGreen/JA2-Reborn/tree/743f38a6ca86c81893376c2576277db660320170).
The local pinned git objects were read directly; no private save was accessed.
Offsets below are zero-based within decrypted normal non-Linux records, not
native C++ structure offsets. All admitted fields are signed INT8.

| Stat | Upstream field | Profile offset | Live soldier offset | Admitted expected/new domain | Required companion |
| --- | --- | ---: | ---: | --- | --- |
| Agility | bAgility | 405 | 880 | 1..100 | bAgilityDamage at 820 equals 0 |
| Dexterity | bDexterity | 335 | 840 | 1..100 | bDexterityDamage at 821 equals 0 |
| Strength | bStrength | 296 | 886 | 1..100 | bStrengthDamage at 822 equals 0 |
| Leadership | bLeadership | 341 | 895 | 1..100 | none |
| Wisdom | bWisdom | 355 | 841 | 1..100 | bWisdomDamage at 823 equals 0 |
| Experience | bExpLevel | 352 | 849 | 1..10 | no earned-level side effects |
| Marksmanship | bMarksmanship | 353 | 1377 | 0..100 | none |
| Mechanical | bMechanical | 411 | 916 | 0..100 | none |
| Explosives | bExplosive | 339 | 1378 | 0..100 | none |
| Medical | bMedical | 261 | 1372 | 0..100 | none |

The opt-in, offline audit is reproducible with:

```sh
python3 -B tools/check_hired_stat_offsets.py /path/to/JA2-Reborn
```

It reads immutable git blobs, cumulatively counts the explicit serializer
operations (including fixed strings, padding and inventory), fails on unknown
statements, and checks 34 profile/stat-history and 20 live/companion offsets. It retains no
upstream implementation or data. The ordinary core tests use independently
specified literal offsets and project-authored synthetic bytes for every stat;
no upstream checkout or network is required by CI.

Evidence at the pinned revision:

- [LoadSaveMercProfile.cc](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/LoadSaveMercProfile.cc),
  blob `3b38fe4c9d873b2bcfd0872178bc251ad066878e`: explicit injector/extractor,
  716-byte record, checksum at 696..699.
- [LoadSaveSoldierType.cc](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/LoadSaveSoldierType.cc),
  blob `2dbe7b38c3494b25f7628138bf28732ffa35c907`: explicit injector/extractor,
  2328-byte record, checksum at 2208..2211; stat damage is restored only for
  save versions >=103 and treated as zero on earlier runtime loads.
- `LoadSaveData.h`, `Soldier_Profile_Type.h`, `Soldier_Control.h` and
  `LoadSaveObjectType.cc`: scalar widths, 30/10-character profile strings,
  10-character soldier name, 30-byte palettes, fixed arrays, 19 inventory
  objects of 36 bytes. These extents establish the cumulative offsets.

Both checksum functions combine life/lifeMax, agility/dexterity,
strength/marksmanship, medical/mechanical, explosives/experience, and inventory;
the soldier function also includes profile ID. Neither includes leadership,
wisdom or injury counters. The editor always recalculates **both** checksums,
including for leadership, wisdom and no-ops. Candidate public admission retains
its independent checksum validation. Unchanged checksum values for uncovered
fields are expected, not evidence that reparse can be skipped.

## Semantic reassessment and admission

The audit found three distinct departures from a blanket marksmanship rule:
attribute/experience domains, recoverable stat injuries, and health/level-change
side effects. The operation was reassessed before generalization. A direct set
is admitted only with the domain and injury restrictions above; health remains
unrepresentable. This does not expand into training, healing or leveling events.

[Campaign.h](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Campaign.h)
(blob `d06776b8a48f39ededbca1ce67bda86e91ea94a0`) defines maximum stat 100 and
experience 10. `Campaign.cc` (blob `38ff561e23cfc3cc043cb749146a167cc63c6a1b`,
lines 800–904) uses minimum 1 for attributes/leadership/experience, minimum 0 for
the four skills, and the special experience maximum. These are the editor's
conservative admitted domains, not new restrictions on the read model. In
particular an existing zero-leadership target remains readable but is refused
by this edit slice; the campaign-improvement minimum does not prove that every
legacy profile starts at 1. No clamping or coercion is performed.

`Soldier_Create.cc` (blob `ae33325c12bb3da7aa07869f88da5dd8c2a40ff9`, lines
501–512, profile initialization) copies these profile fields into
live fields; `Campaign.cc` lines 390–590 maps each stat back to corresponding
profile/live pointers and synchronizes them. Field Kit requires both independently
parsed values to equal the supplied expected-current. Source size/SHA-256 binds
that assertion, including companion bytes, to the exact inspected source.

[Weapons.cc](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Weapons.cc)
(blob `d6f9ffc691c3a0f55b41cf1deb785f5fbb459285`, lines 2970–3100) decreases both
profile/live agility, dexterity, strength or wisdom and increases the live damage
counter. **Equal profile/live values can therefore still represent an injury.**
`Assignments.cc` (blob `68e8d4a42eed4de8cd1c613da254058377d37593`, lines
1494–1564) restores the stat from this counter and updates profile/delta state.
A direct edit of an injured stat is refused (`STAT_INJURY_PRESENT`), including
no-ops and negative/corrupt counters. Injuries of unrelated stats are preserved.
The mandatory verifier independently rechecks the target injury restriction.

Experience means the stored base/current level, not accumulated experience
points. `Campaign.cc` uses 350 subpoints times current level as its conversion
threshold; zero would permit a zero divisor, so both expected/new values must
be 1..10. Earned level increases additionally adjust salary/deposit amounts,
contract-price flags, dialogue and strategic email events (lines 668–743).
Those are progression-event effects, not serialization aliases of `bExpLevel`.
This explicit **direct set** preserves salary, contract state, gain counters,
deltas and event bytes. It does not award earned levels or reconstruct history;
pending gains continue under the newly assigned level's normal runtime threshold.

The same profile serializer establishes the preserved training/history fields.
They are accumulated progress (INT16) and historical changes (INT8), not alternate
current values. They are excluded from both the mutation envelope and checksums.

| Stat | Profile gain offset (INT16) | Profile delta offset (INT8) |
| --- | ---: | ---: |
| Agility | 246 | 300 |
| Dexterity | 248 | 301 |
| Strength | 326 | 307 |
| Leadership | 324 | 308 |
| Wisdom | 250 | 302 |
| Experience | 242 | 298 |
| Marksmanship | 252 | 303 |
| Mechanical | 256 | 305 |
| Explosives | 258 | 306 |
| Medical | 254 | 304 |
| Health (deferred) | 244 | 299 |

For every stat, gain counters, historical deltas, change-time/UI flags, effective
stat modifiers, AP/breath state, inventory and every other non-target field are
preserved. These are not additional copies of the requested base/current field.
Health and effective/runtime simulation remain outside this transaction's claim.

## Health deliberately deferred

Profile life/lifeMax are at 334/297; live life/lifeMax at 868/917. Related live
fields include old life 704, fractional life 708 (INT16), bleeding 710, next bleed
896 (FLOAT), and the four stat-damage fields above. The serialized offsets are
known; safe editing semantics are not fully qualified.

`Campaign.cc` lines 640–667 changes current life along with max life and applies
an OKLIFE floor. `Soldier_Control.cc` derives bandaged damage from
lifeMax − life − bleeding and handles death, collapse and bleeding transitions.
`Assignments.cc` healing separately considers wounds and stat injury recovery.
A max-health reduction could violate wound/bleeding invariants; setting life
could amount to healing or revival and leave timers, fractional life, services,
and tactical state inconsistent. This slice neither changes those companions
nor claims that two byte assignments implement healing. Neither life nor
lifeMax appears in `HiredMercStat`.

## API consolidation and remaining qualification

`SetHiredStat` replaces `SetHiredMarksmanship`; there is one transaction engine.
The earlier unbound profile-only editor, its result/model/verifier and privileged
harness were retired. Their applicable authority, admission, ownership,
corruption and preservation coverage is carried by transaction tests; obsolete
non-hired and signed-representation write acceptance is intentionally removed.
Read-only signed values and lower-level crypto/checksum regressions remain.

Live leadership/wisdom now cross the public read surface and participate in full
candidate verification. Android production presentation is unchanged; its test
constructors explicitly initialize the two additional model fields.

The next slice still requires production capability policy, qualified Save As
placement, and separately authorized real-save/game-load interoperability and
resource qualification. The existing licensing review remains a pre-merge gate.
No synthetic test or offset audit supplies that qualification.

## Construction validation (2026-09-28)

JDK 21, cached Gradle 9.5.0, pinned validator Python environment, offline builds:

- Initial focused transaction/authority run: **58 passed** (before four additional
  migrated/read-model regressions). Final complete run includes **62 passing**
  transaction/authority tests: 59 transaction and 3 JVM authority/writer tests.
- `gradle :core:test :android-app:testDebugUnitTest --offline --no-daemon`:
  **190 core tests in 19 classes**, **101 Android unit tests in 18 classes**;
  zero failures, errors or skipped tests. Android production code was unchanged.
- `python -B -m unittest discover -s tools/tests -v`: **69 passed**.
- `python3 -B tools/check_hired_stat_offsets.py /path/to/pinned/upstream`:
  **54 offsets passed** (34 profile/stat-history, 20 live/companion).
- `git diff --check` and staged diff check: passed.
- Header/profile synthetic fixtures were freshly materialized through the
  unchanged manifest validator and Bubblewrap pipeline during successful runs.

One isolated test defect was repaired: the live-model field allowlist still
listed ten fields after adding leadership/wisdom. The exact allowlist was
extended to those two signed integer facts; its privacy and immutable-ownership
assertions remain. The complete suites then passed. No serializer, checksum,
read validation, preservation rule or fixture policy was weakened.

Gradle required the host execution path for its local lock socket and fixture
namespaces. The initial tracked-file snapshot also required staging the retired
files before materialization. These infrastructure failures were not test or
semantic failures. No private fixture or user save was opened. Exact committed
head CI/game-load qualification is not claimed by this local run.
