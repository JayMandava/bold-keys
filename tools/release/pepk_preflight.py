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
    release.require(record.get('schema') == 2, 'Unsupported PEPK review record')
    release.require(record.get('status') == 'blocked_pending_build_and_recipient_review', 'Unreviewed export activation forbidden')
    release.require(record.get('expected_certificate_sha256') == release.CERT, 'Production certificate pin mismatch')
    release.require(record.get('official_source_url') == SOURCE, 'Official source identity mismatch')
    release.require(record.get('tool_origin') == 'official_source_build', 'Reviewed source-built tool required')
    release.require(record.get('official_source_size_bytes') == 5268938, 'Official source size differs from audit')
    for field in ('official_source_sha256', 'source_built_tool_sha256', 'console_encryption_pem_sha256'):
        value = record.get(field)
        release.require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value), 'Missing reviewed source-built tool/Console PEM hashes; export unavailable')
    for field in ('build_recipe_review_id', 'source_licence_review_id', 'controlled_pty_protocol_review_id'):
        release.require(isinstance(record.get(field), str) and bool(record[field].strip()), 'Missing reviewed build recipe, licence or controlled PTY protocol')
    release.require(record.get('password_input_source_verified') is True, 'Controlled PTY password input has not been reviewed')
    release.require(record.get('encryption_and_output_source_verified') is True, 'PEPK encryption/output review is incomplete')
    release.require(record.get('export_implementation_reviewed') is True, 'Protected export implementation has not been reviewed')
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
