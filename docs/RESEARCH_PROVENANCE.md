# Research provenance

JA2 Field Kit began from a compatibility investigation into a reproducible JA2 Reborn crash and the structure of a privately supplied save game.

## Public source references

Initial research used the public repository:

- `RealTommyGreen/JA2-Reborn`
- reference commit: `743f38a6ca86c81893376c2576277db660320170`

Relevant source areas were inspected to understand interoperability facts such as header sizes, field ordering, encryption selection, encrypted record sizes, and the presence of persistent profile and active-soldier data.

The Field Kit repository should **not** copy implementation code from JA2 Reborn or JA2 Stracciatella merely for convenience. Public source is used here as compatibility documentation/evidence; Field Kit code should be independently authored around the documented format behavior.

## Private validation material

The initial investigation used privately supplied `.sav` files from JA2 Reborn Build `04.12.02` / saved-game version `103`.

Those files demonstrated that the documented layout was sufficient to recover a real player roster and persistent mercenary statistics.

Private saves, player names, campaign details, and decoded roster output are not to be committed to the public repository.

When tests require binary fixtures, prefer one of these approaches:

1. generate synthetic fixtures from independently authored serializers;
2. create tiny redacted fixtures whose provenance and redistribution rights are clear;
3. require developers to supply their own local saves for integration tests, with those paths ignored by Git.

## Confidence labels

Research documentation should distinguish among:

- **Verified** — confirmed by both public serialization logic and successful decoding of a real supported save.
- **Source-derived** — supported by public serializer/parser behavior but not yet independently exercised by Field Kit.
- **Hypothesis** — inferred behavior requiring a test before implementation may rely on it.

The current `SAVE_FORMAT_RESEARCH.md` is a bootstrap document. As Field Kit gains tests, statements should be promoted to verified behavior only when covered by reproducible fixtures/tests.

## Licensing note

JA2 Reborn currently carries the Strategy First Source Code License Agreement, which contains non-commercial and derivative-work restrictions. JA2 Field Kit intentionally has no selected software license yet while the project establishes an independent interoperability implementation boundary.

A dedicated licensing review should precede selection of a permissive license such as MIT or Apache-2.0.
