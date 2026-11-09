#!/usr/bin/env bash
# Build only the current OS's installer. Publication is a separate gated action.
# Production desktop builds generate exact host runtime legal inputs first and
# bind the bytes actually embedded in the app-image JAR in packages.json.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
platform="$(python3 -B scripts/desktop-package-legal.py platform)"
case "$(uname -s)" in
    Linux) task=packageDeb ;;
    Darwin) task=packageDmg ;;
    MINGW*|MSYS*|CYGWIN*) task=packageMsi ;;
    *) printf '%s\n' 'No supported native installer for this host.' >&2; exit 1 ;;
esac
marker="$(mktemp "$VEYRA_WORK_ROOT/tmp/package-started.XXXXXX")"
trap 'rm -f "$marker"' EXIT
# A failed package must not leave an older manifest that could be mistaken for
# this run's verified binding.
rm -f .work/verification/packages.json
inventory=".work/verification/runtime-inventory/$platform.json"
legal=".work/verification/desktop-legal/$platform"
# Never let a previous inventory silently satisfy this run. The exporter is the
# only writer below and package-manifest rejects stale/changed build inputs.
rm -f "$inventory"
# The inventory resolves the production runtime classpath, so the selected
# project JARs must already exist. This does not compile the app or run tests;
# the installer task performs its normal single application build afterwards.
"$SCRIPT_DIR/gradle.sh" :shared:jvmJar
"$SCRIPT_DIR/gradle.sh" -I scripts/runtime-inventory.init.gradle \
    :composeApp:exportProductionInventory -Pveyra.inventory.platform="$platform"
python3 -B scripts/package-notices.py \
    --inventory "$inventory" \
    --supplement third_party/notices/desktop-reviewed.json \
    --output "$legal"
python3 -B scripts/desktop-package-legal.py validate-legal-dir --directory "$legal"
"$SCRIPT_DIR/gradle.sh" ":composeApp:$task" "-Pveyra.desktopLegalDir=$legal"
app_jar="$(python3 -B scripts/desktop-package-legal.py locate-app-jar --platform "$platform")"
python3 -B scripts/package-manifest.py --newer-than "$marker" \
    --expected-source-sha "${GITHUB_SHA:-}" --repository "${GITHUB_REPOSITORY:-}" --task "$task" \
    --app-jar "$app_jar" --desktop-legal-dir "$legal" \
    --expected-legal-platform "$platform" --require-embedded-legal
printf '%s\n' 'Package generation is not installation or signing verification. No release was published.'
