# PR #4 room transactional regressions and portable prepared tests

Status: **ready for review; focused JVM verification green.** This stacked PR is based on
`t3/typed-room-download-recovery` (PR #4) and remains unmerged. It changes tests/fixtures and
verification evidence only. No production, schema, pin, Gradle, build, workflow, legal or
Compose file changed.

- Repository: `Apdelrahman1911/veyra`
- Base: `t3/typed-room-download-recovery` at `67594669c6cd51e63ca772b281d4fc56144cafdd`
- Tested source commit: `88f21fa119d7f65f31d456e52c355d38b436c940`
- Branch: `t3/pr4-room-regression-ports`
- PR: <https://github.com/Apdelrahman1911/veyra/pull/9>
- Accepted launch: provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, variant `max`.
  No independent runtime/backend/variant attestation is exposed; no substitution was made and
  no subagents were created.

## Changed paths and reasons

| Path | Reason |
|---|---|
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomLibraryStoreTest.kt` | Port the two failing methods from guarded snapshot seeds to real typed admission, physical claims, expiry/fence, finalization and canonical rejection. |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomWriteVerificationTest.kt` | Add a valid unrelated CATALOG saved/favorite relation to the same real rollback transaction as the offline-asset collision; remove the redundant identical branch. |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomTestSupport.kt` | Extend the existing low-level row fixture so the same transaction can also exercise a genuine typed saved row. No store/transaction authority was added. |
| `shared/src/jvmTest/kotlin/dev/veyra/data/RoomAcquisitionRestoreTest.kt` (new) | Actual Room close/reopen of a valid-video raw first receipt whose committed outcome is `Unplayable` because of a blocking page failure. |
| `shared/src/commonTest/kotlin/dev/veyra/source/youtube/YouTubePreparedDownloadGatewayTest.kt` | Remove the only `java.util.concurrent` import from `commonTest`; use portable `MutableStateFlow` counters while retaining simultaneous consumption/issuer/renewal assertions. |
| `shared/src/commonTest/kotlin/dev/veyra/acquisition/AcquisitionCoordinatorTest.kt` | Correct evidence wording: this Rig is a fake-port test; cite the new real-Room class and do not claim `gateway.restore(request, observation)` recreates a page failure absent from its inputs. |
| `docs/verification/pr4-second-correction-mapping.md` | Correct run 023/025/027 and old member-retry evidence; update R8/R9 mapping. |
| `docs/verification/pr4-room-regression-ports.md` (new) | This report. |

## Original-method to canonical-seam mapping

All seeded rows below are produced by canonical Room commands on the real `LibraryStore`
transaction owner. Authored material is limited to the requested item/title, source answer and
non-audio fixture bytes; Room, SQLite, file leases and finalization/readback are real.

### `RoomLibraryStoreTest.downloadUniquenessLeaseAndAttemptFinalizationAreTransactional`

| Original claim at parent `6759466` | New setup / command seam | Retained assertion |
|---|---|---|
| One durable download admission, not a snapshot `DownloadRecord`. | `DownloadExecutorSchema3Fixture.select` captures a typed catalog request and a real `AcquisitionCoordinator` first result; `enqueue` performs a live authored admission-token check and calls canonical `admitStandalonePhysicalDownload`. | The admitted join has exactly one claim, one typed producer, and the accepted request/binding/recording identity. |
| Two real Room owners race one claim; exactly one wins. | Two separate `DownloadExecutorSchema3Fixture.store()` owners (separate real Room connections) race `claimPhysicalDownloadAttempt` with distinct attempt ids and the same producer/generation. | Exactly one `PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>` and one `PhysicalDownloadCommit.Stale`; the durable join has one claim and the winner's lease owner. |
| A live lease rejects a third owner before expiry. | Canonical claim at `now = claimedAt + 1` against the live generation. | `Stale`. |
| Lease expiry enables a successor; the old owner cannot update it. | Advance the fake clock to `62_000`, read the real join, canonical `reconcilePhysicalDownloadIntent` (expires the old attempt, clears the lease, returns to `QUEUED`), then claim owner `C`. Old-fence `heartbeatPhysicalDownload` and `finishPhysicalDownloadAttempt` are attempted after the successor claims. | Reconciliation retired the old attempt with `finishedEpochMs`; successor lease owner is `C`; both old-owner updates are `Stale`; the join still says `C`. |
| Attempt and finalization survive. | Under the successor fence: real representation selection, heartbeat with authored bytes, `beginPhysicalDownloadValidation`, `preparePhysicalDownloadFinalization`. | The persisted `physicalAttempts` entry is `FINALIZING`; `physicalFinalizations[downloadId]` equals the prepared intent, including producer, paths, hash, size, attempt, representation and ownership generation. |
| Room `@Upsert` cannot hide a non-PK uniqueness conflict; the real SQL constraint is exercised with the intended error. | A raw `INSERT ... SELECT` copies the single admitted `downloads` row with a new `id` and a distinct real catalogue `subjectId`, so the only colliding unique key is the owned `mediaId`. | `UNIQUE constraint failed: downloads.mediaId` exactly; the raw transaction rolls back; one `downloads` row remains. The duplicate subject is a real catalog identity; the separate `subjectId` UNIQUE index cannot answer first and mask the intended error. |
| A rejected collision leaves the whole graph/revision and journal unchanged; no silent loss. | A second accepted request for the same owned catalogue media with a different recording is passed to canonical `enqueueAccepted`; `admitPhysicalJob` rejects the conflicting recording before writing. | `VeyraException(INVALID_STATE, "download_has_different_recording")`; the complete `AcquisitionRepositoryState` before/after is equal; one physical download; the `FINALIZING` attempt and finalization remain. |

### `RoomLibraryStoreTest.cleanupIntentBlocksOrdinaryClaimAndSurvivesRestart`

| Original claim | New setup / command seam | Retained assertion |
|---|---|---|
| A cleanup-intent owner excludes ordinary claims before restart. | Admit a typed physical job; take one canonical claim (real attempts/path); advance past its 1-second lease; reconcile the expired intent; call canonical `updateStandaloneDownloadIntent(CANCELLED, retainCompletedAsset=false)`. | The durable job has `cleanupPending` and `removeWhenClean`, status `CANCELLED`; a new canonical claim attempt is `Stale`. |
| Cleanup intent and exclusion survive cold reopen. | Close the owner coordinator, assert its positive dependency-release receipt, close the real store, create a fresh owner/store on the same database. | Reopened job still has `cleanupPending` and `removeWhenClean`, status `CANCELLED`, no lease; the retired attempt is still present/finished; a restart-time claim is `Stale`. |

## R10 corrections

### `RoomWriteVerificationTest.rejectsSharedOfflineAsset`

The previous test changed a favorite only in a pure proposed `AppSnapshot`; the real
`writerRowsUpdate` row transform never attempted it. The corrected case now runs both transforms
inside the same real `RoomLibraryPersistence.transactAcquisition` rollback:

- `favoriteSubjectRows += FavoriteSubjectEntity("music-2", SavedLibraryOrigin.CATALOG, null, null, null, null)`
  is a genuinely valid saved relation: `music-2` is a real catalogue subject and CATALOG origin
  carries no request/binding/occurrence/mode lineage.
- `favorites += FavoriteEntity("music-2")` is the legacy saved projection in the same transaction.

The two-owner/SQL asset collision still raises the exact `"Offline asset identities must be
unique"` or `"Offline asset files must have one owner"` message and the exact
`UNIQUE constraint failed: offline_assets.id`/`.uri` SQL error. After the transactional rejection
the test asserts `favorites` **and** `favorite_subject_rows` contain no `music-2` row, the graph
and revision are unchanged, and cold reopen is equal. The redundant identical
`if (duplicateId) existingAsset.id else existingAsset.id` assertion was removed.

### `YouTubePreparedDownloadGatewayTest`

`java.util.concurrent.atomic.AtomicInteger` was replaced by portable `MutableStateFlow<Int>` +
`update`. The simultaneous-consumption case still proves exactly one transfer, one success and
one `INVALID_STATE YOUTUBE_PREPARED_DOWNLOAD_CONSUMED`, with no extra inspection/transfer; the
renewal case still proves one transfer attempt and terminal capability consumption. This is JVM
test evidence only; no iOS compilation was run.

### `AcquisitionCoordinatorTest` and real-Room restore

The `Rig` uses `AcquisitionTestRepository`, not Room. Its wording now says so explicitly and
states that `gateway.restore(request, observation)` is not called and cannot encode a page
failure absent from its arguments; the committed `Unplayable` outcome is the durable guard.

The new `dev.veyra.data.RoomAcquisitionRestoreTest` uses `DownloadAcquisitionRoomFixture` (real
Room/SQLite, real canonical capture/claim/commit) to admit a pending occurrence, then commit
`SourceAcquisitionResult.Unplayable` with a raw ordinal-zero `VIDEO` entry
(`ABCDEFGHIJK`, complete title, canonical URL) and `FIRST_RESULT_UNAVAILABLE`. It asserts:

- accepted outcome is `Unplayable`, stage `SELECTION`, with the exact raw receipt and problem;
- no binding is created and the occurrence is `UNPLAYABLE` with no subject/binding;
- after actual coordinator/store close and fresh Room/store/owner open,
  `AcquisitionCoordinator.restore()` returns the same complete `AcquisitionRepositoryState`,
  including the outcome, problem and raw receipt;
- the reopened state has no binding, no executable occurrence, no source query, no prepare,
  transfer, validation or legacy fallback.

## Verification evidence

All Gradle commands held the shared lock
`/root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock` through process
completion. The literal bounded argument was:

`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`

The ignored initializer is `.work/verification/pr4-room-regression-ports/bounded-tests.init.gradle`;
fresh XML was redirected with `-Dveyra.lead.evidence=`. All paths are project-local.

### Final source-frozen run `run-012-final`

- Source HEAD: `88f21fa119d7f65f31d456e52c355d38b436c940`; worktree clean at start.
- UTC: `2026-11-07T17:38:56.870590+00:00` → `2026-11-07T17:56:50.922315+00:00`.
- Exit: **0**.
- Command: `:shared:compileKotlinJvm :shared:compileTestKotlinJvm :shared:jvmTest --tests 'dev.veyra.data.*' --tests 'dev.veyra.acquisition.AcquisitionCoordinatorTest' --tests 'dev.veyra.source.youtube.YouTubePreparedDownloadGatewayTest'`.
- Log SHA-256: `b20d50ddc8a84db90feb07af67222228a268c604645dd1bbe05993d9f7a77add`.
- 24 suites, **235 tests / 0 failures / 0 errors / 0 skipped**.
- Five assigned classes: `RoomLibraryStoreTest` 8, `RoomWriteVerificationTest` 5,
  `RoomAcquisitionRestoreTest` 1, `AcquisitionCoordinatorTest` 28,
  `YouTubePreparedDownloadGatewayTest` 9 — all zero failures/errors/skips.
- Data control pattern: 198 tests/22 suites, including migration, process, writer, driver,
  command and recovery controls; zero failures/errors/skips.
- XML manifest SHA-256: `704397b633454263e66895dc652f350d6bbd29de2016bdda6cb95cc1c4725922`.
- Exact method list SHA-256: `aa5dd9d18ab4063846b65ac7b9274b0d7a7e7d40df671eff27270f72a4dbc316`.
- 507 tracked regular-file inputs before/after; **0 drift**. Input-map SHA-256:
  `f24dcb8215de8b425e35986d5ebbcecdf115129266f47d9c2557ace1fff8581f`.
- Gitlink `third_party/kmpYT` is mode `160000` at
  `c0a08090fe7dbd76e20216559b8184ad2cccde63`; submodule HEAD matches, worktree clean, and
  `gradle/kmpyt.commit` matches. No submodule files were copied or hashed recursively.
- Schema export SHA-256 remains
  `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`.

Earlier failures are preserved, not relabelled: `run-001-compile` (offline plugin resolution
configuration failure), `run-004-focused` and `run-005-focused` (one `RoomLibraryStoreTest`
failure each, first a raw-SQL closed-row read and then the wrong `downloads.subjectId` unique
error), plus the `run-009`/`run-010`/`run-011` manifest-capture failures that aborted before
Gradle. Passing intermediate runs `run-006`, `run-007` and `run-008` are retained too, but
`run-012-final` is the exact committed-source verification.

## Limitations and remaining scope

- JVM shared tests only. No iOS/Xcode compilation, simulator/device, native playback, real
  audio, live-source authentication, package or distribution verification was executed.
  Passing `commonTest` on JVM is not iOS compile proof.
- The two former `RoomLibraryStoreTest` failures are now passing. Other lanes' known failing
  downloads and AppContainer classes remain outside this slice and were not edited:
  `DownloadCleanupRoomTest` (4), `DownloadHashCancellationRoomTest` (3),
  `DownloadIntegrityRoomTest` (9), `DownloadMaintenanceRoomTest` (8),
  `DownloadOwnershipRoomTest` (5), `DownloadRecoveryRoomTest` (4),
  `DownloadSchedulingRoomTest` (15), and the AppContainer composition set.
- No full `:shared:jvmTest` run is claimed; the parent's 1,162-test suite still needs the lead's
  combined integration and full-suite verification after all sibling slices are reviewed.
- No production defect was found or changed in this slice. The schema, pin, PR #2
  driver/writer safeguards, legal previews and ownership/cleanup code are untouched.

The tested source/test commit is `88f21fa119d7f65f31d456e52c355d38b436c940`. This report is a
docs-only successor commit; the 507 input hashes above remain the evidence for that tested
source, not a relabelled new execution.
