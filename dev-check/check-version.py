#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Version consistency check for the 1.12.2 line.

Why this exists: the other three lines expand ``${mod_version}`` into
``mods.toml`` at build time, so the version lives in exactly one place.
1.12.2 has no mods.toml — ``@Mod(version = ...)`` only accepts a compile
time constant, so ``LogiScopeMod.VERSION`` is a hand-written literal.
Two sources of truth drift silently; this script makes the drift loud.

Exit code 0 = consistent, 1 = mismatch (offline-check turns that red).
"""
from __future__ import print_function

import io
import re
import sys

ROOT = "."
PROPS = ROOT + "/gradle.properties"
MOD = ROOT + "/src/main/java/com/jessetech/logiscope/LogiScopeMod.java"


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def main():
    props = read(PROPS)
    m = re.search(r"^mod_version\s*=\s*(\S+)", props, re.M)
    if not m:
        print("  FAIL gradle.properties has no mod_version")
        return 1
    prop_ver = m.group(1)

    src = read(MOD)
    m = re.search(r'VERSION\s*=\s*"([^"]+)"', src)
    if not m:
        print("  FAIL LogiScopeMod.java has no VERSION constant")
        return 1
    java_ver = m.group(1)

    if prop_ver != java_ver:
        print("  FAIL version mismatch: gradle.properties=%s  LogiScopeMod.VERSION=%s"
              % (prop_ver, java_ver))
        return 1
    print("  ok   version = %s (gradle.properties == LogiScopeMod.VERSION)" % prop_ver)

    # A 1.12.2 jar must carry mcmod.info; catching it here is cheaper than
    # finding out in the mod list.
    res = read(ROOT + "/src/main/resources/mcmod.info")
    if '"modid": "logiscope"' not in res:
        print("  FAIL mcmod.info does not declare modid logiscope")
        return 1
    if '"${version}"' not in res:
        print("  FAIL mcmod.info lost its ${version} placeholder (processResources expands it)")
        return 1
    print("  ok   mcmod.info declares logiscope and keeps ${version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
