"""Unprivileged fresh-fixture validation of a PUBLIC candidate JAR only.

Never accepts a keystore, password or signing environment input. All keys generated here
are disposable; private baseline bytes exist only inside the temporary fixture directory.
"""
import base64
import hashlib
from pathlib import Path
import secrets
import tempfile
import zipfile

from pepk_export import certificate_der, run_identity_quiet, validate_encrypted_zip
from pepk_policy import require
from pepk_terminal import ENV, terminal_run, verify_trusted_files

BASELINE_SOURCE = '''import java.nio.file.*;import java.security.*;
public class FixtureBaseline {public static void main(String[] a)throws Exception {
KeyStore s=KeyStore.getInstance("jks");try(var i=Files.newInputStream(Path.of(a[0]))){s.load(i,System.getenv("FIXTURE_STORE").toCharArray());}
var k=s.getKey("dummy-only",System.getenv("FIXTURE_KEY").toCharArray());
Files.write(Path.of(a[1]),k.getEncoded());Files.write(Path.of(a[2]),s.getCertificate("dummy-only").getEncoded());}}
'''


def independent_unwrap(encrypted, recipient):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.keywrap import aes_key_unwrap_with_padding
    size=(recipient.key_size+7)//8
    aes=recipient.decrypt(encrypted[:size],padding.OAEP(mgf=padding.MGF1(hashes.SHA1()),algorithm=hashes.SHA1(),label=None))
    require(len(aes)==32,'Disposable RSA unwrap did not produce AES256')
    plaintext=aes_key_unwrap_with_padding(aes,encrypted[size:])
    if plaintext.startswith(b'-----BEGIN'):
        return serialization.load_pem_private_key(plaintext,password=None)
    return serialization.load_der_private_key(plaintext,password=None)


def audit(jar, java, expected_tool_hash):
    import cryptography
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    java=java.resolve(strict=True);jar=jar.resolve(strict=True)
    pins={str(java):hashlib.sha256(java.read_bytes()).hexdigest(),
          str(java.parent.parent/'lib/modules'):hashlib.sha256((java.parent.parent/'lib/modules').read_bytes()).hexdigest(),
          str(jar):expected_tool_hash}
    verify_trusted_files(pins)
    checks=[]
    # No existing credential selector/input is accepted by this function.
    with tempfile.TemporaryDirectory(prefix='public-pepk-disposable-') as temp:
        root=Path(temp);root.chmod(0o700)
        store_pass=secrets.token_urlsafe(24);key_pass=secrets.token_urlsafe(24)
        fixture_env=ENV|{'FIXTURE_STORE':store_pass,'FIXTURE_KEY':key_pass}
        keyfile=root/'DUMMY-ONLY.jks'
        keytool=java.parent/'keytool'
        require(keytool.is_file(),'Candidate JDK keytool is missing')
        run_identity_quiet([str(keytool),'-genkeypair','-keystore',str(keyfile),'-storetype','JKS',
                            '-storepass:env','FIXTURE_STORE','-keypass:env','FIXTURE_KEY','-alias','dummy-only',
                            '-keyalg','RSA','-keysize','2048','-dname','CN=Disposable PEPK Candidate Test',
                            '-validity','1','-noprompt'],fixture_env)
        source=root/'FixtureBaseline.java';source.write_text(BASELINE_SOURCE)
        run_identity_quiet([str(java),str(source),str(keyfile),str(root/'baseline.der'),str(root/'certificate.der')],fixture_env)
        baseline=serialization.load_der_private_key((root/'baseline.der').read_bytes(),password=None)
        certificate=(root/'certificate.der').read_bytes();fingerprint=hashlib.sha256(certificate).hexdigest()
        recipient=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        pem=root/'recipient.pem';pem.write_bytes(recipient.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        prompts=[b"Enter password for store 'DUMMY-ONLY.jks':",b"Enter password for key 'dummy-only':"]
        values=[store_pass.encode(),key_pass.encode()]
        def invoke(path,passwords=values,recipient_path=pem,extra=()):
            argv=[str(java),'-jar',str(jar),f'--keystore={keyfile}','--alias=dummy-only',f'--output={path}',
                  '--include-cert','--rsa-aes-encryption',f'--encryption-key-path={recipient_path}',*extra]
            return terminal_run(argv,passwords,prompts,pins,timeout=30)
        ciphertext=[]
        for repeat in range(2):
            out=root/f'export-{repeat}.zip';result=invoke(out)
            require(result==dict(reason=None,exit_code=0,prompts_sent=2,no_password_echo=True,child_reaped=True),'Disposable PTY export failed')
            checks.append('ordered-echo-guarded-PTY-export')
            validate_encrypted_zip(out,fingerprint,384);checks.append('ZIP-allowlist-integrity-certificate')
            with zipfile.ZipFile(out) as z:
                encrypted=z.read('encryptedPrivateKey')
                require(certificate_der(z.read('certificate.pem'))==certificate,'Disposable exported certificate differs')
            decrypted=independent_unwrap(encrypted,recipient)
            require(decrypted.private_numbers()==baseline.private_numbers(),'Independent crypto round-trip differs')
            checks.append('independent-RSA-OAEP-AES5649-complete-private-numbers')
            ciphertext.append(encrypted)
        require(ciphertext[0]!=ciphertext[1],'Disposable encryption did not randomize');checks.append('randomized-ciphertext')
        sentinel=root/'existing.zip';sentinel.write_bytes(b'public fixture sentinel')
        require(invoke(sentinel)['reason'] is not None and sentinel.read_bytes()==b'public fixture sentinel','Existing output overwritten')
        checks.append('existing-output-not-overwritten')
        for name,passwords in [('wrong-store',[b'wrong-fixture-only',values[1]]),('wrong-key',[values[0],b'wrong-fixture-only'])]:
            out=root/(name+'.zip');require(invoke(out,passwords)['reason'] is not None and not out.exists(),'Wrong password did not fail closed')
            checks.append(name+'-fails-closed')
        malformed=root/'malformed.pem';malformed.write_bytes(b'not a public key')
        out=root/'malformed.zip';require(invoke(out,recipient_path=malformed)['reason'] is not None and not out.exists(),'Malformed recipient did not fail closed')
        checks.append('malformed-recipient-fails-closed')
        # No fixture key/cert/private baseline or raw terminal output leaves the context.
    require(not root.exists(),'Disposable fixture directory was not removed')
    import cryptography.hazmat.bindings._rust as rust
    from cryptography.hazmat.backends.openssl.backend import backend
    with Path(rust.__file__).open('rb') as stream:
        crypto_binary_sha256=hashlib.file_digest(stream,'sha256').hexdigest()
    return {'checks_passed':len(checks),'checks':checks,'cryptography_version':cryptography.__version__,
            'cryptography_backend_sha256':crypto_binary_sha256,'openssl_backend':backend.openssl_version_text(),
            'private_fixtures_removed':True,'scope':'fresh disposable keys only; not Google acceptance or production approval'}
