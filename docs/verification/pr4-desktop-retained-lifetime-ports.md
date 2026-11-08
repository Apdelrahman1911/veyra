# PR #4 desktop retained-lifetime schema-3 port — verification record

Status: **test-only port, focused headless desktop green.** This record does not establish Task A
completion, native playback, live-source access, device execution, Android/iOS rebuild, signing,
packaging or distribution. Parent PR #4 remains draft and its shared/AppContainer suites remain
red (1,162 tests / 50 failures and 35 / 21 respectively); only the parent/lead integration can
close Task A.

## Accepted launch and attestation limit

| Item | Value |
|---|---|
| Provider | `opencode` |
| Driver | `opencode` |
| Model | `opencode-go/deepseek-v4.1-flash` |
| Variant | `max` |
| Runtime/backend/variant attestation | **Not exposed by this harness.** The accepted launch configuration above is the only record; no substitution was made. |

## Provenance, isolation and actual refs

| Item | Value |
|---|---|
| Repository | public `https://github.com/Apdelrahman1911/veyra` |
| Required parent/base | `t3/typed-room-download-recovery` at `67594669c6cd51e63ca772b281d4fc56144cafdd` |
| Parent remote readback | `git ls-remote` returned exactly `67594669c6cd51e63ca772b281d4fc56144cafdd`; the parent was not pushed or rewritten by this lane. |
| Actual worktree | `/root/.t3/worktrees/Veyra/t3-c876e727` |
| New branch | `t3/pr4-desktop-retained-lifetime-ports` |
| Remote branch | `origin/t3/pr4-desktop-retained-lifetime-ports` |
| Tested code revision | `d30a77fafaa8cae67b325b3e3d3c937fff106867` |
| Draft PR | https://github.com/Apdelrahman1911/veyra/pull/11 (base `t3/typed-room-download-recovery`) |
| Isolation | The supplied checkout started at integration head `9b1b047`; that integration head is an ancestor of the verified parent `67594669`, so the required parent is ahead. The clean supplied branch was not rewritten; the new local branch was created directly at the verified parent commit. No reset, rebase, force-push, worktree creation or sibling checkout was performed. |

Launch packet hashes were verified before implementation:

- `inputs.json` SHA-256 `f44f376230583492df84d5c3c3fabcba9c070a49eb5b51b68528f0b9cbfbcc05`
- `brief.md` SHA-256 `16f94ac5fa0b07bf79048224189d4cd05d21f7b8c49d229ab4eb27a4592ed78e`
- `acceptance-run001-evidence.json` SHA-256 `71c9520fbdcb2a760af8fc6addb91fed37b32347a1a362ca847e8840b9c7dca5`

The frozen `AppContainerIntegrationTest.reference.kt` used for contract comparison had SHA-256
`8981b469110896e14f4c4bdf8f3248acc7ea51e654902429a430a30aa492c25d`, matching the packet.

## Scope changed

Only one committed code/test file changed from the parent:

`composeApp/src/desktopTest/kotlin/dev/veyra/desktop/DesktopRetainedLifetimeTest.kt`

`git diff --stat 67594669..d30a77f` is exactly one file (298 insertions, 66 deletions), and
`git diff --name-only 67594669..d30a77f -- shared composeApp/src/commonMain
composeApp/src/desktopMain composeApp/src/androidMain composeApp/src/iosMain gradle
build.gradle.kts settings.gradle.kts .github` is empty. Every production file, schema, build
file, workflow, pin and other test/helper/doc remained read-only. This report is a docs-only
addition on top of the tested code SHA.

The kmpYT gitlink and `gradle/kmpyt.commit` both remain
`c0a08090fe7dbd76e20216559b8184ad2cccde63`; the actual submodule HEAD matches and its status is
clean. Schema-3 export
`shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json` remains SHA-256
`aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`.

## Fixture port: obsolete seams → canonical contracts

The original fixture used an obsolete setup path: it did not insert its catalogue item, supplied
only a `KmpMediaGateway` fake, returned a DOWNLOAD `PlaybackResource` from `resolve`, implemented
only the old `AssetValidator`, and waited for a legacy snapshot `AppState.downloads` asset. The
ported fixture now uses:

1. **A legitimate catalogue row.** `store.upsertCatalog(listOf(ITEM))` inserts the authored
   `MediaItem` before the public `MusicUiAction.Download(ITEM)` action, so fresh Download can
   freeze a real displayed row.
