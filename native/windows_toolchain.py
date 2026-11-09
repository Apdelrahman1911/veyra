#!/usr/bin/env python3
"""Private per-child Windows MSVC environment from existing Visual Studio.

The repository already installs no compilers and must not change global state.
For a Windows native command this helper finds the existing Visual Studio
installation through the image's own vswhere.exe, asks its VsDevCmd.bat for the
matching x64 developer environment in a bounded child process, and returns the
captured environment for that one native child only. The parent environment is
never mutated, no GitHub environment file is written, and no fallback compiler
is selected. A missing, archived or mismatched toolchain is a truthful error.
"""
from __future__ import annotations

import locale
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import tempfile
from typing import Callable, Mapping

VSWHERE_COMPONENT = "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"
VSDEVCMD_TARGET_ARCH = "amd64"
VSDEVCMD_HOST_ARCH = "amd64"
MSVC_TOOLS = ("cl.exe", "lib.exe", "dumpbin.exe")
VSWHERE_TIMEOUT_SECONDS = 30.0
VSDEVCMD_TIMEOUT_SECONDS = 90.0
CAPTURE_MAX_BYTES = 4 * 1024 * 1024

_X64_ARCH_NAMES = frozenset(("x64", "amd64"))
_SAFE_ENVIRONMENT_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_()]*")
_VSDEVCMD_VARIABLE = "VEYRA_VSDEVCMD_PATH"
_SCRATCH_BATCH = (
    "@echo off\r\n"
    f'call "%VEYRA_VSDEVCMD_PATH%" -no_logo -arch={VSDEVCMD_TARGET_ARCH} '
    f"-host_arch={VSDEVCMD_HOST_ARCH} >nul\r\n"
    "if errorlevel 1 exit /b 1\r\n"
    "set\r\n"
).encode("ascii")

# scripts/env.sh owns these homes/caches/temp/preferences. A Visual Studio
# setup must not redirect them to a global profile or system temp directory.
_PRESERVED_ENVIRONMENT = frozenset(name.casefold() for name in (
    "HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP",
    "GRADLE_USER_HOME", "KONAN_DATA_DIR",
    "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME",
    "XDG_RUNTIME_DIR", "APPDATA", "LOCALAPPDATA", "CFFIXED_USER_HOME",
    "ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ANDROID_EMULATOR_HOME",
    "CLANG_MODULE_CACHE_PATH", "SWIFT_MODULECACHE_PATH", "PYTHONPYCACHEPREFIX",
    "npm_config_cache", "npm_config_userconfig", "npm_config_prefix",
    "PIP_CACHE_DIR", "XAUTHORITY", "GIT_CONFIG_GLOBAL", "JAVA_HOME",
    "JAVA_TOOL_OPTIONS", "GRADLE_OPTS", "JAVA_OPTS",
    "VEYRA_PROJECT_ROOT", "VEYRA_WORK_ROOT", "VEYRA_SCOPED_ENV",
    "VEYRA_TRUSTED_BASH", "VEYRA_DISPLAY_AUTHORITY",
    "VEYRA_DISPLAY_MOUNT_NAMESPACE", "VEYRA_EXPECTED_OS", "VEYRA_EXPECTED_ARCH",
    "BUILD_PACKAGES", "PYTHONDONTWRITEBYTECODE",
))

Capture = Callable[[list[str], Mapping[str, str], float], bytes]


def _get(environment: Mapping[str, str], name: str) -> str | None:
    wanted = name.casefold()
    for key, value in environment.items():
        if key.casefold() == wanted:
            return value
    return None


def _replace(environment: dict[str, str], name: str, value: str) -> None:
    for existing in [key for key in environment if key.casefold() == name.casefold()]:
        del environment[existing]
    environment[name] = value


def _hostx64_tool(tool: str) -> bool:
    parts = [part.casefold() for part in PureWindowsPath(tool).parts]
    return any(
        parts[index] == "hostx64" and parts[index + 1] == "x64"
        for index in range(len(parts) - 1)
    )


def _matching_tools(environment: Mapping[str, str], which: Callable[..., str | None]) -> bool:
    path = _get(environment, "PATH")
    if not path:
        return False
    for tool in MSVC_TOOLS:
        found = which(tool, path=path)
        if not isinstance(found, str) or not _hostx64_tool(found):
            return False
    return True


def _sdk_environment(environment: Mapping[str, str]) -> bool:
    # Hostx64\x64\cl.exe alone is not a usable developer environment: the
    # compiler still needs the Windows SDK/VC include and library paths.
    include = _get(environment, "INCLUDE")
    library = _get(environment, "LIB")
    return bool(include and include.strip() and library and library.strip())


