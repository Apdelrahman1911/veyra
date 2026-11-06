#!/usr/bin/env bash
# Stage 0 only. Guest boot/instrumentation remains explicitly unauthorized here.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
if [ "${1:-}" = --run ]; then
    printf '%s\n' 'Android guest execution is still gated: reviewed Stage 0 evidence and a separate launch approval are required.' >&2
    exit 78
fi
if [ "$#" -ne 1 ] || [ "$1" != --preflight ]; then
    printf '%s\n' 'Usage: scripts/android-instrumentation.sh --preflight (no guest); --run remains gated.' >&2
    exit 2
fi
exec "$SCRIPT_DIR/dev.sh" python3 -B "$SCRIPT_DIR/android-preflight.py" --preflight
