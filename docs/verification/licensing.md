# Licensing verification

## kmpYT MIT adoption

On 2026-11-06 the owner of `Apdelrahman1911/kmpYT` explicitly authorized MIT
adoption and confirmed licensing authority, subject to checking contributions and
dependencies. Upstream working commit **`e8754184b37b8c30fa89956908f09cda7f56732e`**
adds the official MIT file, a source/dependency audit, README notice and Maven POM
license metadata; there are no runtime-code changes in that commit. It is now
published in [kmpYT PR #1](https://github.com/Apdelrahman1911/kmpYT/pull/1), stacked
with separately reviewed repository-scoped CI hygiene (`293f5dfa6ddb8336a9a2a1f924c31431aa8dbf3f`).
Merge and application final-pin update remain, not owner permission.

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
audit; reviewed merge/final pinning remains a delivery step.

The public [license/provenance check](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42177267667/job/127758488399)
passed at that PR head. This is licensing evidence only. Initial JVM checks exposed
a missing runner FFmpeg prerequisite, and native runtime checks deliberately failed
before unaudited emulator/simulator launches; they are not labeled passing. CI
prerequisites are being corrected within the repository boundary. Android library
compilation/lint separately passed, not instrumentation or Veyra verification.

## GPL-3.0-or-later compatibility

Veyra's newly authored code uses GPL-3.0-or-later. The GNU license list explicitly
identifies the standard [MIT/Expat license as GPL-compatible](https://www.gnu.org/licenses/license-list.html#Expat),
and [Apache-2.0 as GPLv3-compatible](https://www.gnu.org/licenses/license-list.html#apache2).
kmpYT remains MIT within the combined distribution; keep its notice verbatim.
Veyra's GPL terms do not erase dependency notices or their original licenses.

## Remaining binary-distribution obligations (not kmpYT permission questions)

- Update Veyra's pin to the reviewed/merged licensed revision, and include the
  corresponding MIT and third-party notices in shipped resources/packages.
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
