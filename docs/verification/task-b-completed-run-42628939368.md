# Completed combined run and cleanup handoff correction — 2026-11-09

Run `42628939368` tested `7b91522ec5a233caf11ff497f517f6dfc0c1d1cc` and completed
with overall failure. It ran Android, Linux x64, Windows x64, macOS arm64 and
macOS x64. The workflow is manual; the absence of PR checks is separate from
these actual workflow results.

| Lane | Tests | Installer/build | Result |
| --- | --- | --- | --- |
| Android | 713 shared + 160 Compose, zero failures/errors/skips | APK/AAB builds and lint passed | Passed |
| Linux x64 | 1,215 shared passed; 484 desktop with nine failures | DEB built | Failed |
| Windows x64 | 1,215 shared passed; 484 desktop, zero failures/errors, one skip | FFmpeg Make AWK syntax error; no installer | Failed |
| macOS arm64 | 1,215 shared passed; 484 desktop, zero failures/errors, one skip | DMG built | Passed |
| macOS x64 | 1,215 shared with one failure; 484 desktop, zero failures/errors, one skip | DMG built | Failed |

The desktop skip on Windows/macOS is the Linux-only real D-Bus/MPRIS test.
Linux's nine negatives are native audio/crossfade tests loading MPV without
`libEGL.so.1`. Windows configure finished before its Make dependency-filter
failure. Corrections and local checks are recorded in
`task-b-linux-native-closure-42628939368.md` and
`task-b-windows-make-shell-42628939368.md`; they are included in the successor.
Actual hosted Windows execution remains necessary.

## Intel cleanup handoff

`DownloadRemovalInterruptedRetryRoomTest.interruptedRemovalStaysPendingThroughTheSecondRetryOwnerAndThenSucceeds`
failed at the assertion that removal remain pending after the first cancelled
cleanup owner released its lease. The retained XML reports 1.147 seconds, below
the five-second owner grace. The original assertion did not include the removal
exception. The original method passes in a local focused diagnostic run, so its
host scheduling interleaving has not been independently reproduced.

Source inspection found a concrete competing path: the scheduler may own the
cleanup, so the command observes settlement without catching the child's
cancellation. A stale finish from a revoked/superseded owner was already classified
as benign for checkpoint handling, but still emitted a terminal `Failed` removal
observation. That contradicts the accepted requirement to await the foreground
retry after the old owner has released its physical resources.

A new deterministic runtime regression registers that exact revoked owner,
consumes its benign stale finalizer, and observes through the ordinary terminal-
failure policy. It fails on the prior source. The correction emits `InProgress`
for this benign stale settlement, preserving durable `cleanupPending` and the
existing bounded wait for real deletion. Non-benign stale settlements, failed
physical closes and unconfirmed writes retain their failure behavior. The real
Room assertion now also reports its removal exception on failure. This closes
the demonstrated source defect; it does not prove the missing historical
exception or hosted Intel result.

## Validation and retention

The corrected common production source also passed the actual incremental
`:shared:compileDebugKotlinAndroid` task (exit 0); evidence is
`android-cleanup-compile.log` / `.exit`. Existing compiler warnings remain.
The earlier 873-test Android result belongs to `7b91522`; it was not rerun locally
for this common-code change. The next combined workflow checks the successor on
Android and all four desktop lanes. No hosted successor result is claimed here.

Evidence is under `.work/verification/task-b-combined-42628939368/`:

- `final-status.json`, lane console logs and `production-readback.log` retain the
  original results, including all negatives.
- `desktop-readback-receipt.json` verifies Linux/Windows/ARM support packets;
  `final-readback-receipt.json` verifies Intel/Android. All five exact draft tags
  and **911 indexed entries** verify against the manifests. Sanitized XML agrees
  with the counts above. Independent hosted production readback also passed.
- `intel-removal-before/` / `.log` / `.exit` retain the original Room method's
  local pass with the diagnostic assertion added.
- `intel-stale-red/` / `.log` / `.exit` retain the new discriminator's failure
  against unchanged production source.
- `intel-removal-final/` / `.log` / `.exit` are the focused correction gate.
  **80 tests across six suites passed**, zero failures/errors/skips, exit 0.
  This covers physical commands/runtime, recovery corrections, real Room recovery,
  the interrupted Room retry and removal observations. Shared production and test
  Kotlin compilation executed. `intel-removal-receipt.json` binds the affected
  source, XML and logs. These were working-tree runs without a whole-tree
  admission manifest; they are not hosted Intel execution.

Support-packet verification is separate from independently reopening installers,
installation, signing, audible output, device/live-source verification or release
acceptance. No release was published. Existing Task A/Task B and legal/native/JDK
closure holds remain open.
