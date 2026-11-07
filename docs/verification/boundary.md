# Build/runtime filesystem boundary

Veyra tooling may modify only this repository (including ignored `.work`) and the separately authorized kmpYT checkout. Existing SDKs/tools are read-only inputs. No global installs, caches, Git configuration, service configuration, or unrelated cleanup is allowed.

## Verified tooling checks — 2026-11-06

`scripts/env.sh` is sourced only in build subprocesses through `scripts/dev.sh` / `scripts/gradle.sh`; the terminal's original `HOME` and GitHub authentication context remain unchanged. Tool homes, temp paths, Gradle/Konan/Android/XDG directories, JNA extraction, and Java preferences are explicitly project-owned. A pre-existing escaping directory symlink is rejected before directory creation.

The standard-library tests in `scripts/tests/test_tooling.py` check argument preservation, directory containment, parent-environment preservation, escaping-symlink rejection, and real Java/Node temporary-directory parity. AGP 8.13 rejects conflicting deprecated preference overrides, so only `ANDROID_USER_HOME` is set; `ANDROID_PREFS_ROOT` and `ANDROID_SDK_HOME` are removed in the build subprocess. No SDK directory is changed to solve this configuration issue.

## Disclosed transient Xvfb violation — 2026-11-06

A lightweight display-tool audit disabled Xvfb's default `/tmp` X11 socket/lock creation, redirected ordinary homes/temp/authentication/log files, and traced file operations while issuing `xdpyinfo` (not while running the application).

The trace nevertheless showed the existing Xvfb's `xkbcomp` child **created `/var/lib/xkb/server-53953.xkm` outside the allowed repository**. Xvfb read and automatically unlinked that file before the audited process exited. The lead was informed immediately and disclosed the incident to the owner in the terminal and Telegram. No out-of-scope cleanup command was run; no remaining file is claimed to justify the original write.

Evidence (task-owned, not a private terminal transcript): `.work/verification/xvfb-boundary.trace`. Relevant operations:

- `openat(..., "/var/lib/xkb/server-53953.xkm", O_WRONLY|O_CREAT|O_EXCL, ...)` succeeded.
- Xvfb's later `unlink("/var/lib/xkb/server-53953.xkm")` succeeded.

This was a real boundary violation, **not a successful containment check**. The display launcher was disabled before further display runs.

### Cause and verified display-tool remediation

