# Task A UI/recovery integration

Status: **source integrated and tested on the exact committed revision; Task A acceptance,
Task B, native/live/device/package/signing verification remain held.** This note records the
UI/AppContainer half of Task A over the accepted shared recovery source. It is not a release
claim.

## Revision and composition

- Worktree: `/root/.t3/worktrees/Veyra/t3-b705873a`
- Integration topic: `t3/task-a-ui-recovery-integration`
- Starting core head: `7fbe62d` (PR22 docs head; tested code `bd9edf7`)
- Exact recovery merge: PR26 `cd715ddc2daf272d602fb3594329a6b503d85c4f`, ordinary merge,
  no conflicts. Frozen PR26/PR22 and earlier published refs were not moved.
- Tested code/test head: `ee1aa17e682f67a4032aaabf3dcc9f6a43f305a6`
- kmpYT pin: `c0a08090fe7dbd76e20216559b8184ad2cccde63`
- Room schema 3 SHA-256:
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367` (92 entities)
- No schema, pin, dependency, workflow or navigation/detail redesign was introduced.
- The independently reviewed PR26 followups were applied: the cleanup test no longer masks a
  drain throw, and the historical red inventory/proof wording is corrected. No frozen PR26 ref
  was changed.

## Implemented behavior

### Exact retained Validate target

`DownloadSurface` now derives durable validation identity from the paired canonical presentation:

- `RetainedValidationTarget(jobId, bindingId)` for a standalone owner;
- `RetainedValidationTarget(jobId, bindingId, batchId, memberPosition, expectedIntentRevision)`
  for an exact batch member.

The target is exposed only for a projected `FAILED` job with owned bytes, no cleanup and no
current ready grant. It comes from the same claim/batch/member graph as the row; no newest/first
member or fabricated binding is selected. A changed batch revision or a claim/member mismatch
omits the target. The AppContainer action re-reads the current presentation and requires an exact
match of every field before calling the shared API, so a stale or forged row action fails before
authority/native validation mutation.

`AppContainer` now calls:

- `validateRetainedAssetUse(downloadId, requiredBindingId)` for an exact standalone target;
- `validateRetainedMemberAssetUse(batchId, memberPosition, expectedIntentRevision,
  requiredBindingId)` for an exact batch-member target.

The returned `AssetUse` is not consumed as a Play/native grant.

### Repeated origins and explicit member choice

A physical row shared by several legitimate retained batch members exposes every distinct exact
target as `retainedValidationChoices`. The UI presents a localized one-button path when the target
is unique and an explicit localized member choice when several exist. The action carries the exact
batch/member/revision; the UI never silently selects the first/newest member. The connected test
validates one repeated member while the sibling claim remains active and asserts the requested
binding's use is not duplicated and no other binding use is inferred.

### Neutral typed source rows

A `TYPED_SOURCE` retained failure now has a real exact local Validate path using its persisted
typed binding. The shared validation API supports that binding without catalogue media, so no
`MediaItem`, catalogue membership, playback binding or Play action is fabricated. `bindingId`
remains null for neutral rows; only the validation target carries the exact binding. The returned
use does not become catalogue readiness.

### True foreground recovery

A visible-app `ResumeDownload` action for a row whose current status is `NEEDS_FOREGROUND` now calls
the real foreground announcement `downloads.resumePending()`. This is the same host lifecycle used
by visibility activation; it is not synthesized from a background worker. Ordinary `PAUSED` and
`WAITING_NETWORK` actions keep the existing serialized `resume(id)` path. The connected test
interrupts a real completed-file validation, observes the completed-check foreground marker and
shows that the UI action returns it to `COMPLETE` without a source query or transfer.

### Preserved contracts

- Completed removal remains a separate confirmed action. No normal UI success is claimed before
  required cleanup; shared-sibling retention and truthful failure display are untouched.
- Neutral source rows remain non-catalogue/non-Play members with correct storage accounting and
  process-local checked readiness.
- Canonical/saved playback precedence, exact podcast Resume, activation/coverage fences and local
  only policy were not changed.

## Tests added or strengthened

- `composeApp/src/commonTest/.../DownloadSurfaceTest`: exact standalone target, exact batch
  member/revision, explicit repeated choices, stale revision omission, neutral exact target.
- `composeApp/src/commonTest/.../RetainedValidationActionTest`: exact binding, batch member and
  repeated-choice action projection, neutral exact Validate, no-asset Retry.
- `composeApp/src/desktopTest/.../RetainedDownloadSurfacesTest`: one-button catalogue path,
  exact batch fields, localized member dropdown choice, neutral Arabic Validate without Play,
  separate confirmed removal.
- `composeApp/src/desktopTest/.../TaskAUiRecoveryIntegrationTest`: real AppContainer/Room/files/
  coordinator tests for standalone success and negative, neutral source exact validation, exact
  batch member stale-revision rejection then current success, repeated-origin explicit choice with
  the sibling retained, and true foreground `NEEDS_FOREGROUND` handoff.
- `TypedDownloadPresentationTestSupport` gained purpose-specific canonical batch admission and
  retained-negative/foreground helpers over the same Room owner and authored non-audio gateway.
- Existing typed download surfaces, AppContainer download/activation/removal regression methods,
  exact-resume/podcast projection tests and AppStrings were retained in the selected union.

## Final committed-source evidence

Evidence root (ignored, task-owned):
`/root/.t3/worktrees/Veyra/t3-b705873a/.work/verification/task-a-ui-recovery-integration/attempts/`

All final runs started at committed head `ee1aa17`, used the shared project lock inside
`vps-build`, `--offline`, one bounded test fork, the required single JVM argument, and recorded
before/after tracked-input manifests. All three final attempts have identical input-manifest
SHA-256 `f5a1d5d5a5f4af1432b9eeb625cc981c20ff8632098d013f5d10734fcc300e55`, exit 0,
positive owned drains (`scan_matches=0`), lock-holder snapshots and gate admission prefixes.

| Attempt | Result | Log SHA-256 | Admission prefix SHA-256 |
| --- | --- | --- | --- |
| `final-shared-002` | **1,205 tests / 106 suites; 0 failures / 0 errors / 0 skipped**, 2026-11-09T03:07:33Z–03:10:28Z | `e08bbb65d1665b7ff26d7d1392abd22ae59c6b83ba18fb48803b5dcfa55488fb` | `c32bd5430391ccb2f5208374062d9a554bebac1dd2c72014fe8918d3c0f0b80b` (85 bytes) |
| `final-ui-002` | **138 tests / 19 suites; 0 failures / 0 errors / 0 skipped**, 2026-11-09T03:10:41Z–03:11:38Z | `d261d79ae5506e503d8545f497f7c0d0ab6330b10516af12017735ee9c31172f` | `87f8e4cea9a05ecc91abc2a4a92b9267a88521b0f46b095d5cfe90a6a2480102` (81 bytes) |
| `final-android-compile-002` | **52 tasks executed**, `:composeApp:compileDebugKotlinAndroid` and `:shared:compileDebugKotlinAndroid` successful with `--rerun-tasks`, 2026-11-09T03:11:52Z–03:14:31Z | `3e66ead250c48a076c0123f3753849488f9fe427dffdf3300217de65aa63156a` | `e9692a7ba89bf3fd7ed467e50642653ea2662f23d50c919b953b199b1ff90733` (94 bytes) |

The selected UI union includes the new Task A tests, typed download surfaces, AppContainer
download/activation/removal methods, exact-resume/podcast projection classes, retained/removal
detail/row methods and AppStrings. The final shared suite includes
`DownloadRetainingCleanupIdentityRoomTest` and the accepted PR26 source.

### Preserved red and its diagnosis

`final-shared-001` at the same head and inputs recorded **1,204/1,205 tests passed** with one
15-second timeout in the pre-existing
`DownloadRecoveryRoomTest.sqliteRollbackAfterAtomicRenameIsRecoveredFromDurableIntentOnReopenOffline`
(`run.log` SHA-256 `5571f87c99e663c9a558b1d1f6d0df0f0325d46c281f366b2272b1ed792721a0`). The
evidence is retained. A targeted in-gate rerun of that exact method passed **1/1** at the same
head (`final-shared-red-method-001`, log SHA-256
`08b50e61975522129ae660d2ee30a5547252c4a66c4f5f70ba290583194ff297`). This is recorded as a
non-reproducible full-sequence timing timeout, not as a green first attempt.

## Limits and holds

- Source/native/transfer answers in connected tests are authored fakes over **NON-AUDIO** bytes.
  Room, files, OS locks and coordinator commands are real. No live source, authenticated provider,
  physical download or playable audio is claimed.
- iOS/Xcode was not available; no iOS compile/simulator/device result is claimed.
- No physical device, native decoder/audible output, background OS scheduling, packaging, signing,
  workflow dispatch or distribution result is claimed.
- Explicit local validation under a **paused** batch intent currently returns the shared
  `download_asset_check_not_admitted` failure. The active repeated-origin path is tested; the
  paused-intent limitation is not claimed as a recovery success.
- The known `MusicSurfacesTest.performScrollTo` hang is unresolved and was not included in the
  selected union; it is not called green.
- Task A acceptance still waits on lead review/adjudication. Task B, the shared recovery successor
  integration outside the merged PR26 source, and all release gates remain held.
