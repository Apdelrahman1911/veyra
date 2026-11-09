# Task B — desktop package legal embedding

Updated 2026-11-09 UTC. **Implementation, authored checks and focused JAR resource
verification; no installer or release acceptance.** The initial implementation
pass used PR27 base `1bf647cd2f293c18140a1a55243aeb89099920d3` with uncommitted
changes and performed no Gradle/native/package execution. Section 8 records the
subsequent lead review and Gradle/JAR proof; it supersedes the initial execution
limits below only within that scope.

## 1. Defect being addressed

The retained Linux `.deb` embedded the source-only legal index in
`composeApp-desktop-1.0.0-*.jar`: `0` production components, `3` documents and
the issue `Exact production runtime inventory has not been supplied.` The
separate support packet embedded a different inventory-backed index with `161`
components and `85` issues. `packages.json` hashed only the tracked source-tree
index, so package provenance was internally consistent with the wrong bytes and
did not prove what the installer shipped.

Cheap byte check performed against the retained artifacts (no build/execution):

- extracted app JAR SHA-256 `9a321c827f7e2a336975fb3b11d36ec2223357983a55331c1bebd031fa58b112`;
- embedded index SHA-256 `9a6e2e6ad7a5682e84658b3fe5dbe3b09be00cd9d81312872582825c5632459f`,
  `0` components, `3` documents, `1` coverage issue;
- support `legal/0/index.json` SHA-256 `53192287352213d5159ef829ece2d0d7ff8649c54317646caf3b4df37cc2638b`,
  `161` components, `85` coverage issues;
- both indexes are distinct. Exact machine-readable evidence:
  `.work/verification/task-b-desktop-legal-embedding/retained-app-jar-check.json`.

The reviewed registry `third_party/notices/desktop-reviewed.json` supplies 81
exact coordinates, 88 records and 20 distinct retained texts. It does not make
either readiness flag true and does not resolve the six known Linux text gaps
(`androidx.arch.core`, the two JetBrains AndroidX ports, `material3-desktop`,
`kotlinx.atomicfu` 0.23.2 and the Linux mediamp runtime). Those issues must
remain visible in the generated index.

## 2. Implemented production flow

`scripts/package.sh` now performs, in order:

1. select the exact host desktop target through
   `scripts/desktop-package-legal.py platform`
   (`desktop-linux-x64`, `desktop-windows-x64`, `desktop-macos-arm64` or
   `desktop-macos-x64`);
2. build only the project runtime JARs required to resolve the inventory
   (`:shared:jvmJar`, which also produces the included kmpYT JVM JAR);
3. export the exact host runtime inventory with the existing
   `scripts/runtime-inventory.init.gradle` task
   (`:composeApp:exportProductionInventory`);
4. generate the legal inputs with
   `scripts/package-notices.py --inventory <exact inventory>
   --supplement third_party/notices/desktop-reviewed.json --output
   .work/verification/desktop-legal/<platform>`;
5. validate the generated directory with the bounded helper;
6. run the normal single `packageDeb`/`packageDmg`/`packageMsi` task with
   `-Pveyra.desktopLegalDir=<generated directory>`;
7. locate the exact `composeApp-desktop-*.jar` in the generated Compose
   application image and run `scripts/package-manifest.py` with
   `--app-jar`, `--desktop-legal-dir`, `--expected-legal-platform` and
   `--require-embedded-legal`.

No test suite, second application compilation or complete matrix is added to
the packaging path. The previously generated inventory and `packages.json` are
deleted before each run; the exporter and manifest are the only writers, so a
failed run cannot leave an older manifest that looks current. `package-notices.py` continues to report
`binaryRedistributionReady: false` and every open coverage issue.

## 3. Gradle replacement contract

Research was read-only: the installed `compose-gradle-plugin` 1.9.3 classes and
the Gradle 8.13 `CopySpec`/`ProcessResources` classes/API docs were inspected;
no Gradle execution occurred.

`composeApp/build.gradle.kts` gains a production-only Gradle property,
`veyra.desktopLegalDir`. Ordinary source/developer builds do not set it and keep
the tracked source-only preview unchanged.

When set:

