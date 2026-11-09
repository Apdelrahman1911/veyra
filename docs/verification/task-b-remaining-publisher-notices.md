# Task B — remaining publisher notices for eight unresolved coordinates

Updated 2026-11-09 UTC. **Draft evidence collection, not legal advice, not a
compatibility decision and not release acceptance.** This is one bounded
research pass over the eight exact coordinates left unresolved by
[`task-b-publisher-notice-coverage.md`](task-b-publisher-notice-coverage.md).
It reuses the retained exact artifacts and the prior 81-coordinate/88-record
registry instead of repeating covered research. It did not build, run Gradle,
execute a package, install anything, scan a global cache, retrieve media/live
sources, use credentials, push, dispatch a workflow or message the owner.

Checkout: `/root/.t3/worktrees/Veyra/t3-b705873a`, branch
`t3/task-a-ui-recovery-integration`, HEAD
`0bf9aceda974b028c553f5926e5e5c5c1fabc74d` (read-only). Only this new document
and the new ignored evidence directory
`.work/verification/task-b-remaining-publisher-notices/` were added.

## 1. Result

**4 of the 8** remaining coordinates now have complete retained license terms
and a verified exact-version source mapping. **4 remain truthfully
unresolved.** The prior pass covered 59/67; this pass raises the publisher
supplement to **63/67**, with four unresolved.

| coordinate | result | exact source mapping | retained terms |
|---|---|---|---|
| `androidx.arch.core:core-common:2.2.0` | **resolved** | AOSP `platform/frameworks/support` release branch `androidx-arch-core-release`, commit `d00300b06c00dbf348f871980400948cdf7b10dc`; `libraryversions.toml` declares `ARCH_CORE = "2.2.0"` | `LICENSE.txt`, 10,175 B, `809fa1ed…` |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop:2.9.6` | **resolved** | Compose Multiplatform release tag `v1.9.3`, peeled commit `278178153c9189353ca7bb03f0904ddd9d995ce3`; CMP 1.9.3 release notes map lifecycle `2.9.6` | `LICENSE.txt`, 10,175 B, `809fa1ed…` |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop:1.3.6` | **resolved** | same CMP `v1.9.3` tag/commit; release notes map savedstate `1.3.6` | `LICENSE.txt`, 10,175 B, `809fa1ed…` |
| `org.jetbrains.compose.material3:material3-desktop:1.9.0` | **resolved** | Compose Multiplatform release tag `v1.9.1`, peeled commit `6ba48befdf216eca527ea4209ec37bdf2ed838c9`; release notes map material3 `1.9.0` | `LICENSE.txt`, 10,175 B, `809fa1ed…` |
| `org.jetbrains.kotlinx:atomicfu-jvm:0.23.2` | unresolved | exact tag exists, but only a 596-byte Apache header and a 487-byte NOTICE | none complete |
| `org.openani.mediamp:mediamp-mpv-runtime-linux-x64:0.5.3` | unresolved | exact tag exists; project Apache license does not cover bundled natives | project license only, not promoted |
| `org.openani.mediamp:mediamp-mpv-runtime-macos-arm64:0.5.3` | unresolved | exact tag exists; macOS runtime JAR not retained/inspected | project license only, not promoted |
| `org.openani.mediamp:mediamp-mpv-runtime-macos-x64:0.5.3` | unresolved | same | project license only, not promoted |

All four records use the complete Apache-2.0 text (one retained 10,175-byte
file), byte-identical to the already-retained AndroidX artifact-embedded text
at `third_party/notices/texts/809fa1ed…txt`. They are not generic Apache terms
assigned by similarity: each record has its own exact source URL, commit and
version mapping.

## 2. Exact-version and source-artifact correspondence

The method improves on the prior pass in two ways: it resolves releases that
do not have a version-named tag (core-common, the two compatibility stubs, and
the material3 release line) and it checks the published **sources JAR bytes**
against the claimed source tree. A POM declaration alone was not accepted.

### core-common 2.2.0

