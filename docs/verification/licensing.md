# Licensing verification

## kmpYT MIT adoption

On 2026-11-06 the owner of `Apdelrahman1911/kmpYT` explicitly authorized MIT
adoption and confirmed licensing authority, subject to checking contributions and
dependencies. Upstream license commit **`e8754184b37b8c30fa89956908f09cda7f56732e`**
adds the official MIT file, a source/dependency audit, README notice and Maven POM
license metadata; there are no runtime-code changes in that commit.
[kmpYT PR #1](https://github.com/Apdelrahman1911/kmpYT/pull/1) is **merged** on the
default branch at **`45f7077880c37c20216d767b262bb5757b3010bf`**. The GitHub license
endpoint independently identifies the official default-branch `LICENSE` as MIT.
The application final behavioral pin remains a delivery step, not owner permission.

Lead and independent kmpYT reviewer checked:
- All 15 baseline commits at `533562f781304338f72b27d32cfe5fb7a83dc3a8` are authored
  by **abdelrahman fahmy**; GitHub lists only owner **Apdelrahman1911** (15 contributions).
  No co-author/sign-off attribution was found. The 2026 copyright year matches the
  initial commit. This is a provenance audit, not independent proof of authorship
  outside the repository; authorization covers its ClearMedia-derived own code.
- The 151 baseline tracked paths/source attribution references disclosed no bundled
  third-party extractor implementation. The pinned yt-dlp public-profile reference
  remains; its referenced tag uses the Unlicense. No service media is licensed by MIT.
- Fixture CC0 notices are unchanged. The wrapper JAR's Apache-2.0 `META-INF/LICENSE`
  is unchanged; that JAR has no separate NOTICE entry.
- The MIT grant and disclaimer match SPDX's standard MIT text after whitespace
  normalization. Both attribution and the complete permission/disclaimer are retained.
- Published runtime/dependency-family POM terms were reviewed (142 cached records,
  including transitive/platform/build/test variants). This is **not** an exact final
  binary SBOM. Kotlin/Ktor/kotlinx/AndroidX use Apache-2.0; SLF4J uses MIT; inherited
  POM terms were considered. JUnit EPL-1.0 is test-only and must not ship as a GPL
  application runtime dependency. Detailed upstream audit identifies versions/scopes.

Evidence is retained under ignored `.work/licensing/`; the publishable upstream
`THIRD_PARTY_NOTICES.md` carries the audit and references. Independent review found
no merge-blocking licensing defect. The former unanswered-permission blocker is
resolved by the owner grant, committed official license and completed baseline
audit and official merge. Final tested behavioral pinning remains a delivery step.

The exact merged PR head `2cf2cc68b4e079fb3432a0f2f7b501be3943e5cd` passed its
[license/provenance check](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42179440862/job/127765886434)
and Linux JVM check. Android library compilation/lint passed; instrumentation
failed its explicit preflight before an unaudited emulator launch. Windows
executed391 tests with3 Chromium failures and1 skip, under diagnosis; Apple jobs
were queued. **Those native checks were not represented as passing.**

The [lead merge review](https://github.com/Apdelrahman1911/kmpYT/pull/1#issuecomment-6679665116)
records the independent licensing review and every check limitation. A normal
head-matched merge used no admin/override; main had no protected status contexts
or rulesets, and no policy/check was disabled. The separately reviewed CI changes
remove unauthorized global tool/config/cache writes and retain fail-closed native
gates. Merging a valid license and safer tooling is not a platform/release approval.

## GPL-3.0-or-later compatibility

Veyra's newly authored code uses GPL-3.0-or-later. The GNU license list explicitly
identifies the standard [MIT/Expat license as GPL-compatible](https://www.gnu.org/licenses/license-list.html#Expat),
and [Apache-2.0 as GPLv3-compatible](https://www.gnu.org/licenses/license-list.html#apache2).
kmpYT remains MIT within the combined distribution; keep its notice verbatim.
Veyra's GPL terms do not erase dependency notices or their original licenses.

## Remaining binary-distribution obligations (not kmpYT permission questions)

- Use the final reviewed/merged behavioral revision as Veyra's immutable pin. The
  current development pin already includes the MIT grant; bundled legal resources
  preserve it verbatim. Revalidate resource/pin hashes after each upstream update.
- Generate a resolved, per-platform runtime inventory. Exclude test-only JUnit and
  test tools from production packages; preserve all actual copyright/license/NOTICE files.
- For selected libmpv/FFmpeg/native closures, satisfy their precise GPL/LGPL build
  terms, corresponding-source and relinking requirements as applicable. A probe
  on an installed runtime is not a redistribution audit.
- Retain unmodified IBM Plex Sans Arabic OFL/reserved-name notices, Mozilla CA
  bundle MPL/provenance, and any bundled Node/Chromium/JDK/runtime notices and source
  obligations. Do not assume another platform's bundle has the same closure.
- Do not ship downloaded creator media or private listening metadata as application
  assets. External metadata licenses never transfer to a different kmpYT recording.

No binary is declared distribution-ready until its complete inventory and source
closure are verified. App-store signing/notarization is separately matrixed.
