"""Offline public-runtime integrity/unsafe archive tests; no network or credentials."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_runtime as runtime


class RuntimeChecks(unittest.TestCase):
    def test_pinned_requirements_match_bootstrap_config(self):
        pins=json.loads((ROOT/'tools/release/pepk-candidate-runtime.json').read_text())
        expected=''.join(f"{a['package']}=={a['version']} --hash=sha256:{a['sha256']}\n" for a in pins['wheels'])
        self.assertEqual((ROOT/'tools/release/pepk-candidate-requirements.txt').read_text(),expected)
        self.assertEqual(pins['java']['version'],'21.0.13+9')
        self.assertEqual(pins['python']['archive_sha256'],'5a03168292516f6dd6dcf630f4bf5369b108abd7c699a7e1db37f1e622257fff')

    def test_cached_input_mismatch_is_not_redownloaded(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'fixture';p.write_bytes(b'public')
            with patch.object(runtime.urllib.request,'urlopen') as network:
                runtime.download('https://example.invalid',p,hashlib.sha256(b'public').hexdigest(),6)
                with self.assertRaises(ValueError):runtime.download('https://example.invalid',p,'0'*64,6)
                network.assert_not_called()

    def test_unsafe_python_archive_rejected_and_partial_prefix_removed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'archive.tar.gz';prefix=root/'prefix'
            with tarfile.open(archive,'w:gz') as tar:
                member=tarfile.TarInfo('../escape');member.size=1;tar.addfile(member,io.BytesIO(b'x'))
            with self.assertRaises(Exception):runtime.extract_python(archive,prefix)
            self.assertFalse(prefix.exists());self.assertFalse((root/'escape').exists())

    def test_tree_identity_tracks_bytes_and_symlink_targets(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=root/'a';p.write_bytes(b'a');link=root/'link';link.symlink_to('a')
            first=runtime.tree_identity(root);p.write_bytes(b'b')
            self.assertNotEqual(first,runtime.tree_identity(root))
            link.unlink();link.symlink_to('different')
            self.assertNotEqual(first,runtime.tree_identity(root))

    def test_isolated_env_never_inherits_signing_or_token_values(self):
        env=runtime.python_env(Path('/workspace/fixture-prefix'))
        self.assertEqual(set(env),{'PATH','LANG','LD_LIBRARY_PATH','PYTHONHOME','PYTHONDONTWRITEBYTECODE','PEPK_CANDIDATE_PYTHON_HOME'})

    def native_fixture(self, root):
        home=root/'python';lib=home/'lib/libpython3.12.so.1.0';lib.parent.mkdir(parents=True)
        binary=home/'bin/python3.12';binary.parent.mkdir()
        host=root/'host/libz.so.1';host.parent.mkdir()
        for p in (lib,binary,host):p.write_bytes(b'\x7fELF public native identity fixture '+p.name.encode())
        row=lambda p:{'path':str(p.resolve()),'sha256':runtime.digest(p)}
        identity={'python_home':str(home),'python_sha256':runtime.digest(binary),'python_library':row(lib),
                  'python_library_matches_publisher_archive':True,'loaded_native_libraries':[row(lib),row(binary),row(host)],
                  'loaded_host_libz':[row(host)],'python_standard_library':{'sha256':'1'*64},'python_packages':{'sha256':'2'*64}}
        return home,lib,binary,host,identity

    def test_actual_mapping_inventory_hashes_and_rejects_missing_replaced_or_deleted(self):
        import os
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);_,lib,_,_,_=self.native_fixture(root);stat=lib.stat()
            line=f'1000-2000 r-xp 0000 {os.major(stat.st_dev):02x}:{os.minor(stat.st_dev):02x} {stat.st_ino} {lib}\n'
            self.assertEqual(runtime.loaded_native_libraries(line),[{'path':str(lib),'sha256':runtime.digest(lib)}])
            for text in [line.replace(str(stat.st_ino),str(stat.st_ino+1)),line.rstrip()+' (deleted)\n','']:
                with self.assertRaises(ValueError):runtime.loaded_native_libraries(text)
            lib.unlink()
            with self.assertRaises(FileNotFoundError):runtime.loaded_native_libraries(line)

    def test_configured_libpython_requires_exact_live_path_and_hash_and_presence(self):
        import os
        with tempfile.TemporaryDirectory() as temp:
            home,lib,_,host,identity=self.native_fixture(Path(temp))
            expected={'lib/libpython3.12.so.1.0':identity['python_library']}
            variants=[[],[identity['python_library']|{'sha256':'0'*64}],
                      [{'path':str(host.parent/'libpython3.12.so.1.0'),'sha256':runtime.digest(lib)}]]
            with patch.object(runtime,'verified_python_home',return_value=(home,expected)),patch.dict(os.environ,{'LD_LIBRARY_PATH':str(home/'lib')}):
                for rows in variants:
                    with self.subTest(rows=rows),patch.object(runtime,'loaded_native_libraries',return_value=rows),self.assertRaisesRegex(ValueError,'loaded libpython'):
                        runtime.actual_python_runtime()
                with patch.object(runtime,'loaded_native_libraries',return_value=identity['loaded_native_libraries']):
                    self.assertTrue(runtime.actual_python_runtime()['python_library_matches_publisher_archive'])
                    with patch.dict(os.environ,{'LD_LIBRARY_PATH':str(host.parent)}),self.assertRaisesRegex(ValueError,'loader path'):
                        runtime.actual_python_runtime()

    def test_publisher_archive_rehash_and_extracted_libpython_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);home=root/'runtime/python';home.mkdir(parents=True);cache=home.parent/'inputs';cache.mkdir()
            archive=cache/'python.tar.gz'
            with tarfile.open(archive,'w:gz') as source:
                for name in ('bin/python3.12','lib/libpython3.12.so.1.0'):
                    data=b'\x7fELF verified public archive fixture '+name.encode();item=tarfile.TarInfo('./'+name);item.size=len(data)
                    source.addfile(item,io.BytesIO(data));p=home/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
            pins={'python':{'archive_bytes':archive.stat().st_size,'archive_sha256':runtime.digest(archive)}}
            (root/'pepk-candidate-runtime.json').write_text(json.dumps(pins))
            with patch.object(runtime,'ROOT',root):
                actual=runtime.archive_python_identity(home)
                self.assertEqual(actual['lib/libpython3.12.so.1.0']['sha256'],runtime.digest(home/'lib/libpython3.12.so.1.0'))
                (home/'lib/libpython3.12.so.1.0').write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError,'publisher archive'):runtime.archive_python_identity(home)
                archive.write_bytes(b'changed archive')
                with self.assertRaisesRegex(ValueError,'publisher archive'):runtime.archive_python_identity(home)

    def test_ldd_zero_exit_unresolved_dependency_is_rejected(self):
        import subprocess
        with patch.object(runtime,'diagnostic_env',return_value={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'}):
            for stream in ('stdout','stderr'):
                result=subprocess.CompletedProcess([],0,stdout=b'libx => /lib/x.so',stderr=b'')
                setattr(result,stream,b'libpython3.12.so => not found\n')
                with patch.object(runtime.subprocess,'run',return_value=result),self.assertRaisesRegex(ValueError,'unresolved'):
                    runtime.native_libraries(Path('/public-fixture'))

    def test_controlled_environments_never_inherit_arbitrary_loader_or_credentials(self):
        import os
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp)
            with patch.object(runtime,'verified_python_home',return_value=(home,{})),patch.dict(os.environ,{
                'LD_LIBRARY_PATH':'/untrusted/fixture','LD_PRELOAD':'never-executed-fixture','LD_AUDIT':'never-executed-fixture',
                'BOLD_RELEASE_STORE_PASSWORD':'synthetic-only','BOLD_PEPK_DELIVERY_TOKEN':'synthetic-only'}):
                env=runtime.diagnostic_env();child=runtime.python_child_env()
                self.assertEqual(env,{'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LD_LIBRARY_PATH':str(home/'lib')})
                self.assertEqual(child,runtime.python_env(home))
                for key in ('LD_PRELOAD','LD_AUDIT','BOLD_RELEASE_STORE_PASSWORD','BOLD_PEPK_DELIVERY_TOKEN'):
                    self.assertNotIn(key,env);self.assertNotIn(key,child)

    def test_child_probe_rejects_conflicting_or_missing_mapped_libpython(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temp:
            _,_,_,_,parent=self.native_fixture(Path(temp))
            with patch.object(runtime,'python_child_env',return_value={'PATH':'/usr/bin:/bin'}),patch.object(runtime,'actual_python_runtime',return_value=parent):
                for child in [parent|{'python_library':parent['python_library']|{'sha256':'0'*64}},
                              parent|{'python_library_matches_publisher_archive':False},
                              parent|{'python_library':None},parent|{'loaded_native_libraries':[]}]:
                    result=subprocess.CompletedProcess([],0,stdout=json.dumps(child).encode())
                    with patch.object(runtime.subprocess,'run',return_value=result),self.assertRaisesRegex(ValueError,'different libpython'):
                        runtime.probe_python_child()
                with patch.object(runtime.subprocess,'run',return_value=subprocess.CompletedProcess([],1,stdout=b'')),self.assertRaisesRegex(ValueError,'probe failed'):
                    runtime.probe_python_child()

    def test_production_enforcement_rejects_unreviewed_trees_libraries_or_missing_host_libz(self):
        import copy
        with tempfile.TemporaryDirectory() as temp:
            _,_,_,_,identity=self.native_fixture(Path(temp));pins=runtime.candidate_runtime_pins(identity)
            runtime.enforce_runtime_identity(pins,identity)
            for key in ('python_sha256','python_library_sha256','python_standard_library_sha256','python_packages_sha256'):
                with self.subTest(key=key),self.assertRaises(ValueError):runtime.enforce_runtime_identity(pins|{key:'0'*64},identity)
            for changed in [identity|{'python_library_matches_publisher_archive':False},identity|{'loaded_host_libz':[]}]:
                with self.assertRaises(ValueError):runtime.enforce_runtime_identity(pins,changed)
            changed=copy.deepcopy(identity);changed['loaded_native_libraries'][-1]['sha256']='0'*64
            with self.assertRaisesRegex(ValueError,'native library'):runtime.enforce_runtime_identity(pins,changed)
            with self.assertRaisesRegex(ValueError,'runtime identity pins'):runtime.enforce_runtime_identity({},identity)
