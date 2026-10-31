#!/usr/bin/env python3
"""Public-only immutable input bootstrap; no production credentials or package resolution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
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
