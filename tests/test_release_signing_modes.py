#!/usr/bin/env python3
"""Secret-free Gradle functional checks. Needs JDK/Android SDK/NDK and network/cache.

Every invocation overrides BOLD_KEYSTORE_PROPERTIES with disposable public fixtures;
no private keystore is read, generated, or signed. Use --gradle to select a local Gradle.
The unsigned build is not an installable/distributable release until separately signed.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default=str(ROOT / "gradlew"))
    parser.add_argument("--logs", type=Path)
    args = parser.parse_args()
    logs = args.logs or Path(tempfile.mkdtemp(prefix="bold-keys-signing-checks-"))
    logs.mkdir(parents=True, exist_ok=True)
    results = []

    with tempfile.TemporaryDirectory(prefix="bold-keys-signing-fixtures-") as temporary:
        fixtures = Path(temporary)
        missing = fixtures / "missing.properties"
        malformed = fixtures / "malformed.properties"
        malformed.write_text("value=\\uZZZZ\n")
        incomplete = fixtures / "incomplete.properties"
        incomplete.write_text("storeFile=missing.jks\n")
        debug = fixtures / "debug-alias.properties"
        debug.write_text("storeFile=missing.jks\nstorePassword=public-fixture\nkeyAlias=androiddebugkey\nkeyPassword=public-fixture\n")

        def run(name, arguments, properties=missing, expected_error=None):
            env = dict(os.environ, BOLD_KEYSTORE_PROPERTIES=str(properties))
            command = [args.gradle, "--no-daemon", "--console=plain", "--max-workers=4", *arguments]
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, timeout=900)
            (logs / f"{name}.log").write_text(result.stdout)
            if expected_error:
                assert result.returncode != 0, f"{name}: unexpectedly succeeded"
                assert expected_error in result.stdout, f"{name}: wrong failure; see {logs / (name + '.log')}"
            else:
                assert result.returncode == 0, f"{name}: failed; see {logs / (name + '.log')}"
            results.append({"check": name, "passed": True, "exit_code": result.returncode})
            print(f"PASS {name}", flush=True)

        run("missing-signing-assemble", [":app:assembleRelease"],
            expected_error="Release signing properties are missing or unreadable.")
        run("explicit-signed-mode", ["-PunsignedRelease=false", ":app:verifyReleaseSigningMode"],
            expected_error="Release signing properties are missing or unreadable.")
        run("malformed-properties", [":app:verifyReleaseSigningMode"], malformed,
            "Release signing properties could not be loaded.")
        run("incomplete-properties", [":app:verifyReleaseSigningMode"], incomplete,
            "Release signing properties are incomplete:")
        run("reject-debug-alias", [":app:verifyReleaseSigningMode"], debug,
            "The Android debug key must not be used for a release.")
        run("invalid-unsigned-flag", ["-PunsignedRelease=tru", ":app:verifyReleaseSigningMode"],
            expected_error="unsignedRelease must be exactly true or false.")
        # Verify the existing local configuration still selects release signing. This is only
        # a plaintext placeholder, never a keystore, and no packaging/signing task is requested.
        placeholder = fixtures / "not-a-keystore.txt"
        placeholder.write_text("Public test fixture, not key material.\n")
        configured = fixtures / "configured.properties"
        configured.write_text(f"storeFile={placeholder}\nstorePassword=public-fixture\nkeyAlias=release-fixture\nkeyPassword=public-fixture\n")
        init = fixtures / "assert-signing.gradle"
        init.write_text("""gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':app')
    app.tasks.register('assertReleaseSigningConfigured') {
        doLast {
            def config = app.android.buildTypes.release.signingConfig
            assert config != null && config.name == 'release'
            assert config.keyAlias == 'release-fixture'
            assert !app.android.buildTypes.release.debuggable
        }
    }
}
""")
        run("local-signing-config-preserved", ["--init-script", str(init),
            ":app:verifyReleaseSigningMode", ":app:assertReleaseSigningConfigured"], configured)
        abi_init = fixtures / "assert-qa-abi.gradle"
        abi_init.write_text("""gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':app')
    app.tasks.register('assertQaAbiIsolation') {
        doLast {
            def x86 = app.findProperty('qaX86Debug') == 'true'
            def debug = app.android.buildTypes.debug
            def release = app.android.buildTypes.release
            def armFlags = ['-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod', '-DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod']
            assert app.android.defaultConfig.ndk.abiFilters.empty
            assert debug.ndk.abiFilters == ([x86 ? 'x86_64' : 'arm64-v8a'] as Set)
            assert release.ndk.abiFilters == (['arm64-v8a'] as Set)
            assert release.externalNativeBuild.cmake.arguments == armFlags
            assert debug.externalNativeBuild.cmake.arguments == (x86 ? ['-DGGML_NATIVE=OFF'] : armFlags)
            assert !release.debuggable
            assert release.signingConfig == null
        }
    }
}
""")
        run("default-debug-release-abi-isolation", ["-PunsignedRelease=true", "--init-script", str(abi_init),
            ":app:assertQaAbiIsolation"], malformed)
        run("x86-debug-release-abi-isolation", ["-PunsignedRelease=true", "-PqaX86Debug=true", "--init-script", str(abi_init),
            ":app:assertQaAbiIsolation"], malformed)
        run("invalid-debug-abi-flag", ["-PunsignedRelease=true", "-PqaX86Debug=tru", ":app:help"], malformed,
            "qaX86Debug must be exactly true or false.")
        run("credential-free-tests-lint", [":app:testReleaseUnitTest", ":app:lintRelease"])

        # On Linux, also prove the unsigned build never even opens/reads the supplied properties.
        watch = None
        if sys.platform.startswith("linux"):
            libc = ctypes.CDLL(None, use_errno=True)
            watch = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
            assert watch >= 0, "inotify unavailable; cannot verify secret-free reads"
            assert libc.inotify_add_watch(watch, os.fsencode(malformed), 0x20 | 0x01) >= 0
        try:
            run("unsigned-tests-lint-assemble", ["-PunsignedRelease=true", ":app:testReleaseUnitTest",
                ":app:lintRelease", ":app:assembleRelease"], malformed)
            if watch is not None:
                try:
                    events = os.read(watch, 65536)
                except BlockingIOError:
                    events = b""
                assert not events, "Unsigned build opened/read the signing properties"
                results.append({"check": "unsigned-no-property-reads", "passed": True})
                print("PASS unsigned-no-property-reads", flush=True)
            else:
                print("NOTE: property-read monitoring requires Linux; not measured here", flush=True)
        finally:
            if watch is not None:
                os.close(watch)

        # Opting into the debug emulator ABI must not alter even one release APK byte.
        release_apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
        normal_digest = hashlib.sha256(release_apk.read_bytes()).hexdigest()
        run("debug-abi-option-release-build", ["-PunsignedRelease=true", "-PqaX86Debug=true", ":app:assembleRelease"], malformed)
        assert hashlib.sha256(release_apk.read_bytes()).hexdigest() == normal_digest, "Debug ABI option changed release APK"
        results.append({"check": "debug-abi-option-release-byte-identity", "passed": True})
        print("PASS debug-abi-option-release-byte-identity", flush=True)

    apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    build_tools = sdk / "build-tools/36.0.0"
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    build_script = (ROOT / "app/build.gradle.kts").read_text()
    version_code = re.search(r"versionCode\s*=\s*(\d+)", build_script).group(1)
    version_name = re.search(r'versionName\s*=\s*"([^"\n]+)"', build_script).group(1)
    assert f"package: name='ai.boldkeys' versionCode='{version_code}' versionName='{version_name}'" in badging
    assert "native-code: 'arm64-v8a'" in badging
    assert "application-debuggable" not in badging
    verify = subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", str(apk)],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (logs / "unsigned-apk-signature.log").write_text(verify.stdout)
    assert verify.returncode == 1 and "DOES NOT VERIFY" in verify.stdout, "APK was signed or verification did not run"
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "APK ZIP entries are corrupt"
        assert not any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF)", name, re.IGNORECASE)
                       for name in archive.namelist()), "APK contains JAR signature files"
    assert b"APK Sig Block 42" not in apk.read_bytes(), "APK contains an APK signing block"
    results.append({"check": "unsigned-apk-metadata-signatures", "passed": True,
                    "apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
    print("PASS unsigned-apk-metadata-signatures", flush=True)
    (logs / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"All checks passed. Logs: {logs}")


if __name__ == "__main__":
    main()
