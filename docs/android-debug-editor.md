# Android debug create-new editor (Issue #72 / Draft PR #73)

> Debug/test construction only; device qualification remains outstanding.
> See the [construction status and gate results](issue-72-debug-editor-construction-status.md).

This slice is available only with `BuildConfig.DEBUG` on Android 10/API 29+.
Release builds cannot enter its UI, candidate generation, backend construction,
or recovery path. It is **not production qualified**. Core's default editor
constructor still refuses execution. The explicit
`Ja2SaveEditor.forAndroidCreateNewTesting()` factory grants only in-memory
candidate generation for the existing closed v103 Build 04.12.02 operations;
it provides no file, URI, or replacement authority. Its output remains the
sealed, defensively copied verified-candidate type.

## Phone behavior

Open a save through the existing read-only import. Select a uniquely current
hired merc and tap **Edit → create NEW save**. Choose one of the ten admitted
stats and a desired value, or one slot 7..10 and Clear/First-aid kit/Medical
kit/Toolkit with condition 1..100. Inventory is labeled as **profile inventory**:
the private immutable snapshot is parsed through the existing core profile API,
and each assertion copies the entire profile `(itemId, count, status)` triple.
Roster and live-merc membership still establish the current hired target; the
profile table alone cannot. No live/profile fields are mixed, and missing or
invalid facts are never defaulted, clamped or normalized. Only immutable scalar
assertions are retained in the session maps. The dialog sends only the desired
choice/condition, never expected-current values. `InventoryMutation.admit()`
remains authoritative: the expected triple must equal **both** serialized
profile and live inventory and the complete live object must be canonical.
A profile parsing/preparation failure disables editing while preserving the
already successful read-only presentation.

A nonempty assertion with expected status 0 fails `STRUCTURE/INVALID_REQUEST`;
a structurally valid wrong status such as 79 against actual 80 reaches
`PRECONDITION/EXPECTED_CURRENT_MISMATCH`. Neither representation wins a
disagreement. Core still rejects injuries,
profile/live disagreement, noncanonical objects, unsupported items and other
unproven states. Health/max health are absent. v102 and all failed/unsupported
states remain read-only. A stale dialog cannot act on a newly imported session.

One transaction contains one operation. Once placement starts, reopen a save
before another edit; the candidate never becomes an implicit new baseline.
Successful placement reports the actual provider display name, Downloads
subdirectory, exact content URI, byte count and SHA-256. The original is never
opened for write. Reborn can be configured to an external save directory;
exports in this slice go to **Downloads/JA2 Field Kit**, not automatically to
Reborn's configured directory. Reopen the generated save through the picker
for another edit.

## Pending Downloads adapter

