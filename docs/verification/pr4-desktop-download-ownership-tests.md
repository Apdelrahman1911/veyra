# PR #4 desktop download reader/validator ownership tests

Status: **focused test lane passes; Task A is not complete and no native/platform verification
is claimed.** This is the test-only contribution for the direct desktop reader-ownership
regressions. It does not approve or merge the parent PR, change production code, or add release
evidence.

## Accepted launch and worktree

- Accepted launch: provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`.
  No independent runtime/backend/variant attestation was exposed by the harness; this is the only
  accepted-configuration record. No substitution was made.
- Worktree: `/root/.t3/worktrees/Veyra/t3-528e5ff7`.
- Local/remote branch: `t3/pr4-desktop-download-ownership-tests`.
- Required base: `t3/typed-room-download-recovery` at
  `67594669c6cd51e63ca772b281d4fc56144cafdd`.
- Integration tip: `9b1b0479a2748012988d378d1f631cf5a50fff79`; it is an ancestor of the
  required parent. The supplied local branch was fast-forwarded from that integration tip to
  the exact parent. The stale local `t3/typed-room-download-recovery` ref (`0d89f15…`) was
  verified as an ancestor of the remote parent, not a divergent ref.
- Tested code SHA: `1f2ab2a7036fd748dced3a918a0f39243f222c26`.
- Remote readback: `1f2ab2a7036fd748dced3a918a0f39243f222c26`.
- Draft PR: https://github.com/Apdelrahman1911/veyra/pull/10, base
  `t3/typed-room-download-recovery`, head `t3/pr4-desktop-download-ownership-tests`.
- Packet verification: `inputs.json` SHA-256
  `e06cc2078e8ffcd41fe1f5c4aa782c97d441a13e0265a35ee2cf58a968679aed`; `brief.md` SHA-256
  `5c0e866e0845c5beda0304011f62e694ac457fb4e07130598d1acf0e11e5b7fd`; lead source manifest
  SHA-256 `f44f376230583492df84d5c3c3fabcba9c070a49eb5b51b68528f0b9cbfbcc05`. The packet's
  eleven inspected source inputs were rehashed with zero drift.

## New files

| Path | Reason |
|---|---|
| `composeApp/src/desktopTest/kotlin/dev/veyra/desktop/audio/DesktopDownloadReaderOwnershipTest.kt` | Real `DesktopAudioEngine` actor ownership/retirement boundaries. |
| `composeApp/src/desktopTest/kotlin/dev/veyra/desktop/audio/DesktopDownloadValidationOwnershipTest.kt` | Real `DesktopAssetValidator` exact-reader/input release boundaries over an authored non-audio file. |
| `composeApp/src/desktopTest/kotlin/dev/veyra/desktop/audio/DesktopDownloadReaderEpochTest.kt` | Direct tests of the real `DesktopNativeReaderEpoch`. |
| `composeApp/src/desktopTest/kotlin/dev/veyra/desktop/audio/DownloadNativeOwnershipFixture.kt` | Authored fake deck/resource, real-projector acquisition graph, bounded test runner and file fixture. |

No existing production/test/helper/docs/config/workflow/schema file was modified. The fixture does
not reimplement `DesktopAudioEngine` or `DesktopAssetValidator`; those real classes run around the
fake `DesktopAudioDeck`/`PlaybackResource` seams.

## Behavior matrix

### Real `DesktopAudioEngine` actor

| Required boundary | Actual test | Real seam / assertion |
|---|---|---|
| Pristine engine establishes only epoch 0; arbitrary wrong epochs are `Superseded` | `pristineEngineEstablishesOnlyEpochZeroAndRejectsArbitraryWrongEpochs` | Real `awaitRetiredReaders`/actor. Asserts `Drained(0)`, `Superseded` for 7/negative, and the initial deck's two exact-epoch checks. |
| A failed invocation is not pristine | `registeredButFailedInvocationMakesAnEmptyLookingEngineNonPristine` | Mismatched acquired handoff registers ownership, closes its resource once, then `awaitRetiredReaders(0)` is `Superseded`, not a false ACK. |
| Exact stale mismatch does not mutate live generation | `exactStaleEpochMismatchDoesNotMutateTheLiveGeneration` | `awaitRetiredReaders(0/99)` is `Superseded`; subsequent `play(1)` succeeds twice and the live resource stays open. |
| Genuine catalogue + neutral recording + explicit controls mode | `acquiredCatalogueAndNeutralSelectionsUseTheRealProjectorAndOnlyTransport` | `PlaybackSelection.project` over an authored typed graph; catalogue keeps `catalogMedia`, neutral keeps `catalogMedia == null`; PODCAST unity gain and MUSIC master gain; only decoder transport reaches the deck. |
| Mismatched acquired selection is owned before rejection, closed exactly once, leaves live B untouched | `mismatchedAcquiredSelectionIsOwnedThenClosedOnceWithoutTouchingLiveB` | Real `prepare(AcquisitionPlaybackSource, PlaybackSelection)`; exact `native_playback_selection_mismatch`; rejected resource close count 1, live B resource/deck counters unchanged. |
| Rejected resource close failure pins the exact owner and vetoes the fence | `failedRejectedResourceClosePinsTheExactOwnerAndVetoesTheReaderFence` | Close failure count 1; `awaitRetiredReaders` is `Unacknowledged(playback_resource_cleanup)`; re-observation does not retry; live B still plays. |
| Replacement creates a new deck; native destruction precedes resource close | `replacementDestroysTheOldNativeHandleBeforeClosingItsResource` | Held real `close()` barrier. While held: old handle not destroyed, resource close 0, retained-reader pair `1 to 0`, queued drain not complete. After release: old `destroyed`, resource close 1 with `closeSawNativeDestroy == [true]`, drain `Drained(2)`. |
| Stop acceptance alone is not a reader-release proof | `stopCommandAcceptanceAloneDoesNotReleaseARetiredReader` | Held destruction after accepted `stop(2)`: old resource still open; lower drain queued; after release resource closes once and the lower-epoch drain is `Superseded`, not a stale success. |
| Failed retired native close while pause succeeds retains handle/resource and does not silence B | `failedRetiredNativeCloseKeepsHandleAndResourceWithoutSilencingUnrelatedB` | Old close count 1/never retried, `destroyed == false`, resource close 0; drain is `Unacknowledged(playback_resource_cleanup)`; B `play(2)` works and no `Error(2)` is invented. |
| Both pause and native destroy fail: truthful containment | `bothPauseAndDestroyFailureUsesTruthfulTransportContainment` | `cancelNext` throws `INVALID_STATE native_audio_silence_unacknowledged`; staged resource stays open; both decks are paused; later `play` is rejected and close remains a cleanup failure. |
| Successful destroy + resource close failure is sticky and never re-closes the handle | `successfulDestroyThenResourceFailureIsStickyAndNeverReclosesTheHandle` | Old handle destroyed once, failed resource close once; two drains both unacknowledged; close does not retry either stage. |
| Same active/failed resource is an alias; distinct owners still drain | `activeResourceAliasesNeverCloseTheLeaderAndDistinctOwnersStillDrain`, `concurrentSameResourceHandoffsHaveOneLeaderAndOneRejectedAlias`, plus the failed-alias case above | Live leader close 0 on alias rejection; concurrent handoffs produce one leader/one `native_playback_resource_already_owned`; distinct resources close once. |
| Staged cancel retirement uses the native-before-resource barrier | `cancelNextRetiresTheStagedDeckBeforeItsResource` | Held staged close: resource open, fence pending; after release staged `destroyed`, resource close 1 with destroy observation true, then `Drained(1)`. |
| Staged preload failure uses the same barrier | `failedPreloadRetiresItsStagedNativeDeckBeforeClosingTheResource` | `StageFailed(crossfade_preload_failed)` after staged close; held barrier proves resource is not released early; exactly one staged close/resource close. |
| Caller cancellation cannot become `Drained` | `callerCancellationCannotBecomeDrained` | Held deck reader-drain barrier; caller records the actual `CancellationException`; release lets the actor finish and `play(1)` remains usable. |
| Close admission racing a pending lower-deck fence cannot return stale success | `closeAdmissionRacingAPendingLowerDeckFenceIsNotAStaleSuccess` | Held `awaitRetiredReaders(1)`; concurrent close admission makes the post-wait result `Unacknowledged(native_audio_owner_closed)`, then close completes normally. |
| Post-shutdown pending resource close is joined even after an earlier close failure | `postShutdownPendingResourceCloseIsJoinedEvenAfterAnEarlierCloseFailure` | First close fails on a real resource-close failure. A post-shutdown handoff starts a gated resource close; a subsequent close and an alias handoff both wait for that observed pending close; unique resource close count stays 1. |

`retainedReadersForTest()` was inspected before use: it returns
`retiredDecks.size to ownedSources.count { retired && completion completed }`. Tests use it only as
supporting state; primary assertions are resource/deck counters and real command/fence results.

### Real `DesktopNativeReaderEpoch`

| Required boundary | Actual test | Assertion |
|---|---|---|
| Only pristine zero is established; wrong/negative epochs are `Superseded` | `initialResultEstablishesOnlyPristineZero` | Initial `result(0)` drains; 1/7/-1 do not adopt; repeated zero remains exact. |
| Accepted stop/replacement retains uncertainty until positive destruction | `acceptedStopOrReplacementRetainsRetiredUncertaintyUntilExactDestruction` | `stopped(2)`/replacement at 5 are `Unacknowledged`; `destroyed(true)` drains only the exact final epoch; older/newer expectations are `Superseded`. |
| Failed destruction stays `Unacknowledged` | `failedDestructionStaysUnacknowledgedAndNeverClearsItself` | Repeated observation remains unacknowledged and side-effect free. |
| Stale lower adoption cannot roll back; equal reuse is explicit | `staleLowerAdoptionCannotRollBackAndEqualReuseIsExplicit` | `adoptSource(19)` returns false at generation 20; equal `adoptSource(20)` is accepted and requires a new destruction ACK. |
| A live current reader is not a retired-reader deletion grant | `liveCurrentReaderIsNotARetiredDeletionGrant` | Live generation 30 drains because no retired reader exists; accepted `stopped(30)` makes it unacknowledged until destruction. This test explicitly does not authorize deleting the current target. |
| Destroyed epoch cannot adopt | `destroyedEpochCannotAdoptAnotherSource` | `adoptSource` throws after positive destruction; final epoch remains drained. |

### Real `DesktopAssetValidator`

| Required boundary | Actual test | Assertion |
|---|---|---|
| Successful authored decode closes the exact deck once before `Valid` | `successfulAuthoredDecodeClosesTheExactDeckOnceBeforeReturningValid` | Real file kind/size/hash checks; deck URI is the normalized local path, original input URI/size/hash/representation/generation stay pinned; `Ready`→`play`→`Ended`; close count 1/destroyed; no output/seek/gain (no audible app engine/network owner). |
| Authored negative decode closes before `Invalid` | `authoredNegativeDecodeClosesTheExactDeckBeforeReturningInvalid` | Exact authored failure, close count 1, no play. |
| Factory failure after publication closes exactly that deck once | `factoryFailureAfterPublicationClosesExactlyThatDeckOnce` | One published owner, one close, `Invalid(native_validator_unavailable)`. |
| Factory failure before publication creates no phantom owner/receipt | `factoryFailureBeforePublicationCreatesNoPhantomOwnerOrReleaseReceipt` | Owned callback count 0, close count 0, `Invalid(native_validator_unavailable)`. |
| Failed close after factory failure is the typed unacknowledged owner | `factoryFailureAfterPublicationWithFailedCloseThrowsTheTypedOwner` | `LocalMediaValidationReaderUnacknowledged`, exact input, `reader.isReleased() == false`, close count 1, no URI in message. |
| Negative validation with failed close is the typed owner, not `Invalid` | `negativeValidationWithFailedCloseThrowsTheTypedOwnerRatherThanInvalid` | Exact input/reader; unsuccessful original close remains observable. |
| Cancellation waits for held native close and remains cancellation | `cancellationWaitsForTheHeldNativeCloseAndRemainsCancellation` | No outcome before the barrier; `NonCancellable` cleanup context active; after release the caller observes `CancellationException`, not `Valid`/`Invalid`; close count 1. |
| Cancellation with failed close is the typed owner | `cancellationWithFailedCloseThrowsTheTypedOwner` | The typed exception escapes cancellation cleanup with the exact input/reader; no false release. |
| File kind/path/size/hash rejection never allocates a decoder | `fileKindSizeHashAndPathRejectionsNeverAllocateADecoder` | Exact `unsupported_offline_asset_kind`, `local_audio_missing`, `offline_byte_count_mismatch`, `offline_hash_mismatch`, `invalid_local_audio_path`, plus generation rejection; factory calls 0. |
| Repeated reader observation is side-effect free | `repeatedReaderObservationIsSideEffectFree` | Five observations after failed close leave close count 1 and the same input. |

The file fixture contains real authored bytes and real size/SHA-256, but it is deliberately
**not audio**. All `Ready`/`Ended`/`Error` results are authored fake answers; this is not codec,
libmpv, native audio, physical-device or platform evidence.

## Evidence actually executed

All heavy commands were run by the task-local runner under the required shared lock:

```text
flock -x /root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock \
  bash .work/verification/pr4-desktop-download-ownership-tests/tools/run_evidence.sh
