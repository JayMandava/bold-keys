"""Public-only deterministic packaging/manifest/toolchain tests, not an official PEPK rebuild."""
import hashlib
import json
import os
import zlib
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_build as build
from pepk_policy import BUILD_SHA


class BuildChecks(unittest.TestCase):
    def record(self):return json.loads((ROOT/'tools/release/pepk-export-review.json').read_text())

    def selected_record(self):
        # In-memory fixture identities only, never a promotion of production pins.
        home=os.environ.get('JAVA_HOME')
        java=(Path(home)/'bin/java' if home else Path('/usr/bin/java')).resolve(strict=True)
        record=self.record()
        record['toolchain']={
            'java_realpath':str(java),'java_sha256':hashlib.sha256(java.read_bytes()).hexdigest(),
            'modules_sha256':hashlib.sha256((java.parent.parent/'lib/modules').read_bytes()).hexdigest(),
            'python_sha256':hashlib.sha256(Path(sys.executable).resolve().read_bytes()).hexdigest(),
            'python_version':'.'.join(map(str,sys.version_info[:3])),
            'zlib_build':zlib.ZLIB_VERSION,'zlib_runtime':zlib.ZLIB_RUNTIME_VERSION}
        return record

    def test_exact_supplied_build_script_hash(self):
        self.assertEqual(hashlib.sha256((ROOT/'tools/release/build_pepk_local.py').read_bytes()).hexdigest(),BUILD_SHA)

    def test_selected_public_toolchain_wrong_pin_fails_before_runtime_verification(self):
        record=self.selected_record();record['toolchain']['java_sha256']='0'*64
        with patch.object(build,'verify_runtime_identities') as runtime:
            with self.assertRaisesRegex(ValueError,'Public input SHA256 mismatch'):
                build.verify_toolchain(record)
            runtime.assert_not_called()

    def test_source_tree_manifest_and_groups_detect_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);a=root/'com/A.java';a.parent.mkdir();a.write_text('class A {}')
            b=root/'extra.txt';b.write_text('public notice')
            record={'java_sources':1,'complete_source_manifest_sha256':build.source_manifest(root,[a,b],'source/'),
                    'group_manifests':[['com/',1,build.source_manifest(root,[a])]]}
            build.validate_source_tree(root,record)
            b.write_text('changed')
            with self.assertRaisesRegex(ValueError,'Complete extracted'):build.validate_source_tree(root,record)

    def test_complete_manifest_uses_source_prefix_but_group_manifest_is_archive_relative(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);path=root/'A.java';path.write_bytes(b'class A {}\n')
            file_hash=hashlib.sha256(path.read_bytes()).hexdigest()
            full_hash=hashlib.sha256((file_hash+'  source/A.java\n').encode('utf-8')).hexdigest()
            group_hash=hashlib.sha256((file_hash+'  A.java\n').encode('utf-8')).hexdigest()
            self.assertNotEqual(full_hash,group_hash)
            self.assertEqual(build.source_manifest(root,[path],'source/'),full_hash)
            self.assertEqual(build.source_manifest(root,[path]),group_hash)
            build.validate_source_tree(root,{'java_sources':1,'complete_source_manifest_sha256':full_hash,
                                            'group_manifests':[['A.java',1,group_hash]]})

    def test_unsafe_archive_paths_symlinks_and_duplicates_cleanup(self):
        for mode in ['traversal','symlink','duplicate']:
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as temp:
                archive=Path(temp)/'source.jar';out=Path(temp)/'source'
                with zipfile.ZipFile(archive,'w') as jar:
                    if mode=='traversal':jar.writestr('../escape.java','x')
                    elif mode=='symlink':
                        info=zipfile.ZipInfo('link');info.external_attr=(stat.S_IFLNK|0o777)<<16;jar.writestr(info,'/tmp/elsewhere')
                    else:
                        jar.writestr('A.java','x')
                        import warnings
                        with warnings.catch_warnings():warnings.simplefilter('ignore');jar.writestr('A.java','y')
                digest=hashlib.sha256(archive.read_bytes()).hexdigest()
                with patch.object(build,'SOURCE_SHA',digest),self.assertRaises(ValueError):
                    build.extract_source(archive,out,{'official_source_size_bytes':archive.stat().st_size})
                self.assertFalse(out.exists())

    def test_unmodified_audit_script_packaging_repeats_on_small_public_fixture(self):
        # ONE harmless Java source, not the3613-source official tool; no claim of PEPK execution.
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'source').mkdir();(root/'source/Fixture.java').write_text('public class Fixture {}\n')
            with zipfile.ZipFile(root/'pepk-src.jar','w') as source:source.write(root/'source/Fixture.java','Fixture.java')
            shutil.copyfile(ROOT/'tools/release/build_pepk_local.py',root/'build_local.py')
            hashes=[]
            for repeat in range(2):
                result=subprocess.run([sys.executable,str(root/'build_local.py')],env=(__import__('pepk_runtime').python_env(Path(__import__('os').environ['PEPK_CANDIDATE_PYTHON_HOME'])) if __import__('os').environ.get('PEPK_CANDIDATE_PYTHON_HOME') else build.ENV),cwd=root,capture_output=True,timeout=30)
                self.assertEqual(result.returncode,0,'Public fixture compilation failed; output suppressed')
                jar=root/'local-build/pepk-local-source-build.jar';hashes.append(hashlib.sha256(jar.read_bytes()).hexdigest())
            self.assertEqual(hashes[0],hashes[1])
            with patch.object(build,'TOOL_SHA',hashes[0]):build.verify_built_jar(jar,{'class_files':1})
            with self.assertRaises(ValueError):build.verify_built_jar(jar,{'class_files':5990})

    def test_mismatched_stack_fails_before_source_or_compiler(self):
        record=self.selected_record();record['toolchain']['zlib_runtime']='not-the-audit'
        with tempfile.TemporaryDirectory() as temp,patch.object(build.subprocess,'run') as compiler:
            with self.assertRaisesRegex(ValueError,'Audit zlib stack differs'):build.build(Path(temp)/'absent.jar',Path(temp)/'out',record)
            compiler.assert_not_called()
            self.assertFalse((Path(temp)/'out').exists())

    def test_reviewed_compiler_selected_without_runner_default_substitution(self):
        import sys,zlib
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);java=root/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_bytes(b'public compiler fixture')
            modules=root/'jdk/lib/modules';modules.parent.mkdir();modules.write_bytes(b'public module fixture')
            record={'toolchain':{'java_realpath':str(java),'java_sha256':hashlib.sha256(java.read_bytes()).hexdigest(),
                    'modules_sha256':hashlib.sha256(modules.read_bytes()).hexdigest(),
                    'python_sha256':hashlib.sha256(Path(sys.executable).resolve().read_bytes()).hexdigest(),
                    'python_version':'.'.join(map(str,sys.version_info[:3])),'zlib_build':zlib.ZLIB_VERSION,'zlib_runtime':zlib.ZLIB_RUNTIME_VERSION}}
            with patch.object(build,'verify_runtime_identities') as runtime:
                self.assertEqual(build.verify_toolchain(record),java)
                runtime.assert_called_once_with(record['toolchain'])
            modules.write_bytes(b'changed')
            with patch.object(build,'verify_runtime_identities') as runtime,self.assertRaises(ValueError):build.verify_toolchain(record)
            runtime.assert_not_called()

    def test_selected_compiler_recipe_requires_explicit_derived_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            java=Path(temp)/'reviewed-java';java.write_bytes(b'public compiler fixture')
            with self.assertRaisesRegex(ValueError,'derived recipe pin'):build.reviewed_build_recipe(java,{})
            original=(ROOT/'tools/release/build_pepk_local.py').read_text()
            derived=original.replace("'/usr/bin/java'",repr(str(java))).encode()
            pin=hashlib.sha256(derived).hexdigest()
            self.assertEqual(build.reviewed_build_recipe(java,{'derived_build_recipe_sha256':pin}),(derived,pin))
            with self.assertRaisesRegex(ValueError,'pin differs'):build.reviewed_build_recipe(java,{'derived_build_recipe_sha256':'0'*64})

    def test_rejection_fixtures_work_with_debian_path_absent_and_github_jdk_layout(self):
        production_record=ROOT/'tools/release/pepk-export-review.json';before=production_record.read_bytes()
        selected=Path(self.selected_record()['toolchain']['java_realpath'])
        with tempfile.TemporaryDirectory() as temp:
            # Copy only the two public inputs these rejection tests inspect. No JDK
            # execution/key fixture is needed; bytes come from the selected runtime.
            home=Path(temp)/'hostedtoolcache/Java_Temurin-Hotspot_jdk/21/x64'
            (home/'bin').mkdir(parents=True);(home/'lib').mkdir()
            shutil.copyfile(selected,home/'bin/java')
            shutil.copyfile(selected.parent.parent/'lib/modules',home/'lib/modules')
            original_resolve=Path.resolve
            def missing_debian(path,*args,**kwargs):
                if str(path).startswith('/usr/lib/jvm/java-21-openjdk-amd64'):
                    raise FileNotFoundError('Debian reference JDK deliberately absent in runner fixture')
                return original_resolve(path,*args,**kwargs)
            with patch.dict(os.environ,{'JAVA_HOME':str(home)}),patch.object(Path,'resolve',missing_debian):
                self.test_selected_public_toolchain_wrong_pin_fails_before_runtime_verification()
                self.test_mismatched_stack_fails_before_source_or_compiler()
            self.assertEqual(production_record.read_bytes(),before)

    def test_missing_reviewed_compiler_never_substitutes_available_configured_jdk(self):
        record=self.selected_record();available_home=Path(record['toolchain']['java_realpath']).parent.parent
        with tempfile.TemporaryDirectory() as temp:
            record['toolchain']['java_realpath']=str(Path(temp)/'missing-reviewed-jdk/bin/java')
            with patch.dict(os.environ,{'JAVA_HOME':str(available_home)}),patch.object(build.subprocess,'run') as compiler,patch.object(build,'verify_runtime_identities') as runtime:
                with self.assertRaises(FileNotFoundError):build.verify_toolchain(record)
                compiler.assert_not_called();runtime.assert_not_called()
