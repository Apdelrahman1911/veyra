# Task B — draft package notice coverage (bounded research pass)

Updated 2026-11-09 UTC. **Draft evidence collection, not legal advice, not a
compatibility decision and not release acceptance.** This pass only read retained
packets and repository-owned `.work` dependency artifacts; it did not build, run
Gradle, execute a package, install anything, scan a global cache or retrieve
network sources. PR27 head during this pass was
`78df94d5ace55ad5588c2f4388397eab8e3d695d` (read-only); another owner subsequently
advanced the branch to `fb9a0b8a774a0e5633bc061651ff15a8d4f48c05`, which changed no
build input used here and is not evaluated by this report. Retained run
`42569788087` is at commit
`ceced9a7af226b22a2a96b82d8fa2451a6522729`, kmpYT pin
`6805d4db8077650992d342e5395b1f0d7c80e984`.

Owned evidence: `.work/verification/task-b-notice-coverage/` (new, untracked).
Retained packets under
`.work/verification/task-b-hosted-fixes/retained-42569788087/` were not modified.

## 1. Retained data verified

`support.zip` hashes match each lane's `verification.json`:

| packet | support.zip SHA-256 | components | coverageIssues | docs | noticeCoverageComplete | binaryRedistributionReady |
|---|---|---|---|---|---|---|
| desktop-linux-x64 | `a03153005e232ad68b15e309c93e0bfbbfca80089d439628df77792287ecf032` | 161 | 85 | 15 | false | false |
| desktop-macos-arm64 | `03bbab5e43c9e75892a097e4de780e3344786db491296a47651fa9c48636d114` | 161 | 85 | 15 | false | false |
| desktop-macos-x64 | `2adbbdfd23e975c24d7a1db5904835b2ee45c7779f0a3c2cfd170c7224798709` | 161 | 85 | 15 | false | false |

Package hashes also match `verification.json`: Linux `.deb`
`78577abb870b7ef0b5e4d0660d590656078629c44e7faff20b619f928840a98f`, macOS
arm64 DMG `9dc90ba6142491b0da128fb6850a5468812b9368f195d9adb9551c25718cb746`,
macOS x64 DMG `fcd8ebee3a6123352784af80f88d274618174383fc87d9ea44722a703f73a6e0`.

Actual artifact verification against the retained inventories:

- Linux: **87/87** inventory artifacts and **166/166** POMs exist and hash-match
  (85 module artifacts plus two project JARs).
- macOS arm64: **85/87** artifacts and **163/166** POMs exist and hash-match.
  Missing locally: `skiko-awt-runtime-macos-arm64:0.9.22.2` and
  `mediamp-mpv-runtime-macos-arm64:0.5.3` (plus their POMs and the
  `desktop-jvm-macos-arm64` POM).
- macOS x64: same counts; missing `skiko-awt-runtime-macos-x64:0.9.22.2` and
  `mediamp-mpv-runtime-macos-x64:0.5.3` (plus their POMs and the
  `desktop-jvm-macos-x64` POM).

All **15/15** documents referenced by the retained legal index were reproduced
with exact bytes from matching inputs: 12 from artifact entries whose artifact
SHA-256 matches the inventory, and 3 authored asset notices from the repository.
The same 15 texts are embedded in the retained `THIRD_PARTY_NOTICES.txt`; every
embedded block's `Original-text SHA256` marker matches its content (15/15 in
each packet; `analysis/tpn-embedded-verification.json`).
All **21/21** `fileHashes` in the retained index are accounted for:
`NOTICE.md`, `THIRD_PARTY_NOTICES.txt` and `kmpYT-LICENSE` present in the
support packet; `LICENSE` and `kmpYT-THIRD_PARTY_NOTICES.md` matched against the
pinned source; the 15 reproduced notices; and the deterministic `INDEX.txt`.
The retained `support.zip` contains only four legal files; the separate
`notices/*` files are absent, but the notice bundle is self-contained because
`THIRD_PARTY_NOTICES.txt` embeds all 15 texts with SHA/origin headers. Reproduced
copies are in `.work/verification/task-b-notice-coverage/texts/`.

Evidence: `analysis/candidate-documents.json` (per-origin SHA proof),
`analysis/extract_candidates.py`, `receipt.json`.

## 2. Exact missing union

The union is **89** missing component/version coordinates, not 85:

- 83 common to all three packets;
- 2 Linux-only (`skiko-awt-runtime-linux-x64`, `mediamp-mpv-runtime-linux-x64`);
- 2 macOS arm64-only (`skiko-awt-runtime-macos-arm64`,
  `mediamp-mpv-runtime-macos-arm64`);
