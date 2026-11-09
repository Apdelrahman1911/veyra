#!/usr/bin/env python3
"""Authored Stage 0 regressions; NEVER launch adb, emulator, unshare or mounts."""
from __future__ import annotations

import ast
from contextlib import ExitStack, contextmanager
from copy import deepcopy
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TMP = ROOT / '.work/tmp'
TMP.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('android_preflight', ROOT / 'scripts/android-preflight.py')
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


def network_fixture(up=False, sit=True, ipv6=True):
    # Default DOWN/UP shapes match all eight bounded network reports from the
    # lead's preflight-m19r6p1x; that attempt reached no Android command.
    # Absent-sit/disabled-IPv6/explicit-table variants remain authored fixtures.
    lo = {'ifindex': 1, 'ifname': 'lo', 'flags': ['LOOPBACK'], 'mtu': 65536,
          'qdisc': 'noop', 'operstate': 'DOWN', 'link_type': 'loopback',
          'address': '00:00:00:00:00:00', 'broadcast': '00:00:00:00:00:00',
          'group': 'default', 'txqlen': 1000}
    tunnel = {'ifindex': 2, 'ifname': 'sit0', 'link': None, 'flags': ['NOARP'], 'mtu': 1480,
              'qdisc': 'noop', 'operstate': 'DOWN', 'link_type': 'sit',
              'address': '0.0.0.0', 'broadcast': '0.0.0.0', 'group': 'default', 'txqlen': 1000}
    if up:
        lo.update(flags=['LOOPBACK', 'UP', 'LOWER_UP'], qdisc='noqueue', operstate='UNKNOWN')
    addresses = [dict(lo, addr_info=[])] + ([dict(tunnel, addr_info=[])] if sit else [])
    links = [dict(lo)] + ([dict(tunnel)] if sit else [])
    for item in links:
        is_sit = item['ifname'] == 'sit0'
        item.update(linkmode='DEFAULT', promiscuity=0, allmulti=0,
                    min_mtu=1280 if is_sit else 0, max_mtu=65555 if is_sit else 0,
                    inet6_addr_gen_mode='eui64', num_tx_queues=1, num_rx_queues=1,
                    gso_max_size=65536, gso_max_segs=65535,
                    tso_max_size=65536 if is_sit else 524280, tso_max_segs=65535, gro_max_size=65536)
        if is_sit:
            item['linkinfo'] = {'info_kind': 'sit', 'info_data': {
                'proto': 'ip6ip', 'remote': 'any', 'local': 'any', 'ttl': 64, 'pmtudisc': False}}
    routes4, routes6 = [], []
    if up:
        addresses[0]['addr_info'] = [
            {'family': 'inet', 'local': '127.0.0.1', 'prefixlen': 8, 'scope': 'host', 'label': 'lo',
             'valid_life_time': 4294967295, 'preferred_life_time': 4294967295}]
        for destination, kind in (('127.0.0.0/8', 'local'), ('127.0.0.1', 'local'),
                                   ('127.255.255.255', 'broadcast')):
            routes4.append({'type': kind, 'dst': destination, 'dev': 'lo', 'table': 'local',
                            'protocol': 'kernel', 'scope': 'host' if kind == 'local' else 'link',
                            'prefsrc': '127.0.0.1', 'flags': []})
        if ipv6:
            addresses[0]['addr_info'].append(
                {'family': 'inet6', 'local': '::1', 'prefixlen': 128, 'scope': 'host',
                 'valid_life_time': 4294967295, 'preferred_life_time': 4294967295})
            routes6.append({'type': 'local', 'dst': '::1', 'dev': 'lo',
                            'protocol': 'kernel', 'metric': 0, 'flags': [], 'pref': 'medium'})
    return {'links': links, 'addresses': addresses, 'ipv4Routes': routes4, 'ipv6Routes': routes6}


class ReachedMockGuard(Exception):
    pass


class ReachedMockProbe(Exception):
    pass


