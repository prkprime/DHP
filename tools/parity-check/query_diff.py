#!/usr/bin/env python3
"""Per-query comparison of two 'queries' outputs (native vs DHP).
Usage: query_diff.py native.queries.txt dhp.queries.txt [lines-of-diff-to-show]
Classifies every query as identical, ORDER-ONLY (same rows, different order) or DIFFERENT. Identity hash codes
(Foo@1a2b3c, [I@1a2b3c) and the dump path are normalized because they differ between runs by construction."""
import re, sys
def norm(line):
    line = re.sub(r"(\[?[A-Za-z$.]+)@[0-9a-f]{5,8}\b", r"\1@HASH", line)
    line = re.sub(r"/[^\s\t]*/(native|dhp)/", "/WORK/", line)
    line = line.split("  at ")[0]  # stack traces of failing queries differ in the plugin frames by construction
    return line
def blocks(path):
    d, cur, name = {}, [], None
    for line in open(path, encoding="utf-8", errors="replace").read().split("\n"):
        if line.startswith("QUERY "):
            name, cur = line[6:], []
            d[name] = cur
        elif line == "END":
            name = None
        elif name is not None:
            cur.append(norm(line))
    return d
a, b = blocks(sys.argv[1]), blocks(sys.argv[2])
show = int(sys.argv[3]) if len(sys.argv) > 3 else 0
counts = {"identical": 0, "ORDER-ONLY": 0, "DIFFERENT": 0}
for q in a:
    x, y = a[q], b.get(q) or []
    if x == y:
        counts["identical"] += 1
        continue
    kind = "ORDER-ONLY" if sorted(x) == sorted(y) else "DIFFERENT"
    counts[kind] += 1
    diff = sum(1 for i in range(max(len(x), len(y))) if (x[i] if i < len(x) else None) != (y[i] if i < len(y) else None))
    print(f"{kind} ({diff} of {len(x)}/{len(y)} lines): {q}")
    if show and kind == "DIFFERENT":
        n = 0
        for i in range(max(len(x), len(y))):
            xi = x[i] if i < len(x) else "<none>"; yi = y[i] if i < len(y) else "<none>"
            if xi != yi and n < show:
                print("   N:", xi[:240]); print("   D:", yi[:240]); n += 1
print(counts)