- the directory must exist, be a real directory inside the repository, contain
  `index.json`, contain no symlinks/nonregular files, and stay within bounded
  file count/size limits; escaping paths are rejected at configuration time;
- the generated directory is declared as an explicit Gradle input;
- `desktopProcessResources` excludes the complete original subtree at
  `composeResources/dev.veyra.generated.resources/files/legal/**`;
- a nested `from(generated) { into(...) }` spec copies the replacement into the
  same exact prefix. Gradle applies inherited include/exclude patterns to each
  spec's source-relative paths; the replacement spec's source root is the
  generated legal directory, so its `LICENSE`/`index.json`/`notices/*` entries
  are not matched by the prefixed original exclusion and are retained;
- `doFirst` deletes the previous output legal subtree so incremental output
  cannot keep a stale original entry;
- the prefix matches the retained app JAR and the current
  `desktopProcessResources` layout: `composeResources/dev.veyra.generated.resources/files/legal/`.

The prefix constant is asserted by the authored helper tests and independently
by `package-manifest.py`; it is not derived from an arbitrary scan.

## 4. Package-manifest binding

`scripts/package-manifest.py` now accepts:

- `--app-jar` — exact application-image JAR consumed by the installer task;
- `--desktop-legal-dir` — generated directory to compare byte-for-byte;
- `--expected-legal-platform` — exact expected index platform;
- `--require-embedded-legal` — production gate; fails without an app JAR.

Verification reads only the exact resource prefix in the actual app JAR. It
rejects duplicate/case-colliding entries, linked/special/encrypted entries,
unsafe names (`..`, absolute, backslash, colon), oversized entries, missing
documents, extra stale entries and malformed/duplicate-key JSON. It requires:

- generator `scripts/package-notices.py`, schema 1, `testOnly=false`,
  `binaryRedistributionReady=false`;
- the same kmpYT pin as the package, exactly one expected desktop platform, a
  nonempty component set and retained documents;
- `generatedFiles`/`fileHashes` to cover exactly the JAR entries, with every
  actual indexed text hash matching and every document metadata hash matching;
- when supplied, the generated directory to equal the embedded bytes exactly.

`packages.json` now binds `source.legalIndexSha256` to the **verified embedded**
index and adds `source.legalIndexScope: verified-embedded-app-jar`,
`source.legalIndexSourceTreeSha256` (the source-only preview hash) and a top-level
`embeddedLegal` record containing app-JAR path/bytes/SHA-256, resource prefix,
index hash, platform, component/document/coverage counts and per-file hashes.
The explicit scope is: *desktop application-image JAR consumed by the installer
task; the `.deb`/`.dmg`/`.msi` container itself is not re-opened*. Native, JDK,
source-correspondence and legal compatibility remain unverified.

CLI fixtures without `--app-jar` remain usable but are labelled
`source-tree-unverified`; the production `package.sh` path always requires
embedded-byte verification.

## 5. Workflow reuse and fallback

The desktop inventory/support step now reuses the package-generated
`.work/verification/desktop-legal/<platform>` set whenever its exact inventory
and index exist, and re-checks it with the same reviewed supplement (`--check`)
instead of repeating Gradle resolution. If `packages.json` is present, the step
also asserts that its embedded-legal binding is verified at `GITHUB_SHA`. If
packaging failed before the generated inputs exist, the step keeps the
independent diagnostic path: export the inventory and generate into
`.work/verification/legal-inventory/<platform>`, always with the same reviewed
supplement.

The retention step prefers the legal set actually bound to the verified package,
then the diagnostic copy, then any generated package legal directory; it no
longer retains the tracked source-only preview as if it were package coverage.
Always-after-failure retention, true outcome enforcement, token separation
(`GH_TOKEN` only in retention steps), free public runners, manual-only dispatch
and namespace containment are unchanged.

Android and iOS integration is explicitly **not** part of this narrow desktop
patch. Their inventory steps still generate unsupplemented diagnostic indexes;
that remains open.

## 6. Authored checks performed

All checks are cheap Python/shell/YAML only; no Gradle/native/package execution.
Receipt, logs and `SHA256SUMS`: `.work/verification/task-b-desktop-legal-embedding/`.

