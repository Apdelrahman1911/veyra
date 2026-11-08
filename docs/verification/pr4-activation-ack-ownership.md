# Occurrence activation preserves staged native ownership until acknowledgement (Task A / R13)

Status: **implementation and playback regressions complete; Android shared-production compilation
is unverified.** This is a narrow typed transaction/native-owner regression, not physical
crossfade, real audio, native-platform, Room/SQLite or release verification. Parent PR #4 stays
draft and Task B stays gated.

## Provenance

| Item | Value |
|---|---|
| Parent draft PR | https://github.com/Apdelrahman1911/veyra/pull/4 |
| Parent branch | `t3/typed-room-download-recovery` at `6d09ed5fa24f39e97a6d7b9689e9e30a008e1857` |
| Integration base | `feat/veyra-integration` at `9b1b0479a2748012988d378d1f631cf5a50fff79` |
| This branch | `t3/pr4-activation-ack-ownership` |
| Implementation commit | `ab686ef0e9e64fd8dad3eccc2adbbe1c0b0cd0ec` |
| kmpYT pin | `c0a08090fe7dbd76e20216559b8184ad2cccde63` (indexed gitlink, `gradle/kmpyt.commit`, actual clean checkout) |
| Schema 3 export | `aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367`, 92 entities |
| Input packet | `/root/projects/Veyra/.work/delegation/resume-20261108/inputs.json`, `head=6d09ed5…`, 508 regular files + gitlink |
| Accepted agent configuration | provider `opencode`, model `opencode-go/deepseek-v4.1-flash`, `variant=max`; recorded, no independent runtime/backend/variant attestation |

The packet was rehashed against the parent before edits: 508/508 regular files matched and all
gitlink/pin/schema checks passed. At the implementation commit, 506/508 parent inputs remain
byte-identical; the only changes are the expressly allowed controller and its test class.

## Finding and correction

At the parent, `PlaybackController.AcquisitionRuntime.playOccurrence` called
`cancelNext(notifyEngine = false)` **before** the real `AcquisitionPlaybackCommand.Activate`
transaction. `cancelNext` cancels the in-flight neighbour resolver and clears the controller's
stage/crossfade reference while deliberately leaving the native stage owned by the engine. The
replacement native stop is only queued by `start()` after a positive ACK. A rejected final queue
CAS or acknowledgement could therefore leave the engine owning a staged deck that the controller
no longer referenced, while an already-consumed neighbour selection was cancelled.

The correction removes only that premature retirement from `playOccurrence`. The existing
`start()` ordering already performs `cancelNext(notifyEngine = false)` and then queues
`sendEngine(releaseBarrier = true) { stopNative(token) }` **after** the activation commit. No
duplicate stop, new native policy, schema, dependency or build setting was added. The seven
original admission-CAS methods are unchanged.

## Regression matrix

All tests use the existing real `PlaybackController`, canonical projection commands and
same-owner repository decorator. Sources/native are authored non-audio fakes; no filesystem,
network, Room, SQLite or native execution occurs.

| R13 item | Method | Parent result | Fixed result |
|---|---|---|---|
| 1. Handed stage, final Activate queue CAS rejected | `rejectedActivationAfterThePreliminaryReadRetainsTheHandedStageAndOutgoingOwner` | FAILED: stage was `null` | PASS |
| 2. ACK failure + queued `StageReady` | `rejectedActivationAcknowledgementRetainsTheOldStageAndQueuesItsReadyEvent` | FAILED: stage already `null` | PASS |
| 3. In-flight neighbour after claim | `rejectedActivationDoesNotCancelOrRepeatTheInFlightNeighbourSelection` | FAILED: `sourceCancelled=true` | PASS |
| 4. Successful activation ordering | `successfulActivationRetiresTheOldStageOnlyAfterTheCommittedAcknowledgement` | FAILED: stage cleared while Activate suspended | PASS |
| 5. Original admission CAS methods unchanged | 7 existing `AcquisitionAdmissionStageCasTest` methods | present | PASS, unchanged |

