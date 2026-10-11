# Strategic place-label evidence

Issue #81 adds presentation-only decoration of already-qualified sector coordinates.
The coordinate remains first and authoritative. Place names assume the standard map:
this pinned default-content metadata does not authenticate an imported campaign or
establish that its maps match. Current-squad location still comes only from
`LiveMercLocation`; non-current RPC/NPC sectors retain the separate “Recorded profile
location” wording and existing saved-profile warning. No parser, editor, or write
behavior changes are involved.

## Exact approved surface mapping (33 entries)

| Label | Coordinates |
| --- | --- |
| Omerta | A9, A10 |
| Drassen Airport | B13 |
| Drassen | C13, D13 |
| Alma | H13, H14, I13, I14 |
| Grumm | G1, G2, H1, H2, H3 |
| Cambria Hospital | F8 |
| Cambria | F9, G8, G9, H8 |
| San Mona | C5, C6, D4, D5 |
| Estoni | I6 |
| Balime | L11, L12 |
| Meduna Airport | N3 |
| Meduna | N5, O3, O4, P3 |
| Chitzena | A2, B2 |

All six secret sectors are excluded: **D2, D15, I8, J9, K4, N4**.
J9, K4, and N4 remove three of the 36 town-sector associations; the other three
secrets are outside town membership. B13, F8, and N3 replace town labels with
exact structured surface-facility names. No other facilities are admitted.

Examples: `B13 — Drassen Airport`, `C13 — Drassen`, `F8 — Cambria Hospital`,
`N3 — Meduna Airport`, `A9 — Omerta`, `P3 — Meduna`. A1 and all excluded or
unmapped sectors remain coordinate-only. Every underground depth stays
coordinate/depth-only, including `A10-1` and `B13-1`.

No ownership, militia, mine production, SAM state, discovery state, quest state,
facility flags, or map filenames are consulted or inferred. No secret or underground
place names are displayed. The project-authored table retains only short proper
names; no upstream descriptive prose, resource files, or source bytes are retained.

## Pinned evidence

Public upstream: `RealTommyGreen/JA2-Reborn`, revision
`743f38a6ca86c81893376c2576277db660320170`.

Town membership joins `townId` to the English town-name array. Special-sector
records supply `(sector, sectorLevel)` and a land-type key, joined through
traversibility ratings to the English land-type array. Only level zero overrides
are admitted. The secrets resource supplies the unconditional exclusion set.
`TownModel.cc` and `DefaultContentManager.cc` establish resource loading and town
indices; `SectorLandTypes.cc` establishes special-sector/depth and land-type
resolution. `StrategicMap.cc` provides context for coordinate, town, and special
sector display. Its dynamic display behavior is deliberately outside this slice.

| Object path | Git blob |
| --- | --- |
| `assets/externalized/strategic-map-towns.json` | `9bf4d480a82cbb5a2e83f2fcf1604337e53bca35` |
| `assets/externalized/strings/strategic-map-town-names-eng.json` | `4902bc86de1eaddf094c06e56f0c9ea25f03223e` |
| `assets/externalized/strategic-map-sectors-descriptions.json` | `49b8ea74d08c3d116d9bdacd78de2296e8a23c7e` |
| `assets/externalized/strategic-map-traversibility-ratings.json` | `6137db1d470c2069ab890572dbc661449db6333f` |
| `assets/externalized/strings/strategic-map-land-types-eng.json` | `5f0aa6f7603219eab8a7374fbaa16b4d8a7b9063` |
| `assets/externalized/strategic-map-secrets.json` | `51e427b59a2f13f6a4b97a9ec53b1625ea48199f` |
| `src/game/Strategic/StrategicMap.cc` | `e72c19c51c80b2dbd98b21309f6d06ad7f50d4a4` |
| `src/externalized/DefaultContentManager.cc` | `03d360b8aada60e6f3df372b62e4007e1aab45ff` |
| `src/externalized/strategic/TownModel.cc` | `42e8df6af7c75331299ec0c080b6f8f02ae9cccf` |
| `src/externalized/strategic/SectorLandTypes.cc` | `6d2d95d6ee74892af710b72be17d7d5d5ca6e344` |

## Reproduction and coverage

```sh
python3 -B tools/check_strategic_place_labels.py /path/to/JA2-Reborn
```

The independent audit reads exact Git objects into memory, checks all ten blob
identities and content hashes, parses all six resources, rejects duplicate keys and
memberships, validates resource indices, derives the 33 labels, and compares them
to production. It rejects secret inclusion, depth keys, missing overrides, and extra
surface facilities. No upstream files are copied into this repository.

`StrategicPlaceLabelsTest` independently specifies all 33 expected names and checks
all 256 surface coordinates, all 768 underground coordinates, asymmetric X/Y cases,
all six exclusions, all three overrides, RPC/NPC shared decoration and preserved
caveats, and invalid/unavailable recorded locations. Existing recorded-location
coverage checks every live special state and prevents fallback to a named profile
sector for current squad members.

Validation here is ordinary Draft development, not final immutable-head review,
device verification, campaign authentication, or authorization to publish an APK.

Local Draft-development gates (2026-10-10): focused Android 27/27; complete Android
214/214 after updating two source-wiring expectations for the new UI note; complete
core gate accepted unchanged inputs as up to date (223/223 in its report). All test
reports have zero failures, errors, or skips. Fixture/privacy/workflow suite 69/69;
worktree fixture validation, all five strategic-place/personnel/live-location/
hired-stat/inventory source audits, `lintDebug`, `assembleDebug`, and
`git diff --check` passed. Validation used JDK 21, Gradle 9.5.0, the existing Android
SDK, and the installed fixture-validator environment; no network dependency fetch
was needed. No private saves were used.