- 2 macOS x64-only (`skiko-awt-runtime-macos-x64`,
  `mediamp-mpv-runtime-macos-x64`).

Each packet still has 85 components with artifacts and 85 coverage issues. In
the union, **22** components have an artifact-embedded complete license text and
**67** have no matching license/notice entry under the repository scanner's
role rules. The 85-issue per-packet count is not the union count.

Exact machine-readable lists:
`.work/verification/task-b-notice-coverage/analysis/missing-union.json` and
`missing-union.tsv`.

### Retained candidate texts grouped by SHA

Seven distinct complete license texts cover the 22 components:

| SHA-256 (prefix) | bytes | role / identity | components covered |
|---|---|---|---|
| `809fa1ed21450f59` | 10,175 | Apache-2.0 full terms | 16 AndroidX components |
| `b1d2870f1a00e4d7` | 11,359 | Apache-2.0 full terms | `commons-lang3`, `commons-codec` |
| `51b88fd3e9e24edc` | 11,358 | Apache-2.0 full terms | `commons-compress` |
| `c1d1a38c99c48cca` | 11,360 | Apache-2.0 full terms | `commons-io` |
| `0d542e0c8804e39a` | 10,174 | Apache-2.0 full terms (JNA dual grant) | `jna` |
| `eea173a556abac03` | 24,389 | LGPL-2.1 full terms (JNA dual grant) | `jna` |
| `4e7f90c86ab51278` | 1,178 | MIT full terms | `slf4j-api` |

The `809fa` and `0d542` copies end at `END OF TERMS AND CONDITIONS` and omit
the optional "How to apply" appendix; they contain the complete license terms.

Additional retained texts are notice-role or asset-level, not substitutes for a
complete component license:

- `07c938b23950ab7d` (797 bytes): JNA dual-license notice; it points to the two
  complete texts above and is not itself a complete license.
- `0d98967f0ab328af`, `64bc8696af3f6c77`, `b64933ee1d36d146`,
  `b54b0db6c617273e`: Apache Commons `NOTICE` texts (178/174/175/172 bytes).
- `3f3d9e0024b1921b` (MPL-2.0, 16,726 bytes), `7e6b2818edbd8f6a` (OFL-1.1,
  4,456 bytes), `7b43872589cd962d` (CA-bundle provenance, 818 bytes): authored
  asset notices, not linked to the 85 Maven components.

No Apache text was applied globally from a POM declaration. Every one of the 22
records is tied to an exact entry in the exact artifact whose SHA-256 matches the
retained inventory. The 67 remaining components have only retained POM
declarations and filenames; those are deliberately not treated as licenses.

### The 67 remaining, by publisher/version

| group:version | count | declared POM term (declaration only) |
|---|---:|---|
| `androidx.arch.core:core-common:2.2.0` | 1 | Apache-2.0 |
| `com.github.hypfvieh:dbus-java-*:5.2.2` | 2 | MIT |
| `com.squareup.okio:okio-jvm:3.15.0` | 1 | Apache-2.0 |
| `io.coil-kt.coil3:coil*-jvm:3.3.0` | 6 | Apache-2.0 |
| `io.ktor:ktor-*-jvm:3.2.3` | 14 | Apache-2.0 |
| `org.jetbrains:annotations:23.0.0` | 1 | Apache-2.0 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop:2.9.6` | 1 | Apache-2.0 |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop:1.3.6` | 1 | Apache-2.0 |
| `org.jetbrains.compose.*:1.9.3` | 18 | Apache-2.0 |
| `org.jetbrains.compose.material3:material3-desktop:1.9.0` | 1 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7/jdk8:2.1.21` | 2 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.21` | 1 | Apache-2.0 |
| `org.jetbrains.kotlinx:*` (0.23.2/0.7.0/0.7.1/1.9.0/1.10.2) | 9 | Apache-2.0 |
| `org.jetbrains.runtime:jbr-api:1.5.0` | 1 | Apache-2.0 |
| `org.jetbrains.skiko:*:0.9.22.2` | 4 | Apache-2.0 |
| `org.jspecify:jspecify:1.0.0` | 1 | Apache-2.0 |
| `org.openani.mediamp:mediamp-mpv-runtime-*:0.5.3` | 3 | GPL-3.0 (Linux); LGPL-2.1+/Apache (macOS POMs) |

Representative JARs (`ktor`, Kotlin stdlib, coroutines, Compose runtime,
`jbr-api`, skiko, mediamp) were checked and contain no entry matched by the
scanner's license/notice rules; this is a gap in retained artifact text, not a
claim that the projects publish no license.

## 3. Draft supplement (22 components, 23 records)

