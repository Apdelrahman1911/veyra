# Current source-fix installation on iPhone — 2026-11-11

The current development-signed `iphone-search-control-20261111` build was installed
in place on the paired physical iPhone. CoreDevice installation reports success in
`.work/evidence/redesign/iphone-source-acceptance-20261111-install.json`.
No uninstall or application-data reset was performed. Existing library and selected
recording were observed after launch.

Before installation, all three signed executable hashes, the processed Info.plist
hash and the deep/strict code signature matched the existing artifact receipt.
All 24 saved source-file hashes still match that build. Signed debug-library SHA256:
`8f40e67d4436e2b098cde5af3ef3677be53f029740dca7a4afbc22a979abfadb`.
The latest summary receipt is `iphone-source-acceptance-20261111-summary.json`.

## Actual device observations

The new build reached `library_ready`. Two separate inspection-only XCTest runs
passed and retained app-scoped screenshots. These establish launch/accessibility
observation, not successful playback.

The first playback probe timed out querying the accessibility hierarchy. The
attached application later reported normal exit code 0. A subsequent targeted
crash-file listing found no new Veyra crash report; its latest entry still dates
from 2026-11-10 17:49:55 UTC. Absence of a new report does not prove an application
could not fail or hang.

A focused probe confirmed the selected title still includes “Dahab by night and
Nuweiba by dawn,” then attempted the actual compact-player Play control. The test
log shows foreground changes to SpringBoard and another application during the
taps, an invalid hit point and repeated orientation changes. It never reliably
reached the expanded-player toggle, so its failed result is **not playback
acceptance**. No download acceptance is claimed. The test is retained under
`.work/ios-device-probe/Tests/VeyraDeviceProbe.swift`; production source was not
changed during this continuation.

One transient post-timeout accessibility snapshot also contains the incomplete or
damaged-file snackbar. That observation does not identify the producing action or
asset, and is not treated as proof that the selected recording's media is corrupt.
It remains a finding to investigate during the uninterrupted physical retest.

Device actions were stopped after the foreground conflict. The task-owned local
console monitor was detached with a nonforwarded local signal, avoiding a catchable
signal to the phone application. No heavy build/test runner remains active.

## Evidence and remaining check

Evidence paths below are relative to `.work/evidence/redesign/`:

- `iphone-source-acceptance-20261111-install-retry.log` and `-install.json`: install success.
- `iphone-source-acceptance-20261111-console.log` and `-console-2.log`: actual startup.
- `iphone-probe-source-acceptance-inspect-20261111-test.log` and
  `iphone-probe-source-acceptance-after-timeout-20261111-test.log`: two inspection passes.
- `iphone-probe-source-acceptance-playback-20261111-test.log`: snapshot timeout.
- `iphone-probe-source-acceptance-exact-20261111-build.log` and `-test.log`:
  focused probe compiled, signed, then failed during foreground changes.
- `iphone-source-acceptance-20261111-crash-files.json`: targeted app crash-file listing.
- `iphone-source-acceptance-20261111-console-detach.json`: owned monitor shutdown.

The source-selection and native error-classification changes are installed.
Physical streaming, fresh download, offline restart, keyboard/bottom geometry and
player gestures still need an uninterrupted check with Veyra in the foreground.
The Android acceptance and unchanged authored regression results retain their
scope in `source-selection-runtime-20261111.md`. The full redesign remains open.
