#!/usr/bin/env python3
"""Bounded authenticated Xlib check using the library required by desktop Java."""
from __future__ import annotations

import ctypes
import os
from pathlib import Path
import subprocess
import sys


def probe() -> None:
    library = ctypes.CDLL("libX11.so.6")
    library.XOpenDisplay.argtypes = [ctypes.c_char_p]
    library.XOpenDisplay.restype = ctypes.c_void_p
    library.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
    library.XSync.restype = ctypes.c_int
    library.XCloseDisplay.argtypes = [ctypes.c_void_p]
    library.XCloseDisplay.restype = ctypes.c_int
    display = library.XOpenDisplay(os.environ["DISPLAY"].encode("ascii"))
    if not display:
        raise SystemExit(1)
    try:
        library.XSync(display, 0)
    finally:
        library.XCloseDisplay(display)


def main() -> None:
    # Keep native-library failures/timeouts in one awaited child. The cookie
    # stays in XAUTHORITY; neither arguments nor diagnostics contain it.
    try:
        subprocess.run([sys.executable, "-B", str(Path(__file__).resolve()), "--probe"],
                       check=True, timeout=10, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise SystemExit("Owned display authentication failed; see .work/verification/xvfb.log")


if __name__ == "__main__":
    if sys.argv[1:] == ["--probe"]:
        probe()
    elif not sys.argv[1:]:
        main()
    else:
        raise SystemExit("Usage: check-display.py")
