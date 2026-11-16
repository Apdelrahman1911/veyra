# Android startup, playback and download repairs — 2026-11-10

Local changes on base `ef31659eb2600ed5f2f8c4b0dc2446555a798018` in
`/Users/abdelrahman/Projects/veyra`. No commit, push or release was performed.
The owner requested direct Codex work; the initial delegated investigation was
cancelled before implementation.

## Corrections

- **Startup:** escaped the closing brace in the placeholder regular expression.
  Android's ICU rejects the original pattern during `AppStrings` initialization.
  The common parser test and physical Android regression cover this path.
- **Play after search:** reproduced `IllegalArgumentException: isLoading only
  allowed when not in STATE_IDLE or STATE_ENDED` on the phone. The Media3 session
  now publishes loading only when its projected state is BUFFERING. Resolution
  before a current item exists remains a valid IDLE projection. The regression
  exercises every playback phase before queue admission, during item resolution,
  and with a selected item; existing session transport dispatch is also checked.
- **Download progress:** captured an argument failure in
  `PhysicalDownloadCommands.applyPhysicalHeartbeat`. kmpYT may discard an
  unverifiable partial prefix, and final materialized output may be smaller than
  transferred input. Both previously violated an unconditional increasing-count
  requirement. An explicit progress-reset command now permits these cases only
  for the same current owner, attempt and selected representation in RUNNING.
  Default lease heartbeats remain monotonic; stale owners, negative or impossible
  counts, and maintenance-counter changes still fail. Hash, native validation,
  publication and immutable-producer checks are unchanged. No schema change.
- **Expired-link copy:** the exact `YOUTUBE_SOURCE_EXPIRED` detail now selects an
  English/Arabic media-link message. It no longer instructs users to restore a
  database backup. Other recovery failures retain their existing behavior; raw
  failure details are never displayed.

## Automated verification

| Check | Actual result |
| --- | --- |
| New Room restart/output-size cases before repair | 2 executed, 2 failed with the original counter rejection |
| New Room restart/output-size cases after repair | 2 passed |
| PhysicalDownloadCommandTest | 11 passed |
| DownloadPhysicalRuntimeTest | 32 passed |
| DownloadPhysicalCoordinatorRoomTest | 9 passed |
| DownloadPhysicalIntegrityTest | 12 passed |
| AppStringsTest, including bilingual/error-detail checks | 17 passed |
| QueueSessionPlayerTest on physical Android | 2 passed |
| AppStringsAndroidTest on physical Android | 1 passed |

Total: **86 passing regression tests**, zero failures/errors/skips in those
final selected runs. Room/runtime tests use authored non-audio bytes and fake
source/native seams; they are not live-download or audible-output evidence.
Both Android APKs assembled successfully. `git diff --check` passed.

## Physical phone and subsequent download repair

Test device: Huawei AQM-LX1, Android 10. APK updates used `adb install -r`;
application data was preserved. These Android crash repairs first established a
successful cold launch and fresh online playback advancing to 54 seconds.

At that stage the original transfer still returned 403 for initial and refreshed
links. That finding is superseded by the subsequent source-availability repair:
the original download now completes, saves 3,725,310 bytes and plays in Veyra's
offline mode. The new kmpYT patch also adds audio-only inspection and transformation
reuse. The final APK includes both sets of repairs and again passes all three
physical device regressions. See
[kmpYT media/startup repairs](kmpyt-media-startup-repairs-20261110.md) for the cause,
full suite results, timing, installed artifact hash and upstream delivery limits.

The earlier stage's APK hash was
`ad165c8abe0268e685802231eebc8c78c568e0c8f351ba86fac9408a2d58d6d4`;
it is retained only as historical evidence. Temporary diagnostic source/classes
are absent from the final APKs. kmpYT now has an uncommitted local patch; its
original pin is unchanged. No upstream push or release was performed.

Ignored local evidence is under `.work/evidence/playback-download-fix/` and
`.work/evidence/media-transfer-startup/`, including crash capture, red/green
regressions, live download/playback observations, final instrumentation output,
screenshots and artifact identities. Private captures/data remain local and
uncommitted. These are Android repair results, not five-platform production
approval.
