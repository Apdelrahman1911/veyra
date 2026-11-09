#!/usr/bin/env python3
"""Owned, awaited execution of trusted build tools (Python 3.11+, stdlib only).

Windows uses a non-breakaway, kill-on-close Job Object. A Python gate is assigned
before it can start the requested command. POSIX uses a new session and a reserved
tool process group; its keeper remains alive outside that group until it is empty,
so a recycled PID/process-group identifier is never used for cleanup. On Linux an
isolated helper is also a child subreaper, including adopted/detached descendants.
Linux child candidates are confirmed with waitid(WNOWAIT) by that sole reaper;
the optional /proc task/children interface is not assumed to exist.
Other POSIX systems contain the trusted command's process group, not deliberate
setsid()/sandbox escape. This is resource ownership, not an untrusted-code sandbox.

The caller validates repository scope and provides an existing working/log parent
directory and scoped environment. No files are deleted by this module. In
particular, cleanup_complete=False requires retaining the work/logs for recovery.
"""
from __future__ import annotations

import ctypes
import json
import math
import ntpath
import os
from pathlib import Path
import queue
import select
import signal
import stat
import subprocess
import sys
import threading
import time
from typing import BinaryIO, Sequence


_POLL_SECONDS = 0.01
_TERM_SECONDS = 0.5
_CLEANUP_SECONDS = 10.0
_CHILD_SETUP_SECONDS = 5.0
_MAX_CAPTURE_BYTES = 64 * 1024 * 1024
_JOB_KILL_ON_CLOSE = 0x2000


class OwnedProcessError(RuntimeError):
    def __init__(self, reason: str, *, cleanup_complete: bool,
                 returncode: int | None = None, output: bytes = b"", process_state: dict | None = None):
        self.reason = reason
        self.cleanup_complete = cleanup_complete
        self.returncode = returncode
        self.output = output
        self.process_state = process_state
        super().__init__(f"Managed build command failed: {reason}; "
                         f"owned processes {'drained' if cleanup_complete else 'NOT proven drained'}.")


class _Stop(Exception):
    def __init__(self, reason: str, returncode: int | None = None):
        self.reason, self.returncode = reason, returncode


def _ordinary(attributes: os.stat_result, *, directory: bool = False) -> bool:
    return not (getattr(attributes, "st_file_attributes", 0) & 0x400) and (
        stat.S_ISDIR(attributes.st_mode) if directory else stat.S_ISREG(attributes.st_mode)
    ) and (directory or attributes.st_nlink == 1)


def _log_ancestors(path: Path) -> None:
    for parent in path.parents:
        if not _ordinary(parent.lstat(), directory=True):
            raise ValueError("Build log has a linked, special, or unverifiable parent")


def _validate_log(path: Path, stream: BinaryIO, before: os.stat_result | None = None) -> None:
    _log_ancestors(path)
    opened, current = os.fstat(stream.fileno()), path.lstat()
    if not _ordinary(opened) or not _ordinary(current):
        raise ValueError("Build log must be an unlinked ordinary file")
    identity = (opened.st_dev, opened.st_ino)
    if identity != (current.st_dev, current.st_ino) or (
        before is not None and identity != (before.st_dev, before.st_ino)
    ):
        raise ValueError("Build log identity changed while opening it")


def _open_log(path: Path) -> BinaryIO:
    path = Path(os.path.abspath(path))
    _log_ancestors(path)
    try:
        before = path.lstat()
    except FileNotFoundError:
        before = None
    if before is not None and not _ordinary(before):
        raise ValueError("Build log must not be a link, hard link, or special file")
    flags = (os.O_WRONLY | os.O_APPEND | getattr(os, "O_CLOEXEC", 0)
             | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
             | getattr(os, "O_BINARY", 0))
    if before is None:
        flags |= os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    try:
        stream = os.fdopen(descriptor, "ab", buffering=0)
    except BaseException:
        os.close(descriptor)
        raise
    try:
        _validate_log(path, stream, before)
        return stream
    except BaseException:
        stream.close()
        raise


