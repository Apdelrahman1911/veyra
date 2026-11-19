#!/usr/bin/env bash
# Validate the checked-in Gradle wrapper and dependency inputs.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
ROOT="$VEYRA_PROJECT_ROOT"

fail() { printf 'Veyra bootstrap: %s\n' "$*" >&2; exit 1; }
for file in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradle/libs.versions.toml; do
    [ -f "$ROOT/$file" ] || fail "Missing versioned build input: $file"
done
grep -Fxq 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip' \
    "$ROOT/gradle/wrapper/gradle-wrapper.properties" || fail 'Wrapper must use the pinned official Gradle 9.7.1 binary distribution.'
grep -Fxq 'distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a' \
    "$ROOT/gradle/wrapper/gradle-wrapper.properties" || fail 'Missing or unexpected Gradle distribution checksum.'

for file in settings.gradle.kts build.gradle.kts shared/build.gradle.kts composeApp/build.gradle.kts nativeComponents/build.gradle.kts; do
    if grep -Eq 'mavenLocal[[:space:]]*\(' "$ROOT/$file"; then
        fail 'MavenLocal is not permitted in the reproducible Veyra dependency graph.'
    fi
done
case "$(tr -d '\r' < "$ROOT/gradle/kmpyt.mode")" in
    source) python3 -B "$SCRIPT_DIR/kmpyt-source.py" check ;;
    maven) ;;
    *) fail 'gradle/kmpyt.mode must be source or maven.' ;;
esac
printf '%s\n' 'Veyra bootstrap OK: pinned dependencies, Gradle 9.7.1, and project-owned tool directories.'
