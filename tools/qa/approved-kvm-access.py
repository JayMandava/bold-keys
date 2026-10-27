#!/usr/bin/env python3
"""One explicitly approved disposable job: current runner UID only, exact ACL cleanup."""
import argparse
import grp
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import time
import urllib.parse
import urllib.request

DEVICE = '/dev/kvm'
REF = 'refs/heads/qa/iconic-colours-kvm-20261027-5'
BASE = ['user::rw-', 'group::rw-', 'other::---']


def run(*args):
    return subprocess.run(args, text=True, capture_output=True, check=True, timeout=30).stdout


def acl():
    return [line.strip() for line in run('getfacl', '-cpn', DEVICE).splitlines() if line.strip()]


def context():
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != 'JayMandava/bold-keys'
            or os.environ.get('GITHUB_REF') != REF or os.environ.get('GITHUB_RUN_ATTEMPT') != '1'
            or os.environ.get('GITHUB_EVENT_NAME') != 'push' or os.environ.get('GITHUB_ACTOR') != 'JayMandava'):
        raise RuntimeError('This approval is restricted to the initial owner-created QA job')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if event.get('created') is not True or event.get('deleted') or event.get('ref') != REF:
        raise RuntimeError('Only the first creation push is approved; no rerun or later push')
    uid = os.getuid()
    if uid == 0 or os.geteuid() != uid:
        raise RuntimeError('The emulator and controller must remain the normal non-root runner')
    return uid, os.environ['GITHUB_RUN_ID']


def metadata():
    if Path(DEVICE).is_symlink():
        raise RuntimeError('Unexpected virtualization-device symlink')
    st = os.stat(DEVICE)
    if not stat.S_ISCHR(st.st_mode):
        raise RuntimeError('Virtualization device is not a character device')
    return {'uid': st.st_uid, 'gid': st.st_gid, 'mode': stat.S_IMODE(st.st_mode), 'rdev': st.st_rdev}


def baseline():
    before = metadata()
    if before['uid'] != 0 or before['gid'] != grp.getgrnam('kvm').gr_gid or before['mode'] != 0o660 or acl() != BASE:
        raise RuntimeError('Unexpected baseline; do not broaden any existing ACL or permissions')
    return before


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n')


def verify_sole_run(run_id):
    # Public read only, no token. A deleted/recreated branch must not reuse this grant.
    query = urllib.parse.urlencode({'branch': REF.removeprefix('refs/heads/'), 'per_page': 100})
    url = 'https://api.github.com/repos/JayMandava/bold-keys/actions/workflows/colour-approved-kvm-qa-5.yml/runs?' + query
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'bold-keys-one-job-approval-guard'})
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    runs = data.get('workflow_runs', [])
    if (data.get('total_count') != 1 or len(runs) != 1 or str(runs[0].get('id')) != run_id
            or runs[0].get('run_attempt') != 1 or runs[0].get('head_sha') != os.environ['GITHUB_SHA']):
        raise RuntimeError('Approval is single-use; previous/ambiguous workflow history forbids a new grant')
    write(Path('qa-artifacts/one-job-approval.json'), {'run_id': run_id, 'head_sha': runs[0]['head_sha'],
        'matching_run_count': 1, 'run_attempt': 1, 'public_history_checked': True})


