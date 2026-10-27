#!/usr/bin/env python3
"""Build and pin-sign the opt-in app. No key generation or credential provisioning."""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import resource
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SECRET_NAMES = {"BOLD_DEBUG_KEYSTORE_BASE64", "BOLD_DEBUG_STORE_PASSWORD"}


def fail(message):
    raise RuntimeError(message)


def checked(command, env, message):
    # Tool errors can echo credential/configuration values. Never relay their output.
    result = subprocess.run(command, env=env, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=1800)
    if result.returncode:
        fail(message)
    return result.stdout


def build(output, expected_pin, sdk, java_home):
    pin = expected_pin.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        fail("An explicit expected SHA-256 certificate pin is required.")
    encoded = os.environ.get("BOLD_DEBUG_KEYSTORE_BASE64", "")
    password = os.environ.get("BOLD_DEBUG_STORE_PASSWORD", "")
    if not encoded or not password:
        fail("PersonalVault must inject BOLD_DEBUG_KEYSTORE_BASE64 and BOLD_DEBUG_STORE_PASSWORD.")
    if output.exists() or output.is_symlink():
        fail("Output already exists; choose a new private destination.")
    if not output.parent.is_dir():
        fail("Output parent directory must already exist.")
    parent = output.parent.stat()
    if parent.st_uid != os.getuid() or parent.st_mode & 0o077:
        fail("Output parent directory must be owner-private (mode 0700).")
    # Passwords/base64 never reach Gradle, its daemon, command arguments or a build log.
    env = {k: v for k, v in os.environ.items() if k not in SECRET_NAMES}
    env["BOLD_KEYSTORE_PROPERTIES"] = "/nonexistent/bold-debug-no-production-credentials"
    tools = sdk / "build-tools" / "35.0.0"
    signer = tools / "apksigner"
    aapt = tools / "aapt"
    keytool = java_home / "bin" / "keytool"
    if not all(p.is_file() for p in (signer, aapt, keytool)):
        fail("Requires JDK keytool and Android SDK build-tools 35.0.0 (apksigner/aapt).")
    checked([str(ROOT / "gradlew"), "--no-daemon", "--no-configuration-cache", "--console=plain", "--max-workers=4",
             "-PunsignedRelease=true", "-PboldDebug=true", ":app:assemblePrivateDebug"],
            env, "Unsigned private Debug build failed; rerun unsigned checks without injected credentials.")
    unsigned = ROOT / "app/build/outputs/apk/privateDebug/app-privateDebug-unsigned.apk"
    if not unsigned.is_file():
        fail("Expected unsigned private Debug APK is missing.")
    badging = checked([str(aapt), "dump", "badging", str(unsigned)], env, "APK metadata check failed.")
    if not (b"package: name='ai.boldkeys.debug' " in badging
            and b"application-label:'Bold Keys Debug'" in badging):
        fail("APK identity is not Bold Keys Debug / ai.boldkeys.debug.")
    with tempfile.TemporaryDirectory(prefix="bold-debug-sign-", dir="/tmp") as directory:
        temporary = Path(directory)  # mkdtemp creates mode 0700; main also sets umask 077.
        # Ignore TMPDIR and defend against future caller/refactor changes: never decode
        # key material beneath the checkout, including its ignored build outputs.
        if temporary.resolve().is_relative_to(ROOT.resolve()):
            fail("Signing temporary directory must be outside the repository.")
        keystore = temporary / "injected.keystore"
        try:
            material = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error):
            fail("Injected keystore is not valid base64.")
        keystore.write_bytes(material)
        keystore.chmod(0o600)
        del material, encoded
        signing_env = dict(env, BOLD_DEBUG_STORE_PASSWORD=password)
        cert = checked([str(keytool), "-exportcert", "-keystore", str(keystore),
                        "-alias", "boldkeys-debug", "-storepass:env", "BOLD_DEBUG_STORE_PASSWORD"],
                       signing_env, "Injected keystore/alias/password could not be verified.")
        if hashlib.sha256(cert).hexdigest() != pin:
            fail("Injected certificate does not match the expected public pin.")
        candidate = temporary / "Bold-Keys-Debug.apk"
        checked([str(signer), "sign", "--ks", str(keystore), "--ks-key-alias", "boldkeys-debug",
                 "--ks-pass", "env:BOLD_DEBUG_STORE_PASSWORD", "--key-pass", "env:BOLD_DEBUG_STORE_PASSWORD",
                 "--out", str(candidate), str(unsigned)], signing_env, "Pinned Debug signing failed.")
        verified = checked([str(signer), "verify", "--verbose", "--print-certs", str(candidate)],
                           env, "Signed Debug APK verification failed.").decode("utf-8", errors="replace")
        pins = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", verified, re.MULTILINE)
        if [p.lower() for p in pins] != [pin]:
            fail("Signed APK must have exactly the expected signer certificate.")
        # Stage/fsync beside the destination, then publish atomically and exclusively.
        # A failed copy leaves no final APK; hardlink creation never overwrites a symlink.
        with tempfile.NamedTemporaryFile(prefix=".bold-debug-apk-", dir=output.parent) as destination:
            os.fchmod(destination.fileno(), 0o600)
            destination.write(candidate.read_bytes())
            destination.flush()
            os.fsync(destination.fileno())
            os.link(destination.name, output)
    print("Verified private Debug APK written to the requested destination.")
    print("SHA-256: " + hashlib.sha256(output.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-cert-sha256", required=True,
                        help="Public pin independently confirmed by the owner; no default/trust-on-first-use")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", ""))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME", ""))
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    try:
        build(args.output.absolute(), args.expected_cert_sha256, args.sdk, args.java_home)
    except (RuntimeError, OSError, subprocess.SubprocessError):
        # Report only our fixed messages; arbitrary OS/tool exceptions may contain secrets.
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, RuntimeError) else "Private Debug build/signing failed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