The platform basis is Android's [Downloads collection](https://developer.android.com/reference/android/provider/MediaStore.Downloads),
[owner-only pending state](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING),
and [shared-storage ownership guidance](https://developer.android.com/training/data-storage/shared/media).
Downloads and pending state are available from API 29. An app can access its
own MediaStore objects without broad storage permissions. Pending blocks other
apps from opening the underlying file until publication. No permission or
manifest entry is added.

A process-wide singleton serializes placement and reconciliation. It inserts
only into `MediaStore.Downloads` on `external_primary`, with `IS_PENDING=1`,
`RELATIVE_PATH=Download/JA2 Field Kit/`, and a fixed sanitized `save` stem plus
12 candidate-hash hex digits and `.sav`. This stem intentionally avoids retaining
a source filename in the receipt. Display names never authorize writes and need
not be unique. A collision may yield a different provider name, which is queried
and reported. The destination is the newly returned provider-assigned URI.

The backend grants one writable descriptor only to the just-inserted URI and
consumes that authority even on write failure. It writes the candidate, flushes,
`FileDescriptor.sync()`s, and closes before pending readback. The machine checks
exact EOF/size/SHA-256 and queries the same object before and after each read:
ID-to-URI binding, owner package, path, actual name and pending state.
Missing or inaccessible owner metadata fails closed. Publication is exactly one
`ContentResolver.update` to that URI with only `IS_PENDING=0`. Exact readback and
metadata checks repeat after publication before recording success. The app
never shares a pending writable descriptor. Its only write path is serialized
and cannot address an imported source or arbitrary destination.

MediaStore indexed `SIZE` is nullable, advisory metadata, never byte authority
for this adapter. Pending rows can retain null, zero or stale nonzero size until
a deferred scan; the index can also lag after publication. The query preserves
null rather than manufacturing a size. Exact byte count, EOF and SHA-256 are
established by reopening the exact provider URI after writer sync/close, and
again after publication. Indexed-size disagreement is intentionally ignored:
under this adapter's contract it is not evidence of different content when
bounded exact stream readback matches candidate provenance and all URI, owner,
name, path and pending-state checks pass on both sides of the read. No index
value can substitute for or bypass these checks. Receipt size is the candidate
size proven by readback, not the provider's indexed value.

The platform provider and OS are trusted to preserve newly inserted row identity
and enforce pending ownership; root, provider compromise, app-private storage
tampering and same-UID injected code are outside the model. A third-party writer
after publication may change the output: success is a verified placement receipt
at that time, not a permanent immutability promise. Later reconciliation detects
observable content/binding changes and reports uncertainty.

## Declared interruption and durability model

**Supported target: abrupt Field Kit application-process death while Android,
MediaProvider and the kernel remain running.** Power loss, reboot, kernel crash,
MediaProvider/system-server crash and storage hardware failure are not qualified.
No claim is made that ContentResolver publication commits the provider database
or its namespace durably across those events. File sync and app-private directory
sync do not establish provider namespace power-loss durability.

The receipt is one bounded app-private file in `noBackupFilesDir`, plus one
bounded temporary file (at most 2,048 bytes each). Backup is disabled. Store writes
a versioned, allowlisted record to the temporary file, flushes, fsyncs the file,
renames within that directory, then fsyncs the directory using Android `Os.open`
with `O_RDONLY`, `fstat`/`S_ISDIR`, and `Os.fsync`. Any failure prevents publication, or reports
uncertainty if publication may already have occurred. There is no copy/delete
fallback. Directory fsync is available in the chosen Android API surface; device
support still needs qualification and an error fails closed.

The record contains only random operation ID, destination URI (nullable before
insert returns), candidate hash/size, expected/actual output names, and stage.
It contains no original URI/name/path/hash, source/candidate bytes, merc name,
campaign data or requested edit values. Parser input and output are bounded;
corruption, oversized records and unknown versions fail closed.

```
PREPARING (durable before insert)
  -> CREATED_PENDING (exact returned URI durable before write)
  -> BYTES_VERIFIED_PENDING
  -> PUBLICATION_ATTEMPTED (durable before update)
  -> VERIFIED_PUBLISHED (only after final readback and receipt persistence)
```

A crash between insert and recording the returned URI can leave an unidentified
pending orphan. The pre-insert PREPARING record blocks further placement; the app
does not search by name or claim disposal authority over that orphan. MediaStore
may expire pending items according to its own policy. No app-driven delete,
cleanup, overwrite, restoration or publication retry is implemented.

Startup/resume and **Recheck export status** reread/query only the exact recorded
URI. Published matching bytes/binding reconcile to success even with null/stale
indexed size. Exact pending bytes with a recorded actual name and a durable
`CREATED_PENDING`, `BYTES_VERIFIED_PENDING` or `FAILED` receipt prove publication
was not attempted; reconciliation persists `VERIFIED_RETAINED_PENDING`. This
also recovers old receipts blocked by indexed SIZE after a complete write.
It reports retained/not published, never published success. A later explicitly
requested export rechecks the retained object before replacing its receipt and
inserts a separate fresh URI. The old object is neither rewritten, published nor
deleted; provider expiry may eventually remove it. A request that first resolves
an old receipt reports that the current edit was not exported.

Missing, changed, unknown or unverifiable objects remain UNCERTAIN and block
exports, including an old null-SIZE failure before any bytes were written.
`PUBLICATION_ATTEMPTED` and `UNCERTAIN` pending receipts remain blocked even when
bytes match. A formerly published object observed pending remains UNCERTAIN.
Recovery performs no media writes, retries or cleanup. Successful or verified
retained receipts can be replaced by a later export, keeping only the current/last
operation. No export history is kept. A temporary receipt without a committed
record is ambiguous; with a committed record, the last atomic committed state is
recovery authority.

## Qualification boundary

Deterministic JVM tests exercise real core-verified synthetic candidates through
a fake provider and fault-injected journal, including each durable transition,
insert/receipt and publication/receipt gaps, same-size tampering, wrong ownership,
wrong binding/path, truncated/overlong bytes, pending failure, publication errors,
post-publication errors and receipt persistence errors. Recovery cannot call a
write, publication or deletion API. Static wiring tests check source isolation,
closed UI inputs, snapshot clearing, permission absence and release/API gates.
File-journal tests exercise serialization bounds, atomic receipt replacement,
interrupted temporary records and failed directory-sync reporting.
Independent indexed-size scenarios include null on insert, zero after writing,
stale nonzero values, null/stale published metadata and Android-10-like scanning
deferred until publication, with a synchronous-index control. Stream truncation,
extension and same-length hash drift refuse even if the index claims the expected
size. Recovery tests cover safe retained receipts, subsequent fresh exports,
receipt persistence failures and unresolved publication attempts.

These tests qualify the deterministic state machine under its backend contract.
They do **not** establish actual OEM/provider behavior. Before future release
or production enablement, Chris's device qualification must include Android 10+
(on API 29 as well as a current supported device), duplicate/concurrent names,
actual provider-assigned name, indexed-size lag and exact stream size,
exclusive pending access from another app, complete-only reader visibility across
publication, writable-handle closure,
actual owner metadata, URI binding, injected process death at every transition,
reconciliation after death, and file/directory sync behavior. Verify the new save
in Reborn with the original independently preserved. No private user save is
read or mutated by the construction/test run. Hosted CI remains synthetic only.

The generic [transactional safety contract](transactional-edit-safety.md) remains
unchanged: no source replacement is supported and no other output adapter is
qualified by this work.
