#!/usr/bin/env python3
"""Compare the local baseline revision and working tree with the cached Kotlin compiler; no downloads."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"

def jar(group, name, version):
    matches = list((cache / group / name / version).glob("*/*.jar"))
    if not matches:
        raise SystemExit(f"Missing cached dependency: {group}:{name}:{version}; run the Android build first")
    return str(matches[0])

compiler = [jar("org.jetbrains.kotlin", name, "2.0.21") for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
             jar("org.jetbrains", "annotations", "13.0")]
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
source = root / "app/src/main/java/ai/boldkeys"
with tempfile.TemporaryDirectory(prefix="bold-suggestions-") as directory:
    temp = Path(directory)
    baseline = subprocess.check_output(["git", "show", "e8ca3757286e7abf277432bbe7467349c624d305:app/src/main/java/ai/boldkeys/SpellEngine.kt"], cwd=root, text=True)
    (temp / "BaselineSpellEngine.kt").write_text(baseline.replace("SpellEngine", "BaselineSpellEngine"))
    files = [str(source / f"{name}.kt") for name in ("SpellEngine", "LearningData", "Spelling", "Layouts", "SuggestionSafety")]
    files += [str(temp / "BaselineSpellEngine.kt"), str(root / "tools/BenchmarkSuggestions.kt")]
    subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", compiler[1], "-d", str(temp / "classes"), *files], check=True)
    subprocess.run([java, "-Xmx1g", "-cp", os.pathsep.join([str(temp / "classes"), compiler[1]]),
                    "BenchmarkSuggestionsKt", str(root / "app/src/main/assets")], check=True)
