# Online preparation and source-original audio — 2026-11-11

## Scope and changes

The owner's clarification is **the video's original language**, not Arabic, the
device locale or the app language. Work continues directly in the dirty checkout;
no new worker, checkout, pin change, commit, push or owner-data reset occurred.
The full redesign and five-platform acceptance scope remains open.

- `YouTubeProvider.prepare` builds the bounded response graph once and reuses it
  before/after URL transformations. Ordinary responses avoid a second traversal;
  encoded nested JSON still expands. Authorization, diagnostics, limits and
  original provenance remain enforced. No watch-response/media-URL cache is added.
- The disposable iOS SABR playback cache no longer requests `fsync` after every
  write. Verified coverage, storage errors, bounds, private paths, cancellation and
  deletion remain unchanged. Retained SABR downloads **still use durable writes**.
  A narrow injected synchronization function enables real native-file tests; the
  production default remains the real syscall.
- Automatic selection prefers source-declared originals, regardless of language.
  New app tests cover English, French, Spanish, Japanese, Hindi and Arabic, both
  purposes and iOS/Android/JVM, plus absent optional language-code metadata.
  Explicit selectors and renewal identity remain intact. An incompatible known
  original is not silently replaced by a dub. Unknown provenance retains the
  existing default fallback rather than inferring originality from a locale.
- All **152 library Kotlin files** match `.work/kmpYT` byte-for-byte.

## Physical online measurements

The exact example is `1WdCxUiIC7I`. It is already downloaded in Veyra, so timing
ordinary playback could falsely measure its local file. A separate ignored
`dev.veyra.onlineprobe` host uses the current library, source-authorized SABR and
AVPlayer without accessing Veyra's database or media. It selects a declared
compatible original, not a fixed language, and verifies native readiness plus at
least one second of advancing playback while muted. No retained file was deleted.

All four measured runs succeeded with `original=true`, `fixedLanguage=false`.
Each process fetched a player program on its first run; its second reused that
program but performed a fresh source inspection.

| Test-host configuration | Run | Inspection | Resource open | Native ready | Total through ≥1 s playback |
|---|---|---:|---:|---:|---:|
| Response reuse; ephemeral fsync enabled | Cold | 5.132 s | 0.017 s | 5.477 s | 11.842 s |
| Same process/program | Second | 3.432 s | 0.016 s | 4.046 s | 8.711 s |
| Response reuse; ephemeral fsync disabled | Cold | 6.156 s | 0.016 s | 3.953 s | 11.341 s |
| Same process/program | Second | 3.228 s | 0.016 s | 7.990 s | 12.452 s |

The controls already contain response reuse: they do **not** isolate its speedup
against v9. Removing unnecessary synchronization is proven by the native
syscall-count test, but these live runs establish **no repeatable overall speedup**;
the last native-readiness sample was slower. No speedup percentage or instant-start
claim is justified. Previous v9 samples remain historical, not controlled baselines.
SABR still validates framing/protection before exposing coverage. This work does
not bypass that boundary, invent protocol requests or substitute a recording.
Native network/media readiness remains a separate residual cost. Muted decoding
and progress are not a human audible-language confirmation.

## Executed checks

- **108 focused library JVM tests pass**: prepared parsing, extraction, original
  metadata/selection, compiler preparation, streaming and HTTP transfer.
- **47 native iOS Simulator tests pass**: parsing, provenance, selection and
  real-file cache/durability. The ephemeral-cache test failed before the flag fix
  (`Expected 0, actual 2` synchronizations), then passed; retained-download sync
  and sync-error propagation still pass.
- **80 focused shared-app tests pass**, including all nine original-selection
  tests and the Room recovery class. The broad run passed 1,253/1,254 tests; one
  rollback/reopen Room test hit its 15-second setup deadline. The whole affected
  class passes on the focused rerun. The initial failure is preserved.
- Android application Kotlin compilation and the full iPhone app build pass.
  Both signed separate-host online runs complete with exit code zero.
- Initial standalone packaging had a Swift self-module error; the first host
  launch trapped before inspection because scene lifecycle was missing. Both
  test-host issues were corrected. These were not Veyra app crashes.

## Final owner application

The final v10 local Debug application is development-signed and **installed in
place as `dev.veyra.app`** on the owner's iPhone. All 798 inventoried source inputs
match, the signed files still match their receipt, and the linked debug library
contains the new prepared-parser symbol. Its signed SHA-256 is
`07e49b85a983a56af5f8fecf69d17ca2a14b328a02e42168c05e9f5abb571e19`.

Both physical final checks pass: the exact retained example plays after a cold
app restart with the app's offline mode enabled (tap-to-Playing **2.759 s**), and
the previously repaired retained download remains Downloaded. The two tests took
26.479 seconds, with zero failures. The original online setting was restored and
playback paused. App offline mode is not an OS-level native network-denial test.
These are local-file regressions, not fresh online timing inside the owner app.
Online timings above belong specifically to the independent source/library host.

The separate test host was removed, only the task-owned simulator was shut down,
and normal Veyra was launched successfully. No owner app or media was deleted.
`final-device-verification.json`, `final-iphone-install.json`,
`repair-iphone-signature.json` and `final-linked-markers.json` bind the outcome.
The physical test log is
`.work/evidence/redesign/iphone-probe-online-preparation-final-v10-20261111-test.log`.

## Evidence and limits

Ignored evidence: `.work/evidence/online-preparation-20261111/`.
Console/signature/install receipts bind the online hosts; native before/after
logs preserve the syscall regression. `app-full-results/` preserves the timeout;
`app-focused-completed-results/` and `library-final-focused-results/` contain the
accepted focused XML. `app-focused-results/` is a stale snapshot taken before the
rerun completed, **not** accepted rerun evidence.
`source-inputs-before-final-build.json` inventories **798 source inputs**;
`mirror-parity.json` records parity. The `v9-*` receipts preserve the prior accepted
source, signature and device-verification baseline.

No live signed URL, source response, audio file or private capture is included in
this tracked report. Existing download/offline results, the separate immutable
first-result issue and broader release gates retain their previous limits.
