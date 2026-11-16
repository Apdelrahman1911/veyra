# Redesign, native controls, and UI verification — 2026-11-10

This continues the full Veyra redesign in the existing checkout. It does not
establish completion of the physical playback/download and accessibility gates.
All changes remain local; no commit, push, store upload, uninstall, or data reset
was performed.

## Implemented refinements

- Home presents an empty recommendations response as a calm, bilingual invitation
  to search. Other source failures remain visible. No recommendations were invented.
- The nativeCmpkit consumer's UIKit switches and sliders update their theme and
  layout direction when the app changes language or appearance. The text-size
  slider receives a localized accessible name, actual displayed percentage, and
  speech language instead of exposing its internal normalized fraction.
- Settings expansion is keyed by a stable section identity. Changing English to
  Arabic and back no longer closes Appearance. Headers expose localized expanded
  or collapsed state and stable test selectors.
- The full player explicitly supplies its theme text color. The authored Arabic
  dark-theme capture previously showed black header/title text; the final capture
  shows readable light text. Media transport direction stays unchanged in Arabic.
- Search again offers a direct pointer/touch Search button. It is disabled for a
  short query, loading state, offline mode, or the instant local-library scope.
  The existing IME/tap keyboard dismissal stays in place.
- Track details retain a fixed Back action for standalone callers. The application
  hides that local header because its shell supplies navigation, avoiding duplicate
  headers. Track, episode, and player Audio details controls expose their state;
  detail expansion belongs to the current media item.
- Tests now open the actual menus and audio disclosures before checking actions or
  recording identity. The original assertions for exact occurrences, reference-only
  deletion, downloaded/current/previous receipts, and offline restrictions remain.

## Native host linkage correction

A successful Xcode build and saved source hashes did not prove that the Simulator
host had linked the newly compiled Kotlin archive. Fresh screenshots still showed
the old Home failure card. `xcode-framework-relink-before.json` establishes that the
Simulator archive contained `NoRecommendations` while its app debug library did
not. The intermediate device binary already contained that marker; the evidence
does not establish that every earlier iPhone build was stale.

The Xcode script phase now declares its generated archive, header, and module map.
Both Debug and Release explicitly link the generated static archive. The repaired
Simulator and device build logs show actual `Ld` steps. Both resulting app binaries
contain the new Home, track-disclosure, and settings-section markers, as recorded
in `xcode-framework-relink-after.json`.

The `redesign-final-simulator-*` captures made before that linkage correction are
superseded. Use only `redesign-refreshed-simulator-*` for this current UI state.
Older source snapshot receipts describe inputs and must not be treated alone as
proof that a native application contained those inputs.

## Executed verification

All evidence names below are relative to `.work/evidence/redesign/`.

| Check | Result and evidence |
| --- | --- |
| Full desktop UI/localization suite | **235 tests, 0 failures/errors/skips**, from actual XML; `all-ui-final-summary.json`, `all-ui-final-TEST-*.xml`, `redesign-all-ui-after-import.log`. |
| Regression history | First full run: 235 tests / 12 failures, preserved under `all-ui-before-TEST-*`. Focused settings run initially found 11 stale closed-section assumptions. No assertions were removed to obtain the final pass. |
| Runtime language switching | New `changingLanguageKeepsTheAppearanceSectionOpen` passes alongside Arabic 200% narrow-layout, independent settings-intent, and keyboard-activation tests. |
| Android production compilation | `redesign-final-android-compile.log` succeeds. No new Android runtime or audible-output claim. |
| Complete Simulator host | `ios-framework-refresh-simulator-build.log` succeeds and relinks the current archive. |
| Complete iPhone host | `iphone-framework-refresh-build.log` succeeds and relinks the current archive. |
| Development signature | `iphone-framework-refresh-sign.log` verifies nested code, profile, entitlement, architecture and signature; receipt `iphone-framework-refresh-signature.json`. |
| Native app visual/startup check | Actual task-owned iOS Simulator cold launches reach `library_ready` in English light/dark and Arabic dark. Process-only AppleLanguages/AppleLocale launch arguments were used; no device language setting or user database was edited. |
| Current captures | `redesign-refreshed-simulator-captures.json`; each named PNG was captured from the task-owned Simulator. English and Arabic dark captures were visually reviewed. Home navigation paints through the bottom safe area; no selected mini-player was present. |
| Player visual fixtures | `redesign-final-player-ar-dark-large-top.png` and controls, plus the preserved before-color capture. These are authored desktop UI fixtures, not physical iPhone captures. |

The UI suite includes music, playlists, collections, downloads, podcasts, settings,
design/localization, keyboard dismissal and expandable-player tests. It uses
fixtures and does not prove live extraction, native media decoding, audible audio,
VoiceOver/TalkBack behavior, or store readiness.

## Current iPhone installation and remaining acceptance

The final signed build is **not installed**. The specific paired iPhone still
reports `tunnelState=unavailable` in
`iphone-framework-refresh-device-state.json`. No installation was attempted while
that state was unavailable. Signing receipts intentionally say `installed=false`.

The current signed app's debug library SHA256 is:
`9a246fd5de6f6f3219bee895010eae511d1b6db7b98ef3ca4207a4732de80d02`.
Source snapshot: `iphone-framework-refresh-source-hashes.json`.

Still required on the connected iPhone: install in place; retry the exact video
that produced the native access error; verify actual bounded same-recording link
renewal, playback and download; complete tap-outside/IME keyboard checks and
compact/full-player bottom geometry; repeat player gestures; inspect native
slider/switch RTL, theme and VoiceOver adjustments. Earlier successful physical
playback/download and sheet gesture runs remain historical evidence for the
recordings/builds actually tested, not for this final build.

Current Windows/Linux execution, physical Android rendering, native screen-reader
checks, and human listening are unverified for this changed tree. Native builds
used the existing explicit local diagnostic path while kmpYT repairs remain dirty;
normal clean/pinned bootstrap gates remain intact. The full redesign goal remains
open.
