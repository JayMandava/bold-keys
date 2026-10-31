#!/usr/bin/env python3
"""One-time encrypted export driver; readiness gates run BEFORE any signing-secret read.
Only reviewed PEPK/identity code runs with key material. No network, app build or raw logs.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import signal
import time
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

from pepk_policy import CERT, SOURCE_SHA, TOOL_SHA, require, validate_ready, validate_execution_context
from pepk_public import verify_public_file
from pepk_runtime import verify_runtime_identities
from pepk_terminal import ENV, checked_label, terminal_run, verify_trusted_files, GuardFailure

ROOT = Path(__file__).resolve().parent
PAYLOAD_NAMES = {'pepk.jar', 'PepkIdentity.class', 'encryption-public-key.pem', 'public-provenance.json', 'THIRD_PARTY_NOTICES.txt'}
EXPORT_NAMES = {'encrypted-export.zip', 'public-certificate.pem', 'SHA256SUMS', 'export-provenance.json'}


def run_identity_quiet(argv, env, timeout=30, cleanup_timeout=2):
    """No captured confidential diagnostics; bounded polling/kill/reap on every exception."""
    child = None
    code = None
    try:
        child = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            code = child.poll()
            if code is not None:
                require(code == 0, 'Selected production identity verification failed')
                return
            time.sleep(.01)
        raise ValueError('Identity verification timed out')
    finally:
        if child is not None:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except OSError:
                pass
            deadline = time.monotonic() + cleanup_timeout
            while child.poll() is None and time.monotonic() < deadline:
                time.sleep(.005)
            require(child.poll() is not None, 'Identity process could not be reaped within cleanup bound')


def exact_files(directory, names):
    require(directory.is_dir() and not directory.is_symlink(), 'Invalid artifact directory')
    children = list(directory.iterdir())
    require({p.name for p in children} == names and all(p.is_file() and not p.is_symlink() for p in children),
            'Artifact allowlist mismatch')


def certificate_der(pem):
    require(len(pem) <= 16384, 'Certificate exceeds bound')
    match = re.fullmatch(rb'-----BEGIN CERTIFICATE-----\r?\n([A-Za-z0-9+/=\r\n]+)-----END CERTIFICATE-----\r?\n?', pem)
    require(match is not None, 'Expected exactly one PEM certificate')
    return base64.b64decode(re.sub(rb'[\r\n]', b'', match[1]), validate=True)


def recipient_bytes(path):
    # Public-only check; SPKI PUBLIC KEY is the source-audited recipient format.
    value = path.read_bytes()
    require(len(value) <= 16384 and re.fullmatch(rb'-----BEGIN PUBLIC KEY-----\r?\n[A-Za-z0-9+/=\r\n]+-----END PUBLIC KEY-----\r?\n?', value),
            'Invalid recipient PEM shape')
    result = subprocess.run(['/usr/bin/openssl','rsa','-pubin','-in',str(path),'-modulus','-noout'],
                            env=ENV, capture_output=True, timeout=10)
    require(result.returncode == 0 and len(result.stdout) <= 16384, 'Recipient RSA parsing failed')
    match = re.fullmatch(rb'Modulus=([0-9A-Fa-f]+)\r?\n?', result.stdout)
    require(match is not None and 2048 <= int(match[1],16).bit_length() <= 8192, 'Unsupported recipient RSA size')
    return (int(match[1],16).bit_length() + 7) // 8


def validate_encrypted_zip(path, expected_certificate, rsa_bytes):
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= 262144, 'Invalid export ZIP')
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        require(len(members) == 2 and {m.filename for m in members} == {'encryptedPrivateKey','certificate.pem'},
                'Encrypted ZIP entry allowlist mismatch')
        require(not archive.comment and all(not m.is_dir() and not m.flag_bits & 1
                and stat.S_IFMT(m.external_attr >> 16) in (0, stat.S_IFREG)
                and m.file_size <= 65536 and m.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                for m in members), 'Unsupported encrypted ZIP entry')
        encrypted = archive.read('encryptedPrivateKey')
        pem = archive.read('certificate.pem')
        require(len(encrypted) > rsa_bytes + 16 and (len(encrypted) - rsa_bytes) % 8 == 0,
                'Ciphertext framing differs from audited RSA/AES construction')
        require(hashlib.sha256(certificate_der(pem)).hexdigest() == expected_certificate,
                'Export certificate pin mismatch')
        require(archive.testzip() is None, 'Encrypted ZIP integrity failure')
    # Structural checks cannot decrypt Google's ciphertext or prove Google acceptance.


def verify_payload(payload, record, commit):
    verify_runtime_identities(record['toolchain'])  # Before any signing-secret lookup.
    exact_files(payload, PAYLOAD_NAMES)
    toolchain = record['toolchain']
    java = Path(toolchain['java_realpath']).resolve(strict=True)
    require(str(java) == toolchain['java_realpath'], 'Java runtime path differs from audit')
    pins = {str(java): toolchain['java_sha256'],
            str(java.parent.parent / 'lib/modules'): toolchain['modules_sha256'],
            str(payload / 'pepk.jar'): TOOL_SHA,
            str(payload / 'PepkIdentity.class'): record['identity_class_sha256'],
            str(payload / 'encryption-public-key.pem'): record['console_encryption_pem_sha256'],
            str(payload / 'THIRD_PARTY_NOTICES.txt'): record['third_party_notices_sha256']}
    verify_trusted_files(pins)
    provenance = json.loads((payload / 'public-provenance.json').read_text())
    require(provenance == {'commit':commit,'source_sha256':SOURCE_SHA,'tool_sha256':TOOL_SHA,
                          'pem_sha256':record['console_encryption_pem_sha256'],
                          'identity_class_sha256':record['identity_class_sha256'],
                          'notices_sha256':record['third_party_notices_sha256'],
                          'console_charset':'UTF-8','identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY'},
            'Public-input provenance mismatch')
    return java, pins


def export_reviewed(payload, output, record, commit, runner_temp, environ=None):
    validate_ready(record)  # Mandatory before environment secret lookup or private directory creation.
    require(re.fullmatch('[0-9a-f]{40}',commit) is not None, 'Invalid reviewed commit')
    env = os.environ if environ is None else environ
    validate_execution_context(commit, env)
    java, pins = verify_payload(payload, record, commit)
    rsa_bytes = recipient_bytes(payload / 'encryption-public-key.pem')
    require(runner_temp.is_absolute() and runner_temp.is_dir() and not runner_temp.is_symlink(), 'Invalid temporary root')
    require(output.is_absolute() and not output.exists() and not output.is_symlink(), 'Output destination must be new')
    owned_output = complete = False
    secrets = []
    try:
        names = ('BOLD_RELEASE_KEYSTORE_BASE64','BOLD_RELEASE_STORE_PASSWORD','BOLD_RELEASE_KEY_ALIAS','BOLD_RELEASE_KEY_PASSWORD')
        values = {name:env.get(name) for name in names}
        require(all(isinstance(v,str) and v for v in values.values()), 'Missing signing inputs')
        alias = checked_label(values['BOLD_RELEASE_KEY_ALIAS'])
        require(alias.lower() != 'androiddebugkey', 'Debug alias forbidden')
        for name in ('BOLD_RELEASE_STORE_PASSWORD','BOLD_RELEASE_KEY_PASSWORD'):
            encoded = bytearray(values[name].encode('utf-8'))
            require(0 < len(encoded) <= 253 and all(c >= 32 and c != 127 for c in encoded), 'Unsupported password input')
            secrets.append(encoded)
        require(len(values['BOLD_RELEASE_KEYSTORE_BASE64']) <= 14 * 1024 * 1024, 'Keystore input exceeds bound')
        # Only an encrypted keystore container is written, never a plaintext private-key serialization.
        container = bytearray(base64.b64decode(values['BOLD_RELEASE_KEYSTORE_BASE64'],validate=True))
        secrets.append(container)
        require(0 < len(container) <= 10 * 1024 * 1024, 'Invalid keystore container')
        os.umask(0o077)
        resource.setrlimit(resource.RLIMIT_CORE,(0,0))
        with tempfile.TemporaryDirectory(prefix='bold-pepk-private-',dir=runner_temp) as private:
            work = Path(private)
            keyfile = work / 'release.keystore'
            keyfile.write_bytes(container)
            keyfile.chmod(0o600)
            public_cert = work / 'certificate.pem'
            identity_env = ENV | {name:values[name] for name in names[1:]}
            # Related audited helper proves selected key/cert correspondence in memory and exports cert only.
            run_identity_quiet([str(java),'-cp',str(payload),'PepkIdentity',str(keyfile),str(public_cert),CERT],identity_env)
            require(public_cert.is_file(), 'Selected production identity certificate missing')
            require(hashlib.sha256(certificate_der(public_cert.read_bytes())).hexdigest() == CERT, 'Selected certificate mismatch')
            zipfile_path = work / 'encrypted-export.zip'
            argv = [str(java),'-jar',str(payload / 'pepk.jar'),f'--keystore={keyfile}',f'--alias={alias}',
                    f'--output={zipfile_path}','--include-cert','--rsa-aes-encryption',
                    f'--encryption-key-path={payload / "encryption-public-key.pem"}']
            prompts = [b"Enter password for store 'release.keystore':",
                       f"Enter password for key '{alias}':".encode('ascii')]
            state = terminal_run(argv,secrets[:2],prompts,pins,timeout=120)
            require(state == {'reason':None,'exit_code':0,'prompts_sent':2,'no_password_echo':True,'child_reaped':True},
                    'Bounded PEPK controller failed')
            validate_encrypted_zip(zipfile_path,CERT,rsa_bytes)
            output.mkdir(mode=0o700)
            owned_output = True
            shutil.copyfile(zipfile_path,output / 'encrypted-export.zip')
            shutil.copyfile(public_cert,output / 'public-certificate.pem')
            provenance = {'commit':commit,'certificate_sha256':CERT,'source_sha256':SOURCE_SHA,'tool_sha256':TOOL_SHA,
                          'recipient_pem_sha256':record['console_encryption_pem_sha256'],
                          'tool_identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY',
                          'validation':'key/certificate challenge verified; ZIP/certificate/framing checked; Google decrypt/import not verified'}
            (output / 'export-provenance.json').write_text(json.dumps(provenance,sort_keys=True,indent=2)+'\n')
            (output / 'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n'
                                                      for p in sorted(output.iterdir())))
            exact_files(output,EXPORT_NAMES)
            complete = True
    finally:
        for value in secrets:
            value[:] = b'\0' * len(value)
        # Python/Java environment strings/VM memory are not claimed to be fully zeroized.
        if owned_output and not complete:
            shutil.rmtree(output)


def verify_export_output(output, payload, record, commit):
    validate_ready(record)
    verify_payload(payload, record, commit)
    exact_files(output, EXPORT_NAMES)
    expected = {'commit':commit,'certificate_sha256':CERT,'source_sha256':SOURCE_SHA,'tool_sha256':TOOL_SHA,
                'recipient_pem_sha256':record['console_encryption_pem_sha256'],
                'tool_identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY',
                'validation':'key/certificate challenge verified; ZIP/certificate/framing checked; Google decrypt/import not verified'}
    require(json.loads((output/'export-provenance.json').read_text()) == expected, 'Export provenance mismatch')
    expected_sums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n'
                            for p in sorted(output.iterdir()) if p.name!='SHA256SUMS')
    require((output/'SHA256SUMS').read_text()==expected_sums, 'Export checksums mismatch')
    require(hashlib.sha256(certificate_der((output/'public-certificate.pem').read_bytes())).hexdigest()==CERT,
            'Public certificate pin mismatch')
    validate_encrypted_zip(output/'encrypted-export.zip', CERT, recipient_bytes(payload/'encryption-public-key.pem'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--commit',required=True)
    parser.add_argument('--verify-output',action='store_true')
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise GuardFailure('interrupted')
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupted)
    try:
        record = json.loads((ROOT / 'pepk-export-review.json').read_text())
        # Runner/root selectors are public; signing variables are read only inside the gated function.
        if args.verify_output:
            verify_export_output(args.output.absolute(),args.payload.resolve(),record,args.commit)
        else:
            export_reviewed(args.payload.resolve(),args.output.absolute(),record,args.commit,Path(os.environ['RUNNER_TEMP']))
        print('Verified encrypted export/public certificate allowlist; no Play upload performed.')
    except BaseException:
        print('Encrypted export failed closed; confidential diagnostics suppressed; no artifact is authorized.',file=sys.stderr)
        sys.exit(1)
