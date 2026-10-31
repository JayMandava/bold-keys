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


def verify_protected_runtime(record):
    check_host(record)
    java=verify_toolchain(record)  # Executable, modules, Python/zlib and live native/tree identities.
    require(tree_identity(java.parent.parent)['sha256']==record['github_hosted_runtime']['java_tree_sha256'],
            'Complete installed JDK tree differs from reviewed publisher archive')
    reviewed_build_recipe(java,record)  # Explicit derived-recipe hash, no compiler substitution.
    enforce_runtime_identity(record['toolchain'],probe_python_child())
    verify_reviewed_java_runtime(record,java)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-host',action='store_true')
    parser.add_argument('--verify-runtime',action='store_true')
    args=parser.parse_args()
    record=None
    try:
        require(args.check_host != args.verify_runtime,'Select exactly one preparation check')
        record=json.loads((ROOT/'pepk-export-review.json').read_text())
        if args.check_host:check_host(record)
        else:verify_protected_runtime(record)
        print('Staged protected runtime/image checks passed; this does not approve export.')
    except Exception as error:
        if args.check_host:
            try:
                print('Public host-check diagnostics: '+json.dumps(public_host_diagnostics(record,error),sort_keys=True),file=sys.stderr)
            except Exception:
                print('Public host-check diagnostics unavailable; safe public probe failed.',file=sys.stderr)
        print('Protected runtime/image check failed closed; no secret use is authorized.',file=sys.stderr)
        sys.exit(1)
