#!/usr/bin/env python3
"""No real keystore: exercise signing gates with mocked tools and invalid dummy bytes."""
import base64
import hashlib
import importlib.util
import io
import subprocess
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("private_debug", Path(__file__).resolve().parents[1] / "tools/build_private_debug.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class SigningGates(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path in ("gradlew", "sdk/build-tools/35.0.0/apksigner", "sdk/build-tools/35.0.0/aapt", "java/bin/keytool",
                     "app/build/outputs/apk/privateDebug/app-privateDebug-unsigned.apk"):
            item = self.root / path
            item.parent.mkdir(parents=True, exist_ok=True)
            item.write_bytes(b"invalid public dummy bytes, never key material")
        self.env = {"BOLD_DEBUG_KEYSTORE_BASE64": base64.b64encode(b"invalid dummy bytes").decode(),
                    "BOLD_DEBUG_STORE_PASSWORD": "public-test-password"}
        self.cert = b"mock public cert, not a real certificate"
        self.pin = hashlib.sha256(self.cert).hexdigest()
        self.output = self.root / "output.apk"
        self.key_paths = []
        self.calls = []
        self.failure = None

    def tool(self, command, env, message):
        self.calls.append(command)
        self.assertNotIn(self.env["BOLD_DEBUG_STORE_PASSWORD"], command)
        self.assertNotIn("BOLD_DEBUG_KEYSTORE_BASE64", env)
        if command[0].endswith("gradlew"):
            self.assertNotIn("BOLD_DEBUG_STORE_PASSWORD", env)
            self.assertIn("-PunsignedRelease=true", command)
            self.assertIn(":app:assemblePrivateDebug", command)
        elif command[0].endswith("aapt"):
            if self.failure == "identity":
                return b"package: name='ai.boldkeys' versionCode='10'\napplication-label:'Bold Keys'\n"
            return b"package: name='ai.boldkeys.debug' versionCode='10'\napplication-label:'Bold Keys Debug'\n"
        elif command[0].endswith("keytool"):
            key = Path(command[command.index("-keystore") + 1])
            self.key_paths.append(key)
            self.assertEqual(key.parent.stat().st_mode & 0o777, 0o700)
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)
            self.assertEqual(env["BOLD_DEBUG_STORE_PASSWORD"], self.env["BOLD_DEBUG_STORE_PASSWORD"])
            if self.failure == "keytool":
                raise RuntimeError(message)
            return self.cert
        elif "sign" in command:
            Path(command[command.index("--out") + 1]).write_bytes(b"mock APK, not installable")
        elif "verify" in command:
            self.assertNotIn("BOLD_DEBUG_STORE_PASSWORD", env)
            pin = "0" * 64 if self.failure == "verify" else self.pin
            return f"Signer #1 certificate SHA-256 digest: {pin}\n".encode()
        return b""

    def run_build(self, pin=None):
        with patch.object(helper, "ROOT", self.root), patch.dict(os.environ, self.env, clear=True), patch.object(helper, "checked", self.tool), patch("builtins.print"):
            helper.build(self.output, pin or self.pin, self.root / "sdk", self.root / "java")

    def assert_clean(self):
        self.assertTrue(all(not p.exists() and not p.parent.exists() for p in self.key_paths))

    def test_missing_credentials(self):
        for name in self.env.copy():
            saved = self.env.pop(name)
            with self.assertRaisesRegex(RuntimeError, "PersonalVault must inject"):
                self.run_build()
            self.env[name] = saved
        self.assertEqual(self.calls, [])

    def test_missing_pin(self):
        with self.assertRaisesRegex(RuntimeError, "explicit expected"):
            self.run_build("invalid")
        self.assertEqual(self.calls, [])

    def test_malformed_base64(self):
        self.env["BOLD_DEBUG_KEYSTORE_BASE64"] = "not-base64!"
        with self.assertRaisesRegex(RuntimeError, "not valid base64"):
            self.run_build()
        self.assertFalse(self.output.exists())

    def test_repo_temporary_directory_rejected_before_decode(self):
        directory = self.root / "build" / "mock-signing"
        directory.mkdir(parents=True)
        with patch.object(helper.tempfile, "TemporaryDirectory") as factory:
            factory.return_value.__enter__.return_value = str(directory)
            with self.assertRaisesRegex(RuntimeError, "outside the repository"):
                self.run_build()
            factory.assert_called_once_with(prefix="bold-debug-sign-", dir="/tmp")
        self.assertEqual(list(directory.iterdir()), [])
        self.assertFalse(any("sign" in c or "-exportcert" in c for c in self.calls))
        self.assertFalse(self.output.exists())

    def test_invalid_keystore_cleanup(self):
        self.failure = "keytool"
        with self.assertRaisesRegex(RuntimeError, "could not be verified"):
            self.run_build()
        self.assert_clean()
        self.assertFalse(self.output.exists())

    def test_production_identity_rejected(self):
        self.failure = "identity"
        with self.assertRaisesRegex(RuntimeError, "identity is not"):
            self.run_build()
        self.assertFalse(any("sign" in c or "-exportcert" in c for c in self.calls))
        self.assertFalse(self.output.exists())

    def test_mismatched_injected_certificate(self):
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            self.run_build("0" * 64)
        self.assert_clean()
        self.assertFalse(any("sign" in c for c in self.calls))
        self.assertFalse(self.output.exists())

    def test_mismatched_signed_certificate(self):
        self.failure = "verify"
        with self.assertRaisesRegex(RuntimeError, "exactly the expected"):
            self.run_build()
        self.assert_clean()
        self.assertFalse(self.output.exists())

    def test_mock_success_private_output_and_cleanup(self):
        self.run_build()
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)
        self.assert_clean()

    def test_shared_output_directory_rejected(self):
        self.root.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, "owner-private"):
            self.run_build()
        self.assertFalse(self.output.exists())
        self.assertEqual(self.calls, [])

    def test_output_write_failure_leaves_no_final_or_staged_apk(self):
        with patch.object(helper.os, "fsync", side_effect=OSError("mock disk full")):
            with self.assertRaises(OSError):
                self.run_build()
        self.assert_clean()
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".bold-debug-apk-*")), [])

    def test_destination_created_during_signing_is_not_overwritten(self):
        real_link = os.link
        def race(source, destination):
            self.output.write_bytes(b"earlier candidate")
            return real_link(source, destination)
        with patch.object(helper.os, "link", race):
            with self.assertRaises(FileExistsError):
                self.run_build()
        self.assertEqual(self.output.read_bytes(), b"earlier candidate")
        self.assertEqual(list(self.root.glob(".bold-debug-apk-*")), [])
        self.assert_clean()

    def test_no_overwrite(self):
        self.output.write_bytes(b"earlier candidate")
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            self.run_build()
        self.assertEqual(self.output.read_bytes(), b"earlier candidate")
        self.assertEqual(self.calls, [])


class ToolRedaction(unittest.TestCase):
    def test_tool_failure_uses_only_fixed_error(self):
        result = subprocess.CompletedProcess(["fake-tool"], 1, stdout=b"mock sensitive stdout", stderr=b"mock sensitive stderr")
        with patch.object(helper.subprocess, "run", return_value=result) as run:
            with self.assertRaisesRegex(RuntimeError, "^fixed public failure$"):
                helper.checked(["fake-tool"], {}, "fixed public failure")
        self.assertEqual(run.call_args.kwargs["stdout"], subprocess.PIPE)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.PIPE)

    def test_cli_timeout_does_not_echo_tool_output(self):
        error = subprocess.TimeoutExpired(["fake-tool"], 1, output=b"mock sensitive stdout", stderr=b"mock sensitive stderr")
        stderr = io.StringIO()
        with patch.object(helper.sys, "argv", ["helper", "--output", "/tmp/unused", "--expected-cert-sha256", "0" * 64]), patch.object(helper, "build", side_effect=error), patch.object(helper.sys, "stderr", stderr):
            self.assertEqual(helper.main(), 1)
        self.assertEqual(stderr.getvalue(), "Private Debug build/signing failed.\n")


if __name__ == "__main__":
    unittest.main()
