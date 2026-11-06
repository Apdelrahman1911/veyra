# Download ownership / recovery review

2026-11-06 · independent read-only review of `DownloadCoordinator.kt` and
`DownloadFiles.kt`, with the Room transaction/lease and gateway contracts checked.
The D01–D21 list is a ranked regression-test checklist, **not itself executed test
evidence**. The download implementation owner is patching the accepted
ownership/finalization/cleanup defects; a patch is not considered verified until
its regression tests pass. A separate, executed native-lock diagnostic is
recorded below; it is not an application or Kotlin-test pass. The separately
preserved 41-test JVM download checkpoint is also recorded below with its exact
scope; it does not verify subsequent maintenance/projection changes.

Reviewed baseline SHA-256:
- Coordinator: `75a1c4dad773516fc5f95f56ca638e7b05b447157ac47470913b0c96d49f364d`
- Files: `b6230c7e0c552b6b050b5cd33f53b99d4eed29c28e7e84081065988e67e220c2`

## Required regression cases

Use two coordinator/store instances over the same durable database, an authored
local media fixture, a controllable clock, a barrier-controlled gateway and
fault-injecting filesystem/persistence. Assert durable records **and** actual file
writes/deletes; an in-memory status assertion alone cannot prove ownership.

| ID | Priority | Scenario → required assertion |
|---|---|---|
| D01 | P1 | A owns a future lease and active attempt; B starts/reconciles, then B closes or loses its own background allowance → A's record, lease and attempt remain intact. Global offline/user-cancel policy must be distinguished from B's local lifecycle. |
| D02 | P1 | Two instances replay the same expired finalization intent behind a validation barrier → one durable claimant/publisher; the loser cannot mark the winner Failed, clear its lease or reset its files. |
| D03 | P1 | Cancel/pause/remove, ownership change or a newer intent occurs between validation and final commit → exact intent/attempt/owner/state compare-and-set rejects the stale commit and stale failure update; no resurrection or newer-file deletion. |
| D04 | P1 | A gateway writes without progress callbacks; B cancels/removes and immediately resumes → cancellation is durable, parent transfer is revoked and joined/acknowledged before destructive cleanup or reusing its files. A failed heartbeat must not merely cancel the heartbeat child while acquisition continues. |
| D05 | P1 | A stalls longer than its lease, B takes over/cleans/retries, then A resumes writing → expired ownership cannot write/unlink the new attempt's files. Expiry alone is not a writer join; require fenced/generation-scoped storage or enforced exclusive host ownership. |
| D06 | P1 | Kill/fail after intent insertion, after atomic publish, after Room commit, and during cleanup; then restart/cancel/remove → recover the valid asset or delete every owned staging/intent-final/prior-asset file, retaining the cleanup journal until all deletions succeed. Resume while cleanup is pending must not acquire new media. |
| D07 | P1 | B's cached state is stale while A completes or starts the item; B enqueues/resumes/pauses → guards inside the latest Room transaction preserve the winning state/lease and one download per media ID. No stale precheck may demote Complete or overwrite a live attempt. |
| D08 | P1 | An intent's foreign lease is still valid at startup, then expires; repeat with a Failed record whose published file is valid → scheduler retries durable recovery without restart and without a second gateway acquisition. |
| D09 | P2 | Complete file is missing, truncated, replaced by a nonregular file, or byte-corrupted without length change → never present an unchecked asset as Ready; surface usable validation/repair. Retry then remove must also remove the old failed generation, not overwrite its only reference. Playback's existing hash/decoder check does not itself repair download status. |
| D10 | P2 | Root/descendant symlink and a symlink ancestor of the configured root point to another task-owned fixture directory → reject or explicitly canonicalize/validate the trusted root **before** creating files; no unintended target writes/deletes. Current descendant checks do not establish ancestor safety. |
| D11 | P2 | OS allowance is denied/expired; expiration fires inline during acquire and during lease close → persist NeedsForeground with `BACKGROUND_RESTRICTED`, terminate acquisition and release allowance once, preserve resumable identity, and avoid caller/self-join. Also exercise a noncancellable close callback. |
| D12 | P2 | Two coordinators schedule disjoint IDs with parallelism 1/2/4, then the preference is lowered → enforce the documented total concurrency bound, not merely each instance's local `active.size`; no duplicate IDs or stuck leased rows. |
| D13 | P2 | Pause/close during hashing, native validation, a silent transfer and finalization → cancellation completes without the command-lock/join inversion; retained bytes stay paired with their immutable binding/representation and a restart remains recoverable. |
| D14 | P2 | Network/rate-limit retries, storage-full/I/O failures, corrupt transfer counts and changed representation → bounded durable backoff where appropriate, truthful typed errors otherwise, no Ready state or orphaned files after a failed retry. |
| D15 | Gate | Spy on acquisition and deny network during replay, cleanup, validation and offline playback → no extra gateway acquisition; online transfer goes only through kmpYT. Real-source/OS-native egress-denial evidence remains separate from this fixture test. |
| D16 | P1 | Hold a native generation/slot lease, attempt and reject a second lease in the same process, then probe from a separate process before releasing the first → the separate process must still be excluded. Repeat for FileChannel/RandomAccessFile, multiple failed probes and acquire/release cycles. A same-JVM-only test is insufficient; see the executed diagnostic below. |
| D17 | P1 | Play A, switch the UI/controller to B while native stop of A is barrier-blocked, then remove A; also cancel staged A before native cancellation completes → deletion acknowledgement must wait for actual native A release, including retiring/cancelled resolve jobs, not merely the current/staged metadata pointers. |
| D18 | P1 | A trusted mobile OS data-directory base contains a legitimate alias/symlink, while an app-owned child is either regular or maliciously symlinked → canonicalize the trusted base before appending owned children; accept the regular case and reject owned-child symlinks before writes. Use the same canonical root for artwork containment. A mobile source-path review is not a native-device run. |
| D19 | P1 | Retire/cancel/promote deck A and make its resource close fail, then successfully stop unrelated B → deletion of A and final engine close must not falsely acknowledge successful A cleanup. Retain/retry the failed retirement or keep a sticky veto; a successful unrelated stop is not proof that the forgotten resource closed. |
| D20 | P1 | A projected COMPLETE map lags a newer Room removal, failed record, asset replacement or binding-map change → every presented snapshot must use its own coherent revision/IDs/asset and exact checked receipt. Never overlay a stale complete map onto newer metadata or inherit verification merely because a changed receipt keeps its ID. Initial projection must also withhold Ready for unchecked persisted assets. |
| D21 | P2 | Room startup fails before the coordinator starts, then the host closes → cancel owned scopes/resources without reopening, checkpointing or flushing the failed/unstarted store. An existing startup failure must not be fabricated into a new download cleanup failure. |