def _is_matching_x64_environment(environment: Mapping[str, str],
                                  which: Callable[..., str | None]) -> bool:
    target = _get(environment, "VSCMD_ARG_TGT_ARCH")
    host = _get(environment, "VSCMD_ARG_HOST_ARCH")
    if target is not None and target.casefold() not in _X64_ARCH_NAMES:
        return False
    if host is not None and host.casefold() not in _X64_ARCH_NAMES:
        return False
    return _sdk_environment(environment) and _matching_tools(environment, which)


def vswhere_path(environment: Mapping[str, str], *,
                 is_file: Callable[[str], bool] = os.path.isfile) -> str:
    program_files = _get(environment, "ProgramFiles(x86)")
    if not program_files:
        raise ValueError("Existing Visual Studio locator vswhere.exe is required; no toolchain download or fallback is used.")
    candidate = str(PureWindowsPath(program_files, "Microsoft Visual Studio", "Installer", "vswhere.exe"))
    if not is_file(candidate):
        raise ValueError("Existing Visual Studio locator vswhere.exe is required; no toolchain download or fallback is used.")
    return candidate


def _decode_vswhere(data: bytes) -> str:
    if not isinstance(data, bytes):
        raise ValueError("Existing Visual Studio locator returned an invalid response; no fallback is used.")
    for encoding in ("utf-8-sig", locale.getpreferredencoding(False)):
        try:
            return data.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return data.decode("utf-8", errors="replace")


def _find_vsdevcmd(environment: Mapping[str, str], locator: str, *,
                   capture: Capture, is_file: Callable[[str], bool]) -> str:
    command = [locator, "-latest", "-products", "*", "-requires", VSWHERE_COMPONENT,
               "-property", "installationPath", "-utf8"]
    try:
        data = capture(command, environment, VSWHERE_TIMEOUT_SECONDS)
    except Exception as error:
        if getattr(error, "cleanup_complete", True) is False:
            raise ValueError("Visual Studio environment setup child cleanup was not proven; "
                             "no native command was started.") from None
        raise ValueError("Existing Visual Studio installation could not be queried; no fallback is used.") from None
    lines = [line.strip() for line in _decode_vswhere(data).splitlines() if line.strip()]
    installations = [line for line in lines if PureWindowsPath(line).is_absolute()]
    if len(installations) != 1:
        raise ValueError("No existing Visual Studio installation with x64 C++ tools was found; no toolchain download or fallback is used.")
    installation = PureWindowsPath(installations[0])
    if ".." in installation.parts:
        raise ValueError("Existing Visual Studio installation path is invalid; no fallback is used.")
    candidate = str(installation / "Common7" / "Tools" / "VsDevCmd.bat")
    if not is_file(candidate):
        raise ValueError("Existing Visual Studio VsDevCmd.bat is required; no fallback is used.")
    return candidate


def _prepare_scratch(scratch: Path) -> None:
    if scratch.is_symlink() or (scratch.exists() and not scratch.is_dir()):
        raise ValueError("Windows toolchain scratch path is not a safe directory; no setup is attempted.")
    scratch.mkdir(parents=True, exist_ok=True)


