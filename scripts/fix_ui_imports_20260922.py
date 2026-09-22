#!/usr/bin/env python3
"""Phase 1a fallout fixer v3 (2026-09-22).

- Indexes every top-level declaration (classes/objects/funs/vals/typealias),
  including extension declarations (records receiver kind).
- Fixes compile errors in two ways:
    * broken import lines  -> rewrite to the correct package
    * unresolved bare refs -> insert correct import
- Disambiguates by usage: 'x.name(' -> extension decl; bare 'name(' -> top-level.
Run from repo root. Loops until clean or no progress.
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
COMPILE_TASKS = [":app:compileDebugKotlin", ":app:compileDebugUnitTestKotlin", ":core-data:compileDebugKotlin"]

MODS = (r"(?:public|private|internal|protected|open|abstract|final|sealed|data|enum|"
        r"value|inline|const|lateinit|external|suspend|operator|infix|tailrec|"
        r"override|expect|actual|vararg|companion)")
DECL_RE = re.compile(
    rf"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:{MODS}\s+)*"
    r"(fun|val|var|class|interface|object|typealias)\s+"
    r"([A-Za-z_][A-Za-z0-_.<>?]*\.)?"
    r"`?([A-Za-z_][A-Za-z0-9_]*)`?"
)

def package_of(path: Path) -> str:
    parts = list(path.relative_to(ROOT).parts)
    return ".".join(parts[parts.index("java") + 1 : -1])

# name -> {(pkg, has_receiver): [paths]}
def build_index():
    idx = defaultdict(lambda: defaultdict(list))
    for src in SRC_DIRS:
        for kt in src.rglob("*.kt"):
            pkg = package_of(kt)
            for line in kt.read_text(encoding="utf-8", errors="replace").splitlines():
                s = line.strip()
                if s.startswith(("import", "package")):
                    continue
                m = DECL_RE.match(line)
                if m:
                    has_rx = m.group(2) is not None
                    idx[m.group(3)][(pkg, has_rx)].append(kt)
    return idx

ERR_RE = re.compile(r"^e: file://(.*?\.kt):(\d+):\d+ Unresolved reference '([^']+)'")

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

PKG_PRIORITY = ["viewmodel", "grid", "common", "loading", "chess", "stats", "settings",
                "meals", "advice", "map", "", "data", "widget", "ipc", "notify", "backup", "meal", "ai"]
def prio(pkg: str) -> int:
    for i, p in enumerate(PKG_PRIORITY):
        if pkg.endswith("." + p) or (p == "" and pkg == "com.example.tail.ui"):
            return i
    return len(PKG_PRIORITY)

def choose(cands, path: Path, name: str):
    """cands: set of (pkg, has_receiver). Returns pkg or None."""
    if not cands:
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    ext_use = re.search(rf"(\.\s*{re.escape(name)}\s*[(])|(=\s*{re.escape(name)}\s*\()", text) is not None
    bare_use = re.search(rf"(^|[^.\w]){re.escape(name)}([^\w]|$)", text, re.M) is not None
    ext_c = {p for p, rx in cands if rx}
    top_c = {p for p, rx in cands if not rx}
    if ext_use and ext_c and not (bare_use and top_c and len(top_c) < len(ext_c)):
        pool = ext_c
    elif top_c:
        pool = top_c
    else:
        pool = ext_c
    if len(pool) > 1:
        # prefer same parent dir, then priority list
        same = [p for p in pool if package_of(path) == p]
        if len(same) == 1:
            return same[0]
        return sorted(pool, key=prio)[0]
    return next(iter(pool), None)

def fix_import_line(path: Path, lineno: int, name: str, idx) -> str:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    old = lines[lineno - 1]
    m = re.match(r"(\s*import\s+)([\w.]+)", old)
    if not m:
        return "not-import"
    pkg = choose({k for k in idx.get(name, {})}, path, name)
    if pkg is None:
        return "no-candidate"
    lines[lineno - 1] = f"import {pkg}.{name}\n"
    path.write_text("".join(lines), encoding="utf-8")
    return f"rewrote -> {pkg}.{name}"

def insert_import(path: Path, fqn: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if re.search(rf"^import {re.escape(fqn)}$", text, re.M):
        return False
    lines = text.splitlines(keepends=True)
    import_idxs = [i for i, l in enumerate(lines) if l.strip().startswith("import ")]
    new_imp = f"import {fqn}\n"
    if import_idxs:
        start, last = import_idxs[0], import_idxs[-1]
        lines.insert(last + 1, new_imp)
        block = lines[start : last + 2]
        lines[start : last + 2] = sorted(block, key=lambda l: l.strip()[len("import"):])
    else:
        ins = 1
        if ins < len(lines) and lines[ins].strip() == "":
            ins += 1
        lines.insert(ins, new_imp)
        lines.insert(ins + 1, "\n")
    path.write_text("".join(lines), encoding="utf-8")
    return True

def main():
    idx = build_index()
    print(f"indexed {len(idx)} declaration names")
    done = set()
    stall = 0
    for round_no in range(1, 41):
        errs = compile_errors()
        if not errs:
            print(f"round {round_no}: compile clean")
            return 0
        acted = manual = 0
        for path, line, name in errs:
            key = (str(path), line, name)
            if key in done:
                continue
            done.add(key)
            text = path.read_text(encoding="utf-8", errors="replace")
            tl = text.splitlines()[line - 1] if line <= len(text.splitlines()) else ""
            if tl.strip().startswith("import"):
                res = fix_import_line(path, line, name, idx)
                if res.startswith("rewrote"):
                    acted += 1
                else:
                    print(f"  MANUAL import {path.name}:{line} {name} ({res})")
                    manual += 1
            else:
                cands = idx.get(name, {})
                pkg = choose(set(cands.keys()), path, name)
                if pkg is None:
                    print(f"  MANUAL ref {path.name}:{line} {name}")
                    manual += 1
                elif insert_import(path, f"{pkg}.{name}"):
                    acted += 1
        print(f"round {round_no}: {len(errs)} unresolved; fixed {acted}, manual {manual}")
        if acted == 0:
            stall += 1
            if stall < 4:
                done.clear()
                continue
            print("stalled; remaining errors above")
            return 1
        stall = 0
    return 1

if __name__ == "__main__":
    sys.exit(main())
