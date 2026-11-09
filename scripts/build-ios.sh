#!/usr/bin/env bash
# Compile the complete Swift host. No simulator launch, signing, or installation.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$VEYRA_PROJECT_ROOT"
[ "$(uname -s)" = Darwin ] || { printf '%s\n' 'iOS host compilation requires existing macOS/Xcode.' >&2; exit 1; }
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode_26.0.1.app/Contents/Developer}"
[ -d "$DEVELOPER_DIR" ] || { printf '%s\n' 'Pinned Xcode unavailable; no install or xcode-select change attempted.' >&2; exit 1; }
platform="${1:-simulator}"
[ "$#" -le 1 ] || exit 2
case "$platform" in
    simulator)
        sdk=iphonesimulator; destination='generic/platform=iOS Simulator'
        architecture="$(uname -m)"
        case "$architecture" in arm64|x86_64) ;; *) printf '%s\n' 'Unsupported simulator host architecture.' >&2; exit 1 ;; esac
        ;;
    device) sdk=iphoneos; destination='generic/platform=iOS'; architecture=arm64 ;;
    *) printf '%s\n' 'Usage: scripts/build-ios.sh simulator|device' >&2; exit 2 ;;
esac
configuration="${VEYRA_IOS_CONFIGURATION:-Debug}"
case "$configuration" in Debug|Release) ;; *) exit 2 ;; esac
derived="$VEYRA_WORK_ROOT/xcode/DerivedData-$platform-$architecture"
result="$VEYRA_WORK_ROOT/xcode/results/$platform-$architecture-$(python3 -B -c 'import uuid; print(uuid.uuid4().hex)').xcresult"
python3 -B - "$derived" "$result" <<'PY'
import os,sys
from pathlib import Path
root=Path(os.environ['VEYRA_PROJECT_ROOT']).resolve()
for value in sys.argv[1:]:
    if not Path(value).resolve(strict=False).is_relative_to(root):
        raise SystemExit('Escaping Xcode output directory rejected')
Path(sys.argv[1]).mkdir(parents=True,exist_ok=True)
Path(sys.argv[2]).parent.mkdir(parents=True,exist_ok=True)
PY
for source in VeyraApp.swift ContentView.swift AppDelegate.swift Info.plist; do
    [ -f "iosApp/iosApp/$source" ] || { printf 'Missing Swift host input: %s\n' "$source" >&2; exit 1; }
done
mkdir -p .work/verification
xcodebuild -version
xcodebuild -project iosApp/Veyra.xcodeproj -scheme Veyra \
    -configuration "$configuration" -sdk "$sdk" -destination "$destination" \
    -derivedDataPath "$derived" -resultBundlePath "$result" \
    -clonedSourcePackagesDirPath "$VEYRA_WORK_ROOT/xcode/SourcePackages" \
    -disableAutomaticPackageResolution -skipPackageUpdates \
    ARCHS="$architecture" ONLY_ACTIVE_ARCH=YES \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
    CLANG_MODULE_CACHE_PATH="$CLANG_MODULE_CACHE_PATH" \
    SWIFT_MODULE_CACHE_PATH="$SWIFT_MODULECACHE_PATH" \
    SDK_STAT_CACHE_DIR="$derived/SDKStatCaches.noindex" \
    COMPILATION_CACHE_ENABLE_CACHING=NO INDEX_ENABLE_DATA_STORE=NO \
    build 2>&1 | tee ".work/verification/ios-$platform-$architecture-build.log"
app="$derived/Build/Products/$configuration-$sdk/Veyra.app"
python3 -B - "$app" "$platform" "$architecture" "$result" <<'PY'
from datetime import datetime,timezone
import hashlib,json,os
from pathlib import Path
import plistlib,subprocess,sys
root=Path(os.environ['VEYRA_PROJECT_ROOT']).resolve()
app=Path(sys.argv[1]).resolve(strict=True)
if not app.is_relative_to(root):
    raise SystemExit('Out-of-repository app output rejected')
with (app/'Info.plist').open('rb') as stream:
    info=plistlib.load(stream)
binary=(app/info['CFBundleExecutable']).resolve(strict=True)
if not binary.is_relative_to(app):
    raise SystemExit('Escaping app executable rejected')
archs=subprocess.check_output(['xcrun','lipo','-archs',str(binary)],text=True).split()
if sys.argv[3] not in archs:
    raise SystemExit('Built app does not contain the requested architecture')
digest=hashlib.sha256()
with binary.open('rb') as stream:
    for block in iter(lambda: stream.read(1024*1024),b''):
        digest.update(block)
data={'recordedAtUtc':datetime.now(timezone.utc).isoformat(),'platform':sys.argv[2],
      'app':str(app.relative_to(root)),'architectures':archs,'binarySha256':digest.hexdigest(),
      'resultBundle':str(Path(sys.argv[4]).relative_to(root)),'builtFullSwiftHost':True,
      'launched':False,'installed':False,'physicalAudioVerified':False,'productionSigned':False,
      'distribution':'simulator-only app' if sys.argv[2]=='simulator' else 'unsigned device build; NOT an installable IPA'}
(root/'.work/verification'/('ios-'+sys.argv[2]+'-'+sys.argv[3]+'.json')).write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps(data,indent=2))
PY