`.work/verification/task-b-notice-coverage/draft-supplement.json` follows the
existing `scripts/package-notices.py` schema: `schemaVersion: 1`, a
`components` map keyed by exact coordinates, and records with `path`, `bytes`,
`sha256`, `role`, `provenance`, and `completeTextVerified`.

Each record points at a retained exact-byte copy under `texts/` and carries:

- the exact artifact SHA-256 from the retained inventory;
- the exact archive entry (e.g. `META-INF/androidx/.../LICENSE.txt`);
- the retained document SHA-256 (the same value as the text filename);
- a note that this is a full-text review of the reproduced bytes, not a
  compatibility or release decision.

A mechanical check called the repository scanner's `generate()` in memory with
this supplement and a **harness copy** of the retained Linux inventory. The
harness refreshes only `shared/build.gradle.kts`, which changed after the
retained run; the retained inventory itself is untouched. Result:

- `licenseTextRetained` becomes true for **22** components;
- coverage issues fall from **85** to **63** for that inventory;
- `noticeCoverageComplete` and `binaryRedistributionReady` remain **false**.

This is a schema/coverage projection, not a reproduction of the hosted run and
not a package result. Per-platform projection is 63 issues for each packet
(61 common + 2 platform-exclusive remain). The 67 union components remain
untouched. Output: `analysis/scanner-projection.json`,
`analysis/scanner_projection.py`.

**Lead action:** re-read the 22 provenance records and the exact bytes before
integrating the supplement into the tracked legal workflow. `completeTextVerified`
is a full-text assertion, not legal compatibility.

## 4. The shipped Linux package is still source-only

This is separate from the support packet and is important for distribution:

- The Linux `.deb` embeds `composeApp-desktop-1.0.0-*.jar` (SHA-256
  `9a321c827f7e2a336975fb3b11d36ec2223357983a55331c1bebd031fa58b112`).
- The legal index inside that shipped JAR is the **source-only** index:
  3 documents, **0 components**, and the single issue `Exact production runtime
  inventory has not been supplied.` Its SHA-256 is
  `9a6e2e6ad7a5682e84658b3fe5dbe3b09be00cd9d81312872582825c5632459f`.
- `packages.json` binds exactly that source-only index
  (`source.legalIndexSha256`), so the package provenance is internally
  consistent with what is shipped.
- The support packet's `legal/0/index.json` is a different, package-specific
  index (161 components, 85 issues). Its Linux SHA-256 is
  `53192287352213d5159ef829ece2d0d7ff8649c54317646caf3b4df37cc2638b`,
  which is **not** the hash in `packages.json`. It is bound only by the
  `support.zip` hash in `verification.json`.

Consequence: applying the supplement to the support packet would improve the
support index but would **not** change the legal resources actually embedded in
the `.deb`. A user installing the current draft would still see the source-only
legal set. Before distribution closure, the package build must embed the
package-specific legal index and notices (or ship and reference a complete
support legal packet), then bind the exact embedded hash in package provenance.

The `.deb` contains no separate non-runtime `LICENSE`, `NOTICE` or
`THIRD_PARTY_NOTICES` file at the package top level; the app JAR resources and
the bundled runtime are the only top-level legal locations.

## 5. JVM/native/JBR runtime coverage is separate

These are not covered by the Maven 85/89 count and must not be inferred from it.

### Bundled JVM runtime

- The Linux `.deb` contains a runtime image at `/opt/veyra/lib/runtime`:
  **398 files / 175,881,356 bytes**, of which **243 files / 1,926,794 bytes**
  are under `runtime/legal/`.
- `runtime/release` reports `JAVA_VERSION="21.0.13"` and a module list. It does
  not declare an implementor/vendor. The retained release file is at
  `analysis/packages/desktop-linux-x64/runtime-release.txt` (1,052 bytes,
  SHA-256 `781f9048527c347356a67c1a8eebadb779c8069a479cc924deb99a992ff756f6`).
- The Maven `org.jetbrains.runtime:jbr-api:1.5.0` is a small API JAR, not the
  bundled runtime; do not equate it with the runtime vendor or source.
- No runtime legal-file hashes, vendor source mapping or corresponding-source
  archive are in the support packet. This is a separate closure gap.

### Native components in the Maven set

Eight union components are flagged `containsNativeCode`:

- `androidx.sqlite:sqlite-bundled-jvm:2.6.2` and `net.java.dev.jna:jna:5.19.1`:
  their complete license texts are among the 22 covered records, but native
  build/source correspondence is still unverified.
- `org.jetbrains.skiko:skiko-awt-runtime-*` (3 platform runtime JARs): the Linux
  JAR contains `libskiko-linux-x64.so`; no license text is embedded; no source
  mapping is retained.
