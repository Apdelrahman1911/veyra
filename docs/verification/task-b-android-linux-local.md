# Android/Linux local verification and batched Windows continuation

2026-11-09 UTC. Existing PR27 checkout and branch; no additional worktree or
implementation delegate. Android/application Kotlin is unchanged by this correction.
The starting commit was `27335f3d678446ed6da2da4a51db3c0a81f1d9eb`.
Later local gates include the dirty script/native diagnostic corrections described
below; their input manifests and logs are retained, not relabeled as clean-commit runs.

## Corrections

- `scripts/gradle.sh` separates pinned distribution bootstrap from the requested
  task. Only the exact official Gradle 8.13 download's HTTP 502/503/504 response
  permits up to three bootstrap attempts. Checksum failures and task failures
  are never retried. Five executable wrapper-fixture regressions cover this.
- Linux retains Ubuntu 24.04. The retained native library ABI inspection found
  GLIBC 2.38 requirements; changing to Ubuntu 22.04 would introduce an ABI failure.
  The existing runner SBOM includes Podman 4.9.3, its AppArmor profile and `unshare`.
  CI selects `podman unshare` with storage/run/temp explicitly under repository
  `.work`, without an image/container, installation, sudo or policy/sysctl change.
- The original mount-namespace identity remains mandatory. An unreadable parent
  namespace across the user boundary is tolerated. Recursive bind mounts retain
  locked descendant mounts; plain root bind failed in the local user-namespace
  probe. Every inherited mount is still checked read-only; repository and exact
  scratch bindings, capability dropping and display authentication are preserved.
- Windows native reporting reads binary log bytes and keeps up to three bounded,
  filtered inner configure/probe reports when the outer error is written. A new
  per-invocation identifier excludes reports from earlier commands. Credentials,
  raw logs and arbitrary exception output are not uploaded. Added regressions
  cover nested reports, mismatched invocation, unsafe prior records and Ctrl-Z.
- Manual `android-linux-windows` scope selects exactly Android, Linux and Windows.
  Linux runs normal desktop verification. Windows runs the four targeted recovery
  methods and relevant cheap regressions before independent installer generation.
  macOS/iOS are omitted. Expected retention lanes are executed in a plan regression.

## Executed local results

Evidence root: `.work/verification/task-b-android-linux-local/`.

| Attempt | Result |
| --- | --- |
| `android-2` | 873 tests, 75 suites, zero failures/errors/skips; debug APK and lint pass (0 errors, 16 warnings). Combined release compile stopped with InvocationTargetException after a metaspace warning. |
| `android-release-3` | Release Kotlin compile, unsigned release APK and release AAB succeed in a separate fresh invocation, exit 0. No signing/distribution claim. |
| `linux-1` | 484 desktop tests plus 4 recovery tests, 60 suites, zero failures/errors/skips; actual guarded/authenticated display. |
| `linux-userns-3` | Revised guard under a real user + mount namespace: 13 recovery/native/crossfade tests, 3 suites, zero failures/errors/skips, exit 0. |
| `native-1` | Current source-pinned FFmpeg build, authored stream-copy check and resource staging succeed, exit 0. Stale prior output preserved after process-reference inspection. |
| `app-image-3` | Fresh runtime inventory and Linux app image built. Embedded legal files match generated inputs exactly. Packaged launcher passes prepare/play/pause/seekForward/rate/seekBack/stop, TLS verification and owned host shutdown; mpv 0.41.0 / FFmpeg 8.0.1, silent null output. |

Tracked before/after manifests match for Android, release, initial Linux and final
app-image gates. The user-namespace rerun is an explicitly recorded local probe,
not a hosted Podman runtime attestation. APK/AAB hashes and counted XML results
are in `receipt.json`. Ten cheap suites pass 155 tests total; workflow YAML parses.
The workflow and bootstrap suites were repeated after their final small edits.

Earlier stops remain: `android-1` lacked offline dependencies; `app-image-1`
missed the runner's required evidence environment; `app-image-2` lacked an offline
Compose JDK-probe artifact; the first user-namespace root bind failed. Online
`app-image-3` fetched the missing metadata/probe and completed. The initial nested
report test exposed filesystem timestamp freshness weakness; invocation identity
replaced that draft before the passing native build. No red was removed or renamed.

## Remaining boundaries

The actual hosted Podman/AppArmor entry and Windows toolchain remain unverified
until the combined dispatch. Windows's two forced-process recovery failures still
report SQLite error 1546 during pre-open validation; their underlying OS cause is
unknown. Its FFmpeg configure failure also needs the newly preserved inner report;
this patch does not claim a speculative production fix for either failure.

Local DEB assembly was not run: fakeroot is absent. The freshly generated app
image and actual embedded bytes were checked instead; hosted installer assembly
is a separate gate. No Android device or system image is available here. There
is no device/emulator, physical audio, installation, signing or release acceptance.

The fresh online legal inventory has 161 production components, 28 retained texts
and two missing-text issues (atomicfu and Linux mediamp); both readiness flags
remain false. The earlier offline diagnostic inventory also lacked 85 POMs and
must not be confused with this fresh online result. Native/JDK/source closure and
the remaining Task A/Task B release gates remain open.
