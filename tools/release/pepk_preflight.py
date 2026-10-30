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
    release.require(record.get('schema') == 1, 'Unsupported PEPK review record')
    release.require(record.get('status') == 'blocked_unverified_official_tool', 'Unreviewed export activation forbidden')
    release.require(record.get('expected_certificate_sha256') == release.CERT, 'Production certificate pin mismatch')
    release.require(record.get('official_source_url') == SOURCE, 'Official source identity mismatch')
    for field in ('official_source_sha256', 'console_tool_sha256', 'console_encryption_pem_sha256'):
        value = record.get(field)
        release.require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value), 'Missing reviewed official source/tool/Console PEM hashes; export unavailable')
    release.require(record.get('password_input_source_verified') is True, 'PEPK password input has not been verified')
    release.require(record.get('encryption_and_output_source_verified') is True, 'PEPK encryption/output behavior has not been verified')
    # Changing metadata can never turn this scaffold into a key exporter.
    raise ValueError('Export execution intentionally unimplemented; a separately audited implementation is required')


def preflight(commit):
    dispatch_identity(commit)
    release.require(release.api(f'/repos/{release.REPO}/git/ref/heads/main')['object']['sha'] == commit,
                    'Main moved; review and dispatch the new exact SHA')
    release.gate()  # Existing sole-owner approval/main-only/no-admin-bypass policy, unchanged.
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
