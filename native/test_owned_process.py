#!/usr/bin/env python3
"""Authored lightweight process fixtures; no compiler, Gradle, or system changes."""
from __future__ import annotations

import ctypes
from contextlib import nullcontext
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("veyra_owned_process", Path(__file__).with_name("owned_process.py"))
OWNED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OWNED)


def python(code: str, *arguments: str) -> list[str]:
    return [sys.executable, "-I", "-B", "-c", code, *map(str, arguments)]


def alive(pid: int) -> bool:
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        kernel.WaitForSingleObject.restype = ctypes.c_uint32
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle.restype = ctypes.c_int
        handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE, never terminate access.
        if not handle:
            return False
        try:
            return kernel.WaitForSingleObject(handle, 0) == 258  # WAIT_TIMEOUT
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


class OwnedProcessTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / ".work/native-owned-process-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="case-", dir=parent))
        self.env = dict(os.environ)
        self.owners = []
        spawn = OWNED._spawn
        def recorded_spawn(command, **options):
            process = spawn(command, **options)
            self.owners.append(process)
            record = {"pid": process.pid, "argv": list(command), "cwd": str(self.directory)}
            if sys.platform.startswith("linux"):
                value = Path(f"/proc/{process.pid}/stat").read_text()
                fields = value[value.rfind(")") + 2:].split()
                record.update(start_ticks=int(fields[19]), session=int(fields[3]), group=int(fields[2]))
            with (self.directory / "owners.jsonl").open("a") as receipt:
                receipt.write(json.dumps(record) + "\n")
            return process
        self.spawn_patch = patch.object(OWNED, "_spawn", side_effect=recorded_spawn)
        self.spawn_patch.start()
        self.addCleanup(self.spawn_patch.stop)

    def tearDown(self):
        # Retain tiny task-owned fixtures/logs, including any unproven cleanup.
        # A failed ownership assertion must never delete work used by a live child.
        for process in self.owners:
            self.assertIsNotNone(process.poll(), f"Owned gate {process.pid} is still live; "
                                 f"preserved identity receipt at {self.directory}; stop before any rerun")

    def capture(self, command, **options):
        return OWNED.capture(command, cwd=self.directory, env=self.env, **options)

    def assert_drained(self, path):
        self.assertTrue(path.is_file(), "Fixture never established its process identities")
        pids = [int(value) for value in path.read_text().split()]
        self.assertTrue(pids)
        for pid in pids:
            self.assertFalse(alive(pid), f"Owned fixture process {pid} was not awaited/reaped")

    def tree(self, path, *, exitcode=None, flood=False, detached=False):
        descendant = "import signal,time; signal.signal(signal.SIGTERM,lambda *_:None); time.sleep(60)"
        return python(
            "import os,pathlib,subprocess,sys,time\n"
            f"child=subprocess.Popen([sys.executable,'-I','-B','-c',{descendant!r}],start_new_session={detached!r})\n"
            "pathlib.Path(sys.argv[1]).write_text(str(os.getpid())+' '+str(child.pid))\n"
            "print('authored fixture ready',flush=True)\n"
            + (f"sys.exit({exitcode})\n" if exitcode is not None else
               "while True:\n print('x'*8192,flush=True)\n" if flood else "time.sleep(60)\n"), path)

    def test_capture_combines_bounded_stdout_and_stderr(self):
        output = self.capture(python("import sys; print('authored stdout',flush=True); print('authored stderr',file=sys.stderr,flush=True)"))
        self.assertEqual(b"authored stdout\nauthored stderr\n", output.replace(b"\r\n", b"\n"))

    def test_successful_run_appends_combined_output_and_returns_none(self):
        log = self.directory / "build.log"
        log.write_bytes(b"retained earlier log\n")
        result = OWNED.run(python("import sys; print('out',flush=True); print('err',file=sys.stderr,flush=True)"),
                           cwd=self.directory, env=self.env, timeout=10, log=log)
        self.assertIsNone(result)
        self.assertEqual(b"retained earlier log\nout\nerr\n", log.read_bytes().replace(b"\r\n", b"\n"))

    def test_control_reader_setup_failure_never_releases_tool(self):
        marker = self.directory / "must-not-exist"
        with patch.object(OWNED, "_Control", side_effect=RuntimeError("authored reader failure")):
            with self.assertRaisesRegex(RuntimeError, "authored reader failure"):
                self.capture(python("import pathlib,sys; pathlib.Path(sys.argv[1]).touch()", marker))
        self.assertFalse(marker.exists())

    def test_capture_reader_setup_failure_never_releases_tool(self):
        marker = self.directory / "must-not-exist"
        with patch.object(OWNED, "_Capture", side_effect=RuntimeError("authored reader failure")):
            with self.assertRaisesRegex(RuntimeError, "authored reader failure"):
                self.capture(python("import pathlib,sys; pathlib.Path(sys.argv[1]).touch()", marker))
        self.assertFalse(marker.exists())

    def test_normal_parent_exit_waits_for_grandchild_completion(self):
        marker = self.directory / "finished"
        ids = self.directory / "pids"
        child = "import pathlib,sys,time; time.sleep(.15); pathlib.Path(sys.argv[1]).write_text('finished')"
        self.capture(python("import os,pathlib,subprocess,sys; p=subprocess.Popen([sys.executable,'-I','-B','-c',sys.argv[1],sys.argv[2]]); pathlib.Path(sys.argv[3]).write_text(str(os.getpid())+' '+str(p.pid))",
                            child, marker, ids))
        self.assertEqual("finished", marker.read_text())
        self.assert_drained(ids)

    def test_timeout_drains_sleeping_child_and_grandchild_and_keeps_log(self):
        ids, log = self.directory / "pids", self.directory / "build.log"
        log.write_bytes(b"retained earlier log\n")
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            OWNED.run(self.tree(ids), cwd=self.directory, env=self.env, timeout=.7, log=log)
        self.assertEqual("timeout", raised.exception.reason)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assertTrue(log.read_bytes().startswith(b"retained earlier log\n"))
        self.assertIn(b"authored fixture ready", log.read_bytes())
        self.assert_drained(ids)

    def test_nonzero_exit_stops_remaining_descendants(self):
        ids = self.directory / "pids"
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            self.capture(self.tree(ids, exitcode=7), timeout=10)
        self.assertEqual("command_failed", raised.exception.reason)
        self.assertEqual(7, raised.exception.returncode)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assert_drained(ids)

    def test_capture_overflow_stops_tree_without_retaining_unbounded_output(self):
        ids = self.directory / "pids"
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            self.capture(self.tree(ids, flood=True), timeout=10, max_output_bytes=4096)
        self.assertEqual("output_limit", raised.exception.reason)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assertEqual(4096, len(raised.exception.output))
        self.assert_drained(ids)

    def test_explicit_cancellation_drains_tree(self):
        ids = self.directory / "pids"
        cancelled = threading.Event()
        def cancel_after_start():
            deadline = time.monotonic() + 5
            while not ids.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            cancelled.set()
        waiter = threading.Thread(target=cancel_after_start)
        waiter.start()
        try:
            with self.assertRaises(OWNED.OwnedProcessError) as raised:
                self.capture(self.tree(ids), timeout=10, cancel=cancelled)
            self.assertEqual("cancelled", raised.exception.reason)
            self.assertTrue(raised.exception.cleanup_complete)
            self.assert_drained(ids)
        finally:
            waiter.join(timeout=6)
            self.assertFalse(waiter.is_alive())

    def test_pre_cancelled_call_does_not_open_log_or_launch(self):
        cancelled = threading.Event()
        cancelled.set()
        log = self.directory / "absent.log"
        with patch.object(OWNED, "_spawn", side_effect=AssertionError("No child may start")):
            with self.assertRaises(OWNED.OwnedProcessError) as raised:
                OWNED.run(python("pass"), cwd=self.directory, env=self.env, timeout=1, log=log, cancel=cancelled)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assertFalse(log.exists())

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux process-local subreaper behavior")
    def test_linux_adopted_detached_grandchild_is_also_drained(self):
        ids = self.directory / "pids"
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            self.capture(self.tree(ids, exitcode=8, detached=True), timeout=10)
        self.assertEqual("command_failed", raised.exception.reason)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assert_drained(ids)

    def test_missing_executable_is_an_awaited_failure(self):
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            self.capture([str(self.directory / "does-not-exist")], timeout=10)
        self.assertEqual("command_failed", raised.exception.reason)
        self.assertTrue(raised.exception.cleanup_complete)

    def test_invalid_command_and_bounds_never_start_a_process(self):
        with patch.object(OWNED, "_spawn", side_effect=AssertionError("No child may start")):
            for command in ("echo bad", [], ["\0"]):
                with self.subTest(command=command), self.assertRaises(ValueError):
                    self.capture(command)
            for timeout in (0, -1, float("inf"), float("nan")):
                with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                    self.capture(python("pass"), timeout=timeout)
            for limit in (0, -1, 1.5, True, 100 * 1024 * 1024):
                with self.subTest(limit=limit), self.assertRaises(ValueError):
                    self.capture(python("pass"), max_output_bytes=limit)

    def test_link_hardlink_directory_and_fifo_logs_are_rejected_before_spawn(self):
        target = self.directory / "caller-owned"
        target.write_bytes(b"retained")
        hardlink = self.directory / "hardlink.log"
        os.link(target, hardlink)
        candidates = [hardlink]
        directory = self.directory / "directory.log"
        directory.mkdir()
        candidates.append(directory)
        if os.name == "posix":
            link = self.directory / "link.log"
            link.symlink_to(target)
            fifo = self.directory / "fifo.log"
            os.mkfifo(fifo)
            candidates += [link, fifo]
        with patch.object(OWNED, "_spawn", side_effect=AssertionError("Unsafe logs must fail before launch")):
            for candidate in candidates:
                with self.subTest(candidate=candidate.name), self.assertRaises(ValueError):
                    OWNED.run(python("pass"), cwd=self.directory, env=self.env, timeout=1, log=candidate)
        self.assertEqual(b"retained", target.read_bytes())

    @unittest.skipUnless(os.name == "posix", "Authored POSIX linked-parent/open-race fixture")
    def test_linked_parent_and_post_open_replacement_are_refused(self):
        actual = self.directory / "actual"
        actual.mkdir()
        linked = self.directory / "linked"
        linked.symlink_to(actual, target_is_directory=True)
        with self.assertRaises(ValueError):
            OWNED._open_log(linked / "new.log")
        self.assertFalse((actual / "new.log").exists())
        log, caller = self.directory / "log", self.directory / "caller"
        log.write_bytes(b"log")
        caller.write_bytes(b"retained")
        original_open = os.open
        opened = []
        def raced_open(path, flags, mode):
            descriptor = original_open(path, flags, mode)
            opened.append(descriptor)
            log.unlink()
            log.symlink_to(caller)
            return descriptor
        with patch.object(OWNED.os, "open", side_effect=raced_open), self.assertRaises(ValueError):
            OWNED._open_log(log)
        self.assertEqual(b"retained", caller.read_bytes())
        with self.assertRaises(OSError):
            os.fstat(opened[0])

    def test_fdopen_failure_closes_the_new_log_descriptor(self):
        descriptors = []
        original_open = os.open
        def record_open(*arguments):
            descriptor = original_open(*arguments)
            descriptors.append(descriptor)
            return descriptor
        with patch.object(OWNED.os, "open", side_effect=record_open), \
             patch.object(OWNED.os, "fdopen", side_effect=OSError("authored fdopen failure")):
            with self.assertRaises(OSError):
                OWNED._open_log(self.directory / "new.log")
        with self.assertRaises(OSError):
            os.fstat(descriptors[0])

    @unittest.skipUnless(os.name == "posix", "POSIX special-file open flags")
    def test_log_open_cannot_block_on_a_substituted_fifo(self):
        log = self.directory / "build.log"
        log.write_bytes(b"previous")
        original_open = os.open
        def replace_with_fifo(path, flags, mode):
            self.assertTrue(flags & os.O_NONBLOCK)
            log.unlink()
            os.mkfifo(log)
            return original_open(path, flags, mode)
        with patch.object(OWNED.os, "open", side_effect=replace_with_fifo):
            with self.assertRaises(OSError):
                OWNED._open_log(log)


