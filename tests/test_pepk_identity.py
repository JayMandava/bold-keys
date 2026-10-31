"""Fresh local disposable JKS/identity tests; never PEPK or production signing inputs."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_export as exporter
import pepk_stage as stage
from pepk_terminal import ENV


class DisposableIdentityTests(unittest.TestCase):
    @classmethod
    def command(cls,args,env):
        result=subprocess.run(args,env=env,capture_output=True,timeout=30)
        if result.returncode:raise AssertionError('Disposable fixture command failed; confidential output suppressed')
        return result

    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='pepk-disposable-test-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root=Path(cls.temp.name);cls.classes=cls.root/'classes';cls.classes.mkdir()
        cls.java=str((Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))/'bin/java').resolve())
        cls.store=secrets.token_urlsafe(24);cls.password=secrets.token_urlsafe(24)
        cls.fixture_env=ENV|{'FIX_STORE':cls.store,'FIX_KEY':cls.password}
        cls.command([cls.java,'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8',
                     '-d',str(cls.classes),str(ROOT/'tools/release/PepkIdentity.java')],ENV)
        for name in ['a','b']:
            cls.command([str(Path(cls.java).parent/'keytool'),'-genkeypair','-keystore',str(cls.root/(name+'.jks')),
                         '-storetype','JKS','-storepass:env','FIX_STORE','-keypass:env','FIX_KEY',
                         '-alias','fixture','-keyalg','RSA','-keysize','2048','-dname','CN=Disposable Local Test',
                         '-validity','1','-noprompt'],cls.fixture_env)
            cls.command([str(Path(cls.java).parent/'keytool'),'-exportcert','-keystore',str(cls.root/(name+'.jks')),
                         '-storepass:env','FIX_STORE','-alias','fixture','-file',str(cls.root/(name+'.der'))],cls.fixture_env)
        cls.identity_env=ENV|{'BOLD_RELEASE_STORE_PASSWORD':cls.store,'BOLD_RELEASE_KEY_PASSWORD':cls.password,
                             'BOLD_RELEASE_KEY_ALIAS':'fixture'}
        # Deliberately mismatch disposable keyA/certB inside JKS to exercise proof-of-pair rejection.
        source=cls.root/'Mismatch.java'
        source.write_text('''import java.nio.file.*;import java.security.*;public class Mismatch {
public static void main(String[] a)throws Exception {char[] s=System.getenv("FIX_STORE").toCharArray();char[] p=System.getenv("FIX_KEY").toCharArray();
KeyStore x=KeyStore.getInstance("jks"),y=KeyStore.getInstance("jks");try(var i=Files.newInputStream(Path.of(a[0]))){x.load(i,s);}try(var i=Files.newInputStream(Path.of(a[1]))){y.load(i,s);}
var k=x.getKey("fixture",p);x.setKeyEntry("fixture",k,p,new java.security.cert.Certificate[]{y.getCertificate("fixture")});try(var o=Files.newOutputStream(Path.of(a[2]))){x.store(o,s);}}}''')
        cls.command([cls.java,str(source),str(cls.root/'a.jks'),str(cls.root/'b.jks'),str(cls.root/'mismatch.jks')],cls.fixture_env)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()
        if cls.root.exists():raise AssertionError('Disposable fixture cleanup failed')

    def invoke(self,store,pin,name,env=None):
        out=self.root/name
        result=subprocess.run([self.java,'-cp',str(self.classes),'PepkIdentity',str(self.root/store),str(out),pin],
                              env=self.identity_env if env is None else env,capture_output=True,timeout=10)
        for value in [self.store,self.password]:
            self.assertNotIn(value.encode(),result.stdout+result.stderr)
        return result,out

    def test_public_helper_class_is_pinned_and_actual_console_utf8(self):
        record=json.loads((ROOT/'tools/release/pepk-export-review.json').read_text())
        if hashlib.sha256(Path(self.java).read_bytes()).hexdigest()==record['toolchain']['java_sha256']:
            self.assertEqual(hashlib.sha256((self.classes/'PepkIdentity.class').read_bytes()).hexdigest(),record['identity_class_sha256'])
        else:
            self.assertEqual(len(hashlib.sha256((self.classes/'PepkIdentity.class').read_bytes()).hexdigest()),64)
        stage.probe_console(Path(self.java),self.classes)

    def test_selected_disposable_key_and_certificate_match(self):
        der=(self.root/'a.der').read_bytes();pin=hashlib.sha256(der).hexdigest()
        result,out=self.invoke('a.jks',pin,'success.pem')
        self.assertEqual(result.returncode,0)
        self.assertEqual(exporter.certificate_der(out.read_bytes()),der)

    def test_wrong_public_identity_or_private_password_fails_without_output(self):
        pin=hashlib.sha256((self.root/'a.der').read_bytes()).hexdigest()
        for i,override in enumerate([{'BOLD_RELEASE_STORE_PASSWORD':'incorrect-fixture'},
                                     {'BOLD_RELEASE_KEY_PASSWORD':'incorrect-fixture'}]):
            result,out=self.invoke('a.jks',pin,f'wrong-{i}.pem',self.identity_env|override)
            self.assertNotEqual(result.returncode,0);self.assertFalse(out.exists())
        result,out=self.invoke('a.jks','0'*64,'wrong-pin.pem')
        self.assertNotEqual(result.returncode,0);self.assertFalse(out.exists())

    def test_matched_fingerprint_but_mismatched_private_key_is_rejected(self):
        cert_b_pin=hashlib.sha256((self.root/'b.der').read_bytes()).hexdigest()
        result,out=self.invoke('mismatch.jks',cert_b_pin,'mismatch.pem')
        self.assertNotEqual(result.returncode,0);self.assertFalse(out.exists())

    def test_existing_public_certificate_is_not_overwritten(self):
        out=self.root/'existing.pem';out.write_bytes(b'public sentinel')
        pin=hashlib.sha256((self.root/'a.der').read_bytes()).hexdigest()
        result,out=self.invoke('a.jks',pin,'existing.pem')
        self.assertNotEqual(result.returncode,0);self.assertEqual(out.read_bytes(),b'public sentinel')
