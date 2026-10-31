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
