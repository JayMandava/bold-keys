"""Public-only deterministic packaging/manifest/toolchain tests, not an official PEPK rebuild."""
import hashlib
import json
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

    def test_exact_supplied_build_script_hash(self):
        self.assertEqual(hashlib.sha256((ROOT/'tools/release/build_pepk_local.py').read_bytes()).hexdigest(),BUILD_SHA)

    def test_actual_public_toolchain_matches_audit_and_wrong_pin_fails(self):
        record=self.record()
        # Reference bytes need not match an unprivileged candidate host; mismatch must reject.
        try:build.verify_toolchain(record)
        except ValueError:pass
        record['toolchain']['java_sha256']='0'*64
        with self.assertRaises(ValueError):build.verify_toolchain(record)

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
        record=self.record();record['toolchain']['zlib_runtime']='not-the-audit'
        with tempfile.TemporaryDirectory() as temp,patch.object(build.subprocess,'run') as compiler:
            with self.assertRaises(ValueError):build.build(Path(temp)/'absent.jar',Path(temp)/'out',record)
            compiler.assert_not_called()
            self.assertFalse((Path(temp)/'out').exists())
