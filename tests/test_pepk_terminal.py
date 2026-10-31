"""Adversarial private-PTY tests using pinned harmless mock children, never PEPK/real keys."""
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import textwrap
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('terminal', ROOT / 'tools/release/pepk_terminal.py')
t = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t)
PROMPTS = [b"Enter password for store 'fixture.jks':", b"Enter password for key 'fixture':"]
VALUES = [b'Fixture-Only-Password-One!', b'Fixture-Only-Password-Two!']
CHILD = '''
import os,sys,termios,time
mode=sys.argv[1]
a=termios.tcgetattr(0)
a[3] |= termios.ECHO | termios.ECHONL
termios.tcsetattr(0,termios.TCSANOW,a)
if mode=='timeout': time.sleep(30)
if mode=='flood': os.write(1,b'x'*70000);time.sleep(30)
if mode=='eof': sys.exit(0)
if mode=='wrong': os.write(1,b"Enter password for key 'other':");time.sleep(30)
for p in [b"Enter password for store 'fixture.jks':",b"Enter password for key 'fixture':"]:
 for byte in p: os.write(1,bytes([byte]));time.sleep(.0001)
 if mode=='echo':time.sleep(30)
 time.sleep(.04)
 a[3] &= ~(termios.ECHO | termios.ECHONL)
 termios.tcsetattr(0,termios.TCSANOW,a)
 value=sys.stdin.buffer.readline().rstrip(b'\\n')
 if mode=='leak':os.write(1,value);time.sleep(.1)
 if mode=='echonl':
  a[3] |= termios.ECHONL
  termios.tcsetattr(0,termios.TCSANOW,a)
  time.sleep(30)
 a[3] |= termios.ECHO | termios.ECHONL
 termios.tcsetattr(0,termios.TCSANOW,a)
if mode=='extra':os.write(1,b"Enter password for key 'fixture':");time.sleep(.1)
if mode=='nonzero':sys.exit(3)
'''


class TerminalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.script = Path(self.temp.name) / 'child.py'
        self.script.write_text(textwrap.dedent(CHILD))
        self.executable = str(Path('/usr/bin/python3').resolve())
        self.pins = {self.executable: self.sha(self.executable), str(self.script): self.sha(self.script)}

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def sha(path):
        with Path(path).open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest()

    def run_child(self, mode='normal', **kw):
        return t.terminal_run([self.executable, str(self.script), mode], VALUES, PROMPTS,
                              self.pins, timeout=kw.pop('timeout', 2), echo_timeout=.2, **kw)

    def test_incremental_prompt_delayed_echo_transition_and_two_inputs(self):
        result = self.run_child()
        self.assertEqual(result, dict(reason=None,exit_code=0,prompts_sent=2,no_password_echo=True,child_reaped=True))

    def test_echo_and_echonl_are_both_required_off(self):
        result = self.run_child('echo')
        self.assertEqual(result['reason'], 'echo-enabled')
        self.assertEqual(result['prompts_sent'], 0)
        self.assertTrue(result['child_reaped'])
        with patch.object(t.termios,'tcgetattr',return_value=[0,0,0,t.termios.ECHONL,0,0,[]]):
            self.assertEqual(self.run_child()['reason'], 'echo-enabled')

    def test_unexpected_extra_early_nonzero_and_flood_fail(self):
        for mode, reason in [('wrong','unexpected-prompt'),('extra','unexpected-prompt'),
                             ('eof','early-eof'),('nonzero','child-nonzero'),('flood','output-limit')]:
            with self.subTest(mode=mode):
                result = self.run_child(mode)
                self.assertEqual(result['reason'], reason)
                self.assertTrue(result['child_reaped'])

    def test_detected_password_echo_is_failure_and_never_returned(self):
        result = self.run_child('leak')
        self.assertEqual(result['reason'], 'password-echo')
        self.assertFalse(result['no_password_echo'])
        for value in VALUES:
            self.assertNotIn(value.decode(), repr(result))

    def test_timeout_and_reaping_are_bounded(self):
        start = time.monotonic()
        result = self.run_child('timeout', timeout=.2)
        self.assertEqual(result['reason'], 'timeout')
        self.assertTrue(result['child_reaped'])
        self.assertLess(time.monotonic() - start, 2.5)

    def test_controls_invalid_utf8_and_canonical_limit_rejected(self):
        for value in [b'line\n',b'line\r',b'\x00',b'\x7f',b'\xff',b'a'*256]:
            with self.subTest(value_length=len(value)):
                result = t.terminal_run([self.executable,str(self.script),'normal'],[value,VALUES[1]],PROMPTS,self.pins)
                self.assertEqual(result['reason'],'unsupported-input')
        with patch.object(t.os,'fpathconf',return_value=16):
            result=self.run_child()
            self.assertEqual(result['reason'],'canonical-input-limit')
            self.assertTrue(result['child_reaped'])

    def test_wrong_child_pin_and_password_in_argv_rejected_before_fork(self):
        with patch.object(t.pty,'fork') as fork:
            result=t.terminal_run([self.executable,str(self.script)],VALUES,PROMPTS,{self.executable:'0'*64})
            self.assertEqual(result['reason'],'child-pin-mismatch')
            result=t.terminal_run([self.executable,str(self.script),VALUES[0].decode()],VALUES,PROMPTS,self.pins)
            self.assertEqual(result['reason'],'password-in-argv')
            fork.assert_not_called()

    def test_partial_writes_nonblocking_and_echo_rechecked(self):
        original=t.os.write
        def short_write(fd, data):return original(fd,data[:1])
        with patch.object(t.os,'write',side_effect=short_write):
            self.assertIsNone(self.run_child()['reason'])
        original_flags=t.termios.tcgetattr
        writes=[]
        def capture_write(fd,data):
            writes.append(True)
            return original(fd,data[:1])
        def changing_flags(fd):
            flags=original_flags(fd)
            if writes:flags[3]|=t.termios.ECHO
            return flags
        with patch.object(t.os,'write',side_effect=capture_write),patch.object(t.termios,'tcgetattr',side_effect=changing_flags):
            result=self.run_child()
            self.assertEqual(result['reason'],'echo-enabled')
            self.assertEqual(len(writes),1)
            self.assertTrue(result['child_reaped'])

    def test_backpressure_and_unexpected_exception_cleanup(self):
        with patch.object(t.os,'write',side_effect=BlockingIOError()):
            result=self.run_child(timeout=.4)
            self.assertEqual(result['reason'],'timeout')
            self.assertTrue(result['child_reaped'])
        with patch.object(t.termios,'tcgetattr',side_effect=RuntimeError('fixture confidential diagnostic')):
            result=self.run_child()
            self.assertEqual(result['reason'],'controller-exception')
            self.assertTrue(result['child_reaped'])
            self.assertNotIn('confidential',repr(result))

    def test_labels_charset_and_missing_trust_rejected(self):
        for label in ['', 'bad\nname', "bad'name",'x:y','nonasciié']:
            with self.assertRaises(t.GuardFailure):t.checked_label(label)
        self.assertEqual(t.checked_label('release.keystore'),'release.keystore')
        result=t.terminal_run([self.executable],VALUES,PROMPTS,{},console_charset='ASCII')
        self.assertEqual(result['reason'],'unsupported-limits-or-charset')