def stop_owned_emulator(state, uid):
    pidfile = state / 'emulator.pid'
    if not pidfile.exists():
        return
    pid = int(pidfile.read_text().strip())
    proc = Path('/proc') / str(pid)
    def status_now():
        try:
            return (proc / 'status').read_text()
        except FileNotFoundError:
            return None
    status = status_now()
    if status is None:
        return
    if '\nState:\tZ' in '\n' + status:
        return
    actual_uid = int(next(line for line in status.splitlines() if line.startswith('Uid:')).split()[1])
    try:
        command = (proc / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')
    except FileNotFoundError:
        return
    if actual_uid != uid or 'bold-approved-colours-35' not in command or 'qemu' not in command and 'emulator' not in command:
        raise RuntimeError('Refuse to signal an unrelated or differently owned process')
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    until = time.monotonic() + 10
    while time.monotonic() < until:
        status = status_now()
        if status is None or '\nState:\tZ' in '\n' + status:
            return
        time.sleep(.2)
    if status_now() is not None:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            return
        until = time.monotonic() + 5
        while time.monotonic() < until:
            status = status_now()
            if status is None or '\nState:\tZ' in '\n' + status:
                return
            time.sleep(.2)
        if proc.exists():
            raise RuntimeError('Owned emulator did not terminate; ACL still must be restored')


def grant(state, uid, run_id):
    record = state / 'state.json'
    if record.exists():
        raise RuntimeError('This job has already consumed its single grant attempt')
    before = baseline()
    state.mkdir(parents=True, exist_ok=True)
    # Intent is durable BEFORE sudo, so an uncertain/failed grant is still cleaned.
    data = {'uid': uid, 'run_id': run_id, 'before': before, 'phase': 'grant_intent'}
    write(record, data)
    run('sudo', '-n', 'setfacl', '--no-mask', '--modify', f'user:{uid}:rw-,mask::rw-', DEVICE)
    expected = ['user::rw-', f'user:{uid}:rw-', 'group::rw-', 'mask::rw-', 'other::---']
    if acl() != expected or metadata() != before or not os.access(DEVICE, os.R_OK | os.W_OK):
        raise RuntimeError('The exact runner-only grant was not verified')
    data['phase'] = 'active'
    write(record, data)
    write(Path('qa-artifacts/kvm-grant.json'), data | {'acl': expected})
    print(f'Approved temporary read/write access verified for runner UID {uid}; emulator remains non-root.')


def cleanup(state, uid, run_id):
    record = state / 'state.json'
    if not record.exists():
        print('No grant intent exists; no ACL mutation to clean.')
        return
    data = json.loads(record.read_text())
    if data['uid'] != uid or data['run_id'] != run_id:
        raise RuntimeError('Cleanup state does not belong to this exact approved job')
    stop_error = None
    try:
        stop_owned_emulator(state, uid)
    except Exception as error:
        stop_error = str(error)
    current = acl()
    permitted = set(BASE + [f'user:{uid}:rw-', 'mask::rw-'])
    if not set(current).issubset(permitted) or not set(BASE).issubset(current):
        # Never overwrite an unexpected principal's ACL. Remove only our grant.
        if any(line.startswith(f'user:{uid}:') for line in current):
            run('sudo', '-n', 'setfacl', '--no-mask', '--remove', f'user:{uid}', DEVICE)
        raise RuntimeError('Unexpected ACL mutation; runner grant removed, baseline requires investigation')
    if current != BASE:
        run('sudo', '-n', 'setfacl', '--set', ','.join(BASE), DEVICE)
    restored = acl() == BASE and metadata() == data['before']
    result = {'run_id': run_id, 'uid': uid, 'restored_exact_baseline': restored,
              'runner_grant_absent': not any(line.startswith(f'user:{uid}:') for line in acl()),
              'emulator_stop_error': stop_error, 'acl': acl(), 'metadata': metadata()}
    write(Path('qa-artifacts/kvm-cleanup.json'), result)
    if not restored or stop_error:
        raise RuntimeError('Cleanup verification failed: ' + json.dumps(result))
    data['phase'] = 'cleaned'
    write(record, data)
    print('PASS: owned emulator stopped; exact original ACL, owner, group and mode restored. Runner grant absent.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['check', 'grant', 'cleanup'])
    args = parser.parse_args()
    uid, run_id = context()
    state = Path(os.environ['RUNNER_TEMP']) / 'bold-approved-kvm-access'
    if args.operation == 'check':
        before = baseline()
        run('setfacl', '--version')
        write(Path('qa-artifacts/kvm-baseline.json'), {'uid': uid, 'run_id': run_id, 'baseline': before, 'acl': BASE})
    elif args.operation == 'grant':
        verify_sole_run(run_id)
        grant(state, uid, run_id)
    else:
        cleanup(state, uid, run_id)


if __name__ == '__main__':
    main()
