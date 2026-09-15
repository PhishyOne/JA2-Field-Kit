# Save-format knowledge

This document records current evidence without treating every observation as a universal JA2 invariant.

## Confirmed from prior source study + real save decoding

A real JA2 Reborn Build `04.12.02` save reporting saved-game version `103`
was successfully decoded before this repository was created. That work
established the parser concept and produced an 18-merc roster with stats.

For the examined normal Windows/Stracciatella-style layout used by that save:

- Save header: **432 bytes**.
- The header contains save version, day/time, sector, player merc count, balance, game options, random/encryption-related state, and other fields.
- Reborn uses rotating save encryption selected from header state.
- Encrypted merc profile table: **170 `MERCPROFILESTRUCT` records**.
- Serialized merc profile record size: **716 bytes each**.
- Encrypted active soldier records serialize `SOLDIERTYPE` at **2328 bytes each**.
- Merc profiles contain names, base/profile stats, traits, combat history, salary, profile inventory, location, and additional data.
- Active soldier records contain current stats/condition, sector, assignment, tactical inventory, paths, and additional runtime state.

## Not yet encoded in Field Kit

The exact Build `04.12.02` normal-header offsets and a narrow structural parser
are recorded in [`save-header-04.12.02.md`](save-header-04.12.02.md). Its public
tests are synthetic because the cataloged real save is not currently an
admissible test fixture; the document records the exact remaining evidence
gate.

Encryption-selector rules, encrypted block locations, and merc-profile field
offsets are not yet encoded. They must be transferred into this project from
evidence and revalidated against eligible fixtures before they are treated as
parser contracts.

That remains intentional: each later format slice must record only facts that
can be stated confidently without inventing offsets.

## Compatibility strategy

Target order:

1. JA2 Reborn
2. Normal JA2 Stracciatella / compatible PC saves
3. Classic JA2 where practical
4. JA2 1.13 and heavily modified formats as separate future adapters

Do not assume two formats are different merely because their product names differ. Detect divergence from bytes/fixtures and share parsing code where layouts are actually identical.

## Fixture policy

Real save fixtures are the ground truth for parser behavior, but they are not
automatically safe to publish. The authoritative admission, provenance,
hashing, and CI rules are in [`fixture-policy.md`](fixture-policy.md). The
machine-readable registry and contract live under [`fixtures/`](../fixtures/).

At minimum, each supported family/version needs:

- source/build identification,
- expected header fields,
- expected campaign summary,
- expected roster size,
- several known merc names/stats,
- truncated/corrupted variants for failure tests.

If redistribution of a save fixture is legally or personally questionable, keep the fixture private and store only non-sensitive hashes/expected logical assertions in the public repository.

## Licensing boundary

JA2/JA2 Reborn source may be consulted to understand serialized structures and algorithms. Field Kit implementation should be independently written from those facts rather than copied from restricted source.

Before adding any copied constants, tables, code-shaped logic, or a repository license, review whether the material is factual format information or protected implementation expression.
