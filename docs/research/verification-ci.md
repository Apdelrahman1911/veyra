# Verification, CI, packaging, and zero-purchase feasibility

Research date: **2026-11-06 UTC**. This is a proposed verification plan, **not a record of successful application builds or device tests**. Sources were fetched read-only during planning. No repository, runner, billing setting, SDK, signing identity, or system configuration was created or changed for this research.

## Executive decision

- A public Veyra repository can use **standard GitHub-hosted Linux, Windows, and macOS runners without compute charges**. Do not use larger/custom/GPU runners, paid device farms, or purchases. Public visibility is a hard precondition, not an incidental repository preference.
- Build native desktop packages on their respective operating systems. Kotlin/JVM compilation on Linux does **not** produce or verify Windows or macOS installers.
- Build and launch the complete iOS simulator app on a standard macOS runner. A compiled Kotlin framework is a lower verification level than a built and launched iOS application.
- Android debug APKs and unsigned desktop packages are possible without purchasing signing credentials. An unsigned iOS device build is **not** an installable IPA. TestFlight, App Store distribution, and Developer ID notarization are not promised.
- **Do not use Actions artifact uploads initially.** Current billing documentation clearly exempts public standard-runner compute, but describes artifact storage as a separate pooled allowance with hourly charges. No public-artifact exemption or available account allowance was established. Use logs/job summaries for evidence and authorized **public GitHub Releases** for distributables; no copyrighted test media, cookies, credentials, or private captures in either.
- The filesystem boundary applies to CI as well as the VPS: no system package installation, global caches, global Git configuration, or SDK modification. Use installed tools read-only or download/extract tools into the checked-out Veyra directory.

## 1. Read-only feasibility observations

| Observation | Result | What it proves / does not prove |
|---|---|---|
| Local host | Linux x86_64, 12 logical processors | Suitable host for JVM/Linux/Android compilation; not an Apple or Windows build host |
| Available memory at sampling | Approximately 4.4 GiB; no swap | Keep local builds sequential and use 1–2 workers; total RAM is not available RAM |
| Available disk at sampling | Approximately 36 GiB | Sufficient for a bounded initial build, not permission to fill the host with caches |
| Installed JDK metadata | Ubuntu OpenJDK 21.0.12 | JDK 21 available without installation |
| Installed Gradle path | `/opt/gradle/gradle-8.14.5` | Tool exists; use the project-pinned wrapper rather than silently substituting it |
| Existing Android SDK | Platforms 34, 35, 36, 37; build-tools 34.0.0, 35.0.0, 36.0.0 | Read-only SDK use is possible; unneeded preview platforms are not selected |
| Android emulator executable | Present under installed SDK | Presence is not a successful boot |
| `/dev/kvm` | Absent | No local hardware-accelerated emulator verification can be claimed; no system change is authorized to create it |
| GitHub CLI | Authenticated as `Apdelrahman1911` | API identity was established without disclosing credentials |
| GitHub token capabilities | `repo`, `workflow` scopes observed | Capabilities appear sufficient for the lead's later authorized repository/workflow changes; actual changes were not tested |
| Existing `Apdelrahman1911/kmpYT` | Public; authenticated account has push/admin permissions | Read-only repository access established |
| Proposed `Apdelrahman1911/Veyra` | Unavailable to a read-only repository GET during initial research | Repository creation remains the lead's implementation responsibility |

No `adb devices`, emulator boot, Gradle execution, installation, or other operation that might start a service or populate a global cache was used in this inventory.

Agent-model attestation is limited to the lead's accepted explicit `gpt-6-astra` / `max` spawn requests. There is no independent runtime/back-end attestation available to this task, and no stronger claim should be made.

## 2. Official runner policy and exact labels

GitHub's current [runner reference][runners] says standard GitHub-hosted runners are **free and unlimited for public repositories**. “Unlimited” does not remove concurrency, job-duration, storage, fair-use, or service-availability limits. The [billing reference][billing] explicitly says larger runners are billed for public repositories.

Recommended initial allowlist:

