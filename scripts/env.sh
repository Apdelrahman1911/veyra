#!/usr/bin/env bash
# Source only inside a build subprocess. Prefer scripts/dev.sh or scripts/gradle.sh
# so the caller's HOME and existing gh authentication remain unchanged.

_veyra_prepare_environment() {
    local root python native_root native_home native_tmp native_prefs native_daemon display_authority
    local candidates candidate native_java_bin bash_path bash_win bash_prefix expected_java_arch
    root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)" || return 1
    if command -v python3 >/dev/null 2>&1; then
        python=python3
    elif command -v python >/dev/null 2>&1; then
        python=python
    else
        printf '%s\n' 'Veyra requires an existing Python 3 installation; no global install will be attempted.' >&2
        return 1
    fi

    # Validate every destination before mkdir: a pre-existing escaping symlink
    # must not turn a project-local-looking path into an out-of-scope write.
    display_authority="$("$python" -B - "$root" <<'PY'
import os
import pathlib
import re
import stat
import sys

root = pathlib.Path(sys.argv[1]).resolve(strict=True)
names = (
    'home', 'tmp', 'gradle', 'konan', 'kotlin-daemon', 'cache', 'config',
    'data', 'state', 'runtime', 'android/user', 'android/avd',
    'android/emulator', 'preferences/user', 'preferences/system',
    'xcode/DerivedData', 'xcode/results', 'xcode/module-cache',
)
paths = [root / '.work' / name for name in names]
for path in paths:
    resolved = path.resolve(strict=False)
    if os.path.commonpath((str(root), str(resolved))) != str(root):
        raise SystemExit('Refusing an out-of-repository tool directory: ' + str(path))

# A nested dev/Gradle wrapper must keep the exact private Xvfb cookie selected
# by with-display.sh, not restore the empty default Xauthority. Arbitrary
# inherited desktop authentication is never accepted as this context.
authority = os.environ.get('VEYRA_DISPLAY_AUTHORITY', '')
if authority:
    failure = 'Refusing an invalid owned display authentication context'
    if (not sys.platform.startswith('linux')
            or authority != os.environ.get('XAUTHORITY')
            or os.environ.get('VEYRA_DISPLAY_MOUNT_NAMESPACE') != os.readlink('/proc/self/ns/mnt')):
        raise SystemExit(failure)
    path = pathlib.Path(authority)
    display = re.fullmatch(r'127\.0\.0\.1:([0-9]{1,5})', os.environ.get('DISPLAY', ''))
    if (path.parent != root / '.work/runtime'
            or not re.fullmatch(r'xauth-[A-Za-z0-9_-]{6,64}', path.name)
            or str(path) != authority or not display or not 1 <= int(display[1]) <= 59535):
        raise SystemExit(failure)
    for ancestor in (path, *path.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise SystemExit(failure)
    try:
        info = path.lstat()
    except OSError:
        raise SystemExit(failure)
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid()
            or info.st_mode & 0o077 or not info.st_mode & stat.S_IRUSR
            or not 1 <= info.st_size <= 4096):
        raise SystemExit(failure)
old_mask = os.umask(0o077)
try:
    for path in paths:
        path.mkdir(parents=True, exist_ok=True)
    if os.name != 'nt':
        (root / '.work' / 'runtime').chmod(0o700)
finally:
    os.umask(old_mask)
