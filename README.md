# JA2 Field Kit

A mobile save companion and editor for Jagged Alliance 2.

## Initial direction

JA2 Field Kit is being designed as an Android-first tool that can inspect compatible JA2 save files regardless of whether the game itself was played on Android or PC.

The first milestone is intentionally read-only:

`open .sav -> detect format/version -> campaign summary -> roster -> merc stats`

Save editing will come only after round-trip parsing and verification are proven safe.

## Research

- [Initial save-format research](docs/SAVE_FORMAT_RESEARCH.md)
- [Research provenance and implementation boundary](docs/RESEARCH_PROVENANCE.md)

The repository intentionally has no software license selected yet while the project establishes a clean interoperability/licensing boundary from JA2/Reborn source.