The official [Xorg 21.1.12 `xkb/ddxLoad.c`](https://gitlab.freedesktop.org/xorg/xserver/-/blob/xorg-server-21.1.12/xkb/ddxLoad.c) `OutputDirectory` routine selects writable `XKM_OUTPUT_DIR` (here `/var/lib/xkb`), falling back to literal `/tmp`. It does not honor `TMPDIR`; `-xkbdir` changes keyboard input data, not this output directory. Therefore changing ordinary environment variables or X11 socket flags alone cannot contain this version's keyboard cache.

The next run was blocked until the lead authorized a private namespace guard and the guard passed **without starting Xvfb**. `scripts/linux-namespace.sh` now:

1. Creates a mount namespace, then compares its identity with the original and parent namespace **before any mount/propagation operation**.
2. Makes propagation private and gives every existing writable mount a private read-only **bind** view. It never remounts a shared filesystem superblock or writes mtab.
3. Reopens only the repository plus `/tmp`, `/var/lib/xkb`, and `/dev/shm`, whose backing directories are `.work/tmp`, `.work/cache/xkb`, and `.work/shm`. It validates effective read-only flags and each alias's device/inode identity, not merely textual mount names.
4. Drops all effective/bounding capabilities and sets `NoNewPrivs` before executing the display/test command. Missing tools, namespace permission, or a verification failure stops execution. There is **no sudo, sysctl, AppArmor change, installation, or uncontained fallback**.

The non-display probe observed `/`, `/usr`, `/proc`, `/sys`, and `/dev` read-only; wrote and removed only files in the verified repository-backed aliases; and verified that the caller's namespace and complete mountinfo were unchanged. Evidence: `.work/verification/namespace-guard.json`.

**One authorized guarded display audit then passed:** Xvfb 21.1.11 started and authenticated `xdpyinfo` completed. Review of the file-operation trace found successful ordinary file mutations only in the repository or its verified XKB alias; `/dev/null` was an existing device output, not an ordinary file. `/var/lib/xkb/server-47959.xkm` in this trace was backed by `.work/cache/xkb`, unlike the disclosed earlier incident. Host mountinfo was byte-identical before/after. Evidence: `.work/verification/xvfb-guarded.{trace,json}`, `xvfb-guarded-client.log`, and `xvfb-guarded-stderr.log`. Benign unresolved-key-symbol warnings did not prevent the display query.

`scripts/with-display.sh` is re-enabled **only through this verified guard**, with scoped authentication/log files and no unauthenticated X server. This establishes the display helper on this Linux host, **not application execution, screenshot verification, audible playback, or GitHub-runner namespace support**. The guard prevents accidental ordinary filesystem writes; it is not a hostile-code sandbox and does not claim to isolate device I/O, network connections, or host-managed services. Those remain separate audit concerns.

### Nested-client authentication correction

The first actual Compose UI run compiled its tests but all 67 UI cases failed
initialization: the Java client could not authenticate. `with-display.sh` chose
a per-run cookie, then `gradle.sh` re-sourced `env.sh`, which unconditionally
replaced `XAUTHORITY` with the empty scoped default. A successful direct
`xdpyinfo` probe had not exercised that nested environment path. This was an
authentication plumbing defect, **not evidence of a working app UI**.

The wrapper now creates a fresh private authentication file. Nested scoped
environments retain it only with the explicit matching namespace context,
literal loopback display, exact owned runtime parent, no symlink ancestors,
regular/single-link/current-owner file, private permissions and bounded size.
Unmarked inherited host authentication is still replaced, and an invalid marked
context fails closed. After TCP readiness the wrapper runs a real authenticated
`xdpyinfo` through `dev.sh` before the requested command. No cookie is printed;
no `-ac`, wildcard authentication, external display or unguarded fallback was
introduced.

On 2026-11-06, **10/10 scoped-tooling tests passed**, including nested preservation
and invalid-context regressions. An actual small Java AWT client, run through
`with-display.sh dev.sh`, created and disposed its window successfully; the
caller mountinfo hash was unchanged. Evidence:
`.work/verification/tooling-display-auth-tests.log` and
`.work/verification/display-auth-preflight.log`. This proves authenticated nested
Java access on this host, not that the failed Compose suite has subsequently
passed; that requires its own rerun and rendered review.

## Pending platform audits

- Android's authored [no-guest Stage 0 runner](android-stage0.md) adds an
  explicitly requested **empty read-only `/run` mask**, with `/var/run` verified
  as the same alias. Default display callers retain their original mount check.
  The runner uses a separate private network namespace, owned5038 ADB plan,
  pidfd teardown, and tracee exit-kill. Its Python/tracer regressions are not an
  Android-tool or guest execution: the native preflight still requires lead
  review and separate approval, and `--run` remains exit78.
- Actions checkout is pinned to a reviewed version with `set-safe-directory: false`, `persist-credentials: false`, and its submodule credential helper disabled. Public submodule initialization happens separately in a scoped process. Newer checkout versions that write credential config under the host `RUNNER_TEMP` need another containment review before upgrade.
- Xcode build/derived/result/module-cache paths are owned; CoreSimulator's host-managed service state is a separate unresolved audit and simulator execution is not assumed safe solely because its device set has a custom path.
- Packaged native audio extraction/decoder tests must use the app's included JRE/native libraries and owned storage. A read-only installed host library is not permission to install a missing one.
- Actions-managed reporting/control files (job summary/step outputs) are the explicit workflow reporting channel; no application caches, private data, or signing credentials belong there.
