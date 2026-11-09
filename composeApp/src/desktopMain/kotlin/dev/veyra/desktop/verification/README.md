# Central listening verification

`CentralListeningProof` is an **opt-in, headless application integration runner**,
not a replacement audio engine, downloader, catalogue, or interactive UI test.
It uses the supplied production `AppContainer`, normalized Room store, kmpYT
gateway, download coordinator, native validator, and the existing libmpv engine.
Play/Download/Seek are normal application actions; no second playback engine or
second consumer of native events is created. The production validator legitimately
owns its separate silent validation handle.

**Current evidence:** production and test sources compiled in the second guarded
desktop batch on 2026-11-06. Independently inspected XML shows **25/25 proof
policy/file/lifetime tests, 19/19 AppContainer integration tests, and 8/8 desktop
CLI/result tests passed**, with no errors/skips. Current proof/AppContainer/CLI
source hashes match that batch's frozen inputs. The whole batch failed: 249 tests,
240 passed and nine GUI failures outside these suites. Evidence is archived under
`.work/feasibility/desktop-second-owned-results/`; its input-hash audit is
`.work/feasibility/desktop-second-owned-observation.json`.

**The real two-process journey has not run.** These fixtures do not make live
source requests or establish actual source audio, native network denial, or
physical sound. Do not execute the online phase until the lead has separately
authorized the live run, native readiness is verified, and the serialized
execution slot is available.

## Authorization and bounds

- The pinned `third_party/kmpYT/ci/approved-channel.json` authorizes only
  `https://www.youtube.com/@oPiiLz`, at most two distinct video candidates, and
  67,108,864 media bytes per acquisition. Tests compare these pinned constants.
- Discover one video from that channel's `/videos` metadata. Verify its exact
  author URL independently through bounded public YouTube oEmbed metadata and
  retain the complete title (up to 16,384 valid UTF-16 units; never truncate).
- The wrapper observes the **actual first raw result of the application's one
  full-title search**, not a preparatory search. Verify that video's author even
  when its identity differs from the discovered item. Preserve the exact first
  receipt. Unsupported, unavailable, unauthorized, malformed, or absent first
  results stop the attempt; there is no later-result selection or alternate source.
- All source execution must retain that immutable binding. No cookies,
  credentials, private headers, RSS audio, Commons audio, or authorization bypass.
- Before media transfer, the desktop execution hooks reject any plan lacking a
  known positive standalone-audio size of at most 64 MiB. Segmented/muxed/paired
  audio and arbitrary headers/checkpoints are refused for this restricted proof.
  kmpYT's transfer boundary enforces the declared media length before writing;
  the wrapper additionally checks progress and final bytes. Native SABR has a
  64 MiB cache bound installed **before** opening its resource.
- Bare progressive **playback** is refused: its current native-URL handoff has no
  pre-transfer byte-size hook. The proof never chooses another representation to
  evade this restriction. An upstream-supplied fallback within a SABR request must
  preserve its selected format/video and independently pass the same byte bound.
- These are per-acquisition media bounds, **not cumulative ingress accounting**
  for protocol envelopes, headers, retries, or simultaneous cache/file copies.
  Reports explicitly set `totalNetworkIngressBoundClaimed=false`. Public URL/DNS
  checks are not atomic DNS pinning.

## Phases

### Online

Requires an explicitly selected dedicated data directory with an empty library,
no existing media bytes, and no existing online marker. Observes native playback
advancing for over two seconds, a confirmed held pause, resumed advancement,
forward and backward native-confirmed seeks followed by advancement, and another
held pause. Duration must be at least 12 seconds. TLS verification must remain on.

Download through the normal action using the same bound recording; require the
coherent session-validated `COMPLETE` projection, native validation, size/SHA-256,
and representation provenance. Persist position and offline mode, then atomically
write the bounded private marker
`<data-dir>/verification/central-listening-online.json`.

### Restarted offline (Linux only)

