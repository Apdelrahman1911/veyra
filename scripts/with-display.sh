#!/usr/bin/env bash
# An owned, authenticated Xvfb for Linux tests. Default xvfb-run writes X11
# sockets/locks under /tmp, so do not use it under Veyra's filesystem boundary.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
[ "$#" -gt 0 ] || { printf '%s\n' 'Usage: scripts/with-display.sh command [args...]' >&2; exit 2; }
[ "$(uname -s)" = Linux ] || exec "$@"
# Xorg ignores TMPDIR for its keyboard cache. Never launch it directly in
# the caller's mount view, even when an external DISPLAY is already set.
if [ "${VEYRA_DISPLAY_MOUNT_NAMESPACE:-}" != "$(readlink /proc/self/ns/mnt)" ]; then
    exec "$SCRIPT_DIR/linux-namespace.sh" "$SCRIPT_DIR/with-display.sh" "$@"
fi
python3 -B - <<'PY'
import os
from pathlib import Path
root=Path(os.environ['VEYRA_PROJECT_ROOT'])
for path in ('/', '/proc', '/sys', '/dev'):
    if not os.statvfs(path).f_flag & os.ST_RDONLY:
        raise SystemExit('Refusing Xvfb without the read-only mount guard')
for target,source in (('/tmp','.work/tmp'),('/var/lib/xkb','.work/cache/xkb'),('/dev/shm','.work/shm')):
    a=Path(target).stat(); b=(root/source).stat()
    if (a.st_dev,a.st_ino)!=(b.st_dev,b.st_ino):
        raise SystemExit('Refusing Xvfb without repository-backed scratch directories')
PY
for tool in Xvfb xauth python3; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Existing %s required; no system installation will be attempted.\n' "$tool" >&2; exit 1; }
done
mkdir -p .work/verification
display_number="$(python3 -B - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1] - 6000)
PY
)"
export DISPLAY="127.0.0.1:$display_number"
export XAUTHORITY="$VEYRA_WORK_ROOT/runtime/xauth-$$"
export LIBGL_ALWAYS_SOFTWARE=1
export SKIKO_RENDER_API=SOFTWARE
xvfb_pid=""
cleanup() {
    if [ -n "$xvfb_pid" ]; then
        kill "$xvfb_pid" 2>/dev/null || true
        wait "$xvfb_pid" 2>/dev/null || true
    fi
    rm -f -- "$XAUTHORITY" "$XAUTHORITY-c" "$XAUTHORITY-l" "$XAUTHORITY-n"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Authentication cookie goes through stdin, not logs or command arguments.
python3 -B - "$DISPLAY" <<'PY' | xauth -q -f "$XAUTHORITY"
import secrets,sys
print('add ' + sys.argv[1] + ' MIT-MAGIC-COOKIE-1 ' + secrets.token_hex(16))
PY
Xvfb ":$display_number" -screen 0 1440x1000x24 -auth "$XAUTHORITY" \
    -nolock -nolisten unix -nolisten local -nolisten inet6 -listen tcp \
    -extension MIT-SHM -noreset > .work/verification/xvfb.log 2>&1 &
xvfb_pid=$!
python3 -B - "$display_number" "$xvfb_pid" <<'PY'
import os,socket,sys,time
port=6000+int(sys.argv[1]); pid=int(sys.argv[2])
for _ in range(100):
    try:
        os.kill(pid,0)
    except ProcessLookupError:
        raise SystemExit('Owned Xvfb exited before becoming ready; see .work/verification/xvfb.log')
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.1):
            break
    except OSError:
        time.sleep(.05)
else:
    raise SystemExit('Owned Xvfb did not become ready')
PY
"$@"
