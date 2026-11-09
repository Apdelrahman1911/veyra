#!/usr/bin/env bash
# Commands shared by manual CI and a developer's scoped shell. No installation.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
mode="${1:-}"
case "$mode" in
    desktop|android|ios) shift ;;
    *) printf '%s\n' 'Usage: scripts/ci.sh desktop|android|ios [--package]' >&2; exit 2 ;;
esac
package=false
if [ "${1:-}" = --package ]; then package=true; shift; fi
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected CI argument.' >&2; exit 2; }

command -v java >/dev/null 2>&1 || { printf '%s\n' 'No existing JDK; no global installation is permitted.' >&2; exit 1; }
version="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')"
[ "$version" = 21 ] || { printf 'Expected existing JDK 21, got %s.\n' "$version" >&2; exit 1; }
python3 -B - <<'PY'
import os,platform
system=platform.system(); arch=platform.machine().lower()
arch={'amd64':'x64','x86_64':'x64','aarch64':'arm64'}.get(arch,arch)
for key,actual in (('VEYRA_EXPECTED_OS',system),('VEYRA_EXPECTED_ARCH',arch)):
    expected=os.environ.get(key)
    if expected and actual!=expected:
        raise SystemExit(key+' mismatch: expected '+expected+', observed '+actual)
PY

"$SCRIPT_DIR/bootstrap.sh"
mkdir -p .work/verification
python3 -B scripts/verification-summary.py --metadata-only --phase "$mode"
python3 -B scripts/tests/test_tooling.py
python3 -B scripts/tests/test_verification.py
python3 -B scripts/tests/test_package_notices.py
python3 -B scripts/tests/test_package_manifest.py
python3 -B scripts/tests/test_retention_packet.py
python3 -B scripts/tests/test_build_retention.py
python3 -B scripts/tests/test_retention_workflow.py
# Authored Python-only safety regressions; neither Android Stage 0 nor its
# separately approved strace/fork experiment runs automatically in CI.
python3 -B scripts/tests/test_android_preflight.py
# This is the source-only consistency check, not a production dependency or
# binary-release certification. Actual release inventories must be generated on
# the matching target and passed explicitly; see verification/package-notices.md.
python3 -B scripts/package-notices.py --check
python3 -B scripts/tests/test_design_resources.py
python3 -B scripts/verify-design-resources.py

case "$mode" in
    desktop)
        python3 -B native/test_bundle.py
        python3 -B native/test_windows_toolchain.py
        python3 -B native/ffmpeg/test_build.py
        # Compose tests need a display on Linux; use already installed Xvfb,
        # never apt. A verified private read-only mount guard gives Xorg's
        # hard-coded scratch paths repository backing; there is no fallback.
        if [ "$(uname -s)" = Linux ]; then
            "$SCRIPT_DIR/with-display.sh" "$SCRIPT_DIR/gradle.sh" --continue :shared:jvmTest :composeApp:desktopTest
        else
            "$SCRIPT_DIR/gradle.sh" --continue :shared:jvmTest :composeApp:desktopTest
        fi
        python3 -B scripts/verification-summary.py --phase desktop --require-tests shared composeApp
        if [ "$package" = true ]; then "$SCRIPT_DIR/package.sh"; fi
        ;;
    android)
        sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        [ -n "$sdk" ] && [ -f "$sdk/platforms/android-36/android.jar" ] && [ -d "$sdk/build-tools/35.0.0" ] \
            || { printf '%s\n' 'Required read-only Android platform 36/build-tools 35.0.0 unavailable; no SDK install attempted.' >&2; exit 1; }
        "$SCRIPT_DIR/gradle.sh" :shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:lintDebug
        python3 -B scripts/verification-summary.py --phase android --require-tests shared composeApp
        ;;
    ios)
        [ "$(uname -s)" = Darwin ] || { printf '%s\n' 'iOS requires an existing macOS/Xcode host.' >&2; exit 1; }
        export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.0.1.app/Contents/Developer}"
        [ -d "$DEVELOPER_DIR" ] || { printf '%s\n' 'Pinned Xcode 26.0.1 is unavailable; no installation or xcode-select change attempted.' >&2; exit 1; }
        # Compile native tests without claiming execution in CoreSimulator.
        if [ "$(uname -m)" = arm64 ]; then
            simulator=IosSimulatorArm64
        else
            simulator=IosX64
        fi
        "$SCRIPT_DIR/gradle.sh" ":shared:compileTestKotlin$simulator" ":composeApp:compileTestKotlin$simulator" :composeApp:linkDebugFrameworkIosArm64
        "$SCRIPT_DIR/build-ios.sh" simulator
        "$SCRIPT_DIR/build-ios.sh" device
        python3 -B scripts/verification-summary.py --phase ios
        ;;
esac
