#!/usr/bin/env python3
"""Phase 1b fallout fixer (2026-09-22) — top-level-declaration index only.

Deletes dead imports the compiler flags, inserts correct imports for
still-unresolved top-level names. Loops until clean or stuck.
"""
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path("/home/twain/AndroidStudioProjects/tail")
SRC_DIRS = [
    ROOT / "app/src/main/java",
    ROOT / "app/src/test/java",
    ROOT / "core-data/src/main/java",
]
COMPILE_TASKS = [
    ":app:compileDebugKotlin",
    ":app:compileDebugUnitTestKotlin",
    ":core-data:compileDebugKotlin",
    ":core-data:compileDebugUnitTestKotlin",
]

MODS = (r"(?:public|private|internal|protected|open|abstract|final|sealed|data|enum|"
        r"value|inline|const|lateinit|external|suspend|operator|infix|tailrec|expect|actual)")
DECL_RE = re.compile(
    rf"^(?:@\w+(?:\([^)]*\))?\s+)*(?:{MODS}\s+)*"
    r"(fun|val|var|class|interface|object|typealias)\s+"
    r"([A-Za-z_][A-Za-z0-_.<>?]*\.)?"
    r"`?([A-Za-z_][A-Za-z0-9_]*)`?"
)
ERR_RE = re.compile(r"^e: file://(.*?\.kt):(\d+):\d+ Unresolved reference '([^']+)'")

def package_of(path: Path) -> str:
    parts = list(path.relative_to(ROOT).parts)
    return ".".join(parts[parts.index("java") + 1 : -1])

def build_index():
    idx = defaultdict(set)
    for src in SRC_DIRS:
        for kt in src.rglob("*.kt"):
            pkg = package_of(kt)
            for line in kt.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith((" ", "\t", "import", "package")) or not line.strip():
                    continue
                m = DECL_RE.match(line)
                if m:
                    idx[m.group(3)].add((pkg, m.group(2) is not None))
    return idx

def compile_errors():
    proc = subprocess.run(
        ["./gradlew", *COMPILE_TASKS, "-q", "--console=plain"],
        cwd=ROOT, capture_output=True, text=True, timeout=1200,
    )
    errs = []
    for line in (proc.stdout + proc.stderr).splitlines():
        m = ERR_RE.match(line)
        if m:
            errs.append((Path(m.group(1)), int(m.group(2)), m.group(3)))
    return errs

def insert_import(path: Path, fqn: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if re.search(rf"^import {re.escape(fqn)}$", text, re.M):
        return False
    lines = text.splitlines(keepends=True)
    import_idxs = [i for i, l in enumerate(lines) if l.strip().startswith("import ")]
    if import_idxs:
        last = import_idxs[-1]
        lines.insert(last + 1, f"import {fqn}\n")
    else:
        ins = 1
        if ins < len(lines) and lines[ins].strip() == "":
            ins += 1
        lines.insert(ins, f"import {fqn}\n")
        lines.insert(ins + 1, "\n")
    path.write_text("".join(lines), encoding="utf-8")
    return True

def main():
    idx = build_index()
    print(f"indexed {len(idx)} top-level names")
    for rnd in range(1, 16):
        errs = compile_errors()
        if not errs:
            print(f"round {rnd}: clean")
            return 0
        byfile = defaultdict(set)
        for p, l, _n in errs:
            byfile[p].add(l)
        removed = inserted = manual = 0
        for p, linenos in byfile.items():
            lines = p.read_text().splitlines(keepends=True)
            dead = sorted((l for l in linenos if lines[l-1].strip().startswith("import ")), reverse=True)
            for l in dead:
                del lines[l-1]
            if dead:
                removed += len(dead)
                p.write_text("".join(lines))
        for p, l, name in errs:
            if name not in idx:
                manual += 1
                continue
            pool = {pkg for pkg, _ in idx[name]}
            if len(pool) > 1:
                own = [x for x in pool if package_of(p) == x]
                if len(own) == 1:
                    pkg = own[0]
                else:
                    print(f"  AMBIGUOUS {p.name}:{l} {name} -> {sorted(pool)}")
                    manual += 1
                    continue
            else:
                pkg = next(iter(pool))
            if insert_import(p, f"{pkg}.{name}"):
                inserted += 1
        print(f"round {rnd}: {len(errs)} errs, removed {removed}, inserted {inserted}, manual {manual}")
        if removed == 0 and inserted == 0:
            for p, l, name in errs[:50]:
                print(f"  REMAINING {p.relative_to(ROOT)}:{l} {name}")
            return 1
    return 1

if __name__ == "__main__":
    sys.exit(main())
