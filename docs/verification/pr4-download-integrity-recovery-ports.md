# PR #4 download integrity, ownership and cold-recovery regression ports

Status: **scoped slice verified; draft stacked PR open. Not merged, not combined-suite green.**
This report covers only the four exclusive shared JVM test classes and one new owned helper. The
full 1,162-test shared suite, AppContainer suite, native/iOS, device, live source, real audio,
packaging and release gates remain the lead's combined responsibility and are **not** claimed here.

## Provenance and accepted launch

| Item | Value |
|---|---|
| Repository | `https://github.com/Apdelrahman1911/veyra` (public) |
| Parent PR | #4, branch `t3/typed-room-download-recovery` |
| Exact parent head | `67594669c6cd51e63ca772b281d4fc56144cafdd` |
| Worktree branch | `t3/download-integrity-recovery-ports` |
| Pushed scoped branch | `t3/pr4-download-integrity-recovery-ports` |
| Tested code commit | `7d553607647c3a2ef5985d8f813697fb27129fdf` (`HEAD^` = parent head above) |
| Stacked PR | #13, draft, base `t3/typed-room-download-recovery` |
| kmpYT pin | gitlink mode `160000` and `gradle/kmpyt.commit` both `c0a08090fe7dbd76e20216559b8184ad2cccde63`; submodule HEAD equal, clean tracked/untracked |
| Schema 3 | `shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json` SHA-256 `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`, unchanged |
| Starting inputs | immutable manifest `pr4-ports-wave-inputs.json` SHA-256 `1d41eec8…`; **506/506** tracked hashes matched at start and after the slice, with drift limited to the four owned class files |
| Launch | `opencode-go/deepseek-v4.1-flash`, variant `max`, provider `opencode`; no independent runtime/backend/variant attestation is exposed by the harness |

The initial checkout was the integration parent `9b1b047`; it was fast-forwarded to the exact
parent PR head `6759466` (verified ancestor and exact remote ref) before any edit. The local parent
ref was stale at `0d89f15`; it was fetched explicitly and verified.

## Changed paths

| Path | Change |
|---|---|
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadIntegrityRoomTest.kt` | all nine guarded-fixture methods ported; legacy `seedComplete/seedFinalization` helpers removed |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadOwnershipRoomTest.kt` | five guarded-fixture methods ported; six already-typed methods preserved byte-for-byte except the shared file line shift |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadRecoveryRoomTest.kt` | four guarded-fixture methods ported; four already-typed methods preserved |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadMemberRetryLifetimeRoomTest.kt` | cold-reopen audit diagnostics/assertions strengthened; three methods preserved in intent |
| `shared/src/jvmTest/kotlin/dev/veyra/downloads/DownloadIntegrityPortTestSupport.kt` | **new owned helper only**: real-SQLite external corruption/guard probe + revision bump |
| `docs/verification/pr4-download-integrity-recovery-ports.md` | this docs-only report |

No production source, schema/migration, dependency, build, workflow, pin, legal or generated
resource change. `third_party/kmpYT` was treated as a gitlink, never as a regular file; its actual
HEAD and cleanliness are recorded in the evidence result.

## Fixture architecture used

- `DownloadExecutorSchema3Fixture`: real Room database, dual-interface persistence, real
  `DownloadFiles`/hashes/OS leases, real canonical claim/select/complete/recovery/foreign-claim
  commands, separate observer probes and untrusted-failure teardown.
- `DownloadAcquisitionRoomFixture` + `prepareFinalizationSchema3`: the same-owner typed
  acquisition/recovery fixture with authored interrupted finalization history built only through
  canonical claim/select/finalize/finish commands under real path/slot leases.
- `DownloadIntegrityPortTestSupport` is a deliberately narrow **corruption probe**: one real
  IMMEDIATE SQLite transaction, an explicit revision bump, and no production usage. It exists only
  because canonical typed history is immutable; it is never used to claim a legal in-place update.
- Transfers, first-result answers and decoder results are authored fakes over non-audio bytes.
  Room/SQLite, files, hashes, deletes, OS path/slot leases and durable ownership are real.

## Original → new seam → retained assertions

### DownloadIntegrityRoomTest

