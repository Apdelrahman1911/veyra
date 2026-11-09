# Task B — publisher license/NOTICE bytes for the 67-component gap

Updated 2026-11-09 UTC. **Draft evidence collection, not legal advice, not a
compatibility decision and not release acceptance.** This pass fetched public
upstream license/NOTICE files for the exact release tags of the 67
component/version coordinates that the prior packet scan left without complete
embedded license text. It did not build, run Gradle, execute a package, install
anything, scan a global cache, retrieve media/live sources, or use credentials.

Checkout under research: `/root/.t3/worktrees/Veyra/t3-b705873a`, branch
`t3/task-a-ui-recovery-integration`, HEAD
`fb9a0b8a774a0e5633bc061651ff15a8d4f48c05` (read-only). Prior report and
retained packets were not modified. The prior exact union is
[`task-b-notice-coverage.md`](task-b-notice-coverage.md) and
`.work/verification/task-b-notice-coverage/analysis/missing-union.tsv`.

Owned evidence: `.work/verification/task-b-publisher-notices/` (new, ignored).
It contains the retained texts, download headers, tag-verification records,
coordinate map, draft supplement, validation, scanner projection and SHA
receipt.

## 1. Method and bounds

1. Each Maven family/version was mapped to a public upstream repository and a
   release tag. `git ls-remote` was used to resolve the tag reference and its
   peeled commit; the commit was compared with the expected commit before any
   download. All 15 family tag mappings verified.
2. Every file was fetched from
   `https://raw.githubusercontent.com/<repo>/<commit>/<path>` — the immutable
   commit URL, not a moving branch. The tag URL is recorded alongside it.
3. Downloads used `curl` with `--fail`, a 60-second timeout and a
   **4 MiB per-file bound**. Response status (all `HTTP/2 200`), content type,
   ETag/Last-Modified headers, bytes and SHA-256 are recorded in
   `analysis/sources.json` and `analysis/headers/`.
4. Only files whose bytes matched a recomputed SHA-256 were retained. Text
   filenames are the SHA-256 of their contents.
5. No POM declaration or Apache URL was used as a license. A POM declaration is
   still a declaration only.

Result: **59 of the 67** exact coordinates now have an exact-tag upstream
license text retained and mapped; **8 remain unresolved**. The supplement has
**65 records** (59 license records plus 6 NOTICE records). Fifteen unique text
files are retained: 13 distinct license payloads plus 2 NOTICE files, from 17
fetched upstream files (the extra license copies are byte-identical duplicates
that collapse by SHA).

## 2. Covered families and exact upstream mapping

| family / version | upstream tag | peeled commit | retained license SHA-256 (bytes) | coords | retained NOTICE |
|---|---|---|---:|---:|---|
| Ktor 3.2.3 | `3.2.3` | `dfbeb283641ddb74fd90c5fcbc9f0b12aa8dbaf1` | `883b820629750db1…` (11,351) | 14 | none at tag |
| Coil 3.3.0 | `3.3.0` | `1731511f0c12ef556db7822a91b6ea03fa3f95a1` | `5ade6484903351f6…` (11,347) | 6 | none at tag |
| Okio 3.15.0 | `parent-3.15.0` | `2af3bb8caecd89fb168388d1b3974df415536a5e` | `cfc7749b96f63bd3…` (11,358) | 1 | none at tag |
| Compose Multiplatform 1.9.3 | `v1.9.3` | `110ea3937e6edf1b0290d5a132dab5d520b80372` | `f9a85c61ba4fe8ba…` (11,394) | 18 | none at tag |
| Kotlin 2.1.21 | `v2.1.21` | `f59375aebc65b867f1d9f50038fc8878f3f686d3` | `cfc7749b96f63bd3…` (11,358) | 2 | none at tag |
| Kotlin 2.2.21 | `v2.2.21` | `2146684dcba708e5a304758b41a9e4ec9c7eff71` | `cfc7749b96f63bd3…` (11,358) | 1 | none at tag |
| kotlinx.coroutines 1.10.2 | `1.10.2` | `5f8900478a8e20c073145b1608fbc71fe3d7378b` | `b1febe6399dffb10…` (11,398) | 3 | none at tag |
| kotlinx-datetime 0.7.1 | `v0.7.1` | `1ee0daac9fc4c22a7da33b830c7cc60e625c49bb` | `cfc7749b96f63bd3…` (11,358) | 1 | none at tag |
| kotlinx-io 0.7.0 | `0.7.0` | `0329b9d0ebbf614634f48d9f69b1e14350d4d7e2` | `9e29096c161f416b…` (11,358) | 2 | `a126623d2c0c1ca2…` (486) |
| kotlinx.serialization 1.9.0 | `v1.9.0` | `16306f4c97e21e4d134bf1bb95fc20a61927f6b0` | `c5accbbd8546e94c…` (11,356) | 2 | none at tag |
| Java annotations 23.0.0 | `23.0.0` | `5d06c0c48e0a4fe44b2bc0d5d3e8b113c7a57de2` | `8c1e966c7855fb54…` (11,352) | 1 | none at tag |
| JSpecify 1.0.0 | `v1.0.0` | `b2a10e14bb81c1b7cb6e488c112fe55cb2218d7d` | `cfc7749b96f63bd3…` (11,358) | 1 | none at tag |
| dbus-java 5.2.2 | `dbus-java-parent-5.2.2` | `37d6c62403e0d4a30aab8ba044784eefa26d7260` | `d14b6f26eaa5b4cf…` (1,109, MIT) | 2 | none at tag |
| Skiko 0.9.22.2 | `v0.9.22.2` | `2f621572270c28052549ff4678b762c3fea09fa5` | `c71d239df91726fc…` (11,357) | 4 | `35dd077c24768b53…` (197) |
| JBR API 1.5.0 | `v1.5.0` | `ed86bdce3817abf63acdb17e5297d9d63111e20e` | `58d1e17ffe5109a7…` (11,357) | 1 | none at tag |

