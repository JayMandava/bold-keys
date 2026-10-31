#!/usr/bin/env python3
"""Public-only immutable input bootstrap; no production credentials or package resolution."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT=Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()


def download(url,path,sha,size):
    if path.exists():
        if path.is_symlink() or path.stat().st_size!=size or digest(path)!=sha:raise ValueError('Public input cache pin mismatch')
        return
    part=path.with_suffix(path.suffix+'.part')
    try:
        with urllib.request.urlopen(url,timeout=120) as response,part.open('xb') as target:
            count=0
            while True:
                block=response.read(1024*1024)
                if not block:break
                count+=len(block)
                if count>size:raise ValueError('Public input size exceeds pin')
                target.write(block)
        if count!=size or digest(part)!=sha:raise ValueError('Public input digest/size mismatch')
        part.rename(path)
    finally:
        if part.exists():part.unlink()


def tree_identity(root,exclude=()):
    """All regular bytes and symlink targets; no timestamps/permissions or silent pyc exclusion."""
    rows=[];count=0
    for path in sorted(root.rglob('*')):
        rel=path.relative_to(root).as_posix()
        if any(rel==x or rel.startswith(x+'/') for x in exclude):continue
        if path.is_symlink():rows.append('link  '+rel+'  '+os.readlink(path)+'\n')
        elif path.is_file():rows.append(digest(path)+'  '+rel+'\n')
        else:continue
        count+=1
    return {'sha256':hashlib.sha256(''.join(rows).encode()).hexdigest(),'files_and_links':count,'exclusions':list(exclude)}


def python_env(home):
    return {'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LD_LIBRARY_PATH':str(home/'lib'),
            'PYTHONHOME':str(home),'PYTHONDONTWRITEBYTECODE':'1','PEPK_CANDIDATE_PYTHON_HOME':str(home)}


def archive_python_identity(home):
    """Derive interpreter/libpython expectations from the existing hash-pinned archive.

    No new pins are accepted here. The cached publisher archive must match the
    checked-in public installation record before any members are examined.
    """
    home=home.resolve(strict=True)
    pins=json.loads((ROOT/'pepk-candidate-runtime.json').read_text())['python']
    archive=home.parent/'inputs/python.tar.gz'
    if (archive.is_symlink() or not archive.is_file() or archive.stat().st_size!=pins['archive_bytes']
            or digest(archive)!=pins['archive_sha256']):
        raise ValueError('Verified Python publisher archive unavailable or changed')
    expectations={}
    with tarfile.open(archive) as source:
        names=('bin/python3.12','lib/libpython3.12.so.1.0')
        members={}
        for item in source.getmembers():
            normalized=PurePosixPath(item.name).as_posix()
            if normalized in names:
                if normalized in members:raise ValueError('Duplicate pinned Python archive member')
                members[normalized]=item
        for name in names:
            if name not in members:raise ValueError('Pinned Python archive member missing')
            member=members[name]
            if not member.isfile() or not 0<member.size<=64*1024*1024:
                raise ValueError('Unsupported pinned Python archive member')
            with source.extractfile(member) as stream:
                sha=hashlib.file_digest(stream,'sha256').hexdigest()
            path=(home/name).resolve(strict=True)
            if not path.is_relative_to(home) or not path.is_file() or digest(path)!=sha:
                raise ValueError('Selected Python bytes differ from publisher archive')
            expectations[name]={'path':str(path),'sha256':sha}
    return expectations


def verified_python_home():
    """Public runtime selector only; never trust an inherited loader search path."""
    selector=os.environ.get('PEPK_CANDIDATE_PYTHON_HOME')
    if selector is None:return None
    home=Path(selector)
    if not home.is_absolute() or home.is_symlink():raise ValueError('Invalid verified Python prefix')
    home=home.resolve(strict=True)
    expected=archive_python_identity(home)
    if Path(sys.executable).resolve(strict=True)!=Path(expected['bin/python3.12']['path']):
        raise ValueError('Active interpreter differs from verified runtime prefix')
    return home,expected


def python_child_env():
    selected=verified_python_home()
    return python_env(selected[0]) if selected else {'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'}


def diagnostic_env():
    """Keep only the archive-verified runtime lib directory; drop LD_PRELOAD etc."""
    selected=verified_python_home()
    env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'}
    if selected:env['LD_LIBRARY_PATH']=str(selected[0]/'lib')
    return env


def loaded_native_libraries(maps_text=None):
    """Actual file-backed executable ELF mappings in THIS process, without addresses.

    Check device/inode against the mapped identity, reject deleted/replaced files,
    and hash file bytes. Virtual mappings such as vdso have no file and are excluded.
    This is a file-identity inventory, not an in-memory integrity attestation.
    """
    if maps_text is None:
        with Path('/proc/self/maps').open() as stream:maps_text=stream.read(4*1024*1024+1)
    if len(maps_text)>4*1024*1024:raise ValueError('Native mapping inventory exceeds bound')
    found={}
    for line in maps_text.splitlines():
        fields=line.split(None,5)
        if len(fields)!=6 or 'x' not in fields[1] or not fields[5].startswith('/'):continue
        if fields[5].endswith(' (deleted)'):raise ValueError('Mapped native file was deleted')
        path=Path(fields[5]).resolve(strict=True)
        before=path.stat()
        major,minor=(int(value,16) for value in fields[3].split(':'))
        if (before.st_ino!=int(fields[4]) or os.major(before.st_dev)!=major or os.minor(before.st_dev)!=minor
                or not path.is_file()):raise ValueError('Mapped native file identity changed')
        if str(path) in found:continue
        with path.open('rb') as stream:
            if stream.read(4)!=b'\x7fELF':raise ValueError('Executable file mapping is not ELF')
            stream.seek(0);sha=hashlib.file_digest(stream,'sha256').hexdigest()
        after=path.stat()
        if (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns):
            raise ValueError('Mapped native file changed during inventory')
        found[str(path)]={'path':str(path),'sha256':sha}
        if len(found)>1024:raise ValueError('Too many mapped native files')
    if not found:raise ValueError('No file-backed native mappings available')
    return [found[key] for key in sorted(found)]


def actual_python_runtime():
    import sysconfig
    import zlib
    # Preload/audit hooks are never supported by this candidate execution path.
    if any(os.environ.get(key) for key in ('LD_PRELOAD','LD_AUDIT')):
        raise ValueError('Unsupported active loader injection configuration')
    selected=verified_python_home()
    if selected:
        home,expected=selected;library=expected['lib/libpython3.12.so.1.0']
        if os.environ.get('LD_LIBRARY_PATH')!=str(home/'lib'):
            raise ValueError('Active loader path differs from verified runtime prefix')
    else:
        path=Path(sys.executable).resolve().parent.parent/'lib'/sysconfig.get_config_var('LDLIBRARY')
        if not path.exists():path=Path(sysconfig.get_config_var('LIBDIR'))/sysconfig.get_config_var('LDLIBRARY')
        library={'path':str(path.resolve(strict=True)),'sha256':digest(path)}
    mapped=loaded_native_libraries()
    python_libraries=[row for row in mapped if Path(row['path']).name.startswith('libpython3.')]
    kind='mapped shared library'
    if not selected and not python_libraries:
        # Some local test hosts embed Python without a shared libpython mapping.
        # This is explicitly UNVERIFIED, and production enforcement rejects it.
        executable=Path(sys.executable).resolve(strict=True)
        library={'path':str(executable),'sha256':digest(executable)}
        kind='no shared libpython mapping observed; executable identity only, local unverified runtime'
    elif len(python_libraries)!=1 or python_libraries[0]!=library:
        raise ValueError('Actually loaded libpython differs from selected verified library')
    return {'python_library':library,'python_library_kind':kind,'python_library_matches_publisher_archive':selected is not None,
            'loaded_native_libraries':mapped,
            'loaded_host_libz':[row for row in mapped if re.fullmatch(r'libz\.so(?:\.[0-9]+)*',Path(row['path']).name)],
            'zlib_runtime':zlib.ZLIB_RUNTIME_VERSION,'mapped_scope':'file-backed executable ELF mappings; no in-memory integrity claim'}


def native_libraries(binary):
    """Diagnostic resolution only. Active process mappings are recorded separately."""
    result=subprocess.run(['/usr/bin/ldd',str(binary)],env=diagnostic_env(),capture_output=True,timeout=10)
    if result.returncode!=0 or len(result.stdout)+len(result.stderr)>65536 or b'not found' in result.stdout+result.stderr:
        raise ValueError('Native runtime dependency inventory failed or unresolved')
    paths=set(re.findall(rb'(?:=> )?(/[A-Za-z0-9_./+\-]+)',result.stdout))
    if not paths:raise ValueError('No diagnostic native dependencies resolved')
    return [{'path':str(Path(path.decode()).resolve(strict=True)),
             'sha256':digest(Path(path.decode()).resolve(strict=True))} for path in sorted(paths)]


def runtime_identity():
    identity=actual_python_runtime()
    identity['python_sha256']=digest(Path(sys.executable).resolve(strict=True))
    identity['python_standard_library']=tree_identity(Path(os.__file__).parent,('site-packages',))
    identity['python_packages']=tree_identity(Path(os.__file__).parent/'site-packages')
    selected=verified_python_home()
    identity['python_home']=str(selected[0]) if selected else str(Path(sys.executable).resolve().parent.parent)
    return identity


def native_pin_key(path, home):
    path=Path(path).resolve(strict=True);home=Path(home).resolve(strict=True)
    return 'python:'+path.relative_to(home).as_posix() if path.is_relative_to(home) else 'host:'+str(path)


def candidate_runtime_pins(identity):
    """Public review evidence/fixture data only; NEVER writes or promotes approved pins."""
    return {'python_sha256':identity['python_sha256'],'python_library_sha256':identity['python_library']['sha256'],
            'python_standard_library_sha256':identity['python_standard_library']['sha256'],
            'python_packages_sha256':identity['python_packages']['sha256'],
            'native_library_pins':{native_pin_key(row['path'],identity['python_home']):row['sha256']
                                   for row in identity['loaded_native_libraries']}}


def validate_runtime_pins(pins):
    for name in ('python_sha256','python_library_sha256','python_standard_library_sha256','python_packages_sha256'):
        if not isinstance(pins.get(name),str) or not re.fullmatch('[0-9a-f]{64}',pins[name]):
            raise ValueError('Missing reviewed production runtime identity pins')
    native=pins.get('native_library_pins')
    if (not isinstance(native,dict) or not native or any(not isinstance(k,str)
            or not k.startswith(('python:','host:/')) or not isinstance(v,str)
            or not re.fullmatch('[0-9a-f]{64}',v) for k,v in native.items())):
        raise ValueError('Missing reviewed production native-library pins')


def enforce_runtime_identity(pins,identity):
    validate_runtime_pins(pins)
    if identity.get('python_library_matches_publisher_archive') is not True:
        raise ValueError('Production runtime is not the verified publisher installation')
    observed=candidate_runtime_pins(identity)
    for name in ('python_sha256','python_library_sha256','python_standard_library_sha256','python_packages_sha256'):
        if pins[name]!=observed[name]:raise ValueError('Production Python/library tree differs from reviewed pins')
    if not identity.get('loaded_host_libz'):raise ValueError('Reviewed shared host libz mapping missing')
    for key,value in observed['native_library_pins'].items():
        if pins['native_library_pins'].get(key)!=value:
            raise ValueError('Loaded production native library is unreviewed or changed')


def verify_runtime_identities(pins):
    validate_runtime_pins(pins)  # Missing identities reject before archive/tree probes.
    identity=runtime_identity()
    enforce_runtime_identity(pins,identity)
    return identity


def probe_python_child():
    """Live build-environment child, using the exact verified-prefix Python selectors."""
    code=("import json,sys;sys.path.insert(0,sys.argv[1]);"
          "from pepk_runtime import runtime_identity;"
          "print(json.dumps(runtime_identity(),sort_keys=True))")
    result=subprocess.run([str(Path(sys.executable).resolve()),'-c',code,str(ROOT)],
                          env=python_child_env(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=30)
    if result.returncode or len(result.stdout)>262144:raise ValueError('Build-runtime child probe failed')
    identity=json.loads(result.stdout)
    parent=actual_python_runtime()
    if (not isinstance(identity,dict) or identity.get('python_library')!=parent['python_library'] or
            identity.get('python_library_matches_publisher_archive')!=parent['python_library_matches_publisher_archive'] or
            parent['python_library'] not in identity.get('loaded_native_libraries',[])):
        raise ValueError('Build child loaded a different libpython')
    return identity


def extract_python(archive,destination):
    if destination.exists():raise ValueError('Runtime prefix must be new')
    destination.mkdir(mode=0o700)
    try:
        with tarfile.open(archive) as source:source.extractall(destination,filter='data')
        destination.chmod(0o700)
        # setup.sh performs an unpinned network pip upgrade: it is intentionally never executed.
        executable=destination/'bin/python3.12'
        if not executable.is_file():raise ValueError('Expected pinned Python executable missing')
        return executable
    except BaseException:
        shutil.rmtree(destination);raise


def verify_java_archive(archive,java_home,archive_root):
    """Prove installed setup-java content matches the checksum-pinned publisher archive."""
    count=0
    with tarfile.open(archive) as source:
        members=source.getmembers()
        for member in members:
            rel=Path(member.name).relative_to(archive_root)
            path=java_home/rel
            if member.isfile():
                with source.extractfile(member) as stream:
                    expected=hashlib.file_digest(stream,'sha256').hexdigest()
                if not path.is_file() or path.is_symlink() or digest(path)!=expected:raise ValueError('Installed JDK differs from pinned archive')
                count+=1
            elif member.issym():
                if not path.is_symlink() or os.readlink(path)!=member.linkname:raise ValueError('JDK archive link differs')
    if count<100:raise ValueError('Incomplete JDK archive check')
    return tree_identity(java_home)


def prepare(root,java_home,pins,verify_java=True):
    if root.exists():raise ValueError('Runtime root must be new')
    root.mkdir(mode=0o700);cache=root/'inputs';cache.mkdir(mode=0o700)
    try:
        p=pins['python'];archive=cache/'python.tar.gz'
        download(p['archive_url'],archive,p['archive_sha256'],p['archive_bytes'])
        home=root/'python';python=extract_python(archive,home);env=python_env(home)
        requirements=root/'requirements.txt'
        requirements.write_text(''.join(f"{a['package']}=={a['version']} --hash=sha256:{a['sha256']}\n" for a in pins['wheels']))
        wheels=cache/'wheels';wheels.mkdir(mode=0o700)
        for a in pins['wheels']:download(a['url'],wheels/a['file'],a['sha256'],a['bytes'])
        # This archive contains pip. No ensurepip/network bootstrap or unpinned installer is run.
        subprocess.run([str(python),'-m','pip','--isolated','install','--no-index','--no-deps','--no-compile',
                        '--only-binary=:all:','--require-hashes','--find-links',str(wheels),'-r',str(requirements)],
                       env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=120)
        java_tree=None
        if verify_java:
            j=pins['java'];jdk=cache/'jdk.tar.gz';download(j['archive_url'],jdk,j['archive_sha256'],j['archive_bytes'])
            java_tree=verify_java_archive(jdk,java_home,j['archive_root'])
        report={'installer_inputs':pins,'python_standard_library':tree_identity(home/'lib/python3.12',('site-packages',)),
                'python_packages':tree_identity(home/'lib/python3.12/site-packages'),'java_tree':java_tree,
                'java_vendor_signature':'workflow requires pinned setup-java vendor signature verification; this helper verifies installed archive bytes, not signatures itself',
                'python_publisher':'checksum-pinned GitHub actions/python-versions release; no PSF signature asserted',
                'setup_script_executed':False,'network_package_resolution':False,'candidate_only':True}
        (root/'runtime-input-provenance.json').write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')
        return python,report
    except BaseException:
        shutil.rmtree(root);raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True);parser.add_argument('--java-home',type=Path,required=True)
    args=parser.parse_args()
    try:
        os.umask(0o077)
        pins=json.loads((ROOT/'pepk-candidate-runtime.json').read_text())
        python,report=prepare(args.root.absolute(),args.java_home.resolve(),pins)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'],'a') as out:
                out.write(f'python={python}\npython_home={python.parent.parent}\n')
        print('Public Python/wheels/JDK archive pins and installed runtime contents verified; no signing inputs accessed.')
    except Exception:
        print('Public runtime bootstrap failed closed; installation diagnostics suppressed.',file=sys.stderr);sys.exit(1)
