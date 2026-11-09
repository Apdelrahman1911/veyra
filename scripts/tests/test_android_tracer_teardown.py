#!/usr/bin/env python3
"""Explicitly opted-in strace/Python fork regression; NO adb/emulator/namespace.

Use VEYRA_RUN_AUTHORED_TRACE_TESTS=1 only with the lead's execution approval.
Private traces and identity-only receipts remain under .work/verification/.
"""
from __future__ import annotations

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('android_preflight', ROOT / 'scripts/android-preflight.py')
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)

AUTHORED_FORK = r'''
import json,os,pathlib,sys,time
def identity():
    fields=pathlib.Path('/proc/self/stat').read_bytes().rsplit(b')',1)[1].split()
    return {'pid':os.getpid(),'startTicks':int(fields[19]),'uid':os.getuid(),
            'parentPid':os.getppid(),'state':'S','networkNamespace':os.readlink('/proc/self/ns/net')}
records=[identity()]
child=os.fork()
if child==0:
    records.append(identity())
    grandchild=os.fork()
    if grandchild==0:
        records.append(identity())
        pathlib.Path(sys.argv[1]).write_text(json.dumps(records))
        time.sleep(30)
        os._exit(0)
    os._exit(0)
os.waitpid(child,0)
time.sleep(30)
'''


@unittest.skipUnless(sys.platform == 'linux' and os.environ.get('VEYRA_RUN_AUTHORED_TRACE_TESTS') == '1',
                     'Requires explicit approval for authored strace/Python regression')
class TracerTeardownTests(unittest.TestCase):
    def setUp(self):
        self.assertEqual(os.environ.get('VEYRA_SCOPED_ENV'), '1', 'Use scripts/dev.sh')
        self.strace = shutil.which('strace')
        self.assertIsNotNone(self.strace, 'Existing strace required; no installation/fallback')
        parent = ROOT / '.work/verification'
        parent.mkdir(parents=True, exist_ok=True)
        self.evidence = Path(tempfile.mkdtemp(prefix='android-authored-tracer-', dir=parent))
        self.source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                              for path in (ROOT / 'scripts/android-preflight.py', Path(__file__).resolve())}
        old = os.umask(0o077)
        try:
            self.run_path = preflight.prepare_run(self.evidence)
        finally:
            os.umask(old)

    def exercise_tracer_exit(self, exit_signal):
        environment = preflight.native_environment(dict(os.environ), self.run_path)
        unrelated = subprocess.Popen([sys.executable, '-B', '-c', 'import time; time.sleep(30)'],
                                     cwd=self.run_path, env=environment, preexec_fn=preflight.limit_probe)
        unrelated_tree = preflight.OwnedTree(unrelated)
        marker = self.run_path / 'authored-identities.json'
        argv = preflight.traced_command({'strace': self.strace}, self.run_path, 'double-fork',
                                        [sys.executable, '-B', '-c', AUTHORED_FORK, str(marker)])
        records = []
        process = None
        tree = None
        succeeded = False
        try:
            with (self.run_path / 'logs/trace-launch.log').open('xb') as log:
                process = subprocess.Popen(argv, cwd=self.run_path, env=environment,
                                           stdout=log, stderr=subprocess.STDOUT,
                                           start_new_session=True, preexec_fn=preflight.limit_probe)
                # Intentionally model the scan race: register ONLY our tracer.
                # Neither child identity nor descendant scan may do the cleanup.
                with patch.object(preflight, 'process_table', return_value={}):
                    tree = preflight.OwnedTree(process)
                deadline = time.monotonic() + 5
                while not marker.is_file() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(marker.is_file(), 'Authored grandchild failed to publish its identity')
                records = json.loads(preflight.bounded(marker))
                self.assertEqual(len(records), 3)
                grandchild = records[-1]
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    current = preflight.proc_identity(grandchild['pid'])
                    if current and current['parentPid'] != records[1]['pid']:
                        break
                    time.sleep(0.01)
                self.assertTrue(preflight.same_process(current, grandchild))
                self.assertNotEqual(current['parentPid'], records[1]['pid'], 'Fast child has not reparented')
                self.assertEqual(set(tree.identities), {process.pid})
                self.assertNotIn(grandchild['pid'], tree.identities)
                preflight.signal_owned(tree.identities[process.pid], exit_signal)
                process.wait(timeout=5)
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    survivors = [old for old in records
                                 if preflight.same_process(value := preflight.proc_stat(old['pid']), old)
                                 and value['state'] != 'Z']
                    if not survivors:
                        break
                    time.sleep(0.01)
                self.assertEqual(survivors, [], 'Tracer death left an authored tracee alive')
                self.assertIsNone(unrelated.poll(), 'An unrelated child was disturbed')
                succeeded = True
        finally:
            # Even a failing assertion signals only explicitly authored exact
            # identities. No process-group/name/global cleanup exists here.
            for value in reversed(records):
                preflight.signal_owned(value, signal.SIGKILL)
            if tree is not None:
                tree.stop()
            unrelated_tree.stop()
            preflight.json_write(self.evidence / 'result.json', {
                'schemaVersion': 1, 'authoredPythonOnly': True,
                'tracerExitSignal': int(exit_signal), 'passedBeforeFallbackCleanup': succeeded,
                'sourceSha256': self.source_hashes,
                'traceeIdentities': records, 'tracerPid': process.pid if process else None,
                'androidToolLaunched': False, 'guestStarted': False,
                'nativeInstrumentedTestsExecuted': 0, 'physicalAudioVerified': False,
            })
            print('Authored tracer evidence:', self.evidence.relative_to(ROOT))

    def test_tracer_term_drains_unobserved_double_fork_and_spares_unrelated_child(self):
        self.exercise_tracer_exit(signal.SIGTERM)

    def test_tracer_abrupt_kill_drains_unobserved_double_fork_and_spares_unrelated_child(self):
        self.exercise_tracer_exit(signal.SIGKILL)


if __name__ == '__main__':
    unittest.main(verbosity=2)