class OwnershipPolicyTest(unittest.TestCase):
    def test_worker_crash_is_not_a_successful_command(self):
        control = OWNED._Control(io.BytesIO(b'{"event":"complete","returncode":0,"drained":true}\n'))
        self.assertTrue(control.done.wait(1))
        process = SimpleNamespace(poll=lambda: 99)
        with self.assertRaises(OWNED._Stop) as raised:
            OWNED._monitor(process, None, control, None, time.monotonic() + 1, None)
        self.assertEqual("owner_failed", raised.exception.reason)

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux waitid ownership")
    def test_candidate_pid_is_never_owned_without_kernel_child_confirmation(self):
        calls = []
        def waitid(kind, pid, flags):
            calls.append((kind, pid, flags))
            if pid != 123:
                raise ChildProcessError()
            return None  # A live, kernel-confirmed direct child; deliberately not reaped.
        with patch.object(OWNED, "_proc_child_candidates", return_value={123, 456}), \
             patch.object(OWNED.os, "waitid", side_effect=waitid):
            self.assertEqual({123}, OWNED._children())
        self.assertEqual({(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) for pid in (123, 456)}, set(calls))

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux optional proc interface")
    def test_kernel_without_task_children_uses_direct_parent_candidates(self):
        owner = os.getpid()
        entries = [SimpleNamespace(name=str(pid), path=f"/proc/{pid}") for pid in (123, 456, 789)]
        def read_text(path):
            if str(path) == "/proc/self/stat":
                return f"{owner} (owner) S 1"
            if path.name == "children":
                raise FileNotFoundError()
            if str(path) == "/proc/123/stat":
                return f"123 (authored child (with parentheses)) S {owner}"
            if str(path) == "/proc/456/stat":
                return "456 (unrelated process) S 1"
            raise PermissionError()  # Cannot silently omit a possibly owned PID.
        with patch.object(Path, "read_text", autospec=True, side_effect=read_text), \
             patch.object(OWNED.os, "scandir", return_value=nullcontext(entries)):
            self.assertEqual({123, 789}, OWNED._proc_child_candidates())

    @unittest.skipUnless(os.name == "posix", "POSIX capability refusal")
    def test_failed_ownership_capability_never_starts_a_child(self):
        messages = []
        with patch.object(OWNED, "_linux_subreaper", side_effect=OSError("authored unavailable facility")), \
             patch.object(OWNED.signal, "signal"), patch.object(OWNED, "_emit", side_effect=messages.append), \
             patch.object(OWNED.subprocess, "Popen", side_effect=AssertionError("Must fail before spawning")):
            OWNED._posix_tree(["must-not-execute"])
        self.assertEqual([{"event": "complete", "returncode": 125, "drained": True}], messages)


