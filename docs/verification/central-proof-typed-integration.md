# Central listening proof: typed acquisition integration

Status: authored regression only. No live source request, native decoder run, physical
audio, signing, or distribution was performed for this note. This is not an acceptance
claim.

## Verified cause of the live failure

At clean head `e7cfb5c9ec34d9e07587e116294d1675a2454a46`, `CentralProofAuthorization.wrap`
returned an object implementing only `KmpMediaGateway`. The application composes the
proof gateway through `PolicyMediaGateway`, which captures typed capabilities with
`delegate as? AcquisitionGateway` and `delegate as? AcquisitionExecutionGateway`. Both
casts were null, so `PolicyMediaGateway.requireAcquisition()` failed with
`UNSUPPORTED_SOURCE` before the real `YouTubeKmpMediaGateway` was reached. The retained
live result `.work/verification/task-b-hosted-fixes/live-central-001/data/verification/results/online.json`
shows the approved discovery/title, `RECOVERY_REQUIRED`, and zero search/resolve/download
counts.

## Harness change

- `CentralProofGateway` now implements `KmpMediaGateway`, `AcquisitionGateway`, and
  `AcquisitionExecutionGateway`.
- Typed `acquire`, `restore`, `inspect`, `preparePlayback`, `prepareDownload`, and typed
  `download` delegate to the production gateway only after the proof guards pass.
- Legacy `firstResult`/`resolve`/`download` behavior and their existing assertions are
  retained for authored compatibility tests.
- Collection acquisition is refused explicitly; the proof still admits exactly one
  catalogue MEDIA request.

## Preserved security properties

- Only `https://www.youtube.com/@oPiiLz`; the discovery complete title is unchanged.
- The actual raw ordinal-zero first result is the only execution identity. Its author is
  verified independently through bounded oEmbed metadata before the result is accepted.
- No later result, alternate representation, second search, or second playback/download
  preparation is allowed.
- Typed request, receipt, recording, catalogue subject, binding, occurrence, and physical
  download/materialization/AssetUse identities must agree. The first durable binding and
  representation IDs are pinned and cannot change later.
- Playback plans remain anonymous standalone audio with a known positive size of at most
  67,108,864 bytes. Bare progressive playback without an owned bounded native resource is
  refused; rejected resources stay owned until closed, and a failed close is sticky.
- The download's prepared representation is pinned; the transfer result must match it.
  The plan hook still enforces the byte bound and video identity.
- Cancellation propagates. Offline restore performs zero acquisition/execution calls and
  keeps the same Room/native owner closure evidence.

## Runner migration

- Online playback identity comes from `PlaybackState.currentSelection.executionContext`
  and the canonical `PlaybackAcquisitionView`, not `AppSnapshot.playbackBindings`.
- Typed downloads are read from `AcquisitionRepositoryState.physicalDownloads`,
  `materializations`, `materializationReceipts`, and `assetUses`. Typed jobs are absent
  from the legacy `AppSnapshot.downloads` projection, so the old await could never observe
  them.
- Position/checkpoint evidence uses the canonical `ExecutionQueue` and
  `OccurrenceCheckpoint`. `catalogProgress` is episode-only and is not used for the MUSIC
  proof item.
- The online marker schema is 2 and stores the typed request/receipt/binding/recording/
  subject/occurrence/download/asset IDs plus the discovery and actual raw-first video IDs.
- The offline phase validates the same canonical graph, independently re-hashes and
  natively validates the materialized file, restores paused without autoplay, plays the
  exact occurrence locally, and requires all typed/legacy acquisition counters to be zero.

## Focused authored evidence

Attempt directory:
`.work/verification/task-a-ui-recovery-integration/attempts/typed-proof-20261109-i/`

- `CentralListeningProofTest`: 25 tests, 0 failures, 0 errors, 0 skipped.
- `CentralListeningProofTypedTest`: 12 tests, 0 failures, 0 errors, 0 skipped.
- Gradle test task exit: 0 (`exit.txt`).

A final failure-detail split (`UNBOUNDED_NATIVE_PLAYBACK_REFUSED` for a missing/mismatched
owned resource) and its authored assertion were made after attempt `i` and are awaiting the
lead's combined focused gate.

The runner's outer input-equality post-check reported a mismatch because unrelated
tracked files were edited concurrently in the same shared worktree during the run. Those
edits are outside this task's ownership and were not modified or reverted by it. The
compiled proof suites themselves passed.

## Limits

- No live online/offline source attempt was made; the lead owns the source/security
  review and live-run decision.
- No physical audible output, network-namespace denial, packaging, signing, or
  distribution was verified here.
- The typed wrapper cannot attest that the production source will choose a SABR
  representation; a bare progressive selection is deliberately refused rather than
  weakened.