print(authority)
PY
    )" || return 1

    native_root="$root"
    if command -v cygpath >/dev/null 2>&1; then
        native_root="$(cygpath -m "$root")" || return 1
    fi
    native_home="$native_root/.work/home"
    native_tmp="$native_root/.work/tmp"
    native_prefs="$native_root/.work/preferences"
    native_daemon="$native_root/.work/kotlin-daemon"

    export VEYRA_PROJECT_ROOT="$root"
    export VEYRA_WORK_ROOT="$root/.work"
    export VEYRA_SCOPED_ENV=1
    export HOME="$native_home"
    export USERPROFILE="$native_home"
    export GRADLE_USER_HOME="$native_root/.work/gradle"
    export KONAN_DATA_DIR="$native_root/.work/konan"
    export TMPDIR="$native_tmp" TMP="$native_tmp" TEMP="$native_tmp"
    export XDG_CACHE_HOME="$native_root/.work/cache"
    export XDG_CONFIG_HOME="$native_root/.work/config"
    export XDG_DATA_HOME="$native_root/.work/data"
    export XDG_STATE_HOME="$native_root/.work/state"
    export XDG_RUNTIME_DIR="$native_root/.work/runtime"
    export APPDATA="$native_root/.work/config"
    export LOCALAPPDATA="$native_root/.work/data"
    export CFFIXED_USER_HOME="$native_home"
    export ANDROID_USER_HOME="$native_root/.work/android/user"
    export ANDROID_AVD_HOME="$native_root/.work/android/avd"
    export ANDROID_EMULATOR_HOME="$native_root/.work/android/emulator"
    # AGP rejects simultaneous deprecated/new preference overrides even when
    # both are repository-owned. ANDROID_HOME is the read-only SDK, not prefs.
    unset ANDROID_PREFS_ROOT ANDROID_SDK_HOME
    export CLANG_MODULE_CACHE_PATH="$native_root/.work/xcode/module-cache"
    export SWIFT_MODULECACHE_PATH="$native_root/.work/xcode/module-cache"
    export PYTHONDONTWRITEBYTECODE=1
    export PYTHONPYCACHEPREFIX="$native_root/.work/cache/python"
    export npm_config_cache="$native_root/.work/cache/npm"
    export npm_config_userconfig="$native_root/.work/config/npmrc"
    export npm_config_prefix="$native_root/.work/data/npm"
    export PIP_CACHE_DIR="$native_root/.work/cache/pip"
    export XAUTHORITY="${display_authority:-$native_root/.work/runtime/Xauthority}"
    export GIT_CONFIG_NOSYSTEM=1
    export GIT_CONFIG_GLOBAL="$native_root/.work/config/gitconfig"
    export GIT_TERMINAL_PROMPT=0

    # kmpYT checks that Java's real temporary directory equals Node's
    # os.tmpdir(). Keep ALL four settings identical, including on Windows.
    # Do not carry arbitrary inherited Java options (or secrets) into logs.
    unset _JAVA_OPTIONS JDK_JAVA_OPTIONS
    # Select the existing matching-architecture JDK21 once for every scoped
    # Gradle/Java child, including independent package and inventory steps.
    # No download, system install, parent-shell or global configuration change.
    case "$(uname -m)" in
        arm64|aarch64) expected_java_arch=aarch64; candidates=("${JAVA_HOME_21_ARM64:-}" "${JAVA_HOME_21_arm64:-}" "${JAVA_HOME:-}") ;;
        *) expected_java_arch=x86_64; candidates=("${JAVA_HOME_21_X64:-}" "${JAVA_HOME:-}") ;;
    esac
    local found_jdk=0 candidate_unix
    for candidate in "${candidates[@]}"; do
        [ -n "$candidate" ] || continue
        candidate_unix="$candidate"
        if command -v cygpath >/dev/null 2>&1; then
            candidate="$(cygpath -m "$candidate")"
            candidate_unix="$(cygpath -u "$candidate")"
        fi
        if { [ -x "$candidate_unix/bin/java" ] || [ -f "$candidate_unix/bin/java.exe" ]; } &&
            grep -Eq '^JAVA_VERSION="21([."]|$)' "$candidate_unix/release" 2>/dev/null &&
            grep -q "^OS_ARCH=\"$expected_java_arch\"" "$candidate_unix/release"; then
            export JAVA_HOME="$candidate"
            native_java_bin="$candidate_unix/bin"
            export PATH="$native_java_bin:$PATH"
            found_jdk=1
            break
        fi
    done
    if [ "$found_jdk" -ne 1 ]; then
        printf '%s\n' 'No existing matching-architecture JDK21 was found; no download or global installation is permitted.' >&2
        return 1
    fi

    # Hosted Windows Python/native children must resolve the existing Git Bash,
    # never C:\Windows\System32\bash.exe (WSL shim). Prefer the exact bash that
    # launched the scoped environment; no install or global PATH mutation.
    bash_path="${BASH:-}"
    [ -x "$bash_path" ] || bash_path="$(command -v bash 2>/dev/null || true)"
    if [ -n "$bash_path" ]; then
        if command -v cygpath >/dev/null 2>&1; then
            bash_win="$(cygpath -w "$bash_path")"
            case "$bash_win" in
                *\\[Ss]ystem32\\*) printf '%s\n' 'Refusing the Windows WSL bash shim.' >&2; return 1 ;;
            esac
            bash_prefix="$(dirname "$bash_path")"
            export VEYRA_TRUSTED_BASH="$bash_win"
            export PATH="$bash_prefix:$PATH"
        else
            export VEYRA_TRUSTED_BASH="$bash_path"
        fi
    fi

    export JAVA_TOOL_OPTIONS="-XX:-UsePerfData -Duser.home=\"$native_home\" -Djava.io.tmpdir=\"$native_tmp\" -Djava.util.prefs.userRoot=\"$native_prefs/user\" -Djava.util.prefs.systemRoot=\"$native_prefs/system\" -Dkotlin.daemon.runFilesPath=\"$native_daemon\" -Djna.tmpdir=\"$native_tmp\""
    export JAVA_OPTS=""
    export GRADLE_OPTS="-Dorg.gradle.java.installations.auto-download=false"

    # Installed SDK/JDK locations remain read-only; no SDK manager, package
    # manager, gh login, global git config, or download runs from this script.
    return 0
}

_veyra_prepare_environment
_veyra_env_status=$?
unset -f _veyra_prepare_environment
if [ "$_veyra_env_status" -ne 0 ]; then
    return "$_veyra_env_status" 2>/dev/null || exit "$_veyra_env_status"
fi
unset _veyra_env_status