| Check | Result |
|---|---|
| `python3 -B scripts/tests/test_package_manifest.py` | **18/18** |
| `python3 -B scripts/tests/test_desktop_package_legal.py` | **7/7** |
| `python3 -B scripts/tests/test_retention_workflow.py` | **12/12** |
| `python3 -B scripts/tests/test_package_notices.py` | **74/74** |
| `python3 -B scripts/tests/test_build_retention.py` | **18/18** |
| `python3 -B scripts/tests/test_retention_packet.py` | **7/7** |
| `python3 -B scripts/tests/test_tooling.py` | **16/16** |
| `python3 -B scripts/tests/test_verification.py` | **6/6** |
| `bash -n scripts/package.sh` | pass |
| Workflow YAML parse (`ruby -e 'require "yaml"; ...'`) | pass |
| `python3 -B scripts/package-notices.py --check` | source-only pass: 0 components, 3 texts, 1 open issue |
| `git diff --check` | pass |

Authored JAR tests cover: correct full index plus notices; duplicate, missing,
stale/extra, tampered, path-traversal and stale-directory entries; source-tree vs embedded hash
mismatch; zero-component source-only index rejection; app JAR outside the
repository or symlinked; generated-directory/JAR mismatch. A separate cheap
check (`source-only-rejection-check.py`) generated the actual source-only index,
embedded it in an authored JAR, and confirmed the production manifest rejects it
(`0` components, no inventoried platform) instead of accepting it.

The new helper suite is not yet wired into `scripts/ci.sh` (not owned by this
patch); the existing manifest and workflow suites are already executed there.

## 7. Initial implementation limits and proposed package proof

No local or hosted Gradle configuration, compilation, inventory, JAR
construction, installer build, installer re-open, installation or native run was
performed. In particular this pass does **not** prove:

- that the Gradle `desktopProcessResources` replacement compiles and produces
  the intended JAR layout;
- that `createDistributable`/`packageDeb`/`packageDmg`/`packageMsi` succeed on
  any host;
- that the app-image JAR contains a nonzero-component index after a real
  inventory;
- macOS/Windows app-image layouts beyond the explicitly tested path contract;
- installer container bytes, installation, signing/notarization, redistribution
  readiness, native/JDK/source closure or physical audio.

The next proof must be lead-coordinated through `vps-build` with the existing
Veyra project lock. Intended Linux command (lead selects/confirms profile and
lock; not executed here):

```sh
vps-build run --profile heavy --label veyra-desktop-legal-embedding \
  --project-lock /root/projects/Veyra/.work/verification/pr4-ports-wave-20261107/gradle.lock \
  -- scripts/package.sh
```

Expected proof points:

1. `:shared:jvmJar` and `:composeApp:exportProductionInventory` exit 0 for
   `desktop-linux-x64`;
2. generated legal index has a nonzero Linux component set and the reviewed
   supplement applied, while readiness stays false and the known six issues stay
   visible;
3. `packageDeb` exit 0 and the app JAR contains the same index/text hashes;
4. `packages.json` has `embeddedLegal.verified=true`,
   `source.legalIndexScope=verified-embedded-app-jar`, and an app-JAR hash that
   matches the JAR in the generated app image;
5. independently extract the `.deb` app JAR and confirm its legal index hash
   equals `embeddedLegal.indexSha256` (container re-open is not implemented by
   the manifest itself).

Until that coordinated run and independent readback, this remains an
implementation pass only. No Task B, package, legal, native or release
acceptance is claimed.


## 8. Lead review and focused JAR proof — 2026-11-09

The lead reviewed the production resource replacement, manifest byte binding,
host selection and workflow fallback. Fixed the `ProcessResources` import to
`org.gradle.language.jvm.tasks.ProcessResources`. The first gated attempt then
caught a Kotlin DSL nullable-variable smart-cast error in the ancestor walk;
the walk now snapshots the nullable path into a local non-null value.
The failing attempt remains at
`.work/verification/task-b-desktop-legal-embedding/lead-proof/`.

