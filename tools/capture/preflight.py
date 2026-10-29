"""Aggregate dependency audit. Guest smoke is only for an approved running guest."""
import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

BASE_COMMANDS = ('bash', 'git', 'python3', 'sudo', 'apt-get', 'stat', 'id', 'ps',
                 'kill', 'timeout', 'tee', 'tr', 'sleep', 'head', 'find', 'cp',
                 'cat', 'ls', 'grep', 'sed', 'dirname', 'uname', 'ldd', 'unzip',
                 'tar', 'gzip', 'xz', 'which', 'readlink', 'expr', 'xargs')
HOST_COMMANDS = BASE_COMMANDS + ('getfacl', 'setfacl', 'ffmpeg', 'ffprobe')
STDLIB = ('argparse', 'hashlib', 'importlib.util', 'json', 'os', 'pathlib', 're',
          'shutil', 'subprocess', 'sys', 'tempfile', 'zipfile', 'grp', 'stat',
          'signal', 'time', 'urllib.parse', 'urllib.request', 'xml.etree.ElementTree',
          'ssl', 'zlib', 'io', 'base64', 'unittest', 'contextlib')
ASSETS = ('Nunito.ttf', 'emoji.tsv', 'words.tsv', 'words_de.tsv', 'words_fr.tsv',
          'pairs.tsv', 'pairs_de.tsv', 'pairs_fr.tsv')
GUEST_COMMANDS = ('sh', 'screenrecord', 'screencap', 'stat', 'cat', 'kill',
                  'mkdir', 'rm', 'cp', 'ls', 'uiautomator', 'cmd', 'settings', 'ime',
                  'wm', 'pm', 'am', 'dumpsys', 'getprop', 'pidof', 'logcat', 'input', 'id')


def command_gaps(names, which=shutil.which):
    return ['missing command: ' + name for name in names if not which(name)]


def signer_gaps(environment):
    return ['Signing variables must not be injected into capture jobs'] if any(
        name in environment for name in ('BOLD_DEBUG_KEYSTORE_BASE64', 'BOLD_DEBUG_STORE_PASSWORD')) else []


def run(args, timeout=30, environment=None):
    return subprocess.run(args, capture_output=True, text=True, check=True, timeout=timeout,
                          env=environment).stdout


