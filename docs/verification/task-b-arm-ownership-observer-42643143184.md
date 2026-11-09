# ARM ownership-test observation correction — 2026-11-09

Run `42643143184` at `baa837009f355d68a2baf98f9c4d67ef81988df2` failed
one of 1,216 shared JVM tests on macOS arm64. The retained stack names
`DownloadOwnershipRoomTest.expiryDoesNotExceedOnePhysicalTransferWhileZombieHoldsGlobalSlot`
and `IllegalArgumentException: List has more than one element` at line 325.
The ARM DMG and runtime inventory still built successfully. The previous ARM
run `42628939368` at `7b91522` passed; that pass does not exclude this ordering.

The test releases a blocked, noncancellable old transfer, then repeatedly calls
`a.gateway.transfers.single()` while waiting for its completion. Once its OS
slot is released, either still-live coordinator may legitimately record the
retry. The test explicitly expects two transfers across both coordinators at
completion, so requiring A's history to remain a singleton during this wait is
an invalid observer assumption. The exception proves A's history had grown at
that point; it does not establish two simultaneous physical transfers.

The correction captures the original transfer after it enters the barrier and
waits on that exact operation. The checks that B cannot transfer while the old
OS slot is held, the actual slot remains unavailable, the final job completes,
and exactly two total transfers occur are retained. No production source is
changed for this correction.

## Focused verification

The full `DownloadOwnershipRoomTest` class passes locally on Linux: **11 tests,
0 failures, 0 errors, 0 skipped; Gradle exit 0**. This includes the failed method.
The command used the existing project lock via `vps-build`, offline dependencies,
and the established bounded JVM/in-process Kotlin settings. No full-suite rerun
was needed for this test-only correction.

Evidence is under `.work/verification/task-b-combined-42643143184/`:
`macos-arm64.log`, `arm-ownership-final.log`, `arm-ownership-final.exit`,
`arm-ownership-final/shared/jvmTest/xml/` and `arm-ownership-receipt.json`.
The receipt records exact source, log and XML hashes. Actual macOS execution
of the corrected test remains pending; no successor hosted pass or release
acceptance is claimed.
