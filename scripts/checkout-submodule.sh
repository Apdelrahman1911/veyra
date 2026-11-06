#!/usr/bin/env bash
# Explicit public-source initialization, separated from validation and builds.
# No credentials, --remote update, Git globals, or unrelated submodules.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"

url="$(git -c safe.directory="$VEYRA_PROJECT_ROOT" config -f .gitmodules --get submodule.third_party/kmpYT.url)"
case "$url" in
    https://github.com/Apdelrahman1911/kmpYT|https://github.com/Apdelrahman1911/kmpYT.git) ;;
    *) printf '%s\n' 'Refusing a submodule URL outside the authorized public kmpYT repository.' >&2; exit 1 ;;
esac
git -c safe.directory="$VEYRA_PROJECT_ROOT" -c protocol.file.allow=never \
    submodule update --init -- third_party/kmpYT
"$SCRIPT_DIR/bootstrap.sh"
