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

    def test_live_fixture_contract_is_enforced_without_promoting_production_pins(self):
        import json
        before=(ROOT/'tools/release/pepk-export-review.json').read_bytes()
        java=fixture_java()
        with tempfile.TemporaryDirectory() as temp:
            evidence=probe.java_runtime_evidence(java,Path(temp)/'probe')
        record={'java_runtime_contract':probe.java_contract_from_evidence(evidence)}
        probe.verify_java_native_files(record,java)
        probe.enforce_java_runtime_evidence(record,java,evidence)
        self.assertEqual((ROOT/'tools/release/pepk-export-review.json').read_bytes(),before)
        self.assertEqual(json.loads(before)['java_runtime_contract']['evidence_run_id'],'41196109347')

    def test_changed_extra_missing_or_unresolved_java_components_rejected(self):
        import copy
        java=fixture_java()
        with tempfile.TemporaryDirectory() as temp:
            evidence=probe.java_runtime_evidence(java,Path(temp)/'probe')
        record={'java_runtime_contract':probe.java_contract_from_evidence(evidence)}
        changes=[]
        bad=copy.deepcopy(evidence);bad['loaded_native_libraries'][0]['sha256']='0'*64;changes.append(bad)
        bad=copy.deepcopy(evidence);bad['loaded_native_libraries'].append({'path':'/unreviewed/libextra.so','sha256':'0'*64});changes.append(bad)
        bad=copy.deepcopy(evidence);bad['loaded_native_libraries']=[r for r in bad['loaded_native_libraries'] if Path(r['path']).name!='libdl.so.2'];changes.append(bad)
        bad=copy.deepcopy(evidence);bad['probe_class_sha256']='0'*64;changes.append(bad)
        bad=copy.deepcopy(evidence);bad['java_home']='/different/jdk';changes.append(bad)
        bad=copy.deepcopy(evidence);bad['resolved_native_dependencies'].pop(next(iter(bad['resolved_native_dependencies'])));changes.append(bad)
        bad=copy.deepcopy(evidence);next(iter(bad['resolved_native_dependencies'].values()))[0]['sha256']='0'*64;changes.append(bad)
        for bad in changes:
            with self.subTest(change=changes.index(bad)),self.assertRaises(ValueError):
                probe.enforce_java_runtime_evidence(record,java,bad)
        for key in ('java:bin/java','host:/usr/lib/x86_64-linux-gnu/libdl.so.2','host:/usr/lib/x86_64-linux-gnu/librt.so.1'):
            wrong=copy.deepcopy(record)
            wrong['java_runtime_contract']['native_library_pins'][key]='0'*64
            with self.subTest(pin=key),patch.object(probe,'java_runtime_evidence') as child,self.assertRaisesRegex(ValueError,'SHA256 mismatch'):
                probe.verify_reviewed_java_runtime(wrong,java)
            child.assert_not_called()

    def test_separate_approved_java_pins_match_reviewed_public_evidence(self):
        import json
        record=json.loads((ROOT/'tools/release/pepk-export-review.json').read_text())
        contract=record['java_runtime_contract'];pins=contract['native_library_pins']
        self.assertEqual(len(pins),15)
        self.assertEqual(len([key for key in pins if key.startswith('host:')]),6)
        self.assertEqual(pins['host:/usr/lib/x86_64-linux-gnu/libdl.so.2'],'c2994527172cc23b7fb0f7d611b71e00a37da27af12c2e5e9e0dc1a742886c96')
        self.assertEqual(pins['host:/usr/lib/x86_64-linux-gnu/librt.so.1'],'6d072187d88e092e81561a44c03a4a41397319022c6d43519757cc051c576d82')
        self.assertEqual(contract['evidence_report_sha256'],'07f2460566d6749ddceceec65d9290a9e6200d6828c621a8efa881976f9f5633')
        self.assertEqual(len(record['toolchain']['native_library_pins']),41)

    def test_public_unresolved_dependency_diagnostics_are_bounded_names_only(self):
        from subprocess import CompletedProcess
        home=fixture_java().parent.parent;state={}
        result=CompletedProcess([],0,b'libmissing.so.1 => not found\nUNSAFE/PRIVATE-DUMMY-PAYLOAD => not found\n',b'PRIVATE-DUMMY-PAYLOAD')
        with patch.object(probe.subprocess,'run',return_value=result),self.assertRaises(ValueError):
            probe.java_native_dependencies(home/'lib/libjava.so',home,diagnostic_state=state)
        self.assertEqual(state,{'unresolved_library_names':['libmissing.so.1']})
