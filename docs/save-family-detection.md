# Save-family detection v0.1

## Supported matrix

Detection is deliberately narrower than header parsing. The detector currently
returns `SUPPORTED` only for the evidenced JA2 Reborn normal non-Linux saved-game
version `103` / `Build 04.12.02` path. A supported result requires all of these
independent facts:

1. the complete version/build identity is exactly `103` / `Build 04.12.02`;
2. all header values used by the established normal encryption selector have
   evidenced encodings and ranges;
3. the bounded normal non-Linux prefix reaches a complete 170-by-716-byte profile
   table, with supported zero-used-count laptop tails; and
4. the framed ciphertext has one unique save-derived rotation satisfying the
   established reserved-byte and profile-checksum constraints.

This is a compatibility classification for the supported Reborn path, not proof
of which executable wrote a file. File size or header identity alone never yields
a supported family.

| Observed evidence | State | Family | Parsing boundary |
| --- | --- | --- | --- |
| Complete four-part Reborn path above | `SUPPORTED` | `JA2_REBORN` | Bounded profile consistency only |
| Exact identity, but truncated header/body, unsupported selector values or dynamic laptop tail | `PARTIALLY_SUPPORTED` | `UNKNOWN` | Stop at the failing structural boundary |
| Complete profile frame without unique rotation/checksum consistency | `PARTIALLY_SUPPORTED` | `UNKNOWN` | Do not parse profile fields |
| Exactly one of version `103` and build `Build 04.12.02` matches | `CONTRADICTORY` | `UNKNOWN` | Identity only |
| Incomplete or otherwise unknown identity | `UNKNOWN` | `UNKNOWN` | Identity only |

Diagnostics retain only bounded structural facts: input size, version/build
identity (printable build text only), header completeness, selector compatibility,
event count, framing boundaries, and failure stage. They never retain save bytes,
descriptions, profile plaintext, names, paths, or recovered rotation material.

## Deferred families

Stracciatella, classic JA2, 1.13, and other modified formats are not supported by
this detector. The authorized Android Stracciatella fixture proves that its
examined save shares the 432-byte v103 / `Build 04.12.02` header layout; it does
not provide a product-family discriminator or validate the complete current
profile/roster path. Consequently the shared header must not be labeled
Stracciatella or routed to the supported parser by itself.

The historical local Reborn save remains private and non-redistributable under
the fixture policy. Detection tests use only project-authored synthetic bytes to
exercise the matrix; those bytes test control flow and do not independently
claim a real save family.

Classic, 1.13, modded, Linux-layout, and contradictory identities remain
fail-closed until representative admitted fixtures and exact authoritative
layout evidence justify a narrower adapter.