## Preserved executed JVM checkpoint — not the current unverified patch

This reviewer independently read the XML and full log copied to
`.work/download-tests-checkpoint/`. The five download classes report **41 tests,
zero failures, errors or skips**: allowance 7, native file leases 5, ownership 11,
policy 10 and recovery 8. The coordinator recorded for that run is SHA-256
`c9fa390149754c3066da8ae4e152c33f8eb8da12c257c0eece1344df09051c0a`;
the complete source list is `source-sha256.txt`. The encompassing shared suite
**failed** (247 tests, six failures); its successful subset is not a full-suite
pass.

The passing D16-related fixture includes
`failedSameProcessContenderDoesNotReleaseFirstOwnersKernelLock`, which uses a
fresh JVM child, plus canonical-alias and partial-lock-release regressions.
Separately preserved Room XML under
`.work/architecture/evidence/shared-third/` reports all three
`RoomProcessRecoveryTest` cases passing, including the rejected same-JVM lease
probe and two killed-writer transaction cases. This is Linux/JVM kernel-lock and
Room evidence, **not Android/iOS execution or physical audio**.

The same playback checkpoint passes the retired active/staged/resolver and
unhanded-resource release-barrier cases. However,
`failedNativeStopVetoesDeletionWithoutInfiniteAutomaticStopRetries` fails: a
queued same-generation `Resolved` message can overwrite the displayed ERROR
after the stop failure. The deletion veto remained in place. The architecture
owner has authored a fresh-generation failure handler and stronger resource
disposal/stale-event assertions, but that repair has **not been rerun here**.
Desktop adapter release tests and remaining D09/D13 variants are not closed by
this checkpoint.

## Current maintenance, startup and projection follow-up

