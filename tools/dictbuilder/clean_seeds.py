#!/usr/bin/env python3
"""种子词库清洗：去重（首个生效）、去零频、去空行、列数校验。"""
import sys

for path in sys.argv[1:]:
    seen = set()
    out = []
    dropped = 0
    with open(path, "r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            raw = line.rstrip("\n")
            if not raw.strip() or raw.startswith("#"):
                continue
            parts = raw.split("\t")
            if len(parts) < 3:
                print(f"{path}:{lineno} 列数不足，跳过: {raw!r}")
                dropped += 1
                continue
            w = parts[0].strip()
            if not w or w in seen:
                dropped += 1
                continue
            try:
                freq = int(parts[2].strip())
            except ValueError:
                print(f"{path}:{lineno} 词频非整数，跳过: {raw!r}")
                dropped += 1
                continue
            if freq <= 0:
                dropped += 1
                continue
            seen.add(w)
            out.append(f"{w}\t{parts[1].strip()}\t{freq}")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"[clean] {path}: {len(out)} 条保留, {dropped} 条剔除")