All license files contain complete terms. Fourteen are full Apache-2.0 terms
(with `TERMS AND CONDITIONS…` and `END OF TERMS AND CONDITIONS`); dbus-java is
the full MIT text. The shared SHA values across some JetBrains/Kotlinx families
mean the upstream files are byte-identical, not that one file was applied
globally. Each supplement record still carries its own repository/tag/commit
provenance.

Exact per-coordinate mapping (all 67 rows, covered and unresolved) is in
`.work/verification/task-b-publisher-notices/analysis/coordinate-coverage.tsv`.
Machine-readable sources and tag verification are in
`analysis/sources.json`; the draft supplement is
`.work/verification/task-b-publisher-notices/draft-supplement.json`.

## 3. Unresolved coordinates (8)

These are deliberately not promoted. A POM declaration, a generic Apache file,
or an incomplete project file was not substituted.

| coordinate | reason |
|---|---|
| `androidx.arch.core:core-common:2.2.0` | AndroidX publishes from AOSP; no public exact-version release tag was found, and the retained artifact has no embedded license. |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop:2.9.6` | No public exact-version tag found in the JetBrains Compose core fork (observed tags stop at 2.9.4/1.3.3); only an Apache POM declaration exists. |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop:1.3.6` | Same: no public exact-version tag found; POM declaration only. |
| `org.jetbrains.compose.material3:material3-desktop:1.9.0` | Material3 artifact version 1.9.0 does not match a verified source tag; the POM points at the legacy compose-jb repository and the core fork tags carry AndroidX material3 versions (e.g. 1.4.0). |
| `org.jetbrains.kotlinx:atomicfu-jvm:0.23.2` | Exact tag `0.23.2` exists, but its `LICENSE.txt` is only a 596-byte Apache-2.0 source-header notice, not the complete terms. Retained as evidence (`f4def61b…`), not promoted. |
| `org.openani.mediamp:mediamp-mpv-runtime-linux-x64:0.5.3` | Exact tag `v0.5.3` exists and the project Apache license is retained as evidence (`43070e2d…`), but the runtime JAR bundles MPV/FFmpeg native libraries and the Linux POM declares GPL-3.0. Project license alone is not a complete license for this artifact. |
| `org.openani.mediamp:mediamp-mpv-runtime-macos-arm64:0.5.3` | Same native gap; macOS POM declares LGPL-2.1-or-later/Apache, so the project Apache file is not a complete artifact license. |
| `org.openani.mediamp:mediamp-mpv-runtime-macos-x64:0.5.3` | Same native gap as the arm64 macOS runtime. |

## 4. Mechanical schema/coverage projection

A scanner-schema check called `scripts/package-notices.py`'s `generate()` in
memory with the prior 22-component supplement plus this supplement. It used a
**harness copy** of the retained Linux inventory
(`analysis/scanner-harness-inventory.json`); the retained packet was untouched.
One build-input fingerprint (`shared/build.gradle.kts`, retained
1,983 bytes / `7c3a3861…`, current 2,434 bytes / `a0004de1…`) was refreshed in
the harness only. This is a projection, not a hosted-run reproduction.