2. **The real typed admission path.** The public action runs `requireTapCurrent` → same-owner
   catalogue read → canonical capture → exactly one raw ordinal-zero `AcquisitionGateway.acquire`
   → durable request/receipt/binding → `DownloadCoordinator.enqueueAccepted`.
3. **The real physical executor.** The existing `DownloadCoordinator`/`DownloadPhysicalRuntime`
   claims the standalone job, attempts a transfer, validates the staged file, finalizes and
   publishes it through canonical Room commands. The test warms the real `DownloadExecutionGate`
   path and deliberately fails its original allowance close only at cleanup time.
4. **One same-central-gateway fake.** `GatewayProbe` implements `KmpMediaGateway`,
   `AcquisitionGateway` and `AcquisitionExecutionGateway`. Its legacy first-result/resolve/
   transfer methods and playback/collection methods fail loudly and are asserted at zero. The
   typed `prepareDownload` returns an opaque same-client `PreparedAcquisitionDownload`; the fake
   validates issuer, type, context and single consumption before the first transfer I/O.
   `prepareDownload` creates **no** `PlaybackResource` and no native player.
5. **An explicit identity-free local validator.** `platform.localValidator` is an authored
   `LocalMediaValidator` with an invocation counter and exact in-flow assertions for kind, byte
   count, MIME/codecs, SHA-256, representation ID, validator generation, the app-owned
   `transfer.part` path and file existence. A separate counter proves the legacy
   `AssetValidator` is not used by the typed physical path. This is a clearly labelled fake, not
   native audio proof.
6. **One same-owner Room read barrier.** After the deliberate cleanup failure, the test reads one
   `store.acquisitionView()` and verifies the completed physical job, immutable
   `MaterializationAsset`, exact `MaterializationReceipt`, exact `AssetUse`, the canonical
   request/raw-zero receipt/binding/recording join, the owned NON-AUDIO bytes, and the staged
   transfer metadata. A COMPLETE flag or historical success alone is not treated as a current-
   process Ready grant.

### The removed Resource stage

There is no DOWNLOAD `PlaybackResource` in the current API, and the test does not invent one or
claim it was closed. The old `Resource.closeCalls` assertions are replaced by earlier successful
typed-stage observations: exactly one acquisition, one complete-title query, one prepared
download, one transfer invocation at entry, one successful transfer completion, and one local
validation. Every cleanup retry is followed by a re-assertion that those counts remain one, so
retries never replay consumed source or validation work.

## Method-by-method mapping

All five original method names, the 45-second body timeout, the 10-second job-join timeout,
NonCancellable teardown, primary/suppressed error propagation and the sticky-failure model are
preserved.

### 1. `retainedDownloadAllowancePinsHostGraphAndLockUntilExplicitRetryAcknowledgesConsumption`

| Original safety claim | Current assertion |
|---|---|
| Real app is not released before close | `assertFalse(app.dependencyReleaseSafeAfterClose)`, `assertFalse(app.retainedForCleanup, "An empty pin registry is not a positive release receipt")` before the download |
| Real download completes before the deliberate failure | Public `Download(ITEM)`; wait for `CLEANUP_FAILURE`; `assertCanonicalPublication()` verifies one request/receipt/binding/job, COMPLETE, no failure/cleanup/lease/attempt, typed producer, exact context lineage, immutable asset/receipt/use, owned bytes and metadata |
| Original allowance close failed exactly once | `assertEquals(1, platform.allowanceCloses)`, `assertFalse(platform.allowanceConsumed)` |
| Exactly-once/no-replay work | Replaces `Resource.closeCalls` with `assertAuthoredDownloadStagesOnce()`: one acquisition/query/preparation/transfer entry/transfer completion/local validation/allowance acquisition, zero legacy paths |
| One startup job and one running callback are admitted | Real `DesktopStartupJobs.admit(MEDIA_SESSION, ...)` and `host.submitMediaCallback`, both entered before close |
| First close retires and joins both exactly once, then fails sanitized | `host.close()` throws `VeyraException(CLEANUP_FAILURE)`, `cause == null`, no suppressed exceptions; startup/callback finalizers each one; late startup admission rejected |
| First close only retries the retained allowance | `assertEquals(2, allowanceCloses)`; app/host pins, active Jobs, engine closed once, Room Ready + live flush, runtime/YouTube/HTTP/slots/verification unclosed, late callback rejected, second process context excluded |
| Positive allowance consumption then explicit close releases once | Permit close; second `host.close()`; same sticky `VeyraException` instance; `assertReleased()`: app/host pins released, receipt true, both Jobs completed, engine/runtime/YouTube/HTTP closed once, Room Closed, all slots/verification once, NonCancellable cleanups |
| No transfer/validation replay and consumption observed | `assertEquals(3, allowanceCloses)`, `assertTrue(allowanceConsumed)`, `assertAuthoredDownloadStagesOnce()` again |
| New process lock is not released by the old host | Acquire a NEW `DesktopProcessContext`, call old `host.close()` (still same exception), prove a third context is excluded, then close the new context; counts remain three and finalizers remain one |

