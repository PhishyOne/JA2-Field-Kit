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

The public oracle is deliberately minimal. It contains one non-secret digest
for index `139`, derived from the 49-byte row at that index in
`src/game/Tactical/Tactical_Save.cc` at immutable JA2 Reborn commit
`743f38a6ca86c81893376c2576277db660320170` (source blob
`5640f1a623f609f973020b0a617bbc00b2dafe75`). The row bytes and the other 227
production rows are not stored in this repository. Index `139` is admitted
because the public synthetic header independently exercises that selector
index; the synthetic body intentionally uses a different project-authored
rotation and therefore does not become supported under the production oracle.

The digest establishes selector/body identity against that pinned table. It
does not authenticate the whole save or identify which compatible executable
wrote it.

## Decision matrix

| Observed evidence | Compatibility | Layout | Family |
| --- | --- | --- | --- |
| Complete frame, unique recovered rotation, and matching digest for the selected index | `SUPPORTED` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Complete frame and unique recovered rotation, but no oracle for the selected index | `CANDIDATE` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Complete frame and ambiguous recovered rotation | `CANDIDATE` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Selected-index digest mismatch, conflicting rotation constraints, or no checksum survivor | `INCONSISTENT` | `NORMAL_V103_BUILD_041202_NON_LINUX` | `UNKNOWN` |
| Exact identity but incomplete header or body | `TRUNCATED` | `UNKNOWN` | `UNKNOWN` |
| Unsupported selector values or dynamic laptop tails | `UNSUPPORTED_VARIANT` | `UNKNOWN` | `UNKNOWN` |
| Exactly one of version `103` and build `Build 04.12.02` matches | `INCONSISTENT` | `UNKNOWN` | `UNKNOWN` |
| Incomplete or otherwise unknown identity | `UNKNOWN` | `UNKNOWN` | `UNKNOWN` |

Changing a selector-affecting header byte while retaining the body changes the
selected index. If an oracle exists for that changed index and its digest does
not match the recovered body rotation, detection returns `INCONSISTENT`. If the
changed index has no admitted oracle, detection returns only `CANDIDATE`; it
never falls back to structural support.

Diagnostics retain only bounded structural facts: input size, printable
version/build identity, header completeness, selector compatibility, selected
index, oracle presence/match booleans, event count, framing boundaries, and
failure stage. They never retain save bytes, descriptions, profile plaintext,
names, paths, recovered rotation material, or digest values.

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