| Original method | New setup/command seam | Retained assertions and documented mapping |
|---|---|---|
| `missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording` | Real completion (`select` → `enqueueAccepted` → USER_SESSION execution → `complete`), cold owner, delete file, USER_SESSION recheck, canonical `remove` with a real `beforePhysicalDelete` barrier, then re-admit the **same accepted request/binding** + `resume` | Pre-start projection is VERIFYING, never Ready; failure is CORRUPT_MEDIA before the decoder; the old asset row/bytes are retained until actual cleanup; cleanupPending/asset/recording binding are observable while the release callback is held; old generation deleted before reacquisition; one repair transfer, exact producer context, zero new title queries; final removal deletes bytes. The legacy `resume` shortcut is replaced by the canonical remove/re-admit route because Resume of asset-backed FAILED bytes is not a legal typed re-download. |
| `truncatedCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording` | Same, with `Files.write` of the authored bytes truncated to half | Same pre-native CORRUPT_MEDIA, retained asset/bytes, cleanup/recording and one no-query repair. |
| `nonregularCompletedPathIsNeverReadyAndExplicitRetryRetainsItsRecording` | Same, with the completed path replaced by a real directory | Same pre-native rejection; the empty directory is really deleted by canonical cleanup; the replacement generation is a regular file at a new path. |
| `invalidCompletedMaterializationLinksFailBeforeNativeValidationWithoutLosingTheAsset` | Real completed asset. Four typed-representable identity replacements via the real-SQLite probe: representation, validator generation, ownership generation, missing materializer attempt. Six earlier-guard shapes: unselected video (raw receipt id null), non-video (raw entry kind PLAYLIST), unbound typed binding (recording null), different binding (a second real selection's binding on asset+receipt), missing and blank representation | Typed-representable cases: FAILED CORRUPT_MEDIA, **zero** fresh native validations, the exact replaced asset row/immutable receipt/use and bytes retained, no prepare/transfer/query. Legacy-only shapes: canonical full-graph validation fails closed (IllegalArgumentException) before any typed materialization/native/cleanup stage; native/transfer counts unchanged and bytes retained. The later typed path is explicitly **not** claimed exercised for those shapes. The old asset use/receipt survive. |
| `invalidFinalizationLinksFailBeforeNativeValidationWithoutLosingPublishedOrStagedBytes` | Real authored interrupted finalization via `recoveryIntent` (published and unpublished). Four typed-representable link replacements via the probe: different/missing/blank representation and changed validator generation. Two earlier guards: missing typed asset id and a replaced binding | Each recoverable defect: FAILED CORRUPT_MEDIA before the decoder, no asset, the exact corrupted journal retained, no re-query/prepare/transfer, bytes retained at the published destination or staging path per the original flag, and no final rename for the unpublished case. The two earlier guards fail closed at canonical graph validation; their later native path is not claimed. Original producer/attempt/staging/final identities are asserted unchanged where applicable. |
| `changedBindingValueUnderTheSameIdInvalidatesTheExactCompletedCheckReceipt` | Complete asset with a process check C0; gate the next VALIDATE before native; replace the **materialization representation value under the same asset id** (asset+receipt) with a real-SQLite probe; publish the new Room revision | The presentation is VERIFYING, not COMPLETE; the process-local history still names the exact old asset value, but `acquisitionPresentation.successfulChecks` excludes it; the durable job remains COMPLETE while untouched; after release the recheck fails CORRUPT_MEDIA before an **additional** decoder call; the replaced asset row/bytes are retained; no new prepare/transfer/query. The legacy PlaybackBinding title has no typed mutable counterpart; the typed identity named by the process check is the immutable materialization. No legal typed binding update is claimed. |
| `removedAndRecreatedDownloadIdsCannotInheritAStaleReadyMapOrCheckedReceipt` | Complete, canonical `remove` (forgets the exact process check, deletes live bytes, retains receipt/use history), re-admit the same accepted request/binding and `resume` under the same job id, gate the recreated run | No asset/materialization/check is inherited after the ABA; the recreated generation is QUEUED/RESOLVING with no Ready projection; a new asset id/URI and bytes are produced; two native checks, two transfers, one selector request (the original accepted recording), one physical job, two immutable receipt uses. This is a **legal current request/claim replacement**, not an in-place immutable row rewrite. |
| `bindingChangedDuringCompletedValidationCannotPublishACheckForTheReplacement` | Complete; cold owner; block the completed recheck at the native validator; make a legal new selection with a **different recording** and attempt admission; release the check | Admission is rejected with `INVALID_STATE` / `download_has_different_recording` and leaves the job, producer, materialization and asset use unchanged; the original recheck then publishes exactly one check for the original asset; one materialization/use, no prepare/transfer, no bytes lost. The replacement cannot rename/commit against the in-flight original. |
| `bindingChangedDuringFinalizationValidationCannotRenameOrCommitAgainstTheReplacement` | Real interrupted finalization; block recovery validation; legal replacement selection with a different recording; reject admission; release recovery | The replacement is rejected before any graph change; the original intent publishes its own `finalPath` and original producer binding exactly once; the retired attempt, journal and staged bytes follow the canonical recovery path; no prepare/transfer/query; no replacement asset. |

### DownloadOwnershipRoomTest

| Original method | New setup/command seam | Retained assertions and documented mapping |
|---|---|---|
| `twoReplayersHaveOneDurableClaimAndOnePublisher` | `prepareFinalizationSchema3` (real canonical interrupted history), two real Room connections, A validates through a barrier, B starts and closes | Exactly one durable canonical maintenance claim with a real lease; B cannot steal/clear it; one publisher creates exactly one materialization/receipt/use; finalization cleared; final bytes at the canonical destination, staging gone; B has no validation/prepare/transfer/legacy work. The typed retired materializer remains **PAUSED** history instead of the legacy `SUCCEEDED` relabel; recovery must not rewrite completed history. |
| `removeDuringNoncancellableValidationCannotPublishOrResurrect` | Real `recoveryIntent`; A blocked inside a NonCancellable validator; B starts and removes; B's drain is genuinely unacknowledged; release A, close A; B cleanup | B's remove returns `BACKGROUND_RESTRICTED download_owner_drain_unconfirmed` (no fabricated ACK); the durable claim is CANCELLED, cleanupPending and removeWhenClean; intent/staging retained, final absent; resume is rejected INVALID_STATE; after actual unwind the job is CANCELLED with no asset/finalization/bytes, one A decoder call, no B validation, no transfer/prepare/legacy work. No resurrection. |
| `changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates` | Real authored interrupted finalization; old validator fails while blocked; a second connection legally commits a CANCELLED non-retaining claim; release the old validator; bounded `+5 s` recheck; gated cleanup | The old finalizer cannot rewrite the replacement claim/generation/intent, producer, intent attempt identity or immutable attempt history; the removal intent stays durable; the old primitive CORRUPT_MEDIA diagnostic is retained on the already-cancelled/cleanup-pending job and then cleared by real cleanup; old staging is retained until cleanup; final destination is never fabricated before cleanup; resume is rejected; cleanup leaves CANCELLED/no asset/no finalization. A concurrent legal **new attempt** cannot be admitted while the old maintenance lease is live; the typed equivalent replacement is the committed claim-intent generation, which is what the test drives. |
| `publishedFailedIntentIsRecoveredAfterForeignLeaseExpiresWithoutRestart` | Real `recoveryIntent(published = true)` authored crash setup: a foreign canonical lease + published destination with OS locks already released; a second live coordinator starts before expiry and waits | Before `advance(1100)`: intent/producer/lease unchanged, scheduling plan's local deadline is the exact lease expiry, no validation/gate/prepare/transfer/query. After expiry, the same coordinator recovers in-process: asset URI equals the intent destination, asset id/attempt/ownership match the intent, finalization cleared, retired attempt stays PAUSED/finished, one real validation, no source/transfer/legacy work. The typed job is VERIFYING rather than the legacy FAILED seed; a terminal typed local failure would not be retried by maintenance. The durable foreign lease/expiry and published-intent recovery claims are preserved. |
| `staleEnqueueResumeAndPauseCannotDemoteOtherConnectionsCompletedAsset` | Real completed typed job; a second Room connection re-admits the same accepted binding to the one existing job, then resume/pause | The one physical job remains COMPLETE with the same asset/producer/representation/generation, no lease/attempt/finalization/cleanup; exactly one live materialization and use; bytes unchanged; the claim is explicitly PAUSED/retaining but the completed asset is not demoted; no validation/prepare/transfer/query/gate/legacy work. The legacy exact-record equality is superseded by immutable typed claim metadata changes only. |

### DownloadRecoveryRoomTest

| Original method | New setup/command seam | Retained assertions and documented mapping |
|---|---|---|
| `partialDeletionKeepsJournalForPriorAssetAndIntentDestinationUntilReopenCompletesAll` | Real completed asset plus a separate real interrupted/published intent (typed history cannot legally hold an asset and a pending finalization on one job); a real `ForwardingFileSystem` delete fault; a second connection commits removal/unacknowledged intent; the recovery rename creates the destination; both cleanups hit real STORAGE_IO; cold reopen | Both jobs keep cleanupPending, their exact prior asset/intent/producer/attempt journal and their real files; the old asset is never lost; the intent destination is never fabricated as deleted; resume during cleanup is rejected; the durable deadlines are exactly the published `+5 s` local rechecks; reopen preserves the plan deadline; after the cold retry all assets/finalizations/paths are actually removed while immutable attempts/receipt/use history is retained. The legacy single-job asset+intent shape is split because the typed graph cannot legally carry both; the byte-loss/journal claims are verified over the two canonical generations, and this split is not hidden. |
| `startupSameSizeCorruptionFailsDurablyAndRetryDeletesOldGenerationBeforeReacquiring` | Real completion, cold owner, same-size byte corruption, hash failure, bounded recheck, canonical remove with a release barrier, same-receipt re-admit + resume | Hash corruption is CORRUPT_MEDIA before the decoder; old asset/bytes/cleanupPending retained through the real delete barrier; the old generation is deleted before the new transfer; the repaired asset is a new URI with authored bytes; one transfer with the exact original context; zero new title queries; final removal deletes bytes. |
| `removeWaitsForActualReleaseCallbackAndCanBeReenteredWithoutCommandLockInversion` | Real completion; remove in a coroutine; a real `beforePhysicalDelete` barrier | Removal intent is durably cleanupPending with the asset/file still present; resume and pause return promptly and are rejected INVALID_STATE without mutating the removal claim; the removal coroutine remains blocked until the real callback returns; final state is CANCELLED with no asset/bytes, immutable attempts, retained receipt/use; no new prepare/transfer/legacy work. |
| `redownloadOfCorruptUnpublishedIntentCleansItInsteadOfReplayingForever` | Real interrupted unpublished intent; corrupt staging bytes; recovery fails pre-native; bounded `+5 s`; canonical remove cleans the intent/staging; same-receipt re-admit + resume | Corrupt staged bytes fail CORRUPT_MEDIA before native with the journal retained; canonical cleanup removes the intent/staging/final, leaves PAUSED attempt history; redownload produces one transfer with the original context and no new title query. Typed Resume alone would replay the corrupt journal; the actual canonical cleanup/redownload route is used and documented. |

### DownloadMemberRetryLifetimeRoomTest audit

The cold-reopen method was audited, not just rerun:

- Added exact durable reset checks before closing: QUEUED, no failure/next-retry/lease/active
  attempt, producer unchanged, claim generation +1, owner-intent revision unchanged.
- Replaced the opaque full-view equality after reopen with separate snapshot/canonical-graph
  assertions carrying the exact revisions, plus explicit no-file/no-lease/no-gate checks.
- Added a deterministic offline-phase check after `start(DUrable)` + `idle`: the retried job is
  still QUEUED with no owner/attempt/failure/retry, no gate/prepare/transfer/query, and the
  scheduling plan's blocked reason is OFFLINE_POLICY before connectivity is restored.
- Added exact no-new-query assertions on request/binding/receipt key sets, the prepared context
  and required representation key, prior-attempt immutability, producer identity and attemptCount+1.
- Conclusion: no deterministic ordering flaw was found. The earlier one-off AssertionError (15 s
  timeout diagnostic) was **not** reproduced in runs 003/004/005/006/007/009; it remains an
  unresolved intermittent and is not claimed fixed. These diagnostics now narrow the exact state
  if it recurs. No timeout was inflated, skipped, or masked.

## Evidence

All Gradle commands used the project wrapper with `--offline`, the literal bounded JVM option
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
the ignored bounded-test initializer (one fork, 384 MiB heap/256 MiB metaspace, SerialGC, 2 CPUs,
fresh XML redirect) and the one shared lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock` held through process
completion. Evidence is under
`.work/verification/pr4-download-integrity-recovery-ports/`.

| Run | What | Exit | Actual result |
|---|---|---:|---|
| run-001-compile | `:shared:compileTestKotlinJvm` | 1 | 27 test-compile diagnostics from a fixture mismatch in the new ownership port; preserved, then fixed |
| run-002-compile | `:shared:compileTestKotlinJvm` | 0 | test compilation green before the test fixes |
| run-003-four-classes | four owned classes | 1 | 31 executed, 5 failed (integrity 3, ownership 1, recovery 1); preserved |
| run-004-four-classes | four owned classes | 1 | 31 executed, 4 failed; preserved |
| run-005-four-classes | four owned classes | 0 | **31/31, 0 failures/errors/skips** |
| run-006-four-classes | four owned classes after import-only cleanup | 0 | **31/31, 0 failures/errors/skips** |
| run-007-four-classes | four owned classes after the final earlier-guard shapes | 1 | 31 executed, 30 pass / **1 pre-existing intermittent**: `unboundRetainedBytesNeverAcquireAFreshReceiptInTheirOldDirectory` failed `RECOVERY_REQUIRED` at `requireClearedSchema2` (schema2 adoption/cleanup race candidate). Not one of the eighteen; passed in runs 003–006. Failure preserved; not claimed fixed. |
| run-008-schema2-adoption-single | single-method diagnosis | n/a | queued behind the shared lock; the harness timeout expired before Gradle started (stdout 0 bytes, no process remained); **not** a test result |
| run-009-four-classes | final four owned classes on the committed code | 0 | **31 tests, 0 failures, 0 errors, 0 skipped**, 4 fresh suites; `:shared:compileTestKotlinJvm` UP-TO-DATE against the same source; `BUILD SUCCESSFUL` |

Final run-009 exact fresh XML and log receipts:

| Suite | Methods | Failures | Errors | Skipped | XML SHA-256 |
|---|---:|---:|---:|---:|---|
| `DownloadIntegrityRoomTest` | 9 | 0 | 0 | 0 | `91e3da69749d4927b3a4f42a247241f75a6c7b28488eb83a9844cbda0281eea1` |
| `DownloadOwnershipRoomTest` | 11 | 0 | 0 | 0 | `7abc990b8e46ce1766690614b8ca3ca6b3c0154b77dc4d2caa210ada2c7fb173` |
| `DownloadRecoveryRoomTest` | 8 | 0 | 0 | 0 | `3e2b52b601814851bbdad00338c236fc627d185f85ba242202b185bfe91ee45b` |
| `DownloadMemberRetryLifetimeRoomTest` | 3 | 0 | 0 | 0 | `598ad5afb7fd19db21360eb0737f2b4fb0068987dc908a662a4f90a2b6e4aaa7` |
| **Total** | **31** | **0** | **0** | **0** | — |

- run-009 log: 2,649 bytes, SHA-256 `3609c4d9982f55115d2abf3949bacd9084b9704460aae3ae6e25e2b2c1f4629f`;
  start `2026-11-07T20:03:27Z`, end `2026-11-07T20:06:10Z`, exit 0.
- Final tested source hashes (exact bytes in code commit `7d55360`):
  - Integrity `b8ae617e7797c99c1c762cbb05c87ab1db6d8e46eafb6206b91ad6df6fa55f62`
  - Ownership `55bc69a0ea3ad1b0d0a740d4dbc6ab0a5d20745b865c0647a3f52dadf029fc0c`
  - Recovery `7b2e7d49b383fd46b55da3b61619584a2ffec9e5a6c90b1c7ffe9b33bda36a6b`
  - Member retry `3581cd9a5ebc48dd6cecf57934fed5a78035aba86a804697e6ab6ac4166dad40`
  - New helper `a8e821d840c2ff6083a044a038485a9b4499a158a7bb4a3378b52454525f843b`
- `inputs-before.json` contains the 506 pre-edit manifest hashes; `inputs-after.json` contains the
  current 506 tracked hashes plus the explicitly hashed new helper (and this docs-only report).
  Drift is limited to the four owned class paths; there is no unexpected tracked drift.
- The gitlink is recorded separately as mode `160000`, expected and actual submodule HEAD
  `c0a0809…`, clean status and no untracked files; `gradle/kmpyt.commit` agrees.
- Earlier failed directories run-001/003/004/007 and the empty queued run-008 are retained.

## Honest limitations and open items

- The full 1,162-test shared suite was **not** run in this slice; run-009's green result is a
  focused four-class result, not a combined-suite or release claim.
- Run-007 exposed a previously-passing schema2 adoption failure outside the eighteen assigned
  methods. It is preserved and reported, not fixed or reclassified. A rerun on the final code
  passed it; the underlying race candidate remains unproven and unprotected by a new deterministic
  regression. The lead should treat the schema2 adoption/cleanup race as a separate follow-up.
- The member-retry cold-reopen intermittent has no proven root cause; the audit and diagnostics are
  reported as unresolved evidence, not a fix.
- Native/iOS/Xcode, device/emulator execution, live source access, real audio/codec validation,
  physical crossfade, packaging, signing and distribution were not executed. Transfers/native
  answers are authored fakes over non-audio bytes.
- The PR is draft and deliberately unmerged; the lead owns independent review, the combined run
  and the parent PR integration. No watch was started.
