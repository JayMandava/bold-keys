"""Public probe checks, not production-key execution or a PEPK cryptographic audit."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_java_runtime as probe
from test_pepk_candidate import fixture_java


class JavaRuntimeChecks(unittest.TestCase):
    def test_live_selected_jvm_mapping_and_dependency_resolution(self):
        java=fixture_java()
        with tempfile.TemporaryDirectory() as temp:
            report=probe.java_runtime_evidence(java,Path(temp)/'probe')
        home=java.parent.parent
        self.assertFalse(report['public_tool_provider_loaded'])
        self.assertIn(str(home/'lib/server/libjvm.so'),[row['path'] for row in report['loaded_native_libraries']])
        self.assertTrue(report['mapped_host_libraries'])
        deps=report['resolved_native_dependencies'][str(home/'lib/server/libjvm.so')]
        self.assertTrue(any(Path(row['path']).name=='libdl.so.2' for row in deps))
        self.assertTrue(any(Path(row['path']).name=='librt.so.1' for row in deps))
        self.assertTrue(all(len(row['sha256'])==64 for row in deps))

    def test_missing_selected_jvm_mapping_rejected(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(probe,'loaded_native_libraries',return_value=[]):
            with self.assertRaisesRegex(ValueError,'JVM mapping missing'):
                probe.java_runtime_evidence(fixture_java(),Path(temp)/'probe')

    def test_unresolved_dependency_rejection_is_not_suppressed(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(probe,'java_native_dependencies',side_effect=ValueError('unresolved')):
            with self.assertRaisesRegex(ValueError,'unresolved'):
                probe.java_runtime_evidence(fixture_java(),Path(temp)/'probe')

    def test_probe_environment_does_not_inherit_loader_or_credentials(self):
        self.assertEqual(probe.ENV,{'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'})
        text=(ROOT/'tools/release/PepkRuntimeProbe.java').read_text()
        self.assertNotIn('getenv',text);self.assertNotIn('KeyStore',text)

    def test_nonzero_or_excessive_probe_output_rejected(self):
        from subprocess import CompletedProcess
        for result in (CompletedProcess([],1,b'',b''),CompletedProcess([],0,b'x'*(4*1024*1024+1),b'')):
            with self.subTest(code=result.returncode),tempfile.TemporaryDirectory() as temp,patch.object(probe.subprocess,'run',side_effect=[CompletedProcess([],0),result]):
                with self.assertRaisesRegex(ValueError,'mapping probe failed'):
                    probe.java_runtime_evidence(fixture_java(),Path(temp)/'probe')

    def test_probe_child_uses_only_fixed_environment(self):
        from subprocess import CompletedProcess
        with tempfile.TemporaryDirectory() as temp,patch.object(probe.subprocess,'run',side_effect=[CompletedProcess([],0),CompletedProcess([],1,b'',b'')]) as run:
            with self.assertRaises(ValueError):probe.java_runtime_evidence(fixture_java(),Path(temp)/'probe')
        for call in run.call_args_list:
            self.assertEqual(call.kwargs['env'],{'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'})

    def test_diagnostic_loader_path_is_selected_jdk_only_and_missing_rejected(self):
        from subprocess import CompletedProcess
        home=fixture_java().parent.parent
        with patch.object(probe.subprocess,'run',return_value=CompletedProcess([],0,b'libmissing.so => not found\n',b'')) as run:
            with self.assertRaisesRegex(ValueError,'unresolved'):
                probe.java_native_dependencies(home/'lib/libjava.so',home)
        self.assertEqual(run.call_args.kwargs['env'],probe.ENV|{'LD_LIBRARY_PATH':str(home/'lib/server')+':'+str(home/'lib')})