class _JobBasicLimits(ctypes.Structure):
    _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                ("flags", ctypes.c_uint32), ("minimum_working_set", ctypes.c_size_t),
                ("maximum_working_set", ctypes.c_size_t), ("active_process_limit", ctypes.c_uint32),
                ("affinity", ctypes.c_size_t), ("priority", ctypes.c_uint32), ("scheduling", ctypes.c_uint32)]


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]


class _JobExtendedLimits(ctypes.Structure):
    _fields_ = [("basic", _JobBasicLimits), ("io", _IoCounters),
                ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t)]


class _JobAccounting(ctypes.Structure):
    _fields_ = [("user_time", ctypes.c_int64), ("kernel_time", ctypes.c_int64),
                ("period_user_time", ctypes.c_int64), ("period_kernel_time", ctypes.c_int64),
                ("faults", ctypes.c_uint32), ("total", ctypes.c_uint32),
                ("active", ctypes.c_uint32), ("terminated", ctypes.c_uint32)]


class _JobProcessIds(ctypes.Structure):
    _fields_ = [("assigned", ctypes.c_uint32), ("listed", ctypes.c_uint32),
                ("ids", ctypes.c_size_t * 64)]


# Diagnostics retain only known tool basenames, never paths, arguments or env.
_DIAGNOSTIC_TOOL_NAMES = frozenset((
    "cmake.exe", "msbuild.exe", "cl.exe", "link.exe", "mspdbsrv.exe", "vctip.exe",
    "cmd.exe", "python.exe", "python3.exe", "nmake.exe", "ninja.exe", "rc.exe",
    "cvtres.exe", "tracker.exe", "conhost.exe", "powershell.exe", "pwsh.exe",
    "vbcscompiler.exe", "vswhere.exe"))


