# Windows CMake configuration probe — 2026-11-09

Run `42664351655` at `c149781d50e72ad21aad7f5a7ea2227849f287fc` passed
1,216 shared and 484 desktop tests (one Linux-only skip). Installer preparation
then logged `Native session configure started` and timed out exactly 180 seconds
later. It never logged configuration completion or compilation start. The
separate diagnostic path is `.work/native-session/windows-x64/configure.log`.
The no-reuse correction did not resolve this configuration timeout. Early helper
admission avoided repeating FFmpeg first. Independent hosted failed-lane
readback passed; no Windows installer exists for this attempt.

The retained diagnostic did not record root exit or active descendant identity.
Therefore the original process state remains unknown. No timeout increase,
cleanup bypass or compiler-error attribution is justified by this evidence.

## Targeted diagnostic

The owned runner now samples command-completion status and its exact Windows
Job membership before existing cleanup. Query-only process handles are checked
with `IsProcessInJob` before image inspection, protecting against recycled PIDs.
Only PIDs and a fixed allowlist of build-tool basenames are retained; unknown
images are `<other>`. Paths, command lines, environment and arbitrary log output
are excluded. The query is bounded to 64 PID slots / 16 retained entries;
query failure is optional metadata and cannot mask the original result or
change drain requirements. The bundle reporter retains and prints this small
structured state. Existing timeout and kill/drain behavior is unchanged.

A `windows-session-probe` manual scope selects one read-only Windows-2022 job,
with an eight-minute job cap. It runs the actual owned-process tests (including
an exited-root/live-child snapshot case), bundle tests, then the real matching-
host helper under its preparation lock. It does not run Gradle, FFmpeg, packages,
release retention or other platforms. Diagnostic state is printed in the job log.
This short probe exists to establish the next correction before another full
installer attempt, not to establish application/release acceptance.

Local checks: **32 process tests discovered, 30 passed, 2 Windows-only skips**;
**40/40 bundle tests**, **18/18 workflow tests**; unique-key YAML parsing and
`git diff --check` pass. Actual Windows API/child-process execution remains the
probe's proof step. The initial process-test syntax error and corrected result
are both retained. Evidence is under
`.work/verification/task-b-windows-42664351655/`.
