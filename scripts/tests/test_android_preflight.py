#!/usr/bin/env python3
"""Authored Stage 0 regressions; NEVER launch adb, emulator, unshare or mounts."""
from __future__ import annotations

import ast
from contextlib import contextmanager
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

    @contextmanager
    def mocked_guard_entry(self, fail_at=None, refusal=None, unsafe=None):
        # No real namespace, mount, capability, listener or process operation enters
        # this fixture. Owned temporary JSON is the only filesystem work performed.
        run = preflight.prepare_run(self.root)
        preflight.json_write(run / 'origin.json', {'host': {
            'networkNamespace': 'net:[1]', 'mountNamespace': 'mnt:[1]',
        }, 'inputs': {'tools': {'adb': '/authored/adb', 'emulator': '/authored/emulator'}}})
        preflight.json_write(run / 'network.json', {'networkNamespace': 'net:[3]'})
        events = []
        refusal = refusal or PermissionError(errno.EACCES, 'authored-private-message', '/authored/private-key')
        def observed(phase):
            events.append(phase)
            if phase == fail_at:
                raise refusal
        def identity(kind, pid='self'):
            if kind == 'net':
                self.assertEqual(pid, 'self')
                observed('guard-self-network')
                return 'net:[1]' if unsafe == 'caller-network' else 'net:[3]'
            self.assertEqual(kind, 'mnt')
            if pid == 'self':
                observed('guard-self-mount')
                return {'caller-mount': 'mnt:[1]', 'parent-mount': 'mnt:[2]'}.get(unsafe, 'mnt:[3]')
            self.assertEqual(pid, 12345)
            observed('guard-parent-mount')
            return 'mnt:[2]'
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
        with patch.object(preflight, 'validate_run', return_value=run), \
                patch.object(preflight, 'bounded', side_effect=bounded), \
                patch.object(preflight, 'namespace', side_effect=identity), \
                patch.object(preflight.os, 'getppid', return_value=12345), \
                patch.object(preflight.os, 'statvfs', side_effect=statvfs), \
                patch.object(Path, 'stat', stat_path), patch.object(Path, 'iterdir', iterdir), \
                patch.object(Path, 'read_text', read_text), \
                patch.object(preflight, 'native_environment', side_effect=environment), \
                patch.object(preflight, 'listener_inodes', side_effect=listeners), \
                patch.object(preflight, 'probe', side_effect=ReachedMockProbe) as probe, \
                patch.object(preflight.subprocess, 'Popen', side_effect=AssertionError('No native process')), \
                patch.object(preflight.subprocess, 'run', side_effect=AssertionError('No native command')), \
                patch.object(preflight.subprocess, 'check_output', side_effect=AssertionError('No native command')), \
                patch.object(preflight.os, 'execv', side_effect=AssertionError('No exec')), \
                patch.object(preflight.socket, 'create_connection', side_effect=AssertionError('No connection')):
            yield SimpleNamespace(run=run, events=events, probe=probe, refusal=refusal)

    def test_inside_guard_permission_receipts_distinguish_each_preprobe_phase(self):
        phases = preflight.FAILURE_PHASES - {'mount-guard-exec', 'guard-mount-separation'}
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
            self.assertEqual(set(mocked.events), preflight.FAILURE_PHASES - {'mount-guard-exec', 'guard-mount-separation'})

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
                        patch.object(preflight.os, 'statvfs', side_effect=statvfs), \
                        patch.object(Path, 'iterdir', iterdir), \
                        patch.object(Path, 'stat', return_value=SimpleNamespace(st_dev=1, st_ino=2)), \
                        patch.object(Path, 'read_text', return_value=status), \
                        patch.object(preflight, 'json_write'):
                    if bad_path is None:
                        preflight.assert_guard(self.run_path, origin, 'net:[2]')
                    else:
                        with self.assertRaises(preflight.PreflightError):
                            preflight.assert_guard(self.run_path, origin, 'net:[2]')

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
