"""Mocked export/opaque-ZIP validation. No actual PEPK or production key operations."""
import base64
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_export as e
SHA='a'*40
# Synthetic DER-shaped bytes are used ONLY for structural parser/hash fixtures, not X509 claims.
DER=b'synthetic public certificate parser fixture'
PEM=b'-----BEGIN CERTIFICATE-----\n'+base64.b64encode(DER)+b'\n-----END CERTIFICATE-----\n'
PIN=hashlib.sha256(DER).hexdigest()


def write_zip(path,entries=None):
    if entries is None:entries=[('encryptedPrivateKey',b'x'*(256+32)),('certificate.pem',PEM)]
    with zipfile.ZipFile(path,'w') as z:
        for name,data in entries:z.writestr(name,data)


class OpaqueOutputChecks(unittest.TestCase):
    def test_certificate_parser_rejects_extra_certificates_and_trailing_data(self):
        self.assertEqual(e.certificate_der(PEM),DER)
        for value in [PEM+PEM,PEM+b'private sentinel',b'not PEM',PEM.replace(b'CERTIFICATE',b'PRIVATE KEY')]:
            with self.assertRaises(ValueError):e.certificate_der(value)

    def test_exact_zip_allowlist_fingerprint_framing_and_no_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'opaque.zip';write_zip(path);e.validate_encrypted_zip(path,PIN,256)
            bad=[ [('encryptedPrivateKey',b'x'*288),('certificate.pem',PEM),('extra',b'x')],
                  [('encryptedPrivateKey',b'x'*287),('certificate.pem',PEM)],
                  [('encryptedPrivateKey',b'x'*288),('../certificate.pem',PEM)],
                  [('encryptedPrivateKey',b'x'*288),('certificate.pem',PEM+b'x')] ]
            for entries in bad:
                write_zip(path,entries)
                with self.assertRaises(Exception):e.validate_encrypted_zip(path,PIN,256)
            write_zip(path)
            with self.assertRaisesRegex(ValueError,'certificate pin'):e.validate_encrypted_zip(path,'0'*64,256)
            with zipfile.ZipFile(path,'w') as z:
                info=zipfile.ZipInfo('encryptedPrivateKey');info.external_attr=(stat.S_IFLNK|0o777)<<16
                z.writestr(info,b'x'*288);z.writestr('certificate.pem',PEM)
            with self.assertRaisesRegex(ValueError,'Unsupported'):e.validate_encrypted_zip(path,PIN,256)

    def test_duplicate_zip_entry_refused_and_existing_output_preserved(self):
        import warnings
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'opaque.zip'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore');write_zip(path,[('encryptedPrivateKey',b'x'*288),('certificate.pem',PEM),('certificate.pem',PEM)])
            with self.assertRaisesRegex(ValueError,'allowlist'):e.validate_encrypted_zip(path,PIN,256)
            folder=Path(temp)/'artifact';folder.mkdir();(folder/'unexpected').write_bytes(b'fixture')
            with self.assertRaisesRegex(ValueError,'allowlist'):e.exact_files(folder,e.EXPORT_NAMES)
            self.assertEqual((folder/'unexpected').read_bytes(),b'fixture')

    def env(self):
        return {'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REPOSITORY':'JayMandava/bold-keys',
                'GITHUB_REF':'refs/heads/main','GITHUB_SHA':SHA,'GITHUB_WORKFLOW_SHA':SHA,
                'PRIVATE_DELIVERY_CHECKED':'true','BOLD_RELEASE_KEYSTORE_BASE64':base64.b64encode(b'not-a-keystore fixture').decode(),
                'BOLD_RELEASE_STORE_PASSWORD':'Fixture-Store-Only!','BOLD_RELEASE_KEY_ALIAS':'fixture',
                'BOLD_RELEASE_KEY_PASSWORD':'Fixture-Key-Only!'}

    def run_mock_export(self,root,fail=None):
        payload=root/'public';payload.mkdir();output=root/'artifact';record={'console_encryption_pem_sha256':'b'*64}
        def identity(args,env):
            self.assertNotIn('BOLD_RELEASE_KEYSTORE_BASE64',env)
            self.assertNotIn('Fixture-Store-Only!',repr(args))
            self.assertNotIn('Fixture-Key-Only!',repr(args))
            Path(args[-2]).write_bytes(PEM)
            return None
        def terminal(argv,values,prompts,pins,**kwargs):
            self.assertNotIn('Fixture-Store-Only!',repr(argv));self.assertNotIn('Fixture-Key-Only!',repr(argv))
            self.assertEqual(prompts,[b"Enter password for store 'release.keystore':",b"Enter password for key 'fixture':"])
            path=Path(next(arg.split('=',1)[1] for arg in argv if arg.startswith('--output=')))
            write_zip(path)
            if fail=='controller':return dict(reason='mock-failure',exit_code=1,prompts_sent=0,no_password_echo=True,child_reaped=True)
            return dict(reason=None,exit_code=0,prompts_sent=2,no_password_echo=True,child_reaped=True)
        with patch.object(e,'validate_ready'),patch.object(e,'verify_payload',return_value=(Path('/fixture/java'),{})),patch.object(e,'recipient_bytes',return_value=256),patch.object(e,'run_identity_quiet',side_effect=identity),patch.object(e,'terminal_run',side_effect=terminal),patch.object(e,'CERT',PIN),patch.object(e.resource,'setrlimit'):
            if fail=='copy':
                with patch.object(e.shutil,'copyfile',side_effect=OSError('confidential fixture')):
                    with self.assertRaises(OSError):e.export_reviewed(payload,output,record,SHA,root,self.env())
            elif fail:
                with self.assertRaises(ValueError):e.export_reviewed(payload,output,record,SHA,root,self.env())
            else:e.export_reviewed(payload,output,record,SHA,root,self.env())
        self.assertFalse(list(root.glob('bold-pepk-private-*')))
        if fail:self.assertFalse(output.exists())
        return output,payload,record

    def test_mock_success_allows_only_encrypted_zip_public_cert_and_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            output,payload,record=self.run_mock_export(Path(temp))
            self.assertEqual({p.name for p in output.iterdir()},e.EXPORT_NAMES)
            content=b''.join(p.read_bytes() for p in output.iterdir())
            self.assertNotIn(b'Fixture-Store-Only!',content);self.assertNotIn(b'not-a-keystore fixture',content)
            with patch.object(e,'validate_ready'),patch.object(e,'verify_payload'),patch.object(e,'recipient_bytes',return_value=256),patch.object(e,'CERT',PIN):
                e.verify_export_output(output,payload,record,SHA)
                (output/'public-certificate.pem').write_bytes(PEM+b'bad')
                with self.assertRaisesRegex(ValueError,'checksums'):e.verify_export_output(output,payload,record,SHA)

    def test_failure_and_unexpected_exception_remove_partial_outputs(self):
        for mode in ['controller','copy']:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as temp:self.run_mock_export(Path(temp),mode)

    def test_identity_child_timeout_and_exception_are_bounded_and_reaped(self):
        import time
        import os
        original=e.subprocess.Popen
        children=[]
        def start(*args,**kwargs):
            child=original(*args,**kwargs);children.append(child);return child
        start_time=time.monotonic()
        with patch.object(e.subprocess,'Popen',side_effect=start):
            with self.assertRaisesRegex(ValueError,'timed out'):
                e.run_identity_quiet([str(Path('/usr/bin/python3').resolve()),'-c','import time;time.sleep(30)'],e.ENV,timeout=.1)
        self.assertLess(time.monotonic()-start_time,2.5)
        self.assertIsNotNone(children[0].poll())

    def test_sensitive_driver_imports_only_related_modules(self):
        text=(ROOT/'tools/release/pepk_export.py').read_text()
        for bad in ['import release','import data_pipeline','gradlew','curl ']:self.assertNotIn(bad,text)
        self.assertIn('validate_ready(record)',text.split('def export_reviewed')[1].split('values =')[0])
