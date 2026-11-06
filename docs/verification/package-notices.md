# Offline notices and production inventories

`scripts/package-notices.py` is an offline, bounded collector/checker. It reads
the actual `gradle/kmpyt.commit` checkout, complete reviewed GPL/MIT bytes, the
committed upstream notice, retained asset terms, and **only explicitly supplied
production inventories**. It neither invokes Gradle nor searches global caches
or downloads license text.

## Source-only check (current CI)

```sh
scripts/dev.sh python3 -B scripts/tests/test_package_notices.py
scripts/dev.sh python3 -B scripts/package-notices.py
scripts/dev.sh python3 -B scripts/package-notices.py --check
```

Regenerate only between builds: Compose packages these generated resources.
The output is `composeApp/src/commonMain/composeResources/files/legal/`, including
the five existing UI document names, an index, and SHA256-addressed original
texts. Generation is deterministic, uses staged replacement, and preserves
unchanged file timestamps. Manually edited, unmanaged or symlinked output is
rejected rather than silently overwritten. `--check` never writes that output.

**No supplied inventory means zero inventoried production components and an
explicit open coverage issue.** Passing source-only `--check` confirms retained
source/asset bytes, not package completeness. Current `scripts/ci.sh` runs this
limited check and the Python regression suite; its success is not a release gate
substitute. A final release job must instead generate/check the exact supplied
production inventories and package that resulting notice set.

## Exact target inventory

First build the selected target in the coordinated build slot. The exporter
does not silently compile missing local project artifacts. Then, on the matching
host and through the scoped wrapper:

```sh
scripts/gradle.sh -I scripts/runtime-inventory.init.gradle \
  :composeApp:exportProductionInventory \
  -Pveyra.inventory.platform=desktop-linux-x64
scripts/dev.sh python3 -B scripts/package-notices.py \
  --inventory .work/verification/runtime-inventory/desktop-linux-x64.json
```

Platforms/configurations are allowlisted:

| Platform | Configuration |
| --- | --- |
| `desktop-linux-x64`, `desktop-windows-x64`, `desktop-macos-x64`, `desktop-macos-arm64` | `desktopRuntimeClasspath` on that host |
| `android-release`, `android-debug` | `releaseRuntimeClasspath`, `debugRuntimeClasspath` |
| `ios-arm64`, `ios-simulator-arm64`, `ios-simulator-x64` | Corresponding main `CompileKlibraries` on macOS |

iOS inventories are main native link inputs, **not a final linked binary SBOM**.
An Android debug inventory does not establish release coverage. Supply multiple
`--inventory` arguments explicitly for a combined notice set. Inputs, outputs,
Maven POMs and all resolved artifacts must be within the repository. Inventories
record exact coordinates, variants, byte sizes, SHA256s, the immutable source pin
and all critical Gradle/pin inputs. Changed inputs require re-export. POM parent
traversal is bounded and only follows parents of actually selected components.
No test configuration or opaque/file dependency is accepted as production.

The collector checks JAR/AAR/KLIB legal entries, nested AAR libraries, native
presence, unsafe archive paths/links/entries, embedded test classes, original
text encodings, size/depth bounds and POM inheritance. It retains byte-identical
original texts with provenance; POM names/URLs remain metadata, not grants.

## Reviewed complete-text supplements

**A filename such as `LICENSE` is not proof of complete terms.** Extracted
documents are candidates only. Closing a component's text-coverage gap requires
a separately reviewed, exact-hashed complete license document:

```json
{
  "schemaVersion": 1,
  "components": {
    "example.group:example-module:1.0": [{
      "path": "third_party/notices/example-LICENSE.txt",
      "bytes": 1234,
      "sha256": "<64 lowercase hexadecimal characters>",
      "role": "license",
      "completeTextVerified": true,
      "provenance": "Reviewer checked the complete original terms at the recorded public source revision."
    }]
  }
}
```

Replace every example field with audited values, retain the full original text,
and supply `--supplement <repo-relative-manifest.json>` along with the inventories.
`completeTextVerified` is a review assertion, **not algorithmic legal approval**.
Missing/false assertions leave a coverage issue. `--require-notices-complete`
returns nonzero on open text/inventory gaps; it still never certifies a binary
release. Re-run `--check` with the same inventories and supplements after
generation. An isolated inspection output can use `--output .work/<owned-dir>`.

## Explicit on-demand helper enrollment

Node and Chromium are **not Maven components and are not bundled executables**.
The desktop runtime downloads the exact official archives on demand, then checks
their whole-archive and installed-file inventories. Never invent Maven coordinates
to put these helpers in a supplement. Adding files under native/licenses/ also
does **not** enroll them automatically.

The additive interface is repeatable **--helper-inventory PATH** and
**generate(..., helper_inventories=[...])**. It reads small, retained audit inputs
only: no network, archive download/extraction/rehashing, browser/Node execution,
Gradle invocation or source checkout. **archiveReverifiedByCollector** is false.
No helper arguments preserves the existing source-only output byte-for-byte.
The legacy dependency-only mode does not claim helper/package closure.

