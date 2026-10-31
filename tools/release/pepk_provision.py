#!/usr/bin/env python3
"""Protected-runtime public preparation checks. No keys/tokens or approval mutation."""
import argparse
import json
import os
import re
from pathlib import Path
import sys

from pepk_build import verify_toolchain, reviewed_build_recipe
from pepk_policy import require
from pepk_public import verify_public_file
from pepk_java_runtime import verify_reviewed_java_runtime
from pepk_runtime import tree_identity, probe_python_child, enforce_runtime_identity, digest

ROOT=Path(__file__).resolve().parent


def check_host(record,environ=None,os_release=None):
    """Require trusted OS/architecture and immutable installer; image label is metadata."""
    env=os.environ if environ is None else environ
    expected=record['github_hosted_runtime']
    require(expected.get('host_contract')=='component-bytes-v1','Missing reviewed component host contract')
    for name,value in [('RUNNER_OS',expected['runner_os']),('RUNNER_ARCH',expected['runner_arch'])]:
        require(env.get(name)==value,'Hosted runner image/architecture differs from reviewed evidence')
    if os_release is None:
        os_release={}
        for line in Path('/etc/os-release').read_text().splitlines():
            if '=' in line:
                key,value=line.split('=',1);os_release[key]=value.strip('"')
    require(os_release.get('ID')==expected['os_id'] and os_release.get('VERSION_ID')==expected['os_version_id'],
            'Hosted operating system differs from reviewed evidence')
    verify_public_file(ROOT/'pepk-candidate-runtime.json',expected['installer_manifest_sha256'])


def safe_error_category(error):
    name=type(error).__name__
    allowed={'FileNotFoundError','PermissionError','OSError','UnicodeDecodeError',
             'ValueError','TypeError','KeyError','AttributeError','JSONDecodeError'}
    return name if name in allowed else 'OtherError'


def public_host_facts(record,environ=None,os_release=None):
    """Only public compared fields; never exception text, argv or an environment dump.

    This is a fresh readonly observation, not approval, enforcement or a pin update.
    """
    env=os.environ if environ is None else environ
    expected=(record or {}).get('github_hosted_runtime',{})
    if not isinstance(expected,dict):expected={}
    def safe(value):
        if value is None:return None
        return value if isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9_.-]{0,80}',value) else '<invalid>'
    checks={}
    def compared(name,observed,wanted):
        checks[name]={'observed':safe(observed),'expected':safe(wanted),'matches':observed==wanted,
                      'enforced':name!='ImageVersion'}
    for name,key in [('RUNNER_OS','runner_os'),('RUNNER_ARCH','runner_arch'),('ImageVersion','image_version')]:
        compared(name,env.get(name),expected.get(key))
    os_error=None
    if os_release is None:
        os_release={}
        try:
            for line in Path('/etc/os-release').read_text().splitlines():
                if '=' in line:
                    key,value=line.split('=',1);os_release[key]=value.strip('"')
        except Exception as read_error:os_error=safe_error_category(read_error)
    compared('os_release.ID',os_release.get('ID'),expected.get('os_id'))
    compared('os_release.VERSION_ID',os_release.get('VERSION_ID'),expected.get('os_version_id'))
    manifest_error=None;manifest_sha=None
    try:manifest_sha=digest(ROOT/'pepk-candidate-runtime.json')
    except Exception as read_error:manifest_error=safe_error_category(read_error)
    compared('installer_manifest_sha256',manifest_sha,expected.get('installer_manifest_sha256'))
    return {'checks':checks,'os_release_read_error':os_error,'manifest_read_error':manifest_error}


def public_host_diagnostics(record,error,environ=None,os_release=None):
    facts=public_host_facts(record,environ,os_release)
    checks=facts['checks']
    return {**facts,'observation':'public allowlist reread after check-host failure; no pin changes',
            'failure_category':safe_error_category(error),
            'first_nonmatching_public_field':next((key for key,value in checks.items() if value['enforced'] and not value['matches']),None),
            'system_python_version':'.'.join(map(str,sys.version_info[:3]))}


