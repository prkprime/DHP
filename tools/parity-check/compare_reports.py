#!/usr/bin/env python3
"""Compare MAT report zips produced from a native dump and from the DHP backend: every HTML page as normalized text.
Usage: compare_reports.py native_report.zip dhp_report.zip"""
import re, sys, zipfile, difflib

def pages(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".html") and n != "toc.html":
                t = z.read(n).decode("utf-8", "ignore")
                t = re.sub(r"<[^>]+>", " ", t)
                t = re.sub(r"\s+", " ", t).strip()
                # the two snapshots live in different directories by construction
                t = re.sub(r"File path \S+", "File path <path>", t)
                out[n] = t
    return out

a, b = pages(sys.argv[1]), pages(sys.argv[2])
bad = 0
for n in sorted(set(a) | set(b)):
    if n not in a or n not in b:
        print("only in", "native" if n in a else "dhp", n); bad += 1; continue
    if a[n] != b[n]:
        bad += 1
        print("DIFF", n)
        sm = difflib.SequenceMatcher(None, a[n], b[n], autojunk=False)
        shown = 0
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag != "equal" and shown < 3:
                print("   native:", a[n][max(0, i1 - 40):i2 + 40]); print("   dhp   :", b[n][max(0, j1 - 40):j2 + 40]); shown += 1
print("pages compared:", len(set(a) | set(b)), "different:", bad)
sys.exit(1 if bad else 0)