### 2. `concurrentAndCancelledCallersShareOneRetainedAttemptWithoutDuplicatingCleanup`

| Original safety claim | Current assertion |
|---|---|
| Initial allowance failure and first explicit close failure | `failInitialAllowanceCleanup()`, then `host.close()` throws `CLEANUP_FAILURE` |
| Third cleanup attempt is explicitly held | `beforeAllowanceClose` barrier on attempt `3` |
| Leader, concurrent caller and already-cancelled waiter share one attempt | All three launched `UNDISPATCHED`; after cancellation `leader`, `other`, `cancelled` and the cancelled outcome are all not completed |
| Only one allowance attempt exists while held | `assertEquals(3, allowanceCloses)`; `assertRetained()` keeps NonCancellable cleanup, Room/host pins and lock, engine closed once, dependencies unclosed |
| Release without consumption still fails for all with the same original instance | Complete barrier with permission still false; `assertSame(first, leader.await())`, `assertSame(first, other.await())`, `assertSame(first, cancelledOutcome.await())`; count remains `3`; `assertRetained()` once more |
| Only a later fourth explicit attempt consumes | Permit; fourth `host.close()` shares the same first failure; `assertReleased()`; `assertEquals(4, allowanceCloses)`; stages remain exactly once |

### 3. `cancellationCannotReleaseTheHostWhileFirstApplicationCloseStillAwaitsItsPositiveReceipt`

| Original safety claim | Current assertion |
|---|---|
| Second allowance attempt is held inside the first application close | `beforeAllowanceClose` barrier on attempt `2` |
| Cancelled calling coroutine cannot complete early or publish a receipt | `closing.cancel(...)`; both `closing` and `outcome` remain incomplete |
| Host/app remain pinned with active Jobs and original lock | `assertRetained(engineCloses = 0)`: app/host pins true, receipt false, host/app Jobs active, Room Ready + flush, dependencies/slots/verification unclosed, lock excludes a second context, NonCancellable cleanup active |
| Engine has NOT closed while `downloads.close` waits | `assertEquals(0, platform.engine.closeCalls)` |
| Consumption then release produces the positive receipt | Permit close, release barrier, join caller; `assertReleased()` closes engine/runtime/YouTube/HTTP/store/slots exactly once; allowance consumed |
| Sticky failure remains and repeated close does not retry | Exactly two allowance closes; same `VeyraException` instance on repeated `host.close()`; `assertEquals(2, allowanceCloses)` again; stages remain exactly once |

### 4. `neverInitializedApplicationMustCloseAndAcknowledgeBeforeTheHostReleasesItsLock`

| Original safety claim | Current assertion |
|---|---|
| Loading/Uninitialized and no receipt before close | `AppInitialization.Loading`, `StoreStatus.Uninitialized`, no cleanup pin, no release receipt |
| No acquisition/gate/transfer work for a never-initialized app | `host.close()`; `assertReleased()`; zero allowance acquisitions, typed acquisitions, queries, preparations, transfer invocations, local validations |
| Actual positive handoff and exactly-once dependency release | `assertReleased()` verifies engine/runtime/YouTube/HTTP/store/slots/verification exactly once and Room Closed |
| Lock reacquisition and repeated-close control | Acquire/close a new process context; call `host.close()` again; `assertReleased()` still holds |

### 5. `partialStartupWithoutAnApplicationStillClosesPublishedServiceSlotsExactlyOnce`

| Original safety claim | Current assertion |
|---|---|
| Real partial `createOwned` path with no published app | Real `DesktopPlatformServices.createOwned`; only ENGINE/STORE inert sentinels; `host.container == null` |
| Initial lock excludes another context | `AlreadyRunningException` before close |
| Only published slots close in original order once, verification once | `host.close()`; `closedSlots == [ENGINE, STORE]`; verification one; host completes; lock reacquired |
| Repeated close consumes nothing again | New context acquired/closed; repeated `host.close()`; slot count remains two and verification remains one |