Post-checkpoint source introduces separately tracked/gated cleanup,
finalization, completed-file validation and orphan deletion. Claim and child
registration now share command admission before native allowance acquisition;
expiration can latch host suspension before Room and cancel only owned work,
including a gate that expires inline. The reconciler is separate from acquisition
and startup no longer waits for every stored file to validate. Unchecked durable
COMPLETE receipts are projected as Verifying/Needs foreground without rewriting
the completion receipt just to represent this process's check. These changes
require fresh cancellation/allowance/restart tests; the 41-test checkpoint
predates them.

Root projection review found D20 in a second asynchronous combine that copied
the entire projected download map over a newer raw Room snapshot. Root and
download owners accepted a single coherent projected `AppSnapshot` stream and
exact receipt retention with the checked asset. The review also found D21 in
unconditional `DownloadCoordinator.close()` checkpoint/flush after failed Room
startup; the owner accepted an unstarted-close guard. Repairs/tests are pending
the serialized compile slots, not represented as executed closure.

## Follow-up review of generation/maintenance protocol

Read-only follow-up reviewed the complete coordinator and files implementation:
- Coordinator: `d8b718399a735d4768e8cc95099184dec56cac2b6d3b77fd137996e2e5a1ba2e`
- Files: `2ad2251dee66186cf7901d8cc8a991b6e808226126b3a28dfe58c0ff1423c2a3`

Source now contains latest-transaction claims, random attempt-key staging/final
names, stable never-unlinked native lock files, bounded global slots, exact
intent/attempt/owner/status finalization CAS, durable cleanup journaling and
periodic recovery. Cleanup locks all referenced generations/finals before
deleting; an old writer's acknowledgement changes only its own attempt, not a
new owner's record. Heartbeats cancel the parent execution, and an inline
allowance-expiration callback cancels without joining itself. These are observed
implementation changes, **not closure of D01–D15**. No Gradle/Kotlin download
regressions were executed by this reviewer.

The composition root now supplies `beforePhysicalDelete` through
`PlaybackController.releaseForAssetRemoval`. At review time, that controller's
early success when the item was no longer current/staged omitted pending native
release of a retired item (D17). The playback owner received the precise
A→B/blocked-stop and cancelled-stage scenarios. Actual native-release tests
remain required.

Additional follow-up source review observed both requested edge-case repairs:
aggregate lease close attempts every member even if one close fails, and a slot
selected before failed maintenance-lock release is also closed; enqueueing an
existing failed corrupt finalization now requests the same cleanup repair as
explicit resume. Latest reviewed Coordinator SHA-256:
`e69032c1c31e1a2bfe2002ccd8fbacc3e8bddc58be65a5b5e9a38ecac0769f11`;
Files: `679315755c9e5d2871ac039ba8f3c77c00439c496a39f83f3b53859037c7a15e`.
These patches remain unverified by executed Kotlin regressions in this review.

## D17–D19 native-release and platform-root follow-up

Controller SHA-256
`3ea888f161f70d4309810d64a713afc788a6bd49eace86e04703f15ab07c1928`
now tracks retired resolver jobs, joins matching jobs outside its actor/native
worker, performs a FIFO actor roundtrip, and always queues ordered native and
controller-resource cleanup fences before release acknowledgement. It checks the
generation again before success and preserves native failure as a veto until a
successful full stop. A failed stop no longer recursively schedules failed stops.
Source review supports these structural changes. Six additional controller
regressions cover retired active/staged/resolver releases, newer generations,
failed unhanded-resource close and failed native stop; they were **not executed
by this reviewer**. Adapter and physical file-release evidence remains separate.

That review exposed D19 in the desktop adapter: cancelled/promoted old deck
ownership was dropped before `dispose`, and `dispose` cleared `slot.source` even
if resource close failed. A later successful unrelated full stop could then clear
the shared veto despite the lost failed retirement. The desktop owner accepted
the finding and added sticky resource/native-close accounting; stop/cancel/repeated
close cannot acknowledge success after a failed retirement, and shutdown returns
an exceptional completion rather than swallowing cleanup failure. Failed resource
close no longer clears `slot.source`; promoted audio is silenced on cleanup
failure. `MpvAudioDeck` also completes teardown waiters exceptionally if native
destroy throws instead of leaving them suspended. Reviewed hashes:
`DesktopAudioEngine.kt` — `a7995c61468c0d6b4ebee9864cb1e80e967a0727f7f33f3464192be9538320c9`;
`MpvAudioDeck.kt` — `58c4a1996848e9a76821b4eaa5c610494d32838e9ef7a87021ee6922a5090572`.
Three additional desktop regression groups are authored, **not executed here**.
Android/iOS source already retains a sticky resource-cleanup failure;
iOS explicitly does not claim a public per-file-descriptor close acknowledgement.