The second attempt, `lead-proof-2`, ran through `vps-build --profile normal`
with the existing shared project lock, offline dependencies, one worker,
explicit bounded JVM arguments and in-process Kotlin compilation requested.
`:composeApp:desktopJar` exited 0 (2m 50s; 19 actionable tasks, 8 executed).
The included Kotlin, shared Kotlin and desktop Kotlin compilation tasks ran.
Tracked input manifests were equal before/after; this was a dirty-input proof
at base `1bf647c`, not a clean committed-source or installer gate.

The legal input was the retained Linux inventory projection under
`.work/verification/task-b-hosted-fixes/reviewed-legal-projection`.
No fresh runtime inventory was exported in this focused check. The generated
Gradle JAR was independently opened by the manifest verifier and every legal
resource compared with the generated directory and index:

- JAR: `composeApp/build/libs/composeApp-desktop-1.0.0.jar`;
- JAR SHA-256: `f0be3ec74c52c76a11e3a0300d8d94aabecdca7eae73007ff0ca5c1a327d5fc2`;
- embedded index SHA-256: `bea608038fec5fdbb661901d456e456a0950035c7e5918ed01b9ca1fd4d1c979`;
- 161 components, 28 documents, 35 resources (34 hashed payloads plus the index),
  six unresolved coverage issues;
- exact generated-directory byte equality; source-only preview hash remains
  distinct and the tracked legal previews were not replaced.

Lead authored rechecks passed: package manifest 18/18, desktop legal helper 7/7,
workflow invariants 12/12, shell syntax and diff check. Other initial authored
results remain as recorded in section 6. No Gradle/Kotlin Java process belonging
to this checkout remained in the post-build process sample. The protected root
checkout's three dirty legal-preview hashes were independently rechecked unchanged.

Evidence: `lead-proof-2/result.json`, `gradle.log`, `inputs.before.json`,
`inputs.after.json`, `embedded-jar.json`; authored checks under `lead-checks/`,
all beneath `.work/verification/task-b-desktop-legal-embedding/`.
The next scoped hosted Windows run must verify the fresh inventory, JAR/app-image
and installer path together. No fresh installer, Windows execution, installer
re-open, native build, legal closure, installation, signing, physical audio or
Task B acceptance follows from this focused check.


## 9. Scoped Windows run 42583592086 — failed before build configuration

Exact clean source `0bf9aceda974b028c553f5926e5e5c5c1fabc74d` was dispatched
Windows-only with packaging and draft retention. The run completed failed.
Step conclusions masked by `continue-on-error` are not acceptance; final outcome
enforcement correctly failed the lane.

The package-notice suite passed 74/74. The package-manifest suite failed 1/18
in an authored assertion expecting the LF hash of `{}` plus newline. On Windows
`write_text` created CRLF bytes; the manifest correctly hashed those actual bytes.
The observed hashes were reproduced exactly: LF `ca3d163b...`, CRLF `e3566b3a...`.
The lead correction compares the actual source fixture bytes and adds an explicit
CRLF preservation regression. Local corrected suite: 19/19. This test correction
is batched with the four additional exact-coordinate notice records described
in section 10; no manifest production behavior was changed.

Independent packaging and the inventory fallback each failed while downloading
the pinned Gradle 8.13 distribution, HTTP 504 at the GitHub distribution URL.
No Gradle configuration, JVM testing, native build, fresh inventory, legal JAR,
app-image or installer proof was reached. A bounded lead-host range probe reached
the official distribution and checksum, but does not prove a whole download or
Windows availability. No distribution URL/checksum was changed and no unchanged
hosted retry was dispatched.

Retention and fresh hosted readback succeeded. The lead independently downloaded
and verified the diagnostic-only packet through the release-id asset API:

- unpublished draft/prerelease `438868262`;
- tag `veyra-draft-42583592086-1-desktop-windows-x64` resolves directly to
  `0bf9aceda974b028c553f5926e5e5c5c1fabc74d`;
- `manifest.json` 1,636 bytes and `support.zip` 921 bytes;
- support SHA-256 `df0b57b1bf16d098880571876706c7e51b5ac56cbe33100ff506a24f2bc6520a`;
- verified failed-lane outcome, zero packages.

