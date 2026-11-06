#!/usr/bin/env bash
# Read-only private mount view for Xorg's hard-coded scratch paths. Writable
# directory backing remains inside Veyra. This is an accidental-write guard,
# not a general-purpose sandbox for hostile processes or device/network I/O.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
[ "$(uname -s)" = Linux ] || { printf '%s\n' 'Linux mount namespaces are required.' >&2; exit 1; }

if [ "${1:-}" = --inside ]; then
    shift
    [ "$#" -ge 4 ] || exit 2
    original_namespace="$1"; caller_uid="$2"; caller_gid="$3"; shift 3
    current_namespace="$(readlink /proc/self/ns/mnt)"
    parent_namespace="$(readlink "/proc/$PPID/ns/mnt")"
    if [ "$current_namespace" = "$original_namespace" ] || [ "$current_namespace" = "$parent_namespace" ]; then
        printf '%s\n' 'Refusing a mount in the caller/parent namespace.' >&2
        exit 1
    fi
    [[ "$caller_uid" =~ ^[0-9]+$ ]] && [[ "$caller_gid" =~ ^[0-9]+$ ]] || exit 2
    for tool in mount python3 setpriv; do
        command -v "$tool" >/dev/null 2>&1 || { printf 'Existing %s required; no installation/fallback.\n' "$tool" >&2; exit 1; }
    done

    # The identity checks above precede EVERY mount operation, including
    # propagation changes. -n avoids any mtab write. Only bind-remount flags
    # change: do not remount an underlying shared filesystem superblock.
    python3 -B - <<'PY'
import os
from pathlib import Path
import re
import subprocess

root = Path(os.environ['VEYRA_PROJECT_ROOT']).resolve(strict=True)
work = root / '.work'
if root == Path('/'):
    raise SystemExit('The repository cannot be the filesystem root')
backing = {
    Path('/tmp'): work / 'tmp',
    Path('/var/lib/xkb'): work / 'cache' / 'xkb',
    Path('/dev/shm'): work / 'shm',
}
for target, source in backing.items():
    if not target.is_dir() or target.is_symlink():
        raise SystemExit('Existing plain scratch mount target required: ' + str(target))
    if not source.resolve(strict=False).is_relative_to(root):
        raise SystemExit('Escaping namespace backing directory rejected')
for source in backing.values():
    source.mkdir(parents=True, exist_ok=True)

def mount(*args):
    subprocess.run(['mount', '-n', *map(str, args)], check=True)

def readonly(path):
    return bool(os.statvfs(path).f_flag & os.ST_RDONLY)

def mountpoints():
    # mountinfo octal-escapes whitespace and backslashes in paths.
    points = set()
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        value = line.split()[4]
        points.add(Path(re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), value)))
    return sorted(points, key=lambda p: (len(p.parts), str(p)))

mount('--make-rprivate', '/')
protected = mountpoints()
for target in protected:
    if not readonly(target):
        mount('--bind', target, target)
        mount('-o', 'remount,bind,ro', target)
    if not readonly(target):
        raise SystemExit('Read-only mount verification failed: ' + str(target))

# A new repository bind may be writable even though its containing mount
# is read-only. Failure (including a locked user-namespace mount) is fatal.
mount('--bind', root, root)
mount('-o', 'remount,bind,rw', root)
if readonly(root):
    raise SystemExit('Repository did not remain writable')
for target, source in backing.items():
    mount('--bind', source, target)
    a, b = target.stat(), source.stat()
    if (a.st_dev, a.st_ino) != (b.st_dev, b.st_ino) or readonly(target):
        raise SystemExit('Repository-backed mount identity failed: ' + str(target))

# Check the effective view, not duplicate/hidden lines in mountinfo.
for target in mountpoints():
    if target.is_relative_to(root) or target in backing:
        continue
    if not readonly(target):
        raise SystemExit('Unexpected writable system mount: ' + str(target))
PY
    export VEYRA_DISPLAY_MOUNT_NAMESPACE="$current_namespace"
    # Prevent an accidentally privileged child from undoing the read-only
    # guard. /dev/null and other pre-existing devices, stdout/stderr pipes,
    # anonymous memory and network I/O are not ordinary filesystem writes.
    privilege_args=(--no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all)
    if [ "$(id -u)" != "$caller_uid" ] || [ "$(id -g)" != "$caller_gid" ]; then
        privilege_args+=(--reuid="$caller_uid" --regid="$caller_gid" --init-groups)
    fi
    exec setpriv "${privilege_args[@]}" -- "$@"
fi

[ "$#" -gt 0 ] || { printf '%s\n' 'Usage: scripts/linux-namespace.sh command [args...]' >&2; exit 2; }
command -v unshare >/dev/null 2>&1 || { printf '%s\n' 'Existing unshare required; no uncontained fallback.' >&2; exit 1; }
original_namespace="$(readlink /proc/self/ns/mnt)"
caller_uid="$(id -u)"; caller_gid="$(id -g)"
if [ "$caller_uid" = 0 ]; then
    exec unshare --mount --propagation unchanged "$SCRIPT_DIR/linux-namespace.sh" \
        --inside "$original_namespace" "$caller_uid" "$caller_gid" "$@"
else
    # No sudo/sysctl/AppArmor workaround: missing capabilities fail closed.
    # sudo itself may have host-managed log/timestamp side effects.
    exec unshare --user --map-current-user --mount --propagation unchanged "$SCRIPT_DIR/linux-namespace.sh" \
        --inside "$original_namespace" "$caller_uid" "$caller_gid" "$@"
fi
