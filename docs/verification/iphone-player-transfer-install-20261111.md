# Latest player and transfer build installed on iPhone — 2026-11-11

The user's connected iPhone received the current development-signed Veyra build
in place, using the existing bundle ID, signing identity and valid device profile.
No uninstall or app-data reset was performed. The complete Kotlin/Swift device
build succeeded, including a fresh iOS arm64 framework link. This includes the
native player seek integration and bounded-range iOS download repair.

All 767 recorded source inputs remained unchanged through build/sign/install.
Nested publisher-owned debug libraries and the app signature verified, with
expected development entitlements. CoreDevice reported installation and launch
success. The launched process was subsequently observed running at PID 26478.
This establishes installation and initial process liveness, not physical playback
or download acceptance. No playback/download controls or unrelated apps were used.

The install response supplied `unknown` for the optional Launch Services
persistent identifier. An attempt to use it was rejected before launch; launching
by the verified bundle ID then succeeded. The failed CLI attempt is retained.

Evidence under `.work/evidence/redesign/iphone-player-transfer-20261111-`:
`build.log`, `source-hashes.json`, `signature.json`, `install.json`, `launch.json`,
`processes.json`, and `summary.json`.

Signed debug-library SHA256:
`cf3ee54f2deb80892767ce7a3b64ae91533530973c2d0d8f56351696fff465b2`.
The broader redesign and physical media acceptance gates remain open.