~~~sh
# Only after review, between builds; keep inspection output separate initially.
scripts/dev.sh python3 -B scripts/package-notices.py \
  --inventory .work/verification/runtime-inventory/desktop-linux-x64.json \
  --helper-inventory <reviewed-helper-notice-inventory.json> \
  --output .work/<owned-inspection-directory> \
  --require-notices-complete
~~~

This example contains placeholders, not a ready-to-run complete enrollment.
Use the same arguments with **--check** to verify existing generated output.
Supplying only helper inventories leaves the missing Gradle-runtime inventory
issue open. If helper mode is used with multiple desktop runtime inventories,
each supplied desktop target also needs its helper inventory. Conversely, every
enrolled helper target needs that target's runtime inventory for global notice
completeness; an Android or different desktop inventory cannot substitute.

### Inventory v1

One JSON object per target; at most the four supported desktop targets. Objects
must not contain duplicate JSON keys, nonfinite values or nesting deeper than
32 levels. All file fingerprints
have this shape (replace every placeholder with reviewed values):

~~~json
{
  "path": "<canonical repo-relative path>",
  "bytes": 1234,
  "sha256": "<64 lowercase hexadecimal characters>"
}
~~~

The reader rejects stale, escaping, symlinked, nonregular and oversized inputs.
It caps original document and source-input files at 4 MiB, runtime manifests at
1 MiB, inventory JSON at 32 MiB and cumulative helper input bytes at 128 MiB.

| Container field | Required value / meaning |
| --- | --- |
| schemaVersion | Integer 1, not a boolean |
| type | veyra-provisioned-helper-notices |
| platform | One of the four desktop targets above |
| helperSetComplete | Explicit boolean review assertion |
| sourceInputs | Exact fingerprints of the three installer sources below |
| helpers | Records for **both node and chromium**, never caller-chosen requiredComponents |

The three source fingerprints are
composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/ followed by
DesktopHelperManifest.kt, DesktopMediaRuntime.kt and SafeHelperArchive.kt.
All three must be current. The collector also checks the existing literal
DesktopHelperManifests hash table against the actual manifest bytes; a changed
table representation or installation policy requires collector/source review.
These are source checks, not proof that a particular packaged binary used them.

Missing helpers or false completeness declarations produce explicit inventory
coverage issues, even if the caller claims the set is complete. Unknown helpers,
duplicate helpers/targets, wrong platforms, stale hashes and contradictory
identity/selection records are rejected. FFmpeg and the packaged JDK are **not**
allowed extra helper records; they have separate inventory/legal/source gates.

Each helper has the following fields:

| Helper field | Required value / meaning |
| --- | --- |
| component | node or chromium |
| target | linux-x64, windows-x64, macos-x64 or macos-arm64, matching the container |
| version | Exact version/snapshot string from the packaged manifest |
| delivery | downloaded-on-demand |
| manifest | Fingerprint of composeApp/src/desktopMain/resources/dev/veyra/desktop/helpers/&lt;component&gt;-&lt;target&gt;.json |
| archive | Object with url, bytes and sha256 exactly matching that manifest |
| installedFiles | Object with selection, entriesSha256 and explicit boolean complete |
| source | Reviewed exact source mapping and retained original revision evidence below |
| documents | Original legal-text records below; at most 8192 |
| coverage | Object with scope = all-installed-files-and-transitive-components, explicit boolean complete and nonempty review rationale |

**installedFiles.selection** is the exact manifest map. For Node it selects only
the executable and LICENSE, not npm; Chromium's empty map means **all**
inventoried installed entries, not no files. **entriesSha256** binds the entire
file/link inventory, including paths, bytes, hashes and executable flags:

~~~python
sha256(json.dumps(sorted(manifest["entries"], key=lambda item: item["path"]),
    ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))
~~~

The script exposes **helper_entries_sha256(entries)** for this calculation.
The whole original manifest is independently fingerprinted too. Changing any
selected files, archive, platform, manifest or installer source invalidates the
old review binding. A false installedFiles.complete is an inventory gap, not a
claim that license/source obligations are met.

### Original source mapping

**source** contains repository, a full lowercase 40-hex revision,
mappingReviewed = true, a nonempty review rationale and evidence (one or two
fingerprinted original JSON documents, each with role and url):

* **Node** repository: https://github.com/nodejs/node.
  The node-tag-ref role must be the retained original response from
  https://api.github.com/repos/nodejs/node/git/ref/tags/v&lt;version&gt;.
  Its ref must match that exact release. A lightweight tag's commit must equal
  source.revision. An annotated tag additionally requires node-tag-object
  from https://api.github.com/repos/nodejs/node/git/tags/&lt;tag-object-sha&gt;;
  its tag name, object SHA and peeled commit must match. An unpeeled tag object
  is not accepted as the source commit.
* **Chromium** repository: https://chromium.googlesource.com/chromium/src.
  The chromium-revisions role must be the retained original REVISIONS response
  beside the exact snapshot archive. chromium_revision must equal the manifest's
  snapshot and got_revision must equal source.revision.

