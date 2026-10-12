#!/usr/bin/env python3
"""Compare two address-sorted parity TSVs (native MAT vs DHP). Usage: compare.py native.s.tsv dhp.s.tsv [samples]"""
import sys, collections
F = ["class", "shallow", "retained", "idom", "outbound", "roots"]
def rd(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            while len(parts) < 7: parts.append("")
            yield parts
def main():
    na, db = rd(sys.argv[1]), rd(sys.argv[2])
    samples = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    diff = collections.Counter(); ex = collections.defaultdict(list)
    only_n = only_d = common = 0
    a = next(na, None); b = next(db, None)
    while a is not None or b is not None:
        if b is None or (a is not None and a[0] < b[0]):
            only_n += 1
            if only_n <= samples: print("native-only", a[:5])
            a = next(na, None)
        elif a is None or b[0] < a[0]:
            only_d += 1
            if only_d <= samples: print("dhp-only", b[:5])
            b = next(db, None)
        else:
            common += 1
            for i, name in enumerate(F, start=1):
                x, y = a[i], b[i]
                if name in ("outbound", "roots"):
                    x, y = sorted(x.split(",")) if x else [], sorted(y.split(",")) if y else []
                if x != y:
                    diff[name] += 1
                    if len(ex[name]) < samples: ex[name].append((a[0], a[1], str(a[i])[:80], str(b[i])[:80]))
            a = next(na, None); b = next(db, None)
    print(f"common={common} native-only={only_n} dhp-only={only_d}")
    for name in F:
        print(f"{name:9s} differs in {diff[name]}")
        for e in ex[name]: print("    ", e)
    ok = only_n == 0 and not diff
    print("PARITY OK (dhp-only objects are unreachable ones, check separately)" if ok else "PARITY DIFFERENCES FOUND")
main()
