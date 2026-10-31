#!/usr/bin/env python3
"""Public-only strict audit-stack rebuild. No network, signing secrets or alternative hashes."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import zipfile
import zlib

from pepk_public import verify_public_file
from pepk_runtime import python_child_env, verify_runtime_identities, probe_python_child, enforce_runtime_identity
from pepk_policy import BUILD_SHA, SOURCE_SHA, TOOL_SHA, require

ROOT = Path(__file__).resolve().parent
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}


def verify_toolchain(record):
    pins = record['toolchain']
    java = Path(pins['java_realpath']).resolve(strict=True)
    require(str(java) == pins['java_realpath'], 'Audit Java realpath differs; do not substitute a runner JDK')
    verify_public_file(java, pins['java_sha256'])
    verify_public_file(java.parent.parent / 'lib/modules', pins['modules_sha256'])
    verify_public_file(Path(sys.executable).resolve(), pins['python_sha256'])
    require('.'.join(map(str, sys.version_info[:3])) == pins['python_version'], 'Audit Python version differs')
    require(zlib.ZLIB_VERSION == pins['zlib_build'] and zlib.ZLIB_RUNTIME_VERSION == pins['zlib_runtime'],
            'Audit zlib stack differs; no alternate output hash is accepted')
    verify_runtime_identities(pins)
    return java


def source_manifest(root, paths, path_prefix=""):
    rows = []
    for path in sorted(paths, key=lambda p: p.relative_to(root).as_posix()):
        require(path.is_file() and not path.is_symlink(), 'Invalid extracted source file')
        rows.append(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path_prefix + path.relative_to(root).as_posix() + '\n')
    return hashlib.sha256(''.join(rows).encode('utf-8')).hexdigest()


def extract_source(archive, destination, record):
    verify_public_file(archive, SOURCE_SHA, record['official_source_size_bytes'])
    require(not destination.exists(), 'Source destination must be new')
    destination.mkdir(mode=0o700)
    try:
        with zipfile.ZipFile(archive) as source:
            members = source.infolist()
            require(len(members) <= 10000 and sum(m.file_size for m in members) <= 128 * 1024 * 1024,
                    'Source archive exceeds reviewed safety limits')
            names = set()
            for member in members:
                name = member.filename
                parts = PurePosixPath(name).parts
                require(name and name not in names and not name.startswith('/') and '\\' not in name
                        and all(p not in ('..', '.') for p in parts) and not any(ord(c) < 32 for c in name),
                        'Unsafe or duplicate source archive path')
                names.add(name)
                require(not stat.S_ISLNK(member.external_attr >> 16) and not member.flag_bits & 1
                        and member.file_size <= 8 * 1024 * 1024, 'Unsupported source archive entry')
                target = destination.joinpath(*parts)
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True, mode=0o700)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with target.open('xb') as output:
                        output.write(source.read(member))
                    target.chmod(0o600)
        validate_source_tree(destination, record)
    except BaseException:
        shutil.rmtree(destination)
        raise


def validate_source_tree(source, record):
    files = [p for p in source.rglob('*') if p.is_file()]
    java_files = [p for p in files if p.suffix == '.java']
    require(len(java_files) == record['java_sources'], 'Java source count differs')
    require(not any(any(c.isspace() for c in str(p)) for p in java_files), 'Audit compiler argfile cannot represent whitespace paths')
    require(source_manifest(source, files, 'source/') == record['complete_source_manifest_sha256'], 'Complete extracted-file manifest differs')
    for prefix, count, digest in record['group_manifests']:
        group = [p for p in java_files if p.relative_to(source).as_posix().startswith(prefix)]
        require(len(group) == count and source_manifest(source, group) == digest, 'Dependency-group source manifest differs')


def verify_built_jar(path, record):
    verify_public_file(path, TOOL_SHA)
    with zipfile.ZipFile(path) as jar:
        infos = jar.infolist()
        require(len(infos) == record['class_files'] + 1, 'Compiled class count differs')
        require(infos[0].filename == 'META-INF/MANIFEST.MF', 'Build manifest order differs')
        names = [i.filename for i in infos[1:]]
        require(names == sorted(names) and len(names) == len(set(names)) and all(n.endswith('.class') for n in names), 'Build class order differs')
        require(all(i.date_time == (1980,1,1,0,0,0) and i.compress_type == zipfile.ZIP_DEFLATED
                    and i.external_attr == 0o100644 << 16 for i in infos), 'Deterministic ZIP metadata differs')


def reviewed_build_recipe(java, record):
    """Preserve original recipe; any selected-compiler substitution requires its own pin."""
    verify_public_file(ROOT/'build_pepk_local.py', BUILD_SHA)
    original=(ROOT/'build_pepk_local.py').read_bytes()
    pin=record.get('derived_build_recipe_sha256')
    if pin is None:
        require(Path('/usr/bin/java').resolve(strict=True)==java, 'Selected compiler requires reviewed derived recipe pin')
        return original,BUILD_SHA
    text=original.decode('utf-8')
    require(text.count("'/usr/bin/java'")==2,'Reviewed base build recipe structure changed')
    derived=text.replace("'/usr/bin/java'",repr(str(java))).encode('utf-8')
    require(hashlib.sha256(derived).hexdigest()==pin, 'Derived build recipe pin differs')
    return derived,pin


def build(source_jar, destination, record):
    require(not any(c.isspace() for c in str(destination.resolve())), 'Build path must have no whitespace')
    require(not destination.exists(), 'Build destination must be new')
    verify_public_file(ROOT / 'build_pepk_local.py', BUILD_SHA)
    java=verify_toolchain(record)  # Still requires explicitly reviewed runtime byte pins.
    recipe,recipe_pin=reviewed_build_recipe(java,record)
    child_env=python_child_env()
    enforce_runtime_identity(record['toolchain'],probe_python_child())
    verify_public_file(source_jar, SOURCE_SHA, record['official_source_size_bytes'])
    destination.mkdir(mode=0o700)
    try:
        shutil.copyfile(source_jar, destination / 'pepk-src.jar')
        (destination/'build_local.py').write_bytes(recipe)
        extract_source(destination / 'pepk-src.jar', destination / 'source', record)
        for repeat in range(2):
            validate_source_tree(destination / 'source', record)
            verify_public_file(destination / 'build_local.py', recipe_pin)
            subprocess.run([str(Path(sys.executable).resolve()), str(destination / 'build_local.py')],
                           cwd=destination, env=child_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           check=True, timeout=240)
            verify_built_jar(destination / 'local-build/pepk-local-source-build.jar', record)
        classes = destination / 'identity'
        classes.mkdir(mode=0o700)
        verify_public_file(ROOT / 'PepkIdentity.java', record['identity_source_sha256'])
        subprocess.run([str(java),'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8',
                        '-d',str(classes),str(ROOT / 'PepkIdentity.java')], env=ENV,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=30)
        verify_public_file(classes / 'PepkIdentity.class', record['identity_class_sha256'])
        return destination / 'local-build/pepk-local-source-build.jar'
    except BaseException:
        shutil.rmtree(destination)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--verify-toolchain', action='store_true')
    args = parser.parse_args()
    try:
        record = json.loads((ROOT / 'pepk-export-review.json').read_text())
        if args.verify_toolchain:
            verify_toolchain(record)
            print('Strict audited Java/Python/zlib stack matches.')
        else:
            require(args.source is not None and args.destination is not None, 'Missing public build inputs')
            build(args.source, args.destination, record)
            print('Two strict audit-stack public builds match the reviewed source-built tool hash.')
    except Exception:
        print('Public PEPK rebuild failed closed; no alternative toolchain/output hash is accepted.', file=sys.stderr)
        sys.exit(1)