class _WindowsApi:
    def __init__(self):
        self.dll = ctypes.WinDLL("kernel32", use_last_error=True)
        signatures = {
            "CreateJobObjectW": ([ctypes.c_void_p, ctypes.c_wchar_p], ctypes.c_void_p),
            "SetInformationJobObject": ([ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32], ctypes.c_int),
            "AssignProcessToJobObject": ([ctypes.c_void_p, ctypes.c_void_p], ctypes.c_int),
            "QueryInformationJobObject": ([ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p], ctypes.c_int),
            "OpenProcess": ([ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32], ctypes.c_void_p),
            "IsProcessInJob": ([ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)], ctypes.c_int),
            "QueryFullProcessImageNameW": ([ctypes.c_void_p, ctypes.c_uint32, ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_uint32)], ctypes.c_int),
            "TerminateProcess": ([ctypes.c_void_p, ctypes.c_uint32], ctypes.c_int),
            "WaitForSingleObject": ([ctypes.c_void_p, ctypes.c_uint32], ctypes.c_uint32),
            "TerminateJobObject": ([ctypes.c_void_p, ctypes.c_uint32], ctypes.c_int),
            "CloseHandle": ([ctypes.c_void_p], ctypes.c_int),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.dll, name)
            function.argtypes, function.restype = arguments, result

    @staticmethod
    def _check(value):
        if not value:
            raise ctypes.WinError(ctypes.get_last_error())
        return value

    def create(self):
        # No name, no inheritable security attributes, no shared/global object.
        return self._check(self.dll.CreateJobObjectW(None, None))

    def configure(self, handle, flags):
        limits = _JobExtendedLimits()
        limits.basic.flags = flags
        self._check(self.dll.SetInformationJobObject(handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)))

    def assign(self, handle, process):
        self._check(self.dll.AssignProcessToJobObject(handle, process))

    def active(self, handle):
        accounting = _JobAccounting()
        self._check(self.dll.QueryInformationJobObject(handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None))
        return accounting.active

    def processes(self, handle):
        members = _JobProcessIds()
        self._check(self.dll.QueryInformationJobObject(handle, 3, ctypes.byref(members), ctypes.sizeof(members), None))
        if members.listed > len(members.ids):
            raise ValueError("Invalid owned-job process count")
        result = []
        for pid in list(members.ids)[:min(members.listed, 16)]:
            process = self.dll.OpenProcess(0x1000, False, pid)  # query-only, never terminate access
            if not process:
                continue  # It may have exited after the membership snapshot.
            try:
                member = ctypes.c_int()
                self._check(self.dll.IsProcessInJob(process, handle, ctypes.byref(member)))
                if not member.value:
                    continue  # A recycled PID grants no right to inspect another process.
                capacity = ctypes.c_uint32(32768)
                image = ctypes.create_unicode_buffer(capacity.value)
                self._check(self.dll.QueryFullProcessImageNameW(process, 0, image, ctypes.byref(capacity)))
                name = image.value.replace("\\", "/").rsplit("/", 1)[-1].casefold()
                result.append({"pid": int(pid), "image": name if name in _DIAGNOSTIC_TOOL_NAMES else "<other>"})
            finally:
                self.close(process)
        return {"listed": members.listed, "truncated": members.listed > 16, "members": result}

    def finish_telemetry(self, handle, expected_image):
        """Stop only the exact optional tool remaining after a successful command."""
        members = _JobProcessIds()
        self._check(self.dll.QueryInformationJobObject(handle, 3, ctypes.byref(members), ctypes.sizeof(members), None))
        if members.listed > len(members.ids):
            raise ValueError("Invalid owned-job process count")
        stopped = 0
        for pid in list(members.ids)[:members.listed]:
            # Pin the process object; never terminate a name or a recycled PID.
            process = self.dll.OpenProcess(0x00100000 | 0x1000 | 1, False, pid)
            if not process:
                continue
            try:
                member = ctypes.c_int()
                self._check(self.dll.IsProcessInJob(process, handle, ctypes.byref(member)))
                if not member.value:
                    continue
                capacity = ctypes.c_uint32(32768)
                image = ctypes.create_unicode_buffer(capacity.value)
                self._check(self.dll.QueryFullProcessImageNameW(process, 0, image, ctypes.byref(capacity)))
                if ntpath.normcase(ntpath.normpath(image.value)) != ntpath.normcase(ntpath.normpath(expected_image)):
                    continue
                state = self.dll.WaitForSingleObject(process, 0)
                if state == 0:  # Already terminated.
                    continue
                if state != 258:
                    raise OSError("Cannot verify owned telemetry state")
                if not self.dll.TerminateProcess(process, 0):
                    if self.dll.WaitForSingleObject(process, 0) == 0:
                        continue  # Exited between the wait and termination request.
                    raise OSError("Owned telemetry termination failed")
                if self.dll.WaitForSingleObject(process, 1000) != 0:
                    raise OSError("Owned telemetry termination was not confirmed")
                stopped += 1
            finally:
                self.close(process)
        return stopped

    def terminate(self, handle):
        self._check(self.dll.TerminateJobObject(handle, 1))

    def close(self, handle):
        self._check(self.dll.CloseHandle(handle))


class _WindowsJob:
    def __init__(self, api=None):
        self.api = api if api is not None else _WindowsApi()
        self.handle = self.api.create()
        try:
            # Neither BREAKAWAY_OK nor SILENT_BREAKAWAY_OK is permitted.
            self.api.configure(self.handle, _JOB_KILL_ON_CLOSE)
        except BaseException:
            self.api.close(self.handle)
            self.handle = None
            raise

    def assign(self, process):
        self.api.assign(self.handle, int(process._handle))

    def active(self):
        return self.api.active(self.handle)

    def failure_state(self, control):
        # Best effort and captured BEFORE cleanup; it grants no cleanup authority.
        completed = control.completed if control is not None else None
        state = {"commandCompleted": completed is not None,
                 "commandReturncode": completed["returncode"] if completed else None}
        try:
            state["jobActiveBeforeCleanup"] = self.active()
            state["processes"] = self.api.processes(self.handle)
        except Exception as error:
            state["snapshotError"] = type(error).__name__
        return state

    def finish_telemetry(self, expected_image):
        return self.api.finish_telemetry(self.handle, expected_image)

    def terminate(self):
        self.api.terminate(self.handle)

    def close(self):
        if self.handle is not None:
            self.api.close(self.handle)
            self.handle = None


def _python(arguments: Sequence[str]) -> list[str]:
    return [sys.executable, "-I", "-B", str(Path(__file__).resolve()), *arguments]


def _send(stream: BinaryIO, message: bytes) -> None:
    stream.write(message)
    stream.flush()


