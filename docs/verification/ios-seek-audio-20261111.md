# iPhone seek and silent-tail investigation — 2026-11-11

## Scope and current artifact

Work continued directly in the existing dirty checkout. No additional worker,
checkout, commit, push, dependency-pin change or owner-data reset occurred.
The current local candidate is **v14**, development-signed and installed in place
as `dev.veyra.app` on the owner's iPhone. It preserves the redesign, previous
download fixes, preparation optimizations and D013 source-declared original-audio
selection; no language is hardcoded.

Ignored evidence root: `.work/evidence/ios-seek-audio-20261111/`.
Final source manifest, signed-artifact receipt, installation receipt and build
logs are in its `final-v14/` directory. All **798 source inputs** match after
installation; all **152 library Kotlin files** match `.work/kmpYT` byte-for-byte.
This is a local Debug repair, not a store/release candidate.

## Confirmed silent-tail cause

The selected retained fragmented AAC was playable, but AVFoundation reported an
asset/track duration of **375.3723356 seconds**. AudioToolbox measured
**8,275,904 decoded frames at 44,100 Hz = 187.662222 seconds**. The UI consequently
offered approximately twice the actual audio timeline.

Compressed-sample inspection found real media through approximately 187.71
seconds, followed by an empty zero-byte marker near 375.42 seconds. A diagnostic
header experiment on a private copy corrected AVFoundation's duration; **no
production header rewriting or modification of the owner's retained file was
introduced**.

A separate physical test host used `MTAudioProcessingTap` to count decoded frames,
nonzero samples and processing errors on a private copy of that same file. It
recorded no PCM buffers or microphone input. Before bounding the timeline, seeks
to 60% and 80% landed at approximately 225 and 300 seconds: native time advanced
but decoded nonzero samples were **zero**, including after pause/play. This is
not merely an incorrect Play/Pause label or a muted gain.

After bounding duration, the same fractions landed inside real media. All 12
measured windows contained nonzero PCM with an advancing clock. The final
lifecycle probe also passed nine windows spanning seek-before-first-play,
end/pause/back/explicit-play and a later forward seek. It observed nonzero PCM,
no tap errors, unmuted gain 0.667 and no native waiting reason. Automatic stall
waiting was not changed.

Evidence: `local-timing-metadata.log`, `local-tail-timing.log`,
`local-header-controls.log`, `local-pcm-before-console.log`,
`local-pcm-bounded-console.log`, and `final-v14/pcm-lifecycle-console.log`.
Owner recording names and file identities stay in ignored evidence only.

## Production repair

- `IosAudioEngine` takes the minimum of positive AVFoundation and actual
  source/validated-materialization durations, with null fallbacks. It uses that
  bound for native seeking and sets `AVPlayerItem.forwardPlaybackEndTime`.
  Catalogue/publisher duration is not substituted for measured audio identity.
- After a successful current seek, `PlaybackController` reasserts desired Play
  behind the native-operation queue. A delayed `isPlaying` observation must not
  suppress this command. Prepared/current generation, confirmed seek sequence,
  no newer pending seek, terminal/end bounds and current desired intent remain
  mandatory. Pausing during the seek never gets overridden.
- kmpYT playback resources expose terminal streaming failure dynamically through
  resource ownership wrappers. Veyra maps bounded stable codes to typed failures;
  iOS samples this health before Play/Seek and during native observation, pauses
  a failed deck and emits one Error. Native decoder failures prefer a known
  source failure over a misleading unsupported-media classification.
- Protection failures map to `PROVIDER_ACCESS_REQUIRED`; no protection bypass,
  invented tokens, recording replacement or automatic switch to a dub is added.
  Unknown details are normalized rather than exposing URLs/provider text.

No schema change, transcoding, source-identity rewrite or buffer-policy change
is included. The five library paths changed for health propagation are mirrored
into the authorized upstream working checkout.

## Separate online failure and limits

The exact earlier example `1WdCxUiIC7I` selected the declared Arabic original
(`ar.4`), not the English auto-dub (`en-US.10`). The diagnostic online host then
observed terminal `SABR_PROTECTION_RETRY_LIMIT` after receiving
1,133,693 of 23,626,255 bytes. Seeking outside received media left AVPlayer's
clock moving without audio before failure propagation was added.

The repair makes terminal acquisition failure visible and stops fake continued
playback; **it does not repair that provider refusal or establish successful
fresh-online seeking in Veyra for this recording**. Existing cold-preparation
latency limits, full-redesign acceptance and other platform/release gates remain.

## Executed regression evidence

- New native terminal-source-failure test failed before the health repair and
  passed after. New measured-duration test failed before bounding (8,000 versus
  expected 4,000 ms) and passed after. Final **13/13 native iOS Simulator engine
  tests pass**, including end/back playback, running seeks and crossfade gain.
- Initial shared restore tests reproduced missing resume. The additional stale
  playing-state ordering test failed before removing the redundant `isPlaying`
  guard, then passed. Final **104/104 shared JVM tests pass**, including source
  purpose/original selection, queue playback, podcasts, skips and crossfade.
- **18/18 focused library JVM tests pass** for resource ownership/late failure and
  SABR streaming sessions. Counts are parsed from XML in
  `final-v14/final-results.json`, not inferred from authored-test counts.
- Android Kotlin compilation and the complete iPhone build pass. Existing
  compilation warnings remain; no whole-project/all-platform release suite ran.
- The final installed-app **physical UI test passes** in 44.049 seconds: measured
  duration 3:07, middle seek to approximately 1:55, backward to 0:47, forward to
  2:10 with continued advancement, paused seek staying at 0:46, and end/back with
  explicit Play returning to an advancing native Playing state. Playback is
  paused at test completion.

Physical UI log:
`.work/evidence/redesign/iphone-probe-seek-audio-final-v14-20261111-test.log`.
The UI runner installs only its own runner and tests the already installed app;
it does not reinstall an older Veyra binary.

Earlier v12/v13 UI failures remain in evidence and are not relabeled successes.
The earlier test required automatic resume after reaching the real endpoint.
Its gesture can land slightly before the endpoint and reach natural EOF, which
clears play intent. The final test separates paused/playing seeks from natural
end and explicit resume. It **does not prove automatic resume after genuine
natural EOF**, nor use elapsed time alone as speaker-audibility evidence.

## Reproduction and cleanup

Use `scripts/dev.sh` with the existing JDK, repository-owned homes/temp/cache and
offline Gradle, one worker, bounded heap and in-process Kotlin compilation.
`final-v14/` retains the failed-before test log, final shared/library/native logs,
source binding, mirror parity and signing/installation evidence. The final
native run uses only the task-owned simulator set `.work/ios-unit-simulators`.

Physical decoded-sample evidence is from a separate test host on a private copy,
not an audio tap inside Veyra or microphone proof of speaker audibility. After
verification the task-owned test host was uninstalled, four private-copy/test-app
locations were removed and zero `.m4a` copies remained under the diagnostic probe.
Original retained files, app data, code, scalar logs and receipts remain. Only
the task-owned simulator was shut down. Veyra was reopened with playback left
paused by the passing device test. Cleanup, uninstall and launch receipts are
in `final-v14/`. No broad cleanup or distribution is implied.
