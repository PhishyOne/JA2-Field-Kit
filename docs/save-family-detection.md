# Save-layout compatibility detection v0.1

## Three independent results

Detection reports three facts that must not be collapsed into one another:

- `layout` identifies a serialized byte layout. The named values are
  `NORMAL_V102_BUILD_041202_NON_LINUX` and `NORMAL_V103_BUILD_041202_NON_LINUX`.
- `compatibility` says how far the current read-only implementation can safely
  proceed: `SUPPORTED`, `CANDIDATE`, `TRUNCATED`, `UNSUPPORTED_VARIANT`,
  `INCONSISTENT`, or `UNKNOWN`.
- `family` identifies the producer executable only when a byte discriminator
  independently proves it. No such discriminator is currently evidenced for
  this layout, so this detector always returns `UNKNOWN` family.

JA2 Reborn and the verified Android Stracciatella evidence share the examined
version/build and normal header layout. The currently reviewed bytes do not
distinguish those producers at this layer. A successful compatibility result
therefore must not become `JA2_REBORN`, `STRACCIATELLA`, or `CONFIRMED` merely
because the layout is usable.

## Selector/body binding

A `SUPPORTED` result requires all of the following:

1. the complete identity is exactly saved-game version `102` or `103` and game-version
   string `Build 04.12.02`;
2. all normal-selector header values have evidenced encodings and ranges;
3. `NormalSaveEncryptionSelector.select` computes the rotation-table index from
   those header values;
4. the bounded normal non-Linux prefix reaches a complete 170-by-716-byte
   profile table with supported zero-used-count laptop tails;
5. profile constraints and checksums yield exactly one recovered rotation; and
6. the recovered rotation's SHA-256 identity equals an independently admitted
   digest for the exact header-selected index.

The internal production oracle covers all 228 selector indexes (`0..227`) with
non-secret lowercase SHA-256 identities supplied by a separate read-only audit.
The audit provenance is JA2-Reborn commit
`743f38a6ca86c81893376c2576277db660320170`,
`src/game/Tactical/Tactical_Save.cc`, blob
`5640f1a623f609f973020b0a617bbc00b2dafe75`.
Only cryptographic identities/facts are incorporated in a project-authored
indexed digest list. No production 49-byte rotation rows, reconstructed row
bytes, upstream comments, or source initializer layout ship. Initialization
fails closed on inconsistent count, duplicate identities, or invalid digest
syntax. Indexes `124` and `139` retain their previously admitted identities.

This expands campaign-state compatibility, not producer attribution: family
stays `UNKNOWN`. A match establishes only selector/body identity against the
pinned table; it does not authenticate the entire save. The public synthetic
body uses a different project-authored rotation and remains inconsistent with
the production oracle. Tests pin the complete ordered digest list and selected
individual identities without adding production rotation bytes.

`ROTATION_DIGEST_ORACLE_MISSING` remains a fail-closed injected test seam;
production has a digest for every valid selector index. Unique recovery alone
never permits support, and ambiguous recovery remains `CANDIDATE` before any
oracle lookup. This is engineering interoperability/provenance, not legal
clearance or a formal clean-room claim.

## Decision matrix

| Observed evidence | Compatibility | Layout | Family |
| --- | --- | --- | --- |
| Complete frame, unique recovered rotation, and matching digest for the selected index | `SUPPORTED` | Version-specific normal non-Linux layout | `UNKNOWN` |
| Complete frame and unique recovered rotation, but an injected test oracle has no digest for the selected index | `CANDIDATE` | Version-specific normal non-Linux layout | `UNKNOWN` |
| Complete frame and ambiguous recovered rotation | `CANDIDATE` | Version-specific normal non-Linux layout | `UNKNOWN` |
| Selected-index digest mismatch, conflicting rotation constraints, or no checksum survivor | `INCONSISTENT` | Version-specific normal non-Linux layout | `UNKNOWN` |
| Exact identity but incomplete header or body | `TRUNCATED` | `UNKNOWN` | `UNKNOWN` |
| Unsupported selector values or dynamic laptop tails | `UNSUPPORTED_VARIANT` | `UNKNOWN` | `UNKNOWN` |
| Exactly one of version `102` or `103` and build `Build 04.12.02` matches | `INCONSISTENT` | `UNKNOWN` | `UNKNOWN` |
| Incomplete or otherwise unknown identity | `UNKNOWN` | `UNKNOWN` | `UNKNOWN` |

