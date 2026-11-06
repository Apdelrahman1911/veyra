#!/usr/bin/env bash
# Reproducible, scoped wrapper entry point. Use this instead of bare ./gradlew.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
"$SCRIPT_DIR/bootstrap.sh"
cd "$VEYRA_PROJECT_ROOT"
exec bash ./gradlew --no-daemon --max-workers="${VEYRA_GRADLE_WORKERS:-1}" \
    -Pandroid.builder.sdkDownload=false \
    -Porg.gradle.java.installations.auto-download=false \
    "$@"
