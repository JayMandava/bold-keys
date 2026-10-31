#!/usr/bin/env python3
"""PUBLIC, UNPRIVILEGED candidate rebuild/audit. NEVER updates approved export pins."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import zlib
import sysconfig
import zipfile

from pepk_build import ENV, ROOT, extract_source, validate_source_tree
from pepk_disposable_audit import audit
from pepk_runtime import python_env, tree_identity, digest
from pepk_policy import BUILD_SHA, SOURCE_SHA, require
from pepk_public import verify_public_file


def native_libraries(binary):
    result=subprocess.run(['/usr/bin/ldd',str(binary)],env=ENV,capture_output=True,timeout=10)
    require(result.returncode==0 and len(result.stdout)<=65536,'Native runtime dependency inventory failed')
    paths=set(re.findall(rb'(?:=> )?(/[A-Za-z0-9_./+\-]+)',result.stdout))
    return [{'path':str(Path(path.decode()).resolve(strict=True)),
             'sha256':digest(Path(path.decode()).resolve(strict=True))} for path in sorted(paths)]


def compiler_provenance(java):
    java=java.resolve(strict=True)
    result=subprocess.run([str(java),'-version'],env=ENV,capture_output=True,timeout=10)
    version=result.stderr.decode('utf-8',errors='strict')
    require(result.returncode==0 and ('version "21.' in version or 'version "21"' in version),'Candidate compiler must be Java21')
    modules=java.parent.parent/'lib/modules'
    def digest(path):
        with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
    library=Path(sys.executable).resolve().parent.parent/'lib'/sysconfig.get_config_var('LDLIBRARY')
    if not library.exists():library=Path(sysconfig.get_config_var('LIBDIR'))/sysconfig.get_config_var('LDLIBRARY')
    return {'java_path':str(java),'java_sha256':digest(java),'java_modules_sha256':digest(modules),
            'java_version':version,'python_path':str(Path(sys.executable).resolve()),
            'python_sha256':digest(Path(sys.executable).resolve()),'python_version':sys.version,
            'python_standard_library':tree_identity(Path(os.__file__).parent,('site-packages',)),
            'python_library':{'path':str(library.resolve()),'sha256':digest(library)},
            'zlib_binary':{'sha256':digest(Path(zlib.__file__)) if getattr(zlib,'__file__',None) else digest(library),
                           'identity':'extension module' if getattr(zlib,'__file__',None) else 'built into hash-recorded libpython'},
            'native_libraries':{'python':native_libraries(Path(sys.executable).resolve()),
                                'zlib':native_libraries(Path(zlib.__file__) if getattr(zlib,'__file__',None) else library)},
            'zlib_build':zlib.ZLIB_VERSION,'zlib_runtime':zlib.ZLIB_RUNTIME_VERSION,'platform':platform.platform(),
            'provisioner':os.environ.get('PUBLIC_COMPILER_PROVENANCE','local public audit; publisher provenance requires review')}


def candidate(source,work,java,record):
    require(not work.exists() and not any(c.isspace() for c in str(work.resolve())),'Candidate root must be new and whitespace-free')
    verify_public_file(source,SOURCE_SHA,record['official_source_size_bytes'])
    verify_public_file(ROOT/'build_pepk_local.py',BUILD_SHA)
    provenance=compiler_provenance(java)
    work.mkdir(mode=0o700)
    try:
        shutil.copyfile(source,work/'pepk-src.jar')
        extract_source(work/'pepk-src.jar',work/'source',record)
        original=(ROOT/'build_pepk_local.py').read_text()
        require(original.count("'/usr/bin/java'")==2,'Reviewed build recipe structure changed')
        # Explicit public candidate recipe delta: selected canonical compiler path only.
        derived=original.replace("'/usr/bin/java'",repr(str(java.resolve(strict=True))))
        script=work/'build_local.py';script.write_text(derived)
        hashes=[]
        for repeat in range(2):
            validate_source_tree(work/'source',record)
            subprocess.run([str(Path(sys.executable).resolve()),str(script)],cwd=work,env=(python_env(Path(os.environ['PEPK_CANDIDATE_PYTHON_HOME'])) if os.environ.get('PEPK_CANDIDATE_PYTHON_HOME') else ENV),
                           stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=240)
            jar=work/'local-build/pepk-local-source-build.jar'
            with zipfile.ZipFile(jar) as archive:
                require(len(archive.infolist())==record['class_files']+1,'Candidate class count differs')
            hashes.append(hashlib.sha256(jar.read_bytes()).hexdigest())
        require(hashes[0]==hashes[1],'Public candidate builds are not reproducible')
        identity=work/'identity';identity.mkdir()
        verify_public_file(ROOT/'PepkIdentity.java',record['identity_source_sha256'])
        subprocess.run([str(java.resolve()),'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8',
                        '-d',str(identity),str(ROOT/'PepkIdentity.java')],env=ENV,check=True,
                       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
        from pepk_stage import probe_console
        probe_console(java.resolve(),identity)
        identity_hash=digest(identity/'PepkIdentity.class')
        result=audit(jar,java,hashes[0])
        from pepk_driver_audit import audit_driver
        driver=audit_driver(jar,java,identity,hashes[0],record)
        runtime_report=None
        if os.environ.get('PEPK_CANDIDATE_PYTHON_HOME'):
            runtime_report=json.loads((Path(os.environ['PEPK_CANDIDATE_PYTHON_HOME']).parent/'runtime-input-provenance.json').read_text())
        report={'status':'UNPRIVILEGED_CANDIDATE_NOT_APPROVED_FOR_REAL_KEYS','source_sha256':SOURCE_SHA,
                'base_recipe_sha256':BUILD_SHA,'derived_recipe_sha256':hashlib.sha256(script.read_bytes()).hexdigest(),
                'recipe_delta':'canonical selected Java21 path replaces /usr/bin/java; flags/packaging unchanged',
                'candidate_tool_sha256':hashes[0],'prior_audit_tool_sha256':record['source_built_tool_sha256'],
                'matches_prior_audit':hashes[0]==record['source_built_tool_sha256'],
                'two_clean_builds_identical':True,'workflow_commit':os.environ.get('GITHUB_SHA'),
                'workflow_run_id':os.environ.get('GITHUB_RUN_ID'),'compiler_stack':provenance,'disposable_validation':result,
                'actual_export_driver_validation':driver,'identity_class_sha256':identity_hash,
                'runtime_installation':runtime_report,
                'next_step':'Independent review compiler provenance/recipe/hash/tests, then explicit protected pin update; no automatic promotion'}
        (work/'candidate-public-report.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
        return report
    except BaseException:
        shutil.rmtree(work)
        raise


if __name__=='__main__':
    import zipfile
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--java',type=Path,required=True)
    args=parser.parse_args()
    try:
        os.umask(0o077)
        record=json.loads((ROOT/'pepk-export-review.json').read_text())
        print(json.dumps(candidate(args.source.resolve(),args.work.absolute(),args.java,record),sort_keys=True))
    except BaseException:
        print('Unprivileged public candidate audit failed; no output hash was approved and no real keys were used.',file=sys.stderr)
        sys.exit(1)