The **online process must actually terminate**. Start a new process against the
same directory in an externally created private network namespace. No namespace,
firewall, route, or system-configuration change is performed by the Kotlin runner.
It checks a namespace distinct from the online process, only loopback
interfaces/routes, the namespace of every currently observed JVM/native thread,
and failure of a literal public TCP connect. It repeats topology/connection checks
around native playback; application-level offline flags alone do not pass.

Room identity, immutable binding, file SHA/size/representation, queue occurrence,
and position must match the marker. Restore paused without autoplay; wait for
this process's real validation, then use the normal queue action to play and seek
the local file. The guarded gateway must observe **zero** searches, resolutions,
or downloads. Windows/macOS OS-denial verification is not implemented here.

## Pre-launch gates (read-only review is not runtime preparation)

1. Obtain the lead's execution slot and explicit approval for this single attempt.
   The only approved channel is `@oPiiLz`; the discovered item and actual raw first
   result are the only possible two video candidates. A source refusal ends the
   attempt. Do not prepare a different query, later result, fresh data directory,
   or retry loop to evade that refusal.
2. Use a matching-host **prepared Veyra executable**, with the reviewed native
   dependency inventory and exact source revision/pin. A plain `desktopJar` or a
   passing native fixture suite does not establish a runnable distribution. The
   JAR containing libmpv must remain intact for its whole-archive hash check.
   Packaged FFmpeg and its SHA sidecar must exist at
   `dev/veyra/desktop/native/<platform>/ffmpeg[.exe]` and `.sha256`, alongside the
   matching pinned Node/Chromium manifests and verified CA bundle.
3. Prepare/check the actual app-owned extraction helpers through the separately
   scheduled production runtime gate, in the **same dedicated data directory**.
   The current explicit entry point is Settings → prepare media tools; there is
   no reviewed headless preparation-only CLI. Its separate UI/runtime invocation
   must be scheduled and verified, not invented as an extra proof option.
   Never substitute installed host Node, Chromium, FFmpeg, or mpv. Construction
   does not prepare helpers; the first media resolution otherwise tries to do
   so *after* discovery/search. Helper archive transfers are separate from the
   64 MiB media bound and are not authorized by this read-only runbook review.
   Missing packaged FFmpeg fails before helper download. Do not spend a live
   candidate merely to discover missing runtime resources.
4. Keep the dedicated Room library and media directory empty for the online
   phase; helper/runtime files alone are permitted. No prior online marker is
   allowed. Keep all paths below the repository's ignored `.work/`, reject
   symbolic path ancestors, and preserve failed-attempt evidence rather than
   resetting/reusing its directory without a separately approved rerun.
5. On Linux, use the reviewed mount guard for **both phases**. For offline,
   create the private network namespace **before** the mount guard drops
   capabilities. Do not reuse a Gradle daemon, IDE, existing app process, or media
   helper; they cannot demonstrate that the actual app/native threads started in
   the denying namespace. No GUI/display server is needed by these headless
   commands. Missing namespace permissions are a failed/unexecuted gate.

At the 2026-11-06 second-batch audit, only a plain desktop JAR was present and the
generated `.work/desktop-native/resources/` tree was absent. That snapshot was
**not live-ready**; a later separately verified build/preparation must supersede
it. Reading these instructions, compiling the tests, or inspecting a manifest
does not perform that preparation.

## Linux invocation contract (not an executed result)

Run from the repository root. `$VEYRA_DESKTOP` denotes the lead-verified absolute
path to the prepared Veyra executable, not an invented path, Gradle command, or
host media helper. The example data directory must be assigned to this attempt
and satisfy the gates above. Record the executable/input hashes before launching.

```sh
scripts/dev.sh scripts/linux-namespace.sh "$VEYRA_DESKTOP" \
  --central-proof online --silent-audio \
  --data-dir "$PWD/.work/central-listening-proof" \
  --verification-result "$PWD/.work/central-listening-proof/verification/results/online.json"
online_status=$?
printf 'Online process exit: %s\n' "$online_status"
```

