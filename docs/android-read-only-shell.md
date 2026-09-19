# Android read-only shell v0.1

## Operational boundary

The Android app is a read-only consumer of
`Ja2SaveInspector.inspectV01(ByteArray)`. Its single importer accepts a stream
from one user-granted `content://` URI and optional provider metadata: display
name, declared size, and last-modified timestamp. It sanitizes the filename,
counts and SHA-256 hashes the bytes while reading, closes the stream, lends the
private byte snapshot only to the immediate inspection call, and discards it
after presentation mapping. Core receives bytes only; it never receives the
URI, source category, or provider metadata.

The input limit is **16 MiB**. The admitted real Android save recorded in the
fixture manifest is 2,563,321 bytes, so the limit leaves more than six times
the observed size for ordinary campaign growth while bounding memory use and
hostile or mistaken provider responses. A provider-declared oversized value is
advisory only and cannot reject otherwise readable content; declared size is at
most a safely capped allocation hint. The limit is enforced solely on bytes read,
and actual byte count is authoritative when a provider's declared size differs.
Size and lowercase hexadecimal SHA-256 provenance are published only for a
complete accepted import, never for a rejected prefix.

Display filenames are reduced to a leaf name, have control, format,
line-separator, and paragraph-separator characters replaced with spaces, and
are limited to 120 Unicode code points. Retained provenance is limited to that
filename, entry category, actual size, optional provider-declared size, optional
provider last-modified timestamp, and the SHA-256 of the exact imported bytes.
No URI, filesystem path, provider/account identifier, provider exception, save
bytes, or parser-internal diagnostic reaches presentation.

## Local, USB, and cloud documents

Local files and Downloads, USB storage exposed by an installed Android document
provider, and cloud storage exposed by an installed Android document provider
all enter through the same Storage Access Framework (SAF) document picker and
the same importer. USB and cloud support therefore require the relevant storage
or cloud app/provider to participate in SAF. Field Kit does not scan mounted
filesystems, request broad storage access, sign in to cloud accounts, upload
content, or integrate provider-specific SDKs.

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
The current read-only flow uses the explicit user grant only for the immediate
import and does not call `takePersistableUriPermission` or retain URI history.
It has no background scanner or service.

## Original and generated-save semantics

v0.1 opens the provider stream in read mode and has no write, create-document,
save-copy, export, or edit control. The original is never modified. This slice
does not produce a generated save at all.

Open-with (`ACTION_VIEW`) and share-to (`ACTION_SEND`) use the same bounded
import/provenance result as the in-app `ACTION_OPEN_DOCUMENT` picker. Multiple
shares remain unsupported. `ACTION_OPEN_DOCUMENT_TREE`, desktop/LAN transfer,
QR or short-code pairing, recent-file databases, persistent grants, export, and
write-back remain future work for issue #10.

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

CI bootstraps Google's command-line-tools package `15859902` using its pinned
published SHA-256, then installs only platform package `android-37.0` and the
declared Build Tools into the ephemeral runner directory. No SDK or generated
wrapper binary is checked in.

The UI uses platform views and no Compose, navigation, persistence, network,
analytics, dependency-injection, or background-work dependency. Its only
outbound presentation action is the explicit text-only compatibility report
described in [compatibility-report-v0.1.md](compatibility-report-v0.1.md); that
action shares no save or source reference.

The activity draws edge to edge and applies AndroidX system-bar and display-cutout
insets on all four edges. Each inset dispatch is added to the fixed design
padding rather than the view's current padding, so rotations and repeated inset
delivery cannot accumulate spacing.