class WindowsJobPolicyTest(unittest.TestCase):
    class Api:
        def __init__(self):
            self.events = []
            self.count = 0
        def create(self): self.events.append("create"); return 91
        def configure(self, handle, flags): self.events.append(("configure", handle, flags))
        def assign(self, handle, process): self.events.append(("assign", handle, process))
        def active(self, handle): self.events.append(("active", handle)); return self.count
        def terminate(self, handle): self.events.append(("terminate", handle))
        def close(self, handle): self.events.append(("close", handle))

    class Process:
        _handle = 72
        def __init__(self, events):
            self.events = events
            class Input(io.BytesIO):
                def write(inner, value):
                    events.append(("gate", value))
                    return super().write(value)
            self.stdin, self.stdout, self.stderr = Input(), io.BytesIO(), None
        def kill(self): self.events.append("kill-gate")
        def wait(self, timeout): self.events.append("await-gate"); return 1

    def test_failure_snapshot_distinguishes_completed_command_from_live_job(self):
        api = self.Api()
        api.count = 2
        job = OWNED._WindowsJob(api)
        expected = {"listed": 2, "truncated": False, "members": [{"pid": 12, "image": "mspdbsrv.exe"}]}
        with patch.object(api, "processes", return_value=expected, create=True):
            result = job.failure_state(SimpleNamespace(completed={"returncode": 0}))
        self.assertTrue(result["commandCompleted"])
        self.assertEqual(0, result["commandReturncode"])
        self.assertEqual(2, result["jobActiveBeforeCleanup"])
        self.assertEqual(expected, result["processes"])
        with patch.object(api, "processes", side_effect=OSError("authored private error"), create=True):
            result = job.failure_state(None)
        self.assertFalse(result["commandCompleted"])
        self.assertIsNone(result["commandReturncode"])
        self.assertEqual("OSError", result["snapshotError"])
        self.assertNotIn("private", json.dumps(result))
        job.close()

    def test_process_snapshot_rechecks_job_membership_and_redacts_unknown_images(self):
        events = []
        def query(handle, kind, pointer, size, returned):
            self.assertEqual(3, kind)
            members = ctypes.cast(pointer, ctypes.POINTER(OWNED._JobProcessIds)).contents
            members.assigned = members.listed = 3
            members.ids[:3] = (11, 22, 33)
            return 1
        def member(process, job, pointer):
            ctypes.cast(pointer, ctypes.POINTER(ctypes.c_int)).contents.value = process != 22
            return 1
        def image(process, flags, buffer, capacity):
            events.append(("image", process))
            buffer.value = "C:/authored-private-dir/" + ("MSBuild.exe" if process == 11 else "authored-private.exe")
            return 1
        def close(handle): events.append(("close", handle)); return 1
        api = object.__new__(OWNED._WindowsApi)
        api.dll = SimpleNamespace(QueryInformationJobObject=query, OpenProcess=lambda access, inherit, pid: pid,
            IsProcessInJob=member, QueryFullProcessImageNameW=image, CloseHandle=close)
        result = api.processes(91)
        self.assertEqual([{"pid": 11, "image": "msbuild.exe"}, {"pid": 33, "image": "<other>"}], result["members"])
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn(("image", 22), events)
        self.assertEqual([11, 22, 33], [item[1] for item in events if item[0] == "close"])

    def test_job_has_kill_on_close_without_either_breakaway_flag(self):
        api = self.Api()
        job = OWNED._WindowsJob(api)
        self.assertIn(("configure", 91, 0x2000), api.events)
        self.assertEqual(0, api.events[1][2] & (0x800 | 0x1000))
        job.close()
        job.close()
        self.assertEqual(1, api.events.count(("close", 91)))

    def test_gated_child_is_assigned_before_start_message(self):
        api = self.Api()
        job = OWNED._WindowsJob(api)
        process = self.Process(api.events)
        with patch.object(OWNED, "_spawn", return_value=process):
            self.assertIs(process, OWNED._start_owner(["authored-command"], ROOT, {}, None, job))
        self.assertFalse(any(isinstance(event, tuple) and event[0] == "gate" for event in api.events))
        # Production installs both bounded readers before explicitly opening START.
        OWNED._send(process.stdin, b"start\n")
        self.assertLess(api.events.index(("assign", 91, 72)), api.events.index(("gate", b"start\n")))
        job.close()

    def test_failed_job_termination_does_not_skip_gated_child_kill_and_wait(self):
        api = self.Api()
        job = OWNED._WindowsJob(api)
        process = self.Process(api.events)
        with patch.object(api, "assign", side_effect=OSError("authored assignment refusal")), \
             patch.object(api, "terminate", side_effect=OSError("authored terminate refusal")), \
             patch.object(OWNED, "_spawn", return_value=process), self.assertRaises(OWNED.OwnedProcessError) as raised:
            OWNED._start_owner(["must-not-execute"], ROOT, {}, None, job)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assertIn("kill-gate", api.events)
        self.assertIn("await-gate", api.events)
        self.assertFalse(any(isinstance(event, tuple) and event[0] == "gate" for event in api.events))
        job.close()

    def test_failed_job_handle_close_keeps_the_handle_for_recovery(self):
        api = self.Api()
        job = OWNED._WindowsJob(api)
        with patch.object(api, "close", side_effect=OSError("authored close refusal")):
            with self.assertRaises(OSError):
                job.close()
        self.assertEqual(91, job.handle)
        job.close()

    def test_assignment_failure_never_opens_gate_and_awaits_only_owned_child(self):
        api = self.Api()
        job = OWNED._WindowsJob(api)
        process = self.Process(api.events)
        with patch.object(api, "assign", side_effect=OSError("authored assignment refusal")), \
             patch.object(OWNED, "_spawn", return_value=process), self.assertRaises(OWNED.OwnedProcessError) as raised:
            OWNED._start_owner(["must-not-execute"], ROOT, {}, None, job)
        self.assertEqual("start_failed", raised.exception.reason)
        self.assertTrue(raised.exception.cleanup_complete)
        self.assertFalse(any(isinstance(event, tuple) and event[0] == "gate" for event in api.events))
        self.assertIn("kill-gate", api.events)
        self.assertIn("await-gate", api.events)
        job.close()

    def test_job_configuration_failure_closes_its_handle(self):
        api = self.Api()
        with patch.object(api, "configure", side_effect=OSError("authored refusal")), self.assertRaises(OSError):
            OWNED._WindowsJob(api)
        self.assertIn(("close", 91), api.events)

    def test_active_job_after_deadline_is_explicitly_not_drained(self):
        api = self.Api()
        api.count = 1
        job = OWNED._WindowsJob(api)
        process = self.Process(api.events)
        self.assertFalse(OWNED._drain_windows(process, job, time.monotonic() + .02))
        self.assertIn(("terminate", 91), api.events)
        self.assertNotIn("await-gate", api.events)
        job.close()


