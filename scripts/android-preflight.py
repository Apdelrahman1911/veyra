#!/usr/bin/env python3
"""Linux Android Stage 0: owned, traced help/private-ADB probes; NEVER boot a guest.

Run only after lead review/authorization. No SDK installation, host ADB query,
default-port client, shared-service reuse, global kill, or native-gate bypass.
A completed probe still requires manual trace review before any guest proposal.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
try:
    import resource
except ImportError:  # Windows can inspect the CLI/tests; this runner is Linux-only.
    resource = None
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / '.work/android/runs'
PORT = 5038
SERIAL = 'emulator-5580'
MAX_LOG = 16 * 1024 * 1024
MAX_RUN_LOGS = 64 * 1024 * 1024
MAX_NETWORK_REPORT = 64 * 1024
NETWORK_REPORTS = (
    ('links', ('-details', 'link', 'show'), 2),
    ('addresses', ('address', 'show'), 2),
    ('ipv4Routes', ('-4', 'route', 'show', 'table', 'all'), 4),
    ('ipv6Routes', ('-6', 'route', 'show', 'table', 'all'), 1),
)
PROHIBITED_ENV = (
    'ADB_SERVER_SOCKET', 'ANDROID_ADB_SERVER_PORT', 'ANDROID_SERIAL', 'ADB_VENDOR_KEYS',
    'DBUS_SESSION_BUS_ADDRESS', 'DBUS_SYSTEM_BUS_ADDRESS', 'PULSE_SERVER', 'PIPEWIRE_REMOTE',
    'DISPLAY', 'WAYLAND_DISPLAY', 'WAYLAND_SOCKET', 'VEYRA_DISPLAY_AUTHORITY',
)
RUN_DIRECTORIES = (
    'home/.android', 'tmp', 'cache', 'config', 'data', 'state', 'runtime',
    'avd', 'emulator', 'discovery', 'service-runtime', 'logs',
)
FAILURE_PHASES = frozenset((
    'mount-guard-exec', 'guard-inputs', 'guard-self-network', 'guard-self-mount',
    'guard-parent-mount', 'guard-mount-separation', 'guard-protected-mounts',
    'guard-service-aliases', 'guard-scratch-identities', 'guard-capabilities',
    'guard-native-environment', 'guard-listeners',
))


class PreflightError(ValueError):
    pass


def bounded(path: Path, limit: int = 1024 * 1024) -> bytes:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 <= info.st_size <= limit:
        raise PreflightError('A preflight input is not a bounded regular file')
    with path.open('rb') as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise PreflightError('A preflight input exceeded its byte bound')
    return value


def validate_run(value: Path, root: Path = ROOT) -> Path:
    root = root.resolve(strict=True)
    path = Path(os.path.abspath(value))
    if (path.parent != root / '.work/android/runs'
            or not re.fullmatch(r'preflight-[A-Za-z0-9_-]{6,64}', path.name)):
        raise PreflightError('Invalid owned Android preflight directory')
    for ancestor in (path, *path.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise PreflightError('Symlinked Android preflight directory rejected')
    if not path.is_dir() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise PreflightError('Private current-owner Android preflight directory required')
    return path


def prepare_run(root: Path = ROOT) -> Path:
    parent = root / '.work/android/runs'
    for ancestor in (parent, *parent.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise PreflightError('Symlinked Android preflight ancestor rejected')
    parent.mkdir(parents=True, exist_ok=True)
    path = validate_run(Path(tempfile.mkdtemp(prefix='preflight-', dir=parent)), root)
    for name in RUN_DIRECTORIES:
        (path / name).mkdir(parents=True, exist_ok=True, mode=0o700)
    return path


def json_write(path: Path, value: dict) -> None:
    # Every destination is a fresh, fixed-name file in the validated owned run.
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')


@contextmanager
def failure_phase(run: Path, phase: str):
    """Failure-only receipt in an already validated run; never a retry or fallback."""
    if phase not in FAILURE_PHASES:
        raise PreflightError('Unknown fixed preflight diagnostic phase')
    try:
        yield
    except (OSError, ValueError) as error:
        # Do not serialize exception text/args/filename/stack, process environment,
        # or caller-supplied class names. Even diagnostic-write failure must preserve
        # the original refusal; absence of a receipt is not success at this phase.
        known_types = (PreflightError, PermissionError, FileNotFoundError, FileExistsError,
                       NotADirectoryError, IsADirectoryError, ProcessLookupError,
                       InterruptedError, BlockingIOError, TimeoutError, OSError, ValueError)
        category = type(error).__name__ if type(error) in known_types else (
            'OSError' if isinstance(error, OSError) else 'ValueError')
        number = error.errno if isinstance(error, OSError) else None
        if type(number) is not int or not 0 <= number <= 4095:
            number = None
        try:
            json_write(run / 'failure-phase.json', {
                'schemaVersion': 1, 'phase': phase, 'errorClass': category, 'errno': number,
            })
        except (OSError, ValueError):
            print('Stage 0 failure-phase receipt could not be retained.', file=sys.stderr)
        raise


def namespace(kind: str, pid: int | str = 'self') -> str:
    return os.readlink(f'/proc/{pid}/ns/{kind}')


def assert_new_namespace(current: str, original: str, parent: str) -> None:
    if not re.fullmatch(r'net:\[[0-9]+\]', current) or current in (original, parent):
        raise PreflightError('Refusing a network operation in the caller/parent namespace')


def limit_network_report() -> None:
    # Applied only to an ip report child, not the caller or its other logs.
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_NETWORK_REPORT, MAX_NETWORK_REPORT))


def network_snapshot(ip: str, run: Path, phase: str) -> dict:
    """Read BOTH route families/all tables; retain bounded, own-netns evidence."""
    if phase not in ('before', 'after'):
        raise PreflightError('Invalid private network snapshot phase')

    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise PreflightError('Duplicate field in private network report')
            result[key] = value
        return result

    def invalid_constant(value):
        raise PreflightError('Non-JSON number in private network report')

    result = {}
    for name, arguments, _ in NETWORK_REPORTS:
        path = run / 'logs' / f'network-{phase}-{name}.json'
        # A regular-file stdout plus child-only FSIZE bound avoids unbounded
        # check_output buffering. No ip mutation is performed by these reads.
        with path.open('xb') as output:
            subprocess.run([ip, '-json', *arguments], stdout=output, check=True,
                           timeout=5, preexec_fn=limit_network_report)
        try:
            result[name] = json.loads(bounded(path, MAX_NETWORK_REPORT),
                                      object_pairs_hook=unique_fields, parse_constant=invalid_constant)
        except (ValueError, RecursionError) as error:
            raise PreflightError('Malformed or oversized private network report') from error
    return result


def expect_network_fields(value: dict, required: dict, optional: dict | None = None) -> None:
    """Reject missing identity/state fields and every unreviewed configuration key."""
    allowed = dict(required, **(optional or {}))
    if (not isinstance(value, dict) or not required.keys() <= value.keys()
            or not value.keys() <= allowed.keys()):
        raise PreflightError('Missing or unreviewed private network metadata')
    # JSON comparison also distinguishes false/0 and true/1 inside tunnel data.
    for key, item in value.items():
        if (json.dumps(item, sort_keys=True, allow_nan=False)
                != json.dumps(allowed[key], sort_keys=True, allow_nan=False)):
            raise PreflightError('Unexpected private network identity or state')


def validate_network_state(state: dict, loopback_up: bool) -> dict[str, int]:
    """Only lo may be active; optional sit0 must be the reviewed inert kernel link.

    This pure shape check is NOT namespace-origin proof. inside_network must
    independently establish and retain a new namespace before invoking ip.
    Unknown metadata/configuration fails closed instead of being ignored.
    """
    if not isinstance(state, dict) or state.keys() != {name for name, _, _ in NETWORK_REPORTS}:
        raise PreflightError('Incomplete private network snapshot')
    for name, _, maximum in NETWORK_REPORTS:
        if not isinstance(state[name], list) or len(state[name]) > maximum:
            raise PreflightError('Invalid or oversized private network list')
    links = state['links']
    addresses = state['addresses']
    names = [item.get('ifname') if isinstance(item, dict) else None for item in links]
    if names not in (['lo'], ['lo', 'sit0']):
        raise PreflightError('Unexpected, missing or duplicate private network interface')
    if [item.get('ifname') if isinstance(item, dict) else None for item in addresses] != names:
        raise PreflightError('Private link and address reports disagree')

    families = set()
    for link, address in zip(links, addresses):
        sit = link['ifname'] == 'sit0'
        active = loopback_up and not sit
        common = {
            'ifindex': 2 if sit else 1, 'ifname': 'sit0' if sit else 'lo',
            'flags': ['NOARP'] if sit else (['LOOPBACK', 'UP', 'LOWER_UP'] if active else ['LOOPBACK']),
            'operstate': 'UNKNOWN' if active else 'DOWN',
            'mtu': 1480 if sit else 65536, 'qdisc': 'noqueue' if active else 'noop',
            'link_type': 'sit' if sit else 'loopback',
            'address': '0.0.0.0' if sit else '00:00:00:00:00:00',
            'broadcast': '0.0.0.0' if sit else '00:00:00:00:00:00',
        }
        if sit:
            common['link'] = None
        details = dict(common, promiscuity=0, allmulti=0)
        if sit:
            details['linkinfo'] = {'info_kind': 'sit', 'info_data': {
                'proto': 'ip6ip', 'remote': 'any', 'local': 'any', 'ttl': 64, 'pmtudisc': False,
            }}
        passive = {'group': 'default', 'txqlen': 1000}
        expect_network_fields(link, details, dict(passive,
            linkmode='DEFAULT', min_mtu=1280 if sit else 0, max_mtu=65555 if sit else 0,
            inet6_addr_gen_mode='eui64', num_tx_queues=1, num_rx_queues=1,
            gso_max_size=65536, gso_max_segs=65535, tso_max_size=65536 if sit else 524280,
            tso_max_segs=65535, gro_max_size=65536))
        info = address.get('addr_info')
        if not isinstance(info, list) or len(info) > 2 or (not active and info):
            raise PreflightError('Unexpected addresses on an inactive private interface')
        expect_network_fields(address, dict(common, addr_info=info), passive)
        for item in info:
            family = item.get('family') if isinstance(item, dict) else None
            if family not in ('inet', 'inet6') or family in families:
                raise PreflightError('Unexpected or duplicate private loopback address family')
            families.add(family)
            expected = {'family': family, 'local': '127.0.0.1' if family == 'inet' else '::1',
                        'prefixlen': 8 if family == 'inet' else 128, 'scope': 'host',
                        'valid_life_time': 4294967295, 'preferred_life_time': 4294967295}
            if family == 'inet':
                expected['label'] = 'lo'
            expect_network_fields(item, expected)
    if loopback_up and 'inet' not in families:
        raise PreflightError('Private IPv4 loopback did not become ready')

    for name, family in (('ipv4Routes', 'inet'), ('ipv6Routes', 'inet6')):
        routes = state[name]
        if not loopback_up or family not in families:
            if routes:
                raise PreflightError('Unexpected route before private loopback activation')
            continue
        destinations = set()
        for route in routes:
            if not isinstance(route, dict):
                raise PreflightError('Malformed private loopback route')
            destination = route.get('dst')
            local = ('127.0.0.0/8', '127.0.0.1') if family == 'inet' else ('::1',)
            broadcast = ('127.0.0.0', '127.255.255.255') if family == 'inet' else ()
            if destination not in (*local, *broadcast) or destination in destinations:
                raise PreflightError('Unexpected or duplicate private loopback route')
            destinations.add(destination)
            kind = 'local' if destination in local else 'broadcast'
            expected = {'type': kind, 'dst': destination, 'dev': 'lo',
                        'protocol': 'kernel', 'flags': []}
            if family == 'inet':
                expected.update(table='local', scope='host' if kind == 'local' else 'link',
                                prefsrc='127.0.0.1')
                optional = {}
            else:
                expected.update(metric=0, pref='medium')
                # Non-details iproute2 omits the default-main table, including
                # local ::1 on single-table IPv6 kernels. Do not infer/insert
                # table=local: only this exact route may omit the table field.
                optional = {'scope': 'host', 'table': 'local'}
            expect_network_fields(route, expected, optional)
        required = {'127.0.0.0/8', '127.0.0.1'} if family == 'inet' else {'::1'}
        if not required <= destinations:
            raise PreflightError('Required private loopback kernel routes are missing')
    return {item['ifname']: item['ifindex'] for item in links}


def proc_stat(pid: int) -> dict | None:
    """Read only bounded identity fields, never command lines or environments."""
    try:
        base = Path('/proc') / str(pid)
        # comm is parenthesized and may contain spaces/closing parentheses. Do
        # not decode or retain it; the fixed fields follow the final ')'.
        fields = bounded(base / 'stat', 65536).rsplit(b')', 1)[1].split()
        return {'pid': pid, 'startTicks': int(fields[19]), 'state': fields[0].decode('ascii'),
                'parentPid': int(fields[1]), 'uid': base.stat().st_uid}
    except (OSError, ValueError, IndexError):
        return None


def proc_identity(pid: int) -> dict | None:
    before = proc_stat(pid)
    if before is None:
        return None
    try:
        network = namespace('net', pid)
    except OSError:
        return None
    after = proc_stat(pid)
    if not same_process(after, before) or after['parentPid'] != before['parentPid']:
        return None
    return dict(after, networkNamespace=network)


def same_process(left: dict | None, right: dict) -> bool:
    return left is not None and all(left[key] == right[key] for key in ('pid', 'startTicks', 'uid'))


def process_table() -> dict[int, dict]:
    # Some Linux kernels omit /proc/<pid>/task/<tid>/children entirely. Scan
    # parent IDs instead of silently losing every descendant on those hosts.
    # This is read-only identity discovery, not a process-name/global kill.
    values = {}
    for path in Path('/proc').iterdir():
        if path.name.isdigit():
            value = proc_stat(int(path.name))
            if value is not None:
                values[value['pid']] = value
    return values


def signal_owned(old: dict, sig: int) -> None:
    """Signal a revalidated pidfd, never a reusable numeric PID or process group."""
    try:
        descriptor = os.pidfd_open(old['pid'])
    except ProcessLookupError:
        return
    try:
        current = proc_identity(old['pid'])
        if (same_process(current, old) and current['state'] != 'Z'
                and current['networkNamespace'] == old['networkNamespace']):
            # If this process exits/reaps after validation, the descriptor
            # remains attached to that process, not a replacement using its PID.
            signal.pidfd_send_signal(descriptor, sig)
    except ProcessLookupError:
        pass
    finally:
        os.close(descriptor)


def host_adb_identity() -> list[dict]:
    """Read process identity only; no connection, command, signal or host client."""
    identities = []
    for path in Path('/proc').iterdir():
        if not path.name.isdigit():
            continue
        try:
            if (path / 'comm').read_text().strip() != 'adb':
                continue
        except OSError:
            continue
        value = proc_identity(int(path.name))
        if value is not None:
            value.pop('state')
            identities.append(value)
    return sorted(identities, key=lambda item: item['pid'])


def snapshot() -> dict:
    return {'networkNamespace': namespace('net'), 'mountNamespace': namespace('mnt'),
            'mountinfoSha256': hashlib.sha256(Path('/proc/self/mountinfo').read_bytes()).hexdigest(),
            'hostAdbProcesses': host_adb_identity()}


def validate_host_environment(environment: dict[str, str], core_pattern: str,
                              usb_present: bool, pcm_present: bool) -> None:
    if any(environment.get(key) for key in PROHIBITED_ENV):
        raise PreflightError('Inherited host display/audio/service or ADB configuration must not be reused')
    if core_pattern.strip() != 'core':
        raise PreflightError('Host coredump handler requires separate review; no system change attempted')
    if usb_present or pcm_present:
        raise PreflightError('Host USB/PCM devices require a separate access-mask review')


def tool_inputs(environment: dict[str, str]) -> dict:
    sdk_value = environment.get('ANDROID_HOME') or environment.get('ANDROID_SDK_ROOT')
    if not sdk_value:
        raise PreflightError('An existing read-only Android SDK is required')
    sdk = Path(sdk_value).resolve(strict=True)
    other = environment.get('ANDROID_SDK_ROOT')
    if other and Path(other).resolve(strict=True) != sdk:
        raise PreflightError('Android SDK variables disagree')
    image = sdk / 'system-images/android-31/default/x86_64'
    for name in ('kernel-ranchu', 'ramdisk.img', 'system.img', 'vendor.img', 'userdata.img', 'encryptionkey.img'):
        path = image / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size <= 0:
            raise PreflightError('Required existing API31 AOSP x86_64 image component missing')
    versions = {}
    for name, directory, expected in (('adb', sdk / 'platform-tools', '37.0.1'),
                                       ('emulator', sdk / 'emulator', '37.1.11'),
                                       ('image', image, '3')):
        text = bounded(directory / 'source.properties', 65536).decode('utf-8')
        match = re.search(r'^Pkg.Revision\s*=\s*(\S+)\s*$', text, re.M)
        if not match or match[1] != expected:
            raise PreflightError('Existing Android tool/image revision needs a new review')
        versions[name] = expected
    paths = {'adb': sdk / 'platform-tools/adb', 'emulator': sdk / 'emulator/emulator'}
    for name in ('unshare', 'ip', 'strace'):
        found = shutil.which(name)
        if found is None:
            raise PreflightError('Existing namespace/tracing tools required; no installation/fallback')
        paths[name] = Path(found).resolve(strict=True)
    for path in paths.values():
        if not path.is_file() or not os.access(path, os.X_OK):
            raise PreflightError('Existing executable preflight tool required')
    return {'sdk': str(sdk), 'image': str(image), 'versions': versions,
            'tools': {name: str(path) for name, path in paths.items()}}


def native_environment(base: dict[str, str], run: Path) -> dict[str, str]:
    environment = dict(base)
    for key in (*PROHIBITED_ENV, 'XAUTHORITY', 'SSH_AUTH_SOCK', 'GPG_AGENT_INFO',
                'LD_PRELOAD', 'LD_LIBRARY_PATH', 'QT_PLUGIN_PATH', 'QML2_IMPORT_PATH'):
        environment.pop(key, None)
    overrides = {
        'HOME': run / 'home', 'USERPROFILE': run / 'home', 'CFFIXED_USER_HOME': run / 'home',
        'TMPDIR': run / 'tmp', 'TMP': run / 'tmp', 'TEMP': run / 'tmp',
        'XDG_CACHE_HOME': run / 'cache', 'XDG_CONFIG_HOME': run / 'config',
        'XDG_DATA_HOME': run / 'data', 'XDG_STATE_HOME': run / 'state',
        'XDG_RUNTIME_DIR': run / 'runtime', 'ANDROID_USER_HOME': run / 'home/.android',
        'ANDROID_AVD_HOME': run / 'avd', 'ANDROID_EMULATOR_HOME': run / 'emulator',
        'ANDROID_EMULATOR_DISCOVERY_DIR': run / 'discovery', 'ADB_VENDOR_KEYS': run / 'home/.android',
    }
    environment.update({key: str(value) for key, value in overrides.items()})
    environment.update(ADB_SERVER_SOCKET=f'tcp:127.0.0.1:{PORT}', ANDROID_ADB_SERVER_PORT=str(PORT),
                       ANDROID_SERIAL=SERIAL, ADB_MDNS='0', ADB_MDNS_AUTO_CONNECT='', ADB_LIBUSB='0',
                       QT_QPA_PLATFORM='offscreen', LC_ALL='C', LANG='C')
    return environment


def limit_probe() -> None:
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_LOG, MAX_LOG))
    resource.setrlimit(resource.RLIMIT_CPU, (30, 35))
    resource.setrlimit(resource.RLIMIT_AS, (1536 * 1024 ** 2, 1536 * 1024 ** 2))
    if hasattr(os, 'sched_getaffinity'):
        os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:2])
    if os.getpriority(os.PRIO_PROCESS, 0) < 10:
        os.setpriority(os.PRIO_PROCESS, 0, 10)


class OwnedTree:
    """Track exact descendant start identities; no process-name/global signals."""
    def __init__(self, process: subprocess.Popen):
        self.process = process
        self.identities: dict[int, dict] = {}
        value = proc_identity(process.pid)
        if value is not None:
            self.identities[process.pid] = value
        self.refresh()

    def refresh(self) -> None:
        table = process_table()
        children = {}
        for value in table.values():
            children.setdefault(value['parentPid'], []).append(value)
        pending = list(self.identities.values())
        visited = set()
        while pending:
            old = pending.pop()
            pid = old['pid']
            if pid in visited:
                continue
            visited.add(pid)
            value = proc_identity(pid)
            if not same_process(value, old):
                continue
            # Namespace transitions are intentional only in the outer owned
            # unshare/mount launcher. Every observed process still has the same
            # tracked PID/start/UID, never a reused or unrelated PID.
            self.identities[pid] = value
            for candidate in children.get(pid, ()):
                existing = self.identities.get(candidate['pid'])
                if existing is not None and not same_process(candidate, existing):
                    continue
                child = proc_identity(candidate['pid'])
                parent = proc_identity(pid)
                # The table alone is not ancestry proof: a parent can exit and
                # its PID be reused while /proc is scanned. Revalidate BOTH
                # exact identities, owner, chronology and the current lineage.
                if (same_process(child, candidate) and same_process(parent, value)
                        and child['parentPid'] == pid and child['uid'] == value['uid']
                        and child['startTicks'] >= value['startTicks']):
                    self.identities[child['pid']] = child
                    pending.append(child)

    def live(self) -> list[dict]:
        self.refresh()
        result = []
        for pid, old in self.identities.items():
            current = proc_identity(pid)
            if current is None:
                basic = proc_stat(pid)
                if same_process(basic, old) and basic['state'] != 'Z':
                    raise PreflightError('Cannot verify a still-live owned process namespace')
            elif same_process(current, old) and current['state'] != 'Z':
                result.append(current)
        return result

    def stop(self) -> None:
        for sig, duration in ((signal.SIGTERM, 2.0), (signal.SIGKILL, 2.0)):
            self.refresh()
            for old in reversed(list(self.identities.values())):
                signal_owned(old, sig)
            deadline = time.monotonic() + duration
            while self.live() and time.monotonic() < deadline:
                time.sleep(0.05)
            if not self.live():
                break
        self.process.wait(timeout=2)
        if self.live():
            raise PreflightError('Owned preflight process teardown did not complete')


def watch(tree: OwnedTree, run: Path, seconds: float, ready=None) -> int | None:
    deadline = time.monotonic() + seconds
    while True:
        tree.refresh()
        total = sum(path.stat().st_size for path in (run / 'logs').iterdir() if path.is_file())
        if total > MAX_RUN_LOGS:
            raise PreflightError('Preflight trace/log budget exceeded')
        code = tree.process.poll()
        if code is not None:
            return code
        if ready and ready(tree):
            return None
        if time.monotonic() >= deadline:
            raise PreflightError('Preflight command exceeded its bounded deadline')
        time.sleep(0.05)


def traced_command(tools: dict, run: Path, name: str, arguments: list[str]) -> list[str]:
    # No read/write/send/receive buffers are traced: keys and payloads are not
    # included. File paths plus connection/bind destinations need manual review.
    # PTRACE_O_EXITKILL also covers descendants that double-fork/reparent before
    # a /proc scan can observe their lineage. A dying tracer must not detach and
    # leave an untracked native helper behind. An unsupported flag fails closed.
    # With -o and a new command, strace otherwise defaults to ignoring TERM.
    # Explicit interruptibility permits graceful owned-tracer cancellation;
    # the bounded pidfd KILL escalation remains available for a stuck tracer.
    return [tools['strace'], '-f', '--kill-on-exit', '--interruptible=2', '-qq', '-s', '4096', '-y', '-e',
            'trace=%file,connect,bind,listen,getsockname,getpeername',
            '-o', str(run / 'logs' / (name + '.trace')), '--', *arguments]


def probe(tools: dict, run: Path, environment: dict, name: str, arguments: list[str],
          allowed: tuple[int, ...] = (0,)) -> dict:
    with (run / 'logs' / (name + '.log')).open('xb') as log:
        process = subprocess.Popen(traced_command(tools, run, name, arguments), cwd=run,
                                   env=environment, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, preexec_fn=limit_probe)
        tree = OwnedTree(process)
        try:
            code = watch(tree, run, 20)
            if code not in allowed:
                raise PreflightError('A guarded preflight tool failed; inspect only the owned logs')
            return {'name': name, 'exitCode': code}
        finally:
            tree.stop()


def listener_inodes(port: int) -> list[tuple[str, str]]:
    entries = []
    for filename in ('tcp', 'tcp6'):
        for line in Path('/proc/net', filename).read_text().splitlines()[1:]:
            fields = line.split()
            address, number = fields[1].split(':')
            if int(number, 16) == port and fields[3] == '0A':
                entries.append((address, fields[9]))
    return entries


def owns_listener(tree: OwnedTree) -> bool:
    entries = listener_inodes(PORT)
    if not entries:
        return False
    if len(entries) != 1 or entries[0][0] != '0100007F':
        raise PreflightError('Private ADB did not bind exclusively to literal IPv4 loopback')
    target = 'socket:[' + entries[0][1] + ']'
    for value in tree.live():
        if value['networkNamespace'] != namespace('net'):
            raise PreflightError('A native preflight child left its network namespace')
        try:
            if any(os.readlink(path) == target for path in Path(f"/proc/{value['pid']}/fd").iterdir()):
                return True
        except OSError:
            pass
    return False


def private_adb_version() -> str:
    def exact(connection: socket.socket, count: int) -> bytes:
        result = b''
        while len(result) < count:
            block = connection.recv(count - len(result))
            if not block:
                raise PreflightError('Private ADB response was truncated')
            result += block
        return result
    with socket.create_connection(('127.0.0.1', PORT), timeout=2) as connection:
        connection.sendall(b'000chost:version')
        if exact(connection, 4) != b'OKAY':
            raise PreflightError('Private ADB protocol probe was refused')
        header = exact(connection, 4)
        if not re.fullmatch(rb'[0-9a-fA-F]{4}', header) or not 1 <= int(header, 16) <= 16:
            raise PreflightError('Private ADB response length is invalid')
        version = exact(connection, int(header, 16))
        if not re.fullmatch(rb'[0-9a-fA-F]{1,16}', version):
            raise PreflightError('Private ADB protocol version is invalid')
        return version.decode('ascii')


def assert_guard(run: Path, origin: dict, private_net: str) -> None:
    with failure_phase(run, 'guard-self-network'):
        if namespace('net') != private_net or private_net == origin['networkNamespace']:
            raise PreflightError('The private network namespace was not retained')
    with failure_phase(run, 'guard-self-mount'):
        current_mount = namespace('mnt')
    with failure_phase(run, 'guard-parent-mount'):
        parent_mount = namespace('mnt', os.getppid())
    with failure_phase(run, 'guard-mount-separation'):
        if current_mount in (origin['mountNamespace'], parent_mount):
            raise PreflightError('Private read-only mount namespace required')
    with failure_phase(run, 'guard-protected-mounts'):
        for path in ('/', '/proc', '/sys', '/dev'):
            if not os.statvfs(path).f_flag & os.ST_RDONLY:
                raise PreflightError('A protected host mount is not read-only')
    with failure_phase(run, 'guard-service-aliases'):
        for path in ('/run', '/var/run'):
            if not os.statvfs(path).f_flag & os.ST_RDONLY or any(Path(path).iterdir()):
                raise PreflightError('The isolated service alias must remain empty and read-only')
    with failure_phase(run, 'guard-scratch-identities'):
        for target, backing in (('/tmp', ROOT / '.work/tmp'), ('/var/lib/xkb', ROOT / '.work/cache/xkb'),
                                ('/dev/shm', ROOT / '.work/shm'), ('/run', run / 'service-runtime'),
                                ('/var/run', run / 'service-runtime')):
            left, right = Path(target).stat(), backing.stat()
            if (left.st_dev, left.st_ino) != (right.st_dev, right.st_ino):
                raise PreflightError('A required scratch/service alias is not repository backed')
    with failure_phase(run, 'guard-capabilities'):
        status = Path('/proc/self/status').read_text()
        if (not re.search(r'^NoNewPrivs:\s+1$', status, re.M)
                or any(not re.search(r'^' + name + r':\s+0+$', status, re.M) for name in ('CapEff', 'CapBnd'))):
            raise PreflightError('Preflight requires capability drop and NoNewPrivs')


def inside_guard(run: Path) -> None:
    run = validate_run(run)
    with failure_phase(run, 'guard-inputs'):
        origin = json.loads(bounded(run / 'origin.json'))
        network = json.loads(bounded(run / 'network.json'))
    assert_guard(run, origin['host'], network['networkNamespace'])
    tools = origin['inputs']['tools']
    with failure_phase(run, 'guard-native-environment'):
        environment = native_environment(dict(os.environ), run)
    with failure_phase(run, 'guard-listeners'):
        if listener_inodes(5037) or listener_inodes(PORT):
            raise PreflightError('New private network namespace unexpectedly contains an ADB listener')
    results = [probe(tools, run, environment, 'adb-help', [tools['adb'], 'help']),
               probe(tools, run, environment, 'emulator-help', [tools['emulator'], '-help-all']),
               probe(tools, run, environment, 'accel-check', [tools['emulator'], '-accel-check'], (0, 1))]
    help_text = bounded(run / 'logs/adb-help.log').decode('utf-8', errors='replace')
    if '--one-device' not in help_text or '-L SOCKET' not in help_text:
        raise PreflightError('Installed ADB help does not confirm the required private-server flags')
    results.append(probe(tools, run, environment, 'adb-keygen',
                         [tools['adb'], 'keygen', str(run / 'home/.android/adbkey')]))
    arguments = [tools['adb'], '-L', f'tcp:127.0.0.1:{PORT}', '--one-device',
                 'veyra-no-usb-device', 'server', 'nodaemon']
    with (run / 'logs/private-adb.log').open('xb') as log:
        process = subprocess.Popen(traced_command(tools, run, 'private-adb', arguments), cwd=run,
                                   env=environment, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, preexec_fn=limit_probe)
        tree = OwnedTree(process)
        try:
            if watch(tree, run, 10, owns_listener) is not None:
                raise PreflightError('Private foreground ADB exited before its owned listener was ready')
            version = private_adb_version()
        finally:
            tree.stop()
    if listener_inodes(5037) or listener_inodes(PORT):
        raise PreflightError('A private ADB listener survived owned teardown')
    json_write(run / 'guarded-result.json', {
        'schemaVersion': 1, 'phase': 'no-guest-stage-0', 'probes': results,
        'privateAdbProtocolVersion': version, 'guestStarted': False,
        'networkNamespace': namespace('net'), 'mountNamespace': namespace('mnt'),
        'nativeInstrumentedTestsExecuted': 0, 'manualTraceReviewRequired': True,
        'guestExecutionAuthorized': False, 'physicalAudioVerified': False,
    })


def inside_network(run: Path) -> None:
    run = validate_run(run)
    origin = json.loads(bounded(run / 'origin.json'))
    current = namespace('net')

    def recheck_namespace():
        observed = namespace('net')
        assert_new_namespace(observed, origin['host']['networkNamespace'], namespace('net', os.getppid()))
        if observed != current:
            raise PreflightError('Private network namespace identity changed')

    recheck_namespace()
    tools = origin['inputs']['tools']
    before = network_snapshot(tools['ip'], run, 'before')
    identities = validate_network_state(before, loopback_up=False)
    recheck_namespace()
    # The ONLY network mutation follows fresh identity and complete state checks.
    # Never remove/configure sit0, another interface, or the caller namespace.
    subprocess.run([tools['ip'], 'link', 'set', 'dev', 'lo', 'up'], check=True, timeout=5)
    recheck_namespace()
    after = network_snapshot(tools['ip'], run, 'after')
    if validate_network_state(after, loopback_up=True) != identities:
        raise PreflightError('Private interfaces changed during loopback activation')
    recheck_namespace()
    json_write(run / 'network.json', {
        'networkNamespace': current, 'loopbackOnly': True,  # Only lo is active/addressed/routed.
        'inertKernelSit0': 'sit0' in identities, 'before': before, 'after': after,
    })
    guard = str(ROOT / 'scripts/linux-namespace.sh')
    arguments = [guard, '--mask-run', str(run / 'service-runtime'), '--', sys.executable, '-B',
                 str(Path(__file__).resolve()), '--inside-guard', str(run)]
    with failure_phase(run, 'mount-guard-exec'):
        os.execv(guard, arguments)


def preflight() -> Path:
    if sys.platform != 'linux' or os.environ.get('VEYRA_SCOPED_ENV') != '1':
        raise PreflightError('Use the scoped Stage 0 wrapper on the reviewed Linux host')
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise PreflightError('Safe pidfd teardown support is required; no numeric-PID fallback')
    validate_host_environment(dict(os.environ), Path('/proc/sys/kernel/core_pattern').read_text(),
                              Path('/dev/bus/usb').exists(), bool(list(Path('/dev/snd').glob('pcm*'))))
    if shutil.disk_usage(ROOT).free < 2 * 1024 ** 3:
        raise PreflightError('Insufficient repository storage for a bounded Stage 0 audit')
    inputs = tool_inputs(dict(os.environ))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    before = snapshot()
    run = prepare_run()
    json_write(run / 'origin.json', {'schemaVersion': 1, 'host': before, 'inputs': inputs})
    command = [inputs['tools']['unshare']]
    if os.getuid() != 0:
        command += ['--user', '--map-current-user']
    command += ['--net', sys.executable, '-B', str(Path(__file__).resolve()), '--inside-network', str(run)]
    failure = None
    with (run / 'logs/namespace-launch.log').open('xb') as log:
        process = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, preexec_fn=limit_probe)
        tree = OwnedTree(process)
        try:
            if watch(tree, run, 120) != 0:
                raise PreflightError('Guarded Stage 0 failed; no guest or fallback was attempted')
        except (PreflightError, OSError, subprocess.SubprocessError) as error:
            failure = type(error).__name__
        finally:
            tree.stop()
    after = snapshot()
    result_path = run / 'guarded-result.json'
    result = json.loads(bounded(result_path)) if result_path.is_file() else {}
    unchanged = before == after
    network_path = run / 'network.json'
    private_net = json.loads(bounded(network_path)).get('networkNamespace') if network_path.is_file() else None
    remaining = []
    if private_net and private_net != before['networkNamespace']:
        for path in Path('/proc').iterdir():
            if path.name.isdigit():
                value = proc_identity(int(path.name))
                if value and value['networkNamespace'] == private_net and value['state'] != 'Z':
                    remaining.append(value)
    json_write(run / 'summary.json', {
        'schemaVersion': 1, 'phase': 'no-guest-stage-0', 'probesCompleted': bool(result),
        'callerNamespaceMountsAndAdbUnchanged': unchanged, 'failureClass': failure,
        'remainingPrivateNamespaceProcesses': remaining,
        'manualTraceReviewRequired': True, 'guestExecutionAuthorized': False,
        'nativeInstrumentedTestsExecuted': 0, 'physicalAudioVerified': False,
    })
    if failure or not result or not unchanged or remaining:
        raise PreflightError('Stage 0 is incomplete; inspect owned evidence before any further native launch')
    return run


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--preflight', action='store_true')
    group.add_argument('--inside-network', type=Path, help=argparse.SUPPRESS)
    group.add_argument('--inside-guard', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise PreflightError('Android Stage 0 requires the reviewed Linux namespace host')
    def interrupted(signum, frame):
        raise PreflightError('Stage 0 interrupted; tearing down only tracked owned processes')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    old = os.umask(0o077)
    try:
        if args.inside_network:
            inside_network(args.inside_network)
        elif args.inside_guard:
            inside_guard(args.inside_guard)
        else:
            run = preflight()
            print('Stage 0 probes completed; manual trace review still required: ' + str(run.relative_to(ROOT)))
            print('No guest, instrumented-test or physical-audio success is claimed. --run remains gated.')
    finally:
        os.umask(old)


if __name__ == '__main__':
    try:
        main()
    except (PreflightError, OSError, ValueError, subprocess.SubprocessError) as error:
        print('Android Stage 0 stopped (' + type(error).__name__ + '); no uncontained fallback.', file=sys.stderr)
        if isinstance(error, PreflightError):
            print(str(error), file=sys.stderr)
        raise SystemExit(1)
