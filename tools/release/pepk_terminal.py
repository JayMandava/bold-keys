"""Linux-only private-PTY driver. No raw transcript, password flags or inherited environment.

Flag-check/write is not atomic against a malicious child. Use only audited pinned code.
Unexpected exceptions are sanitized after bounded process-group kill/reap and buffer clearing.
"""
import errno
import hashlib
import os
from pathlib import Path
import pty
import re
import select
import signal
import termios
import time
import unicodedata

ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}


class GuardFailure(Exception):
    pass


def checked_label(value):
    if not isinstance(value, str) or not value or len(value) > 256 or any(
        ord(c) < 32 or ord(c) >= 127 or c in "':" for c in value
    ):
        raise GuardFailure('unsupported-label')
    return value


def verify_trusted_files(pins):
    if not pins:
        raise GuardFailure('missing-child-pins')
    for name, digest in pins.items():
        path = Path(name)
        if not path.is_absolute() or not path.is_file() or path.is_symlink():
            raise GuardFailure('untrusted-child-path')
        if not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest):
            raise GuardFailure('invalid-child-pin')
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                raise GuardFailure('child-pin-mismatch')


def _erase(value):
    value[:] = b'\0' * len(value)


def _reap(pid, deadline):
    while time.monotonic() < deadline:
        try:
            child, status = os.waitpid(pid, os.WNOHANG)
        except InterruptedError:
            continue
        if child:
            return status
        time.sleep(.005)
    return None


