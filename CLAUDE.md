# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**`AGENTS.md` is the binding working agreement** (modification boundary, no subagents, owner notifications via `/usr/local/bin/pocketterminal-notify`, evidence rules). Read it first; this file does not repeat it.

## Resuming work

Veyra (ڤيرا) is a Kotlin/Compose Multiplatform music + podcast app for Android, iOS, Windows, macOS and Linux, with English and Arabic/RTL as required features. It is under active implementation, not a verified release.

Before changing anything, read `docs/STATE.md` (newest entry on top, historical entries below), `docs/PLAN.md` (scope and acceptance gates) and `docs/DECISIONS.md` (D001–D013). Per-task evidence lives in `docs/verification/`; research in `docs/research/`. Raw evidence and probes live in the ignored `.work/evidence/`. When reporting, keep compiled, unit-tested, simulator/emulator-run and physical-device-verified as separate claims. The docs follow that rule throughout.

## Commands

Use `scripts/gradle.sh`. It keeps tool homes, caches and temporary files under `.work/`, validates the pinned Gradle wrapper, and disables SDK and JDK auto-download. It runs with `--no-daemon` and one worker by default. Use an existing JDK 21 and Python 3.11+. Android builds need SDK platform 37.0 and Build Tools 36.0.0.

```sh
scripts/gradle.sh :shared:jvmTest                     # shared logic, Room, coordinators
scripts/gradle.sh :composeApp:desktopTest             # Compose/UI + desktop tests (Linux: wrap in scripts/with-display.sh)
scripts/gradle.sh :shared:jvmTest --tests 'dev.veyra.playback.SomeTest'   # single test class (or append .method)
scripts/gradle.sh :composeApp:run                     # desktop app
scripts/gradle.sh :composeApp:assembleDebug           # Android APK
scripts/gradle.sh :shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:lintDebug
scripts/build-ios.sh simulator|device                 # compile unsigned Swift host (Xcode 26.4.1, arm64 simulator or device)
scripts/ci.sh desktop|android|ios [--package]         # what CI runs: Python tooling tests + Gradle suites + verification summary
scripts/package.sh                                    # current-OS installer only (deb/dmg/exe)
scripts/dev.sh <cmd>                                  # run any other tool with the scoped environment
```

`scripts/ci.sh` also runs the Python checks under `scripts/tests/` and `native/test_*.py`, plus `scripts/package-notices.py --check` and `scripts/verify-design-resources.py`. Run these after touching packaging, notices, design resources or native bundling. Use `python3 -B`. `scripts/android-instrumentation.sh --run` stays gated: running an Android guest needs separate owner approval.

Workflows are in `.github/workflows/` (`verify.yml`, `installers.yml`), on standard free runners only.

## Architecture

Gradle modules (`settings.gradle.kts`):
- **`:shared`**: KMP business logic (Android, JVM, iOS), package `dev.veyra`. Key areas: `source/` (kmpYT adapter, podcast/release metadata, network), `acquisition/` (typed request → kmpYT first-result resolution → collection traversal → download batches), `downloads/` (leased, Room-persisted download engine with physical validation and atomic finalization), `playback/` (`PlaybackController` coordinator, `AudioEngine` abstraction, crossfade math), `data/` + `data/room/` (normalized Room schema, DAOs, transactions), `model/`.
- **`:composeApp`**: Compose UI and platform glue. `app/AppContainer.kt` is the app-lifetime composition root that wires stores, coordinators and platform services. `app/AppNavigation.kt` + `ui/*` hold the four destinations (Listen, Search, Podcasts, Library) plus Settings. Native audio is in `androidMain/.../platform/audio` (Media3 + MediaSession), `iosMain/.../platform/audio` (AVPlayer) and `desktopMain/.../desktop/audio` (JNA → pinned libmpv).
- **`:nativeComponents`** consumes the published Kira UI module and owns Veyra’s platform adapters, including iOS UISwitch and UISlider. See [native UI integration](nativeComponents/README.md).
- **`iosApp/`**: the Xcode Swift host for the Compose framework. **`native/`**: desktop runtime bundling (libmpv/ffmpeg, Windows toolchain) with Python tests.

Invariants from the decision record that span many files:
- **All playable content resolves through kmpYT** (D005). Discovery sources (podcast RSS, release metadata) supply metadata only. The *complete title* goes to kmpYT search and the *first ordered result* is accepted. Never truncate the title, pick a later result, or fall back to direct RSS/HTTP playback or downloads. Keep both the requested and the resolved identity.
- `gradle/libs.versions.toml` pins the kmpYT and NativeCmpKit Kira UI Maven Central releases. `gradle/kmpyt.commit` and `gradle/nativecmpkit.commit` record their publisher-declared source revisions. General-purpose library changes go upstream and reach Veyra in a pinned release.
- **Room is the only persistence** (D006): normalized schema, foreign keys, versioned exported schemas in `shared/schemas/`, explicit migrations tested with `MigrationTestHelper`, no destructive fallback. Schema changes require a new export plus a migration and its test. `AppSnapshot` is only an in-memory UI projection.
- **One playback coordinator, two persistent queue contexts** (Music, Podcast) (D004). Browsing never switches audio, and state restores paused. Native callbacks carry generation/seek tokens so stale events can be rejected. Music-only equal-power crossfade uses managed native decks (D009).
- Downloads (D008) are executed by kmpYT and orchestrated, validated and reconciled by Veyra. "Ready" requires validated lengths, decoded media and hashes. On iOS the app reports NeedsForeground/Paused instead of promising background execution.
- Automatic audio selection prefers the source-declared original language, never the app/device locale (D013).
- UI: "Quiet Currents" theme (`ui/design/VeyraTheme.kt`), localized strings via `ui/design/AppStrings.kt`, bundled IBM Plex Sans Arabic, 48dp targets. Navigation mirrors in RTL; media transport and timelines do not.

## Licensing and data hygiene

New Veyra code is GPL-3.0-or-later; kmpYT is MIT; nativeCmpkit is Apache-2.0. Keep `NOTICE.md` and its copy at `composeApp/src/commonMain/composeResources/files/legal/NOTICE.md` in sync; `scripts/package-notices.py --check` verifies them. Tests use authored redistributable fixtures. Live-source checks are labeled separately. The approved YouTube live fixture currently hits an authorization/bot restriction, and the app must not bypass it.