def _decode_environment(data: bytes) -> str:
    if not isinstance(data, bytes):
        raise ValueError("Existing x64 MSVC developer environment returned invalid output; no fallback is used.")
    if data.startswith(b"\xff\xfe"):
        text = data[2:].decode("utf-16-le", errors="replace")
    elif data.startswith(b"\xfe\xff"):
        text = data[2:].decode("utf-16-be", errors="replace")
    elif data and data.count(b"\x00") >= max(1, len(data) // 4):
        text = data.decode("utf-16-le", errors="replace")
    else:
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = data.decode(locale.getpreferredencoding(False), errors="replace")
    return text.lstrip("\ufeff")


def _parse_environment(data: bytes) -> dict[str, str]:
    captured: dict[str, str] = {}
    for line in _decode_environment(data).splitlines():
        key, separator, value = line.partition("=")
        if not separator or not _SAFE_ENVIRONMENT_NAME.fullmatch(key):
            continue
        captured[key] = value
    return captured


def _environment_after_vsdevcmd(vsdevcmd: str, inherited: Mapping[str, str], scratch: Path, *,
                                capture: Capture) -> dict[str, str]:
    try:
        _prepare_scratch(scratch)
    except OSError:
        raise ValueError("Windows toolchain scratch directory could not be created; no setup is attempted.") from None
    try:
        descriptor, name = tempfile.mkstemp(prefix="veyra-vsdevcmd-", suffix=".cmd", dir=scratch)
    except OSError:
        raise ValueError("Windows toolchain scratch file could not be created; no setup is attempted.") from None
    batch = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(_SCRATCH_BATCH)
        capture_environment = dict(inherited)
        _replace(capture_environment, _VSDEVCMD_VARIABLE, vsdevcmd)
        comspec = _get(inherited, "ComSpec") or "cmd.exe"
        # A built-in precedes the quoted batch path, avoiding cmd /s /c
        # stripping the leading executable-path quotes when it contains spaces.
        command = [comspec, "/u", "/d", "/c", "call", str(batch)]
        try:
            data = capture(command, capture_environment, VSDEVCMD_TIMEOUT_SECONDS)
        except Exception as error:
            if getattr(error, "cleanup_complete", True) is False:
                raise ValueError("Visual Studio environment setup child cleanup was not proven; "
                                 "no native command was started.") from None
            raise ValueError("Existing x64 MSVC developer environment could not be initialized; "
                             "no fallback compiler is used.") from None
    finally:
        try:
            batch.unlink()
        except OSError:
            pass
    captured = _parse_environment(data)
    if not captured:
        raise ValueError("Existing x64 MSVC developer environment returned no environment; no fallback is used.")
    return captured


def _path_identity(value: str) -> str:
    return str(PureWindowsPath(value)).rstrip("\\/").casefold()


def _merge_paths(captured: str | None, inherited: str | None,
                 trusted_bash: str | None = None) -> str | None:
    if captured is None and inherited is None:
        return None
    merged: list[str] = []
    seen: set[str] = set()
    for value in (captured, inherited):
        if value is None:
            continue
        for entry in value.split(";"):
            entry = entry.strip().strip('"')
            if not entry:
                continue
            key = _path_identity(entry)
            if key not in seen:
                seen.add(key)
                merged.append(entry)
    if trusted_bash:
        parent = str(PureWindowsPath(trusted_bash).parent)
        identity = _path_identity(parent)
        if identity not in ("", "."):
            for index, entry in enumerate(merged):
                if _path_identity(entry) == identity:
                    merged.insert(0, merged.pop(index))
                    break
            else:
                merged.insert(0, parent)
    return ";".join(merged)


def _merge_environment(inherited: Mapping[str, str],
                       captured: Mapping[str, str]) -> dict[str, str]:
    merged = dict(inherited)
    for key, value in captured.items():
        if not isinstance(value, str) or not _SAFE_ENVIRONMENT_NAME.fullmatch(key):
            continue
        folded = key.casefold()
        if folded == "path" or folded in _PRESERVED_ENVIRONMENT or folded.startswith("veyra_"):
            continue
        _replace(merged, key, value)
    path = _merge_paths(_get(captured, "PATH"), _get(inherited, "PATH"),
                        _get(inherited, "VEYRA_TRUSTED_BASH"))
    if path is not None:
        _replace(merged, "PATH", path)
    return merged


def _validate_setup(environment: Mapping[str, str], captured: Mapping[str, str],
                    which: Callable[..., str | None]) -> None:
    target = _get(captured, "VSCMD_ARG_TGT_ARCH")
    host = _get(captured, "VSCMD_ARG_HOST_ARCH")
    if target is not None and target.casefold() not in _X64_ARCH_NAMES:
        raise ValueError("Existing MSVC developer environment target is not x64; no substitute compiler is used.")
    if host is not None and host.casefold() not in _X64_ARCH_NAMES:
        raise ValueError("Existing MSVC developer environment host is not x64; no substitute compiler is used.")
    if not _sdk_environment(captured) or not _sdk_environment(environment):
        raise ValueError("Existing MSVC include/library (SDK) environment is required; no substitute is used.")
    if not _matching_tools(environment, which):
        raise ValueError("Existing x64 MSVC compiler tools are required; no substitute compiler is used.")


def child_environment(inherited: Mapping[str, str], *, scratch: Path, windows: bool | None = None,
                      capture: Capture | None = None, is_file: Callable[[str], bool] = os.path.isfile,
                      which: Callable[..., str | None] = shutil.which) -> dict[str, str]:
    """Return a private child environment; never mutate the caller or process env."""
    environment = dict(inherited)
    if windows is None:
        windows = os.name == "nt"
    if not windows:
        return environment
    if _is_matching_x64_environment(environment, which):
        return environment
    scratch = Path(scratch)
    if capture is None:
        def capture(command: list[str], child: Mapping[str, str], timeout: float) -> bytes:
            import owned_process
            # vswhere is the first child, before the batch-file setup creates
            # scratch. Fresh checkouts need its working directory here too.
            _prepare_scratch(scratch)
            return owned_process.capture(command, cwd=scratch, env=dict(child), timeout=timeout,
                                         max_output_bytes=CAPTURE_MAX_BYTES)
    locator = vswhere_path(environment, is_file=is_file)
    vsdevcmd = _find_vsdevcmd(environment, locator, capture=capture, is_file=is_file)
    captured = _environment_after_vsdevcmd(vsdevcmd, environment, scratch, capture=capture)
    merged = _merge_environment(environment, captured)
    _validate_setup(merged, captured, which)
    return merged
