#!/usr/bin/env python3
"""Owner-run one-time Debug signer setup. Never run automatically or in cloud jobs."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import resource
import secrets
import shutil
import subprocess
import sys

ALIAS = "boldkeys-debug"
CONFIRMATION = "CREATE BOLD KEYS DEBUG SIGNER"
SECRET_NAMES = {"BOLD_DEBUG_KEYSTORE_BASE64", "BOLD_DEBUG_STORE_PASSWORD"}


def fail(message):
    raise RuntimeError(message)


def checked(command, env, message, data=None):
    result = subprocess.run(command, input=data, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=120)
    if result.returncode:
        fail(message)
    return result.stdout


def safe_environment():
    return {k: v for k, v in os.environ.items() if k not in SECRET_NAMES}


def in_repository(directory):
    return any((parent / ".git").exists() for parent in [directory, *directory.parents])


def validate_destination(directory, creating=False):
    # No override/default may place credentials inside a Git working tree.
    absolute = directory.absolute()
    for parent in [absolute, *absolute.parents]:
        if parent.is_symlink():
            fail("Signer path must not contain symlinks.")
    if in_repository(absolute):
        fail("Signer directory must be outside every Git checkout.")
    if creating:
        if directory.exists():
            fail("Signer directory already exists. Nothing will be overwritten.")
        parent = directory.parent.stat()
        if parent.st_uid != os.getuid() or parent.st_mode & 0o022:
            fail("Signer parent must be owned by you and not writable by others.")
    else:
        status = directory.stat()
        if not directory.is_dir() or status.st_uid != os.getuid() or status.st_mode & 0o077:
            fail("Signer directory must be owned by you and mode 0700.")


def confirm_creation(tool, directory):
    if not sys.stdin.isatty():
        fail("Creation requires interactive owner confirmation in a terminal.")
    print("Dedicated Debug signer only. No production key is used.")
    print("Installed keytool: " + str(tool))
    print("New private directory: " + str(directory))
    print("Later manual provisioning uploads this identity to OpenAI PersonalVault and cloud task processes.")
    print("Anyone who obtains this signer and password can sign malicious trusted Debug app updates.")
    if input("Type " + CONFIRMATION + " to generate once: ").strip() != CONFIRMATION:
        fail("Creation cancelled; no key generated.")


def write_private(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(value)


def create_signer(directory, tool):
    validate_destination(directory, creating=True)
    if not tool.is_file() or not os.access(tool, os.X_OK):
        fail("Install a trusted JDK first; its keytool executable is required.")
    confirm_creation(tool, directory)
    # Exclusive directory creation is the no-overwrite boundary, including races.
    directory.mkdir(mode=0o700)
    try:
        password = secrets.token_urlsafe(48)
        env = dict(safe_environment(), BOLD_DEBUG_STORE_PASSWORD=password)
        keystore = directory / "debug-signer.p12"
        checked([str(tool), "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore),
                 "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                 "-dname", "CN=Bold Keys Debug", "-storepass:env", "BOLD_DEBUG_STORE_PASSWORD",
                 "-keypass:env", "BOLD_DEBUG_STORE_PASSWORD"], env, "Dedicated signer creation failed.")
        if not keystore.is_file() or keystore.is_symlink():
            fail("Expected dedicated signer file was not created.")
        keystore.chmod(0o600)
        cert = checked([str(tool), "-exportcert", "-keystore", str(keystore), "-alias", ALIAS,
                        "-storepass:env", "BOLD_DEBUG_STORE_PASSWORD"], env, "Public certificate export failed.")
        if not cert:
            fail("Public certificate export was empty.")
        pin = hashlib.sha256(cert).hexdigest()
        write_private(directory / "store-password.txt", password.encode("ascii"))
        write_private(directory / "certificate-sha256.txt", (pin + "\n").encode("ascii"))
    except BaseException:
        # This directory was exclusively created above; no pre-existing signer is removed.
        shutil.rmtree(directory)
        raise
    print("Dedicated signer saved locally; no upload or provisioning was performed.")
    print("Public certificate SHA-256 (safe to share): " + pin)


def read_private(directory, name):
    validate_destination(directory)
    path = directory / name
    if path.is_symlink():
        fail("Signer files must not be symlinks.")
    status = path.stat()
    if not path.is_file() or status.st_uid != os.getuid() or status.st_mode & 0o077:
        fail("Signer files must be owned by you and mode 0600.")
    return path.read_bytes()


def copy_secret(directory, kind):
    print("Clipboard warning: Universal Clipboard, clipboard managers and password managers may retain/sync secrets.")
    name = "debug-signer.p12" if kind == "keystore" else "store-password.txt"
    value = read_private(directory, name)
    if kind == "keystore":
        value = base64.b64encode(value)  # One strict line; never print or persist a base64 copy.
    checked(["/usr/bin/pbcopy"], safe_environment(), "Clipboard copy failed.", data=value)
    variable = "BOLD_DEBUG_KEYSTORE_BASE64" if kind == "keystore" else "BOLD_DEBUG_STORE_PASSWORD"
    print("Copied secret. Paste only into the PersonalVault field " + variable + ".")
    print("Clear the clipboard immediately after pasting. Clipboard sync/history may retain copies.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["create", "copy-keystore", "copy-password", "show-pin", "clear-clipboard"])
    parser.add_argument("--directory", type=Path, default=Path.home() / ".bold-keys-debug-signer")
    parser.add_argument("--keytool", type=Path, help="Trusted installed JDK keytool; no installation/download is performed")
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    try:
        if sys.platform != "darwin":
            fail("This one-time owner helper is for macOS only.")
        directory = args.directory.expanduser().absolute()
        if args.action == "create":
            path = args.keytool or Path(shutil.which("keytool") or "/nonexistent/keytool")
            create_signer(directory, path.resolve())
        elif args.action.startswith("copy-"):
            copy_secret(directory, args.action.removeprefix("copy-"))
        elif args.action == "show-pin":
            pin = read_private(directory, "certificate-sha256.txt").decode("ascii").strip()
            if len(pin) != 64 or any(c not in "0123456789abcdef" for c in pin):
                fail("Stored public certificate pin is invalid.")
            print("Public certificate SHA-256 (safe to share): " + pin)
        else:
            checked(["/usr/bin/pbcopy"], safe_environment(), "Clipboard clear failed.", data=b"")
            print("Local clipboard cleared; remote clipboard/history copies cannot be revoked here.")
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError):
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, RuntimeError) else "Owner setup action failed; no secret output shown.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