| Job purpose | Explicit standard label | Architecture | Published resources | Notes |
|---|---|---|---|---|
| Common/JVM tests, Android compile, Linux package | `ubuntu-24.04` | x64 | 4 CPU, 16 GB RAM, 14 GB SSD | Existing `dpkg`, `fakeroot`, `rpm`, and Xvfb are documented |
| Windows tests and package | `windows-2022` | x64 | 4 CPU, 16 GB RAM, 14 GB SSD | Existing WiX 3.14.1 is compatible with JDK 21 jpackage's WiX 3 requirement |
| iOS simulator/device compilation and Apple Silicon desktop package | `macos-15` | **arm64** | 3 M1 CPU, 7 GB RAM, 14 GB SSD | Run memory-intensive tasks sequentially; do not assume Intel |
| Intel macOS desktop package if claimed supported | `macos-15-intel` | x64 | 4 CPU, 14 GB RAM, 14 GB SSD | Distinct binary and runtime verification from arm64 |

As checked on the research date, `macos-latest` maps to macOS 26 arm64, and `windows-latest` maps to Windows 2025. Pinning the OS label avoids that particular alias migration, but **does not freeze runner-image contents**. Save the actual image/tool versions in each run's summary. Do not use `-large`, `-xlarge`, GPU, custom-runner groups, `snapshot`, or billing-related settings. `ubuntu-slim` is not appropriate for this build.

The initial workflow should use a literal allowlist, an `if` guard requiring `github.event.repository.private == false`, read-only default permissions, per-ref concurrency with cancellation, bounded timeouts, and no scheduled builds until the matrix is stable. Standard free-plan limits currently include 20 concurrent jobs total, 5 concurrent macOS jobs, and 6 hours maximum per job; our intended jobs are much smaller (roughly 20–60 minute timeouts). Limits are shared with other work on the account and are not reserved capacity.

### Image-source freshness

The following official `actions/runner-images` README revisions were checked through GitHub's commits API, in addition to fetching the current documents:

| Image document | Last relevant source commit | Source commit date |
|---|---|---|
| [macOS 15 arm64][mac-arm] | `4da2307112a0fad2fd2ecaca8ba7d08683b8c581` | 2026-10-30 |
| [macOS 15 Intel][mac-intel] | `07cc2190cd4d100d9f69478219017720c02c40f6` | 2026-08-28 |
| [Windows 2022][windows] | `6e67da36f4944e0bfeae0385c7a554726009b886` | 2026-10-29 |
| [Ubuntu 24.04][ubuntu] | `799bcd0daf03c14c460e2662bcc9ca94a4a8aaa8` | 2026-10-28 |

Actual runner availability and image rollout are only proven by the eventual workflow run. These README dates are not a successful CI result.

## 3. Conservative toolchain recommendation

Coordinate the final version catalog with architecture and the kmpYT integration audit. The lead's selected initial Veyra baseline retains kmpYT's existing AGP, with an explicit compatibility-table exception:

| Component | Proposed pin | Reason / caution |
|---|---|---|
| Kotlin multiplatform + Compose compiler plugin | `2.2.21` | Matches audited kmpYT Kotlin; both compiler plugins must have the same Kotlin version |
| Compose Multiplatform | `1.9.3` | Conservative generation aligned with Kotlin 2.2.x; do not mix with the latest documentation's newer platform-support claims |
| Android Gradle Plugin | `8.13.2` | Lead-selected match to existing kmpYT; **outside** the KGP 2.2.21 officially tested AGP range (maximum 8.11.1). Requires explicit build evidence; do not call it officially covered |
| Gradle wrapper | `8.13` | Matches existing kmpYT and AGP 8.13 minimum/default; inside the KGP 2.2.21 tested Gradle range |
| Java | JDK `21`, JVM bytecode target explicitly configured | Installed JDK 21 supports jpackage; record vendor/patch. If exact JDK patch reproducibility is required, verify/download it into `.work`, never use a global installer |
| Android build | `compileSdk=35` or `36`, `targetSdk` explicit, build-tools `35.0.0` | Final SDK choice must match architecture/library APIs; both are already installed. Fail if absent rather than auto-installing into a system SDK |
| iOS Xcode | `/Applications/Xcode_26.0.1.app/Contents/Developer` | Current macOS 15 images contain it; KGP 2.2.21's compatibility table lists Xcode 26.0. Patch-level behavior still needs actual build evidence |
| iOS runtime | Installed iOS 26.0-family runtime, dynamically resolved exact simulator ID | Fail if the selected runtime is absent; never silently call a different runtime “verified” |

