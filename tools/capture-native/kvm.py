#!/usr/bin/env python3
"""One approved disposable runner UID grant, exact restoration, no global mode change."""
import os,sys,json,stat,subprocess,fcntl,signal,time
from pathlib import Path
D='/dev/kvm'; S=Path(os.environ.get('RUNNER_TEMP','/tmp'))/'bold-film-kvm.json'
def run(*a): return subprocess.check_output(a,text=True,timeout=20)
def acl(): return run('getfacl','-cpn',D)
def meta():
 s=os.stat(D); return [s.st_uid,s.st_gid,stat.S_IMODE(s.st_mode),s.st_rdev]
def context():
 assert os.environ.get('GITHUB_ACTIONS')=='true'
 assert os.environ.get('GITHUB_REPOSITORY')=='JayMandava/bold-keys'
 assert os.environ.get('GITHUB_REF')=='refs/heads/qa/trailer-native-capture-20261115-2'
 assert os.environ.get('GITHUB_RUN_ATTEMPT')=='1'
 assert os.getuid()!=0
if sys.argv[1]=='grant':
 context(); assert not S.exists(); assert not Path(D).is_symlink(); assert stat.S_ISCHR(os.stat(D).st_mode)
 before=acl(); m=meta(); uid=os.getuid()
 assert m[0]==0 and m[2]==0o660
 assert before.strip().splitlines()==['user::rw-','group::rw-','other::---']
 data={'uid':uid,'run_id':os.environ['GITHUB_RUN_ID'],'before_acl':before,'before_meta':m}
 S.write_text(json.dumps(data))
 run('sudo','-n','setfacl','--no-mask','--modify',f'user:{uid}:rw-,mask::rw-',D)
 expected=['user::rw-',f'user:{uid}:rw-','group::rw-','mask::rw-','other::---']
 assert acl().strip().splitlines()==expected and meta()==m
 fd=os.open(D,os.O_RDWR); assert fcntl.ioctl(fd,0xAE00,0)==12; os.close(fd)
 print('Verified runner-only temporary KVM grant; emulator must stay non-root.')
elif sys.argv[1]=='cleanup':
 context()
 if not S.exists(): print('No grant was attempted.'); sys.exit(0)
 d=json.loads(S.read_text()); assert d['uid']==os.getuid() and d['run_id']==os.environ['GITHUB_RUN_ID']
 stop_error=None
 try:
  pidfile=Path(os.environ['RUNNER_TEMP'])/'bold-film-emulator.pid'
  if pidfile.exists():
   pid=int(pidfile.read_text()); proc=Path('/proc')/str(pid)
   if proc.exists():
    status=(proc/'status').read_text(); cmd=(proc/'cmdline').read_bytes()
    if '\nState:\tZ' not in '\n'+status:
     assert int(next(x for x in status.splitlines() if x.startswith('Uid:')).split()[1])==d['uid']
     assert b'bold-film' in cmd and (b'qemu' in cmd or b'emulator' in cmd)
     os.kill(pid,signal.SIGTERM)
     for _ in range(50):
      if not proc.exists() or '\nState:\tZ' in '\n'+(proc/'status').read_text(): break
      time.sleep(.2)
     else: raise RuntimeError('Owned emulator did not stop; refusal to claim cleanup')
 except (FileNotFoundError,ProcessLookupError): pass
 except Exception as error: stop_error=str(error)
 current=acl().strip().splitlines(); allowed=d['before_acl'].strip().splitlines()+[f"user:{d['uid']}:rw-",'mask::rw-']
 assert set(current).issubset(set(allowed)), 'Unexpected ACL principals; refuse overwrite'
 run('sudo','-n','setfacl','--set',','.join(d['before_acl'].strip().splitlines()),D)
 assert acl()==d['before_acl'] and meta()==d['before_meta']
 Path('capture-output').mkdir(exist_ok=True)
 Path('capture-output/kvm-cleanup.json').write_text(json.dumps({'exact_baseline_restored':True,'runner_grant_absent':True,'run_id':d['run_id'],'emulator_stop_error':stop_error}))
 print('Verified exact original KVM ACL, owner, group, mode restored.')
 if stop_error: raise RuntimeError(stop_error)
else: raise SystemExit('Unknown operation')
