# Release and privacy posture

This document is the authoritative release/privacy inventory for JA2 Field Kit.
It records the Android app audited at commit
`069a272e06e95b51bb0f7a12ed55a098dab20273`; a release decision must audit the
exact shipping commit again. It is a future-release gate, not evidence that a
Play Console application, testing track, or release exists.

Field Kit is currently a local, read-only Android save inspector. In this
document, **local processing** means computation performed by Field Kit on the
device, **ephemeral** means process/task memory rather than app-managed durable
storage, **OS-mediated export** means an explicit user action that hands data to
Android's clipboard or chooser, and **Field Kit transmission** means network
traffic initiated by this app. OS-mediated export can let another chosen app
retain or transmit data; it is not automatic collection or upload by Field Kit.

## Actual build identity

Inspected from `android-app/build.gradle.kts` on **2026-09-19**:

| Build property | Current value |
|---|---|
| `namespace` | `com.phishtopia.ja2fieldkit.android` |
| `applicationId` | `com.phishtopia.ja2fieldkit` |
| `minSdk` | 23 |
| `targetSdk` | 37 |
| `compileSdk` | 37 |
| `versionCode` | 1 |
| `versionName` | `0.1.0` |

Starting **2026-08-31**, Google Play requires new Android mobile apps and app
updates to target Android 16 / API 36 or higher. The current `targetSdk` 37
meets that submission floor as of 2026-09-19. This requirement is
time-sensitive: recheck the official
[Play target API requirement](https://support.google.com/googleplay/android-developer/answer/11926878)
and [Android target API guidance](https://developer.android.com/google/play/requirements/target-sdk)
against the exact release build immediately before submission.

## Audited current boundary

- Application ID: `com.phishtopia.ja2fieldkit`.
- The source app manifest declares zero `uses-permission` elements and no
  `INTERNET` permission. The generated debug merged manifest does contain the
  app-scoped
  `com.phishtopia.ja2fieldkit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  signature permission and a `uses-permission` for it, both injected by
  `androidx.core:core:1.18.0`. This package-scoped AndroidX infrastructure does
  not grant Field Kit network or save access. The merged manifest still has no
  `INTERNET` permission, user-granted broad storage permission, Field Kit
  service, or background save scanner.
- `androidx.profileinstaller:profileinstaller:1.4.0` contributes the non-exported
  `androidx.startup.InitializationProvider`, its `ProfileInstallerInitializer`
  metadata, and the exported `androidx.profileinstaller.ProfileInstallReceiver`,
  which is guarded by `android.permission.DUMP`. These are transitive AndroidX
  runtime-infrastructure entries, not additions made by this documentation PR;
  they do not grant Field Kit network or save access. Their presence must still
  be acknowledged and reviewed in the final release manifest audit.
- The dependency graph is `:core`, AndroidX Activity, Kotlin/JUnit test support,
  and Android build tooling. It contains no network stack, analytics,
  telemetry, crash-reporting, advertising, account, cloud-provider, database,
  or background-work SDK.
- All three import routes converge on the same bounded importer. It accepts one
  temporary `content://` stream selected through the document picker or made
  readable by a sender's URI grant, and does not request a persistable URI grant
  or derive a filesystem path. The maximum accepted and retained complete
  payload is 16 MiB. For an oversized rejected stream, the implementation may
  transiently read beyond that boundary by up to one current 64 KiB buffer read
  before throwing `SaveTooLargeException`; the failed input is not retained as
  an accepted save.
- Provider-declared size is advisory. The byte count measured while streaming
  is authoritative, and a completed import receives a lowercase SHA-256 over
  exactly those bytes.
- Field Kit has no app-managed storage of saves, recent sources, reports, or
  user data. The `ViewModel` retains presentation state in memory across an
  activity configuration change; it does not persist that state to disk.
- There is no save editing, generated-save write/export, PC bridge, account,
  cloud relay, or Nomatoka link/card implementation.

The implementation details and narrower contracts remain documented in
[Architecture](architecture.md), [Android read-only shell](android-read-only-shell.md),
and [Compatibility report v0.1](compatibility-report-v0.1.md). This inventory
summarizes their release/privacy consequences rather than replacing them.

## Data-flow inventory

| Feature or state | Data accessed | Retained or persisted by Field Kit | Transmitted outside Field Kit | User action required | Android permission or capability | Release / Data Safety review note |
|---|---|---|---|---|---|---|
| Document picker import (current) | One user-selected provider `content://` stream | URI exists only for the immediate import; bytes are ephemeral as described below; no history or disk persistence | No Field Kit network transmission | Tap **Open save**, choose one document | `ACTION_OPEN_DOCUMENT` and a temporary provider grant; no user runtime storage permission and no persistable grant | Recheck that the shipping flow still selects one document and does not broaden storage access. The merged manifest's AndroidX package-scoped signature permission does not provide save access |
| Open With import (current) | One `content://` URI delivered by another app | Incoming source is consumed once and removed from the activity intent; no URI history or disk persistence | Local processing only; no automatic network transmission by Field Kit | No confirmation is required by Field Kit. Invocation may come from normal user-mediated Android UI or another installed app explicitly launching the exported activity with `ACTION_VIEW` and the required temporary URI grant | Manifest filters advertise narrow MIME/path combinations, but an explicit intent can address the exported activity directly. The sender's temporary read grant bounds URI access; there is no user runtime storage permission, arbitrary filesystem/provider access, or persistent grant | Treat this as an exported, potentially sender-triggered input surface, not as necessarily user-directed. Content-only validation, the 16 MiB accepted-payload cap with the documented current 64 KiB oversize-read behavior, and importer/parser rejection of malformed or unsupported input remain fail-closed. The AndroidX package-scoped signature permission does not provide save access |
| Share To import (current) | Exactly one `content://` stream URI from `ClipData` or `EXTRA_STREAM`; multiple items are rejected | Incoming source is consumed once and removed from the activity intent; no URI history or disk persistence | Local processing only; no automatic network transmission by Field Kit | No confirmation is required by Field Kit. Invocation may come from normal user-mediated Android UI or another installed app explicitly launching the exported activity with `ACTION_SEND`, one stream URI, and the required temporary URI grant | The manifest advertises `application/x-ja2-save`, but an explicit intent can address the exported activity directly. The sender's temporary read grant bounds URI access; there is no user runtime storage permission, arbitrary filesystem/provider access, or persistent grant | Treat this as an exported, potentially sender-triggered input surface, not as necessarily user-directed or as an upload by Field Kit. Single-item/content-only validation, the 16 MiB accepted-payload cap with the documented current 64 KiB oversize-read behavior, and importer/parser rejection of malformed or unsupported input remain fail-closed. The AndroidX package-scoped signature permission does not provide save access |
| Provider metadata (current) | Sanitized leaf display filename, nonnegative provider-declared size, optional positive last-modified timestamp, and import-route category | Retained ephemerally in presentation state after a complete import; filename may also appear while loading. No app-managed persistence | None unless a user separately exports visible information; filename, declared size, and timestamp are excluded from compatibility reports | Document-picker access follows explicit selection; accepted Open With/Share To access may instead be sender-triggered | Provider query through the route's temporary URI grant | Filename controls/separators are replaced, path components removed, and length capped at 120 code points. Declared size is advisory; actual streamed bytes govern the limit |
| Content URI, path, and provider identifiers (current) | The Android layer consumes the content URI; it does not derive a filesystem path or intentionally query provider/account identifiers | URI is captured only by the in-flight import task and is not placed in retained presentation state, recent history, or persistent storage; no persistent URI grant | None | Document-picker access follows explicit selection; accepted Open With/Share To access may instead be sender-triggered | Temporary URI grant | Confirm URI/path/provider identifiers remain absent from UI state, reports, logs, analytics, and crash uploads |
| Complete imported save bytes (current) | Exact stream contents, only after a bounded complete read | Private task-local `ByteArray`, lent to the two immediate read-only inspection calls (`inspectV01` and `inspectLiveMercState`) and then eligible for disposal; never exposed as screen state or written by the app | None | Document-picker access follows explicit selection; accepted Open With/Share To access may instead be sender-triggered | In-process memory; no permission beyond the route's temporary read grant | Maximum accepted and retained complete payload is 16 MiB. An oversized rejected stream may be transiently read beyond that boundary by up to one current 64 KiB buffer read before rejection, but is not retained as an accepted save. Provider-declared size remains advisory; measured streamed bytes govern acceptance. Reassess memory and disclosure posture if the limit, buffer, or lifecycle changes |
| Measured byte count (current) | Count of bytes actually streamed | Retained ephemerally as source provenance; included in eligible compatibility-report inputs | Only through explicit clipboard/share export of a previewed report | Import; then Preview and Copy/Share for export | Local counting; optional OS-mediated export | This is authoritative even when provider-declared size differs |
| Source SHA-256 (current) | Digest of the exact complete imported byte snapshot | Retained ephemerally as source provenance; included in eligible compatibility-report inputs | Only through explicit clipboard/share export of a previewed report | Import; then Preview and Copy/Share for export | Local hashing; optional OS-mediated export | A hash can still correlate identical saves. Keep it purpose-limited to integrity/compatibility diagnostics and disclose it in any actual export flow |
| Parser and structured diagnostics (current) | Local format detection, bounded parsing, and enumerated failure kind/diagnostic | Sanitized logical results or codes are retained ephemerally in presentation state; raw exceptions, offsets, keys, rotation details, and byte fragments are not retained there | Only allow-listed failure facts can leave through explicit report export | Import; export requires Preview followed by Copy or Share | Local computation | Store declarations must describe actual shipped diagnostics behavior; adding automatic error/crash submission is a separate review |
| Successful parsed campaign, merc profile, roster, and live-inventory presentation (current) | Save version/build/layout/family evidence; campaign day/time/sector/count/balance; verified roster names, nicknames, profile/base stats, ten live/current tactical stat facts, and for each canonical live-inventory slot only its presentation label plus Empty or numeric item ID and object count | Retained ephemerally in screen state; no raw save bytes, inventory raw records/payloads, database, file, recent-save record, or cloud persistence | No compatibility-report path is offered for success and Field Kit transmits nothing | Accepted import of a supported save; picker selection or incoming intent as described above | Local computation and UI | Gameplay/save-derived data remains on device. Screenshots or user copying selectable UI text are user/OS actions outside an app upload flow |
| Merc selection (current) | Exact numeric profile index from the validated roster/live join | Ephemeral ViewModel presentation state; survives configuration changes within that ViewModel lifetime, resets on new inspection, no durable storage | None | Choose a merc in the labeled native selector | Local presentation only; no re-import or inspection | Shows one merc's existing stat sections and 19 inventory slots; names are not identity keys |
| Compatibility-report safe input facts (current) | Entry category, actual byte count, source SHA-256, allow-listed format facts, and enumerated failure facts | Retained ephemerally only for a completed structured inspection failure; no serialized JSON yet | None before explicit export | Import must reach an eligible failure | Local computation | Excludes filename, declared size/timestamp, URI/path/provider identifiers, save bytes, campaign/roster data, free text, and parser internals |
| Compatibility report Preview (current) | Safe inputs plus compiled app version | Exact deterministic JSON is generated only after **Compatibility report** is tapped and retained as one immutable in-memory preview | Preview itself causes no transmission | Explicit Preview tap after an eligible failure | Local UI only | Review the schema and visible privacy notice against the shipping implementation |
| Copy report to clipboard (current) | Exact immutable preview text | Field Kit adds no separate copy; Android's clipboard may retain the text according to OS behavior | OS-mediated clipboard export, not Field Kit network transmission; another app/user may subsequently use it | Explicit **Copy report** tap | Android clipboard API; no user runtime permission required | Disclose the clipboard handoff accurately and do not call it automatic upload |
| Share report (current) | Exact immutable preview text | Field Kit adds no durable copy | OS-mediated `ACTION_SEND` handoff to a user-chosen receiver. That receiver may store or transmit it; Field Kit does not select a destination or use the network | Explicit **Share report** tap plus chooser selection | Android chooser, `text/plain` `EXTRA_TEXT`; no URI, attachment, `ClipData`, or grant flags | Data Safety and privacy text must distinguish this user-initiated transfer from app collection or automatic sharing |
| Local storage and persistence (current) | No app database/preferences/file cache of saves or recent sources | No app-managed persistence. Normal Android/runtime/build artifacts are not a product data store | None | None | `allowBackup="false"`; no user-granted broad storage permission | Re-audit app storage APIs and backup behavior on the exact release artifact |
| Network (current) | None | None | No Field Kit network traffic | None | No `INTERNET` permission or network dependency | Any future network capability is a material posture change requiring inventory, threat-model, permission, privacy-policy, and Data Safety review |
| Analytics and telemetry (current) | None | None | None | None | No analytics/telemetry SDK or permission | Play Console aggregate metrics, not an embedded SDK, are the install-base plan |
| Crash or error upload (current) | Local bounded failure handling only | No crash/error upload queue or SDK | None | None | No crash-reporting SDK or network capability | Any future diagnostics upload requires a separate purpose, minimization, consent/disclosure, retention, and vendor review |
| Advertising (current) | None | None | None | None | No advertising SDK or identifier capability | Product posture is no third-party ads. A future first-party developer card is addressed separately below |
| Identifiers and accounts (current) | No advertising ID, app-set ID, device/account identifier, login, or Field Kit account | None | None | None | No account/auth/provider SDK | Re-audit transitive SDK behavior and store declarations for every release |
| Generated-save editing/export (deferred) | Would access original bytes, requested edits, generated bytes, integrity facts, and output destination | Not implemented | Not implemented | Future explicit edit and export actions | Undecided; must not turn the input grant into implicit in-place write access | **Issue [#12](https://github.com/PhishyOne/JA2-Field-Kit/issues/12) is a mandatory design gate.** Require core-owned source-byte and bounded structural request/assertion admission before bulk copying or proportional work, immutable admitted inputs, finite component/count and aggregate input/canonical budgets, and bounded expanded plans/reports/provenance. Required concrete limits must be fixed with workload/expansion and memory/CPU evidence before editing is enabled; callers/adapters cannot raise them. Preserve strictest-bound private candidate serialization, re-admission/reparse, complete verification, and provenance. Separate create-new placement requires protected and verified private staging, qualified atomic no-replace publication of the complete object, then byte/binding verification and required data/namespace durability; unsupported semantics fail closed. Ambiguous publication preserves uncertainty/evidence pending reconciliation before further mutation; never overwrite the source by default. Replacement consumes only an immutable core verified candidate/provenance with explicit intent and exact destination authority. Protect operation-owned staging bytes, identity, and binding continuously from verification through atomic installation, including the destination-guard handoff. Hold a qualified destination content/name-binding guard from final expected-original comparison through backup creation/verification/durability, commit, installed verification/durability, persisted outcome/recovery recording, and release. Create an independent backup from the guarded original, verify and protect its bytes/object/binding, and bind it to that exact original and transaction; establish backup data/namespace durability and recoverable transaction association before any destination mutation. Success requires installed byte/binding verification, installed data/namespace durability, and persisted outcome/recovery recording while guarded. Pre-mutation failure leaves the destination untouched by the operation; once commit may have occurred, missing evidence means UNCERTAIN, retaining durable backup and available staging/evidence without blind retry/restore/delete/cleanup. After crash/guard loss, reacquire the guard and reconcile all artifact identities/hashes, persisted evidence, and competing changes before mutation. Restoration is a separate guarded conditional replacement using durable verified backup authority bound to the exact original. Cleanup requires proven ownership and disposal authority protected through disposal; success alone never authorizes backup deletion without separate proof of safety and no remaining recovery obligation. Unsupported replacement semantics fail closed. See [the output-placement contract](transactional-edit-safety.md#output-placement-and-later-replacement) |
| Local PC bridge and LAN addresses (deferred) | Would access pairing/session material, local addresses, transfer metadata, save bytes, and integrity hashes | Not implemented | Not implemented; intended future transport is direct local network, not a cloud relay | Future explicit pairing and transfer actions | Undecided and subject to then-current Android local-network/network requirements | **Issue [#13](https://github.com/PhishyOne/JA2-Field-Kit/issues/13) is a mandatory protocol/threat-model gate.** Do not add network permission, discovery, background service, account, or relay in advance |
| Nomatoka first-party link/card (deferred) | A future external link could expose the destination and normal browser request metadata after a user tap | Not implemented | None by Field Kit today. A future external-browser handoff is user-initiated; embedded content or app networking would be a different data flow | Future explicit tap | Prefer an external browser intent if approved; no ad SDK | Must be clearly labeled first-party developer promotion, not an ad-network integration. Review destination, wording, referrer behavior, listing policy, and disclosures before shipping |

The current Open With and Share To paths begin parsing an accepted incoming
intent without a separate Field Kit confirmation screen. If a future product
requirement calls for guaranteed human confirmation before parsing external
intents, satisfying it requires a separate runtime/UI change and corresponding
tests and threat review; it is not part of this documentation change. This does
not alter compatibility-report export: Preview, Copy, and Share remain separate,
explicit user actions, and report Share uses an Android chooser.

## Install-base decision for Issue #11

Verified **2026-09-19** against Google's official
[App statistics documentation](https://support.google.com/googleplay/android-developer/answer/139628):

- **Installed Audience** is the primary user-level answer to “roughly how many
  people currently have Field Kit installed?” It counts users who have the app
  installed on at least one device and who have used that device (not
  necessarily the app) in the past 30 days.
- **Install base** is the device-level companion metric: active devices on
  which the app is installed, where active means turned on at least once in the
  past 30 days.
- Total installs/acquisitions remain useful growth measures, but they do not
  answer the current installed-audience question.
- These are aggregate Google Play distribution metrics. Sideloaded and other
  non-Play installs are not represented by Play install-base reporting.
- Reporting may lag, and some breakdowns may be thresholded or otherwise
  privacy-adjusted. Treat the values as aggregate estimates, not an app-owned
  user ledger.
- No Firebase or third-party analytics SDK is justified solely to recreate
  these metrics. If future product analytics would answer a distinct product
  question, it requires a separate privacy/value review before any SDK,
  identifier, event, permission, or disclosure is introduced.

## Current Play policy snapshot

Verified **2026-09-19** from the linked official Google documentation. This is
a dated planning snapshot, not a completed Play Console declaration or a
substitute for checking the policies and the actual account at release time.

- **Privacy policy:** Google says apps must provide a privacy policy. A future
  Play release must have both an active Play-facing privacy-policy URL/store
  metadata as required and privacy-policy link or text accessible inside the
  released app. Publish a final policy that accurately matches the exact
  shipping app's collection, use, sharing, SDKs, and user-initiated exports; do
  not promote this inventory itself as the final policy. The current app does
  not implement an in-app privacy-policy UI or surface, and this documentation
  PR does not add one. See
  [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455).
- **Ads declaration:** the current app posture is free with no ads and no
  advertising SDK. Play still requires declaring whether the app
  contains ads; answer from the exact shipping behavior and presentation on
  the same [app-review page](https://support.google.com/googleplay/android-developer/answer/9859455).
- **App content review:** complete the content rating, target-audience,
  privacy/security declarations, and reviewer-access instructions if access is
  required. Validate every answer against the release candidate; the same
  [app-review page](https://support.google.com/googleplay/android-developer/answer/9859455)
  is the current entry point.
- **Data Safety:** complete the form from the exact shipping behavior and
  resolved SDKs. Do not pre-answer or submit that form in this documentation
  PR; user-initiated OS handoffs and any SDK behavior must be classified under
  the then-current instructions.
- **Personal-account testing:** as of 2026-09-19, personal developer accounts
  created after 2023-11-13 must run a closed test with at least 12 testers
  continuously opted in for at least 14 days before applying for production
  access. This does not apply to every account. Verify the actual account type,
  creation date, eligibility, and the current
  [testing policy](https://support.google.com/googleplay/android-developer/answer/14151465)
  immediately before release; this document makes no account-specific claim.
- **Play App Signing:** keep the developer-held upload key used to authenticate
  uploads distinct from the Play-held app-signing key used to sign distributed
  APKs. Record the release-time ownership, storage, recovery, and key-upgrade
  decisions after checking the current
  [Play App Signing guidance](https://support.google.com/googleplay/android-developer/answer/9842756).
- **Requirements change:** the
  [Play Console Requirements page](https://support.google.com/googleplay/android-developer/answer/10788890)
  carries current requirements and policy updates. Recheck it and its linked
  policy pages immediately before every real release.

## Future public Play release gate

Every checkbox applies to the exact proposed release commit and artifact. Stop
the release if documentation, the merged manifest, dependency graph, store
declarations, or observed shipping behavior disagree; reconcile them and repeat
the review rather than selecting the most convenient description.

### Product identity and claims

- [ ] Confirm ownership and long-term stability of application ID
  `com.phishtopia.ja2fieldkit` before the first upload. Treat a package-name
  change after distribution as a new product identity.
- [ ] Present “JA2 Field Kit” as an independent utility and avoid implying
  official JA2, JA2 Reborn, or JA2 Stracciatella affiliation. Re-review the
  engineering and legal boundaries in
  [Licensing-boundary review](licensing-boundary-review.md); do not infer a
  project license from that review.
- [ ] Tie listing compatibility and version claims only to save families,
  layouts, and builds supported by evidence and tests on the release commit.
  Distinguish supported, experimental/candidate, unsupported, and future work.
- [ ] Ensure screenshots, description, privacy text, and other listing assets
  show only UI and capabilities in the shipping artifact.

### Source, build, signing, and artifact evidence

- [ ] Record the exact reviewed source commit, reviewed base/integration
  commit, CI run, test results, release version name/code, build environment,
  and reviewer approval. Build from a clean, immutable commit.
- [ ] Use pinned/reviewable toolchains and dependencies. Document the build
  command and environment well enough to reproduce or independently review the
  unsigned build; investigate unexplained output differences.
- [ ] Record the identity and SHA-256 of each final release artifact, including
  whether it is an Android App Bundle/APK and whether the hash was taken before
  or after signing. Bind retained review evidence to that artifact and exact
  commit.
- [ ] Choose and document the
  [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
  strategy. Keep the Play-managed **app-signing key** concept distinct from the
  developer-held **upload key** used to authenticate uploads. Establish secure
  upload-key generation, least-access storage, offline backup, ownership,
  rotation/recovery, and incident procedures. Recheck current official Play
  signing and key-upgrade/recovery behavior immediately before the first
  release; do not rely on this summary as operational instructions.

### Runtime, privacy, and permissions

- [ ] Generate and inspect the actual final merged **release** manifest,
  including library contributions; do not rely only on the source
  `AndroidManifest.xml`. Justify every permission declaration and use,
  component, intent filter, exported surface, and URI grant. The current source
  baseline is zero `uses-permission` elements, while the current merged debug
  baseline includes the AndroidX-injected package-scoped signature permission,
  Startup provider, and DUMP-guarded ProfileInstaller receiver documented
  above, with no `INTERNET` or user-granted broad storage permission.
- [ ] Inspect the resolved release dependency graph and SDK behavior for
  network, tracking, identifier, analytics, telemetry, crash-reporting,
  advertising, background-work, storage, and account behavior. A dependency
  must not silently change this inventory.
- [ ] Exercise the final artifact and update this data-flow table from actual
  behavior. Derive the privacy policy and the separate
  [Data Safety form declarations](https://support.google.com/googleplay/android-developer/answer/10787469)
  from what ships, including SDK behavior and user-initiated exports, not from
  intentions or an older build. Review each as its own release obligation.
- [ ] Publish and activate the required Play-facing privacy-policy URL/store
  metadata, and verify that privacy-policy link or text is accessible inside the
  released app. Both surfaces must match the exact shipping behavior. The
  current app does not implement the required in-app privacy-policy UI or
  surface, and this documentation PR does not add it; a public release remains
  gated on that implementation and verification.
- [ ] If any future feature needs sensitive data or permissions, reassess data
  minimization and the current
  [permission declaration](https://support.google.com/googleplay/android-developer/answer/9214102)
  requirements. Add
  [prominent in-app disclosure and consent](https://support.google.com/googleplay/android-developer/answer/11150561)
  where the then-current policy or the feature's reasonable user expectations
  require it; do not treat that step as a substitute for the privacy policy,
  Data Safety form declarations, or any required permission declaration.
- [ ] Keep Issue #12 as a mandatory design and verification gate before any
  editing, write-back, or generated-save export implementation. Keep Issue #13
  as a mandatory protocol and threat-model gate before any LAN bridge,
  discovery, pairing, or transfer implementation.

### Play readiness and release operation

- [ ] Immediately before every public release, recheck the official
  [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/10788890)
  and [prepare/roll-out guidance](https://support.google.com/googleplay/android-developer/answer/9859348),
  plus linked current policy pages. Verify then-current account/identity
  verification, testing-track eligibility, target-SDK, review, privacy,
  permission, regional, and rollout requirements. These rules are time-sensitive;
  do not freeze today's testing counts, durations, or policy dates here.
- [ ] Use Installed Audience as the primary user-level current-install measure
  and Install base as its device-level companion. Keep acquisitions/total
  installs separate. Document that Play metrics omit sideloads and may lag or
  apply thresholds/privacy adjustments. Do not add app analytics solely for
  install counting.
- [ ] Plan staged rollout/monitoring, support ownership, and a stop mechanism.
  Preserve application/signing continuity and monotonically valid update
  versioning. Treat rollback as a new reviewed update when required by the
  distribution mechanism; never respond to a bad release by weakening parser
  admission, generated-save verification, original-save protection, or the
  Issue #12 safety gate.
- [ ] Re-run all repository tests, fixture-policy regression tests, exact-commit
  fixture admission, manifest/dependency/privacy audits, and artifact hashing
  for the final release commit. A green earlier PR is not release evidence for
  a different commit.

## Per-release verification record template

Copy and fill this table for each real release. Empty cells mean unverified;
this template is not a certification and this documentation PR does not
complete it.

| Verification field | Release record |
|---|---|
| Verification date | |
| Exact release commit | |
| Exact artifact SHA-256 (identify AAB/APK and signing stage) | |
| Version code / version name | |
| Target API requirement checked | |
| Play policy pages checked | |
| Actual-account testing requirement applicable? (`yes`/`no` + basis) | |
| Source manifest + merged release manifest permissions/components | |
| Network / analytics / ads observed | |
| Resolved dependency / SDK audit | |
| Active Play-facing privacy-policy URL / store metadata | |
| In-app privacy-policy link or text present and verified in released app | |
| Data Safety completed against exact build | |
| Signing / upload-key plan recorded | |
| Store claims matched to tested compatibility | |
| Reviewer-access instructions needed? (`yes`/`no`) | |
| Content rating / target audience completed | |
| Final licensing / trademark review complete | |

## Acceptance mapping for Issues #15 and #11

### Issue #15: release and privacy posture

| Acceptance criterion | Where this document addresses it |
|---|---|
| Release/privacy checklist exists before the first public Play build | **Future public Play release gate** and the **Per-release verification record template** define the pre-release work without claiming a release exists. |
| Data-flow inventory matches actual app behavior | **Audited current boundary** and **Data-flow inventory** record current local import, ephemeral processing, explicit OS-mediated export, and absent network/analytics/ads behavior; each real release must repeat the audit. |
| No SDK or permission is added without a documented feature need | **Runtime, privacy, and permissions** requires source/merged-manifest and resolved-SDK review; Issues #12 and #13 remain explicit gates for write/export and bridge capabilities. |
| Store claims distinguish tested support from experimental/future compatibility | **Product identity and claims** binds claims and screenshots to tested support on the exact release commit and requires supported, candidate/experimental, unsupported, and future states to remain distinct. |
| Current Play requirements are re-verified from official Google documentation immediately before release | **Actual build identity**, **Current Play policy snapshot**, **Play readiness and release operation**, and the per-release record require a time-sensitive official-source recheck. |
| Privacy-policy completion covers both required release surfaces | **Current Play policy snapshot** and **Runtime, privacy, and permissions** require an active Play-facing privacy-policy URL/store metadata and privacy-policy link or text accessible inside the released app; the per-release record verifies each separately and this document does not claim the currently absent in-app surface is complete. |

### Issue #11: install-base metrics without invasive analytics

| Acceptance criterion | Where this document addresses it |
|---|---|
| Identify the Play Console metrics for current installed audience/install base | **Install-base decision for Issue #11** names Installed Audience as the primary user-level current-installed metric and Install base as the active-device companion; acquisitions/total installs remain growth metrics and Play reporting omits sideloads. |
| Require no analytics SDK solely for install counting | The same section explicitly rejects Firebase or other third-party analytics merely to recreate Play aggregate metrics. |
| Require separate review if later product analytics are proposed | The same section requires a distinct privacy/value review before any later SDK, identifier, event, permission, or disclosure. |

The following lifecycle operations are intentionally deferred until an actual
release: Play Console registration and actual-account verification; publication
of the Play-facing privacy-policy URL/store metadata; implementation and
verification of privacy-policy link or text inside the released app; Data Safety
submission; tester and track execution; signing-key operational setup; store
listing and screenshots; and production of the actual release artifact. None is
claimed complete here. Issues #15 and #11
remain open until their lifecycle work and review are complete. Legal and
licensing review remains a separate decision track, including the explicit
non-conclusion recorded in the licensing-boundary review.

## Official Google references

These links are review inputs, not frozen policy text. Check them and their
linked requirements immediately before each public release:

- [App statistics: Installed Audience and Install base](https://support.google.com/googleplay/android-developer/answer/139628)
- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Android Developers: Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [Prepare your app for review: privacy policy, ads, and App content](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Data Safety form: Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prominent disclosure and consent: Best practices](https://support.google.com/googleplay/android-developer/answer/11150561)
- [Permission declarations](https://support.google.com/googleplay/android-developer/answer/9214102)
- [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/10788890)
- [Prepare and roll out a release](https://support.google.com/googleplay/android-developer/answer/9859348)