The official [KGP compatibility table][kgp] lists Kotlin 2.2.21 with Gradle 7.6.3–8.14, AGP 7.3.1–8.11.1, and Xcode 26.0. The [AGP 8.11 documentation][agp] and [AGP 8.13 documentation][agp13] require Gradle 8.13 and JDK 17 minimum. Gradle 8.13's official `-bin.zip` SHA-256, fetched from the distribution service, is:

```text
20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
```

The existing kmpYT AGP 8.13.2 lies **outside** the KGP 2.2.21 table's tested AGP range. Do not falsely describe that combination as covered. The lead selected retaining it initially to minimize upstream changes, with actual target builds as a gate. AGP 8.11.1 is the conservative table-covered fallback, not an authorized upstream downgrade. A narrowly justified alignment or jointly validated toolchain update should be considered only if evidence requires it.

Xcode 16.4 is also installed on the current macOS 15 images, as are 26.2/26.3; **do not use a default Xcode implicitly**. Pin `DEVELOPER_DIR` and report `xcodebuild -version`, selected SDKs, and `simctl` runtime. Choosing Xcode 26.2 just because it is newer is not evidence of KGP compatibility.

### Action integrity

Pin third-party actions to full commit SHAs and retain a readable release comment. Current official release/tag lookups produced these candidates (not yet execution-tested):

| Action | Release | Commit SHA |
|---|---|---|
| `actions/checkout` | `v7.0.1` | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | `v6.2.0` | `77dd5b2ce225ea11469928ac317850245a74ae78` |
| `ReactiveCircus/android-emulator-runner` | `v2.38.0` | `a421e43855164a8197daf9d8d40fe71c6996bb0d` |

Their pinned `action.yml` files use Node 24. Prefer **not** to use setup-java here: choosing a preinstalled JDK through `JAVA_HOME_21_X64` / `JAVA_HOME_21_arm64` avoids its usual tool-cache/Maven-settings writes outside the workspace. Similarly, the emulator action is only a researched option; do not use its defaults if they install into the system SDK. A small repository-owned script with explicit SDK/cache paths is easier to audit against the boundary.

For checkout set **both** `persist-credentials: false` and `set-safe-directory: false`. The default safe-directory behavior writes global Git configuration. Any necessary safe-directory allowance must be per-command configuration, not a global setting.

## 4. Filesystem and credentials boundary

Only Veyra and the authorized kmpYT checkout may be modified. Do not solve a build failure using `apt`, Homebrew, Chocolatey, a global `sdkmanager --install`, global Gradle/Kotlin caches, Git global configuration, shell startup changes, unrelated-project edits, or deletion of unrelated caches.

For every build subprocess, create owned directories below `Veyra/.work` and redirect at least:

