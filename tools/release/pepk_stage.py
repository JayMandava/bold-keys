#!/usr/bin/env python3
"""Public-only tool/recipient preparation; gated before redistribution. No secret lookups."""
import argparse
import errno
import json
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import sys
import time

from pepk_build import build, verify_toolchain
from pepk_export import PAYLOAD_NAMES, exact_files, recipient_bytes, verify_payload
from pepk_policy import SOURCE_SHA, TOOL_SHA, require, validate_ready
from pepk_public import verify_public_file
from pepk_terminal import ENV, _reap

ROOT = Path(__file__).resolve().parent


def probe_console(java, classes, timeout=10):
    """Public no-password probe of the SAME pinned Java/class helper under a controlling PTY."""
    pid = fd = status = None
    output = bytearray()
    try:
        pid, fd = pty.fork()
        if pid == 0:
            try:
                os.execve(str(java),[str(java),'-cp',str(classes),'PepkIdentity','--probe-console'],ENV)
            except BaseException:
                os._exit(126)
        os.set_blocking(fd,False)
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            ready,_,_=select.select([fd],[],[],min(.05,max(0,deadline-time.monotonic())))
            if ready:
                try:
                    block=os.read(fd,256)
                except BlockingIOError:
                    continue
                except OSError as error:
                    if error.errno!=errno.EIO:raise
                    block=b''
                if not block:
                    status=_reap(pid,min(deadline,time.monotonic()+2))
                    break
                output.extend(block)
                require(len(output)<=1024,'Console probe output exceeded bound')
        require(status is not None and os.waitstatus_to_exitcode(status)==0
                and output==b'PEPK_CONSOLE_UTF8_OK','Pinned Java console is not verified UTF-8')
    finally:
        if pid is not None and pid!=0:
            try:os.killpg(pid,signal.SIGKILL)
            except OSError:pass
            if status is None:
                status=_reap(pid,time.monotonic()+2)
        if fd is not None:os.close(fd)
        output[:]=b'\0'*len(output)


def stage(source, build_root, payload, record, commit):
    validate_ready(record)
    require(not payload.exists(),'Public payload destination must be new')
    pem=ROOT/'pepk-console-encryption.pem'
    notices=ROOT/'pepk-third-party-notices.txt'
    verify_public_file(pem,record['console_encryption_pem_sha256'])
    verify_public_file(notices,record['third_party_notices_sha256'])
    recipient_bytes(pem)
    tool=build(source,build_root,record)
    java=verify_toolchain(record)
    probe_console(java,build_root/'identity')
    payload.mkdir(mode=0o700)
    try:
        for src,name in [(tool,'pepk.jar'),(build_root/'identity/PepkIdentity.class','PepkIdentity.class'),
                         (pem,'encryption-public-key.pem'),(notices,'THIRD_PARTY_NOTICES.txt')]:
            shutil.copyfile(src,payload/name)
        provenance={'commit':commit,'source_sha256':SOURCE_SHA,'tool_sha256':TOOL_SHA,
                    'pem_sha256':record['console_encryption_pem_sha256'],
                    'identity_class_sha256':record['identity_class_sha256'],
                    'notices_sha256':record['third_party_notices_sha256'],
                    'console_charset':'UTF-8','identity':'LOCAL SOURCE BUILD, NOT GOOGLE DISTRIBUTED BINARY'}
        (payload/'public-provenance.json').write_text(json.dumps(provenance,sort_keys=True,indent=2)+'\n')
        exact_files(payload,PAYLOAD_NAMES)
        verify_payload(payload,record,commit)
    except BaseException:
        shutil.rmtree(payload)
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit',required=True)
    parser.add_argument('--payload',type=Path,required=True)
    parser.add_argument('--source',type=Path)
    parser.add_argument('--build-root',type=Path)
    parser.add_argument('--verify-only',action='store_true')
    args=parser.parse_args()
    try:
        record=json.loads((ROOT/'pepk-export-review.json').read_text())
        validate_ready(record)
        if args.verify_only:
            java,_=verify_payload(args.payload.resolve(),record,args.commit)
            recipient_bytes(args.payload/'encryption-public-key.pem')
            probe_console(java,args.payload.resolve())
        else:
            require(args.source is not None and args.build_root is not None,'Missing public build inputs')
            stage(args.source.resolve(),args.build_root.absolute(),args.payload.absolute(),record,args.commit)
        print('Public source-built tool, notices, recipient pin and UTF-8 console verified; no keys accessed.')
    except Exception:
        print('Public PEPK preparation failed closed; no signing secret step is authorized.',file=sys.stderr)
        sys.exit(1)
