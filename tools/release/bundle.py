#!/usr/bin/env python3
"""Nonpublishing AAB provenance, structure and pinned-certificate verification. No private keys."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

import release

BUNDLETOOL_SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
ROOT = Path(__file__).resolve().parents[2]
A = '{http://schemas.android.com/apk/res/android}'


def run(*args):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True)
    release.require(result.returncode == 0, 'Bundle verification command failed: ' + str(args[0]))
    return result.stdout


def elf_16k(data):
    release.require(len(data) >= 64 and data[:6] == b'\x7fELF\x02\x01', 'Expected ELF64 little-endian library')
    release.require(struct.unpack_from('<H', data, 18)[0] == 183, 'Native library is not AArch64')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    release.require(size >= 56 and count > 0 and offset + size * count <= len(data), 'Invalid ELF program headers')
    loads = 0
    for i in range(count):
        kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
        if kind == 1:
            loads += 1
            release.require(alignment >= 16384 and alignment & (alignment - 1) == 0 and (file_offset - address) % 16384 == 0, 'Native library is not 16 KB aligned')
    release.require(loads > 0, 'ELF has no LOAD segments')


def verify_manifest(text, version, code):
    root = ET.fromstring(text)
    release.require(root.tag == 'manifest' and root.get('package') == 'ai.boldkeys', 'Wrong bundle application ID')
    release.require(root.get(A + 'versionCode') == str(code) and root.get(A + 'versionName') == version, 'Wrong bundle version')
    release.require(len(root.findall('uses-sdk')) == 1 and len(root.findall('application')) == 1, 'Ambiguous bundle manifest')
    sdk, app = root.find('uses-sdk'), root.find('application')
    release.require(sdk is not None and sdk.get(A + 'minSdkVersion') == '33' and sdk.get(A + 'targetSdkVersion') == '36', 'Wrong bundle SDK compatibility')
    release.require(app is not None and app.get(A + 'debuggable', 'false') == 'false', 'Debuggable bundle forbidden')
    release.require(not any('EvalActivity' in e.get(A + 'name', '') or 'IconDumpActivity' in e.get(A + 'name', '') for e in root.iter()), 'Debug evaluation component forbidden')


def verify_bundle(path, version, code, signed):
    tool = Path(os.environ['BUNDLETOOL_JAR'])
    release.require(tool.is_file() and release.sha(tool) == BUNDLETOOL_SHA256, 'Unexpected bundletool binary')
    run('java', '-jar', tool, 'validate', '--bundle=' + str(path))
    verify_manifest(run('java', '-jar', tool, 'dump', 'manifest', '--bundle=' + str(path), '--module=base'), version, code)
    config = json.loads(run('java', '-jar', tool, 'dump', 'config', '--bundle=' + str(path)))
    release.require(config.get('optimizations', {}).get('uncompressNativeLibraries', {}).get('alignment') == 'PAGE_ALIGNMENT_16K', 'Bundle must request 16 KB APK alignment')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        release.require(len(names) == len(set(names)) and archive.testzip() is None, 'Duplicate or corrupt bundle ZIP entries')
        release.require([n for n in names if n.endswith('/manifest/AndroidManifest.xml')] == ['base/manifest/AndroidManifest.xml'], 'Only the base module is expected')
        native = [n for n in names if n.endswith('.so')]
        release.require(set(native) == {'base/lib/arm64-v8a/libcontextlm.so', 'base/lib/arm64-v8a/libandroidx.graphics.path.so'}, 'Unexpected native libraries or ABI')
        for name in native:
            elf_16k(archive.read(name))
        signatures = [n for n in names if re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF)', n, re.I)]
        release.require(bool(signatures) is signed, 'Unexpected bundle signature-file state')
    # JarFile verifies digests and exactly one pinned leaf signer on every payload, not just a cert listing.
    run('java', ROOT / 'tools/release/VerifyBundleSignature.java', path, 'signed' if signed else 'unsigned', release.CERT)


def metadata(directory, version, commit, code, signed, expected_hash=None, write=False):
    directory = Path(directory)
    manifest = directory / 'bundle-candidate.json'
    name = f'bold-keys-{version}.aab' if signed else 'unsigned.aab'
    if expected_hash is not None:
        release.require(release.sha(manifest) == expected_hash, 'Bundle manifest digest mismatch')
    if write:
        value = dict(format='aab', version=version, version_code=code, commit=commit, signed=signed,
                     aab=name, aab_sha256=release.sha(directory / name), notes_sha256=release.sha(directory / 'release-notes.md'))
        manifest.write_text(json.dumps(value, sort_keys=True) + '\n')
    value = json.loads(manifest.read_text())
    release.require(value.get('format') == 'aab' and value.get('version') == version and value.get('version_code') == code and value.get('commit') == commit and value.get('signed') is signed, 'Bundle provenance differs from approved inputs')
    release.require(value.get('aab') == name and value.get('aab_sha256') == release.sha(directory / name), 'Bundle digest mismatch')
    release.require(value.get('notes_sha256') == release.sha(directory / 'release-notes.md'), 'Bundle release notes changed')
    verify_bundle(directory / name, version, code, signed)
    checksum = directory / f'SHA256SUMS-AAB-{version}.txt'
    expected = f"{value['aab_sha256']}  {name}\n"
    if signed:
        if write:
            checksum.write_text(expected)
        release.require(checksum.read_text() == expected, 'Bundle checksum manifest mismatch')
    return release.sha(manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preflight', 'prepare', 'verify'])
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--code', type=int)
    parser.add_argument('--directory', default='candidate')
    parser.add_argument('--signed', action='store_true')
    parser.add_argument('--manifest-sha')
    args = parser.parse_args()
    release.version_tuple(args.version)
    release.require(re.fullmatch('[0-9a-f]{40}', args.commit), 'Invalid commit SHA')
    if args.command == 'preflight':
        code = release.preflight(args.version, args.commit, 'validate')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'version_code={code}\n')
    else:
        release.require(args.code is not None and args.code > 0, 'Version code required')
        release.require(args.command != 'verify' or bool(args.manifest_sha), 'Expected bundle manifest digest required')
        digest = metadata(args.directory, args.version, args.commit, args.code, args.signed,
                          args.manifest_sha, write=args.command == 'prepare')
        if args.command == 'prepare' and os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'manifest_sha={digest}\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f'Bundle check failed: {error}', file=sys.stderr)
        sys.exit(1)