RUNTIME_PHASES={'host_metadata','toolchain','complete_jdk_tree','derived_recipe','python_build_child','java_components'}
RUNTIME_REASONS={
    'Public input SHA256 mismatch':'public_byte_pin_mismatch',
    'Reviewed public-input SHA256 is missing or invalid':'missing_public_byte_pin',
    'Public input must be a regular non-symlink file':'public_input_file_invalid',
    'Public input size mismatch':'public_input_size_mismatch',
    'Hosted runner image/architecture differs from reviewed evidence':'host_architecture_mismatch',
    'Hosted operating system differs from reviewed evidence':'host_os_mismatch',
    'Missing reviewed component host contract':'component_contract_missing',
    'Audit Java realpath differs; do not substitute a runner JDK':'java_realpath_mismatch',
    'Audit Python version differs':'python_version_mismatch',
    'Audit zlib stack differs; no alternate output hash is accepted':'zlib_version_mismatch',
    'Production Python/library tree differs from reviewed pins':'python_library_or_tree_mismatch',
    'Loaded production native library is unreviewed or changed':'python_native_identity_mismatch',
    'Production runtime is not the verified publisher installation':'python_installation_unverified',
    'Verified Python publisher archive unavailable or changed':'python_archive_cache_mismatch',
    'Selected Python bytes differ from publisher archive':'python_archive_member_mismatch',
    'Active interpreter differs from verified runtime prefix':'python_executable_selector_mismatch',
    'Invalid verified Python prefix':'python_selector_invalid',
    'Unsupported active loader injection configuration':'loader_injection_unsupported',
    'Actually loaded libpython differs from selected verified library':'loaded_libpython_mismatch',
    'Active loader path differs from verified runtime prefix':'python_loader_path_mismatch',
    'Reviewed shared host libz mapping missing':'python_host_libz_missing',
    'Complete installed JDK tree differs from reviewed publisher archive':'jdk_tree_mismatch',
    'Derived build recipe pin differs':'derived_recipe_mismatch',
    'Build-runtime child probe failed':'python_child_probe_failed',
    'Build child loaded a different libpython':'python_child_library_mismatch',
    'Java native dependency inventory failed or unresolved':'java_dependency_unresolved',
    'Public Java mapping probe failed':'java_mapping_probe_failed',
    'Java probe source/class differs from reviewed evidence':'java_probe_identity_mismatch',
    'Java native mapping/dependency unreviewed or changed':'java_native_identity_mismatch',
    'Reviewed Java host mapping missing or changed':'java_host_mapping_mismatch',
    'Reviewed Java resolved host dependency missing or changed':'java_host_dependency_mismatch',
    'Selected JVM mapping missing':'java_selected_jvm_missing',
    'Java mapped binary missing diagnostic dependency closure':'java_dependency_closure_incomplete',
}


