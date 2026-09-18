# Android read-only shell v0.1

## Operational boundary

The Android app is a read-only consumer of
`Ja2SaveInspector.inspectV01(ByteArray)`. It obtains one user-selected
`content://` URI, queries only `OpenableColumns.DISPLAY_NAME` and `SIZE`, reads
the stream into bounded bytes, closes the stream, and discards the bytes after
inspection. It does not resolve a filesystem path and core never receives the
URI or provider metadata.

The input limit is **16 MiB**. The admitted real Android save recorded in the
fixture manifest is 2,563,321 bytes, so the limit leaves more than six times
the observed size for ordinary campaign growth while bounding memory use and
hostile or mistaken provider responses. A provider-declared oversized value is
rejected before stream reading; unknown or inaccurate sizes are still enforced
while streaming.

Display filenames are reduced to a leaf name, stripped of control characters,
and limited to 120 Unicode code points. No URI, filesystem path, provider
exception, save bytes, or parser-internal diagnostic reaches presentation.

## Intent and document-provider matrix

| Entry | Advertised matching | Current behavior | Limitation |
|---|---|---|---|
| In-app **Open save** | `ACTION_OPEN_DOCUMENT` with `*/*` | Accepts one openable `content://` document and validates its bytes in core | Android document providers commonly report `.sav` as generic or unknown MIME, and the picker cannot portably filter by extension |
| Open with | `ACTION_VIEW`, `content://`, JA2 MIME `application/x-ja2-save` | Reads one URI | The custom MIME is uncommon but precise |
| Open with | `ACTION_VIEW`, `content://`, `application/octet-stream`, URI path ending `.sav` | Reads one URI when Android's resolver can match the provider URI path | Intent filters match the URI path case-sensitively, not `OpenableColumns.DISPLAY_NAME`; providers that hide the filename may not offer the app |
| Share to | `ACTION_SEND`, JA2 MIME `application/x-ja2-save` | Reads exactly one stream URI from `ClipData` or `EXTRA_STREAM` | Generic `application/octet-stream` is deliberately not claimed because it would advertise the app for every binary share |
| Multiple share | Not advertised | Rejected with a sanitized source error if delivered explicitly | `ACTION_SEND_MULTIPLE` is unsupported |
| `file://`, folders, scanning | Not advertised | Rejected or absent | Only user-granted single-document `content://` access is supported |

The broad `*/*` picker filter is an explicit in-app user action, not an intent
ownership claim. Core detection remains the authority for whether selected
bytes are a supported JA2 save.

## Permissions and URI grants

The manifest declares no permissions. The picker, open-with sender, or share
sender grants temporary access to the chosen content URI. The app does not ask
for `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`,
`MANAGE_EXTERNAL_STORAGE`, folder access, or permanent/persistable URI access.
It has no background service.

## Original and generated-save semantics

v0.1 opens the provider stream in read mode and has no write, create-document,
save-copy, export, or edit control. The original is never modified. This slice
does not produce a generated save at all.

That boundary is compatible with the future import/save design tracked by
issue #10: when generated saves are eventually introduced, they must be new
outputs with explicit provenance and must not turn this import URI into an
in-place write target. Issue #10 is not implemented or closed by this shell.

## Toolchain

- JDK/JVM 21 and Kotlin 2.4.10, matching core
- Gradle 9.5.0
- Android Gradle Plugin 9.3.2 (the 9.3 line declares Gradle 9.5.0 support)
- compile/target SDK 37; Build Tools 36.0.0, supported defaults for AGP 9.3
- minSdk 23, matching the current AndroidX default baseline
- AndroidX Activity 1.13.0 for Activity Result APIs and ViewModel ownership

The UI uses platform views and no Compose, navigation, persistence, network,
analytics, dependency-injection, or background-work dependency.