D18 was found by comparing strict `DownloadFiles.checkRootAncestors` with Android
`directory.absolutePath` and iOS's raw Application Support path. Inputs containing
legitimate OS aliases would be rejected; iOS's canonical-artwork/raw-root prefix
comparison could also reject safe cached art. The mobile owner now canonicalizes
**only the OS-trusted base**, preserves owned-root/child symlink rejection, moves
Android directory work off Main and supplies the same canonical root to artwork
containment. Native fixtures cover a trusted-base alias versus an owned-root
symlink; an additional owned media/artwork child case was requested. These are
observed source changes, not executed Android/iOS path evidence.

## Executed D16 diagnostic: failed probe releases another holder's lock

On Linux amd64, OpenJDK `21.0.12+8-1-24.04-Ubuntu`, a small authored Java
diagnostic reproduced the native adapters' open/tryLock/close sequence. It ran
under `scripts/dev.sh`; code, classes, lock files and log are repository-owned
under `.work/design-lock-review/`. It performs no network or media acquisition.

Reviewed pre-fix adapter SHA-256:
- JVM: `548d477991b968130b27bd13077433d6201dc8127e6478e96a76506a6f3b2aab`
- Android: `c7eb65980189186f7ebe3b692f3e6063178ed200d3a13471c1c8963c6f635ee5`

Diagnostic source: `FileLockCloseProbe.java`, SHA-256
`a8d7fc3dc9d6bbd9c405356c1d8f87a7e5a64fbbcd868113022c247c4c64388d`.
Run command inside the scoped shell:

```sh
javac -d .work/design-lock-review .work/design-lock-review/FileLockCloseProbe.java
java -cp .work/design-lock-review FileLockCloseProbe .work/design-lock-review
```

Exit **0 confirmed the defect**, not the desired fixed behavior. Both
FileChannel and RandomAccessFile variants observed:
1. Separate child process excluded while the first owner held the lock.
2. Second open in the owner process raised `OverlappingFileLockException`.
3. Closing that second descriptor let a new child acquire the same file lock,
   while the first owner's `FileLock.isValid` was still true and its release had
   not run.

This is the process-associated POSIX lock close hazard: Java's local overlap
table is not proof that its kernel lock survives another descriptor's close.
Repeated slot/generation probes can therefore invalidate cross-process fencing.
The implementation owner accepted the finding and added synchronized
canonical-path registries that reject local contention **before opening another
descriptor**, retain entries through close, make repeated close idempotent and
retain a reservation if descriptor close fails. Reviewed download adapter hashes:
JVM `e71028d2e8def3d12eab800a1c1c03d5f3c0eca1e17324b46b18c1897832e7ae`;
Android `13a66c396e9d54ea0c2fa64c808c5c79591e327aec66c65871b503613c92baf1`.
The same unsafe pattern was separately identified and patched in the JVM/Android
Room `DatabaseLease` and desktop `DesktopProcessContext`; authored regressions
probe kernel exclusion from a fresh process after failed same-JVM contenders and
after a repeated stale close under a new owner. `DesktopProcessContext` was also
revised to retain its registry reservation on exceptional descriptor close.
These Kotlin/platform regressions still need execution. The scoped Java probe
above reproduces the **pre-fix defect only**, not fixed behavior. It exercises the
Android adapter's Java API pattern, **not an Android-runtime test**; iOS uses a
different `flock` implementation and was not executed here.

## Acquisition boundary observed

These two modules contain no HTTP/socket/range loop or independent RSS media
downloader. Transfer goes through `KmpMediaGateway`; `HashingSource` reads a local
Okio file. This source inspection does **not** verify the concrete gateway,
native-worker integration, real kmpYT success or offline native-player egress.
File flush plus atomic rename also does not establish power-loss directory
durability; test process-crash recovery separately from power-loss guarantees.
