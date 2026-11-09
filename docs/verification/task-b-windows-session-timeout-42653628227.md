# Windows session-helper timeout correction — 2026-11-09

Retry `42653628227` tested `bbcc62f68713ad43c8a9b0f8adce6195211b0a21`.
macOS arm64 passed 1,216 shared tests and 484 desktop tests (one Linux-only
skip), then built its DMG and inventory. Windows passed the same test counts,
but installer preparation failed. Independent hosted readback passed for both
lane packets; the failed Windows packet truthfully contains no installer.

## Exact failure and its limits

The Windows record reports `reason=timeout`, `cleanupComplete=true`,
`returncode=null`, and log `.work/native-session/windows-x64/build.log`.
No compiler-error lines were retained. That name was previously shared by
CMake configuration and compilation, so it does not distinguish those commands.
No raw native-session log survived the ephemeral runner; the exact historical
compiler/process state is unknown.

Preparation reached the session helper after the FFmpeg build, output-header
and sidecar checks returned. The prior AWK failure was passed. This result is
not another AWK/configure failure and does not establish a C++ compiler error.
Windows application tests passed; the failure belongs to installer preparation.

## Scoped correction

MSBuild's documented default permits worker nodes to remain alive for reuse.
The Windows owned-process runner requires the entire Job to empty before it
reports success. Idle reusable workers therefore conflict with this ownership
contract. This is a source-supported explanation, not proof of the missing
historical process state.

Native Windows children now receive `MSBUILDDISABLENODEREUSE=1` after the scoped
VS environment is prepared. The CMake build also forwards `/nodeReuse:false`
to MSBuild. The helper result and inherited parent environment are not mutated.
No global environment, runner configuration, tool installation, ownership rule
or timeout is changed.

The Windows helper runs before the cold FFmpeg build so an admission/compile
failure is detected earlier. Configuration and compilation now preserve separate
`configure.log` / `build.log` files and print literal started/completed phase
markers. Success markers follow the awaited command. The helper is still
architecture/size checked and included in the same staged inventory. macOS keeps
its existing preparation order; Linux still has no C session helper.

Microsoft's command-line reference documents the node-reuse switch, and its
MSBuild `BuildParameters` source explicitly honors the environment value `1`.
Primary references inspected and retained under the evidence root:
`msbuild-command-line-reference.html`, `msbuild-BuildParameters.cs`.
Canonical sources:
`https://learn.microsoft.com/en-us/visualstudio/msbuild/msbuild-command-line-reference?view=visualstudio`
and
`https://github.com/dotnet/msbuild/blob/main/src/Build/BackEnd/BuildManager/BuildParameters.cs`.
These documents explain the selected controls; they do not attest the hosted
runner's exact worker state.

## Verification and next run

- Native bundle tests: **39/39**, including child-only no-reuse configuration,
  separate phase logs, Windows-only MSBuild argument forwarding, truthful
  configure failure markers and helper failure before any FFmpeg command.
- Windows toolchain tests: **10/10**.
- `git diff --check` passes. These are Linux authored-seam executions; no local
  Windows compiler or MSBuild execution is claimed.
- Downloaded Windows `support.zip` matches its manifest, and all **205 indexed
  entries** match their declared byte counts/hashes. Its exact source/platform/
  outcome identity is retained. Hosted independent readback passed both lanes.

Evidence: `.work/verification/task-b-retry-42653628227/`, including lane logs,
`windows/manifest.json`, `windows/support.zip`, the exact native diagnostic,
`native-session-tests.log` (initial assertion mismatch preserved),
`native-session-final-tests.log`, `windows-toolchain-tests.log` and
`session-timeout-receipt.json`.

The next retry selects Windows only with full desktop tests, installer
preparation and independent retention readback. Actual Windows execution of the
correction remains required. No installer, installation, physical audio,
iOS, signing, publication or full release acceptance is claimed. Draft releases
stay unpublished; all existing legal/native/JDK/source closure holds remain.