- AndroidX publishes from AOSP. `git ls-remote --tags` found no exact-version
  tag, so the POM/release page alone was not enough. The AOSP release branch
  `androidx-arch-core-release` resolves to commit
  `d00300b06c00dbf348f871980400948cdf7b10dc`; its `libraryversions.toml` says
  `ARCH_CORE = "2.2.0"`.
- The exact published `core-common-2.2.0-sources.jar` (Google Maven, 5,684 B,
  SHA-256 `563d4308…`) contains three Java files whose bytes match the release
  commit exactly:
  `SafeIterableMap.java` `b515f3b2…`, `FastSafeIterableMap.java` `8d53462b…`,
  `Function.java` `36414a39…`.
- The exact runtime JAR retained in the Gradle cache is
  `core-common-2.2.0.jar`, 11,657 B, SHA-256 `65308a06…`; it matches the
  published Gradle module metadata. It contains no license entry, so the
  release-branch `LICENSE.txt` is the retained term.
- **Caveat:** this is a release-branch commit, not a tag. The commit is
  immutable; the branch ref is mutable. The source-JAR byte match establishes
  source correspondence, not a reproduced build attestation.

### lifecycle 2.9.6 and savedstate 1.3.6

- CMP 1.9.3's release notes explicitly list
  `org.jetbrains.androidx.lifecycle:lifecycle-*:2.9.6` and
  `org.jetbrains.androidx.savedstate:savedstate*:1.3.6`.
- The tags resolve to CMP `v1.9.3` at commit `2781781…`. The published
  `-sources.jar` for each coordinate is a 1,141-byte compatibility stub whose
  `commonMain/EmptyFile.kt` is byte-identical (`e38a9459…`) to the tag's
  compatibility-stub source.
- The binary JARs are also empty compatibility stubs (452 B and 438 B). They
  redirect to `androidx.lifecycle:…:2.9.4` and `androidx.savedstate:…:1.3.3`
  respectively; those are separate coordinates already covered by
  artifact-embedded license text. The retained CMP tag license applies to the
  stub artifact's own source, not to the redirected implementation, which has
  its own record.

### material3 1.9.0

- CMP 1.9.1's release notes explicitly list
  `org.jetbrains.compose.material3:material3*:1.9.0`. There is no
  material3-versioned tag; `v1.9.1` is the CMP release that ships it.
- The exact `material3-desktop-1.9.0-sources.jar` (815,807 B,
  SHA-256 `343fffb5…`) contains source entries that match tag `v1.9.1` at
  commit `6ba48be…` byte-for-byte:
  `ContentColor.kt` `1c29981e…`, `ExperimentalMaterial3Api.kt` `c1ea7f8c…`,
  `TimeFormat.kt` `e7bcbe1f…`.
- The runtime JAR is 4,436,970 B, SHA-256 `78f0787a…`; it has no embedded
  license entry.

## 3. What changed from the prior pass

- **Four coordinates moved from unresolved to resolved**: core-common,
  lifecycle 2.9.6, savedstate 1.3.6 and material3 1.9.0.
- The prior pass's "no exact public tag" conclusion was correct for the
  version-named tag searches, but not sufficient: the AOSP release branch and
  the CMP release-note/version mapping plus source-JAR byte matching provide
  exact source correspondence without a version-named tag.
- The prior pass's "atomicfu LICENSE is only a 596-byte header" conclusion is
  unchanged. A new inspection of the tag tree found `license/NOTICE.txt`
  (487 B) but no complete license file; a generic Apache text was not
  substituted.
- The prior pass's mediamp finding is unchanged and now has a stronger
  artifact-level negative: the exact Linux runtime JAR (29,657,424 B,
  SHA-256 `d1169c75…`) contains 52 bundled native libraries and **no**
  license/notice entry. The macOS runtime JARs were not downloaded; the prior
  packet did not retain them. All three remain unresolved.

## 4. Mechanical validation and Linux projection

