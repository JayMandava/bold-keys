"""Actual export driver/final-verifier audit with GENERATED disposable identities only.

The production readiness gate and fixed identity constants are patched in this process
for fresh fixtures. Workflow/owner gates are NOT proved by this audit. Crypto, PTY,
identity challenge, payload pins, final verifier and cleanup execute real implementations.
Never accepts existing credentials, and never writes the production approval record.
"""
import base64
import copy
import hashlib
import json
from pathlib import Path
import secrets
import shutil
import tempfile
from unittest.mock import patch
import zipfile

import pepk_export as exporter
from pepk_disposable_audit import BASELINE_SOURCE, independent_unwrap
from pepk_policy import require, SOURCE_SHA
from pepk_terminal import ENV, GuardFailure


def audit_driver(jar, java, classes, tool_hash, original):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    java=java.resolve(strict=True);jar=jar.resolve(strict=True)
    checks=[];commit='a'*40
    with tempfile.TemporaryDirectory(prefix='public-pepk-driver-disposable-') as temp:
        root=Path(temp);root.chmod(0o700)
        password=secrets.token_urlsafe(24);key_password=secrets.token_urlsafe(24)
        fixture=ENV|{'FIXTURE_STORE':password,'FIXTURE_KEY':key_password}
        store=root/'DUMMY-ONLY.jks'
        exporter.run_identity_quiet([str(java.parent/'keytool'),'-genkeypair','-keystore',str(store),'-storetype','JKS',
            '-storepass:env','FIXTURE_STORE','-keypass:env','FIXTURE_KEY','-alias','dummy-only','-keyalg','RSA',
            '-keysize','2048','-dname','CN=Disposable Export Driver Test','-validity','1','-noprompt'],fixture)
        baseline_source=root/'FixtureBaseline.java';baseline_source.write_text(BASELINE_SOURCE)
        exporter.run_identity_quiet([str(java),str(baseline_source),str(store),str(root/'baseline.der'),str(root/'certificate.der')],fixture)
        baseline=serialization.load_der_private_key((root/'baseline.der').read_bytes(),None)
        fingerprint=hashlib.sha256((root/'certificate.der').read_bytes()).hexdigest()
        recipient=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        payload=root/'payload';payload.mkdir()
        shutil.copyfile(jar,payload/'pepk.jar');shutil.copyfile(classes/'PepkIdentity.class',payload/'PepkIdentity.class')
        (payload/'encryption-public-key.pem').write_bytes(recipient.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        (payload/'THIRD_PARTY_NOTICES.txt').write_text('Disposable fixture notice; not a distribution of the tool.\n')
        digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
        record=copy.deepcopy(original)
        record.update(identity_class_sha256=digest(payload/'PepkIdentity.class'),
            console_encryption_pem_sha256=digest(payload/'encryption-public-key.pem'),
            third_party_notices_sha256=digest(payload/'THIRD_PARTY_NOTICES.txt'),
            toolchain={'java_realpath':str(java),'java_sha256':digest(java),'modules_sha256':digest(java.parent.parent/'lib/modules')})
        (payload/'public-provenance.json').write_text(json.dumps({'commit':commit,'source_sha256':SOURCE_SHA,'tool_sha256':tool_hash,
            'pem_sha256':record['console_encryption_pem_sha256'],'identity_class_sha256':record['identity_class_sha256'],
            'notices_sha256':record['third_party_notices_sha256'],'console_charset':'UTF-8',
            'identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY'}))
        env={'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REPOSITORY':'JayMandava/bold-keys',
            'GITHUB_REF':'refs/heads/main','GITHUB_SHA':commit,'GITHUB_WORKFLOW_SHA':commit,'PRIVATE_DELIVERY_CHECKED':'true',
            'BOLD_RELEASE_KEYSTORE_BASE64':base64.b64encode(store.read_bytes()).decode(),
            'BOLD_RELEASE_STORE_PASSWORD':password,'BOLD_RELEASE_KEY_ALIAS':'dummy-only','BOLD_RELEASE_KEY_PASSWORD':key_password}
        def reject(operation, label):
            try:operation()
            except (ValueError,OSError,GuardFailure):checks.append(label)
            else:raise ValueError('Disposable driver negative check unexpectedly succeeded')
        # Readiness is deliberately synthetic only inside this disposable audit process.
        with patch.object(exporter,'validate_ready'),patch.object(exporter,'CERT',fingerprint),patch.object(exporter,'TOOL_SHA',tool_hash):
            encrypted=[]
            for i in range(2):
                output=root/f'output-{i}'
                exporter.export_reviewed(payload,output,record,commit,root,env);checks.append('actual-export-driver-success')
                exporter.verify_export_output(output,payload,record,commit);checks.append('actual-final-verifier-success')
                require({p.name for p in output.iterdir()}==exporter.EXPORT_NAMES,'Driver output allowlist changed');checks.append('driver-exact-output-allowlist')
                with zipfile.ZipFile(output/'encrypted-export.zip') as z:encrypted.append(z.read('encryptedPrivateKey'))
                require(independent_unwrap(encrypted[-1],recipient).private_numbers()==baseline.private_numbers(),'Driver independent crypto mismatch')
                checks.append('driver-independent-crypto-roundtrip')
                reject(lambda:exporter.export_reviewed(payload,output,record,commit,root,env),'driver-no-overwrite')
            require(encrypted[0]!=encrypted[1],'Driver ciphertext not randomized');checks.append('driver-randomized-ciphertext')
            for key in ('BOLD_RELEASE_STORE_PASSWORD','BOLD_RELEASE_KEY_PASSWORD'):
                output=root/'wrong-password'
                reject(lambda:exporter.export_reviewed(payload,output,record,commit,root,env|{key:'wrong-fixture-only'}),key+'-rejected')
                require(not output.exists() and not list(root.glob('bold-pepk-private-*')),'Driver private cleanup failed');checks.append('wrong-password-private-cleanup')
            reject(lambda:exporter.export_reviewed(payload,root/'bad-context',record,commit,root,env|{'PRIVATE_DELIVERY_CHECKED':'false'}),'driver-private-context-required')
            output=root/'output-0';original_cert=(output/'public-certificate.pem').read_bytes()
            (output/'public-certificate.pem').write_bytes(original_cert+b'bad')
            reject(lambda:exporter.verify_export_output(output,payload,record,commit),'final-verifier-tamper-rejected')
            (output/'public-certificate.pem').write_bytes(original_cert)
            helper=payload/'PepkIdentity.class';saved=helper.read_bytes();helper.write_bytes(saved+b'bad')
            reject(lambda:exporter.export_reviewed(payload,root/'bad-pin',record,commit,root,env),'driver-helper-pin-tamper-rejected')
            helper.write_bytes(saved)
        require(not list(root.glob('bold-pepk-private-*')),'Driver left private temporary directory');checks.append('driver-private-cleanup')
    require(not root.exists(),'Disposable audit fixture not removed');checks.append('all-driver-fixtures-removed')
    return {'checks_passed':len(checks),'checks':checks,'private_fixtures_removed':True,
            'scope':'actual driver/PTY/crypto/final verifier; synthetic readiness and fresh disposable identity only; not owner-gate or Google acceptance proof'}
