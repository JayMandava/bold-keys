#!/usr/bin/env python3
"""Fail-closed release checks. No signing credentials are consumed by this module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import data_pipeline

REPO = 'JayMandava/bold-keys'
ENVIRONMENT = 'production-release'
CERT = 'b237e1aec0bfdae579caebe1a0d97517af1b7c066adc4b3d197b641adfa46c34'
API = 'https://api.github.com'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        # API redirects are unexpected; never forward an Authorization header to another origin.
        return None


URL_OPENER = urllib.request.build_opener(NoRedirect)



def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def version_tuple(version):
    require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version), 'Use a stable X.Y.Z version without leading zeroes')
    return tuple(map(int, version.split('.')))


def source_version(text):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*([0-9]+)\s*$', text, re.M)
    require(len(names) == len(codes) == 1, 'Ambiguous application version metadata')
    version_tuple(names[0])
    require(int(codes[0]) > 0, 'Invalid versionCode')
    return names[0], int(codes[0])


def api(path, method='GET', data=None, absent_ok=False, binary=False):
    # Only GitHub's API origin receives the token. Do not accept arbitrary upload URLs.
    require(path.startswith('/') and not path.startswith('//'), 'Invalid API path')
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'bold-keys-release'}
    token = os.environ.get('GH_TOKEN')
    require(bool(token), 'GitHub token missing')
    headers['Authorization'] = 'Bearer ' + token
    if binary:
        body = data
        headers['Content-Type'] = 'application/octet-stream'
        origin = 'https://uploads.github.com'
    else:
        body = None if data is None else json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
        origin = API
    request = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
    try:
        with URL_OPENER.open(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if absent_ok and error.code == 404:
            return None
        raise ValueError(f'GitHub API {method} failed with HTTP {error.code}; no automatic overwrite/retry') from None


def gate():
    env = api(f'/repos/{REPO}/environments/{ENVIRONMENT}')
    require(env.get('can_admins_bypass') is False, 'Environment must disable admin bypass')
    policy = env.get('deployment_branch_policy') or {}
    require(policy.get('custom_branch_policies') is True and policy.get('protected_branches') is False, 'Environment must select main explicitly')
    rules = env.get('protection_rules', [])
    reviews = [rule for rule in rules if rule.get('type') == 'required_reviewers']
    require(len(reviews) == 1, 'Environment must require owner approval')
    reviewers = reviews[0].get('reviewers', [])
    require(len(reviewers) == 1 and reviewers[0].get('type') == 'User' and reviewers[0].get('reviewer', {}).get('login') == 'JayMandava', 'Environment reviewer must be JayMandava')
    require(reviews[0].get('prevent_self_review') is False, 'Use the explicitly approved sole-owner review policy')
    branches = api(f'/repos/{REPO}/environments/{ENVIRONMENT}/deployment-branch-policies')
    policies = branches.get('branch_policies', [])
    require(branches.get('total_count') == 1 and len(policies) == 1 and policies[0].get('name') == 'main' and policies[0].get('type') == 'branch', 'Environment must allow only main, not tags or patterns')


def preflight(version, commit, operation):
    version_tuple(version)
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Provide a full lowercase commit SHA')
    require(operation in ('validate', 'publish'), 'Invalid operation')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Manual dispatch required')
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'Wrong repository')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Run only from main')
    require(os.environ.get('GITHUB_SHA') == commit, 'Input commit must equal the dispatched main commit')
    require(os.environ.get('GITHUB_WORKFLOW_SHA') == commit, 'Workflow and source must have the same commit')
    require(api(f'/repos/{REPO}/git/ref/heads/main')['object']['sha'] == commit, 'Main moved; dispatch a new reviewed candidate')
    actual, code = source_version(Path('app/build.gradle.kts').read_text())
    require(actual == version, 'Input version differs from source')
    gate()  # Never reference a nonexistent environment: GitHub would create it unprotected.
    if operation == 'publish':
        require(api(f'/repos/{REPO}/git/ref/tags/v{version}', absent_ok=True) is None, 'Tag already exists; refusing overwrite')
        require(api(f'/repos/{REPO}/releases/tags/v{version}', absent_ok=True) is None, 'Release already exists; refusing overwrite')
        latest = api(f'/repos/{REPO}/releases/latest')
        old_tag = latest['tag_name']
        require(old_tag.startswith('v') and version_tuple(version) > version_tuple(old_tag[1:]), 'Version must increase from latest release')
        import base64
        old = api(f'/repos/{REPO}/contents/app/build.gradle.kts?ref={urllib.parse.quote(old_tag, safe="")}')
        old_version, old_code = source_version(base64.b64decode(old['content']).decode())
        require(old_version == old_tag[1:] and code > old_code, 'versionCode must increase from the latest released source')
    require(Path(f'docs/releases/{version}.md').is_file(), 'Reviewed versioned release notes missing')
    return code


def run(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(result.returncode == 0, 'APK verification command failed: ' + args[0])
    return result.stdout.strip()


def verify_apk(path, version, code, signed):
    sdk = Path(os.environ['ANDROID_HOME'])
    build_tools = sdk / 'build-tools/36.0.0'
    badging = run(str(build_tools / 'aapt'), 'dump', 'badging', str(path))
    require(re.search(r"^package: name='ai.boldkeys' versionCode='" + re.escape(str(code)) + r"' versionName='" + re.escape(version) + "'", badging, re.M), 'Wrong application ID/version')
    require(re.search(r"^sdkVersion:'33'$", badging, re.M) and re.search(r"^targetSdkVersion:'36'$", badging, re.M), 'Unexpected SDK compatibility')
    require(re.findall(r"^native-code: (.+)$", badging, re.M) == ["'arm64-v8a'"], 'APK must contain arm64-v8a only')
    analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    require(run(str(analyzer), 'manifest', 'debuggable', str(path)) == 'false', 'Debuggable APK forbidden')
    run(str(build_tools / 'zipalign'), '-c', '-P', '16', '4', str(path))
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, 'Corrupt APK')
        data_pipeline.verify_archive(archive, 'assets/')
        require({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')} == {'arm64-v8a'}, 'Unexpected native library ABI')
        if not signed:
            require(not any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$', name, re.I) for name in archive.namelist()), 'Unsigned candidate contains a JAR signature')
    if signed:
        output = run(str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path))
        certs = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', output, re.M)
        require(certs == [CERT], 'APK must have exactly the existing production signer')
        require('Verified using v2 scheme (APK Signature Scheme v2): true' in output, 'APK v2 signature required')
    else:
        require(b'APK Sig Block 42' not in Path(path).read_bytes(), 'Unsigned candidate contains APK signing block')
        result = subprocess.run([str(build_tools / 'apksigner'), 'verify', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        require(result.returncode != 0, 'Unsigned candidate unexpectedly verifies as signed')


def metadata(directory, version, commit, code, signed, expected_hash):
    directory = Path(directory)
    manifest = directory / 'candidate.json'
    if expected_hash:
        require(sha(manifest) == expected_hash, 'Candidate manifest digest mismatch')
    value = json.loads(manifest.read_text())
    apk_name = f'bold-keys-{version}.apk' if signed else 'unsigned.apk'
    require(value.get('version') == version and value.get('commit') == commit and value.get('version_code') == code and value.get('signed') is signed, 'Artifact provenance differs from validated job outputs')
    require(value.get('apk') == apk_name and sha(directory / apk_name) == value.get('apk_sha256'), 'APK digest mismatch')
    require(sha(directory / 'release-notes.md') == value.get('notes_sha256'), 'Release notes digest mismatch')
    verify_apk(directory / apk_name, version, code, signed)
    if signed:
        require((directory / f'SHA256SUMS-{version}.txt').read_text() == f"{value['apk_sha256']}  {apk_name}\n", 'Checksum manifest mismatch')
    return value


def write_metadata(directory, version, commit, code, signed):
    directory = Path(directory)
    apk_name = f'bold-keys-{version}.apk' if signed else 'unsigned.apk'
    value = dict(version=version, version_code=code, commit=commit, signed=signed, apk=apk_name, apk_sha256=sha(directory / apk_name), notes_sha256=sha(directory / 'release-notes.md'))
    (directory / 'candidate.json').write_text(json.dumps(value, sort_keys=True) + '\n')
    if signed:
        (directory / f'SHA256SUMS-{version}.txt').write_text(f"{value['apk_sha256']}  {apk_name}\n")
    metadata(directory, version, commit, code, signed, None)
    return sha(directory / 'candidate.json')


def publish(directory, version, commit, code, expected_hash):
    require(preflight(version, commit, 'publish') == code, 'Version code changed')
    directory = Path(directory)
    metadata(directory, version, commit, code, True, expected_hash)
    # Creation only. No force, clobber, deletion, or automatic recovery of partial releases.
    api(f'/repos/{REPO}/git/refs', 'POST', {'ref': f'refs/tags/v{version}', 'sha': commit})
    release = api(f'/repos/{REPO}/releases', 'POST', dict(tag_name=f'v{version}', target_commitish=commit, name=f'Bold Keys {version}', body=(directory / 'release-notes.md').read_text(), draft=True, prerelease=False))
    release_id = release['id']
    for name in (f'bold-keys-{version}.apk', f'SHA256SUMS-{version}.txt'):
        data = (directory / name).read_bytes()
        asset = api(f'/repos/{REPO}/releases/{release_id}/assets?name={urllib.parse.quote(name, safe="")}', 'POST', data, binary=True)
        require(asset.get('state') == 'uploaded' and asset.get('size') == len(data) and asset.get('digest') == 'sha256:' + hashlib.sha256(data).hexdigest(), 'Uploaded asset digest verification failed; draft left for manual recovery')
    require(api(f'/repos/{REPO}/git/ref/tags/v{version}')['object']['sha'] == commit, 'Published tag target changed')
    result = api(f'/repos/{REPO}/releases/{release_id}', 'PATCH', {'draft': False, 'make_latest': 'true'})
    require(result.get('draft') is False and result.get('tag_name') == f'v{version}', 'Publication outcome uncertain; inspect before retrying')
    print(result['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preflight', 'prepare', 'verify', 'publish'])
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--operation', choices=['validate', 'publish'], default='validate')
    parser.add_argument('--code', type=int)
    parser.add_argument('--directory', default='candidate')
    parser.add_argument('--signed', action='store_true')
    parser.add_argument('--manifest-sha')
    args = parser.parse_args()
    version_tuple(args.version)
    require(re.fullmatch('[0-9a-f]{40}', args.commit), 'Invalid commit SHA')
    if args.command == 'preflight':
        value = preflight(args.version, args.commit, args.operation)
        print(f'Validated {args.operation} candidate {args.version} ({value}) at {args.commit}')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'version_code={value}\n')
    else:
        require(args.code is not None and args.code > 0, 'Version code required')
        if args.command == 'prepare':
            digest = write_metadata(args.directory, args.version, args.commit, args.code, args.signed)
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                    output.write(f'manifest_sha={digest}\n')
        elif args.command == 'verify':
            require(bool(args.manifest_sha), 'Expected manifest digest required')
            metadata(args.directory, args.version, args.commit, args.code, args.signed, args.manifest_sha)
        else:
            require(bool(args.manifest_sha), 'Expected manifest digest required')
            publish(args.directory, args.version, args.commit, args.code, args.manifest_sha)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f'Release check failed: {error}', file=sys.stderr)
        sys.exit(1)