## Evidence

### Final formal runs at tested code SHA `d30a77f`

All commands used `scripts/gradle.sh --offline`, the literal single argument

`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`

and the ignored initializer
`.work/verification/pr4-desktop-retained-lifetime-20261107/bounded-tests.init.gradle`
(one test fork, 384 MiB heap, 256 MiB metaspace, SerialGC, 2 CPUs, fresh XML output).
Every heavy command held the shared lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock` via
`/usr/bin/flock -x` through its real completion. No committed build setting changed.

| Run | Command | Exit | Actual counts |
|---|---|---:|---|
| `formal-001-compile` | `:composeApp:compileKotlinDesktop :composeApp:compileTestKotlinDesktop` | 0 | `compileKotlinDesktop` UP-TO-DATE; `compileTestKotlinDesktop` executed; 0 test suites |
| `formal-002-retained-class` | `:composeApp:desktopTest --tests dev.veyra.desktop.DesktopRetainedLifetimeTest` | 0 | 1 suite / 5 tests / 0 failures / 0 errors / 0 skipped |
| `formal-003-combined-headless` | repeated `--tests` for `DesktopLifetimeTest`, `DesktopPlatformServicesOwnershipTest`, `DesktopStartupJobsTest`, `DesktopRetainedLifetimeTest` | 0 | 4 suites / 35 tests / 0 failures / 0 errors / 0 skipped |
| `formal-004-compile-rerun` | `--rerun-tasks` for both required compile tasks | 0 | 20 tasks executed, including both `compileKotlinDesktop` and `compileTestKotlinDesktop` |

Hashes:

| Run | Log SHA-256 | XML manifest SHA-256 |
|---|---|---|
| `formal-001-compile` | `27cc7d4d1c48ab22ced205e3d7bd746f79c5a84b45695da8ecdbefcb66ba8fef` | `7d91737973a3514648e7b964f27d44fb142aa7324230215408f5b50a4c0e88e6` |
| `formal-002-retained-class` | `fe10276238e524bf3194a5d193c3f0f966cf21ce7ef4fb20949760c6e5f41dbb` | `08b2876200c96753cff47bb2e9f4a16aec564d8995e7e85c82c12410e5237eb6` |
| `formal-003-combined-headless` | `af2d065e004340db8024db7325db28a33cd201cbc2e4f6c5d7392be13e85ed42` | `f3bdd9160ec2555456a6563ef6258d4d021f85c9ec9e2b0cdfda11cb367f7238` |
| `formal-004-compile-rerun` | `437a761fee072626787091586381e72f2fa37cfef730f037d5a28db31fafd5e6` | not a test run (no XML) |

Each formal run recorded its argv, cwd, HEAD, dirty state, UTC start/end, exact exit, complete
log, fresh XML file hashes, suite/method counts and before/after tracked-input hashes under
`.work/verification/pr4-desktop-retained-lifetime-20261107/`. The recorder fails closed on a
non-zero exit, stale or unexpected XML, unexpected suite/method counts, any failure/error/skip,
missing gitlink/pin agreement, or tracked-input drift. `formal-003` contains exactly the four
expected suites and all five required retained methods.

### Preserved red and pre-final runs

No red run was overwritten or relabelled. Relevant preserved attempts:

| Run | Exit | Actual result | Cause / correction |
|---|---:|---|---|
| `run-001-compile` | 1 | test compilation failed with 2 unresolved imports (`DownloadStatus`, `SourceAcquisitionResult`) | Imports corrected; production compile had passed |
| `run-002-compile` | 0 | both desktop compiles green after import correction | No test execution claimed |
| `run-003-retained-class` | 1 | 5 tests, 3 failures: job observed as `VERIFYING`, `CLEANUP_FAILURE` masked the earlier stage | Fixture bug: `NON_AUDIO_SHA256` was byte-hex, not a SHA-256 digest, so the authored local validator assertion aborted before finalization. No production defect. |
| `run-004-diag` | 1 | 1 test, 1 failure | Added stage diagnostics exposed the validator hash mismatch |
| `run-005-retained-first` | 1 | 1 test, 1 failure: `assertNull(job.cleanupPending)` on a Boolean | Corrected to `assertFalse(job.cleanupPending)` |
| `run-006-retained-first` | 0 | 1/1 retained method pass | First method green |
| `run-007-retained-class` | 0 | 5/5 pass | Pre-final control before the final fixture-context/NonCancellable strengthening |
| `run-008-combined-headless` | 0 | 4 suites / 35 tests pass | Pre-final control; superseded by `formal-003` at the committed tested SHA |

Preserved red XML/log hashes:

| Run | Log SHA-256 | Retained XML SHA-256 |
|---|---|---|
| `run-001-compile` | `16520ef0f804302bd65d71a1f0bd009d829fffa2c2c021f35933638ae067e695` | no XML (compile stopped before tests) |
| `run-003-retained-class` | `5a278b68b41d6656da237ffac4af60b23ef6ea11319e23ca9cfc0d10389397c2` | `5a3a1018aef851fe3fae9b3b9af3a377fbb3af350243498907c804736833dbc6` |
| `run-004-diag` | `aa56bd9acd4e106feb4c776ea941ce99ba6d2366554ab4ac7808ce18d02dd55d` | `bf41ad7b4d2456a83288eaec116b3e0e56bc2b1973f887bf85e94f8134dfafc4` |
| `run-005-retained-first` | `c73d231f8940c47a44339dbf8236ff3de5cee124114c3e651e2f2f2b87e94546` | `80edebd913ccf25f295667eb831c2aec67fc32fbc6f7ec98fc6a2141902a044e` |
| `run-006-retained-first` | `4678bc886ac7affae47c023cd2a4ef9d26e6f0fa776b998f34d1e275fbba3afb` | `24d4b6e4f2b901283a6519935305cd9996a6c55953dc36bbb6809e83b072ba77` |
| `run-007-retained-class` | `aadcfffc4ee1060b02f0617fbcea9098bdb645e5dccb1ec3393042991c8fcbba` | `9f10e6a8baec1d06df6a295225540b9777f06cb3e564e682a830e0ed970505c4` |
| `run-008-combined-headless` | `135d162d6a91866c7de0498dd0dcfca2c7de66aba979e5f76722bb65c13e3271` | 4 XML files; combined control, superseded by `formal-003` |

### Input stability and baseline rehash

At `d30a77f`, 507 tracked inputs were hashed before and after each formal run and again at the
end: 0 drift; dirty state empty throughout. The kmpYT gitlink `c0a0809` and actual submodule HEAD
`c0a0809` agree with `gradle/kmpyt.commit`; submodule status is clean. Schema-3 export hash is
unchanged.

Rehashing all 507 records in the lead packet's `inputs.json` against this checkout found 0
missing and exactly **one** changed path: the owned
`composeApp/src/desktopTest/kotlin/dev/veyra/desktop/DesktopRetainedLifetimeTest.kt` (the port
itself). All other baseline sources, configs, docs and the gitlink match.

All twelve files listed by `acceptance-run001-evidence.json` (five XML files, log, before/after
inputs, start/end/exit/meta) still hash-match their recorded SHA-256 values. The original red
evidence from PR #5's `run-accept-001` is therefore preserved and was not reused as this lane's
green evidence.

## Limits and non-claims

- This is a headless desktop host-lifetime test lane. It does not run native audio, real kmpYT
  source requests/authentication, emulator or simulator, physical device, Android or iOS
  compilation, packaging, signing or distribution.
- Source selection, transfer, local validation and the OS execution allowance are authored
  fakes over authored NON-AUDIO bytes. Room/SQLite, files, application lifetime, host lifetime
  and OS process locks are real.
- The combined 35-test result is a fresh four-class headless control, not a full desktop suite or
  a Task A acceptance run.
- Parent PR #4 and its shared/AppContainer red suites remain the owners' and lead's integration
  responsibility. No shared, AppContainer, Android or pinned-upstream suite was rerun from a
  stale sibling base.
- **No production defect was exposed by this port and no production repair is requested.** The
  only red stages were fixture bugs listed above; production files were never edited to make a
  test pass.

## PR state

Draft PR https://github.com/Apdelrahman1911/veyra/pull/11 targets
`t3/typed-room-download-recovery`, not `main` or the integration branch. Head at tested code SHA
`d30a77fafaa8cae67b325b3e3d3c937fff106867`; this report is added in a docs-only commit on top.
Code/test equality between the tested SHA and the docs HEAD is shown by an empty
`git diff --name-only d30a77f..HEAD -- composeApp/src/desktopTest/kotlin/dev/veyra/desktop/DesktopRetainedLifetimeTest.kt`
and an empty production diff. No merge, watch, publication, workflow dispatch or parent-branch
change is performed by this lane.