The fixed tests verify: precision `INVALID_STATE` / `playback_queue_action_stale` after only the
inactive target queue drifts; the identical post-rejection graph object, active MUSIC
cursor/binding/generation/playing state, handed stage/native load/resource and dirty `86_125`
sample retained; `STORAGE_IO` / `authored_activation_ack_rejected` with the proposed outgoing
checkpoint visible only in the unreleased proposal while the original graph stays visible, and a
queued `StageReady` moving the retained old stage to `READY` after the actor resumes; an in-flight
selection left `CLAIMED` and uncancelled, then committed exactly once into the originally reserved
preload after release; and successful ordering where the target cursor/binding checkpoint is
committed before controller processing while the old stage is still tracked, followed by exactly
one old-native close and one target prepare with no duplicate or resurrected stage.

## Executed evidence

Every Gradle run held the required global `flock`, used `scripts/gradle.sh --offline`, the single
literal
`-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8`,
and one ignored bounded Test fork (384 MiB heap / 256 MiB metaspace / SerialGC / 2 CPUs). Evidence
is in `.work/verification/pr4-activation-ack-ownership/attempts/`.

| Attempt | Command | Exit | Actual outcome |
|---|---|---:|---|
| red-1 | focused 4 activation methods | 1 | 4 executed/4 failed, but two stopped in fixture setup (`PODCAST` absent before initialization); preserved separately, **not** used as product red |
| red-2 | focused 4 activation methods | 1 | **4 executed/4 failed** on parent production: three `CrossfadeState` expected vs `null`, one `sourceCancelled` expected false; XML SHA-256 `0913fa80…` |
| green-focused | focused 4 activation methods | 0 | **4/4 pass**, 0 failures/errors/skips |
| green-suite | `:shared:jvmTest --tests 'dev.veyra.playback.*' --tests dev.veyra.data.room.PlaybackAcquisitionCommandTest` | 0 | **142/142 pass**, 0 failures/errors/skips; 13 command methods, all 11 admission methods (7 original + 4 new), 118 other playback methods |
| compile | `:shared:compileKotlinJvm :shared:compileTestKotlinJvm --rerun-tasks` | 0 | both tasks actually EXECUTED (not UP-TO-DATE); clean drain |
| android-1 | `:shared:compileDebugKotlinAndroid` | 1 | offline cache missing `ktor-client-android-jvm-3.2.3.jar` and `kotlinx-coroutines-android-1.10.2.jar`; preserved |
| android-2 | `:shared:compileDebugKotlinAndroid` | 1 | offline cache missing `room-runtime-android-2.8.5.aar`, `sqlite-framework-android-2.6.2.aar`, `sqlite-bundled-android-2.6.2.aar`, `sqlite-android-2.6.2.aar`; before/after inputs identical and drain clean; no retry |

All green attempts recorded the same owned input freeze (`9cd714e88d4f5de64d405059fabf390fc9aec5f83599133f09c9c256d18b382e`,
508 inputs), pin and schema hash. The later compile and Android attempts use the strict drain
schema (`clean`, `match_count: 0`, `matches: []`). The two early green drain records predate the
`clean` field; they contain `checked_utc`, the exact root, `match_count: 0` and `matches: []`, and
are cited as positive recorded zero-match checks without being rewritten. The old red attempts
lack captured argv/drain from the pre-repair harness; they remain preserved as raw XML/log
evidence, not repaired-harness acceptance. The lead's synthetic parser diagnostic is rejected by
the strict parser as missing `drain.json`.

## Limits and unexecuted checks

- Pure canonical graph/projection persistence, not Room/SQLite; authored remote/non-audio sources
  and `PlaybackTestEngine` native calls, not audio or a native platform.
- `:shared:compileDebugKotlinAndroid` remains unverified because the offline dependency cache
  lacks the exact Android AARs listed above. No online resolution or broader cache substitution
  was performed.
- No physical-device crossfade, streaming/offline source authentication, live source call,
  emulator/simulator/native execution, signing, packaging, Actions dispatch or release upload.
- No independent runtime/backend/variant attestation of the recorded OpenCode model configuration.
- Parent PR #4 and this scoped PR remain draft; Task A is not declared complete or release-ready.