- `GRADLE_USER_HOME`, `KONAN_DATA_DIR`, and Gradle's project cache when it is overridden.
- `HOME` for build/test processes, Java `user.home`, and Java preferences roots; retain the existing terminal GitHub authentication context for lead GH operations instead of moving or copying credentials into a public checkout.
- `TMPDIR`, `TMP`, `TEMP`, Java `java.io.tmpdir`, and tool-specific temporary directories. Disable HotSpot performance-data files with `-XX:-UsePerfData` if needed to keep JVM bookkeeping out of system temp.
- `ANDROID_USER_HOME`, `ANDROID_AVD_HOME`, `ANDROID_EMULATOR_HOME`, and emulator/ADB logs. An emulator requiring downloaded packages needs its **own** `.work/android-sdk`, not symlinks that let sdkmanager mutate the installed SDK.
- `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, and `XDG_DATA_HOME` for desktop app smoke tests. Route test databases/preferences/downloads to fixture directories, not the owner's real library.
- Xcode derived data, result bundles, generated projects, and simulator-related files as far as supported. Native simulator tooling may require host-managed service state: **review that behavior before running**, rather than assuming changing `HOME` contains all CoreSimulator writes.

Use existing Android SDKs and JDK installations read-only. Disable Gradle Android SDK auto-download and preflight the required packages. Never cache signing keys, API tokens, cookies, private feeds, auth headers, or personal libraries. Ignore `.work`, credentials, local properties, build outputs, and private captures in Git.

Local JVM tests should begin with `--no-daemon --max-workers=1`, a bounded heap around 1.5–2 GiB, and in-process Kotlin compilation where useful. Increase to two workers only after observing safe memory use. On macOS arm64 CI, native linking and Xcode compilation should not compete with multiple parallel desktop builds.

## 5. Test-first pipeline and evidence taxonomy

Each feature/platform row in the final report must name one of these distinct levels. They are not interchangeable:

1. **Designed:** interfaces, sources, or tests exist; no successful execution claimed.
2. **Dependency/configuration resolved:** Gradle configuration and dependencies resolved; not a compile or runtime result.
3. **Compiled:** the specified target/task completed. A native framework is not an app; a skipped incompatible task is not a pass.
4. **Automated logic tested:** named tests/assertions executed with counts and failures recorded. JVM common tests do not verify iOS runtime behavior.
5. **Packaged:** a platform-specific distributable was generated, with architecture, signing status, size, and SHA-256. Does not prove installation or launch.
6. **Runtime smoke tested:** a named app/extracted package actually started and performed specified assertions on the stated host/emulator/simulator. A process that survived a timeout is weaker evidence than an app-owned ready/result signal.
7. **Installation tested:** the resulting package was installed through that OS's installer and launched in a clean environment. Inspecting/extracting an archive or launching a Gradle task is not this level.
8. **Physical/device tested:** exact hardware, OS, route/accessibility technology, source, and observed behavior recorded. Emulator/simulator output cannot be promoted to this level.

Record commit SHA, command/task, OS/architecture, actual tool versions, exit status, test counts, artifact checksums, and limitations. CI success is only evidence for its configured checks. Do not label silent/synthetic playback as audible real playback.

### Proposed jobs

| Job | Host | Required checks | Explicit non-claims |
|---|---|---|---|
| Fast deterministic | Ubuntu 24.04 | Common domain/JVM tests; provider/feed fixtures; state reducers; persistence round-trip; formatting/static checks | No actual mobile runtime/audio, no live-provider guarantee |
| Android build | Ubuntu 24.04 | App `assembleDebug`; Android unit tests; lint; manifest and APK checks | APK generation alone is not installation or playback |
| Linux desktop | Ubuntu 24.04 | Desktop tests; `packageDeb`; self-contained app-image runtime under preinstalled Xvfb with test-owned storage | No audible-output/Wayland/desktop-integration guarantee; extraction is not dpkg installation |
| Windows desktop | Windows 2022 | Desktop tests; `packageMsi`; app-image startup using included runtime | Unsigned MSI and no physical audio/SmartScreen acceptance claim |
| macOS desktop | macOS 15 arm64; Intel separately if supported | Desktop tests; `packageDmg`; packaged app-image startup and architecture checks | No notarization, Gatekeeper acceptance, or Intel verification from arm64 results |
| iOS compile | macOS 15 arm64 | Native common tests when supported; both simulator and device framework builds; **full Swift/Xcode app** build with simulator SDK | No installable device IPA or physical iPhone proof |
| iOS simulator smoke | Same, after filesystem-boundary audit | Boot allowed simulator/device set; install full app; launch; deterministic navigation/assertions; capture only safe synthetic UI | No background-audio lock-screen/Bluetooth/interruption/accessibility physical-device proof |
| Android emulator smoke | Ubuntu 24.04, project-owned SDK/AVD | Boot fixed API/ABI emulator; install APK; app-owned ready state/UI assertions; exercise deterministic playback source if supported | No physical output/focus/routing/battery guarantee |

Determine actual Gradle task names from the implemented project; the names above are intended checks, not evidence that those tasks already exist. Run a task directly or assert its execution outcome so that platform-skipped tasks cannot create false green support claims. Put genuine checks in required jobs without `continue-on-error`. Explicitly unconfigured simulator/physical checks must remain **not run**, not fabricated failures or successes.

### Deterministic tests before live services

- Queue movement, shuffle/repeat invariants, selection changes, cancellation, and exactly one active playback session.
- Episode resume progress, completed/reset transitions, speed bounds, sleep-timer semantics, history, and persisted library state after restart.
- Feed fixtures with malformed input, missing optional fields, namespaces, duplicate items, duration/date variation, redirects, invalid URLs, and XML entity handling.
- Provider adapter fixture parsing and typed failure mapping; no embedded user cookies and no credentials in snapshots.
- Offline/error/empty/loading UI states and recovery without discarding the user's queue/library.
- Native playback lifecycle contract tests with fakes, followed by distinct runtime tests with a short self-generated or openly licensed audio fixture. Provider capability must never be inferred from playing the fixture.

Live YouTube/provider smoke tests are a separate, bounded, non-secret evidence step and may be blocked by service restrictions. They must not be the only regression tests. If a real request fails, retain an honest categorized failure rather than substituting a fixture and calling it live success.

## 6. Packaging, signing, and installability limits

The [Compose packaging guide][packaging] and [JDK 21 jpackage documentation][jpackage] require packaging on the corresponding target OS. JVM distribution includes a Java runtime via jlink/jpackage; all required modules, codecs/native libraries, resources, and legal notices must also be present. Running successfully with the developer's full JDK does not prove the packaged runtime is sufficient.

| Platform output | Possible without purchases | Remaining owner/distribution constraints |
|---|---|---|
| Android debug APK | Debug-signed installable APK | Debug signing identity is not a production identity; physical device installation and real media behavior still need evidence |
| Linux `.deb` | Self-contained JVM application package; unsigned package file | Dependencies and codecs must be declared/bundled; distro/architecture and actual OS-install validation are separate |
| Windows `.msi` | Unsigned installer using existing WiX | SmartScreen/trust prompt and production code signing are not solved; test actual packaged launch separately |
| macOS `.dmg` | Unsigned or ad-hoc-signed app in image, architecture labeled | Not a Developer ID/notarized release; do not disable system protections or claim normal Gatekeeper acceptance |
| iOS simulator `.app` | Simulator build and simulator installation | Cannot install on a physical iPhone; label it **simulator-only** if distributed |
| iOS device framework / unsigned app archive | Compile/link evidence | **Not an installable IPA**. Do not rename an archive to IPA and claim delivery |
| Personal iPhone development build | Owner can use existing Apple Account/Xcode Personal Team | [Apple documents][apple] periodic reprovisioning and 7-day profile expiry; requires owner-controlled account/device; no login collection or implicit paid enrollment |
| TestFlight/App Store or notarized desktop release | Only if owner already provides appropriate membership/signing setup and explicitly authorizes it | No purchasing, enrolling, certificate generation, account access, or signing-secret handling is authorized by this CI plan |

Do not automatically weaken OS security controls to make an unsigned package appear installable. Documentation should state its signing status and an owner-reviewed development-install path.

With the strict filesystem boundary, CI initially performs **package generation plus app-image/extraction smoke checks**, not global OS installation. Real installer transactions may modify registry/system/application locations and require separate authorization/appropriate environment. CoreSimulator's service-managed state is another boundary decision, not a reason to silently claim an iOS launch.

## 7. Storage and publication: keep zero-cost guarantees narrow

The current billing page distinguishes runner compute from artifact/cache storage. It lists 500 MB artifact allowance for GitHub Free, shared with Packages, and 10 GB cache allowance per repository. Existing account usage was not inspected. Retaining fewer days or deleting later does not undo storage charges already accrued.

Therefore:

1. No `actions/upload-artifact`, setup-java cache, setup-gradle cache, raised cache quota, custom runner image, or billing-settings change in the initial workflow.
2. CI logs and job summaries are the primary evidence channel; GitHub states these do not count towards artifact storage allowance. Keep them free of credentials, copyrighted media, user feeds, or personal data.
3. Publish authorized distributables directly from their producing job to a **public Veyra Release**, avoiding an intermediate Actions artifact. Limit `contents: write` to an explicit trusted release job/event; fork PRs and ordinary untrusted CI remain read-only. Never use `pull_request_target` to run untrusted code with publication credentials.
4. Before release, validate file allowlist, size, architecture, signing label, license notices, and SHA-256. Release assets must be below 2 GiB each. GitHub's [release documentation][releases] currently permits up to 1,000 assets per release and states no total release-size or bandwidth limit. This is release distribution, not a general-purpose storage service.
5. Once public artifact billing/remaining quota is independently established, an optional tightly bounded artifact policy could be considered, but that is not needed to begin. Do not enable it merely because an example workflow does.

[Retention settings][retention] currently default to 90 days; public repositories can use 1–90 days. Since 2026-10-01 the described policy also covers checks, workflow runs, and commit statuses. Specific artifact retention cannot exceed repository/organization limits. Long-lived release notes should summarize verified checks with commit IDs/checksums, rather than assuming transient CI URLs are permanent evidence.

## 8. Before calling the product verified

- [ ] Public repository and literal standard-runner allowlist confirmed at the actual run.
- [ ] Every local/CI build command respects repository-owned caches/temp/config; no hidden global installs.
- [ ] Exact Veyra and kmpYT revisions recorded and native integration compiled, not merely stubbed.
- [ ] Required tests executed with assertions/counts; skipped/not-run targets called out.
- [ ] Android app and all claimed desktop architectures compiled and packaged on the correct OS.
- [ ] Packaged desktop runtime launches without the developer's system JDK or undeclared playback installation.
- [ ] Full iOS app build distinguished from Kotlin framework build; simulator launch only if genuinely executed within the allowed boundary.
- [ ] Synthetic audio verification distinguished from live-provider resolution and from audible/physical-device playback.
- [ ] Installability, signing/notarization, background playback, interruptions, routing, keyboard/screen-reader behavior, and physical-device gaps explicitly listed.
- [ ] Public release assets contain only authorized code/binaries/notices and safe generated fixtures; hashes and signing limitations documented.

## Execution checkpoint — 2026-11-06

These are scoped observations, not a green matrix or release certificate:

- Public kmpYT PR2 head `31300ef8b21cb3cbce3a6e4afe36096f3234a43f`, run
  `42182306563`: Linux and Intel macOS each executed **443 tests, 4 failures,
  1 skip**. The four shared failures were `DesktopRuntimeDiagnosticsTest`
  authored process/stream fixtures incompatible with the stricter actual helper.
  The isolated fixture-only repair `f0f7fab218323ff3bc9a7728dc22b2cd1f329bd6`
  subsequently passed actual JVM **4/4** and Node endpoint **22/22** with the
  matching helper and 125 unchanged input hashes. Evidence:
  `.work/kmpyt-evidence/diagnostics-f0-first/`. It had not yet been published at
  this observation; testing it against an older helper is not equivalent.
- That same public Windows job executed **443 tests, 10 failures, 1 skip**:
  the four fixture failures, three real Chromium startup failures and three
  checkpoint cases. The actual Chromium failure reason remains unestablished;
  safe code `OFFLINE_BROWSER_STARTUP_TIMEOUT` does not prove a specific cause.
  Later checkpoint edits need their own revision-specific evidence.
- The PR2 Apple job failed real simulator and device compilation in
  `IosYouTubeSabrPlaybackAdapter.kt` (`inet_pton`, `ntohs`, and
  `timeIntervalSince1970` unresolved). Protocol/WebKit steps separately stopped
  with exit78 before CoreSimulator. Do not inherit main revision `45f7077`'s
  successful framework-build result into this different source revision.
- Only the lead-authorized obsolete PR1 duplicate runs `42179433540` and
  `42179440862` were cancelled, both verified **completed/cancelled**, not merely
  accepted cancellation requests. Current PR2/native jobs were not cancelled.
  Read-only job logs/status receipts are under `.work/verification/upstream-ci/`.
- Veyra's [Android Stage0](../verification/android-stage0.md) has **28/28**
  authored Python regressions, **10/10** scoped-tooling regressions, and **2/2**
  separately approved strace/Python immediate-double-fork teardown checks.
  No Android helper preflight, emulator guest, or instrumented test has run.
  Review-ready input hashes: `.work/verification/android-stage0-review-inputs.json`.

## Primary sources

All accessed 2026-11-06. Source pages may change; actual build evidence must record its own dates/tool versions.

[runners]: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
[billing]: https://docs.github.com/en/billing/concepts/product-billing/github-actions
[mac-arm]: https://github.com/actions/runner-images/blob/4da2307112a0fad2fd2ecaca8ba7d08683b8c581/images/macos/macos-15-arm64-Readme.md
[mac-intel]: https://github.com/actions/runner-images/blob/07cc2190cd4d100d9f69478219017720c02c40f6/images/macos/macos-15-Readme.md
[windows]: https://github.com/actions/runner-images/blob/6e67da36f4944e0bfeae0385c7a554726009b886/images/windows/Windows2022-Readme.md
[ubuntu]: https://github.com/actions/runner-images/blob/799bcd0daf03c14c460e2662bcc9ca94a4a8aaa8/images/ubuntu/Ubuntu2404-Readme.md
[kgp]: https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html
[agp]: https://developer.android.com/build/releases/past-releases/agp-8-11-0-release-notes
[agp13]: https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes
[packaging]: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-native-distribution.html
[jpackage]: https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html
[apple]: https://developer.apple.com/support/compare-memberships/
[releases]: https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
[retention]: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#configuring-the-retention-period-for-checks-workflow-runs-commit-statuses-artifacts-and-logs-in-your-repository
