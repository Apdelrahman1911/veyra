# Veyra · ڤيرا

A local-first music and podcast application for **Android, iOS, Windows, macOS and Linux**, built with Kotlin and Compose Multiplatform. English and Arabic/RTL, online sources and verified offline listening are required release features.

**Under active implementation; not a completed or verified release.** See [current state](docs/STATE.md), [plan and acceptance gates](docs/PLAN.md), and [decisions](docs/DECISIONS.md). No fake media integration is presented as working. The approved YouTube live fixture currently returns an authorization/bot restriction; podcast discovery/metadata sources are independently reachable. All playable content must resolve through kmpYT, not directly through RSS.

## Checkout and build

```sh
git clone https://github.com/Apdelrahman1911/veyra.git
cd veyra
# Use JDK 21 and Python 3.11+. Android builds require SDK 37.0 and Build Tools 36.0.0.
scripts/gradle.sh :shared:jvmTest
# Linux UI tests use an isolated, project-owned Xvfb (existing tools required):
scripts/with-display.sh scripts/gradle.sh :composeApp:desktopTest
scripts/gradle.sh :composeApp:run
scripts/gradle.sh :composeApp:assembleDebug
```

Use `scripts/gradle.sh` to validate the Gradle wrapper and confine tool homes, caches and temporary files to the ignored `.work/` directory. On Windows, use Git Bash with the preinstalled JDK and SDK. Use Xcode 26.4.1 for iOS builds. The iOS targets are arm64 for devices and Apple Silicon simulators.

Veyra builds kmpYT from the checked-in upstream snapshot while Maven Central publishing is paused. NativeCmpKit Kira UI resolves from Maven Central. A plain clone includes the kmpYT source.

| Library | Dependency | Use in Veyra |
| --- | --- | --- |
| [kmpYT](https://github.com/Apdelrahman1911/kmpYT) | [0.1.3 source snapshot](third_party/kmpYT/source-tree.json) | Media search, source resolution and downloads |
| [NativeCmpKit Kira UI](https://central.sonatype.com/artifact/io.github.apdelrahman1911.nativecmpkit/kira-ui/0.1.0-preview.1) | `io.github.apdelrahman1911.nativecmpkit:kira-ui:0.1.0-preview.1` | Adaptive UI components, with Kira Core resolved transitively |

Android uses Media3. iOS uses AVPlayer. Desktop distribution builds use a pinned native libmpv runtime and include a JVM. Build native distributions on the target operating system.

### Update kmpYT or return to Maven Central

Make library changes upstream and run kmpYT CI. While publication is paused, tag each new tested version upstream as `source-vMAJOR.MINOR.PATCH`, for example `source-v0.1.4`. Those tags identify versions for source integration. The publisher uses regular release tags such as `v0.1.4`.

Use the maintainer checkout in `.work/kmpYT` to refresh Veyra's snapshot. The refresh records the version, full commit, and source digest. Commit the snapshot and generated notices together.

```sh
git -C .work/kmpYT fetch --tags origin
python3 -B scripts/kmpyt-source.py refresh source-v0.1.4
python3 -B scripts/package-notices.py
scripts/verify-checkout.sh
```

Once the desired version is public, set `gradle/kmpyt.mode` to `maven`. Update the catalog version, `gradle/kmpyt.commit`, and the kmpYT entry in the publication notice record from the verified Maven POM and JAR. Retain the exact notice bytes by SHA-256, regenerate the app notices, and verify that the app selects the published coordinate. The snapshot can then be removed. [Publication notice record](third_party/notices/published-libraries.json) · [Notice verification](docs/verification/package-notices.md)

## Data and privacy

Your library, favorites, playlists, follows, preferences and listening progress belong on your device. No Veyra account or telemetry service is required. Online sources receive the requests needed to search/fetch their content. Only explicit, validated downloads become available offline; cached metadata is not audio. Never commit `.work/`, media downloads, source cookies or signed URLs.

## License and delivery status

Veyra’s code is GPL-3.0-or-later. kmpYT retains its MIT license, and NativeCmpKit retains Apache-2.0 and upstream attribution. See the software notices and the distribution audit for license texts and remaining release requirements. [Software notices](NOTICE.md) · [Licensing audit](docs/verification/licensing.md)