`analysis/build_records.py` recomputed every artifact/source hash and asserted
all source matches. `analysis/scanner_projection.py` called
`scripts/package-notices.py`'s `generate()` in memory with the three existing
supplements and a **harness copy** of the retained Linux inventory. The harness
refreshes only `composeApp/build.gradle.kts`, which changed after the retained
packet (retained 9,708 B/`8e77fdaf…`; current 13,921 B/`b59d2c92…`). The
retained packet was not modified; the component/artifact set is unchanged.

- supplement: 4 components, 4 license records, all `completeTextVerified=true`;
- `licenseTextRetained` components: **83** (79 prior + 4 new);
- Linux coverage issues: **6 → 2**;
- the only two remaining Linux issues are `kotlinx-atomicfu-jvm:0.23.2` and
  `mediamp-mpv-runtime-linux-x64:0.5.3`;
- `noticeCoverageComplete=false` and `binaryRedistributionReady=false` remain;
- this is a schema/coverage projection, not a hosted-run reproduction, package
  embedding result or legal decision.

## 5. Explicit residuals

1. **No build/signing attestation.** The source-JAR byte matches establish a
   strong source-to-source-artifact mapping. They do not prove the shipped
   binary was built from that commit by a reproducible build, nor do they
   replace an SBOM or signed provenance.
2. **Compatibility-stub nuance.** The lifecycle/savedstate artifacts are empty
   stubs. Their own tag license is retained, but the code they redirect to is
   a different AndroidX coordinate with its own already-retained license. Do
   not treat the stub record as covering the redirect target.
3. **Core-common branch, not tag.** The release-branch commit is immutable;
   the branch ref is mutable and was resolved on 2026-11-09. Re-resolve it if
   the evidence is reused.
4. **Atomicfu remains open.** The exact tag has a NOTICE but no complete
   license terms. No generic Apache text was assigned.
5. **Native MPV/FFmpeg remains open.** The exact mediamp Linux JAR bundles
   MPV/FFmpeg/other native libraries; the project Apache license and POM
   declarations are not a complete native/transitive closure. macOS runtime
   JARs were not downloaded and no native terms/source mapping were verified.
6. **JDK runtime is separate.** `jbr-api` is not the bundled runtime; the
   runtime's 243 legal files and corresponding-source obligations remain as
   recorded in the prior reports.
7. **No legal compatibility or redistribution readiness decision.** Nothing
   here asserts that any license is compatible with Veyra's GPL-3.0-or-later
   distribution or that notice obligations are satisfied.

## 6. Evidence

- `analysis/coordinate-coverage.tsv` — all eight rows, resolved and unresolved.
- `third_party/notices/desktop-reviewed.json` — the prior 81-coordinate
  registry, read-only; no entry was modified or overwritten.
- `analysis/sources.json` — tag/branch verification, release-note mapping,
  license and source URLs, artifact/source hashes, source-match assertions,
  evidence-only items and unresolved reasons.
- `analysis/source-match/` — the exact source bytes matched against the
  published sources JARs.
- `analysis/artifacts/` — the retained exact-version sources JARs.
- `analysis/evidence-only.json` — atomicfu header/NOTICE and mediamp project
  license, explicitly **not** promoted.
- `analysis/validation.json`, `analysis/scanner-projection.json` — mechanical
  schema/coverage results.
- `draft-supplement.json` — the four records for integration review.
- `receipt.json`, `SHA256SUMS` — counts, unresolved list and hashes.

No prior report, retained packet, legal preview or other-owner file was
modified. `sha256sum -c .work/verification/task-b-remaining-publisher-notices/SHA256SUMS`
passes from the repository root.

## 7. Result

**63/67** prior missing union coordinates now have complete retained terms and
an exact-version source mapping; **4/67** remain unresolved truthfully. The
Linux projection falls from six to two issues. Source-binary reproducibility,
the redirected-stub implementation licenses, atomicfu, mediamp native closure,
JDK runtime coverage, package embedding and lead/legal review remain open.

PR https://github.com/Apdelrahman1911/veyra/pull/27, head 0bf9aceda974b028c553f5926e5e5c5c1fabc74d, checks and reviews unverified.