@unittest.skipUnless(sys.platform == 'linux', 'Linux namespace-only Stage 0 runner')
class AndroidPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='android-preflight-unit-', dir=TMP)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        old = os.umask(0o077)
        try:
            self.run_path = preflight.prepare_run(self.root)
        finally:
            os.umask(old)

    def parent_reference(self, run):
        return {'schemaVersion': 1, 'runName': run.name, 'fd': 42,
                'parent': {'pid': 12345, 'startTicks': 100, 'uid': 6},
                'mount': {'namespace': 'mnt:[1]', 'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS}}

    @contextmanager
    def mocked_mount_fd(self, flags=None, cloexec=True, nstype=None, stats=None,
                        open_error=None, inspect_error=None, close_error=None):
        events = []
        flags = os.O_RDONLY if flags is None else flags
        nstype = preflight.CLONE_NEWNS if nstype is None else nstype
        def opened(path, mode):
            events.append(('open', path, mode))
            if open_error:
                raise open_error
            return 42
        def fcntl_call(fd, operation):
            self.assertEqual(fd, 42)
            events.append(('fcntl', fd, operation))
            if inspect_error:
                raise inspect_error
            if operation == preflight.fcntl.F_GETFL:
                return flags
            self.assertEqual(operation, preflight.fcntl.F_GETFD)
            return preflight.fcntl.FD_CLOEXEC if cloexec else 0
        def ioctl(fd, operation):
            self.assertEqual((fd, operation), (42, preflight.NS_GET_NSTYPE))
            events.append(('ioctl', fd))
            return nstype
        def close(fd):
            self.assertEqual(fd, 42)
            events.append(('close', fd))
            if close_error:
                raise close_error
        stat_values = stats or [SimpleNamespace(st_dev=1, st_ino=1)] * 2
        with patch.object(preflight.os, 'open', side_effect=opened), \
                patch.object(preflight.os, 'close', side_effect=close), \
                patch.object(preflight.os, 'fstat', side_effect=stat_values), \
                patch.object(preflight.fcntl, 'fcntl', side_effect=fcntl_call), \
                patch.object(preflight.fcntl, 'ioctl', side_effect=ioctl):
            yield events

    def test_parent_reference_shape_is_exact_bounded_and_not_ambient_authority(self):
        reference = self.parent_reference(self.run_path)
        origin = {'mountNamespace': 'mnt:[1]'}
        with patch.object(preflight.resource, 'getrlimit', return_value=(128, 128)):
            self.assertIs(preflight.validate_parent_reference(self.run_path, origin, reference), reference)
            for limit in (43, 42):
                with self.subTest(limit=limit), \
                        patch.object(preflight.resource, 'getrlimit', return_value=(limit, limit)):
                    if limit == 43:
                        preflight.validate_parent_reference(self.run_path, origin, reference)
                    else:
                        with self.assertRaises(preflight.PreflightError):
                            preflight.validate_parent_reference(self.run_path, origin, reference)
            values = [None, [], {}, dict(reference, extra='not-authority')]
            for field in reference:
                values.append({key: value for key, value in reference.items() if key != field})
            for field, replacements in {
                'schemaVersion': (True, 1.0, '1', 2), 'runName': ('preflight-other123', None),
                'fd': (True, 42.0, '42', -1, 0, 1, 2, 128, 2 ** 64),
            }.items():
                values.extend(dict(reference, **{field: value}) for value in replacements)
            for group, fields in (('parent', ('pid', 'startTicks', 'uid')),
                                  ('mount', ('dev', 'ino', 'nstype', 'namespace'))):
                for field in fields:
                    changed = deepcopy(reference)
                    del changed[group][field]
                    values.append(changed)
                    for bad in (None, True, 1.0, '1', -1, 2 ** 65):
                        changed = deepcopy(reference)
                        changed[group][field] = bad
                        values.append(changed)
                changed = deepcopy(reference)
                changed[group]['extra'] = 'not-authority'
                values.append(changed)
            for value in values:
                with self.subTest(value=value), self.assertRaises(preflight.PreflightError):
                    preflight.validate_parent_reference(self.run_path, origin, value)
            with self.assertRaises(preflight.PreflightError):
                preflight.validate_parent_reference(self.run_path, {'mountNamespace': 'mnt:[2]'}, reference)

    def test_mount_descriptor_requires_read_only_cloexec_mount_nsfs_and_stable_device_inode(self):
        with self.mocked_mount_fd() as events:
            self.assertEqual(preflight.mount_descriptor_identity(42),
                             {'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS})
        self.assertEqual(events, [('fcntl', 42, preflight.fcntl.F_GETFL),
                                 ('fcntl', 42, preflight.fcntl.F_GETFD), ('ioctl', 42)])
        cases = ({'flags': os.O_WRONLY}, {'flags': os.O_RDWR}, {'flags': os.O_PATH},
                 {'cloexec': False}, {'nstype': 0x40000000},
                 {'stats': [SimpleNamespace(st_dev=1, st_ino=1), SimpleNamespace(st_dev=2, st_ino=1)]},
                 {'stats': [SimpleNamespace(st_dev=1, st_ino=1), SimpleNamespace(st_dev=1, st_ino=2)]})
        for case in cases:
            with self.subTest(case=case), self.mocked_mount_fd(**case):
                with self.assertRaises(preflight.PreflightError): preflight.mount_descriptor_identity(42)
        for number in (errno.EBADF, errno.ENOTTY, errno.EACCES):
            with self.subTest(errno=number), self.mocked_mount_fd(inspect_error=OSError(number, 'authored')):
                with self.assertRaises(OSError): preflight.mount_descriptor_identity(42)

    def test_origin_mount_reference_captures_own_read_only_fd_and_closes_after_body(self):
        origin = {'mountNamespace': 'mnt:[1]'}
        parent = dict(self.parent_reference(self.run_path)['parent'], state='S')
        with self.mocked_mount_fd() as events, \
                patch.object(preflight, 'proc_stat', return_value=parent), \
                patch.object(preflight, 'namespace', return_value='mnt:[1]') as namespace, \
                patch.object(preflight.os, 'getpid', return_value=12345), \
                patch.object(preflight.os, 'getuid', return_value=6), \
                patch.object(preflight.resource, 'getrlimit', return_value=(128, 128)):
            with preflight.origin_mount_reference(self.run_path, origin) as reference:
                self.assertEqual(reference, self.parent_reference(self.run_path))
                self.assertFalse(any(event[0] == 'close' for event in events))
                events.append(('body',))
            self.assertEqual(events[-2:], [('body',), ('close', 42)])
            self.assertEqual(events[0], ('open', '/proc/self/ns/mnt', os.O_RDONLY | os.O_CLOEXEC))
            self.assertEqual(namespace.call_args_list, [unittest.mock.call('mnt'), unittest.mock.call('mnt')])

    def test_origin_capture_failures_never_leak_an_acquired_fd_or_guess_a_parent_namespace(self):
        parent = dict(self.parent_reference(self.run_path)['parent'], state='S')
        cases = (
            {'identities': [None], 'namespaces': [], 'acquired': False},
            {'identities': [dict(parent, uid=7)], 'namespaces': [], 'acquired': False},
            {'identities': [parent], 'namespaces': ['mnt:[1]'], 'open_error': OSError(errno.EACCES, 'authored'), 'acquired': False},
            {'identities': [parent], 'namespaces': ['mnt:[1]'], 'inspect_error': OSError(errno.EBADF, 'authored'), 'acquired': True},
            {'identities': [parent], 'namespaces': ['mnt:[1]', 'mnt:[2]'], 'acquired': True},
            {'identities': [parent, dict(parent, startTicks=101)], 'namespaces': ['mnt:[1]'] * 2, 'acquired': True},
            {'identities': [parent, None], 'namespaces': ['mnt:[1]'] * 2, 'acquired': True},
            {'identities': [parent, parent], 'namespaces': ['mnt:[2]'] * 2, 'acquired': True},
        )
        for case in cases:
            run = preflight.prepare_run(self.root)
            with self.subTest(case=case), self.mocked_mount_fd(
                    open_error=case.get('open_error'), inspect_error=case.get('inspect_error')) as events, \
                    patch.object(preflight, 'proc_stat', side_effect=case['identities']), \
                    patch.object(preflight, 'namespace', side_effect=case['namespaces']), \
                    patch.object(preflight.os, 'getpid', return_value=12345), \
                    patch.object(preflight.os, 'getuid', return_value=6), \
                    patch.object(preflight.resource, 'getrlimit', return_value=(128, 128)), \
                    patch.object(preflight.subprocess, 'Popen', side_effect=AssertionError('No launch')):
                with self.assertRaises((preflight.PreflightError, OSError)):
                    with preflight.origin_mount_reference(run, {'mountNamespace': 'mnt:[1]'}):
                        self.fail('Invalid capture reached body')
                self.assertEqual(events.count(('close', 42)), int(case['acquired']))
                self.assertEqual(json.loads((run / 'failure-phase.json').read_text())['phase'], 'origin-parent-mount')

    def test_reference_release_is_once_and_close_failure_never_converts_refusal_to_success(self):
        for original in (None, preflight.PreflightError('authored primary')):
            run = preflight.prepare_run(self.root)
            close_error = OSError(errno.EIO, 'private-close-message', '/authored/private')
            with self.subTest(original=original), patch.object(preflight.os, 'close', side_effect=close_error) as close:
                with self.assertRaises((preflight.PreflightError, OSError)) as caught:
                    with preflight.release_mount_reference(run, 42, 'guard-parent-fd-close'):
                        if original:
                            raise original
                self.assertIs(caught.exception, original or close_error)
                close.assert_called_once_with(42)
                receipt = json.loads((run / 'failure-phase.json').read_text())
                self.assertEqual(receipt, {'schemaVersion': 1, 'phase': 'guard-parent-fd-close',
                                          'errorClass': 'OSError', 'errno': errno.EIO})
                self.assertNotIn('private', json.dumps(receipt))

    def test_self_mount_reference_is_fresh_bound_and_closed_before_return(self):
        with self.mocked_mount_fd() as events, \
                patch.object(preflight, 'namespace', return_value='mnt:[1]'):
            self.assertEqual(preflight.self_mount_identity(self.run_path),
                             {'namespace': 'mnt:[1]', 'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS})
            self.assertEqual(events[-1], ('close', 42))
        for namespaces in (['mnt:[1]', 'mnt:[2]'], ['mnt:[2]', 'mnt:[2]']):
            with self.subTest(namespaces=namespaces), self.mocked_mount_fd() as events, \
                    patch.object(preflight, 'namespace', side_effect=namespaces):
                with self.assertRaises(preflight.PreflightError): preflight.self_mount_identity(self.run_path)
                self.assertEqual(events.count(('close', 42)), 1)
        with self.mocked_mount_fd(close_error=OSError(errno.EIO, 'authored')), \
                patch.object(preflight, 'namespace', return_value='mnt:[1]'):
            with self.assertRaises(OSError): preflight.self_mount_identity(self.run_path)

    def test_live_parent_binding_rejects_reparent_reuse_owner_exit_and_unreadable_identity(self):
        reference = self.parent_reference(self.run_path)
        good = dict(reference['parent'], state='S')
        with patch.object(preflight.os, 'getppid', return_value=12345), \
                patch.object(preflight, 'proc_stat', return_value=good), \
                patch.object(preflight, 'namespace', side_effect=AssertionError('No parent namespace read')), \
                patch.object(preflight, 'proc_identity', side_effect=AssertionError('No cross-namespace helper')):
            preflight.assert_bound_parent(self.run_path, reference)
        cases = (([54321], good), ([12345, 54321], good), ([12345], None),
                 ([12345], dict(good, startTicks=101)), ([12345], dict(good, uid=7)),
                 ([12345], dict(good, state='Z')), ([12345], dict(good, state='X')),
                 ([12345], dict(good, state='unknown')), ([12345], dict(good, state=True)),
                 ([12345], dict(good, pid=54321)))
        for parents, identity in cases:
            run = preflight.prepare_run(self.root)
            with self.subTest(parents=parents, identity=identity), \
                    patch.object(preflight.os, 'getppid', side_effect=parents), \
                    patch.object(preflight, 'proc_stat', return_value=identity), \
                    patch.object(preflight, 'namespace', side_effect=AssertionError('No namespace read')):
                with self.assertRaises(preflight.PreflightError): preflight.assert_bound_parent(run, reference)
                self.assertEqual(json.loads((run / 'failure-phase.json').read_text()), {
                    'schemaVersion': 1, 'phase': 'guard-parent-identity', 'errorClass': 'PreflightError', 'errno': None,
                })

    @contextmanager
    def mocked_outer_preflight(self, failures=None, cancel_at=(), watch_code=0,
                               result_present=True, caller_changed=False, remaining=False):
        """Run the real outer control flow, but no host/FD/process operation.

        The real origin-reference context and constructor/registration split
        execute. Discovery, watches, stop and all OS observations are finite
        mocks; returned mock stop is NOT evidence of an actual process drain.
        """
        run = preflight.prepare_run(self.root)
        if result_present:
            preflight.json_write(run / 'guarded-result.json', {
                'phase': 'no-guest-stage-0', 'guestStarted': False,
            })
        preflight.json_write(run / 'network.json', {'networkNamespace': 'net:[3]'})
        reference = self.parent_reference(run)
        parent = reference['parent']
        child = self.fixture_process(700, parent=parent['pid'], start=200, network='net:[3]')
        before = {'networkNamespace': 'net:[1]', 'mountNamespace': 'mnt:[1]',
                  'mountinfoSha256': 'a' * 64, 'hostAdbProcesses': []}
        after = dict(before, mountinfoSha256='b' * 64) if caller_changed else before
        inputs = {'tools': {'unshare': '/authored/unshare'}}
        state = SimpleNamespace(run=run, events=[], held=False, tree=None, launches=[],
                                cancellation=preflight.OuterCancellation(), reference=reference)
        failures = failures or {}

        def observed(name):
            state.events.append(name)
            if name in cancel_at:
                # Directly call the installed-latch contract, never real signals.
                state.cancellation.request(preflight.signal.SIGTERM, None)
                state.cancellation.request(preflight.signal.SIGINT, None)
            if name in failures:
                raise failures[name]

        owner_type = preflight.OwnedTree
        test = self
        class OuterTree(owner_type):
            def __init__(self, process=None):
                test.assertIsNone(process)
                super().__init__()
                state.tree = self
                observed('owner-published')
            def register(self, require_identity=False):
                test.assertTrue(require_identity)
                test.assertTrue(state.held)
                observed('register')
                super().register(require_identity)
            def refresh(self):
                observed('refresh')
            def stop(self):
                test.assertIsNotNone(self.process)
                test.assertTrue(state.held)
                observed('stop-start')
                observed('stop-return')

        def open_fd(path, flags):
            self.assertEqual((path, flags), ('/proc/self/ns/mnt', os.O_RDONLY | os.O_CLOEXEC))
            self.assertFalse(state.held)
            observed('fd-open')
            state.held = True
            return 42
        def close_fd(fd):
            self.assertEqual(fd, 42)
            self.assertTrue(state.held)
            state.held = False  # The local ownership is consumed, never retried.
            observed('fd-close')
        def mount_identity(fd):
            self.assertEqual(fd, 42)
            self.assertTrue(state.held)
            observed('fd-inspect')
            return {key: reference['mount'][key] for key in ('dev', 'ino', 'nstype')}
        def own_identity(pid):
            self.assertEqual(pid, parent['pid'])
            observed('parent-identity')
            return dict(parent)
        def namespace(kind, pid='self'):
            self.assertEqual((kind, pid), ('mnt', 'self'))
            return 'mnt:[1]'
        snapshot_count = 0
        def snapshot():
            nonlocal snapshot_count
            snapshot_count += 1
            observed('snapshot-before' if snapshot_count == 1 else 'snapshot-after')
            if snapshot_count == 2:
                self.assertTrue(state.held)
            return before if snapshot_count == 1 else after
        def launch(arguments, **options):
            self.assertIsNotNone(state.tree)
            self.assertIsNone(state.tree.process)
            self.assertEqual(state.tree.identities, {})
            self.assertTrue(state.held)
            state.launches.append((arguments, options))
            observed('spawn')
            return SimpleNamespace(pid=700)
        def watch(tree, path, seconds, ready):
            self.assertIs(tree, state.tree)
            self.assertEqual((path, seconds), (run, 120))
            self.assertEqual(ready, state.cancellation.check)
            self.assertTrue(state.held)
            observed('watch')
            ready(tree)
            return watch_code
        real_write, real_bounded = preflight.json_write, preflight.bounded
        def write(path, value):
            name = 'summary' if path.name == 'summary.json' else path.name
            observed('write-' + name)
            if name == 'summary':
                self.assertFalse(state.held)
            real_write(path, value)
            observed('wrote-' + name)
        def bounded(path, *args, **kwargs):
            observed('read-' + path.name)
            return real_bounded(path, *args, **kwargs)
        real_read_text, real_exists, real_glob, real_iterdir = Path.read_text, Path.exists, Path.glob, Path.iterdir
        def read_text(path, *args, **kwargs):
            return 'core\n' if path == Path('/proc/sys/kernel/core_pattern') else real_read_text(path, *args, **kwargs)
        def exists(path):
            return False if path == Path('/dev/bus/usb') else real_exists(path)
        def glob(path, *args, **kwargs):
            return iter(()) if path == Path('/dev/snd') else real_glob(path, *args, **kwargs)
        def iterdir(path):
            if path == Path('/proc'):
                observed('private-scan')
                return iter((Path('/proc/700'),) if remaining else ())
            return real_iterdir(path)

        with ExitStack() as stack:
            for context in (
                    patch.dict(preflight.os.environ, {'VEYRA_SCOPED_ENV': '1'}, clear=True),
                    patch.object(Path, 'read_text', read_text), patch.object(Path, 'exists', exists),
                    patch.object(Path, 'glob', glob), patch.object(Path, 'iterdir', iterdir),
                    patch.object(preflight.shutil, 'disk_usage', return_value=SimpleNamespace(free=3 * 1024 ** 3)),
                    patch.object(preflight, 'tool_inputs', return_value=inputs),
                    patch.object(preflight.resource, 'getrlimit', return_value=(128, 128)),
                    patch.object(preflight, 'snapshot', side_effect=snapshot),
                    patch.object(preflight, 'prepare_run', return_value=run),
                    patch.object(preflight, 'OwnedTree', OuterTree),
                    patch.object(preflight.os, 'getuid', return_value=6),
                    patch.object(preflight.os, 'getpid', return_value=12345),
                    patch.object(preflight.os, 'open', side_effect=open_fd),
                    patch.object(preflight.os, 'close', side_effect=close_fd),
                    patch.object(preflight, 'namespace', side_effect=namespace),
                    patch.object(preflight, 'required_process_identity', side_effect=own_identity),
                    patch.object(preflight, 'mount_descriptor_identity', side_effect=mount_identity),
                    patch.object(preflight, 'proc_identity', return_value=child),
                    patch.object(preflight, 'json_write', side_effect=write),
                    patch.object(preflight, 'bounded', side_effect=bounded),
                    patch.object(preflight.subprocess, 'Popen', side_effect=launch),
                    patch.object(preflight, 'watch', side_effect=watch),
                    patch.object(preflight, 'probe', side_effect=AssertionError('No native probe')),
                    patch.object(preflight.subprocess, 'run', side_effect=AssertionError('No command')),
                    patch.object(preflight.os, 'execv', side_effect=AssertionError('No exec'))):
                stack.enter_context(context)
            state.limits = stack.enter_context(patch.object(preflight.resource, 'setrlimit'))
            yield state

    def test_outer_handoff_owns_one_fd_until_returned_stop_and_all_final_checks(self):
        with self.mocked_outer_preflight() as mocked:
            self.assertEqual(preflight.preflight(mocked.cancellation), mocked.run)
            self.assertFalse(mocked.held)
            argv, options = mocked.launches[0]
            self.assertEqual(len(mocked.launches), 1)
            self.assertEqual(argv, ['/authored/unshare', '--user', '--map-current-user', '--net',
                sys.executable, '-B', str(ROOT / 'scripts/android-preflight.py'), '--inside-network', str(mocked.run)])
            self.assertEqual(set(options), {'cwd', 'stdout', 'stderr', 'start_new_session', 'preexec_fn', 'pass_fds'})
            self.assertEqual(options['pass_fds'], (42,))
            self.assertEqual(options['cwd'], mocked.run)
            self.assertEqual(options['stderr'], subprocess.STDOUT)
            self.assertTrue(options['start_new_session'])
            self.assertIs(options['preexec_fn'], preflight.limit_probe)
            self.assertEqual(Path(options['stdout'].name), mocked.run / 'logs/namespace-launch.log')
            mocked.limits.assert_called_once_with(preflight.resource.RLIMIT_CORE, (0, 0))
            order = ['owner-published', 'fd-open', 'write-origin.json', 'spawn', 'register',
                     'watch', 'stop-start', 'stop-return', 'snapshot-after', 'read-guarded-result.json',
                     'private-scan', 'fd-close', 'write-summary']
            self.assertEqual([mocked.events.index(name) for name in order],
                             sorted(mocked.events.index(name) for name in order))
            self.assertEqual(mocked.events.count('fd-close'), 1)
            summary = json.loads((mocked.run / 'summary.json').read_text())
            self.assertTrue(summary['ownedLaunchIdentityRegistered'])
            self.assertTrue(summary['ownedTreeStopReturned'])
            self.assertTrue(summary['ownedTreeDrainCompleted'])
            self.assertTrue(summary['parentMountReferenceReleased'])
            self.assertFalse(summary['cancellationObserved'])
            self.assertFalse(summary['guestExecutionAuthorized'])
            self.assertEqual(summary['nativeInstrumentedTestsExecuted'], 0)

    def test_outer_capture_origin_write_and_spawn_failures_do_not_start_unowned_work(self):
        for point in ('fd-open', 'fd-inspect', 'write-origin.json', 'spawn'):
            with self.subTest(point=point), self.mocked_outer_preflight(
                    failures={point: OSError(errno.EIO, 'authored')}) as mocked:
                with self.assertRaises((OSError, preflight.PreflightError)):
                    preflight.preflight(mocked.cancellation)
                self.assertFalse(mocked.held)
                self.assertEqual(mocked.events.count('fd-close'), int(point != 'fd-open'))
                self.assertNotIn('stop-start', mocked.events)
                self.assertNotIn('register', mocked.events)
                self.assertEqual(len(mocked.launches), int(point == 'spawn'))
                if (mocked.run / 'summary.json').exists():
                    summary = json.loads((mocked.run / 'summary.json').read_text())
                    self.assertFalse(summary['ownedLaunchIdentityRegistered'])
                    self.assertIsNone(summary['ownedTreeDrainCompleted'])

    def test_outer_registration_and_initial_refresh_failure_still_route_through_owned_stop(self):
        for point in ('register', 'refresh'):
            with self.subTest(point=point), self.mocked_outer_preflight(
                    failures={point: preflight.PreflightError('authored registration refusal')}) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.preflight(mocked.cancellation)
                self.assertEqual(mocked.events.count('stop-start'), 1)
                self.assertLess(mocked.events.index('stop-return'), mocked.events.index('fd-close'))
                self.assertNotIn('watch', mocked.events)
                self.assertEqual(set(mocked.tree.identities), {700} if point == 'refresh' else set())
                summary = json.loads((mocked.run / 'summary.json').read_text())
                self.assertFalse(summary['ownedLaunchIdentityRegistered'])
                self.assertTrue(summary['ownedTreeStopReturned'])
                self.assertFalse(summary['ownedTreeDrainCompleted'])  # Unregistered return is not proof.
                self.assertEqual(summary['failureClass'], 'PreflightError')

    def test_empty_owned_handle_survives_required_identity_or_first_refresh_refusal(self):
        process = SimpleNamespace(pid=700)
        with patch.object(preflight, 'proc_identity', return_value=None) as identity, \
                patch.object(preflight, 'process_table', return_value={}) as table:
            tree = preflight.OwnedTree()
            self.assertIsNone(tree.process)
            self.assertEqual(tree.identities, {})
            identity.assert_not_called()
            table.assert_not_called()
            tree.process = process
            with self.assertRaises(preflight.PreflightError): tree.register(require_identity=True)
            self.assertIs(tree.process, process)
            self.assertEqual(tree.identities, {})
            table.assert_not_called()
            # Existing native callers keep their previous optional behaviour.
            self.assertEqual(preflight.OwnedTree(process).identities, {})
        child = self.fixture_process(700)
        with patch.object(preflight, 'proc_identity', return_value=child), \
                patch.object(preflight, 'process_table', side_effect=OSError(errno.EIO, 'authored')):
            tree = preflight.OwnedTree()
            tree.process = process
            with self.assertRaises(OSError): tree.register(require_identity=True)
            self.assertIs(tree.process, process)
            self.assertEqual(tree.identities, {700: child})

    def test_outer_rechecks_descriptor_parent_and_mount_immediately_before_spawn(self):
        for defect in ('descriptor', 'parent', 'mount', 'cancellation'):
            with self.subTest(defect=defect), self.mocked_outer_preflight() as mocked:
                if defect == 'descriptor':
                    mount = {key: mocked.reference['mount'][key] for key in ('dev', 'ino', 'nstype')}
                    preflight.mount_descriptor_identity.side_effect = [mount, dict(mount, ino=2)]
                elif defect == 'parent':
                    parent = mocked.reference['parent']
                    preflight.required_process_identity.side_effect = [parent, parent, dict(parent, startTicks=101)]
                elif defect == 'mount':
                    preflight.namespace.side_effect = ['mnt:[1]', 'mnt:[1]', 'mnt:[2]']
                else:
                    mocked.cancellation.request(preflight.signal.SIGTERM, None)
                with self.assertRaises(preflight.PreflightError): preflight.preflight(mocked.cancellation)
                self.assertEqual(mocked.launches, [])
                self.assertNotIn('stop-start', mocked.events)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                summary = json.loads((mocked.run / 'summary.json').read_text())
                self.assertFalse(summary['ownedLaunchIdentityRegistered'])
                self.assertIsNone(summary['ownedTreeDrainCompleted'])

    def test_outer_watch_timeout_nonzero_and_stop_failures_remain_failed_after_fd_release(self):
        cases = [({'watch': preflight.PreflightError('authored watchdog')}, 0),
                 ({'watch': subprocess.TimeoutExpired(['authored'], 120)}, 0), ({}, 1),
                 ({'stop-start': preflight.PreflightError('authored unproven drain')}, 0)]
        for failures, code in cases:
            with self.subTest(failures=list(failures), code=code), self.mocked_outer_preflight(
                    failures=failures, watch_code=code) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.preflight(mocked.cancellation)
                self.assertEqual(mocked.events.count('stop-start'), 1)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                self.assertLess(mocked.events.index('stop-start'), mocked.events.index('fd-close'))
                summary = json.loads((mocked.run / 'summary.json').read_text())
                stop_failed = 'stop-start' in failures
                self.assertEqual(summary['ownedTreeStopReturned'], not stop_failed)
                self.assertEqual(summary['ownedTreeDrainCompleted'], not stop_failed)
                self.assertEqual(summary['drainFailureClass'], 'PreflightError' if stop_failed else None)
                self.assertTrue(summary['parentMountReferenceReleased'])

    def test_outer_final_validation_write_and_close_failures_cannot_bypass_stop_or_yield_success(self):
        for point in ('snapshot-after', 'read-guarded-result.json', 'read-network.json',
                      'private-scan', 'fd-close', 'write-summary'):
            with self.subTest(point=point), self.mocked_outer_preflight(
                    failures={point: OSError(errno.EIO, 'authored')}) as mocked:
                with self.assertRaises(OSError): preflight.preflight(mocked.cancellation)
                self.assertFalse(mocked.held)
                self.assertEqual(mocked.events.count('stop-return'), 1)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                self.assertLess(mocked.events.index('stop-return'), mocked.events.index('fd-close'))
                self.assertFalse((mocked.run / 'summary.json').exists())

    def test_outer_missing_result_changed_caller_or_late_private_process_cannot_pass(self):
        for options in ({'result_present': False}, {'caller_changed': True}, {'remaining': True}):
            with self.subTest(options=options), self.mocked_outer_preflight(**options) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.preflight(mocked.cancellation)
                self.assertEqual(mocked.events.count('stop-return'), 1)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                self.assertTrue((mocked.run / 'summary.json').exists())

    def test_outer_repeated_cancellation_during_registration_drain_release_and_summary_never_cuts_cleanup(self):
        for points in (('register',), ('stop-start', 'stop-return'), ('fd-close',),
                       ('write-summary',), ('wrote-summary',),
                       ('register', 'stop-start', 'stop-return', 'fd-close', 'write-summary')):
            with self.subTest(points=points), self.mocked_outer_preflight(cancel_at=points) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.preflight(mocked.cancellation)
                self.assertTrue(mocked.cancellation.requested)
                self.assertEqual(mocked.events.count('stop-return'), 1)
                self.assertEqual(mocked.events.count('snapshot-after'), 1)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                self.assertEqual(mocked.events.count('wrote-summary'), 1)
                self.assertLess(mocked.events.index('stop-return'), mocked.events.index('fd-close'))
                summary = json.loads((mocked.run / 'summary.json').read_text())
                # Cancellation DURING the summary write can follow construction
                # of its value. The actual nonzero outcome is still mandatory.
                self.assertEqual(summary['cancellationObserved'], points[0] not in ('write-summary', 'wrote-summary'))

    def test_outer_unknown_registration_or_stop_errors_remain_unproven_not_an_arbitrary_cleanup_promise(self):
        for point in ('register', 'stop-start'):
            with self.subTest(point=point), self.mocked_outer_preflight(
                    failures={point: RuntimeError('authored unexpected fault')}) as mocked:
                with self.assertRaises(RuntimeError): preflight.preflight(mocked.cancellation)
                self.assertEqual(mocked.events.count('stop-start'), 1)
                self.assertEqual(mocked.events.count('fd-close'), 1)
                self.assertFalse((mocked.run / 'summary.json').exists())
                self.assertNotIn('snapshot-after', mocked.events)

    @contextmanager
    def mocked_outer_main(self, during=None, on_restore=None, on_print=None):
        ambient = lambda *args: None
        handlers = {preflight.signal.SIGTERM: preflight.signal.SIG_IGN, preflight.signal.SIGINT: ambient}
        installed = []
        state = SimpleNamespace(handlers=handlers, installed=installed, events=[], cancellation=None)
        def install(signum, handler):
            previous = handlers[signum]
            installed.append((signum, handler))
            if len(installed) in (5, 6) and on_restore == len(installed):
                previous(signum, None)  # Still the latch, not an actual signal.
                state.events.append('cancel-at-restore')
            handlers[signum] = handler
            return previous
        def outer(cancellation):
            state.cancellation = cancellation
            self.assertEqual(handlers[preflight.signal.SIGTERM], cancellation.request)
            self.assertEqual(handlers[preflight.signal.SIGINT], cancellation.request)
            if during:
                during(state)
            state.events.append('preflight-return')
            return self.run_path
        def printed(*args, **kwargs):
            state.events.append('print')
            if on_print:
                handlers[on_print](on_print, None)
        arguments = SimpleNamespace(preflight=True, inside_network=None, inside_guard=None)
        with patch.object(preflight.argparse.ArgumentParser, 'parse_args', return_value=arguments), \
                patch.object(preflight.signal, 'signal', side_effect=install), \
                patch.object(preflight.os, 'umask', return_value=0o027) as umask, \
                patch.object(preflight, 'preflight', side_effect=outer), \
                patch('builtins.print', side_effect=printed):
            state.umask = umask
            yield state

    def test_main_restores_only_its_known_raising_handlers_not_ambient_ignored_or_custom_handlers(self):
        with self.mocked_outer_main() as mocked:
            preflight.main()
            self.assertEqual(len(mocked.installed), 6)
            for sig in (preflight.signal.SIGTERM, preflight.signal.SIGINT):
                handler = mocked.handlers[sig]
                self.assertEqual(handler.__name__, 'interrupted')
                self.assertIs(handler, next(value for number, value in mocked.installed if number == sig))
                with self.assertRaises(preflight.PreflightError): handler(sig, None)
            self.assertEqual(mocked.events, ['preflight-return', 'print', 'print'])
            self.assertEqual(mocked.umask.call_args_list, [unittest.mock.call(0o077), unittest.mock.call(0o027)])

    def test_main_latches_repeated_cleanup_cancellation_until_known_handlers_are_restored(self):
        def during(mocked):
            for name in ('registration', 'drain', 'fd-release'):
                mocked.handlers[preflight.signal.SIGTERM](preflight.signal.SIGTERM, None)
                mocked.handlers[preflight.signal.SIGINT](preflight.signal.SIGINT, None)
                mocked.events.append(name)
        with self.mocked_outer_main(during=during) as mocked:
            with self.assertRaises(preflight.PreflightError): preflight.main()
            self.assertEqual(mocked.events, ['registration', 'drain', 'fd-release', 'preflight-return'])
            self.assertEqual(len(mocked.installed), 6)
            self.assertTrue(mocked.cancellation.requested)
            self.assertEqual(mocked.umask.call_args_list[-1], unittest.mock.call(0o027))

    def test_main_late_cancellation_during_either_handler_restore_or_final_print_cannot_succeed(self):
        for restore in (5, 6):
            with self.subTest(restore=restore), self.mocked_outer_main(on_restore=restore) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.main()
                self.assertNotIn('print', mocked.events)
                self.assertEqual(len(mocked.installed), 6)
                self.assertTrue(mocked.cancellation.requested)
        for sig in (preflight.signal.SIGTERM, preflight.signal.SIGINT):
            with self.subTest(final_print_signal=sig), self.mocked_outer_main(on_print=sig) as mocked:
                with self.assertRaises(preflight.PreflightError): preflight.main()
                self.assertEqual(mocked.events, ['preflight-return', 'print'])
                self.assertEqual(mocked.umask.call_args_list[-1], unittest.mock.call(0o027))

    def test_main_original_preflight_refusal_restores_known_handlers_and_is_not_success(self):
        refusal = OSError(errno.EIO, 'authored')
        def during(mocked):
            raise refusal
        with self.mocked_outer_main(during=during) as mocked:
            with self.assertRaises(OSError) as caught: preflight.main()
            self.assertIs(caught.exception, refusal)
            self.assertEqual(mocked.events, [])
            self.assertEqual(len(mocked.installed), 6)
            self.assertEqual(mocked.handlers[preflight.signal.SIGTERM].__name__, 'interrupted')

    def test_fd_handoff_has_only_one_outer_pass_fds_and_no_namespace_changing_parent_or_native_inheritance(self):
        tree = ast.parse((ROOT / 'scripts/android-preflight.py').read_text())
        functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
        pops = [(name, node) for name, body in functions.items() for node in ast.walk(body)
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name) and node.func.value.id == 'subprocess'
                and node.func.attr == 'Popen']
        self.assertEqual([name for name, node in pops if any(k.arg == 'pass_fds' for k in node.keywords)], ['preflight'])
        for name, node in pops:
            self.assertFalse(any(k.arg == 'close_fds' and isinstance(k.value, ast.Constant)
                                 and k.value.value is False for k in node.keywords))
        inheritable = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                       and isinstance(node.func, ast.Attribute) and node.func.attr == 'set_inheritable']
        self.assertEqual(len(inheritable), 1)
        self.assertIsInstance(inheritable[0].args[1], ast.Constant)
        self.assertIs(inheritable[0].args[1].value, False)
        self.assertFalse(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                             and node.func.attr in ('setns', 'unshare') for node in ast.walk(tree)))
        for name in ('required_process_identity', 'assert_bound_parent'):
            source = ast.get_source_segment((ROOT / 'scripts/android-preflight.py').read_text(), functions[name])
            self.assertNotIn('proc_identity(', source)
            self.assertNotIn('namespace(', source)

    def test_run_and_all_native_directories_are_owned_and_private(self):
        self.assertEqual(preflight.validate_run(self.run_path, self.root), self.run_path)
        for name in preflight.RUN_DIRECTORIES:
            path = self.run_path / name
            self.assertTrue(path.is_dir())
            self.assertEqual(path.stat().st_mode & 0o077, 0)
        self.assertFalse(list((self.run_path / 'service-runtime').iterdir()))

    def test_run_validation_rejects_escaping_paths_and_wrong_run_names(self):
        for value in (self.root, self.run_path.parent, self.run_path / 'child',
                      self.run_path.parent / 'arbitrary-run-name', self.root / 'preflight-fixture'):
            with self.subTest(path=value.name):
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_run(value, self.root)

    def test_run_validation_rejects_symlink_permissions_and_non_directory(self):
        self.run_path.chmod(0o755)
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_run(self.run_path, self.root)
        self.run_path.chmod(0o700)
        alias = self.run_path.parent / 'preflight-linked123'
        alias.symlink_to(self.run_path, target_is_directory=True)
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_run(alias, self.root)
        alias.unlink()
        alias.write_bytes(b'fixture')
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_run(alias, self.root)

    def test_escaping_ancestor_is_rejected_before_directory_creation(self):
        root = self.root / 'alternate'
        root.mkdir()
        outside = self.root / 'outside'
        outside.mkdir()
        (root / '.work').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(preflight.PreflightError):
            preflight.prepare_run(root)
        self.assertEqual(list(outside.iterdir()), [])

    def test_network_identity_must_differ_from_caller_and_parent(self):
        for current in ('net:[1]', 'net:[2]', 'not-a-namespace', 'mnt:[3]'):
            with self.subTest(current=current):
                with self.assertRaises(preflight.PreflightError):
                    preflight.assert_new_namespace(current, 'net:[1]', 'net:[2]')
        preflight.assert_new_namespace('net:[3]', 'net:[1]', 'net:[2]')

    def test_network_accepts_only_loopback_or_exact_inert_sit0_before_and_after(self):
        for up in (False, True):
            for sit in (False, True):
                for ipv6 in (False, True):
                    with self.subTest(up=up, sit=sit, ipv6=ipv6):
                        state = network_fixture(up, sit, ipv6)
                        original = deepcopy(state)
                        self.assertEqual(preflight.validate_network_state(state, up),
                                         {'lo': 1, 'sit0': 2} if sit else {'lo': 1})
                        self.assertEqual(state, original)

    def test_network_accepts_observed_ipv6_table_omission_and_explicit_local(self):
        # Exact IPv6 JSON from the second lead attempt, not a guessed UP shape.
        self.assertEqual(network_fixture(True)['ipv6Routes'], [
            {'type': 'local', 'dst': '::1', 'dev': 'lo', 'protocol': 'kernel',
             'metric': 0, 'flags': [], 'pref': 'medium'}])
        for table_present in (False, True):
            for scope_present in (False, True):
                with self.subTest(table_present=table_present, scope_present=scope_present):
                    state = network_fixture(True)
                    if table_present:
                        state['ipv6Routes'][0]['table'] = 'local'
                    if scope_present:
                        state['ipv6Routes'][0]['scope'] = 'host'
                    original = deepcopy(state)
                    self.assertEqual(preflight.validate_network_state(state, True), {'lo': 1, 'sit0': 2})
                    self.assertEqual(state, original)  # Never normalize a missing table to local.

    def test_network_ipv6_table_omission_keeps_exact_metadata_and_value_checks(self):
        for field, values in (
                ('table', (None, False, 0, 254, 255, '254', '255', 'main', 'unspec', '', [], {})),
                ('scope', (None, 'global', 'link', 'site', '')),
                ('metric', (None, False, 0.0, '0', -1, 1)),
                ('pref', (None, 'low', 'high', '')),
                ('flags', (None, ['onlink'], ['linkdown'])),
                ('gateway', ('::1',)), ('via', ('::1',)), ('nexthops', ([],)),
                ('encap', ({},)), ('nhid', (1,)), ('unreviewed', (0,))):
            for value in values:
                with self.subTest(field=field, value=value):
                    state = network_fixture(True)
                    state['ipv6Routes'][0][field] = value
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, True)

    def test_network_ipv4_routes_still_require_exact_explicit_local_table(self):
        for index in range(len(network_fixture(True)['ipv4Routes'])):
            for table in ('missing', None, False, 0, 254, 255, 'main', '255', [], {}):
                with self.subTest(route=index, table=table):
                    state = network_fixture(True)
                    if table == 'missing':
                        del state['ipv4Routes'][index]['table']
                    else:
                        state['ipv4Routes'][index]['table'] = table
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, True)

    def test_network_requires_all_four_bounded_reports_even_with_ipv6_disabled(self):
        for name in ('links', 'addresses', 'ipv4Routes', 'ipv6Routes'):
            for replacement in ('missing', None, {}, '[]', [None] * 5):
                with self.subTest(report=name, replacement=replacement):
                    state = network_fixture(up=True, ipv6=False)
                    if replacement == 'missing':
                        del state[name]
                    else:
                        state[name] = replacement
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, True)
        state = network_fixture()
        state['unreadRouteFamily'] = []
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_network_state(state, False)

    def test_network_rejects_missing_unknown_duplicate_or_mismatched_interfaces(self):
        for key in ('links', 'addresses'):
            for replacement in ([], [None], [network_fixture()['links'][1]],
                                [network_fixture()['links'][0]] * 2):
                with self.subTest(report=key, replacement=replacement):
                    state = network_fixture()
                    state[key] = deepcopy(replacement)
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, False)
        for key, index, field, value in (
                ('links', 1, 'ifname', 'veth0'), ('links', 1, 'ifname', 'eth0'),
                ('addresses', 1, 'ifname', 'other'), ('addresses', 0, 'ifindex', 2),
                ('links', 1, 'ifindex', 1), ('links', 0, 'ifindex', True),
                ('links', 1, 'ifindex', '2'), ('links', 1, 'ifindex', 2.0),
                ('links', 0, 'link_type', 'ether'), ('links', 1, 'link_type', 'ether')):
            with self.subTest(report=key, field=field, value=value):
                state = network_fixture()
                state[key][index][field] = value
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_network_state(state, False)

    def test_network_rejects_missing_required_link_and_address_metadata(self):
        for key in ('links', 'addresses'):
            for index in (0, 1):
                required = ['ifindex', 'ifname', 'flags', 'mtu', 'qdisc', 'operstate',
                            'link_type', 'address', 'broadcast']
                required += ['promiscuity', 'allmulti'] if key == 'links' else ['addr_info']
                if index == 1:
                    required += ['link'] + (['linkinfo'] if key == 'links' else [])
                for field in required:
                    with self.subTest(report=key, index=index, field=field):
                        state = network_fixture()
                        del state[key][index][field]
                        with self.assertRaises(preflight.PreflightError):
                            preflight.validate_network_state(state, False)

    def test_network_rejects_up_addressed_attached_or_unknown_sit0_state(self):
        for up in (False, True):
            for field, value in (('flags', ['NOARP', 'UP']), ('flags', ['NOARP', 'LOWER_UP']),
                                 ('flags', ['NOARP', 'NOARP']), ('operstate', 'UP'),
                                 ('operstate', 'UNKNOWN'), ('qdisc', 'noqueue'), ('promiscuity', 1),
                                 ('allmulti', 1), ('address', '192.0.2.1'), ('broadcast', '192.0.2.2'),
                                 ('link', 'eth0'), ('link', 1), ('link_netnsid', 0), ('master', 'br0'),
                                 ('peer', 'veth1'), ('unknown_active_state', True)):
                with self.subTest(up=up, field=field, value=value):
                    state = network_fixture(up)
                    state['links'][1][field] = value
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, up)
            for family_index in (0, 1):
                state = network_fixture(up)
                state['addresses'][1]['addr_info'] = [network_fixture(True)['addresses'][0]['addr_info'][family_index]]
                with self.subTest(up=up, sit_address_family=family_index):
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, up)

    def test_network_rejects_forged_or_configured_sit_tunnel_metadata(self):
        for field, value in (('proto', 'ipip'), ('remote', '192.0.2.1'), ('local', '192.0.2.2'),
                             ('ttl', 32), ('ttl', True), ('pmtudisc', True), ('pmtudisc', 0),
                             ('ikey', 1), ('link', 'eth0')):
            with self.subTest(field=field, value=value):
                state = network_fixture()
                state['links'][1]['linkinfo']['info_data'][field] = value
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_network_state(state, False)
        for metadata in ({}, {'info_kind': 'sit'}, {'info_kind': 'veth', 'info_data': {}},
                         {'info_kind': 'sit', 'info_data': None}):
            with self.subTest(metadata=metadata):
                state = network_fixture()
                state['links'][1]['linkinfo'] = metadata
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_network_state(state, False)

    def test_network_rejects_wrong_loopback_state_and_nonloopback_addresses(self):
        for up in (False, True):
            for field, value in (('flags', ['LOOPBACK', 'UP'] if not up else ['LOOPBACK']),
                                 ('link', None), ('master', 'br0'), ('linkinfo', {'info_kind': 'veth'}),
                                 ('promiscuity', 1), ('allmulti', False)):
                with self.subTest(up=up, field=field):
                    state = network_fixture(up)
                    state['links'][0][field] = value
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, up)
        state = network_fixture()
        state['addresses'][0]['addr_info'] = network_fixture(True)['addresses'][0]['addr_info']
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_network_state(state, False)
        for field, value in (('local', '192.0.2.1'), ('local', '127.0.0.2'), ('prefixlen', 0),
                             ('scope', 'global'), ('peer', '192.0.2.2'), ('tentative', True)):
            with self.subTest(field=field, value=value):
                state = network_fixture(True)
                state['addresses'][0]['addr_info'][0][field] = value
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_network_state(state, True)
        for info in ([], [None], [network_fixture(True)['addresses'][0]['addr_info'][0]] * 2):
            state = network_fixture(True)
            state['addresses'][0]['addr_info'] = info
            with self.assertRaises(preflight.PreflightError):
                preflight.validate_network_state(state, True)

    def test_network_rejects_every_nonempty_initial_ipv4_or_ipv6_route_list(self):
        for key in ('ipv4Routes', 'ipv6Routes'):
            for routes in ([{}], [{'dst': 'default'}], network_fixture(True)[key]):
                with self.subTest(family=key, routes=routes):
                    state = network_fixture()
                    state[key] = routes
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, False)

    def test_network_after_requires_only_complete_kernel_loopback_routes(self):
        for key in ('ipv4Routes', 'ipv6Routes'):
            for field, value in (('dst', 'default'), ('dst', '192.0.2.0/24'), ('dst', '2001:db8::/64'),
                                 ('dev', 'sit0'), ('table', 'main'), ('protocol', 'static'),
                                 ('type', 'unicast'), ('flags', ['onlink']), ('gateway', '127.0.0.2'),
                                 ('via', '::1'), ('nexthops', []), ('encap', {}), ('nhid', 1)):
                with self.subTest(family=key, field=field, value=value):
                    state = network_fixture(True)
                    state[key][0][field] = value
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, True)
            for field in network_fixture(True)[key][0]:
                with self.subTest(family=key, missing_field=field):
                    state = network_fixture(True)
                    del state[key][0][field]
                    with self.assertRaises(preflight.PreflightError):
                        preflight.validate_network_state(state, True)
            for routes in ([], [None], [network_fixture(True)[key][0]] * 2):
                state = network_fixture(True)
                state[key] = routes
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_network_state(state, True)
        state = network_fixture(True, ipv6=False)
        state['ipv6Routes'] = network_fixture(True)['ipv6Routes']
        with self.assertRaises(preflight.PreflightError):
            preflight.validate_network_state(state, True)

    def test_network_reports_have_child_only_file_size_and_json_bounds(self):
        with patch.object(preflight.resource, 'setrlimit') as limit:
            preflight.limit_network_report()
        limit.assert_called_once_with(preflight.resource.RLIMIT_FSIZE, (65536, 65536))
        for payload in (b'', b'{', b'[{"ifname":"lo","ifname":"sit0"}]', b'[NaN]',
                        b'[Infinity]', b'[' * 1100, b' ' * (preflight.MAX_NETWORK_REPORT + 1)):
            with self.subTest(payload_prefix=payload[:40]):
                run = preflight.prepare_run(self.root)
                def report(arguments, **kwargs):
                    kwargs['stdout'].write(payload)
                with patch.object(preflight.subprocess, 'run', side_effect=report), \
                        patch.object(preflight.subprocess, 'check_output', side_effect=AssertionError('No buffering')):
                    with self.assertRaises(preflight.PreflightError):
                        preflight.network_snapshot('/authored/ip', run, 'before')

    @contextmanager
    def mocked_network_entry(self, before=None, after=None, networks=None, fail_at=None):
        # ALL execution points are mocked. Even a wiring regression cannot
        # accidentally invoke ip, unshare, an Android process or the mount guard.
        before = network_fixture() if before is None else before
        after = network_fixture(True) if after is None else after
        run = preflight.prepare_run(self.root)
        preflight.json_write(run / 'origin.json', {
            'host': {'networkNamespace': 'net:[1]'}, 'inputs': {'tools': {'ip': '/authored/ip'}}})
        events = []
        reads = iter([before[name] for name, _, _ in preflight.NETWORK_REPORTS]
                     + [after[name] for name, _, _ in preflight.NETWORK_REPORTS])
        network_index = 0
        commands = []
        def identity(kind, pid='self'):
            nonlocal network_index
            self.assertEqual(kind, 'net')
            if pid != 'self':
                return 'net:[2]'
            value = (networks or {}).get(network_index, 'net:[3]')
            network_index += 1
            events.append(('identity', value))
            return value
        def execute(arguments, **kwargs):
            commands.append(arguments)
            events.append(('command', arguments))
            if fail_at == len(commands):
                raise subprocess.TimeoutExpired(arguments, 5)
            if arguments == ['/authored/ip', 'link', 'set', 'dev', 'lo', 'up']:
                self.assertEqual(kwargs, {'check': True, 'timeout': 5})
            else:
                self.assertIn(arguments, [['/authored/ip', '-json', *args]
                                          for _, args, _ in preflight.NETWORK_REPORTS])
                self.assertTrue(kwargs['check'])
                self.assertEqual(kwargs['timeout'], 5)
                self.assertIs(kwargs['preexec_fn'], preflight.limit_network_report)
                self.assertTrue(Path(kwargs['stdout'].name).is_relative_to(run / 'logs'))
                kwargs['stdout'].write(json.dumps(next(reads)).encode())
        with patch.object(preflight, 'validate_run', return_value=run), \
                patch.object(preflight, 'namespace', side_effect=identity), \
                patch.object(preflight.subprocess, 'run', side_effect=execute), \
                patch.object(preflight.subprocess, 'check_output', side_effect=AssertionError('Unexpected subprocess')), \
                patch.object(preflight.subprocess, 'Popen', side_effect=AssertionError('No real subprocess')), \
                patch.object(preflight.os, 'execv', side_effect=ReachedMockGuard) as guard:
            yield SimpleNamespace(run=run, events=events, commands=commands, guard=guard)

    def test_guard_exec_failure_is_diagnostic_only_and_preserves_exact_handoff(self):
        for number in (errno.EACCES, errno.EPERM):
            with self.subTest(errno=number), self.mocked_network_entry() as mocked:
                refusal = PermissionError(number, 'authored-sensitive-message', '/authored/private-key')
                mocked.guard.side_effect = refusal
                with self.assertRaises(PermissionError) as caught:
                    preflight.inside_network(mocked.run)
                self.assertIs(caught.exception, refusal)
                self.assertEqual(len(mocked.commands), 9)  # Eight reports and private lo up only.
                guard = str(ROOT / 'scripts/linux-namespace.sh')
                mocked.guard.assert_called_once_with(guard, [guard, '--mask-run',
                    str(mocked.run / 'service-runtime'), '--', sys.executable, '-B',
                    str(ROOT / 'scripts/android-preflight.py'), '--inside-guard', str(mocked.run)])
                self.assertTrue((mocked.run / 'network.json').is_file())
                self.assertFalse((mocked.run / 'guarded-result.json').exists())
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text()), {
                    'schemaVersion': 1, 'phase': 'mount-guard-exec',
                    'errorClass': 'PermissionError', 'errno': number,
                })

    def test_failure_phase_receipts_have_only_fixed_labels_and_sanitized_error_fields(self):
        SecretSubclass = type('authored_secret_class_name', (PermissionError,), {})
        failures = [
            (PermissionError(errno.EACCES, 'authored-sensitive-message', '/authored/private-key'), 'PermissionError', errno.EACCES),
            (preflight.PreflightError('authored-sensitive-message'), 'PreflightError', None),
            (SecretSubclass(errno.EPERM, 'authored-sensitive-message', '/authored/private-key'), 'OSError', errno.EPERM),
            (ValueError('authored-sensitive-message'), 'ValueError', None),
        ]
        for phase in sorted(preflight.FAILURE_PHASES):
            for refusal, category, number in failures:
                with self.subTest(phase=phase, category=category):
                    run = preflight.prepare_run(self.root)
                    old = os.umask(0o077)
                    try:
                        with self.assertRaises(type(refusal)) as caught:
                            with preflight.failure_phase(run, phase):
                                raise refusal
                    finally:
                        os.umask(old)
                    self.assertIs(caught.exception, refusal)
                    path = run / 'failure-phase.json'
                    self.assertEqual(path.stat().st_mode & 0o077, 0)
                    text = path.read_text()
                    self.assertEqual(json.loads(text), {'schemaVersion': 1, 'phase': phase,
                        'errorClass': category, 'errno': number})
                    for private in ('authored-sensitive-message', '/authored/private-key',
                                    'authored_secret_class_name', 'Traceback'):
                        self.assertNotIn(private, text)

    def test_failure_phase_rejects_unknown_label_before_entering_or_writing(self):
        entered = False
        with self.assertRaises(preflight.PreflightError):
            with preflight.failure_phase(self.run_path, 'authored-sensitive-phase'):
                entered = True
        self.assertFalse(entered)
        self.assertFalse((self.run_path / 'failure-phase.json').exists())

    def test_failure_phase_bounds_errno_and_does_not_write_on_success(self):
        with preflight.failure_phase(self.run_path, 'mount-guard-exec'):
            pass
        self.assertFalse((self.run_path / 'failure-phase.json').exists())
        for number in (-1, 4096, True, 'authored-sensitive-errno', None):
            with self.subTest(errno=number):
                run = preflight.prepare_run(self.root)
                refusal = OSError('authored-sensitive-message')
                refusal.errno = number
                with self.assertRaises(OSError):
                    with preflight.failure_phase(run, 'mount-guard-exec'):
                        raise refusal
                self.assertIsNone(json.loads((run / 'failure-phase.json').read_text())['errno'])

    def test_failure_phase_write_refusal_preserves_original_and_never_overwrites(self):
        path = self.run_path / 'failure-phase.json'
        path.write_bytes(b'authored-prior-receipt\n')
        refusal = PermissionError(errno.EACCES, 'authored-original-secret')
        for writer_error in (None, PermissionError(errno.EPERM, 'authored-write-secret')):
            with self.subTest(write_failure=writer_error is not None), \
                    patch.object(preflight.sys, 'stderr', io.StringIO()) as stderr:
                if writer_error is None:
                    with self.assertRaises(PermissionError) as caught:
                        with preflight.failure_phase(self.run_path, 'mount-guard-exec'):
                            raise refusal
                else:
                    with patch.object(preflight, 'json_write', side_effect=writer_error) as writer:
                        with self.assertRaises(PermissionError) as caught:
                            with preflight.failure_phase(self.run_path, 'mount-guard-exec'):
                                raise refusal
                        writer.assert_called_once()
                self.assertIs(caught.exception, refusal)
                self.assertEqual(path.read_bytes(), b'authored-prior-receipt\n')
                self.assertEqual(stderr.getvalue(), 'Stage 0 failure-phase receipt could not be retained.\n')

    def test_failure_phase_secondary_warning_failure_preserves_original_refusal(self):
        refusal = PermissionError(errno.EACCES, 'authored-original-secret', '/authored/private-key')
        for warning_failure in (OSError(errno.ENOSPC, 'authored-warning-secret'), ValueError('authored-closed-stream')):
            with self.subTest(warning_failure=type(warning_failure).__name__), \
                    patch.object(preflight, 'json_write', side_effect=PermissionError(errno.EPERM, 'authored-write-secret')) as writer, \
                    patch.object(preflight.sys, 'stderr') as stderr:
                stderr.write.side_effect = warning_failure
                with self.assertRaises(PermissionError) as caught:
                    with preflight.failure_phase(self.run_path, 'guard-parent-mount'):
                        raise refusal
                self.assertIs(caught.exception, refusal)
                writer.assert_called_once()
                stderr.write.assert_called_once_with('Stage 0 failure-phase receipt could not be retained.')
                self.assertFalse((self.run_path / 'failure-phase.json').exists())

    @contextmanager
    def mocked_guard_entry(self, fail_at=None, refusal=None, unsafe=None):
        # No real namespace, mount, capability, listener or process operation enters
        # this fixture. Owned temporary JSON is the only filesystem work performed.
        run = preflight.prepare_run(self.root)
        reference = self.parent_reference(run)
        preflight.json_write(run / 'origin.json', {'host': {
            'networkNamespace': 'net:[1]', 'mountNamespace': 'mnt:[1]',
        }, 'parentMountReference': reference,
            'inputs': {'tools': {'adb': '/authored/adb', 'emulator': '/authored/emulator'}}})
        preflight.json_write(run / 'network.json', {'networkNamespace': 'net:[3]'})
        events = []
        refusal = refusal or PermissionError(errno.EACCES, 'authored-private-message', '/authored/private-key')
        def observed(phase):
            events.append(phase)
            if phase == fail_at:
                raise refusal
        def identity(kind, pid='self'):
            self.assertEqual((kind, pid), ('net', 'self'))
            observed('guard-self-network')
            return 'net:[1]' if unsafe == 'caller-network' else 'net:[3]'
        def self_mount(run):
            observed('guard-self-mount')
            return {'namespace': 'mnt:[1]' if unsafe == 'caller-mount' else 'mnt:[3]',
                    'dev': 1, 'ino': 1 if unsafe == 'parent-mount' else 3,
                    'nstype': preflight.CLONE_NEWNS}
        def parent_mount(fd):
            self.assertEqual(fd, 42)
            observed('guard-parent-mount')
            return {'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS}
        def parent_identity(pid):
            self.assertEqual(pid, 12345)
            observed('guard-parent-identity')
            return dict(reference['parent'], state='S', parentPid=1)
        def close_parent(fd):
            self.assertEqual(fd, 42)
            observed('guard-parent-fd-close')
        def statvfs(path):
            path = str(path)
            self.assertIn(path, ('/', '/proc', '/sys', '/dev', '/run', '/var/run'))
            observed('guard-service-aliases' if path in ('/run', '/var/run') else 'guard-protected-mounts')
            return SimpleNamespace(f_flag=0 if unsafe == 'writable-host' and path == '/' else os.ST_RDONLY)
        real_stat, real_iterdir, real_read_text = Path.stat, Path.iterdir, Path.read_text
        scratch = {Path('/tmp'), ROOT / '.work/tmp', Path('/var/lib/xkb'), ROOT / '.work/cache/xkb',
                   Path('/dev/shm'), ROOT / '.work/shm', Path('/run'), Path('/var/run'), run / 'service-runtime'}
        def stat_path(path, *args, **kwargs):
            if path in scratch:
                observed('guard-scratch-identities')
                return SimpleNamespace(st_dev=1, st_ino=3 if unsafe == 'wrong-backing' and path == Path('/tmp') else 2)
            return real_stat(path, *args, **kwargs)
        def iterdir(path):
            if path in (Path('/run'), Path('/var/run')):
                return iter([Path('/authored-unexpected')] if unsafe == 'service-content' else [])
            return real_iterdir(path)
        def read_text(path, *args, **kwargs):
            if path == Path('/proc/self/status'):
                observed('guard-capabilities')
                return ('NoNewPrivs:\t' + ('0' if unsafe == 'no-new-privs' else '1') +
                        '\nCapEff:\t' + ('00000001' if unsafe == 'effective-capability' else '00000000') +
                        '\nCapBnd:\t' + ('00000001' if unsafe == 'bounding-capability' else '00000000') + '\n')
            return real_read_text(path, *args, **kwargs)
        real_bounded = preflight.bounded
        def bounded(path, *args, **kwargs):
            observed('guard-inputs')
            return real_bounded(path, *args, **kwargs)
        def environment(*args):
            observed('guard-native-environment')
            return {'authored': 'scoped'}
        def listeners(port):
            self.assertIn(port, (5037, preflight.PORT))
            observed('guard-listeners')
            return [('authored-address', '123')] if unsafe == 'listener' else []
        with ExitStack() as stack:
            def mock(module, name, **options):
                return stack.enter_context(patch.object(module, name, **options))
            current_mount = mock(preflight, 'self_mount_identity', side_effect=self_mount)
            inherited_mount = mock(preflight, 'mount_descriptor_identity', side_effect=parent_mount)
            parent_read = mock(preflight, 'proc_stat', side_effect=parent_identity)
            parent_pid = mock(preflight.os, 'getppid', return_value=12345)
            inherit = mock(preflight.os, 'set_inheritable')
            close = mock(preflight.os, 'close', side_effect=close_parent)
            probe = mock(preflight, 'probe', side_effect=ReachedMockProbe)
            for context in (
                    patch.object(preflight, 'validate_run', return_value=run),
                    patch.object(preflight, 'bounded', side_effect=bounded),
                    patch.object(preflight, 'namespace', side_effect=identity),
                    patch.object(preflight.resource, 'getrlimit', return_value=(128, 128)),
                    patch.object(preflight.os, 'statvfs', side_effect=statvfs),
                    patch.object(Path, 'stat', stat_path), patch.object(Path, 'iterdir', iterdir),
                    patch.object(Path, 'read_text', read_text),
                    patch.object(preflight, 'native_environment', side_effect=environment),
                    patch.object(preflight, 'listener_inodes', side_effect=listeners),
                    patch.object(preflight.subprocess, 'Popen', side_effect=AssertionError('No native process')),
                    patch.object(preflight.subprocess, 'run', side_effect=AssertionError('No native command')),
                    patch.object(preflight.subprocess, 'check_output', side_effect=AssertionError('No native command')),
                    patch.object(preflight.os, 'execv', side_effect=AssertionError('No exec')),
                    patch.object(preflight.socket, 'create_connection', side_effect=AssertionError('No connection'))):
                stack.enter_context(context)
            yield SimpleNamespace(run=run, events=events, probe=probe, refusal=refusal,
                                  reference=reference, self_mount=current_mount, mount=inherited_mount,
                                  parent_read=parent_read, parent_pid=parent_pid, inherit=inherit, close=close)

    def test_guard_rejects_invalid_reference_metadata_before_adopting_or_guess_closing_a_descriptor(self):
        for defect in ('missing', 'wrong-run', 'stdio', 'boolean-fd', 'extra'):
            with self.subTest(defect=defect), self.mocked_guard_entry() as mocked:
                path = mocked.run / 'origin.json'
                origin = json.loads(path.read_text())
                if defect == 'missing':
                    del origin['parentMountReference']
                elif defect == 'wrong-run':
                    origin['parentMountReference']['runName'] = 'preflight-other123'
                elif defect in ('stdio', 'boolean-fd'):
                    origin['parentMountReference']['fd'] = 1 if defect == 'stdio' else True
                else:
                    origin['parentMountReference']['extra'] = 42
                path.write_text(json.dumps(origin))
                with self.assertRaises(preflight.PreflightError): preflight.inside_guard(mocked.run)
                mocked.inherit.assert_not_called()
                mocked.close.assert_not_called()
                mocked.probe.assert_not_called()
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text())['phase'], 'guard-inputs')

    def test_guard_adopted_closed_or_reused_fd_is_rejected_closed_once_and_never_reaches_native_tail(self):
        for defect in ('closed', 'device', 'inode', 'wrong-type'):
            with self.subTest(defect=defect), self.mocked_guard_entry() as mocked:
                if defect == 'closed':
                    mocked.inherit.side_effect = OSError(errno.EBADF, 'authored')
                else:
                    value = {'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS}
                    value[{'device': 'dev', 'inode': 'ino', 'wrong-type': 'nstype'}[defect]] += 1
                    mocked.mount.side_effect = lambda fd: value
                with self.assertRaises((OSError, preflight.PreflightError)): preflight.inside_guard(mocked.run)
                mocked.inherit.assert_called_once_with(42, False)
                mocked.close.assert_called_once_with(42)
                mocked.probe.assert_not_called()
                self.assertNotIn('guard-native-environment', mocked.events)
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text())['phase'], 'guard-parent-mount')

    def test_guard_integrates_real_fd_validation_without_writable_path_other_ns_or_unsupported_ioctl_fallback(self):
        implementation = preflight.mount_descriptor_identity
        for options in ({'flags': os.O_WRONLY}, {'flags': os.O_PATH}, {'nstype': 0x40000000},
                        {'inspect_error': OSError(errno.ENOTTY, 'authored file, not nsfs')},
                        {'inspect_error': OSError(errno.EBADF, 'authored closed reference')}):
            with self.subTest(options=options), self.mocked_guard_entry() as mocked, \
                    self.mocked_mount_fd(**options) as events:
                mocked.mount.side_effect = implementation
                with self.assertRaises((OSError, preflight.PreflightError)): preflight.inside_guard(mocked.run)
                mocked.probe.assert_not_called()
                self.assertEqual(events.count(('close', 42)), 1)
                self.assertFalse(any(event[0] == 'open' for event in events))
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text())['phase'], 'guard-parent-mount')

    def test_guard_rechecks_actual_parent_before_after_and_at_final_admission(self):
        for check in (0, 1, 2):
            for defect in ('reparent', 'reuse', 'unreadable', 'dead'):
                with self.subTest(check=check, defect=defect), self.mocked_guard_entry() as mocked:
                    parent = dict(mocked.reference['parent'], state='S')
                    identities = [dict(parent) for _ in range(3)]
                    if defect == 'reparent':
                        mocked.parent_pid.side_effect = [12345] * (2 * check) + [54321]
                    else:
                        identities[check] = (None if defect == 'unreadable' else
                            dict(parent, state='Z') if defect == 'dead' else dict(parent, startTicks=101))
                        mocked.parent_read.side_effect = identities
                    with self.assertRaises(preflight.PreflightError): preflight.inside_guard(mocked.run)
                    mocked.close.assert_called_once_with(42)
                    mocked.probe.assert_not_called()
                    self.assertNotIn('guard-native-environment', mocked.events)
                    self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text())['phase'], 'guard-parent-identity')

    def test_guard_closes_both_real_reference_contexts_before_mock_native_probe_and_on_self_close_failure(self):
        mount_implementation = preflight.mount_descriptor_identity
        self_implementation = preflight.self_mount_identity
        for fail_self_close in (False, True):
            with self.subTest(fail_self_close=fail_self_close), self.mocked_guard_entry() as mocked:
                held, closed = {42}, []
                mocked.mount.side_effect = mount_implementation
                mocked.self_mount.side_effect = self_implementation
                def open_fd(path, flags):
                    self.assertEqual((path, flags), ('/proc/self/ns/mnt', os.O_RDONLY | os.O_CLOEXEC))
                    held.add(43)
                    return 43
                def close_fd(fd):
                    self.assertIn(fd, held)
                    held.remove(fd)
                    closed.append(fd)
                    if fd == 43 and fail_self_close:
                        raise OSError(errno.EIO, 'authored self-close refusal')
                def inspect(fd, operation):
                    self.assertIn(fd, held)
                    self.assertIn(operation, (preflight.fcntl.F_GETFL, preflight.fcntl.F_GETFD))
                    return os.O_RDONLY if operation == preflight.fcntl.F_GETFL else preflight.fcntl.FD_CLOEXEC
                def namespace(kind, pid='self'):
                    self.assertEqual(pid, 'self')
                    self.assertIn(kind, ('mnt', 'net'))
                    return kind + ':[3]'
                def probe(*args, **kwargs):
                    self.assertEqual(held, set())
                    raise ReachedMockProbe
                mocked.close.side_effect = close_fd
                mocked.probe.side_effect = probe
                with patch.object(preflight.os, 'open', side_effect=open_fd), \
                        patch.object(preflight.os, 'fstat', side_effect=lambda fd: SimpleNamespace(st_dev=1, st_ino=1 if fd == 42 else 3)), \
                        patch.object(preflight.fcntl, 'fcntl', side_effect=inspect), \
                        patch.object(preflight.fcntl, 'ioctl', return_value=preflight.CLONE_NEWNS), \
                        patch.object(preflight, 'namespace', side_effect=namespace):
                    with self.assertRaises(OSError if fail_self_close else ReachedMockProbe):
                        preflight.inside_guard(mocked.run)
                self.assertEqual(closed, [43, 42])
                self.assertEqual(held, set())
                if fail_self_close:
                    mocked.probe.assert_not_called()
                    self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text())['phase'], 'guard-self-mount')

    def test_inside_guard_permission_receipts_distinguish_each_preprobe_phase(self):
        phases = preflight.FAILURE_PHASES - {
            'mount-guard-exec', 'guard-mount-separation', 'origin-parent-mount', 'origin-parent-fd-close',
        }
        for phase in sorted(phases):
            with self.subTest(phase=phase), self.mocked_guard_entry(fail_at=phase) as mocked:
                with self.assertRaises(PermissionError) as caught:
                    preflight.inside_guard(mocked.run)
                self.assertIs(caught.exception, mocked.refusal)
                mocked.probe.assert_not_called()
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text()), {
                    'schemaVersion': 1, 'phase': phase, 'errorClass': 'PermissionError', 'errno': errno.EACCES,
                })
                if phase in ('guard-self-network', 'guard-self-mount', 'guard-parent-mount'):
                    self.assertNotIn('guard-protected-mounts', mocked.events)
                self.assertFalse((mocked.run / 'guarded-result.json').exists())

    def test_inside_guard_still_rejects_caller_parent_mounts_and_unsafe_host_state(self):
        cases = {
            'caller-network': 'guard-self-network', 'caller-mount': 'guard-mount-separation',
            'parent-mount': 'guard-mount-separation', 'writable-host': 'guard-protected-mounts',
            'service-content': 'guard-service-aliases', 'wrong-backing': 'guard-scratch-identities',
            'no-new-privs': 'guard-capabilities', 'effective-capability': 'guard-capabilities',
            'bounding-capability': 'guard-capabilities', 'listener': 'guard-listeners',
        }
        for unsafe, phase in cases.items():
            with self.subTest(unsafe=unsafe), self.mocked_guard_entry(unsafe=unsafe) as mocked:
                with self.assertRaises(preflight.PreflightError):
                    preflight.inside_guard(mocked.run)
                mocked.probe.assert_not_called()
                self.assertEqual(json.loads((mocked.run / 'failure-phase.json').read_text()), {
                    'schemaVersion': 1, 'phase': phase, 'errorClass': 'PreflightError', 'errno': None,
                })

    def test_successful_guard_validation_reaches_only_mock_probe_without_failure_receipt(self):
        with self.mocked_guard_entry() as mocked:
            with self.assertRaises(ReachedMockProbe):
                preflight.inside_guard(mocked.run)
            mocked.probe.assert_called_once()
            self.assertEqual(mocked.probe.call_args.args[-2:], ('adb-help', ['/authored/adb', 'help']))
            self.assertFalse((mocked.run / 'failure-phase.json').exists())
            self.assertEqual(set(mocked.events), preflight.FAILURE_PHASES - {
                'mount-guard-exec', 'guard-mount-separation', 'origin-parent-mount', 'origin-parent-fd-close',
            })
            self.assertLess(mocked.events.index('guard-parent-fd-close'), mocked.events.index('guard-native-environment'))

    def test_unvalidated_guard_run_cannot_write_diagnostics_or_start_a_probe(self):
        with patch.object(preflight, 'validate_run', side_effect=preflight.PreflightError('Invalid owned run')), \
                patch.object(preflight, 'json_write') as writer, \
                patch.object(preflight, 'probe', side_effect=AssertionError('No probe')) as probe:
            with self.assertRaises(preflight.PreflightError):
                preflight.inside_guard(self.run_path)
            writer.assert_not_called()
            probe.assert_not_called()

    def test_network_entry_rechecks_both_families_after_only_private_lo_up(self):
        for sit in (False, True):
            with self.subTest(sit=sit):
                before, after = network_fixture(sit=sit), network_fixture(True, sit=sit)
                with self.mocked_network_entry(before, after) as mocked:
                    with self.assertRaises(ReachedMockGuard):
                        preflight.inside_network(mocked.run)
                    reads = [['/authored/ip', '-json', *args] for _, args, _ in preflight.NETWORK_REPORTS]
                    self.assertEqual(mocked.commands, reads + [['/authored/ip', 'link', 'set', 'dev', 'lo', 'up']] + reads)
                    self.assertEqual(sum(event[0] == 'identity' for event in mocked.events), 5)
                    self.assertEqual(mocked.events[-1], ('identity', 'net:[3]'))
                    mocked.guard.assert_called_once()
                    self.assertIn('--inside-guard', mocked.guard.call_args.args[1])
                    receipt = json.loads((mocked.run / 'network.json').read_text())
                    self.assertEqual(receipt, {'networkNamespace': 'net:[3]', 'loopbackOnly': True,
                                              'inertKernelSit0': sit, 'before': before, 'after': after})

    def test_network_entry_preserves_observed_ipv6_table_omission_in_raw_and_final_receipts(self):
        before, after = network_fixture(), network_fixture(True)
        with self.mocked_network_entry(before, after) as mocked:
            with self.assertRaises(ReachedMockGuard):
                preflight.inside_network(mocked.run)
            reads = [['/authored/ip', '-json', *args] for _, args, _ in preflight.NETWORK_REPORTS]
            self.assertEqual(mocked.commands, reads + [['/authored/ip', 'link', 'set', 'dev', 'lo', 'up']] + reads)
            mocked.guard.assert_called_once()
            for phase, expected in (('before', before), ('after', after)):
                for name, _, _ in preflight.NETWORK_REPORTS:
                    path = mocked.run / 'logs' / f'network-{phase}-{name}.json'
                    self.assertEqual(json.loads(path.read_text()), expected[name])
            receipt = json.loads((mocked.run / 'network.json').read_text())
            self.assertEqual(receipt['after']['ipv6Routes'], after['ipv6Routes'])
            self.assertNotIn('table', receipt['after']['ipv6Routes'][0])

    def test_network_entry_rejects_inherited_namespace_before_any_ip_read_or_mutation(self):
        for namespace in ('net:[1]', 'net:[2]', 'mnt:[3]', 'not-a-namespace'):
            with self.subTest(namespace=namespace):
                with self.mocked_network_entry(networks={0: namespace, 1: namespace}) as mocked:
                    with self.assertRaises(preflight.PreflightError):
                        preflight.inside_network(mocked.run)
                    self.assertEqual(mocked.commands, [])
                    mocked.guard.assert_not_called()

    def test_network_entry_rejects_invalid_before_state_without_any_network_mutation(self):
        for defect in ('missing_sit_identity', 'up_sit', 'ipv4_route', 'ipv6_route'):
            state = network_fixture()
            if defect == 'missing_sit_identity':
                del state['links'][1]['linkinfo']
            elif defect == 'up_sit':
                state['links'][1]['flags'].append('UP')
            else:
                state['ipv4Routes' if defect == 'ipv4_route' else 'ipv6Routes'] = [{'dst': 'default'}]
            with self.subTest(defect=defect), self.mocked_network_entry(before=state) as mocked:
                with self.assertRaises(preflight.PreflightError):
                    preflight.inside_network(mocked.run)
                self.assertEqual(len(mocked.commands), 4)
                self.assertTrue(all(arguments[1] == '-json' for arguments in mocked.commands))
                mocked.guard.assert_not_called()
                self.assertFalse((mocked.run / 'network.json').exists())

    def test_network_entry_rejects_invalid_after_state_and_disappearing_sit_without_guard(self):
        for defect in ('up_sit', 'ipv6_route', 'ipv6_table', 'ipv6_extra', 'disappeared_sit'):
            state = network_fixture(True, sit=defect != 'disappeared_sit')
            if defect == 'up_sit':
                state['links'][1]['flags'].append('UP')
            elif defect == 'ipv6_route':
                state['ipv6Routes'][0]['dst'] = 'default'
            elif defect == 'ipv6_table':
                state['ipv6Routes'][0]['table'] = 'main'
            elif defect == 'ipv6_extra':
                state['ipv6Routes'][0]['gateway'] = '::1'
            with self.subTest(defect=defect), self.mocked_network_entry(after=state) as mocked:
                with self.assertRaises(preflight.PreflightError):
                    preflight.inside_network(mocked.run)
                self.assertEqual(len(mocked.commands), 9)
                mocked.guard.assert_not_called()
                self.assertFalse((mocked.run / 'network.json').exists())

    def test_network_entry_rejects_namespace_changes_before_and_after_mutation(self):
        for check, command_count in ((1, 0), (2, 4), (3, 5), (4, 9)):
            for changed in ('net:[1]', 'net:[2]', 'net:[4]'):
                with self.subTest(check=check, changed=changed):
                    with self.mocked_network_entry(networks={check: changed}) as mocked:
                        with self.assertRaises(preflight.PreflightError):
                            preflight.inside_network(mocked.run)
                        self.assertEqual(len(mocked.commands), command_count)
                        mocked.guard.assert_not_called()
                        self.assertFalse((mocked.run / 'network.json').exists())

    def test_network_entry_never_enters_guard_after_any_ip_timeout(self):
        for failure in range(1, 10):
            with self.subTest(command=failure), self.mocked_network_entry(fail_at=failure) as mocked:
                with self.assertRaises(subprocess.TimeoutExpired):
                    preflight.inside_network(mocked.run)
                self.assertEqual(len(mocked.commands), failure)
                mocked.guard.assert_not_called()
                self.assertFalse((mocked.run / 'network.json').exists())

    def test_inherited_service_configuration_never_authorizes_reuse(self):
        preflight.validate_host_environment({}, 'core\n', False, False)
        for name in preflight.PROHIBITED_ENV:
            with self.subTest(name=name):
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_host_environment({name: 'authored-untrusted-value'}, 'core', False, False)

    def test_host_coredump_service_and_device_nodes_require_separate_review(self):
        for pattern, usb, pcm in (('|handler', False, False), ('/outside/core', False, False),
                                   ('core', True, False), ('core', False, True)):
            with self.subTest(pattern=pattern, usb=usb, pcm=pcm):
                with self.assertRaises(preflight.PreflightError):
                    preflight.validate_host_environment({}, pattern, usb, pcm)

    def test_final_native_environment_scopes_every_path_without_mutating_parent(self):
        base = {'HOME': 'untrusted-home', 'PATH': '/existing-tools',
                'DISPLAY': 'untrusted-display', 'XAUTHORITY': 'untrusted-cookie',
                'DBUS_SESSION_BUS_ADDRESS': 'untrusted-bus', 'LD_PRELOAD': 'untrusted-loader'}
        before = dict(base)
        values = preflight.native_environment(base, self.run_path)
        self.assertEqual(base, before)
        for key in ('HOME', 'TMPDIR', 'TMP', 'TEMP', 'XDG_RUNTIME_DIR', 'ANDROID_USER_HOME',
                    'ANDROID_AVD_HOME', 'ANDROID_EMULATOR_HOME', 'ANDROID_EMULATOR_DISCOVERY_DIR', 'ADB_VENDOR_KEYS'):
            with self.subTest(key=key):
                self.assertTrue(Path(values[key]).is_relative_to(self.run_path))
        for key in ('DISPLAY', 'XAUTHORITY', 'DBUS_SESSION_BUS_ADDRESS', 'LD_PRELOAD'):
            self.assertNotIn(key, values)
        self.assertEqual(values['ADB_SERVER_SOCKET'], 'tcp:127.0.0.1:5038')
        self.assertEqual(values['ANDROID_ADB_SERVER_PORT'], '5038')
        self.assertEqual(values['ANDROID_SERIAL'], 'emulator-5580')

    def test_trace_argv_has_paths_and_endpoints_but_no_key_or_payload_buffers(self):
        argv = preflight.traced_command({'strace': '/existing/strace'}, self.run_path, 'authored', ['/existing/adb', 'help'])
        self.assertIn('--kill-on-exit', argv)
        self.assertIn('--interruptible=2', argv)
        self.assertIn('-f', argv)
        self.assertIn('trace=%file,connect,bind,listen,getsockname,getpeername', argv)
        self.assertNotIn('read', ','.join(argv))
        self.assertNotIn('write', ','.join(argv))
        self.assertNotIn('sendto', ','.join(argv))
        self.assertNotIn('recvfrom', ','.join(argv))
        self.assertEqual(argv[-2:], ['/existing/adb', 'help'])
        self.assertTrue(Path(argv[argv.index('-o') + 1]).is_relative_to(self.run_path))

    def test_private_adb_probe_never_uses_the_host_default_socket(self):
        class Connection:
            data = b'OKAY00040029'
            sent = None
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def recv(self, count):
                block, self.data = self.data[:min(count, 2)], self.data[min(count, 2):]
                return block
            def sendall(self, data): self.sent = data
        connection = Connection()
        with patch.object(preflight.socket, 'create_connection', return_value=connection) as connect:
            self.assertEqual(preflight.private_adb_version(), '0029')
        connect.assert_called_once_with(('127.0.0.1', 5038), timeout=2)
        self.assertEqual(connection.sent, b'000chost:version')

    def test_private_adb_response_is_bounded_and_validated(self):
        class Connection:
            def __init__(self, data): self.data = data
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def recv(self, count):
                block, self.data = self.data[:count], self.data[count:]
                return block
            def sendall(self, data): pass
        for data in (b'', b'FAIL', b'OKAYzzzz', b'OKAYffff', b'OKAY0000', b'OKAY0004', b'OKAY0004oops'):
            with self.subTest(response=data[:8]):
                with patch.object(preflight.socket, 'create_connection', return_value=Connection(data)):
                    with self.assertRaises(preflight.PreflightError):
                        preflight.private_adb_version()

    def test_process_identity_rejects_reused_pid_or_owner(self):
        original = {'pid': 123, 'startTicks': 45, 'uid': 6}
        self.assertTrue(preflight.same_process(dict(original), original))
        self.assertFalse(preflight.same_process(None, original))
        for name in original:
            self.assertFalse(preflight.same_process(dict(original, **{name: original[name] + 1}), original))

    def fixture_process(self, pid, parent=1, start=10, uid=6, network='net:[123]'):
        return {'pid': pid, 'parentPid': parent, 'startTicks': start, 'uid': uid,
                'state': 'S', 'networkNamespace': network}

    def test_parent_table_discovers_multiple_generations_without_optional_children_file(self):
        values = {100: self.fixture_process(100),
                  101: self.fixture_process(101, parent=100, start=11),
                  102: self.fixture_process(102, parent=101, start=12),
                  103: self.fixture_process(103, parent=2, start=13)}
        class Process: pid = 100
        with patch.object(preflight, 'proc_identity', side_effect=values.get), \
                patch.object(preflight, 'process_table', return_value=values):
            tree = preflight.OwnedTree(Process())
            self.assertEqual(set(tree.identities), {100, 101, 102})

    def test_table_entry_alone_is_not_validated_descendant_ownership(self):
        parent = self.fixture_process(100)
        candidate = self.fixture_process(101, parent=100, start=11)
        class Process: pid = 100
        for child in (dict(candidate, parentPid=99), dict(candidate, startTicks=12),
                      dict(candidate, uid=7), dict(candidate, startTicks=9)):
            with self.subTest(child=child):
                # A candidate must match the scan AND a fresh identity; an
                # actual pre-parent start/other UID is rejected independently.
                scan = {100: parent, 101: dict(candidate, startTicks=child['startTicks'], uid=child['uid'])}
                if child['startTicks'] == 12:
                    scan[101] = candidate
                with patch.object(preflight, 'proc_identity', side_effect={100: parent, 101: child}.get), \
                        patch.object(preflight, 'process_table', return_value=scan):
                    self.assertEqual(set(preflight.OwnedTree(Process()).identities), {100})

    def test_parent_pid_reuse_between_scan_and_child_validation_is_rejected(self):
        parent = self.fixture_process(100)
        child = self.fixture_process(101, parent=100, start=11)
        seen_child = False
        def read(pid):
            nonlocal seen_child
            if pid == 101:
                seen_child = True
                return child
            return dict(parent, startTicks=12) if seen_child else parent
        class Process: pid = 100
        with patch.object(preflight, 'proc_identity', side_effect=read), \
                patch.object(preflight, 'process_table', return_value={100: parent, 101: child}):
            self.assertEqual(set(preflight.OwnedTree(Process()).identities), {100})

    def test_signal_uses_validated_pidfd_not_reusable_pid_or_group(self):
        original = self.fixture_process(100)
        with patch.object(preflight.os, 'pidfd_open', return_value=42) as open_fd, \
                patch.object(preflight.os, 'close') as close_fd, \
                patch.object(preflight, 'proc_identity', return_value=original), \
                patch.object(preflight.signal, 'pidfd_send_signal') as send, \
                patch.object(preflight.os, 'kill', side_effect=AssertionError('No numeric-PID signals')):
            preflight.signal_owned(original, preflight.signal.SIGTERM)
        open_fd.assert_called_once_with(100)
        send.assert_called_once_with(42, preflight.signal.SIGTERM)
        close_fd.assert_called_once_with(42)

    def test_signal_revalidates_pid_start_owner_and_namespace_after_pidfd_open(self):
        original = self.fixture_process(100)
        for current in (None, dict(original, startTicks=11), dict(original, uid=7),
                        dict(original, networkNamespace='net:[999]'), dict(original, state='Z')):
            with self.subTest(current=current):
                with patch.object(preflight.os, 'pidfd_open', return_value=42), \
                        patch.object(preflight.os, 'close') as close_fd, \
                        patch.object(preflight, 'proc_identity', return_value=current), \
                        patch.object(preflight.signal, 'pidfd_send_signal') as send:
                    preflight.signal_owned(original, preflight.signal.SIGTERM)
                send.assert_not_called()
                close_fd.assert_called_once_with(42)

    def test_live_owned_process_with_unreadable_namespace_does_not_pass_teardown(self):
        original = self.fixture_process(100)
        class Process: pid = 100
        with patch.object(preflight, 'proc_identity', return_value=original), \
                patch.object(preflight, 'process_table', return_value={100: original}):
            tree = preflight.OwnedTree(Process())
        with patch.object(preflight, 'proc_identity', return_value=None), \
                patch.object(preflight, 'proc_stat', return_value=original), \
                patch.object(preflight, 'process_table', return_value={100: original}):
            with self.assertRaises(preflight.PreflightError):
                tree.live()

    def test_actual_authored_process_tree_teardown_does_not_signal_unrelated_child(self):
        # Python-only authored sleepers, not Android/native services or a namespace.
        environment = dict(os.environ, TMPDIR=str(self.run_path / 'tmp'), HOME=str(self.run_path / 'home'))
        unrelated = subprocess.Popen([sys.executable, '-B', '-c', 'import time; time.sleep(30)'],
                                     cwd=self.run_path, env=environment)
        unrelated_tree = preflight.OwnedTree(unrelated)
        code = ('import subprocess,sys,time; '
                'child=subprocess.Popen([sys.executable,"-B","-c","import time; time.sleep(30)"]); '
                'print(child.pid,flush=True); time.sleep(30)')
        process = subprocess.Popen([sys.executable, '-B', '-c', code], cwd=self.run_path, env=environment,
                                   stdout=subprocess.PIPE, text=True)
        tree = preflight.OwnedTree(process)
        try:
            child = int(process.stdout.readline())
            tree.refresh()
            self.assertIn(child, tree.identities)
            tree.stop()
            self.assertEqual(tree.live(), [])
            self.assertIsNone(unrelated.poll())
        finally:
            tree.stop()
            unrelated_tree.stop()
            process.stdout.close()

    def test_bounded_inputs_reject_directories_and_oversize(self):
        with self.assertRaises(preflight.PreflightError):
            preflight.bounded(self.run_path)
        file = self.run_path / 'fixture.txt'
        file.write_bytes(b'12345')
        with self.assertRaises(preflight.PreflightError):
            preflight.bounded(file, 4)
        self.assertEqual(preflight.bounded(file, 5), b'12345')

    def test_guest_run_gate_does_not_launch_any_service(self):
        result = subprocess.run(['bash', str(ROOT / 'scripts/android-instrumentation.sh'), '--run'],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 78)
        self.assertIn('still gated', result.stderr)

    def service_mask_validator(self, name='service_mask'):
        # Compile ONLY the pure pre-mount validation function from the guard's
        # embedded Python. No mount/subprocess statement enters this harness.
        text = (ROOT / 'scripts/linux-namespace.sh').read_text()
        body = text.split("python3 -B - <<'PY'\n", 1)[1].split('\nPY', 1)[0]
        tree = ast.parse(body)
        node = next(item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name == name)
        namespace = {'Path': Path, 'os': os, 're': re}
        exec(compile(ast.Module(body=[node], type_ignores=[]), '<authored-service-mask-validation>', 'exec'), namespace)
        return namespace[name]

    def test_no_mask_effective_mount_check_matches_the_original_guard(self):
        check = self.service_mask_validator('repository_backed_mount')
        backing = {Path('/tmp'): self.root / '.work/tmp',
                   Path('/var/lib/xkb'): self.root / '.work/cache/xkb',
                   Path('/dev/shm'): self.root / '.work/shm'}
        for target in (self.root, self.root / 'child', Path('/'), Path('/run'), Path('/run/service'),
                       Path('/tmp'), Path('/tmp/child'), Path('/dev/shm/child'), Path('/var/lib/xkb/child')):
            with self.subTest(target=target):
                self.assertEqual(check(target, self.root, backing, None),
                                 target.is_relative_to(self.root) or target in backing)

    def test_only_explicit_run_mask_can_skip_hidden_run_descendant_mounts(self):
        check = self.service_mask_validator('repository_backed_mount')
        mask = self.run_path / 'service-runtime'
        backing = {Path('/run'): mask, Path('/tmp'): self.root / '.work/tmp'}
        self.assertTrue(check(Path('/run/hidden-service-mount'), self.root, backing, mask))
        for target in (Path('/tmp/child'), Path('/dev/shm/child'), Path('/runtime/child'), Path('/var/lib/xkb/child')):
            with self.subTest(target=target):
                self.assertFalse(check(target, self.root, backing, mask))

    def test_native_guard_rejects_writable_or_nonempty_service_aliases(self):
        origin = {'networkNamespace': 'net:[1]', 'mountNamespace': 'mnt:[1]'}
        reference = self.parent_reference(self.run_path)
        def read_namespace(kind, pid='self'):
            return 'net:[2]' if kind == 'net' else ('mnt:[2]' if pid == 'self' else 'mnt:[1]')
        status = 'NoNewPrivs:\t1\nCapEff:\t00000000\nCapBnd:\t00000000\n'
        for bad_path, nonempty in ((None, False), ('/run', False), ('/var/run', False),
                                   ('/run', True), ('/var/run', True)):
            with self.subTest(path=bad_path, nonempty=nonempty):
                def statvfs(path):
                    return SimpleNamespace(f_flag=0 if str(path) == bad_path and not nonempty else os.ST_RDONLY)
                def iterdir(path):
                    return iter([Path('/authored-unexpected')] if str(path) == bad_path and nonempty else [])
                with patch.object(preflight, 'namespace', side_effect=read_namespace), \
                        patch.object(preflight, 'assert_bound_parent'), \
                        patch.object(preflight, 'self_mount_identity', return_value={
                            'namespace': 'mnt:[2]', 'dev': 1, 'ino': 2, 'nstype': preflight.CLONE_NEWNS}), \
                        patch.object(preflight, 'mount_descriptor_identity', return_value={
                            'dev': 1, 'ino': 1, 'nstype': preflight.CLONE_NEWNS}), \
                        patch.object(preflight.os, 'statvfs', side_effect=statvfs), \
                        patch.object(Path, 'iterdir', iterdir), \
                        patch.object(Path, 'stat', return_value=SimpleNamespace(st_dev=1, st_ino=2)), \
                        patch.object(Path, 'read_text', return_value=status), \
                        patch.object(preflight, 'json_write'):
                    if bad_path is None:
                        preflight.assert_guard(self.run_path, origin, 'net:[2]', reference)
                    else:
                        with self.assertRaises(preflight.PreflightError):
                            preflight.assert_guard(self.run_path, origin, 'net:[2]', reference)

    def test_optional_service_mask_is_disabled_for_normal_display_callers(self):
        self.assertIsNone(self.service_mask_validator()(self.root, ''))

    def test_optional_service_mask_accepts_only_exact_empty_owned_run_directory(self):
        validator = self.service_mask_validator()
        source = self.run_path / 'service-runtime'
        self.assertEqual(validator(self.root, str(source)), source)
        for path in (self.run_path, self.root / 'service-runtime', self.run_path / 'runtime'):
            with self.subTest(path=path.name):
                with self.assertRaises(SystemExit):
                    validator(self.root, str(path))
        (source / 'unexpected').write_bytes(b'fixture')
        with self.assertRaises(SystemExit):
            validator(self.root, str(source))

    def test_optional_service_mask_rejects_symlinks_and_public_permissions(self):
        validator = self.service_mask_validator()
        source = self.run_path / 'service-runtime'
        source.chmod(0o755)
        with self.assertRaises(SystemExit):
            validator(self.root, str(source))
        source.chmod(0o700)
        source.rmdir()
        source.symlink_to(self.run_path / 'runtime', target_is_directory=True)
        with self.assertRaises(SystemExit):
            validator(self.root, str(source))

    def test_optional_service_mask_rejects_nonaliased_var_run(self):
        validator = self.service_mask_validator()
        original = Path.resolve
        def resolve(path, *args, **kwargs):
            return Path('/a-separate-service-path') if path == Path('/var/run') else original(path, *args, **kwargs)
        with patch.object(Path, 'resolve', resolve):
            with self.assertRaises(SystemExit):
                validator(self.root, str(self.run_path / 'service-runtime'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
