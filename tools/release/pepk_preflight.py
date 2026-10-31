#!/usr/bin/env python3
"""Secret-free review scaffold. Deliberately cannot authorize or execute a key export."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

import release

RECORD = Path(__file__).with_name('pepk-export-review.json')
SOURCE = 'https://www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk-src.jar'


def dispatch_identity(commit):
    release.require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Expected full lowercase reviewed main SHA')
    expected = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': release.REPO,
                'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': commit, 'GITHUB_WORKFLOW_SHA': commit}
    for name, value in expected.items():
        release.require(os.environ.get(name) == value, 'Manual exact-main workflow identity required')


def validate_review_record(record):
    from pepk_policy import validate_ready
    validate_ready(record)


def preflight(commit):
    dispatch_identity(commit)
    release.require(release.api(f'/repos/{release.REPO}/git/ref/heads/main')['object']['sha'] == commit,
                    'Main moved; review and dispatch the new exact SHA')
    release.gate()  # Existing sole-owner approval/main-only/no-admin-bypass policy, unchanged.
    # Source stays public. Dedicated protected token checks the fixed private destination
    # after owner approval and BEFORE the separate step injects signing credentials.
    validate_review_record(json.loads(RECORD.read_text()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    try:
        preflight(args.commit)
    except (ValueError, OSError, KeyError) as error:
        print(f'PEPK export blocked: {error}', file=sys.stderr)
        sys.exit(1)