- `licenseTextRetained` is true for **79** components (22 prior + 57 of this
  pass's 59 that are present in the Linux packet; the two macOS-exclusive Skiko
  runtimes are not in the Linux inventory).
- Linux coverage issues fall from **85** to **6**.
- The six remaining Linux issues are exactly the five common unresolved
  coordinates plus the Linux mediamp runtime: `androidx.arch.core`,
  `androidx.lifecycle` 2.9.6, `androidx.savedstate` 1.3.6,
  `compose.material3` 1.9.0, `kotlinx.atomicfu` 0.23.2 and
  `mediamp-mpv-runtime-linux-x64`.
- `noticeCoverageComplete=false` and `binaryRedistributionReady=false` remain.
  The supplement is not integrated into the tracked legal workflow, and the
  package-embedding defect recorded in the prior report was not re-tested here.

The same common five plus each platform's own mediamp runtime imply six
unresolved per macOS packet as well; only the Linux projection was executed, so
that per-macOS count is derived, not re-run.

## 5. Explicit residuals

1. **Source-to-binary correspondence is not verified.** The mapping is
   coordinate/version → public release tag → peeled commit → license bytes.
   There is no reproduced build, signed SBOM, or artifact-to-commit attestation
   for the exact retained JARs. The license text is upstream-exact at the
   release tag; that is weaker than proving the shipped binary was built from
   that commit.
2. **NOTICE coverage is minimal.** Only kotlinx-io and Skiko published a
   NOTICE at the inspected tag paths. For the other families, no `NOTICE`,
   `NOTICE.txt` or `license/NOTICE` existed at the tag root. Absence at those
   paths is not proof that no attribution exists elsewhere in the repository or
   in transitive dependencies.
3. **Native components remain open.** The Skiko records are the project
   Apache-2.0 license plus its AOSP-adaptation NOTICE. Skiko's runtime JARs bundle
   native Skia and other third-party code; the Skia/third-party notices,
   versions and corresponding source were not established. All three mediamp
   runtimes stay unresolved for the MPV/FFmpeg native bundle, including the
   Linux GPL-3.0 vs macOS LGPL-2.1-or-later/Apache POM difference.
4. **JDK runtime is separate.** `jbr-api:1.5.0` is only a small API JAR. The
   bundled runtime's 243 `runtime/legal` files, vendor/source mapping and
   corresponding-source obligations are not covered by this pass and remain as
   recorded in the prior report.
5. **AndroidX/JetBrains port gaps remain.** `androidx.arch.core` 2.2.0 and the
   two JetBrains AndroidX port artifacts have no verified exact source tag in
   this pass; `material3-desktop:1.9.0` and `atomicfu` remain as explained
   above.
6. **No legal compatibility or release decision.** Nothing here asserts that a
   license is compatible with Veyra's GPL-3.0-or-later distribution, that notice
   obligations are satisfied, or that any package may ship. `completeTextVerified`
   in the supplement means the full upstream text was reviewed against the
   retained bytes, not that legal review or binary correspondence is complete.

## 6. Verification and evidence

- `analysis/validation.json`: **ok**, 0 errors; 67 prior missing, 59 covered,
  8 unresolved, 65 records, 15 retained texts, 17 upstream files, 2
  evidence-only files.
- `analysis/coordinate-coverage.tsv`: one row per exact coordinate with family,
  status, tag, commit, license SHA/bytes, notice SHA and mapping/reason.
- `analysis/sources.json`: per-file URL, HTTP status, headers, bytes, SHA-256,
  tag verification and mapping statement.
- `analysis/scanner-projection.json`: projection details and remaining issues.
- `SHA256SUMS` and `receipt.json`: hashes and summary. `sha256sum -c` passes.
- `analysis/collect.py`, `collect_evidence.py`, `build_union_check.py`,
  `scanner_projection.py`, `make_receipt.py`: reproducible helpers. They use
  only the owned `.work` directory and public raw GitHub/Maven metadata.

No prior report, retained packet, legal preview or other-owner file was
modified. The only tracked-tree addition from this pass is this new document;
the evidence directory is ignored `.work`.

## 7. Result

**59/67** missing union coordinates now have exact-tag upstream license bytes
retained and mapped; **8/67** remain truthfully unresolved. Six additional
NOTICE records are retained. The Linux scanner projection falls from 85 to 6
coverage issues, but `noticeCoverageComplete` and `binaryRedistributionReady`
remain false. Source-binary correspondence, complete NOTICE/native/JDK closure,
package embedding and lead/legal review remain open.
