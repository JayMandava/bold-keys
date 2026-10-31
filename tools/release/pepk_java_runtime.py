"""No-secrets Java probe evidence only. Does not approve or change production pins."""
from pathlib import Path
import subprocess
import re
import tempfile

from pepk_terminal import ENV
from pepk_public import verify_public_file
from pepk_runtime import digest, loaded_native_libraries

ROOT=Path(__file__).resolve().parent


def java_native_dependencies(binary,home):
    # Standalone libjava/libnet need the selected JVM directory that the Java
    # launcher normally loads itself. This is diagnostic resolution, not a map.
    env=ENV|{'LD_LIBRARY_PATH':str(home/'lib/server')+':'+str(home/'lib')}
    result=subprocess.run(['/usr/bin/ldd',str(binary)],env=env,capture_output=True,timeout=10)
    output=result.stdout+result.stderr
    if result.returncode or len(output)>65536 or b'not found' in output:
        raise ValueError('Java native dependency inventory failed or unresolved')
    paths=set(re.findall(rb'(?:=> )?(/[A-Za-z0-9_./+\-]+)',result.stdout))
    if not paths:raise ValueError('No Java diagnostic native dependencies resolved')
    return [{'path':str(Path(path.decode()).resolve(strict=True)),
             'sha256':digest(Path(path.decode()).resolve(strict=True))} for path in sorted(paths)]


def java_runtime_evidence(java,work,tool=None):
    java=java.resolve(strict=True)
    if work.exists():raise ValueError('Java probe root must be new')
    work.mkdir(mode=0o700)
    source=ROOT/'PepkRuntimeProbe.java'
    subprocess.run([str(java),'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8',
                    '-d',str(work),str(source)],env=ENV,check=True,
                   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    classpath=str(work)+((':'+str(tool.resolve(strict=True))) if tool else '')
    result=subprocess.run([str(java),'-cp',classpath,'PepkRuntimeProbe',
                           'with-public-tool' if tool else 'jdk-only'],env=ENV,
                          capture_output=True,timeout=30)
    if result.returncode or len(result.stdout)>4*1024*1024 or len(result.stderr)>65536:
        raise ValueError('Public Java mapping probe failed')
    mapped=loaded_native_libraries(result.stdout.decode('utf-8',errors='strict'))
    home=java.parent.parent
    if not any(Path(row['path'])==home/'lib/server/libjvm.so' for row in mapped):
        raise ValueError('Selected JVM mapping missing')
    binaries={java,home/'lib/server/libjvm.so',home/'lib/libjava.so'}
    binaries.update(Path(row['path']) for row in mapped if Path(row['path']).is_relative_to(home))
    resolved={str(path):java_native_dependencies(path,home) for path in sorted(binaries)}
    return {'scope':'public synthetic JCA/compiler/provider probe, not mapping of the later key-bearing export; file bytes, not memory attestation',
            'environment':'fixed PATH/LANG only; no inherited loader or signing environment',
            'probe_source_sha256':digest(source),'probe_class_sha256':digest(work/'PepkRuntimeProbe.class'),
            'java_home':str(home),'public_tool_provider_loaded':tool is not None,
            'loaded_native_libraries':mapped,
            'mapped_host_libraries':[row for row in mapped if not Path(row['path']).is_relative_to(home)],
            'diagnostic_loader_path':'selected JDK lib/server and lib only; actual probe has no LD_LIBRARY_PATH',
            'resolved_native_dependencies':resolved}


def java_pin_key(path,home):
    path=Path(path)
    return 'java:'+path.relative_to(home).as_posix() if path.is_relative_to(home) else 'host:'+str(path)


def java_contract_from_evidence(evidence):
    """Fixture/candidate data only; caller must never persist this as approved pins."""
    home=Path(evidence['java_home'])
    return {'probe_source_sha256':evidence['probe_source_sha256'],
            'probe_class_sha256':evidence['probe_class_sha256'],
            'native_library_pins':{java_pin_key(row['path'],home):row['sha256']
                                   for row in evidence['loaded_native_libraries']}}


def verify_java_native_files(record,java):
    """Static approved bytes, before any Java probe or signing-environment read."""
    contract=record.get('java_runtime_contract',{})
    pins=contract.get('native_library_pins')
    if not isinstance(pins,dict) or not pins:raise ValueError('Missing reviewed Java native contract')
    home=java.parent.parent
    for key,sha in pins.items():
        if not isinstance(key,str) or not isinstance(sha,str) or not re.fullmatch('[0-9a-f]{64}',sha):
            raise ValueError('Invalid reviewed Java native pin')
        if key.startswith('java:'):
            rel=Path(key[5:])
            if rel.is_absolute() or '..' in rel.parts:raise ValueError('Invalid reviewed Java native path')
            path=home/rel
        elif key.startswith('host:/'):path=Path(key[5:])
        else:raise ValueError('Invalid reviewed Java native path')
        verify_public_file(path,sha)
    for required in ('java:bin/java','java:lib/server/libjvm.so',
                     'host:/usr/lib/x86_64-linux-gnu/libdl.so.2','host:/usr/lib/x86_64-linux-gnu/librt.so.1'):
        if required not in pins:raise ValueError('Incomplete reviewed Java native contract')
    verify_public_file(ROOT/'PepkRuntimeProbe.java',contract.get('probe_source_sha256'))
    return contract


def enforce_java_runtime_evidence(record,java,evidence):
    contract=record['java_runtime_contract'];pins=contract['native_library_pins'];home=java.parent.parent
    if evidence['java_home']!=str(home):raise ValueError('Java probe selected a different JDK')
    if (evidence['probe_source_sha256']!=contract['probe_source_sha256'] or
            evidence['probe_class_sha256']!=contract['probe_class_sha256']):
        raise ValueError('Java probe source/class differs from reviewed evidence')
    def checked(rows):
        observed={}
        for row in rows:
            key=java_pin_key(row['path'],home)
            if key in observed or pins.get(key)!=row['sha256']:
                raise ValueError('Java native mapping/dependency unreviewed or changed')
            observed[key]=row['sha256']
        return observed
    mapped=checked(evidence['loaded_native_libraries'])
    wanted_host={key:sha for key,sha in pins.items() if key.startswith('host:')}
    if {key:sha for key,sha in mapped.items() if key.startswith('host:')}!=wanted_host:
        raise ValueError('Reviewed Java host mapping missing or changed')
    if 'java:lib/server/libjvm.so' not in mapped:raise ValueError('Selected JVM mapping missing')
    resolved={}
    for binary,rows in evidence['resolved_native_dependencies'].items():
        if java_pin_key(binary,home) not in mapped:raise ValueError('Unmapped Java diagnostic binary')
        resolved.update(checked(rows))
    wanted_binaries={row['path'] for row in evidence['loaded_native_libraries']
                     if Path(row['path']).is_relative_to(home)}
    if set(evidence['resolved_native_dependencies'])!=wanted_binaries:
        raise ValueError('Java mapped binary missing diagnostic dependency closure')
    if {key:sha for key,sha in resolved.items() if key.startswith('host:')}!=wanted_host:
        raise ValueError('Reviewed Java resolved host dependency missing or changed')


def verify_reviewed_java_runtime(record,java):
    verify_java_native_files(record,java)
    with tempfile.TemporaryDirectory(prefix='public-pepk-java-runtime-') as temp:
        evidence=java_runtime_evidence(java,Path(temp)/'probe')
    enforce_java_runtime_evidence(record,java,evidence)
    return evidence
