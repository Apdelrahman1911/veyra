# Veyra · ڤيرا

A local-first music and podcast application for **Android, iOS, Windows, macOS and Linux**, built with Kotlin and Compose Multiplatform. English and Arabic/RTL, online sources and verified offline listening are required release features.

**Under active implementation; not a completed or verified release.** See [current state](docs/STATE.md), [plan and acceptance gates](docs/PLAN.md), and [decisions](docs/DECISIONS.md). No fake media integration is presented as working. The approved YouTube live fixture currently returns an authorization/bot restriction; podcast discovery/metadata sources are independently reachable. All playable content must resolve through kmpYT, not directly through RSS.

## Checkout and build

```sh
git clone --recurse-submodules https://github.com/Apdelrahman1911/veyra.git
cd veyra
# Existing JDK21; Android SDK36 is needed for Android/configuration.
scripts/gradle.sh :shared:jvmTest
# Linux UI tests use an isolated, project-owned Xvfb (existing tools required):
scripts/with-display.sh scripts/gradle.sh :composeApp:desktopTest
scripts/gradle.sh :composeApp:run
scripts/gradle.sh :composeApp:assembleDebug
```

Use `scripts/gradle.sh` rather than bare Gradle: it confines tool homes, caches and temporary files to ignored `.work/`, checks the pinned submodule/wrapper, and disables automatic system SDK/JDK installation. It does not alter your calling shell's GitHub authentication or system configuration. On Windows use Git Bash with the preinstalled JDK and SDK. Build instructions are being verified; a command shown here is not a passing result.

The kmpYT dependency is an immutable Git submodule/composite build, not MavenLocal, JitPack or a mutable branch. Android uses Media3; iOS uses AVPlayer; desktop uses a pinned native libmpv runtime and a bundled JVM in distribution builds. Native distribution builds run on the target operating system.

## Data and privacy

Your library, favorites, playlists, follows, preferences and listening progress belong on your device. No Veyra account or telemetry service is required. Online sources receive the requests needed to search/fetch their content. Only explicit, validated downloads become available offline; cached metadata is not audio. Never commit `.work/`, media downloads, source cookies or signed URLs.

## License and delivery status

New Veyra code: GPL-3.0-or-later. The kmpYT owner authorized MIT adoption; the complete license and contribution/dependency audit are published in [kmpYT PR #1](https://github.com/Apdelrahman1911/kmpYT/pull/1). MIT is GPLv3-compatible. See [notices](NOTICE.md) and the [audit and remaining distribution obligations](docs/verification/licensing.md). Third-party rights and corresponding-source requirements remain intact. The reviewed upstream merge/final pin and exact binary inventories are still pending. App-store publication is outside scope; final artifacts, signing status and platform verification will be linked here only when actually available.
