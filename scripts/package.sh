#!/usr/bin/env bash
# Build only the current OS's installer. Publication is a separate gated action.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
case "$(uname -s)" in
    Linux) task=packageDeb ;;
    Darwin) task=packageDmg ;;
    MINGW*|MSYS*|CYGWIN*) task=packageMsi ;;
    *) printf '%s\n' 'No supported native installer for this host.' >&2; exit 1 ;;
esac
"$SCRIPT_DIR/gradle.sh" ":composeApp:$task"
python3 -B scripts/package-manifest.py
printf '%s\n' 'Package generation is not installation or signing verification. No release was published.'
