#!/usr/bin/env bash
# Reproducible, scoped wrapper entry point. Use this instead of bare ./gradlew.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
"$SCRIPT_DIR/bootstrap.sh"
cd "$VEYRA_PROJECT_ROOT"
# Gradle 8.13 retries malformed ZIP downloads, but HTTP 5xx errors escape
# immediately. Bootstrap the pinned distribution separately so an identified
# transient download failure can be retried without ever retrying a build/test.
wrapper_log="$(mktemp "$VEYRA_WORK_ROOT/tmp/gradle-bootstrap.XXXXXX")"
trap 'rm -f -- "$wrapper_log"' EXIT
for wrapper_attempt in 1 2 3; do
    if bash ./gradlew --version > "$wrapper_log" 2>&1; then
        break
    else
        wrapper_status=$?
    fi
    if [ "$wrapper_attempt" = 3 ] || ! grep -Eq \
        'Server returned HTTP response code: (502|503|504) for URL: https://github\.com/gradle/gradle-distributions/releases/download/v8\.13\.0/gradle-8\.13-bin\.zip' \
        "$wrapper_log"; then
        cat "$wrapper_log" >&2
        exit "$wrapper_status"
    fi
    printf 'Pinned Gradle download returned a transient HTTP error; bootstrap attempt %s/3.\n' "$((wrapper_attempt + 1))" >&2
    sleep 2
done
rm -f -- "$wrapper_log"
trap - EXIT
exec bash ./gradlew --no-daemon --max-workers="${VEYRA_GRADLE_WORKERS:-1}" \
    -Pandroid.builder.sdkDownload=false \
    -Porg.gradle.java.installations.auto-download=false \
    "$@"
