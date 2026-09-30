# Issue #72 Android debug editor construction

Parent HEAD: `42df47b3618436f697acb51cb0532b9c8c1bc482`.
Branch: `feature/issue-72-save-as-editing`.
Authoritative main/merge base: `120fdc9aeba70b3243c55bff7a2a89ae338533a1`.

This slice preserves and completes the prior Android editor/MediaStore worktree.
It is debug/test construction, not production or device qualification. No push,
Ready transition, merge, artifact publication or private-save access is included.

## Bounded precondition repair

`EditSession` now calls the existing core profile parser on its private immutable
source snapshot, after the exact v103 eligibility gate. Current unique hired
membership still comes from roster/live state. Slots 7..10 copy the complete
profile `(itemId, count, status)` into immutable `ExpectedSlot` assertions; no
live item/count fields are mixed in and no missing/invalid value is defaulted,
clamped or normalized. The unknown live-payload cast is removed. The session
maps retain scalar assertions, not profile/parser objects. The dialog labels
these as profile inventory and states that live inventory must agree. It submits
only desired choices/condition.

`InventoryMutation.admit()` is unchanged: expected values must equal BOTH
serialized representations, with all complete canonical-object restrictions.
Profile/live disagreement remains a refusal. Correction to the previous status:
nonempty expected status 0 fails `STRUCTURE/INVALID_REQUEST`, while valid status
79 against actual 80 reaches `PRECONDITION/EXPECTED_CURRENT_MISMATCH`.

Editor preparation exceptions now leave the successful read-only presentation
usable and clear the task-local import buffer. The private session buffer is
cleared if parsing throws. Caller mutation, immutable maps, consumed/cleared
sessions and stale-dialog identity checks cannot substitute UI preconditions.
The existing production editor factory is used by default; JVM integration tests
inject the actual core editor with its manifested synthetic rotation oracle.
No synthetic test support is packaged as a runtime dependency.

The two earlier placement repairs remain: reconciling an earlier success during
a new request explicitly says the current edit was not exported; an already
published object later observed pending becomes UNCERTAIN and blocks export.

## Product and persistence scope

Ten admitted stats, no health/max health. Inventory slots 7..10 offer Clear and
IDs 201/202/203, count 1 and desired condition 1..100. One operation per
transaction; placement consumes the session. v102/unsupported formats remain
read-only. UI, execution and backend construction require debug/API 29+ gates.
The default core constructor remains disabled. Source URIs never enter placement.

Pending MediaStore Downloads create-new retains the provider-assigned URI as
identity, `IS_PENDING=1`, exact write/flush/fd sync/close, bounded hash/size/EOF
readback, durable publication-intent journal, same-URI `IS_PENDING=0` update and
post-publication reread/query verification. No existing URI write, document-create
picker, overwrite, copy/delete, raw-path fallback or broad storage permission.

The declared crash model is **Field Kit app-process death while MediaProvider,
OS and kernel remain running**. No power-loss, reboot or provider-crash claim.
The no-backup journal is one receipt plus one temporary file, each bounded to
2,048 bytes, with file fsync, same-directory rename and directory fsync. It stores
only output identity/status. Startup/resume reconciliation reads the exact URI;
it never republishes or deletes. Unknown insert/receipt gaps and unresolved
pending, changed, missing or unverifiable outputs block further export.

## Verification

- A: focused `:core:test --tests '*SaveEdit*Test'`: 79 passed.
- B: focused `:android-app:testDebugUnitTest --tests '*.editing.*'`: 64 passed
  (47 session, 11 placement, 3 journal, 3 wiring).
- C: complete `:core:test`: 207 passed.
- D: complete `:android-app:testDebugUnitTest`: 165 passed, zero failures/errors/skips.
- E: `:android-app:lintDebug`: passed, zero errors and 8 warnings (3 SetTextI18n;
  one each UseRequiresApi, DataExtractionRules, ObsoleteSdkInt, IconLauncherShape,
  UseKtx). No lint suppression or baseline was added.
- F: `:android-app:assembleDebug`: passed.
- G: fixture/privacy/workflow `unittest discover -s tools/tests -v`: 69 passed.
- H: pinned hired-stat audit: 34 profile and 20 soldier offsets passed.
- I: pinned inventory audit: 5 profile offsets, live extent [12,696), 11 object
  offsets/36-byte extent and 3 item definitions passed. Both audits read public
  git objects at `743f38a6ca86c81893376c2576277db660320170`.
- J: `git diff --check`: passed.
- K: source manifest and debug APK: no broad storage permission; source backup
  remains disabled.
- L: built debug APK contains all six expected editor/core classes and dialog
  text/resources, is debuggable, and contains no synthetic test classes/fixtures.
- M: `:android-app:assembleRelease` and release vital lint passed. Generated
  release `BuildConfig.DEBUG` is false; compiled bytecode passes constant false
  to editor availability and both backend construction and `DebugPlacement.get`
  unconditionally throw. The unsigned release APK is not debuggable and has no
  broad storage permissions. It was built only to verify gating, not published.

All test counts above have zero failures/errors/skips. The final combined Gradle
invocation completed successfully (106 tasks: 63 executed, 11 from cache,
32 up-to-date). No inventory admission/verifier semantics changed.

The full Android run initially identified a stale exact dependency allowlist.
It now explicitly includes the new `testImplementation` synthetic fixture support;
the runtime dependency list remains unchanged. Gradle required local socket
access and the Python suite required running outside the outer sandbox for its
own bubblewrap tests; neither fixture isolation nor product policy was weakened.

On-device API 29/current Android provider and process-death qualification, plus
Reborn validation of a user-chosen generated save, remain required before
production enablement.

## Changed files

- `core/src/main/java/com/phishtopia/ja2fieldkit/core/Ja2SaveEditor.java`
- `core/build.gradle.kts`
- `core/src/test/java/com/phishtopia/ja2fieldkit/core/SaveEditAuthorityTest.java`
- `core/src/test/kotlin/com/phishtopia/ja2fieldkit/core/SaveEditTransactionTest.kt`
- `android-app/build.gradle.kts`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/InspectionViewModel.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/MainActivity.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/EditDialog.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/importing/SourceMetadata.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/editing/EditSession.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/editing/PlacementMachine.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/editing/FilePlacementJournal.kt`
- `android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/editing/AndroidDownloadsBackend.kt`
- `android-app/src/test/kotlin/com/phishtopia/ja2fieldkit/android/InventoryGroupingWiringTest.kt`
- `android-app/src/test/kotlin/com/phishtopia/ja2fieldkit/android/editing/EditSessionTest.kt`
- `android-app/src/test/kotlin/com/phishtopia/ja2fieldkit/android/editing/PlacementMachineTest.kt`
- `android-app/src/test/kotlin/com/phishtopia/ja2fieldkit/android/editing/FilePlacementJournalTest.kt`
- `android-app/src/test/kotlin/com/phishtopia/ja2fieldkit/android/editing/EditorWiringTest.kt`
- `README.md`
- `docs/architecture.md`
- `docs/release-privacy-checklist.md`
- `docs/save-edit-transactions.md`
- `docs/transactional-edit-safety.md`
- `docs/android-debug-editor.md`
- This construction status report.
