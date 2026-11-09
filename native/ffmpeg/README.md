# Bundled local-remux FFmpeg

This is a **separate production profile**, not the broader kmpYT CI decoder tool.
It builds official FFmpeg **8.0.1**, commit
`894da5ca7d742e4429ffb2af534fcda0103ef593`, from a whole-SHA-256-pinned source
archive. No third-party binary bundle, global install, compiler download, or
developer FFmpeg fallback is used.

The extracted `RELEASE` must be exactly `8.0.1` plus LF, with no `VERSION`
override. GNU make receives `REVISION=8.0.1` explicitly: otherwise FFmpeg's
version script can mistake the enclosing Veyra checkout for FFmpeg's own Git
repository. The release hash/revision are retained in provenance, inherited make
and version overrides are removed, and the reported version token must match
exactly. No source or version-check bypass is used to accept a mismatched build.

Run on the matching existing host toolchain, through the scoped environment:

```sh
scripts/dev.sh python3 -B native/ffmpeg/build.py --jobs 1
# Optional: reuse a previously downloaded pinned archive inside this repo.
scripts/dev.sh python3 -B native/ffmpeg/build.py --jobs 1 --source .work/path/FFmpeg-n8.0.1.tar.gz
# Offline, nonexecuting integrity/reuse check (does not require a C compiler).
scripts/dev.sh python3 -B native/ffmpeg/build.py --verify-output
```

Supported recipes: Linux x64 (Ubuntu24.04 baseline), macOS13+ x64/arm64, and
Windows10+ x64. Linux/macOS need an existing C compiler, GNU make and bash.
Windows needs an **x64 MSVC developer environment** plus existing POSIX make/bash;
the static MSVC CRT is requested. There is no cross-compilation or automatic
toolchain installation. Recipes do not establish successful execution on a host.

The profile disables network, autodetected libraries, GPL/nonfree/version3 code,
shared FFmpeg libraries, and unnecessary programs. It enables only the file,
crypto and data protocols, common local-input demuxers, internal parsers/bitstream
filters, and MP4 stream-copy output used by kmpYT. The ffmpeg CLI still requires
avfilter. It does **not** enable the audio/video decoders for a second media engine;
libmpv validates/plays the kmpYT output. Linux still depends on the supported
system libc/loader; static FFmpeg components do not mean a fully static ELF.

Output contract:

```
.work/native-ffmpeg/output/<platform>/ffmpeg[.exe]
.work/native-ffmpeg/output/<platform>/ffmpeg[.exe].sha256
.work/native-ffmpeg/output/<platform>/provenance.json
.work/native-ffmpeg/output/<platform>/licenses/
.work/native-ffmpeg/output/<platform>/corresponding-source/
```

`<platform>` is `linux-x64`, `windows-x64`, `macos-x64` or `macos-arm64`. The digest
sidecar is exactly64 lowercase hexadecimal characters plus newline (no filename).
Package the executable and sidecar as resources
`dev/veyra/desktop/native/<platform>/ffmpeg[.exe]` and `.sha256`. The application
checks the packaged digest and private extracted bytes before execution. Retain
all provenance, complete licenses, unmodified source archive, build recipe and
generated configuration with the corresponding distribution/source delivery.
The source archive also retains the upstream copyright notices for each file.
The retained `owned_process.py` is the exact process supervisor used by the
recipe; its hash participates in reuse validation. Reproduction uses the matching
Veyra checkout's `native/ffmpeg/{build.py,README.md}`, `native/owned_process.py`,
scoped scripts and pinned authored fixtures, not a relocated standalone recipe.
Python 3.11+ is required. Trusted build descendants are drained on timeout or
cancellation (Windows Job; reserved POSIX group and Linux child subreaper).
Unproven cleanup fails closed and retains work/logs; it never authorizes deletion
or an automatic retry. This is not containment for hostile build code or
deliberately detached descendants on non-Linux POSIX hosts.

The recipe verifies reported version/license/protocol closure and executes the
**actual local stream-copy command** against redistributable authored upstream
H.264/AAC fixtures. This alone is not full decoding, live source, packaged start,
offline restart, or physical-output evidence. It writes `releaseReady:false`;
matching-host build, dependency inspection, packaged native decoding and complete
application redistribution closure remain separate gates. macOS code signing
changes executable bytes: perform signing **before** producing the final packaged
SHA sidecar and update the final binary provenance; never weaken library validation.

An existing completed output is reused **without network, compiler or execution**
only when source/target/profile/configure/recipe/README identities, binary/sidecar,
full retained-file inventory and official source/license bytes still match. It is
not silently overwritten. Preserve/review a stale or invalid output before an
explicit rebuild. This is a trusted-build receipt, not a digital signature against
an attacker who can replace both output and provenance. Failed task-owned build logs remain in
`.work/native-ffmpeg/build-*`; cleanup is selective. No `make install` runs.

On Windows, Make uses the exact existing trusted Git Bash. The early dependency
probe and actual generated `ffbuild/config.mak` use the pinned upstream MSVC AWK
filter through an `awk -f` file; passing the program inline lost a backslash on
the hosted Windows toolchain. Only target substitution becomes `-v target=...`.
The generated-config adapter refuses an unknown upstream filter. The unmodified
source archive, adapter recipe, adapted configuration and exact AWK file are
retained and hash-bound with Windows corresponding source. This is build-time
command transport, with no compiler installation or runtime fallback.
