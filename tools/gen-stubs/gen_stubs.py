#!/usr/bin/env python3
"""Generate the VirtualActivity stub pool.

Android decides launchMode / theme / configChanges / orientation from the
manifest entry of the component being started, *before* our code runs. A guest
screen can therefore only get correct behaviour if a stub with those attributes
already exists in the host manifest.

This script writes two files:

    core/host/src/main/java/dev/aether/host/runtime/VirtualStubs.kt
    core/host/src/main/AndroidManifest.xml   (only the block between markers)

Run `./gradlew :core:host:generateVirtualStubs` or this script directly.
CI fails if the generated files are stale.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STUBS_KT = REPO / "core/host/src/main/java/dev/aether/host/runtime/VirtualStubs.kt"
MANIFEST = REPO / "core/host/src/main/AndroidManifest.xml"

PACKAGE = "dev.aether.host.runtime"

# (pool name, launchMode, count, transparent)
POOLS = [
    ("STANDARD", "standard", 12, False),
    ("SINGLE_TOP", "singleTop", 6, False),
    ("SINGLE_TASK", "singleTask", 3, False),
    ("SINGLE_INSTANCE", "singleInstance", 2, False),
    ("TRANSPARENT", "standard", 4, True),
]

# Guard every config change a game can reasonably survive without a restart.
# Reusing the numeric value in XML: see StubPool.DEFAULT_CONFIG_CHANGES.
CONFIG_CHANGES = (
    "orientation|screenSize|smallestScreenSize|screenLayout|"
    "keyboardHidden|density|fontScale|locale|uiMode|navigation"
)

BEGIN = "    <!-- BEGIN GENERATED STUBS -->"
END = "    <!-- END GENERATED STUBS -->"


def class_name(pool: str, index: int) -> str:
    pretty = "".join(part.capitalize() for part in pool.split("_"))
    return f"Stub{pretty}{index:02d}"


def render_kotlin() -> str:
    out = [
        "package dev.aether.host.runtime",
        "",
        "/**",
        " * GENERATED FILE — do not edit. See tools/gen-stubs/gen_stubs.py.",
        " *",
        " * Every stub is a real, declared subclass of [VirtualActivity]. The",
        " * manifest entry for each one (written by the same generator) carries the",
        " * launchMode / theme / configChanges that Android needs to resolve before",
        " * our code ever runs.",
        " */",
    ]

    for pool, _mode, count, transparent in POOLS:
        for i in range(count):
            out += [
                "",
                f"/** Stub #{i} of the {pool} pool. */",
                f"class {class_name(pool, i)} : VirtualActivity()",
            ]

    out += ["", "internal object VirtualStubs {"]
    for pool, _mode, count, _transparent in POOLS:
        names = ", ".join(f"{class_name(pool, i)}::class" for i in range(count))
        out.append(f"    val {pool}: Array<Class<out VirtualActivity>> = arrayOf({names})")
    out += ["", "    val ALL: List<Class<out VirtualActivity>> ="]
    out.append("        " + " + ".join(f"{p[0]}.toList()" for p in POOLS))
    out.append("}")
    out.append("")
    return "\n".join(out)


def render_activities() -> str:
    lines = []
    for pool, mode, count, transparent in POOLS:
        for i in range(count):
            name = class_name(pool, i)
            lines.append(f'    <activity')
            lines.append(f'        android:name="{PACKAGE}.{name}"')
            lines.append(f'        android:launchMode="{mode}"')
            lines.append(f'        android:configChanges="{CONFIG_CHANGES}"')
            lines.append('        android:exported="false"')
            lines.append('        android:hardwareAccelerated="true"')
            lines.append('        android:resizeableActivity="true"')
            lines.append('        android:supportsPictureInPicture="false"')
            lines.append('        android:windowSoftInputMode="adjustResize"')
            if transparent:
                lines.append('        android:theme="@style/AetherTheme.Transparent"')
            else:
                lines.append('        android:theme="@style/AetherTheme"')
            lines.append('        android:taskAffinity="" />')
            lines.append("")
    return "\n".join(lines).rstrip()


def main() -> None:
    STUBS_KT.write_text(render_kotlin(), encoding="utf-8")
    print(f"wrote {STUBS_KT.relative_to(REPO)}")

    manifest = MANIFEST.read_text(encoding="utf-8")
    block = f"{BEGIN}\n{render_activities()}\n{END}"
    if BEGIN in manifest and END in manifest:
        pattern = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.DOTALL)
        manifest = pattern.sub(lambda _m: block, manifest, count=1)
    else:
        # No markers yet: insert before </application>.
        manifest = manifest.replace(
            "</application>", f"\n{block}\n</application>", 1
        )
    MANIFEST.write_text(manifest, encoding="utf-8")
    print(f"updated {MANIFEST.relative_to(REPO)}")


if __name__ == "__main__":
    main()