def terminal_run(argv, values, prompts, trusted_files, timeout=60, echo_timeout=3,
                 cleanup_timeout=2, output_limit=65536, console_charset='UTF-8'):
    """Return fixed nonsensitive status only; never return/retain terminal transcript.

    The caller must also pin every executable/JAR/module/script passed to the child.
    Production caller constructs argv itself. Caller owns clearing its own input copies.
    Passwords must fit below both the terminal's reported canonical limit and a255-byte cap.
    """
    pid = fd = status = None
    copies = []
    pending = bytearray()
    secrets_tail = bytearray()
    result = {'reason': None, 'exit_code': None, 'prompts_sent': 0,
              'no_password_echo': True, 'child_reaped': False}
    reason = None
    try:
        if console_charset != 'UTF-8' or not (0 < timeout <= 180 and 0 < echo_timeout <= 10
                                              and 0 < cleanup_timeout <= 5 and 0 < output_limit <= 65536):
            raise GuardFailure('unsupported-limits-or-charset')
        if not argv or not Path(argv[0]).is_absolute() or str(Path(argv[0])) not in trusted_files:
            raise GuardFailure('missing-executable-pin')
        verify_trusted_files(trusted_files)
        if len(values) != 2 or len(prompts) != 2 or any(not p or len(p) > 512 for p in prompts):
            raise GuardFailure('unsupported-prompts')
        for value in values:
            copy = bytearray(value)
            copies.append(copy)
            try:
                text = copy.decode('utf-8', errors='strict')
            except UnicodeError:
                raise GuardFailure('unsupported-input') from None
            if not copy or len(copy) > 255 or any(unicodedata.category(c).startswith('C') for c in text):
                raise GuardFailure('unsupported-input')
            if any(bytes(copy) in arg.encode('utf-8') for arg in argv):
                raise GuardFailure('password-in-argv')
        pid, fd = pty.fork()
        if pid == 0:
            try:
                os.execve(argv[0], argv, ENV)
            except BaseException:
                os._exit(126)
        os.set_blocking(fd, False)
        canonical_limit = os.fpathconf(fd, 'PC_MAX_CANON')
        if canonical_limit <= 1 or any(len(v) + 1 >= canonical_limit for v in copies):
            raise GuardFailure('canonical-input-limit')
        deadline = time.monotonic() + timeout
        index = 0
        sending = None
        offset = 0
        echo_deadline = 0
        sent_any = False
        count = 0
        eof = False
        while True:
            now = time.monotonic()
            if now >= deadline:
                raise GuardFailure('timeout')
            if sending is not None:
                flags = termios.tcgetattr(fd)[3]
                if flags & (termios.ECHO | termios.ECHONL):
                    if sent_any or now >= echo_deadline:
                        raise GuardFailure('echo-enabled')
                else:
                    _, writable, _ = select.select([], [fd], [], min(.01, deadline - now))
                    if writable:
                        # Recheck immediately before EVERY nonblocking partial write.
                        if termios.tcgetattr(fd)[3] & (termios.ECHO | termios.ECHONL):
                            raise GuardFailure('echo-enabled')
                        try:
                            with memoryview(sending) as view:
                                with view[offset:] as chunk:
                                    size = os.write(fd, chunk)
                        except BlockingIOError:
                            size = 0
                        if size:
                            sent_any = True
                            offset += size
                        if offset == len(sending):
                            _erase(sending)
                            sending = None
                            index += 1
                            result['prompts_sent'] = index
            readable, _, _ = select.select([fd], [], [], min(.01, max(0, deadline - time.monotonic())))
            if readable:
                try:
                    block = os.read(fd, 4096)
                except BlockingIOError:
                    block = None
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    block = b''
                if block is not None:
                    if not block:
                        eof = True
                    else:
                        count += len(block)
                        if count > output_limit:
                            raise GuardFailure('output-limit')
                        # Small rolling window detects exact password echo across reads.
                        secrets_tail.extend(block)
                        if any(v in secrets_tail for v in copies):
                            result['no_password_echo'] = False
                            raise GuardFailure('password-echo')
                        keep = max(len(v) for v in copies) - 1
                        if len(secrets_tail) > keep:
                            del secrets_tail[:len(secrets_tail) - keep]
                        pending.extend(block)
                        while True:
                            match = re.search(rb'(?i)password|passphrase', pending)
                            if match is None:
                                if len(pending) > 32:
                                    del pending[:-32]
                                break
                            start = match.start()
                            # Expected messages begin "Enter password". Retain prefix across reads.
                            if start >= 6 and pending[start - 6:start] == b'Enter ':
                                start -= 6
                            candidate = bytes(pending[start:])
                            if index >= 2 or sending is not None:
                                raise GuardFailure('unexpected-prompt')
                            expected = prompts[index]
                            if expected.startswith(candidate):
                                if len(candidate) == len(expected):
                                    del pending[:start + len(expected)]
                                    sending = bytearray(copies[index]) + b'\n'
                                    offset = 0
                                    sent_any = False
                                    echo_deadline = min(deadline, time.monotonic() + echo_timeout)
                                break
                            if candidate.startswith(expected):
                                del pending[:start + len(expected)]
                                sending = bytearray(copies[index]) + b'\n'
                                offset = 0
                                sent_any = False
                                echo_deadline = min(deadline, time.monotonic() + echo_timeout)
                                continue
                            # A different password prompt or malformed expected prefix is forbidden.
                            raise GuardFailure('unexpected-prompt')
            child = 0
            if status is None:
                child, child_status = os.waitpid(pid, os.WNOHANG)
                if child:
                    status = child_status
                # Drain available output before deciding success, including extra/echoed prompts.
                if not eof:
                    continue
            if eof:
                if status is None:
                    status = _reap(pid, min(deadline, time.monotonic() + cleanup_timeout))
                if status is None:
                    raise GuardFailure('child-not-reaped')
                result['child_reaped'] = True
                result['exit_code'] = os.waitstatus_to_exitcode(status)
                if index != 2 or sending is not None:
                    raise GuardFailure('early-eof')
                if result['exit_code'] != 0:
                    raise GuardFailure('child-nonzero')
                break
    except GuardFailure as error:
        reason = str(error)
    except BaseException:
        reason = 'controller-exception'
    finally:
        if pid is not None and pid != 0:
            # Also terminate any same-session descendants, even if the immediate child exited.
            try:
                os.killpg(pid, signal.SIGKILL)
            except OSError:
                pass
            if status is None:
                try:
                    status = _reap(pid, time.monotonic() + cleanup_timeout)
                except BaseException:
                    status = None
            if status is not None:
                result['child_reaped'] = True
                result['exit_code'] = os.waitstatus_to_exitcode(status)
            else:
                reason = 'cleanup-reap-timeout'
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
        for value in copies + [pending, secrets_tail]:
            _erase(value)
        if 'sending' in locals() and sending is not None:
            _erase(sending)
    result['reason'] = reason
    return result
