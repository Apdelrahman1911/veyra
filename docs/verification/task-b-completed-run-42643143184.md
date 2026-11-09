# Completed combined run and bounded retry — 2026-11-09

Manual workflow `42643143184` tested
`baa837009f355d68a2baf98f9c4d67ef81988df2` and completed with overall failure.

| Lane | Final result | Concrete outcome |
| --- | --- | --- |
| Android | Passed | Compile, unit tests, lint and package lane passed |
| Linux x64 | Passed | Desktop tests, DEB and inventory passed |
| Windows x64 | Failed | Python AWK preflight failed; Kotlin tests did not run; independent packaging failed the same preflight |
| macOS arm64 | Failed | One of 1,216 shared tests failed on a transfer-list observer; DMG and inventory built |
| macOS x64 | Passed | 1,216 shared tests, zero failures/errors/skips; 484 desktop tests, zero failures/errors, one Linux-only skip; DMG and inventory built |

The independent hosted production readback job `129281258975` passed and
reported `verified: true` for each of the five expected lane packets. This is
hosted packet verification, not a second independent local installer inspection.
The absence of PR checks is separate: this workflow is manually dispatched,
and the branch has newer fixes than the completed run's tested source.

## Corrections and next verification

`72f169d` moves the unchanged pinned upstream Windows MSVC dependency filter
into an AWK file. The received inline regex had lost one backslash; the exact
transport boundary remains unproven. The same bounded adapter and real
Make/Bash/AWK preflight exercise the new file before production configure.
FFmpeg recipe 34/34, native bundle 36/36 and notices 74/74 tests pass locally.
Actual Windows execution remains the proof step. See
`task-b-windows-awk-file-42643143184.md`.

`2a5f081` makes the ARM ownership test await the original transfer directly
instead of calling `single()` on a history that may already include the
legitimate retry. The OS-slot/no-overlap and final-count assertions remain.
All 11 ownership-class tests pass locally; actual ARM execution remains pending.
See `task-b-arm-ownership-observer-42643143184.md`.

The new `windows-macos-arm64` manual scope runs the normal full desktop test
path, installer generation, inventory and independent retention readback on
Windows x64 and macOS arm64 concurrently. It avoids repeating Android, Linux
and Intel, which passed this run. It does not select the reduced Windows
recovery diagnostic path. The exact build matrix and expected readback set
agree; a retain-builds request without package generation fails its lane plan.
All 17 workflow tests pass, and YAML parses with unique keys. No production
source change or tool installation is added by this scope.

## Retained evidence and limits

Evidence remains under `.work/verification/task-b-combined-42643143184/`:
`status-completed.json`, `macos-x64.log`, `production-readback.log`, the original
Windows/ARM logs, local correction receipts and `two-lane-workflow-tests.log`.
`completed-run-receipt.json` binds the completed result, logs, workflow and test
source. The next dispatch will use the corrected branch and these two lanes.
No successor result is claimed here.

No iOS, installation, physical/audio/live-source, signing, publication or
Task A/Task B acceptance is implied. Legal/native/JDK closure holds remain;
draft releases stay unpublished and original failure evidence is preserved.