def _spawn(command: Sequence[str], **options):
    return subprocess.Popen(command, **options)


def _start_owner(command, cwd, env, output, job):
    """Start/assign an inert gate; the caller installs readers before START."""
    windows = job is not None
    process = _spawn(_python(["--owned-worker", "windows" if windows else "posix", *command]),
                     cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=output,
                     bufsize=0, close_fds=True, start_new_session=not windows)
    try:
        if windows:
            job.assign(process)
        return process
    except BaseException as error:
        # No START has been sent. Independently attempt Job and gated-child
        # cleanup: failure of TerminateJobObject must not skip kill()/wait().
        if windows:
            try:
                job.terminate()
            except BaseException:
                pass
        try:
            process.kill()
        except BaseException:
            pass
        drained = False
        try:
            process.wait(timeout=_CLEANUP_SECONDS)
            drained = not windows or job.active() == 0
        except BaseException:
            pass
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except BaseException:
                    drained = False
        raise OwnedProcessError("start_failed" if drained else "cleanup_failed", cleanup_complete=drained) from error


class _Control:
    def __init__(self, stream):
        self.messages = queue.Queue(maxsize=4)
        self.completed = None
        self.done = threading.Event()
        self.failed = threading.Event()
        self.thread = threading.Thread(target=self._read, args=(stream,), daemon=True)
        self.thread.start()

    def _read(self, stream):
        try:
            while line := stream.readline(4097):
                if len(line) > 4096:
                    raise ValueError("Oversized managed-owner response")
                message = json.loads(line)
                if not isinstance(message, dict) or message.get("event") not in ("complete", "cleanup_failed"):
                    raise ValueError("Invalid managed-owner response")
                if message["event"] == "complete" and (
                    type(message.get("returncode")) is not int or type(message.get("drained")) is not bool
                ):
                    raise ValueError("Invalid managed-owner completion")
                if message["event"] == "complete":
                    self.completed = message
                self.messages.put_nowait(message)
        except BaseException:
            self.failed.set()
        finally:
            self.done.set()

    def take(self):
        result = []
        while True:
            try:
                result.append(self.messages.get_nowait())
            except queue.Empty:
                return result


class _Capture:
    def __init__(self, stream, limit):
        self.output = bytearray()
        self.limit = limit
        self.overflow = threading.Event()
        self.failed = threading.Event()
        self.done = threading.Event()
        self.thread = threading.Thread(target=self._read, args=(stream,), daemon=True)
        self.thread.start()

    def _read(self, stream):
        try:
            while block := stream.read(65_536):
                remaining = self.limit - len(self.output)
                self.output.extend(block[:remaining])
                if len(block) > remaining:
                    self.overflow.set()
                # Continue draining without retaining overflow during termination.
        except BaseException:
            self.failed.set()
        finally:
            self.done.set()


def _monitor(process, job, control, captured, deadline, cancel, completed_telemetry_image=None):
    completed = None
    while True:
        for message in control.take():
            if message["event"] == "cleanup_failed":
                raise _Stop("cleanup_failed")
            completed = message
        if control.failed.is_set() or (captured is not None and captured.failed.is_set()):
            raise _Stop("owner_io_failed")
        if captured is not None and captured.overflow.is_set():
            raise _Stop("output_limit")
        if cancel is not None and cancel.is_set():
            raise _Stop("cancelled")
        if time.monotonic() >= deadline:
            raise _Stop("timeout")
        if completed is not None and completed["returncode"] != 0:
            raise _Stop("command_failed", completed["returncode"])
        owner_returncode = process.poll()
        if owner_returncode is not None and control.done.is_set():
            if owner_returncode != 0:
                raise _Stop("owner_failed", owner_returncode)
            if completed is None:
                raise _Stop("owner_failed")
            if job is not None:
                if job.active() == 0:
                    return completed["returncode"]
                if completed_telemetry_image is not None:
                    try:
                        stopped = job.finish_telemetry(completed_telemetry_image)
                    except Exception:
                        raise _Stop("telemetry_cleanup_failed") from None
                    if stopped:
                        print(f"Stopped {stopped} owned MSVC telemetry helper(s) after successful command completion.",
                              file=sys.stderr, flush=True)
            elif completed["drained"]:
                return completed["returncode"]
            else:
                raise _Stop("cleanup_failed")
        time.sleep(_POLL_SECONDS)


