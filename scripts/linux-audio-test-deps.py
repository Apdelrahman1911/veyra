#!/usr/bin/env python3
"""Extract pinned Ubuntu native-test client libraries in .work; never install.

Only test subprocesses receive this library path. Nothing is added to an app
package, the system loader configuration, or the host package database.
"""
from __future__ import annotations

import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = (
    'libapparmor1', 'libasound2t64', 'libasyncns0', 'libbsd0', 'libcap2', 'libdbus-1-3', 'libdrm2',
    'libegl1', 'libexpat1', 'libflac12t64', 'libfontconfig1', 'libgcrypt20', 'libgl1', 'libglvnd0',
    'libglx0', 'libgpg-error0', 'liblz4-1', 'liblzma5', 'libmd0', 'libmp3lame0', 'libmpg123-0t64',
    'libogg0', 'libopus0', 'libpulse0', 'libsndfile1', 'libsystemd0', 'libvorbis0a',
    'libvorbisenc2', 'libvulkan1', 'libx11-6', 'libx11-xcb1', 'libxau6', 'libxcb1', 'libxdmcp6',
    'libzstd1',
)
LIBRARIES = (
    'libEGL.so.1', 'libFLAC.so.12', 'libGL.so.1', 'libGLX.so.0', 'libGLdispatch.so.0',
    'libX11-xcb.so.1', 'libX11.so.6', 'libXau.so.6', 'libXdmcp.so.6', 'libapparmor.so.1',
    'libasound.so.2', 'libasyncns.so.0', 'libbsd.so.0', 'libcap.so.2', 'libdbus-1.so.3',
    'libdrm.so.2', 'libexpat.so.1', 'libfontconfig.so.1', 'libgcrypt.so.20', 'libgpg-error.so.0',
    'liblz4.so.1', 'liblzma.so.5', 'libmd.so.0', 'libmp3lame.so.0', 'libmpg123.so.0',
    'libogg.so.0', 'libopus.so.0', 'libpulse.so.0', 'libsndfile.so.1', 'libsystemd.so.0',
    'libvorbis.so.0', 'libvorbisenc.so.2', 'libvulkan.so.1', 'libxcb.so.1', 'libzstd.so.1',
)
MAX_BYTES = 10 * 1024 * 1024


def metadata(package: str, output: str) -> dict[str, str]:
    for paragraph in output.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in paragraph.splitlines()
                      if ': ' in line and not line.startswith((' ', '\t')))
        if fields.get('Package') == package and fields.get('Architecture') == 'amd64':
            filename = fields.get('Filename', '')
            if (not re.fullmatch(r'pool/[A-Za-z0-9_+./~-]+\.deb', filename)
                    or '..' in Path(filename).parts
                    or not re.fullmatch(r'[a-f0-9]{64}', fields.get('SHA256', ''))
                    or not 0 < int(fields.get('Size', '0')) <= MAX_BYTES):
                raise ValueError('Invalid Ubuntu audio package metadata')
            return fields
    raise ValueError('Pinned Ubuntu package metadata is required: ' + package)


def fetch(fields: dict[str, str], destination: Path) -> None:
    # The reviewed Ubuntu index snapshot supplies the exact path/digest. Fetch only
    # the Ubuntu archive, bounded, with no redirects to an arbitrary host.
    url = 'https://archive.ubuntu.com/ubuntu/' + fields['Filename']
    with urllib.request.urlopen(url, timeout=60) as response:
        if response.status != 200 or not response.url.startswith('https://archive.ubuntu.com/ubuntu/'):
            raise ValueError('Unexpected Ubuntu package response')
        data = response.read(MAX_BYTES + 1)
    if len(data) != int(fields['Size']) or hashlib.sha256(data).hexdigest() != fields['SHA256']:
        raise ValueError('Ubuntu audio package size/hash mismatch')
    destination.write_bytes(data)


def prepare() -> str:
    try:
        for library in LIBRARIES:
            ctypes.CDLL(library)
        return ''  # Every external MPV dependency already loads on this host.
    except OSError:
        pass
    release = Path('/etc/os-release').read_text()
    if not re.search(r'^ID=ubuntu$', release, re.M) or not re.search(r'^VERSION_ID="24\.04"$', release, re.M):
        raise SystemExit('Missing native-test library: scoped extraction supports Ubuntu 24.04 only')
    if subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip() != 'amd64':
        raise SystemExit('Matching amd64 Ubuntu audio packages required')
    parent = ROOT / '.work/linux-audio-tests'
    for path in (parent, *parent.parents):
        if path.is_symlink():
            raise SystemExit('Linked audio dependency directory refused')
        if path == ROOT:
            break
    parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='deps-', dir=parent))
    sysroot = stage / 'sysroot'
    sysroot.mkdir()
    pinned = json.loads(Path(__file__).with_suffix('.json').read_text())
    if pinned.get('schema') != 1 or {r['Package'] for r in pinned['packages']} != set(PACKAGES):
        raise ValueError('Exact Ubuntu audio package inventory required')
    records = []
    for record in pinned['packages']:
        package = record['Package']
        fields = metadata(package, '\n'.join(f'{k}: {v}' for k, v in record.items()))
        archive = stage / (package + '.deb')
        fetch(fields, archive)
        actual = subprocess.check_output(['dpkg-deb', '-f', str(archive), 'Package', 'Version', 'Architecture'],
                                         text=True, timeout=30)
        expected = f"Package: {package}\nVersion: {fields['Version']}\nArchitecture: amd64\n"
        if actual != expected:
            raise ValueError('Audio archive control identity mismatch')
        subprocess.run(['dpkg-deb', '--extract', str(archive), str(sysroot)], check=True, timeout=30)
        records.append({key: fields[key] for key in ('Package', 'Version', 'Filename', 'Size', 'SHA256')})
    libraries = [sysroot / 'usr/lib/x86_64-linux-gnu', sysroot / 'usr/lib/x86_64-linux-gnu/pulseaudio',
                 sysroot / 'lib/x86_64-linux-gnu', sysroot / 'lib/x86_64-linux-gnu/pulseaudio']
    value = ':'.join(str(p) for p in libraries if p.is_dir())
    subprocess.run(['python3', '-B', '-c',
                    'import ctypes,sys; [ctypes.CDLL(name) for name in sys.argv[1:]]', *LIBRARIES],
                   check=True, timeout=10,
                   env=dict(os.environ, LD_LIBRARY_PATH=value + (':' + os.environ['LD_LIBRARY_PATH']
                                                           if os.environ.get('LD_LIBRARY_PATH') else '')))
    (stage / 'manifest.json').write_text(json.dumps({'packages': records, 'libraryPath': value, 'verifiedLibraries': list(LIBRARIES)}, indent=2) + '\n')
    return value


if __name__ == '__main__':
    print(prepare())
