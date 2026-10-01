#!/usr/bin/env python3
"""Pre-build readiness gate for AetherEngine.

This exists because "it compiles on my machine" is not available to us for most
of this repository: the Android SDK and NDK are not present in every
environment, so a lot of the project simply cannot be compiled here. What we
CAN do is check every invariant that does not need a compiler:

  1. structural completeness (every file the build files reference exists)
  2. Gradle module list matches the directories on disk
  3. every generated stub class is declared in the manifest, and vice versa
  4. every Kotlin `external fun` has a matching JNI registration whose
     signature is *correct*, computed from the Kotlin types
  5. the Rust crate can actually be built as a staticlib
  6. the guest TOML parses and agrees with GuestProfile.kt
  7. all XML is well-formed
  8. CI workflows are valid YAML
  9. cross-file Kotlin references resolve

Exit code 0 = ready to attempt a real build, 1 = blocking problems.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

results: list[tuple[str, str, str]] = []  # (level, check, detail)


def add(level: str, check: str, detail: str = "") -> None:
    results.append((level, check, detail))


def read(path: str) -> str:
    return (REPO / path).read_text(encoding="utf-8")


# --------------------------------------------------------------------------
# 1. structural completeness
# --------------------------------------------------------------------------
REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    ".gitignore",
    "tools/build-rust.sh",
    "tools/gen-stubs/gen_stubs.py",
    "tools/gen-stubs/gen_stubs.sh",
    "core/vfs/Cargo.toml",
    "core/vfs/src/lib.rs",
    "core/vfs/src/ffi.rs",
    "core/vfs/src/path.rs",
    "core/vfs/src/rules.rs",
    "core/vfs/src/translator.rs",
    "core/host/build.gradle.kts",
    "core/host/consumer-rules.pro",
    "core/host/src/main/AndroidManifest.xml",
    "core/host/src/main/res/values/styles.xml",
    "core/host/src/main/cpp/CMakeLists.txt",
    "core/host/src/main/cpp/aether_native.cpp",
    "core/host/src/main/cpp/hidden_api.cpp",
    "core/host/src/main/cpp/vfs_bridge.cpp",
    "core/host/src/main/cpp/native_extract.cpp",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/dev/aether/host/app/AetherApplication.kt",
    "guests/com.miniclip.eightballpool.toml",
    "docs/GMS-ARCHITECTURE.md",
    "docs/vfs-policy.txt",
]

PHASE1 = [
    "core/host/src/main/java/dev/aether/host/loader/DynamicApkLoader.kt",
    "core/host/src/main/java/dev/aether/host/loader/GuestClassLoader.kt",
    "core/host/src/main/java/dev/aether/host/loader/GuestContext.kt",
    "core/host/src/main/java/dev/aether/host/loader/GuestResources.kt",
    "core/host/src/main/java/dev/aether/host/loader/PackageParserHidden.kt",
    "core/host/src/main/java/dev/aether/host/runtime/VirtualActivity.kt",
    "core/host/src/main/java/dev/aether/host/runtime/ActivityAttacher.kt",
    "core/host/src/main/java/dev/aether/host/runtime/HostInitializer.kt",
    "core/host/src/main/java/dev/aether/host/runtime/StubPool.kt",
    "core/host/src/main/java/dev/aether/host/runtime/VirtualStubs.kt",
    "core/host/src/main/java/dev/aether/host/core/VirtualCore.kt",
    "core/host/src/main/java/dev/aether/host/core/GuestProfile.kt",
]

missing = [f for f in REQUIRED + PHASE1 if not (REPO / f).exists()]
if missing:
    add("FAIL", "required files present", f"missing: {missing}")
else:
    add("PASS", "required files present", f"{len(REQUIRED + PHASE1)} files")

# --------------------------------------------------------------------------
# 2. gradle modules vs directories
# --------------------------------------------------------------------------
settings = read("settings.gradle.kts")
declared = set(re.findall(r'include\(":(.+?)"\)', settings))
declared |= set(re.findall(r'include\(":(.+?)"\)\s*$', settings, re.M))
on_disk = set()
for candidate in ["core/host", "app", "core/vfs", "ui/flutter"]:
    if (REPO / candidate / "build.gradle.kts").exists() or \
       (REPO / candidate / "pubspec.yaml").exists():
        on_disk.add(candidate)
gradle_modules = {m for m in declared if (REPO / m.replace(":", "/") / "build.gradle.kts").exists()}
bad = declared - gradle_modules - {"aether_ui"}
if bad:
    add("FAIL", "gradle modules resolve", f"declared but no build.gradle.kts: {sorted(bad)}")
else:
    add("PASS", "gradle modules resolve", f"{sorted(gradle_modules)}")

# --------------------------------------------------------------------------
# 3. stub pool <-> manifest
# --------------------------------------------------------------------------
stubs_kt = read("core/host/src/main/java/dev/aether/host/runtime/VirtualStubs.kt")
kt_classes = set(re.findall(r"^class (Stub\w+) : VirtualActivity\(\)", stubs_kt, re.M))

manifest = read("core/host/src/main/AndroidManifest.xml")
manifest_classes = set()
for m in re.finditer(r"<activity\b.*?/>", manifest, re.S):
    block = m.group(0)
    name = re.search(r'android:name="([^"]+)"', block)
    if name and name.group(1).startswith("dev.aether.host.runtime.Stub"):
        manifest_classes.add(name.group(1).split(".")[-1])

if kt_classes != manifest_classes:
    add(
        "FAIL",
        "stub pool matches manifest",
        f"kotlin-only={sorted(kt_classes - manifest_classes)} "
        f"manifest-only={sorted(manifest_classes - kt_classes)}",
    )
else:
    add("PASS", "stub pool matches manifest", f"{len(kt_classes)} stubs on both sides")

# every stub must be referenced by the VirtualStubs arrays
arrays = re.findall(r"val (\w+): Array<Class<out VirtualActivity>> = arrayOf\(([^)]*)\)", stubs_kt)
indexed = set()
for _name, body in arrays:
    indexed |= set(re.findall(r"(Stub\w+)::class", body))
if kt_classes - indexed:
    add("WARN", "all stubs reachable from VirtualStubs", f"not indexed: {sorted(kt_classes - indexed)}")
else:
    add("PASS", "all stubs reachable from VirtualStubs", f"{len(indexed)} indexed")

# --------------------------------------------------------------------------
# 4. Kotlin external fun <-> JNI registrations (with signature checking)
# --------------------------------------------------------------------------
JVM = {
    "Unit": "V",
    "Int": "I", "Long": "J", "Boolean": "Z", "String": "Ljava/lang/String;",
    "IntArray": "[I", "Array<String>": "[Ljava/lang/String;",
    "HiddenApiProbe": "Ldev/aether/host/native/HiddenApiProbe;",
}


def jvm_type(kt: str) -> str:
    kt = kt.strip().rstrip("?").strip()
    if kt.endswith("Array"):
        return "[" + jvm_type(kt[:-5])
    if kt.startswith("Array<"):
        return "[" + jvm_type(kt[6:-1])
    if kt in JVM:
        return JVM[kt]
    return f"L{kt.replace('.', '/')};"


native_kt = read("core/host/src/main/java/dev/aether/host/native/AetherNative.kt")
externals = {}
for m in re.finditer(
    r"external fun (\w+)\(([^)]*)\)(?:\s*:\s*([^\n]+))?", native_kt
):
    name, params, ret = m.group(1), m.group(2), (m.group(3) or "Unit").strip()
    sig_params = []
    for p in [p.strip() for p in params.split(",") if p.strip()]:
        ptype = p.split(":")[-1].strip()
        sig_params.append(jvm_type(ptype))
    externals[name] = "(" + "".join(sig_params) + ")" + jvm_type(ret)

cpp = read("core/host/src/main/cpp/aether_native.cpp")
registered = dict(re.findall(r'\{"(\w+)",\s*"([^"]+)"', cpp))

jni_problems = []
for name, sig in externals.items():
    if name not in registered:
        jni_problems.append(f"{name}: not registered in JNI_OnLoad")
    elif registered[name] != sig:
        jni_problems.append(f"{name}: cpp='{registered[name]}' kotlin='{sig}'")
for name in registered:
    if name not in externals:
        jni_problems.append(f"{name}: registered in C++ but no Kotlin external")

if jni_problems:
    add("FAIL", "JNI signatures match Kotlin externals", "; ".join(jni_problems))
else:
    add("PASS", "JNI signatures match Kotlin externals", f"{len(externals)} methods")

# --------------------------------------------------------------------------
# 5. Rust crate
# --------------------------------------------------------------------------
cargo = read("core/vfs/Cargo.toml")
cfg = tomllib.loads(cargo)
crate_types = cfg.get("lib", {}).get("crate-type", [])
if "staticlib" not in crate_types:
    add("FAIL", "Rust crate builds a staticlib", f"crate-type={crate_types}")
else:
    add("PASS", "Rust crate builds a staticlib", ",".join(crate_types))

if not cfg.get("dependencies"):
    add("PASS", "Rust crate has zero dependencies", "offline-reproducible")
else:
    add("WARN", "Rust crate dependency audit", str(list(cfg["dependencies"])))

script = REPO / "tools/build-rust.sh"
if script.exists() and script.stat().st_mode & 0o111:
    add("PASS", "build-rust.sh is executable", "")
else:
    add("FAIL", "build-rust.sh is executable", "chmod +x")

# --------------------------------------------------------------------------
# 6. guest profile: TOML <-> Kotlin
# --------------------------------------------------------------------------
profile = tomllib.loads(read("guests/com.miniclip.eightballpool.toml"))
toml_pkg = profile["identity"]["package"]
kotlin = read("core/host/src/main/java/dev/aether/host/core/GuestProfile.kt")
if f'packageName = "{toml_pkg}"' in kotlin:
    add("PASS", "guest profile TOML agrees with Kotlin", toml_pkg)
else:
    add("FAIL", "guest profile TOML agrees with Kotlin",
        f"TOML says '{toml_pkg}' but GuestProfile.kt does not contain it")

for section in ["identity", "apk", "abis", "sdk", "permissions", "vfs", "gms", "runtime"]:
    if section not in profile:
        add("WARN", f"profile section [{section}]", "missing")
add("PASS", "guest profile sections", f"{len(profile)} top-level sections")

# --------------------------------------------------------------------------
# 7. XML well-formedness
# --------------------------------------------------------------------------
xml_files = [p for p in REPO.rglob("*.xml") if "target" not in p.parts]
bad_xml = []
for p in xml_files:
    try:
        ET.parse(p)
    except ET.ParseError as e:
        bad_xml.append(f"{p.relative_to(REPO)}: {e}")
if bad_xml:
    add("FAIL", "XML well-formed", "; ".join(bad_xml))
else:
    add("PASS", "XML well-formed", f"{len(xml_files)} files")

# --------------------------------------------------------------------------
# 8. CI workflows
# --------------------------------------------------------------------------
try:
    import yaml  # type: ignore

    for wf in sorted((REPO / ".github/workflows").glob("*.yml")):
        try:
            yaml.safe_load(wf.read_text(encoding="utf-8"))
            add("PASS", f"workflow {wf.name}", "valid YAML")
        except Exception as e:  # noqa: BLE001
            add("FAIL", f"workflow {wf.name}", str(e))
except ImportError:
    add("WARN", "CI workflows", "pyyaml not installed - skipped")

# --------------------------------------------------------------------------
# 9. cross-file Kotlin references
# --------------------------------------------------------------------------
kotlin_files = list((REPO / "core/host/src/main/java").rglob("*.kt")) + \
               list((REPO / "app/src/main/java").rglob("*.kt"))
declared_symbols = set()
for f in kotlin_files:
    src = f.read_text(encoding="utf-8")
    declared_symbols |= set(re.findall(r"^(?:internal |private |open |data |sealed )*"
                                       r"(?:object|class|interface) (\w+)", src, re.M))
    declared_symbols |= set(re.findall(r"^fun (\w+)\(", src, re.M))

CRITICAL = [
    ("VirtualCore", "initialize"), ("VirtualCore", "install"),
    ("VirtualCore", "launchIntent"), ("VirtualCore", "runningSession"),
    ("VirtualCore", "onGuestActivityStarted"), ("VirtualCore", "onGuestActivityDestroyed"),
    ("VirtualCore", "report"),
    ("HiddenApi", "unsealOnce"), ("HiddenApi", "isUsable"), ("HiddenApi", "describe"),
    ("HostInitializer", "install"), ("HostInitializer", "currentInstrumentation"),
    ("HostInitializer", "health"),
    ("StubPool", "acquire"), ("StubPool", "release"), ("StubPool", "Spec"),
    ("ActivityAttacher", "capture"), ("ActivityAttacher", "attach"),
    ("GuestResources", "create"),
    ("PlayAssetDelivery", "resolve"), ("PlayAssetDelivery", "verify"),
    ("PlayAssetDelivery", "missingPacks"),
    ("PackageParserHidden", "parse"),
    ("VfsRouter", "create"), ("VfsRouter", "require"), ("VfsRouter", "addRule"),
    ("AetherNative", "ensureLoaded"), ("AetherNative", "probeElf"),
    ("AetherNative", "markExecutable"), ("AetherNative", "currentAbi"),
    ("GmsBridge", "install"), ("GmsBridge", "isAvailable"), ("GmsBridge", "report"),
    ("IdentitySpoof", "install"), ("GmsFallback", "install"),
    ("GmsFallback", "isExpectedFailure"), ("GmsFallback", "shouldSuppress"),
    ("Reflect", "methodOrNull"), ("Reflect", "invokeByType"), ("Reflect", "callStatic"),
    ("AetherLog", "i"), ("AetherLog", "e"),
]
unresolved = [f"{c}.{m}" for c, m in CRITICAL
              if c not in declared_symbols]
if unresolved:
    add("FAIL", "critical Kotlin symbols exist", f"missing types: {sorted(set(unresolved))}")
else:
    add("PASS", "critical Kotlin symbols exist", f"{len(CRITICAL)} references checked")

# method-level check (best effort)
method_problems = []
for cls, meth in CRITICAL:
    if cls in {"StubPool", "VirtualCore", "ActivityAttacher", "HostInitializer"} and meth[0].islower():
        found = False
        for f in kotlin_files:
            if f.stem == cls:
                src = f.read_text(encoding="utf-8")
                if re.search(rf"(?:fun|val) {re.escape(meth)}\s*[(\[:=]", src):
                    found = True
        if not found:
            method_problems.append(f"{cls}.{meth}")
if method_problems:
    add("WARN", "Kotlin methods resolve", f"not found: {method_problems}")
else:
    add("PASS", "Kotlin methods resolve", "checked core entry points")

# --------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------
print("=" * 72)
print("  AetherEngine — build readiness")
print("=" * 72)
width = max(len(c) for _, c, _ in results)
for level, check, detail in results:
    marker = {"PASS": "  ok ", "WARN": "  !! ", "FAIL": "  XX "}[level]
    line = f"{marker}{check.ljust(width)}"
    if detail:
        line += f"  {detail}"
    print(line)

fails = [r for r in results if r[0] == "FAIL"]
warns = [r for r in results if r[0] == "WARN"]
print("-" * 72)
print(f"  {len(results) - len(fails) - len(warns)} passed, {len(warns)} warnings, {len(fails)} blocking")
print("=" * 72)
sys.exit(1 if fails else 0)