def _drain_windows(process, job, deadline):
    try:
        job.terminate()
    except BaseException:
        pass
    try:
        while time.monotonic() < deadline:
            try:
                if job.active() == 0:
                    process.wait(timeout=max(0.01, deadline - time.monotonic()))
                    return True
                time.sleep(_POLL_SECONDS)
            except KeyboardInterrupt:
                continue
    except BaseException:
        pass
    return False


def _drain_posix(process, control, deadline):
    try:
        _send(process.stdin, b"cancel\n")
    except (BrokenPipeError, OSError, ValueError):
        pass
    try:
        process.stdin.close()  # Owner also treats caller death/EOF as cancellation.
    except (OSError, ValueError):
        pass
    completed = control.completed
    while time.monotonic() < deadline:
        try:
            for message in control.take():
                if message["event"] == "complete":
                    completed = message
            completed = control.completed or completed
            if process.poll() is not None and control.done.is_set():
                return (process.returncode == 0 and completed is not None
                        and completed["drained"] and not control.failed.is_set())
            time.sleep(_POLL_SECONDS)
        except KeyboardInterrupt:
            continue
        except BaseException:
            return False
    return False


def _execute(command, *, cwd, env, timeout, log=None, max_output_bytes=1024 * 1024, cancel=None,
             completed_telemetry_image=None):
    if sys.version_info < (3, 11):
        raise ValueError("Managed build tools require Python 3.11 or newer")
    if isinstance(command, (str, bytes)):
        raise ValueError("Commands must be argument lists, never shell strings")
    command = list(command)
    if not command or any(not isinstance(value, str) or not value or "\0" in value for value in command):
        raise ValueError("A nonempty direct argument list is required")
    if not math.isfinite(timeout) or timeout <= 0 or type(max_output_bytes) is not int or not 0 < max_output_bytes <= _MAX_CAPTURE_BYTES:
        raise ValueError("A finite positive timeout and bounded capture size are required")
    if completed_telemetry_image is not None:
        if (os.name != "nt" or not isinstance(completed_telemetry_image, str)
                or not ntpath.isabs(completed_telemetry_image)
                or ntpath.basename(completed_telemetry_image).casefold() != "vctip.exe"
                or ".." in completed_telemetry_image.replace("/", "\\").split("\\")
                or "\0" in completed_telemetry_image):
            raise ValueError("Only an exact absolute Windows MSVC telemetry image is permitted")
    if cancel is not None and cancel.is_set():
        raise OwnedProcessError("cancelled", cleanup_complete=True)
    log_path = Path(os.path.abspath(log)) if log is not None else None
    output = _open_log(log_path) if log_path is not None else None
    job = None
    process = None
    control = None
    captured = None
    released = False
    deadline = time.monotonic() + timeout
    try:
        if os.name == "nt":
            job = _WindowsJob()
        process = _start_owner(command, cwd, env, output if output is not None else subprocess.PIPE, job)
        try:
            control = _Control(process.stdout)
            if output is None:
                captured = _Capture(process.stderr, max_output_bytes)
            if cancel is not None and cancel.is_set():
                raise _Stop("cancelled")
            if time.monotonic() >= deadline:
                raise _Stop("timeout")
            # Mark as released BEFORE attempting the write: a partial/failed
            # write must never justify treating a possibly running tool as gated.
            released = True
            _send(process.stdin, b"start\n")
            _monitor(process, job, control, captured, deadline, cancel, completed_telemetry_image)
            if captured is not None:
                if not captured.done.wait(_CLEANUP_SECONDS) or captured.failed.is_set():
                    raise _Stop("owner_io_failed")
                if captured.overflow.is_set():
                    raise _Stop("output_limit")
                return bytes(captured.output)
            _validate_log(log_path, output)
            return b""
        except BaseException as error:
            process_state = job.failure_state(control) if job is not None else None
            drain_deadline = time.monotonic() + _CLEANUP_SECONDS + 2
            if job is not None:
                drained = _drain_windows(process, job, drain_deadline)
            elif not released:
                # Reader/setup failures cannot leave a requested command behind:
                # the only child is still waiting for START.
                try:
                    process.kill()
                except BaseException:
                    pass
                try:
                    process.wait(timeout=max(0.01, drain_deadline - time.monotonic()))
                    drained = True
                except BaseException:
                    drained = False
            else:
                drained = _drain_posix(process, control, drain_deadline)
            if drained and captured is not None:
                drained = captured.done.wait(max(0, drain_deadline - time.monotonic()))
            retained = bytes(captured.output) if captured is not None else b""
            if not drained:
                raise OwnedProcessError("cleanup_failed", cleanup_complete=False, output=retained,
                                        process_state=process_state) from error
            if isinstance(error, _Stop):
                raise OwnedProcessError(error.reason, cleanup_complete=True,
                                        returncode=error.returncode, output=retained, process_state=process_state) from None
            raise
    finally:
        # No work/log deletion, including any failure to establish a drained tree.
        close_failure = None
        if job is not None:
            try:
                job.close()
            except BaseException as error:
                close_failure = error
        if process is not None:
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream is not None:
                    try:
                        stream.close()
                    except BaseException as error:
                        if close_failure is None:
                            close_failure = error
        if output is not None:
            try:
                output.close()
            except BaseException as error:
                if close_failure is None:
                    close_failure = error
        if close_failure is not None:
            raise OwnedProcessError("cleanup_failed", cleanup_complete=False) from close_failure