def host_checks(root, sdk, java, bootstrap=False, min_free_gib=8):
    gaps = command_gaps(BASE_COMMANDS if bootstrap else HOST_COMMANDS) + signer_gaps(os.environ)
    for name in STDLIB:
        try:
            importlib.import_module(name)
        except ImportError:
            gaps.append('missing Python standard library: ' + name)
    report = {'phase': 'bootstrap' if bootstrap else 'host', 'gaps': gaps,
              'third_party_python_dependencies': [], 'signing_variables_required': False,
              'python_version': list(sys.version_info[:3])}
    if sys.version_info < (3, 10):
        gaps.append('Python 3.10 or newer required')
    if bootstrap:
        return report
    paths = [java / 'bin/java', java / 'bin/keytool', java / 'bin/javac', java / 'bin/jar',
             sdk / 'platform-tools/adb',
             sdk / 'cmdline-tools/latest/bin/sdkmanager', sdk / 'cmdline-tools/latest/bin/avdmanager',
             sdk / 'emulator/emulator', sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64',
             sdk / 'platforms/android-36/android.jar', sdk / 'cmake/3.22.1/bin/cmake',
             sdk / 'cmake/3.22.1/bin/ninja', sdk / 'ndk/27.0.12077973/source.properties',
             sdk / 'system-images/android-35/google_apis/x86_64/system.img']
    paths += [sdk / 'ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin' / name
              for name in ('clang++', 'ld.lld', 'llvm-ar', 'llvm-strip')]
    paths += [root / p for p in ('gradlew', 'gradle/wrapper/gradle-wrapper.jar',
               'app/src/debug/AndroidManifest.xml',
               'app/src/debug/java/ai/boldkeys/KeyboardQaActivity.kt',
               'app/src/androidTest/java/ai/boldkeys/ImeWindowObserver.kt',
               'third_party/llama.cpp/CMakeLists.txt')]
    for version in ('35.0.0', '36.0.0'):
        paths += [sdk / 'build-tools' / version / name for name in ('aapt2', 'd8', 'apksigner', 'zipalign')]
    gaps += ['missing SDK/JDK file: ' + str(p) for p in paths if not p.is_file()]
    if (java / 'bin/java').is_file():
        try:
            version = re.search(r'(?:openjdk|java) (\d+)', run([str(java / 'bin/java'), '--version']))
            if not version or version.group(1) != '17':
                gaps.append('JDK 17 required')
            else:
                report['java_major'] = 17
        except subprocess.SubprocessError:
            gaps.append('JDK version inspection failed')
    wrapper = root / 'gradle/wrapper/gradle-wrapper.properties'
    if not wrapper.is_file() or not re.search(r'^distributionSha256Sum=[0-9a-f]{64}$', wrapper.read_text(), re.M):
        gaps.append('Gradle distribution SHA256 pin missing')
    providers = [(sdk / 'emulator/lib64/gles_angle', ('libEGL.so', 'libGLESv2.so', 'libvk_swiftshader.so')),
                 (sdk / 'emulator/lib64/gles_swiftshader', ('libEGL.so', 'libGLESv2.so'))]
    graphics = [p / name for p,names in providers if all((p / n).is_file() for n in names) for name in names]
    if not graphics:
        gaps.append('bundled SwiftShader EGL/GLES provider missing')
    assets = {}
    for name in ASSETS:
        p = root / 'app/src/main/assets' / name
        if not p.is_file() or p.stat().st_size == 0:
            gaps.append('missing/empty product asset: ' + name)
        else:
            assets[name] = hashlib.sha256(p.read_bytes()).hexdigest()
    report['asset_sha256'] = assets
    for binary in [sdk / 'platform-tools/adb', sdk / 'emulator/emulator',
                   sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64', *graphics]:
        if binary.is_file() and shutil.which('ldd'):
            try:
                # Match the SDK wrapper's bundled library search for inspection;
                # do not report its shipped Qt/C++ libraries as missing system libs.
                library_env = dict(os.environ, LD_LIBRARY_PATH=':'.join(str(p) for p in
                    (sdk / 'emulator/lib64', sdk / 'emulator/lib64/qt/lib', *(p for p,_ in providers))))
                text = run(['ldd', str(binary)], environment=library_env)
                missing = re.findall(r'^\s*([\w.+-]+)\s*=>\s*not found', text, re.M)
                gaps += ['unresolved native library: ' + n for n in missing]
            except subprocess.SubprocessError:
                gaps.append('native dependency inspection failed: ' + binary.name)
    free = shutil.disk_usage(root).free
    report['free_disk_bytes'] = free
    if free < min_free_gib * 1024**3:
        gaps.append('insufficient free disk before build: require ' + str(min_free_gib) + ' GiB')
    if shutil.which('ffmpeg') and shutil.which('ffprobe'):
        try:
            decoders = run(['ffmpeg', '-hide_banner', '-decoders'])
            encoders = run(['ffmpeg', '-hide_banner', '-encoders'])
            formats = run(['ffmpeg', '-hide_banner', '-formats'])
            for codec in ('h264', 'png'):
                if not re.search(r'^\s*V\S*\s+' + codec + r'\s', decoders, re.M):
                    gaps.append('missing FFmpeg decoder: ' + codec)
            for codec in ('png', 'libx264'):
                if not re.search(r'^\s*V\S*\s+' + codec + r'\s', encoders, re.M):
                    gaps.append('missing FFmpeg encoder: ' + codec)
            for name in ('framemd5', 'null'):
                if not re.search(r'^\s*\S*E\s+' + name + r'\s', formats, re.M):
                    gaps.append('missing FFmpeg output format: ' + name)
            for name in ('ffmpeg', 'ffprobe'):
                text = run([name, '-version'])
                match = re.search(name + r' version (\S+)', text)
                report[name + '_version'] = match.group(1) if match else 'unknown'
        except subprocess.SubprocessError:
            gaps.append('FFmpeg/FFprobe capability inspection failed')
    report['gaps'] = sorted(set(gaps))
    return report


def validate_smoke(info):
    streams = info.get('streams', [])
    try:
        # A static launcher may legitimately produce one I-frame. Motion/action
        # proof belongs to the actual product clips, not this codec-only smoke.
        return (len(streams) == 1 and streams[0].get('codec_name') == 'h264'
                and streams[0].get('width') == 1080 and streams[0].get('height') == 1920
                and int(streams[0].get('nb_read_frames', 0)) >= 1)
    except (ValueError, TypeError):
        return False


def readable_process_identity(text):
    try:
        begin, end = text.index('('), text.rindex(')')
        fields = text[end + 1:].split()
        return (begin < end and fields[0] in ('R', 'S', 'D', 'I')
                and int(fields[19]) >= 0)
    except (ValueError, IndexError):
        return False


def handler_component(text):
    matches = [line.strip() for line in text.splitlines()
               if re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', line.strip())]
    return matches[0] if len(matches) == 1 and not matches[0].startswith('android/') else None


def guest_checks(root):
    # Only called after the owner-approved grant and healthy launcher gate.
    def adb(command): return run(['adb', 'shell', command], timeout=60).strip()
    gaps = []
    if adb('id -u') != '2000':
        gaps.append('guest must remain UID 2000')
    command = 'for c in ' + ' '.join(GUEST_COMMANDS) + '; do command -v "$c" >/dev/null 2>&1 || echo "$c"; done'
    gaps += ['missing guest command: ' + x for x in adb(command).splitlines() if x]
    if not readable_process_identity(adb('cat /proc/self/stat')):
        gaps.append('guest /proc process identity/starttime probe failed')
    if gaps:
        return {'phase': 'guest', 'gaps': gaps}
    adb('mkdir -p /sdcard/Download/bold-keys-qa')
    # This is a genuine launcher codec smoke, explicitly not product footage.
    adb('screenrecord --time-limit 2 /sdcard/Download/bold-teaser-codec-smoke.mp4')
    path = root / 'qa-artifacts/codec-smoke.mp4'
    run(['adb', 'pull', '/sdcard/Download/bold-teaser-codec-smoke.mp4', str(path)], timeout=60)
    info = json.loads(run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0',
                           '-show_entries', 'stream=codec_name,width,height,nb_read_frames', '-of', 'json', str(path)]))
    run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'null', '-'], timeout=60)
    png = root / 'qa-artifacts/codec-smoke.png'
    png.write_bytes(subprocess.run(['adb', 'exec-out', 'screencap', '-p'],
                                   capture_output=True, check=True, timeout=60).stdout)
    run(['ffmpeg', '-v', 'error', '-i', str(png), '-frames:v', '1', '-f', 'null', '-'])
    if not validate_smoke(info):
        gaps.append('guest AVC smoke did not decode at exact 1080x1920 with a valid frame')
    handlers = {
        'app_settings': handler_component(adb('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.android.settings')),
        'link_https': handler_component(adb('cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://www.android.com')),
    }
    # Resolve only; do not launch or contact a URL here. A handler problem must
    # fail its independent feature test, not prevent unrelated useful captures.
    feature_gaps = [name + ': no unambiguous installed handler' for name, value in handlers.items() if not value]
    return {'phase': 'guest', 'gaps': gaps, 'codec_smoke': info,
            'shortcut_handlers': handlers, 'feature_readiness_gaps': feature_gaps,
            'smoke_kind': 'genuine launcher only; never stage as product footage'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=('bootstrap', 'host', 'guest'), required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME', ''))
    parser.add_argument('--java', type=Path, default=os.environ.get('JAVA_HOME', ''))
    args = parser.parse_args()
    root = Path.cwd()
    try:
        result = (guest_checks(root) if args.phase == 'guest' else
                  host_checks(root, args.sdk, args.java, bootstrap=args.phase == 'bootstrap'))
    except (OSError, subprocess.SubprocessError, ValueError):
        result = {'phase': args.phase, 'gaps': ['preflight probe failed; no capture success claimed']}
    (root / 'qa-artifacts').mkdir(exist_ok=True)
    (root / 'qa-artifacts' / ('capture-preflight-' + args.phase + '.json')).write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
    sys.exit(1 if result['gaps'] else 0)
