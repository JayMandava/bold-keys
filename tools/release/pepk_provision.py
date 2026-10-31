#!/usr/bin/env python3
"""Protected-runtime public preparation checks. No keys/tokens or approval mutation."""
import argparse
import json
import os
from pathlib import Path
import sys

from pepk_build import verify_toolchain, reviewed_build_recipe
from pepk_policy import require
from pepk_public import verify_public_file
from pepk_runtime import tree_identity, probe_python_child, enforce_runtime_identity

ROOT=Path(__file__).resolve().parent


def check_host(record,environ=None,os_release=None):
    """Reject image/installer drift before provisioning or injecting any secret step."""
    env=os.environ if environ is None else environ
    expected=record['github_hosted_runtime']
    for name,value in [('RUNNER_OS',expected['runner_os']),('RUNNER_ARCH',expected['runner_arch']),
                       ('ImageVersion',expected['image_version'])]:
        require(env.get(name)==value,'Hosted runner image/architecture differs from reviewed evidence')
    if os_release is None:
        os_release={}
        for line in Path('/etc/os-release').read_text().splitlines():
            if '=' in line:
                key,value=line.split('=',1);os_release[key]=value.strip('"')
    require(os_release.get('ID')==expected['os_id'] and os_release.get('VERSION_ID')==expected['os_version_id'],
            'Hosted operating system differs from reviewed evidence')
    verify_public_file(ROOT/'pepk-candidate-runtime.json',expected['installer_manifest_sha256'])


def verify_protected_runtime(record):
    check_host(record)
    java=verify_toolchain(record)  # Executable, modules, Python/zlib and live native/tree identities.
    require(tree_identity(java.parent.parent)['sha256']==record['github_hosted_runtime']['java_tree_sha256'],
            'Complete installed JDK tree differs from reviewed publisher archive')
    reviewed_build_recipe(java,record)  # Explicit derived-recipe hash, no compiler substitution.
    enforce_runtime_identity(record['toolchain'],probe_python_child())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-host',action='store_true')
    parser.add_argument('--verify-runtime',action='store_true')
    args=parser.parse_args()
    try:
        require(args.check_host != args.verify_runtime,'Select exactly one preparation check')
        record=json.loads((ROOT/'pepk-export-review.json').read_text())
        if args.check_host:check_host(record)
        else:verify_protected_runtime(record)
        print('Staged protected runtime/image checks passed; this does not approve export.')
    except Exception:
        print('Protected runtime/image check failed closed; no secret use is authorized.',file=sys.stderr)
        sys.exit(1)