Changing a selector-affecting header byte while retaining the body changes the
selected index. If its digest does not match the recovered body rotation,
detection returns `INCONSISTENT`. An injected test oracle missing that index
returns only `CANDIDATE`; it never falls back to structural support.

Diagnostics retain only bounded structural facts: input size, printable
version/build identity, header completeness, selector compatibility, selected
index, oracle presence/match booleans, event count, framing boundaries, and
failure stage. They never retain save bytes, descriptions, profile plaintext,
names, paths, recovered rotation material, or digest values.

All layout-specific interpretation methods on `Ja2SaveInspector` snapshot the
input and apply this detector before returning a header, encrypted-profile
frame, profile table, or roster. They continue only for exactly `SUPPORTED`
plus either explicitly admitted v102/v103 layout; every other compatibility fails with
sanitized enum diagnostics. Lower-level format primitives remain explicit
research and unit-test boundaries and do not replace this public admission.

## Issue #66 read-only v102 evidence

Version 102 retains its own layout identity; it is never relabeled as 103.
The issue's established source comparison pins the initial public release
`dba730f20f7677e37448ba7dd60ea7d3861d6b93` at version 102 and sync
`a54aa1320d5bd259b0e3591469a0b94d4876ebf2` at version 103. The latter
replaces four SOLDIERTYPE padding bytes with agility/dexterity/strength/wisdom
damage fields inside an unchanged 12-byte region. Later offsets do not shift.
These four bytes remain uninterpreted by Field Kit. The 432-byte
`SaveLoadGame.h` header and `Tactical_Save` selector/rotation source are
unchanged across that transition.

The issue also records one authorized private v102 / exact `Build 04.12.02`
save passing the existing framing, unique recovery, production selected-index
digest, all 170 profiles, complete 20-slot soldier traversal, checksum,
path/keyring, profile identity/uniqueness, header count, live stats, and
19-slot inventory checks when only the v103 identity gate was bypassed.
No private bytes, filenames, campaign values, or roster data are incorporated
here. This construction slice uses only public synthetic tests; it does not
claim a new private-fixture acceptance run or full candidate qualification.

The shared reader preserves every existing validation gate and only admits
versions 102 and 103 with the exact build. Other versions and Linux layouts
remain outside scope. No save-writing authority changes:
`Ja2MarksmanshipEditor` production capability remains disabled, and its
existing v103-only checks are unchanged.

## Evidence limits

The authorized Android Stracciatella fixture is verified and local-only. Its
existing review establishes non-sensitive header facts, not a producer byte
discriminator and not full detector acceptance. It must never be labeled
Reborn merely because it shares this layout.

The historical local Reborn fixture remains private and identity-pending in the
public manifest. No verified Reborn fixture is admitted for local acceptance,
so this change cannot claim local-only Reborn end-to-end acceptance. Public tests use project-authored
synthetic bytes to verify control flow, selector/body binding, failure states,
immutability, and non-attribution; they are not real-family evidence.

Classic, 1.13, modded, Linux-layout, and other variants remain fail-closed until
representative admitted fixtures and exact authoritative evidence justify a
narrower adapter.

## Issue #7 acceptance correction

Evidence supports “detect compatibility with the normal v102/v103 / Build 04.12.02
non-Linux layout by binding the header-selected index to an independently
admitted rotation digest, while leaving producer family unknown.” It does not
support “detect JA2 Reborn as the producer.” Issue #7 should use the narrower
wording unless a later independently falsifiable byte discriminator is admitted.
