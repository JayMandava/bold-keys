#!/usr/bin/env python3
"""Fixed private ciphertext destination. No public artifacts, raw keys, or token logging.

API writes are attempted once. A failed/uncertain write is resolved only by readback;
a partial transfer is retained in private Git history, never described as erased.
"""
import argparse
import base64
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

from pepk_policy import require, validate_ready

TARGET = 'JayMandava/bold-keys-signing-delivery'
OWNER_ID = 202737776
API = 'https://api.github.com/repos/' + TARGET
NAMES = ('encrypted-export.zip', 'public-certificate.pem')
LIMITS = (262144, 16384)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Authenticated delivery redirect rejected')


class Client:
    def __init__(self, token):
        require(isinstance(token, str) and token.startswith('github_pat_') and len(token) <= 512
                and all(32 < ord(c) < 127 for c in token), 'Expected dedicated fine-grained delivery token')
        self._token = token
        self._opener = urllib.request.build_opener(NoRedirect())

    def request(self, suffix='', method='GET', body=None):
        require(suffix == '' or re.fullmatch(r'/contents/transfers/[0-9a-f]{40}/[0-9]+-[0-9]+/(encrypted-export.zip|public-certificate.pem)(\?ref=main)?', suffix),
                'Delivery request outside fixed allowlist')
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(API + suffix, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self._token, 'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json',
            'User-Agent': 'Bold-Keys-reviewed-private-ciphertext-delivery'})
        try:
            with self._opener.open(req, timeout=30) as response:
                raw = response.read(1048577)
                require(len(raw) <= 1048576, 'Delivery API response exceeds bound')
                return response.status, json.loads(raw), dict(response.headers)
        except urllib.error.HTTPError as error:
            # Never emit response bodies/headers/token/ciphertext or follow redirects.
            if error.code == 404:
                return 404, {}, dict(error.headers)
            raise ValueError('Private delivery API request rejected') from None
        except (OSError, ValueError):
            raise ValueError('Private delivery request failed or outcome uncertain') from None


def instant(value):
    require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:Z|\+00:00| UTC)', value),
            'Expected explicit UTC delivery token expiry')
    return datetime.fromisoformat(value.replace(' UTC', '+00:00').replace('Z', '+00:00').replace(' ', 'T'))


def destination(client, declared_expiry, now=None, expected_id=None):
    now = now or datetime.now(timezone.utc)
    status, repo, headers = client.request()
    require(status == 200 and repo.get('full_name') == TARGET and repo.get('private') is True
            and repo.get('visibility') == 'private' and repo.get('owner', {}).get('login') == 'JayMandava'
            and repo.get('owner', {}).get('id') == OWNER_ID and type(repo.get('id')) is int
            and repo['id'] > 0 and not repo.get('archived') and not repo.get('disabled')
            and repo.get('default_branch') == 'main' and repo.get('permissions', {}).get('push') is True,
            'Fixed private destination identity/visibility/write permission check failed')
    if expected_id is not None:
        require(repo['id'] == expected_id, 'Private destination repository identity changed')
    declared = instant(declared_expiry)
    require(now < declared <= now + timedelta(days=1), 'Declared delivery token expiry must be within one day')
    header = next((v for k, v in headers.items() if k.lower() == 'github-authentication-token-expiration'), None)
    expiry = declared
    evidence = 'owner-supplied UTC expiry; token scope/expiry must also be checked in secure account UI'
    if header:
        expiry = instant(header)
        require(now < expiry <= now + timedelta(days=1) and expiry <= declared,
                'API-reported delivery token expiry is expired or exceeds approved limit')
        evidence = 'API-reported expiry checked against owner-supplied upper bound'
    return {'repository': TARGET, 'repository_id': repo['id'], 'private': True,
            'expires_at': expiry.isoformat(), 'expiry_evidence': evidence}


def context(commit, run, attempt):
    require(re.fullmatch('[0-9a-f]{40}', commit) and re.fullmatch('[0-9]+', run)
            and re.fullmatch('[0-9]+', attempt), 'Invalid delivery run identity')
    return {'commit': commit, 'run_id': run, 'run_attempt': attempt}


