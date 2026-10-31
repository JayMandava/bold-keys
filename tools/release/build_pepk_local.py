"""Build only downloaded official public PEPK source; no network or repo writes."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, zipfile

os.umask(0o077)
root = Path(__file__).resolve().parent
build = root / 'local-build'
classes = build / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
sources = sorted((root/'source').rglob('*.java'))
argfile = build/'all-sources.args'
argfile.write_text('\n'.join(str(f) for f in sources)+'\n')
env = {'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'}
command = ['/usr/bin/java','-m','jdk.compiler/com.sun.tools.javac.Main',
           '-encoding','UTF-8','-d',str(classes),'-sourcepath',str(root/'source'),'@'+str(argfile)]
with (build/'compile-all.log').open('wb') as log:
    result = subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
(build/'compile-all-exit.txt').write_text(str(result.returncode)+'\n')
if result.returncode:
    raise SystemExit('Local compilation failed; inspect public-source compile-all.log')
jar = build/'pepk-local-source-build.jar'
entries = [('META-INF/MANIFEST.MF',b'Manifest-Version: 1.0\r\nMain-Class: com.google.wireless.android.vending.developer.signing.tools.extern.export.ExportEncryptedPrivateKeyTool\r\n\r\n')]
entries += [(f.relative_to(classes).as_posix(),f.read_bytes()) for f in sorted(classes.rglob('*.class'))]
with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name,data in entries:
        info=zipfile.ZipInfo(name,(1980,1,1,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o100644<<16
        z.writestr(info,data,compresslevel=9)
metadata={'identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY',
          'source_jar_sha256':hashlib.sha256((root/'pepk-src.jar').read_bytes()).hexdigest(),
          'command':command,'java_sources':len(sources),'class_count':len(entries)-1,
          'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
          'packaging':'Python zipfile; sorted entries; fixed 1980 timestamp; DEFLATE level 9; local Main-Class manifest',
          'java_version':subprocess.run(['/usr/bin/java','-version'],env=env,capture_output=True,text=True).stderr}
(build/'build-metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(json.dumps(metadata))
