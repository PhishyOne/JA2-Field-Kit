# Licensing-boundary engineering review

## Purpose and scope

This review supports Issue #8's license-selection decision. It is an engineering
inventory and risk review, not legal advice, legal representation, a formal
clean-room report, or legal clearance. It reviews Field Kit `main` exactly at
commit `0fae25822b22174d21b1c3612613495ee5d04aea`, plus the expression cleanup in
the pull request that adds this document. The review is limited to material in
this repository and the evidence described below; legal conclusions depend on
the facts and jurisdiction.

## External licenses and legal evidence

The pinned JA2-Reborn source at
[`743f38a6ca86c81893376c2576277db660320170`](https://github.com/RealTommyGreen/JA2-Reborn/tree/743f38a6ca86c81893376c2576277db660320170)
carries the Strategy First Inc. Source Code License Agreement (SFI-SCLA), as
recorded in its
[`LICENSE`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/LICENSE)
and source notices. The SFI-SCLA permits non-commercial use and modification and
constrains distribution of the Software and derivative works. It does not itself
grant a copyright or patent license from Strategy First for a separate creation
merely because that creation uses information learned from the Software.

The current
[JA2-Stracciatella project](https://github.com/ja2-stracciatella/ja2-stracciatella)
states that original JA2 source is under the SFI-SCLA, that newer changes after
its stated boundary are generally dedicated to the public domain unless
specified otherwise, and that older Tron changes have uncertain licensing.
Those statements do not relicense original JA2 material or resolve Field Kit's
boundary by themselves.

[17 U.S.C. § 102(b)](https://www.law.cornell.edu/uscode/text/17/102) excludes
ideas, procedures, processes, systems, methods of operation, concepts,
principles, and discoveries from copyright protection. The Ninth Circuit's
fact-specific decision in
[Sega Enterprises Ltd. v. Accolade, Inc.](https://www.copyright.gov/fair-use/summaries/segaenters-accolade-9thcir1992.pdf)
supports intermediate reverse engineering where needed to reach unprotected
functional elements for interoperability. It should not be read as automatic
immunity or as deciding contractual, derivative-work, trademark, patent, or
other issues here.

The implementation facts reviewed here retain file-level provenance to that
exact commit: the selector is evidenced in
[`src/game/SaveLoadGame.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/SaveLoadGame.cc),
the soldier checksum in
[`src/game/Tactical/LoadSaveSoldierType.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/LoadSaveSoldierType.cc),
and rotation-row evidence in
[`src/game/Tactical/Tactical_Save.cc`](https://github.com/RealTommyGreen/JA2-Reborn/blob/743f38a6ca86c81893376c2576277db660320170/src/game/Tactical/Tactical_Save.cc).
These are evidence links, not incorporated source.

## Repository inventory and classification

This review treats format sizes and offsets, primitive encodings, version/build
identifiers, record counts, on-disk sequencing, and checksum/encryption
equations as interoperability facts or methods. Project-authored Kotlin and
Python structure, diagnostics, validation policy, UI, and fixture tooling are
Field Kit expression of those documented facts.

The production repository contains SHA-256 identities for only two rotation
rows, not the row bytes. It contains no production rotation array or table, no
upstream source files, and no real private-save bytes. Public fixtures are
independently generated synthetic evidence subject to the fixture policy.

Stale public PR #1 contains documentation and provenance, with no copied numeric
tables or source blobs found in the completed audit. It is not part of current
`main`. A future license on `main` would not automatically bless or relicense
material on every stale branch.

## Findings and cleanup in this pull request

Two implementations on the reviewed main were too close to the statement and
control-flow shape of their source evidence:

- `NormalSaveEncryptionSelector.select` followed the selector's nested
  divisibility branches. This pull request expresses the equivalent nonnegative
  uint32 random contribution as data: add one for divisibility by 2, one for 14,
  one for 322, and two for 1106. It preserves uint32 wrapping, position modulo,
  nineteen-table banks, option offsets, and fail-closed inputs without adding a
  German selector.
- `NormalNonLinuxRosterDecoder.sourceChecksum` followed the stat add/multiply
  statements line by line. This pull request represents the five documented
  serialized offset pairs as data and evaluates the modulo-2^32 recurrence,
  followed by the profile and nineteen item/count contributions.

The simple byte-decrypt equation remains because it is short and functionally
constrained; its provenance remains documented. Profile checksum/recovery and
field mapping are data-driven or literal-offset representations of necessary
interoperability facts, not copied tables or comments.

This cleanup improves expression independence. It is not a formal clean-room
claim and does not provide legal clearance.

## Contributor rules

- Never paste upstream source, comments, tables, arrays, or source-shaped logic
  into this repository, prompts, or tests.
- Record necessary interoperability facts in prose, equations, offset tables,
  or independently generated synthetic vectors.
- Write implementation from reviewed Field Kit specifications and tests, not by
  translating upstream source line by line.
- Document the exact evidence commit and blob or path separately from the
  project-authored implementation.
- Do not make a formal clean-room claim unless a genuinely separated and
  documented process exists.
- Put future source-derived algorithms or tables through an Issue #8-style
  boundary review before merge.

These rules supplement rather than weaken the repository's fixture privacy,
provenance, exact-commit, and no-private-bytes requirements.

## License recommendation

After this boundary cleanup is independently reviewed and after a final human
and legal review, the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) is the
recommended license for Field Kit's project-authored code. Its contributor
patent grant and termination provision, contribution terms, preservation of
notices, and express absence of a trademark license are useful for an
interoperability tool.

An Apache-2.0 license would cover only material owned or licensable by Field Kit
contributors. It would not grant rights in JA2, JA2-Reborn, or JA2-Stracciatella
source or data; Strategy First or other third-party trademarks; or third-party
and private fixtures. This pull request deliberately adds no `LICENSE`,
`COPYING`, `NOTICE`, SPDX project-license claim, or other project license file.

## Residual risks

- Contract and derivative-work characterization under the SFI-SCLA is not
  settled by this engineering audit.
- Contributors have seen source, so this is not formal clean-room development.
- Future contributions could introduce copied expression or data despite the
  present inventory and rules.
- Trademark and branding questions, and rights in third-party fixtures, are
  separate from the source-code license decision.
- Any legal outcome depends on the jurisdiction and complete facts.

## Decision gate

Do not add a project license until this cleanup pull request is independently
reviewed, clean, and merged, and the user makes the consequential final license
decision after independent human/legal review. Issue #8 should remain open
through that review and cleanup lifecycle.