- `org.openani.mediamp:mediamp-mpv-runtime-*` (3 platform JARs): the Linux JAR
  contains `libavcodec`, `libavdevice`, `libavfilter`, `libavformat`, `libavutil`,
  `libmediampv`, `libmpv`, `libswresample`, `libswscale`. The Linux POM declares
  GPL-3.0; the macOS POMs declare LGPL-2.1-or-later plus Apache-2.0. No license
  text or source/build mapping is embedded. This is the largest native notice
  and corresponding-source gap.

### Separate FFmpeg helper

The packaged `composeApp` JAR also carries a separately built FFmpeg helper:

- FFmpeg **8.0.1**, LGPL-2.1-or-later, source commit
  `894da5ca7d742e4429ffb2af534fcda0103ef593`, origin
  `https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.0.1`.
- Tarball SHA-256 `679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1`;
  recipe SHA-256 `9e5ef49a42490b305e1f3f79388ecc6969ff88a7c2b27f1ac6770a3ab923e08f`;
  executable SHA-256
  `d9ab1be68cb9f2c7df9ba45f81e96dc08e381d41c05fbdf215b086b5e3d1ae69`
  (matches the packaged binary).
- Seven native license/notice files are shipped under
  `dev/veyra/desktop/native/linux-x64/licenses/` (Veyra GPL, FFmpeg
  GPLv2/GPLv3/LGPLv2.1/LGPLv3, CREDITS, LICENSE.md). Exact copies are retained
  under `native-texts/`.
- The shipped provenance declares seven `corresponding-source/*` files by hash,
  but those files are **not present** in the JAR or support packet. The
  provenance itself says `releaseReady: false` and lists "full application
  notices/source closure" as a remaining gate.
- Minor provenance-path issue: the declared map keys are
  `licenses/COPYING...` while the packaged paths are
  `licenses/ffmpeg/COPYING...`; content hashes match by basename. This is a
  binding/normalization defect, not missing text.

macOS DMGs were not mounted or listed in this pass. The macOS support packets
show the same 85 Maven gaps; their bundled runtime/native closure is not verified
here.

## 6. Exact checks still open

1. **67 POM-only components:** obtain the publisher's exact-version license and
   notice text (original project repository at the exact tag/commit or the
   publisher's canonical license file), map coordinate/version to that source,
   verify NOTICE attribution, and only then add reviewed supplements. The TSV
   names every coordinate; do not apply one Apache file to all of them.
2. **6 native components without text** (skiko runtime ×3, mediamp ×3): retain
   complete license texts and establish corresponding-source/build material for
   the actual packaged binaries. Resolve the mediamp Linux GPL-3.0 vs macOS
   LGPL/Apache POM difference against the exact artifacts.
3. **JDK runtime:** identify the runtime vendor/source, bind hashes for the 243
   `runtime/legal` files, and establish corresponding-source obligations. Do not
   infer this from `jbr-api` or the host Temurin JDK.
4. **FFmpeg helper:** archive the declared `corresponding-source/*` files
   alongside the release/support evidence; fix the `licenses/` path keys.
5. **Package embedding:** generate the package-specific legal index and notices
   into the shipped `composeApp` resources; verify the installed `.deb`/DMG
   contains them and bind the exact hash in `packages.json`.
6. **macOS byte verification:** the two platform-exclusive runtime JARs and
   three POMs per macOS packet are absent locally; verify them on a macOS or
   artifact-fetching host before claiming byte-level coverage.
7. **Lead/legal review:** re-check the 22 draft records and decide what
   `completeTextVerified` means for the project. No compatibility, notice
   closure, corresponding-source closure or release acceptance is claimed here.

## 7. Result and limits

Result: exact 89-component union built; 22 components covered by seven complete
artifact-embedded license texts (23 draft supplement records); 67 components
remain POM-only; mechanical scanner projection is 63 issues per platform and
still `noticeCoverageComplete=false` / `binaryRedistributionReady=false`. The
Linux `.deb` is confirmed to ship the source-only 0-component legal index, so
package embedding remains a release blocker.

No builds, Gradle, native execution, package installation/execution, network
source retrieval, global cache scan, signing or publication occurred. No
retained evidence was modified. The macOS DMGs were not inspected beyond their
retained hashes and support packets. This is not legal advice.

SHA receipt: `.work/verification/task-b-notice-coverage/receipt.json` and
`SHA256SUMS`. Re-run helpers: `analysis/extract_candidates.py`,
`analysis/build_union.py`, `analysis/scanner_projection.py`,
`analysis/extract_package_legal.py`, `analysis/make_receipt.py`.
