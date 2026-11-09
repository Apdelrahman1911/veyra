#!/usr/bin/env bash
# Create one bounded draft prerelease for a build lane. Invoked only from a
# retention step that has GH_TOKEN scoped to this step. No publication,
# overwrite, artifact action or cache action is used.
set -euo pipefail
platform=""; lane_outcome=""; diagnostic=""
packages=(); directories=(); xml_roots=(); legal=""; inventory=""; packages_json=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --platform) platform="$2"; shift 2 ;;
    --lane-outcome) lane_outcome="$2"; shift 2 ;;
    --diagnostic) diagnostic="$2"; shift 2 ;;
    --package) packages+=("$2"); shift 2 ;;
    --directory) directories+=("$2"); shift 2 ;;
    --xml-root) xml_roots+=("$2"); shift 2 ;;
    --legal) legal="$2"; shift 2 ;;
    --inventory) inventory="$2"; shift 2 ;;
    --packages-json) packages_json="$2"; shift 2 ;;
    *) printf 'Unknown retention argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[ -n "$platform" ] && [ -n "$lane_outcome" ] && [ -n "$diagnostic" ] || { echo 'Missing lane identity' >&2; exit 2; }
[ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_SHA:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ] && [ -n "${GITHUB_RUN_ATTEMPT:-}" ] || { echo 'Missing retention environment' >&2; exit 2; }
case "$GITHUB_SHA" in *[!0-9a-f]*|'') echo 'GITHUB_SHA must be lowercase 40-hex' >&2; exit 2 ;; esac
[ "${#GITHUB_SHA}" -eq 40 ] || { echo 'GITHUB_SHA must be 40-hex' >&2; exit 2; }
tag="veyra-draft-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-${platform}"
workspace="${GITHUB_WORKSPACE:-$PWD}"
retention_root="$workspace/.work/verification/build-retention/${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}/${platform}"
mkdir -p "$retention_root"
packet="$(mktemp -d "$retention_root/packet.XXXXXX")"
receipt="$retention_root/receipt.json"
hash_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
python_args=(
  create --source-root "$PWD" --output "$packet" --platform "$platform"
  --lane-outcome "$lane_outcome" --diagnostic "$diagnostic"
  --repository "$GITHUB_REPOSITORY" --commit-sha "$GITHUB_SHA"
  --run-id "$GITHUB_RUN_ID" --run-attempt "$GITHUB_RUN_ATTEMPT" --release-tag "$tag"
  --workflow-ref "$GITHUB_WORKFLOW_REF" --ref "$GITHUB_REF"
  --pin "$(cat gradle/kmpyt.commit)" --schema-sha256 "$(hash_file shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json)"
  --host-label "${ImageOS:-unknown}-${RUNNER_ARCH:-unknown}"
)
for value in "${packages[@]}"; do python_args+=(--package "$value"); done
for value in "${directories[@]}"; do python_args+=(--directory "$value"); done
for value in "${xml_roots[@]}"; do python_args+=(--xml-root "$value"); done
[ -z "$legal" ] || python_args+=(--legal "$legal")
[ -z "$inventory" ] || python_args+=(--inventory "$inventory")
[ -z "$packages_json" ] || python_args+=(--packages-json "$packages_json")
python3 -B scripts/build-retention.py "${python_args[@]}" > "$receipt"
cat "$receipt"
if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  printf 'Refusing to reuse existing release/tag: %s\n' "$tag" >&2; exit 1
fi
if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$tag" >/dev/null 2>&1; then
  printf 'Refusing to reuse existing tag: %s\n' "$tag" >&2; exit 1
fi
gh api --method POST "repos/$GITHUB_REPOSITORY/git/refs" \
  -f ref="refs/tags/$tag" -f sha="$GITHUB_SHA" >/dev/null
test "$(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$tag" --jq .object.sha)" = "$GITHUB_SHA"
gh release create "$tag" --repo "$GITHUB_REPOSITORY" --verify-tag --draft --prerelease \
  --title "Veyra draft ${platform} ${tag}" \
  --notes "Draft build-retention lane ${platform} at exact commit ${GITHUB_SHA} (run ${GITHUB_RUN_ID}/${GITHUB_RUN_ATTEMPT}), lane outcome ${lane_outcome}. Unsigned/uninstalled unless the manifest says otherwise. Draft and unpublished; not a release or verification claim."
release_id="$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json databaseId --jq .databaseId)"
test -n "$release_id"
python3 -B - "$packet" > "$retention_root/lane-assets-${platform}.list" <<'PY'
import sys
from pathlib import Path
for path in sorted(Path(sys.argv[1]).iterdir()):
    if path.is_symlink() or not path.is_file():
        raise SystemExit("unsafe lane asset")
    print(path)
PY
assets=()
while IFS= read -r value; do assets+=("$value"); done < "$retention_root/lane-assets-${platform}.list"
[ "${#assets[@]}" -gt 0 ] || { echo 'No lane assets' >&2; exit 1; }
gh release upload "$tag" "${assets[@]}" --repo "$GITHUB_REPOSITORY"
gh api "repos/$GITHUB_REPOSITORY/releases/$release_id/assets" > "$retention_root/lane-assets-${platform}.json"
python3 -B - "$packet" "$retention_root/lane-assets-${platform}.json" "$tag" "$lane_outcome" <<'PY'
import hashlib, json, sys
from pathlib import Path
packet = Path(sys.argv[1]); assets = json.loads(Path(sys.argv[2]).read_text()); tag = sys.argv[3]
expected = {}
for path in packet.iterdir():
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 1_900_000_000:
        raise SystemExit("unsafe local lane asset")
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    expected[path.name] = (path.stat().st_size, "sha256:" + value.hexdigest())
actual = {item["name"]: (item["size"], item.get("digest")) for item in assets}
if set(actual) != set(expected):
    raise SystemExit("remote lane asset set mismatch")
for name, (size, sha) in expected.items():
    if actual[name][0] != size or (actual[name][1] and actual[name][1] != sha):
        raise SystemExit("remote lane asset identity mismatch: " + name)
print(json.dumps({"tag": tag, "assets": sorted(actual)}, sort_keys=True))
PY
{
  printf '### Draft build retention lane `%s`\n\n' "$platform"
  printf -- '- Tag/release: `%s` (draft, prerelease, unpublished)\n' "$tag"
  printf -- '- Release id: `%s`\n' "$release_id"
  printf -- '- Exact commit: `%s`; lane outcome: `%s`\n' "$GITHUB_SHA" "$lane_outcome"
  printf -- '- Assets: %s\n' "$(cd "$packet" && printf '%s ' *)"
  printf -- '- No publication, overwrite, artifact/cache action or credential output.\n'
} >> "$GITHUB_STEP_SUMMARY"
printf 'Retained draft lane %s release id=%s tag=%s\n' "$platform" "$release_id" "$tag"