def public_runtime_snapshot(record):
    """Read-only PUBLIC preparation facts. Fixed fields, reviewed component names/hashes.

    Unexpected mapping paths, arbitrary exception messages and environment values
    are never emitted. Java executes only after its static bytes/tree are verified.
    Snapshot observations are a reread, not proof of the original failure's timing.
    """
    import tempfile
    import zlib
    from pepk_runtime import loaded_native_libraries
    from pepk_java_runtime import java_runtime_evidence,java_pin_key,verify_java_native_files
    from pepk_policy import BUILD_SHA
    comparisons={};errors={}
    def safe_hash(value):
        return value if isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value) else None
    def capture(name,expected,operation):
        try:observed=safe_hash(operation())
        except Exception as error:observed=None;errors[name]=safe_error_category(error)
        wanted=safe_hash(expected)
        comparisons[name]={'expected':wanted,'observed':observed,'matches':wanted is not None and wanted==observed}
    pins=record.get('toolchain',{});host=record.get('github_hosted_runtime',{})
    contract=record.get('java_runtime_contract',{})
    java=None
    try:java=Path(pins['java_realpath']).resolve(strict=True)
    except Exception as error:errors['java_realpath']=safe_error_category(error)
    python=Path(sys.executable).resolve();home=python.parent.parent
    capture('installer_manifest',host.get('installer_manifest_sha256'),lambda:digest(ROOT/'pepk-candidate-runtime.json'))
    try:
        inputs=json.loads((ROOT/'pepk-candidate-runtime.json').read_text())
        capture('python_publisher_archive',inputs['python']['archive_sha256'],lambda:digest(home.parent/'inputs/python.tar.gz'))
        capture('jdk_publisher_archive',inputs['java']['archive_sha256'],lambda:digest(home.parent/'inputs/jdk.tar.gz'))
    except Exception as error:errors['installer_manifest_parse']=safe_error_category(error)
    capture('java_executable',pins.get('java_sha256'),lambda:digest(java))
    capture('java_modules',pins.get('modules_sha256'),lambda:digest(java.parent.parent/'lib/modules'))
    capture('complete_jdk_tree',host.get('java_tree_sha256'),lambda:tree_identity(java.parent.parent)['sha256'])
    capture('python_executable',pins.get('python_sha256'),lambda:digest(python))
    capture('python_library_file',pins.get('python_library_sha256'),lambda:digest(home/'lib/libpython3.12.so.1.0'))
    capture('python_standard_library',pins.get('python_standard_library_sha256'),lambda:tree_identity(Path(os.__file__).parent,('site-packages',))['sha256'])
    capture('python_packages',pins.get('python_packages_sha256'),lambda:tree_identity(Path(os.__file__).parent/'site-packages')['sha256'])
    capture('base_recipe',BUILD_SHA,lambda:digest(ROOT/'build_pepk_local.py'))
    capture('derived_recipe',record.get('derived_build_recipe_sha256'),lambda:__import__('hashlib').sha256((ROOT/'build_pepk_local.py').read_text().replace("'/usr/bin/java'",repr(str(java))).encode()).hexdigest())
    capture('java_probe_source',contract.get('probe_source_sha256'),lambda:digest(ROOT/'PepkRuntimeProbe.java'))
    def version(value):
        return value if isinstance(value,str) and re.fullmatch('[0-9.]{1,40}',value) else None
    versions={}
    for name,wanted,observed in [('python',pins.get('python_version'),'.'.join(map(str,sys.version_info[:3]))),
            ('zlib_build',pins.get('zlib_build'),zlib.ZLIB_VERSION),('zlib_runtime',pins.get('zlib_runtime'),zlib.ZLIB_RUNTIME_VERSION)]:
        versions[name]={'expected':version(wanted),'observed':version(observed),'matches':wanted==observed}
    def component_diff(expected,rows,key):
        observed={key(row['path']):safe_hash(row.get('sha256')) for row in rows}
        # Only names already in the reviewed public record may appear in output.
        changed=[{'reviewed_component_index':index,'expected':safe_hash(wanted),'observed':observed[name]}
                 for index,(name,wanted) in enumerate(expected.items()) if name in observed and wanted!=observed[name]]
        return {'changed':changed,'unreviewed_hashes':[sha for name,sha in observed.items() if name not in expected],
                'unobserved_reviewed_indices':[index for index,name in enumerate(expected) if name not in observed],
                'unobserved_reviewed_entries':sum(name not in observed for name in expected),
                'observed_count':len(observed),
                'scope':'indices follow public record insertion order; unobserved allowlist entries alone are not a failure'}
    native={};mapped=[]
    try:
        mapped=loaded_native_libraries()
        def python_key(path):
            path=Path(path)
            return 'python:'+path.relative_to(home).as_posix() if path.is_relative_to(home) else 'host:'+str(path)
        native['python']=component_diff(pins.get('native_library_pins',{}),mapped,python_key)
        native['loaded_libpython_hashes']=[safe_hash(row.get('sha256')) for row in mapped if Path(row['path']).name.startswith('libpython3.')]
    except Exception as error:errors['python_native_inventory']=safe_error_category(error)
    java_pins=contract.get('native_library_pins',{});java_rows=[]
    for index,(key,wanted) in enumerate(java_pins.items()):
        try:
            path=java.parent.parent/key[5:] if key.startswith('java:') else Path(key[5:])
            capture('java_static_'+str(index),wanted,lambda path=path:digest(path))
        except Exception as error:errors['java_static_'+str(index)]=safe_error_category(error)
    java_state={}
    try:
        # Never launch even the public Java probe on unreviewed static components.
        required=['java_executable','java_modules','complete_jdk_tree','java_probe_source']+['java_static_'+str(i) for i in range(len(java_pins))]
        if not all(comparisons.get(name,{}).get('matches') for name in required):
            errors['java_probe']='SkippedUnverifiedStaticBytes'
        else:
            verify_java_native_files(record,java)
            with tempfile.TemporaryDirectory(prefix='public-pepk-runtime-diagnostic-') as temp:
                evidence=java_runtime_evidence(java,Path(temp)/'probe',diagnostic_state=java_state)
            capture('java_probe_class',contract.get('probe_class_sha256'),lambda:evidence['probe_class_sha256'])
    except Exception as error:errors['java_probe']=safe_error_category(error)
    if java is not None:
        java_key=lambda path:java_pin_key(path,java.parent.parent)
        native['java_mapped']=component_diff(java_pins,java_state.get('loaded_native_libraries',[]),java_key)
        resolved={row['path']:row for rows in java_state.get('resolved_native_dependencies',{}).values() for row in rows}
        native['java_resolved']=component_diff(java_pins,list(resolved.values()),java_key)
        active=java_key(java_state['active_dependency_binary']) if java_state.get('active_dependency_binary') else None
        native['java_active_dependency_reviewed_index']=list(java_pins).index(active) if active in java_pins else None
        native['java_unresolved_library_names']=java_state.get('unresolved_library_names',[])
    return {'observation':'public read-only component reread after failure; not a secret-bearing diagnostic',
            'comparisons':comparisons,'versions':versions,'native_differences':native,'probe_errors':errors,
            'host':public_host_facts(record)}