def run(command: Sequence[str], *, cwd: Path, env: dict[str, str], timeout: float,
        log: Path, cancel: threading.Event | None = None,
        completed_telemetry_image: str | None = None) -> None:
    """Await all owners; optionally close exact owned MSVC telemetry after success."""
    _execute(command, cwd=cwd, env=env, timeout=timeout, log=log, cancel=cancel,
             completed_telemetry_image=completed_telemetry_image)


def capture(command: Sequence[str], *, cwd: Path, env: dict[str, str], timeout: float = 20,
            max_output_bytes: int = 1024 * 1024, cancel: threading.Event | None = None) -> bytes:
    """Return bounded combined output, or stop/drain before reporting overflow."""
    return _execute(command, cwd=cwd, env=env, timeout=timeout, max_output_bytes=max_output_bytes, cancel=cancel)


def _emit(message):
    try:
        # One bounded control write, without a buffered stdout object that may
        # fail again during interpreter shutdown after the caller has exited.
        os.write(1, json.dumps(message, separators=(",", ":")).encode("ascii") + b"\n")
    except (BrokenPipeError, OSError):
        pass  # Caller death must not interrupt the owner's cleanup.


def _gate(expected: bytes) -> bool:
    # Raw reads avoid buffered read-ahead between START and CANCEL. In the
    # cancellation daemon they also avoid CPython's buffered-stdin shutdown lock.
    value = bytearray()
    while len(value) < 64:
        byte = os.read(0, 1)
        if not byte:
            break
        value.extend(byte)
        if byte == b"\n":
            break
    return bytes(value) == expected


def _keeper(parent_group: int) -> int:
    print("ready", flush=True)
    if not _gate(b"move\n"):
        return 1
    os.setpgid(0, parent_group)
    print("moved", flush=True)
    return 0 if _gate(b"quit\n") else 1


def _command_gate(command) -> int:
    if not _gate(b"exec\n"):
        return 125
    descriptor = os.open(os.devnull, os.O_RDONLY)
    os.dup2(descriptor, 0)
    if descriptor != 0:
        os.close(descriptor)
    os.execvpe(command[0], command, os.environ)
    return 125


def _read_line(stream, expected: bytes, cancelled) -> None:
    deadline = time.monotonic() + _CHILD_SETUP_SECONDS
    value = bytearray()
    while time.monotonic() < deadline and not cancelled.is_set():
        ready, _, _ = select.select([stream], [], [], _POLL_SECONDS)
        if ready:
            byte = stream.read(1)
            if not byte:
                break
            value.extend(byte)
            if byte == b"\n":
                if bytes(value) == expected:
                    return
                break
            if len(value) > 64:
                break
    raise RuntimeError("Owned process-group setup did not complete")


