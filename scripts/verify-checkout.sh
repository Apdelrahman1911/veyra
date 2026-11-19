#!/usr/bin/env bash
# Validate the exact revision in CI before running the scoped build.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"

# Hosted builds must describe exactly the checked-out source. Local development
# still permits reviewable working edits; GitHub dispatches require clean inputs.
if [ -n "${GITHUB_SHA:-}" ]; then
    actual_head="$(git -c safe.directory="$VEYRA_PROJECT_ROOT" rev-parse HEAD)"
    [ "$actual_head" = "$GITHUB_SHA" ] || { printf '%s\n' 'Hosted source HEAD differs from GITHUB_SHA.' >&2; exit 1; }
    source_status="$(git -c safe.directory="$VEYRA_PROJECT_ROOT" status --porcelain=v1)"
    [ -z "$source_status" ] || { printf 'Hosted source is dirty before bootstrap:\n%s\n' "$source_status" >&2; exit 1; }
fi

"$SCRIPT_DIR/bootstrap.sh"
python3 -B "$SCRIPT_DIR/package-notices.py" --check