def public_runtime_diagnostics(state,error):
    """Fixed phase/reason categories only; no values, paths, transcript or exception payload."""
    phase=state.get('phase') if isinstance(state,dict) else None
    return {'phase':phase if phase in RUNTIME_PHASES else 'unknown',
            'reason':RUNTIME_REASONS.get(str(error),'unclassified_failure'),
            'exception_category':safe_error_category(error)}


def verify_protected_runtime(record,diagnostic_state=None):
    # Optional public observer records only which existing check is executing.
    # Every check still executes, in the same order, and original failures propagate.
    def step(phase,operation):
        if diagnostic_state is not None:diagnostic_state['phase']=phase
        return operation()
    step('host_metadata',lambda:check_host(record))
    java=step('toolchain',lambda:verify_toolchain(record))
    step('complete_jdk_tree',lambda:require(
        tree_identity(java.parent.parent)['sha256']==record['github_hosted_runtime']['java_tree_sha256'],
        'Complete installed JDK tree differs from reviewed publisher archive'))
    step('derived_recipe',lambda:reviewed_build_recipe(java,record))
    step('python_build_child',lambda:enforce_runtime_identity(record['toolchain'],probe_python_child()))
    step('java_components',lambda:verify_reviewed_java_runtime(record,java))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-host',action='store_true')
    parser.add_argument('--verify-runtime',action='store_true')
    parser.add_argument('--public-runtime-diagnostics',action='store_true',
                        help='Unprivileged preparation only: fixed failure phase/reason categories')
    args=parser.parse_args()
    record=None;runtime_state={}
    try:
        require(args.check_host != args.verify_runtime,'Select exactly one preparation check')
        require(not args.public_runtime_diagnostics or args.verify_runtime,'Public runtime diagnostics require verify-runtime')
        record=json.loads((ROOT/'pepk-export-review.json').read_text())
        if args.check_host:check_host(record)
        elif args.public_runtime_diagnostics:verify_protected_runtime(record,runtime_state)
        else:verify_protected_runtime(record)
        print('Staged protected runtime/image checks passed; this does not approve export.')
    except Exception as error:
        if args.public_runtime_diagnostics and args.verify_runtime:
            print('Public runtime-check diagnostics: '+json.dumps(public_runtime_diagnostics(runtime_state,error),sort_keys=True),file=sys.stderr)
            try:
                print('Public runtime component snapshot: '+json.dumps(public_runtime_snapshot(record or {}),sort_keys=True),file=sys.stderr)
            except Exception as snapshot_error:
                print('Public runtime component snapshot unavailable: '+safe_error_category(snapshot_error),file=sys.stderr)
        if args.check_host:
            try:
                print('Public host-check diagnostics: '+json.dumps(public_host_diagnostics(record,error),sort_keys=True),file=sys.stderr)
            except Exception:
                print('Public host-check diagnostics unavailable; safe public probe failed.',file=sys.stderr)
        print('Protected runtime/image check failed closed; no secret use is authorized.',file=sys.stderr)
        sys.exit(1)