The foreground app must actually exit **0**; save that status immediately and
inspect the post-cleanup report. Do not proceed on a marker alone, a candidate
success, a timeout/killed process, or stale result files. Before the offline
command, verify `success=true`, `ownersClosedByHost=true`, the expected online
phase/channel/bounds, one approved full-title search and one download/plan, zero
pending/in-flight rejected resources, and no sticky resource-close failure.
Correlate the online report's process, binding ID, discovery title/ID and native
observations with the new private online marker.

Only after those checks, and with already available namespace permissions, start
the **same executable**, without rebuilding or changing its inputs:

```sh
scripts/dev.sh unshare --net scripts/linux-namespace.sh "$VEYRA_DESKTOP" \
  --central-proof offline --silent-audio \
  --data-dir "$PWD/.work/central-listening-proof" \
  --verification-result "$PWD/.work/central-listening-proof/verification/results/offline.json"
offline_status=$?
printf 'Offline process exit: %s\n' "$offline_status"
```

Require offline exit **0**, `success=true`, `ownersClosedByHost=true`,
`newProcessOfflineStageVerified=true`, and all three gateway call counters zero.
The reported terminated online process must match the successful online report
and marker; the offline process and network namespace must be different. Check
the namespace-denial/native-observation fields and expected milestones, not just
the word `success`. Both reports must still say `phaseOnly=true`,
`physicalAudioVerified=false`, and `totalNetworkIngressBoundClaimed=false`.

The mount guard confines ordinary scratch writes and drops capabilities. No
sudo, package installation, sysctl/firewall change, proxy, credentials, alternate
channel, uncontained fallback, or automatic retry is permitted. Report the
sanitized failed stage/code and retain the owned evidence. A refused first result
or missing runtime does not authorize a replacement acquisition.

`--silent-audio` intentionally selects a null sink; omit it only on a verified
real output host. **Neither choice verifies physical audible output.** Both
reports always state `physicalAudioVerified=false`; physical listening/crossfade
and each other target remain separate required acceptance evidence.

## Evidence and ownership

- Main accepts result files only directly inside
  `<data-dir>/verification/results/`, with a `.json` suffix. Dedicated result
  files may be atomically replaced; the online marker is reserved and never
  overwritten. Symbolic path ancestors and oversized marker reads are rejected.
- Reports contain public discovery/first-result metadata, native observations,
  process identity and sanitized failure codes, not execution URLs, credentials,
  raw exceptions, native transcripts, or audio bytes. Keep all data/results under
  ignored project storage; never publish downloaded media.
- A returned success JSON is a **candidate phase result**. Main publishes success
  only after the app/platform/proof owners close and the requested result file is
  written. Rejected gateway resources remain proof-owned until drained; a failed
  close remains a sticky failure even after a later successful drain.
- A marker alone is not a passing online result: it precedes final teardown.
  Accept the journey only from **both successful exit statuses and post-cleanup
  reports**, correlated with the marker's process/binding. An offline phase does
  not retroactively repair an online failure. `phaseOnly=true` is not a release.
- Linux checks observe the current private namespace and threads, not immunity
  to future privileged reconfiguration, malicious inherited descriptors, or all
  non-IP IPC. Private-directory/atomic-marker checks assume the owned process
  lock; they are not a sandbox against a privileged concurrent filesystem attacker.

Unit tests: `dev.veyra.desktop.verification.CentralListeningProofTest`. Schedule
them through the lead with the normal project Gradle wrapper; inspect actual XML,
not the authored count. Existing AppContainer/Room fixture passes likewise do not
prove this live journey, rendered UI, physical sound, or process-death recovery.

The second desktop batch passed25 proof-policy fixtures, not these live commands.
Later Main startup admission and platform/session ownership repairs are
source-reviewed but **uncompiled/unrun**. Their authored cancellation/partial
initialization/close tests must join the next scheduled desktop batch; historical
second-batch XML does not verify the latest host teardown implementation.
