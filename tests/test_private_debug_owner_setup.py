#!/usr/bin/env python3
"""Syntax and mocked owner-setup checks. Never run a key generator or create a real key."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("owner_setup", Path(__file__).resolve().parents[1] / "tools/setup_private_debug_macos.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class OwnerSetup(unittest.TestCase):
    def setUp(self):
        # The managed test host itself has /tmp/.git; isolate this ambient marker
        # from owner-path logic, while testing marker rejection separately below.
        self.repo_probe = patch.object(setup, "in_repository", return_value=False)
        self.repo_probe.start()
        self.addCleanup(self.repo_probe.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "dedicated-signer"
        self.tool = self.root / "fake-keytool-never-executed"
        self.tool.write_text("public dummy fixture; not an executable key generator")
        self.tool.chmod(0o700)
        self.calls = []
        self.output = io.StringIO()
        self.password = "PUBLIC-MOCK-PASSWORD-NOT-A-CREDENTIAL"
        self.cert = b"PUBLIC INVALID MOCK CERTIFICATE"
        self.failure = False

    def fake_tool(self, command, env, message, data=None):
        self.calls.append((command, env, data))
        self.assertNotIn(self.password, command)
        self.assertNotIn("BOLD_DEBUG_KEYSTORE_BASE64", env)
        if "-genkeypair" in command:
            self.assertEqual(env["BOLD_DEBUG_STORE_PASSWORD"], self.password)
            self.assertEqual(command[command.index("-alias") + 1], "boldkeys-debug")
            self.assertEqual(command[command.index("-storetype") + 1], "PKCS12")
            if self.failure:
                raise RuntimeError(message)
            path = Path(command[command.index("-keystore") + 1])
            path.write_bytes(b"PUBLIC INVALID DUMMY BYTES; NOT A KEY")
        elif "-exportcert" in command:
            return self.cert
        return b""

    def create_mock(self):
        with patch.object(setup, "confirm_creation"), patch.object(setup.secrets, "token_urlsafe", return_value=self.password), patch.object(setup, "checked", self.fake_tool), contextlib.redirect_stdout(self.output):
            setup.create_signer(self.directory, self.tool)

    def test_mock_create_private_files_no_secret_output(self):
        self.create_mock()
        self.assertEqual(self.directory.stat().st_mode & 0o777, 0o700)
        self.assertEqual(sorted(p.name for p in self.directory.iterdir()), ["certificate-sha256.txt", "debug-signer.p12", "store-password.txt"])
        self.assertTrue(all(p.stat().st_mode & 0o777 == 0o600 for p in self.directory.iterdir()))
        self.assertEqual((self.directory / "store-password.txt").read_text(), self.password)
        self.assertIn(hashlib.sha256(self.cert).hexdigest(), self.output.getvalue())
        self.assertNotIn(self.password, self.output.getvalue())
        self.assertNotIn("INVALID DUMMY", self.output.getvalue())

    def test_existing_signer_never_overwritten(self):
        self.create_mock()
        before = (self.directory / "debug-signer.p12").read_bytes()
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            self.create_mock()
        self.assertEqual((self.directory / "debug-signer.p12").read_bytes(), before)
        self.assertEqual(self.calls, [])

    def test_cancellation_generates_nothing(self):
        with patch.object(setup, "confirm_creation", side_effect=RuntimeError("cancelled")), patch.object(setup.secrets, "token_urlsafe") as random, patch.object(setup, "checked") as tool:
            with self.assertRaisesRegex(RuntimeError, "cancelled"):
                setup.create_signer(self.directory, self.tool)
        random.assert_not_called()
        tool.assert_not_called()
        self.assertFalse(self.directory.exists())

    def test_noninteractive_confirmation_refused(self):
        with patch.object(setup.sys.stdin, "isatty", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "interactive owner"):
                setup.confirm_creation(self.tool, self.directory)

    def test_wrong_typed_confirmation_refused_without_creation(self):
        with patch.object(setup.sys.stdin, "isatty", return_value=True), patch("builtins.input", return_value="yes"), contextlib.redirect_stdout(self.output):
            with self.assertRaisesRegex(RuntimeError, "cancelled"):
                setup.confirm_creation(self.tool, self.directory)
        self.assertFalse(self.directory.exists())

    def test_exact_typed_confirmation_only_accepts_intent(self):
        with patch.object(setup.sys.stdin, "isatty", return_value=True), patch("builtins.input", return_value=setup.CONFIRMATION), contextlib.redirect_stdout(self.output):
            setup.confirm_creation(self.tool, self.directory)
        self.assertFalse(self.directory.exists())
        self.assertEqual(self.calls, [])

    def test_tool_failure_removes_only_new_directory(self):
        self.failure = True
        with self.assertRaisesRegex(RuntimeError, "creation failed"):
            self.create_mock()
        self.assertFalse(self.directory.exists())

    def test_repository_destination_rejected(self):
        (self.root / ".git").write_text("public repository marker")
        self.repo_probe.stop()
        self.assertTrue(setup.in_repository(self.directory))
        with self.assertRaisesRegex(RuntimeError, "outside every Git"):
            self.create_mock()
        self.assertFalse(self.directory.exists())
        self.assertEqual(self.calls, [])

    def test_symlink_destination_rejected(self):
        self.directory.symlink_to(self.root / "not-created")
        with self.assertRaisesRegex(RuntimeError, "symlinks"):
            self.create_mock()
        self.assertFalse((self.root / "not-created").exists())

    def test_clipboard_gets_stdin_only_with_exact_variable_names(self):
        self.create_mock()
        self.calls.clear()
        with patch.object(setup, "checked", self.fake_tool), contextlib.redirect_stdout(self.output):
            setup.copy_secret(self.directory, "keystore")
            setup.copy_secret(self.directory, "password")
        self.assertEqual([c[0] for c in self.calls], [["/usr/bin/pbcopy"], ["/usr/bin/pbcopy"]])
        self.assertEqual(base64.b64decode(self.calls[0][2]), b"PUBLIC INVALID DUMMY BYTES; NOT A KEY")
        self.assertNotIn(b"\n", self.calls[0][2])
        self.assertEqual(self.calls[1][2], self.password.encode())
        self.assertIn("BOLD_DEBUG_KEYSTORE_BASE64", self.output.getvalue())
        self.assertIn("BOLD_DEBUG_STORE_PASSWORD", self.output.getvalue())
        self.assertNotIn(self.password, self.output.getvalue())

    def test_insecure_or_symlink_secret_file_rejected(self):
        self.create_mock()
        path = self.directory / "store-password.txt"
        path.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, "mode 0600"):
            setup.read_private(self.directory, path.name)
        path.unlink()
        path.symlink_to(self.tool)
        with self.assertRaisesRegex(RuntimeError, "symlinks"):
            setup.read_private(self.directory, path.name)


if __name__ == "__main__":
    unittest.main()
