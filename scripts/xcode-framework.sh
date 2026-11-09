#!/usr/bin/env bash
# Xcode's checked-in build phase; never use a global Gradle installation.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
[ "$(uname -s)" = Darwin ] || { printf '%s\n' 'Xcode framework builds require macOS.' >&2; exit 1; }
case "${CONFIGURATION:-}" in Debug|Release) ;; *) printf '%s\n' 'Expected an Xcode Debug/Release configuration.' >&2; exit 1 ;; esac
case "${PLATFORM_NAME:-}" in iphoneos|iphonesimulator) ;; *) printf '%s\n' 'Expected an iOS Xcode platform.' >&2; exit 1 ;; esac
python3 -B - <<'PY'
import os
from pathlib import Path
root=Path(os.environ['VEYRA_PROJECT_ROOT']).resolve()
for key in ('BUILT_PRODUCTS_DIR', 'TARGET_BUILD_DIR', 'TEMP_DIR'):
    value=os.environ.get(key)
    if not value or not Path(value).resolve(strict=False).is_relative_to(root):
        raise SystemExit(key+' must be repository-owned; use scripts/build-ios.sh')
PY
exec "$SCRIPT_DIR/gradle.sh" :composeApp:embedAndSignAppleFrameworkForXcode
