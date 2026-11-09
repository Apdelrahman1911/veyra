#!/usr/bin/env bash
# Explicit public-source initialization, separated from validation and builds.
# No credentials, --remote update, Git globals, or unrelated submodules.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"

# Hosted builds must describe exactly the checked-out source. Local development
# still permits reviewable working edits; GitHub dispatches require clean inputs.
if [ -n "${GITHUB_SHA:-}" ]; then
    test "$(git -c safe.directory="$VEYRA_PROJECT_ROOT" rev-parse HEAD)" = "$GITHUB_SHA"
    test -z "$(git -c safe.directory="$VEYRA_PROJECT_ROOT" status --porcelain=v1)"
    python3 -B - <<'IDENTITY'
from pathlib import Path
import hashlib
assert Path("gradle/kmpyt.commit").read_text().strip() == "c0a08090fe7dbd76e20216559b8184ad2cccde63", "Unexpected source dependency pin"
schema = Path("shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json")
assert hashlib.sha256(schema.read_bytes()).hexdigest() == "aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367", "Unexpected schema3 source"
IDENTITY
fi

url="$(git -c safe.directory="$VEYRA_PROJECT_ROOT" config -f .gitmodules --get submodule.third_party/kmpYT.url)"
case "$url" in
    https://github.com/Apdelrahman1911/kmpYT|https://github.com/Apdelrahman1911/kmpYT.git) ;;
    *) printf '%s\n' 'Refusing a submodule URL outside the authorized public kmpYT repository.' >&2; exit 1 ;;
esac
git -c safe.directory="$VEYRA_PROJECT_ROOT" -c protocol.file.allow=never \
    submodule update --init -- third_party/kmpYT
"$SCRIPT_DIR/bootstrap.sh"