def _linux_subreaper() -> bool:
    if not sys.platform.startswith("linux"):
        return False
    libc = ctypes.CDLL(None, use_errno=True)
    prctl = libc.prctl
    prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
    prctl.restype = ctypes.c_int
    if prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER; only this isolated helper.
        raise OSError(ctypes.get_errno(), "Cannot establish owned descendant reaping")
    # Establish every required facility before any keeper or tool can start.
    # Some kernels omit /proc/*/task/*/children; waitid must still be available.
    try:
        os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    except ChildProcessError:
        pass
    _children()
    return True


def _proc_child_candidates() -> set[int]:
    """Read direct-parent candidates, never a recursive or name-based PID tree."""
    owner = os.getpid()
    own_stat = Path("/proc/self/stat").read_text()
    if int(own_stat.split(" ", 1)[0]) != owner:
        raise RuntimeError("The proc filesystem does not identify this owner")
    try:
        return {int(value) for value in Path(f"/proc/self/task/{owner}/children").read_text().split()}
    except FileNotFoundError:
        # Optional kernel interface (CONFIG_CHECKPOINT_RESTORE). The fallback
        # finds only candidate direct children; waitid below is the authority.
        candidates = set()
        with os.scandir("/proc") as entries:
            for entry in entries:
                if not entry.name.isdecimal():
                    continue
                try:
                    value = Path(entry.path, "stat").read_text()
                except (FileNotFoundError, ProcessLookupError, PermissionError):
                    # Never silently omit a possibly owned child. The kernel
                    # waitid check below distinguishes ours from inaccessible
                    # or disappearing unrelated processes without signaling any.
                    candidates.add(int(entry.name))
                    continue
                fields = value[value.rfind(")") + 2:].split()
                if len(fields) < 2:
                    raise RuntimeError("Invalid proc process identity")
                if int(fields[1]) == owner:
                    candidates.add(int(entry.name))
        return candidates


def _children() -> set[int]:
    # Kernel-confirmed DIRECT children of this isolated sole reaper. WNOWAIT
    # keeps exited children/PIDs reserved until this same thread explicitly
    # waits; no other thread can reap/reuse a PID between confirmation and kill.
    children = set()
    for pid in _proc_child_candidates():
        try:
            os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except ChildProcessError:
            continue
        children.add(pid)
    return children


def _group_exists(group: int) -> bool:
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False


def _stop_group(group: int, sig: int) -> None:
    try:
        os.killpg(group, sig)
    except ProcessLookupError:
        pass


def _control_cancellation(cancelled):
    try:
        _gate(b"cancel\n")  # CANCEL, EOF, or malformed control all stop the tree.
    except BaseException:
        pass
    finally:
        cancelled.set()