def precheck(client, expiry, commit, run, attempt):
    return destination(client, expiry) | context(commit, run, attempt)


def deliver(client, expiry, proof, output, payload, record, commit, run, attempt):
    # This rechecks final verifier without any signing variables, then private visibility/expiry.
    from pepk_export import verify_export_output
    validate_ready(record)
    require(all(proof.get(k) == v for k, v in context(commit, run, attempt).items()), 'Delivery proof run mismatch')
    require(proof.get('private') is True and proof.get('repository') == TARGET, 'Private delivery proof missing')
    verify_export_output(output, payload, record, commit)
    destination(client, expiry, expected_id=proof['repository_id'])
    prefix = f'/contents/transfers/{commit}/{run}-{attempt}/'
    files = []
    for name, limit in zip(NAMES, LIMITS):
        path = output / name
        require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= limit, 'Delivery file outside allowlist/bounds')
        data = path.read_bytes()
        require(client.request(prefix + name + '?ref=main')[0] == 404, 'Delivery path already exists; no overwrite allowed')
        files.append((name, data))
    receipt = {'repository': TARGET, 'repository_id': proof['repository_id'], **context(commit, run, attempt), 'files': []}
    for name, data in files:
        # Privacy and expiry can change between requests: check immediately before each write.
        destination(client, expiry, expected_id=proof['repository_id'])
        uncertain = False
        try:
            status, _, _ = client.request(prefix + name, 'PUT', {
                'message': 'Store Google-encrypted existing-key transfer / public certificate',
                'branch': 'main', 'content': base64.b64encode(data).decode('ascii')})
            uncertain = status != 201
        except (ValueError, OSError):
            uncertain = True
        # Readback is safe even after uncertain PUT; NEVER retry a write.
        status, remote, _ = client.request(prefix + name + '?ref=main')
        expected_blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        require(status == 200 and remote.get('type') == 'file' and remote.get('sha') == expected_blob
                and remote.get('size') == len(data) and remote.get('encoding') == 'base64',
                'Private write/readback incomplete or uncertain; inspect private destination before any retry')
        try:
            remote_data = base64.b64decode(remote['content'].replace('\n', ''), validate=True)
        except (ValueError, KeyError):
            raise ValueError('Private readback encoding invalid') from None
        require(hashlib.sha256(remote_data).digest() == hashlib.sha256(data).digest(), 'Private readback digest mismatch')
        receipt['files'].append({'name': name, 'blob_sha': expected_blob,
                                 'sha256': hashlib.sha256(data).hexdigest(), 'uncertain_put_resolved_by_readback': uncertain})
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--proof', type=Path, required=True)
    parser.add_argument('--expiry', required=True)
    parser.add_argument('--deliver', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--payload', type=Path)
    args = parser.parse_args()
    try:
        os.umask(0o077)
        record = json.loads(Path(__file__).with_name('pepk-export-review.json').read_text())
        validate_ready(record)  # BEFORE token lookup; unchanged production-disabled gate.
        from pepk_policy import validate_execution_context
        validate_execution_context(args.commit, dict(os.environ, PRIVATE_DELIVERY_CHECKED='true'))
        client = Client(os.environ.get('BOLD_PEPK_DELIVERY_TOKEN'))
        run, attempt = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']
        if args.deliver:
            require(args.output is not None and args.payload is not None, 'Delivery inputs missing')
            result = deliver(client, args.expiry, json.loads(args.proof.read_text()), args.output, args.payload,
                             record, args.commit, run, attempt)
            args.proof.with_name('private-delivery-receipt.json').write_text(json.dumps(result, sort_keys=True) + '\n')
        else:
            require(not args.proof.exists(), 'Delivery proof must be new')
            result = precheck(client, args.expiry, args.commit, run, attempt)
            args.proof.write_text(json.dumps(result, sort_keys=True) + '\n')
            with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
                stream.write('verified=true\n')
        print('Fixed private destination verified; no public artifact uploaded. Revoke dedicated token after confirmed transfer.')
    except Exception:
        print('Private delivery blocked or partial; inspect private destination before retry. No confidential response logged.', file=sys.stderr)
        sys.exit(1)