@unittest.skipUnless(os.name == "nt", "Actual Windows Job Object execution is required on Windows CI")
class WindowsActualJobTest(unittest.TestCase):
    setUp = OwnedProcessTest.setUp
    tearDown = OwnedProcessTest.tearDown
    capture = OwnedProcessTest.capture

    def test_timeout_snapshot_reports_exited_root_and_remaining_owned_child(self):
        ids = self.directory / "pids"
        with self.assertRaises(OWNED.OwnedProcessError) as raised:
            self.capture(self.tree(ids, exitcode=0), timeout=2.5)
        failure = raised.exception
        self.assertEqual("timeout", failure.reason)
        self.assertTrue(failure.cleanup_complete)
        state = failure.process_state
        self.assertTrue(state["commandCompleted"])
        self.assertEqual(0, state["commandReturncode"])
        self.assertGreater(state["jobActiveBeforeCleanup"], 0)
        self.assertTrue(state["processes"]["members"])
        self.assertTrue(any(member["image"] in ("python.exe", "python3.exe")
                            for member in state["processes"]["members"]))
        self.assert_drained(ids)

    def test_actual_job_refuses_explicit_breakaway(self):
        code = """import subprocess,sys
try:
    subprocess.Popen([sys.executable,'-I','-B','-c','pass'],creationflags=0x01000000)
except OSError:
    print('breakaway-refused')
else:
    raise RuntimeError('Child escaped the owned non-breakaway job')
"""
        self.assertEqual(b"breakaway-refused\n", self.capture(python(code)).replace(b"\r\n", b"\n"))


if __name__ == "__main__":
    unittest.main()