def _posix_tree(command) -> None:
    cancelled = threading.Event()
    # PID reservation requires unreaped children, even if the caller inherited
    # SIGCHLD=SIG_IGN. This mutation is confined to our isolated Python owner.
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    try:
        linux = _linux_subreaper()
    except BaseException:
        _emit({"event": "complete", "returncode": 125, "drained": True})
        return  # Nothing was spawned; unsupported ownership fails before EXEC.
    signal.signal(signal.SIGTERM, lambda *_: cancelled.set())
    signal.signal(signal.SIGINT, lambda *_: cancelled.set())
    threading.Thread(target=_control_cancellation, args=(cancelled,), daemon=True).start()
    keeper = None
    child = None
    executing = False
    failed = False
    try:
        keeper = subprocess.Popen(_python(["--owned-keeper", str(os.getpgrp())]), stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0,
                                  close_fds=True, process_group=0)
        _read_line(keeper.stdout, b"ready\n", cancelled)
        child = subprocess.Popen(_python(["--owned-command", *command]), stdin=subprocess.PIPE,
                                 stdout=sys.stderr, stderr=subprocess.STDOUT, bufsize=0,
                                 close_fds=True, process_group=keeper.pid)
        _send(keeper.stdin, b"move\n")
        _read_line(keeper.stdout, b"moved\n", cancelled)
        # Both gates are established; the group identifier stays reserved by keeper.
        executing = True
        _send(child.stdin, b"exec\n")
        child.stdin.close()
    except BaseException:
        failed = True
        cancelled.set()
    if not executing:
        # These children never received EXEC; no compiler or grandchildren exist.
        drained = True
        for process in (child, keeper):
            if process is not None:
                try:
                    process.kill()
                    process.wait(timeout=_CLEANUP_SECONDS)
                except BaseException:
                    drained = False
        _emit({"event": "complete", "returncode": 125, "drained": drained})
        return
    stopping = None
    warned = False
    while True:
        try:
            child.poll()
        except BaseException:
            failed = True
        stopping_requested = cancelled.is_set() or failed or (child.returncode is not None and child.returncode != 0)
        if stopping_requested:
            if stopping is None:
                stopping = time.monotonic()
            sig = signal.SIGKILL if time.monotonic() - stopping >= _TERM_SECONDS else signal.SIGTERM
            try:
                # Process-group cleanup is independent of proc enumeration. A
                # later discovery failure must not skip stopping known tools.
                _stop_group(keeper.pid, sig)
            except BaseException:
                failed = True
        adopted = set()
        try:
            if linux and (stopping_requested or child.returncode is not None):
                adopted = _children() - {keeper.pid, child.pid}
        except BaseException:
            failed = True
        for pid in adopted:
            if stopping_requested:
                try:
                    # Kernel-confirmed direct unreaped child; no PID can be
                    # reused until this same thread explicitly reaps it below.
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
                except BaseException:
                    failed = True
            try:
                os.waitpid(pid, os.WNOHANG)
            except BaseException:
                failed = True
        try:
            remaining = _children() - {keeper.pid} if linux and child.returncode is not None else set()
            if child.returncode is not None and not _group_exists(keeper.pid) and not remaining:
                break
        except BaseException:
            failed = True
        if stopping is not None and time.monotonic() - stopping > _CLEANUP_SECONDS and not warned:
            _emit({"event": "cleanup_failed"})
            warned = True
            # Keep ownership/PGID reservation while retrying, never falsely report clean.
        time.sleep(_POLL_SECONDS)
    drained = False
    try:
        _send(keeper.stdin, b"quit\n")
    except BaseException:
        failed = True
    try:
        keeper.stdin.close()
    except BaseException:
        failed = True
    try:
        keeper.wait(timeout=_CLEANUP_SECONDS)
        drained = True
        failed = failed or keeper.returncode != 0
    except BaseException:
        try:
            keeper.kill()
            keeper.wait(timeout=_CLEANUP_SECONDS)
            drained = True
        except BaseException:
            pass
        failed = True
    for process in (child, keeper):
        for stream in (process.stdin, process.stdout):
            if stream is not None:
                try:
                    stream.close()
                except BaseException:
                    drained = False
    if not drained:
        _emit({"event": "cleanup_failed"})
    _emit({"event": "complete", "returncode": 125 if failed else child.returncode, "drained": drained})


def _worker(kind: str, command) -> int:
    if not _gate(b"start\n"):
        return 125
    if kind == "posix":
        try:
            _posix_tree(command)
        except BaseException:
            # Before the tree function owns children (e.g. unavailable subreaper).
            _emit({"event": "complete", "returncode": 125, "drained": False})
        return 0
    if kind == "windows" and os.name == "nt":
        try:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=sys.stderr,
                                       stderr=subprocess.STDOUT, close_fds=True)
            returncode = process.wait()
        except BaseException:
            returncode = 125
        # The parent, not this gate, proves that the entire Job reaches zero active processes.
        _emit({"event": "complete", "returncode": returncode, "drained": False})
        return 0
    return 125


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--owned-worker":
        raise SystemExit(_worker(sys.argv[2], sys.argv[3:]))
    if len(sys.argv) == 3 and sys.argv[1] == "--owned-keeper" and os.name == "posix":
        raise SystemExit(_keeper(int(sys.argv[2])))
    if len(sys.argv) >= 3 and sys.argv[1] == "--owned-command" and os.name == "posix":
        raise SystemExit(_command_gate(sys.argv[2:]))
    raise SystemExit("This module is an owned build-process helper, not a general shell.")
