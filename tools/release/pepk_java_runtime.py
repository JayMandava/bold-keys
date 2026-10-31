"""No-secrets Java probe evidence only. Does not approve or change production pins."""
from pathlib import Path
import subprocess
import re

from pepk_build import ENV
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
