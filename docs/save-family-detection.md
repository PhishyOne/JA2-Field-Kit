# Save-layout compatibility detection v0.1

## Three independent results

Detection reports three facts that must not be collapsed into one another:

- `layout` identifies a serialized byte layout. The only named value currently
  emitted is `NORMAL_V103_BUILD_041202_NON_LINUX`.
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

1. the complete identity is exactly saved-game version `103` and game-version
   string `Build 04.12.02`;
2. all normal-selector header values have evidenced encodings and ranges;
3. `NormalSaveEncryptionSelector.select` computes the rotation-table index from
   those header values;
4. the bounded normal non-Linux prefix reaches a complete 170-by-716-byte
   profile table with supported zero-used-count laptop tails;
5. profile constraints and checksums yield exactly one recovered rotation; and
6. the recovered rotation's SHA-256 identity equals an independently admitted
   digest for the exact header-selected index.

The public oracle covers all 228 normal v103 selector indexes `0..227` with
unique lowercase SHA-256 fingerprints only. No upstream 49-byte rotation rows
or upstream source/comments are shipped. The project-audited identities refer
to `src/game/Tactical/Tactical_Save.cc` at these pinned revisions:

- JA2-Reborn: `743f38a6ca86c81893376c2576277db660320170`.
- Stracciatella: `8883ac43dc1b2b95a76286476f690526bc858565`.
- Common source blob: `5640f1a623f609f973020b0a617bbc00b2dafe75`.

Serializing indexes in ascending order as decimal index + `:` + digest + `\n`
(ASCII, including the final newline) has SHA-256
`594442e0803a44774ad919af5addabed5d114d4262695b433a942ee4a5f945fe`.
Oracle initialization and focused tests pin that identity to fail closed on
index misalignment. Index `124` remains
`384d8f0b52fe4413ea361c3027a3293b54d1763eb9828cc1cb0feb483c964306`;
index `139` remains
`b9cf6efc03ac27c7c1293f83df845ae077922af388f4cb149e9041edbf5f68bc`.

Public synthetic bodies use project-authored rotations and still fail the
production digest comparison, including at newly covered index `149`. A match
proves selector/body rotation identity, not whole-save authenticity or producer
family. Coverage does not admit Linux, German, or other save families and does
not enable editing or save writes. This engineering provenance conclusion is
not legal clearance. The compatibility report schema is unchanged.

## Decision matrix

| Observed evidence | Compatibility | Layout | Family |
| --- | --- | --- | --- |
| Complete frame, unique recovered rotation, and matching digest for the selected index | `SUPPORTED` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Complete frame and unique recovered rotation, but no oracle for the selected index (injected test oracle only) | `CANDIDATE` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Complete frame and ambiguous recovered rotation | `CANDIDATE` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Selected-index digest mismatch, conflicting rotation constraints, or no checksum survivor | `INCONSISTENT` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Exact identity but incomplete header or body | `TRUNCATED` | `UNKNOWN` | `UNKNOWN` |
| Unsupported selector values or dynamic laptop tails | `UNSUPPORTED_VARIANT` | `UNKNOWN` | `UNKNOWN` |
| Exactly one of version `103` and build `Build 04.12.02` matches | `INCONSISTENT` | `UNKNOWN` | `UNKNOWN` |
| Incomplete or otherwise unknown identity | `UNKNOWN` | `UNKNOWN` | `UNKNOWN` |

Changing a selector-affecting header byte while retaining the body changes the
selected index. A digest mismatch returns `INCONSISTENT`. The production oracle
covers every valid normal index; the defensive missing-oracle path remains
`CANDIDATE` and is exercised with an injected test oracle. Detection never falls
back to structural support.

Diagnostics retain only bounded structural facts: input size, printable
version/build identity, header completeness, selector compatibility, selected
index, oracle presence/match booleans, event count, framing boundaries, and
failure stage. They never retain save bytes, descriptions, profile plaintext,
names, paths, recovered rotation material, or digest values.

All layout-specific interpretation methods on `Ja2SaveInspector` snapshot the
input and apply this detector before returning a header, encrypted-profile
frame, profile table, or roster. They continue only for exactly `SUPPORTED`
plus `NORMAL_V103_BUILD_041202_NON_LINUX`; every other compatibility fails with
sanitized enum diagnostics. Lower-level format primitives remain explicit
research and unit-test boundaries and do not replace this public admission.

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

Evidence supports “detect compatibility with the normal v103 / Build 04.12.02
non-Linux layout by binding the header-selected index to an independently
admitted rotation digest, while leaving producer family unknown.” It does not
support “detect JA2 Reborn as the producer.” Issue #7 should use the narrower
wording unless a later independently falsifiable byte discriminator is admitted.