Do not create a substitute receipt or guess a commit from a version string.
Retain the original response bytes and record how the official source mapping
was independently reviewed. The offline checks verify recorded hashes, URLs
and mappings; they do not authenticate a fresh remote response or prove the
complete dependency source universe.

### Original text and complete-set review

Every **documents** entry is a fingerprint plus role (license or notice),
explicit boolean completeTextVerified, nonempty provenance, and an origin:

* An **installed-file** origin has kind = installed-file and path = the selected
  installed path. Exact byte length and SHA256 must match that regular-file
  entry. A different document named LICENSE cannot substitute.
* A **source-file** origin has kind = source-file, repository = the public source
  repository, revision = the full 40-hex source commit, path = the original
  relative source path, and url = the exact immutable original-file URL.
  Accepted forms are GitHub blob/raw URLs, raw.githubusercontent.com and Gitiles
  file URLs at hosts ending in .googlesource.com (optionally ?format=TEXT).
  Repository, commit and percent-encoded source path must agree. Retain original
  file bytes, not a base64 transport envelope. A dependency may have its own
  exact repository/commit; its membership in the reviewed helper dependency set
  must be established by the reviewer, not inferred by this tool.

No other query strings, fragments, credentials or explicit ports are accepted.
Unrecognized document-host URL forms require an explicit collector review.
Raw original bytes, including Node's Windows CRLFs, are retained and hash-addressed;
the collector does not rewrite, summarize or manufacture license texts.

All enrolled documents need explicit full-text review and at least one must be
a complete license. **That still does not imply a complete notice set**:
coverage.complete must separately declare that all installed files and
transitive components' required legal texts are covered, with a review rationale.
A root-project license, filename, URL or successful parsing cannot set that flag.
Both flags are review assertions, not algorithmic legal approval.

The report keeps **helperInventoryIssues** separate from **helperNoticeIssues**;
both feed the existing coverageIssues / --require-notices-complete gate.
**helperRuntimeInventoryIssues** separately lists helper targets missing their
corresponding desktop runtime inventory and also blocks that global gate, without
misrepresenting the narrower helper-only inventory/text subcoverage.
helperInventoryCoverageComplete does not imply helperNoticeCoverageComplete.
Neither implies legal compatibility, corresponding-source availability,
installation, package completeness or redistribution readiness. Helper records
retain legalCompatibilityVerified = false and correspondingSourceVerified = false.

### Chromium remains incomplete

No actual helper enrollment or generated legal resources were changed for this
collector implementation. The audited Linux Chromium snapshot 1737260, source
d1dd0d00b7c40cb6f028108b394096a22eb2fa13, embeds a 401-byte sample
credits page, not full third-party terms. Its SHA256 is
c2a338c4f1f5f5e19d3020a25f8dc47e394ba4fed1ec2d8dc5b2a915170e02bc.
The collector rejects that known sample (and its short recognizable placeholder
form) as a legal document, including an unreviewed candidate. Keep it as gap
evidence, not substitute notices.

The pinned upstream [resource build rule](https://chromium.googlesource.com/chromium/src/+/d1dd0d00b7c40cb6f028108b394096a22eb2fa13/components/resources/BUILD.gn)
defaults generate_about_credits to is_official_build; its false branch copies
the [sample](https://chromium.googlesource.com/chromium/src/+/d1dd0d00b7c40cb6f028108b394096a22eb2fa13/components/resources/sample_credits.html).
Opening a browser's credits page cannot recover missing original terms.
Chromium's root BSD license alone is not complete third-party coverage.

Any future source-only notice generation needs the exact source revisions,
complete recursive/conditional DEPS universe, original metadata/legal files and
independent review. The upstream generator can silently skip absent dependency
directories, and notice-generation exit zero is not proof of a clean scan or
complete source inventory. A desktop --target-os argument is not an exact
binary-dependency filter. This collector does not run that generator, GN,
vpython3 or a browser, and does not declare Chromium closure.

## Evidence and remaining gates

The original 39 authored offline regression tests passed locally on 2026-11-06
and are preserved unchanged. The helper-enrollment suite adds 33 methods (72
total), covering exact source/manifest/archive/selection/document bindings,
annotated Node tags, all four target inventories, malformed/stale/unsafe inputs,
original line endings, distinct inventory/text gaps, placeholder credits,
deterministic output and the CLI completeness/check gates. They
exercise fake inventories, POMs, archives and test repositories against actual
reviewed source license bytes. Helper receipts and helper legal texts in tests
are **authored fixtures**, not actual upstream evidence or a real Chromium
closure. These tests are not evidence of Gradle dependency resolution, downloaded
helper execution or native package installation.

`index.json` always records `binaryRedistributionReady: false`; component
`legalCompatibilityVerified` is also false. Exact final package closure still
needs bundled JDK/native libraries, provisioned Node/Chromium/helpers and their
complete terms, corresponding sources/relinking/build materials, final public
source pins, signing/distribution and actual installation verification. License
compatibility and redistribution obligations require review of those actual
materials. Neither this tool nor a successful build proves physical audio.
See [licensing.md](licensing.md) for the source-grant audit.