Evidence is under `.work/verification/task-b-desktop-legal-embedding/windows-42583592086/`:
`windows.log`, `lead-assessment.json`, `manifest-tests.log`, `verification.json`,
`release-api.json`, `tag-api.json`, `packet/` and the bounded network probe.
The prior local focused JAR proof remains valid within its stated projection
scope. Task B remains incomplete.


## 10. Batched exact-version notice and Windows assertion correction

The lead accepted four additional retained-license records after independently
checking all research evidence seals, the four binary artifact hashes, the four
published sources-JAR hashes and all eight cited source-entry byte matches.
The accepted coordinates are core-common 2.2.0, lifecycle-runtime-compose-desktop
2.9.6, savedstate-compose-desktop 1.3.6 and material3-desktop 1.9.0. The immutable
release/commit mappings and their limits are recorded in
`task-b-remaining-publisher-notices.md`. Source-JAR matches are not reproduced
binary builds. The two Compose compatibility stubs have their own coordinate
records; no license is inferred for a different implementation coordinate.

`third_party/notices/desktop-reviewed.json` now has 85 exact coordinates and
92 records. Each new record uses the already-retained original 10,175-byte
publisher terms; no generic global assignment, text normalization or new text
payload was introduced. The four remaining union coordinates are atomicfu
0.23.2 and the Linux/macOS arm64/macOS x64 mediamp runtime 0.5.3 artifacts.
The 596-byte atomicfu header and its NOTICE remain research evidence, not a
substitute for retained complete terms. The mediamp project license is not
substituted for its bundled MPV/FFmpeg and other native-library obligations.

Lead generation with the tracked registry and retained-inventory harness yields
161 Linux components, 83 with retained complete-license terms, 28 documents and
two open text issues (atomicfu and Linux mediamp). The result contains 35 total
resources: 34 hash-indexed payloads plus `index.json`. This corrects the earlier
section-8 prose count that incorrectly included the index in 34; the original
JAR verifier receipt always contained all 35 resources. Both readiness flags
remain false. This projection is not a new host inventory or rebuilt installer.

Proportional checks passed: package notices 74/74, manifest 19/19 (including the
explicit CRLF-byte regression), regenerated projection `--check`, source-only
preview `--check`, and diff check. Evidence is under
`.work/verification/task-b-remaining-publisher-notices/lead-integration/`.
Historical reports, run receipts and research seals are unchanged. The next
Windows-only attempt must exercise the corrected authored test and, if the
pinned Gradle download is available, the fresh inventory/package path. No full
matrix or local native build was repeated for this data/test-only batch.


## 11. Windows run 42588682043 and direct corrections

Run `42588682043` tested `fd03068b34543aa670c9beb455be535d4f92a2fe`.
All 19 manifest and 74 notice tests passed; Gradle 8.13 downloaded and shared
and desktop production Kotlin compilation executed. Three distinct failures
remain in its preserved logs under
`.work/verification/task-b-windows-42588682043/`:

- `test_valid_output_reuse_does_not_run_compilers_network_or_executable` created
  its SHA sidecar with newline-translating `write_text`; Windows emitted CRLF
  while the verifier deliberately requires LF bytes. The fixture now uses the
  existing byte-exact sidecar writer. Production verification is unchanged.
- Native preparation failed with `OwnedProcessError`. Neither console log
  selected an inner compiler diagnostic. This run does not establish the native
  cause. The bounded reporter now also recognizes FFmpeg compiler-probe failures
  and shell missing-file/command messages; secret/URL suppression and all bounds
  remain unchanged.
- The workflow supplied `--native-diagnostic`, but `build-retention.sh` rejected
  it before invoking the Python helper that already implements the option.
  The shell now parses and forwards it unchanged. A shell-execution regression
  checks the real argument path, including a filename with spaces, and stops
  before any GitHub operation. The existing Python packet test verifies retained
  diagnostic bytes and credential rejection.

No release was created by the failed retention step, so the independent readback
truthfully failed with `release not found`. No retained package can be claimed
from this run. Local proportional checks: workflow/shell 13/13, FFmpeg 24/24,
native bundle 29/29, build retention 18/18 (84 total), shell syntax and diff check.
These are Linux authored checks; the Windows runtime and native failure require
the next scoped hosted result. No full platform matrix was repeated.