```

The runner captures a fail-closed tracked-input manifest before every run, hashes the complete
Gradle log, captures the same manifest afterwards, compares source/config/gitlink/schema/pin
maps, and parses fresh XML. No committed Gradle setting was changed. The literal bounded JVM
argument was:

```text
-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8
```

| Run | UTC | Command scope | Exit | Actual result | Log SHA-256 |
|---|---|---|---:|---|---|
| `final-001-compile` | 18:18:38–18:21:45 | `--rerun-tasks :composeApp:compileKotlinDesktop :composeApp:compileTestKotlinDesktop`, offline | 0 | BUILD SUCCESSFUL; 20/20 tasks executed (production + desktop test compile) | `7021496a7a4eb30b87bb4f2eda4efc14ef8a20bdbf723046a52955cefdecc5a1` |
| `final-002-desktop-tests` | 18:21:46–18:22:03 | `:composeApp:desktopTest` filtered to the three new classes, offline | 0 | **34 tests / 3 suites, 0 failures, 0 errors, 0 skipped** | `d3960b658fbc5f5e77c986ed0afa98b054dbde111b3947521b1db18b41c595c2` |
| `final-003-shared-identity` | 18:22:03–18:22:16 | `:shared:jvmTest --tests dev.veyra.playback.NativePlaybackSourceTest`, offline | 1 | **Preserved prerequisite failure:** no cached `androidx.room:room-testing:2.8.5`; 0 tests/XML | `b76466af290007b70451c6e67fe4e3ae69356720ea728b50c20019fb98f47bbd` |
| `final-004-shared-identity-online` | 18:31:11–18:32:27 | Same identity control, online after the diagnosed cache miss | 0 | **13 tests / 1 suite, 0 failures, 0 errors, 0 skipped** | `fbac185865326e71d9fc33b156bb36ca54ecd9292e67925adb4888f83410129e` |

Desktop XML counts: epoch 6/6 pass, reader ownership 18/18 pass, validator 10/10 pass. Fresh XML,
complete logs, run metadata and summaries are under
`.work/verification/pr4-desktop-download-ownership-tests/runs/`. The focused XML summary SHA-256
is `ee28389b014455edb41c04923095e67bc93d58dd9feeae7704b1de46ab4f8ade`; the online identity
summary SHA-256 is `1542e35d94eb07b3532e818725c141d2614ba9bbca8e5af7fc13324541a90d84`.

Input/source manifests (all runs) had zero drift. The preflight manifest at tested code SHA
recorded 510 tracked regular files (506 baseline + four new test files), one gitlink
(`third_party/kmpYT`), the exact pin, schema and schema table count, and all eleven packet source
hashes. Its SHA-256 is
`ae889dac5aea43685b734b2a53e61ce0dbc9dd3317f2d5bac4dc1cd370f9b8e5`.

Frozen evidence result:
`.work/verification/pr4-desktop-download-ownership-tests/result.json`, SHA-256
`0682145c45118799997257bb0ecef62751f95f005013aa10638f306815c52ac7`.

## Invariants

- Changed paths vs required parent: exactly the four new test files above.
- `git diff --check` for `6759466..1f2ab2a`: exit 0.
- kmpYT pin: `c0a08090fe7dbd76e20216559b8184ad2cccde63` in both the gitlink and
  `gradle/kmpyt.commit`; actual submodule HEAD equal and clean.
- Schema `shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json`: SHA-256
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`, version 3, 92 tables,
  identity `a51a13de7a6c06c5b3ff5f9890c7ddd2`.
- No production, existing test, helper, docs, config, workflow or schema file changed by the
  tested code commit. The root project's three pre-existing protected dirty legal previews were
  not touched, stashed, regenerated or committed.

## Remaining limits / defects

- No production defect was exposed by these tests; no production edit was made.
- The parent full shared suite remains red (50 legacy-fixture shared failures and 21 AppContainer
  composition failures as recorded by the parent). This lane did not rerun those known-red suites
  and does not complete Task A.
- No libmpv, native audio/decode, real descriptor destruction, physical-device, emulator,
  simulator, Windows/iOS, performance, packaging, signing, workflow-dispatch or five-platform
  evidence was produced.
- The shared identity control required one online run solely because
  `androidx.room:room-testing:2.8.5` was absent from the offline cache; the offline failure and
  its log are preserved rather than relabeled.

## PR state at evidence freeze

- Draft PR #10, base `t3/typed-room-download-recovery`, head tested SHA `1f2ab2a…`.
- GitHub checks: none configured/reported at freeze; no reviews. Mergeable state reported
  `MERGEABLE` by GitHub, which is not an approval or acceptance.
- The subsequent commit for this report is docs-only; production and test source bytes are
  unchanged from `1f2ab2a`.
