#!/usr/bin/env bash
# Validate the checkout and its pinned source dependency. Never fetch, install,
# update a submodule, initialize MavenLocal, or change global Git configuration.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
ROOT="$VEYRA_PROJECT_ROOT"
UPSTREAM="$ROOT/third_party/kmpYT"

fail() { printf 'Veyra bootstrap: %s\n' "$*" >&2; exit 1; }
command -v git >/dev/null 2>&1 || fail 'git is required; use an existing installation.'
[ -f "$ROOT/.gitmodules" ] || fail 'Missing .gitmodules; use the complete versioned checkout.'
[ -e "$UPSTREAM/.git" ] || fail 'kmpYT is not initialized. From the original shell run: git submodule update --init --recursive -- third_party/kmpYT'

pin_line="$(git -c safe.directory="$ROOT" -C "$ROOT" ls-files --stage -- third_party/kmpYT)"
read -r mode expected stage path <<< "$pin_line"
[ "${mode:-}" = 160000 ] && [ "${stage:-}" = 0 ] && [ "${path:-}" = third_party/kmpYT ] \
    || fail 'third_party/kmpYT must be an unconflicted tracked Git submodule.'
[[ "$expected" =~ ^[0-9a-f]{40}$|^[0-9a-f]{64}$ ]] || fail 'Invalid kmpYT gitlink revision.'
actual="$(git -c safe.directory="$UPSTREAM" -C "$UPSTREAM" rev-parse HEAD)"
[ "$actual" = "$expected" ] || fail "kmpYT revision mismatch: expected $expected, got $actual. Do not update to an unpinned branch."
if [ -f "$ROOT/gradle/kmpyt.commit" ]; then
    recorded="$(tr -d '\r\n' < "$ROOT/gradle/kmpyt.commit")"
    [ "$recorded" = "$expected" ] || fail 'gradle/kmpyt.commit and the Git submodule pin disagree.'
fi
git -c safe.directory="$UPSTREAM" -C "$UPSTREAM" diff --quiet \
    || fail 'kmpYT has unstaged tracked changes; review and update the approved pin first.'
git -c safe.directory="$UPSTREAM" -C "$UPSTREAM" diff --cached --quiet \
    || fail 'kmpYT has staged changes; review and update the approved pin first.'
[ -z "$(git -c safe.directory="$UPSTREAM" -C "$UPSTREAM" ls-files --others --exclude-standard)" ] \
    || fail 'kmpYT has untracked source files; use a clean pinned checkout.'

for file in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do
    [ -f "$ROOT/$file" ] || fail "Missing versioned wrapper file: $file"
done
grep -Eq '^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-8\.13-bin\.zip$' \
    "$ROOT/gradle/wrapper/gradle-wrapper.properties" || fail 'Wrapper must use the pinned official Gradle 8.13 binary distribution.'
grep -Eq '^distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78$' \
    "$ROOT/gradle/wrapper/gradle-wrapper.properties" || fail 'Missing or unexpected Gradle distribution checksum.'

for file in "$ROOT/settings.gradle.kts" "$ROOT/build.gradle.kts" "$ROOT/shared/build.gradle.kts" "$ROOT/composeApp/build.gradle.kts"; do
    if [ -f "$file" ] && grep -Eq 'mavenLocal[[:space:]]*\(' "$file"; then
        fail 'MavenLocal is not permitted in the reproducible Veyra dependency graph.'
    fi
done
printf 'Veyra bootstrap OK: kmpYT %s; Gradle 8.13; project-owned tool directories.\n' "$actual"
