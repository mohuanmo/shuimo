#!/usr/bin/env python3
"""GKDT 词典构建管线（tools/dictbuilder，对应方案 §3.4 / §7.3）

输入 TSV（无表头，TAB 分隔）：word \t pinyin \t freq
  - 中文词库：pinyin 为小写音节，单空格分隔（如：ni hao）；单字同样处理
  - 英文词库：pinyin 列填 "-"（键用小写词本身）
输出：GKDT v1 二进制（格式见 core/src/dict/dict_store.h 头部注释）
  - DAT：双数组 Trie，key = 拼音串 / 英文小写词，词以 '\\0' 终结边入树
  - DAT 终结节点 base = WLIST 下标（词 id 集合，同音词共享一键，§4.3 "词 id 集合"）
  - 段：1 META 2 DAT 3 WORDS 4 FREQ 5 WORD_IDX 7 WLIST_IDX 8 WLIST（6 NGRAM 预留）
  - 全部小端；各段头部 CRC32（IEEE/zlib 兼容）
可重复构建：不写入时间戳，仅写入版本与统计（§10.1 语料快照锁定）。

用法：
  python3 build_gkdt.py --lang zh --input seed/words_zh.tsv --out <path>.gkdt
  python3 build_gkdt.py --lang en --input seed/words_en.tsv --out <path>.gkdt
  python3 build_gkdt.py --selftest   # 构建→解析→随机查询回读自校验
"""
import argparse
import json
import random
import struct
import sys
import zlib

MAGIC = b"GKDT"
VERSION = 1
SEC_META, SEC_DAT, SEC_WORDS, SEC_FREQ, SEC_WORD_IDX, SEC_NGRAM = 1, 2, 3, 4, 5, 6
SEC_WLIST_IDX, SEC_WLIST = 7, 8


def load_tsv(path):
    words, pinyins, freqs = [], [], []
    seen = set()
    with open(path, "r", encoding="utf-8-sig") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                raise ValueError(f"{path}:{lineno} 列数不足: {line!r}")
            w, py, freq = parts[0], parts[1], int(parts[2])
            if not w or w in seen:
                continue  # 重复词跳过（首个生效）
            seen.add(w)
            if py == "-":
                py = ""
            words.append(w)
            pinyins.append(py)
            freqs.append(freq)
    return words, pinyins, freqs


def build_keys(lang, words, pinyins):
    """词 → DAT key。中文=拼音串；英文=小写词本身。"""
    keys, ids = [], []
    for i, (w, py) in enumerate(zip(words, pinyins)):
        if lang == "zh":
            if not py:
                continue
            key = py.lower()
        else:
            if not w.isascii() or not w.isalpha():
                continue
            key = w.lower()
        keys.append(key)
        ids.append(i)
    return keys, ids


class DoubleArrayBuilder:
    """静态双数组 Trie：BFS 分配 + 空位搜索（大词库规模 ≤30万键 足够，无搬迁）。

    语义与 C++ DictStore 契约一致：
      - node 0 为根；check[0] = -2 根占用
      - 边 c：m = base[node] + c，有效当 check[m] == node
      - 词以 '\\0' 终结边入树，终结节点 base = WLIST 下标（>= 0）

    性能（大词库构建，v1.1）：_find_base 使用"单调游标 + 兄弟槽位复用"启发式——
    BFS 序下 base 单调推进，游标不回退到 1 重扫；兄弟节点共享同一 base 候选集，
    同层节点平均 O(1) 命中。_ensure 批量扩容。
    """

    __slots__ = ("base", "check", "_cursor")

    def __init__(self):
        self.base = [0]
        self.check = [-1]
        self._cursor = 1  # 空位搜索游标（单调推进）

    def _ensure(self, size):
        need = size - len(self.check) + 1
        if need > 0:
            self.base.extend([0] * need)
            self.check.extend([-1] * need)

    def _free_at(self, b, chars):
        for c in chars:
            i = b + c
            if i < len(self.check) and self.check[i] != -1:
                return False
        return True

    def _find_base(self, chars):
        b = self._cursor
        lo = min(chars)
        hi = max(chars)
        span = hi - lo
        while True:
            self._ensure(b + hi + 1)
            if self._free_at(b, chars):
                self._cursor = b  # 下次从当前位置起（不回退）
                return b
            b += 1
            if span == 0:
                # 单子节点：大幅跳跃直至遇空位（纯线性代价已最小）
                self._ensure(b + hi + 1)

    def build(self, keys, ids):
        # 1) 分组：key → [word_id...]（同音词）
        groups = {}
        for key, wid in zip(keys, ids):
            groups.setdefault(key, []).append(wid)

        # 2) 普通字典树（含 '\0' 终结子节点）
        trie = {0: {}}
        for key in groups:
            cur = 0
            for ch in key:
                children = trie[cur]
                nxt = children.get(ord(ch))
                if nxt is None:
                    nxt = len(trie)
                    children[ord(ch)] = nxt
                    trie[nxt] = {}
                cur = nxt
            trie[cur][0] = None  # 终结边标记

        # 3) BFS 分配双数组槽位
        self.check[0] = -2
        queue = [(0, 0)]
        head = 0
        while head < len(queue):
            tnode, dnode = queue[head]
            head += 1
            children = trie[tnode]
            if not children:
                continue
            chars = sorted(children.keys())
            b = self._find_base(chars)
            self.base[dnode] = b
            for c in chars:
                slot = b + c
                self.check[slot] = dnode
                if c != 0:
                    queue.append((children[c], slot))

        # 4) 回填终结节点 → WLIST 下标
        wlist_stream = []
        wlist_idx = [0]
        for key, id_list in groups.items():
            node = 0
            for ch in key:
                node = self.base[node] + ord(ch)
            term = self.base[node] + 0
            self.base[term] = len(wlist_idx) - 1
            wlist_stream.extend(id_list)
            wlist_idx.append(len(wlist_stream))
        self._ensure(len(self.base) + 1)
        return wlist_idx, wlist_stream


def dat_walk(base, check, key):
    node = 0
    for ch in key:
        m = base[node] + ord(ch)
        if m < 0 or m >= len(check) or check[m] != node:
            return -1
        node = m
    m = base[node] + 0
    if m < 0 or m >= len(check) or check[m] != node:
        return -1
    v = base[m]
    return v if v >= 0 else -1


def build_gkdt(lang, words, pinyins, freqs, out_path):
    keys, ids = build_keys(lang, words, pinyins)
    dat = DoubleArrayBuilder()
    wlist_idx, wlist_stream = dat.build(keys, ids)

    meta = {
        "lang": lang,
        "order": 1,  # M1 基线：unigram；NGRAM 段随资源包热更启用（§4.3）
        "word_count": len(words),
        "indexed_keys": len(set(keys)),
        "format": "GKDT-1",
    }
    meta_b = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    dat_b = struct.pack("<I", len(dat.base))
    dat_b += b"".join(struct.pack("<i", v) for v in dat.base)
    dat_b += b"".join(struct.pack("<i", v) for v in dat.check)

    words_b = bytearray()
    idx = [0]
    for w, py in zip(words, pinyins):
        words_b += w.encode("utf-8") + b"\x00"
        words_b += py.encode("utf-8") + b"\x00"
        idx.append(len(words_b))
    words_b = bytes(words_b)
    idx_b = b"".join(struct.pack("<I", v) for v in idx)
    freq_b = b"".join(struct.pack("<I", max(0, f)) for f in freqs)
    wlidx_b = b"".join(struct.pack("<I", v) for v in wlist_idx)
    wl_b = b"".join(struct.pack("<I", v) for v in wlist_stream)

    sections = [
        (SEC_META, meta_b),
        (SEC_DAT, dat_b),
        (SEC_WORDS, words_b),
        (SEC_FREQ, freq_b),
        (SEC_WORD_IDX, idx_b),
        (SEC_WLIST_IDX, wlidx_b),
        (SEC_WLIST, wl_b),
    ]

    header = bytearray()
    header += MAGIC
    header += struct.pack("<HH", VERSION, len(sections))
    body = bytearray()
    offset = 8 + 16 * len(sections)
    for stype, data in sections:
        header += struct.pack("<HHIII", stype, 0, offset, len(data), zlib.crc32(data) & 0xFFFFFFFF)
        offset += len(data)
        body += data
    blob = bytes(header + body)
    with open(out_path, "wb") as f:
        f.write(blob)
    print(f"[build_gkdt] {out_path}: {len(blob)} bytes, words={len(words)}, "
          f"keys={len(set(keys))}, dat_nodes={len(dat.base)}")
    return blob


def selftest(lang, path):
    """构建→解析→随机查询回读（DAT 重建/查询一致性，§10.4）。"""
    words, pinyins, freqs = load_tsv(path)
    keys, ids = build_keys(lang, words, pinyins)
    blob = build_gkdt(lang, words, pinyins, freqs, "/tmp/_selftest.gkdt")

    assert blob[:4] == MAGIC
    ver, cnt = struct.unpack("<HH", blob[4:8])
    assert ver == VERSION
    secs = {}
    off = 8
    for _ in range(cnt):
        stype, _r, soff, ssize, scrc = struct.unpack("<HHIII", blob[off:off + 16])
        off += 16
        data = blob[soff:soff + ssize]
        assert zlib.crc32(data) & 0xFFFFFFFF == scrc, f"段 {stype} CRC 不符"
        secs[stype] = data
    db = secs[SEC_DAT]
    n = struct.unpack("<I", db[:4])[0]
    base = list(struct.unpack(f"<{n}i", db[4:4 + 4 * n]))
    check = list(struct.unpack(f"<{n}i", db[4 + 4 * n:4 + 8 * n]))
    wlidx_n = len(secs[SEC_WLIST_IDX]) // 4
    wlidx = struct.unpack(f"<{wlidx_n}I", secs[SEC_WLIST_IDX])
    wl = struct.unpack(f"<{len(secs[SEC_WLIST]) // 4}I", secs[SEC_WLIST])
    idx_n = len(secs[SEC_WORD_IDX]) // 4
    widx = struct.unpack(f"<{idx_n}I", secs[SEC_WORD_IDX])
    words_b = secs[SEC_WORDS]

    def word_text(wid):
        return words_b[widx[wid]:widx[wid + 1]].split(b"\x00")[0].decode("utf-8")

    # 同音词组构建期望表
    expect = {}
    for key, wid in zip(keys, ids):
        expect.setdefault(key, []).append(wid)

    rnd = random.Random(42)
    uniq = list(expect.keys())
    sample = rnd.sample(uniq, min(300, len(uniq))) + uniq[:20]
    for key in sample:
        li = dat_walk(base, check, key)
        assert li >= 0, f"查询失败: {key}"
        got = list(wl[wlidx[li]:wlidx[li + 1]])
        assert sorted(got) == sorted(expect[key]), f"id 集合不符: {key}: {got} vs {expect[key]}"
    for miss in ("zzzzzz", "q'q'q", "xxxyyy"):
        assert dat_walk(base, check, miss) == -1, f"应查询不到: {miss}"
    print(f"[selftest] OK：{len(sample)} 键随机查询 + id 集合比对 + 反例全部通过")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=["zh", "en"], default="zh")
    ap.add_argument("--input")
    ap.add_argument("--out")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        selftest("zh", "seed/words_zh.tsv")
        selftest("en", "seed/words_en.tsv")
        return
    if not args.input or not args.out:
        ap.error("需要 --input 与 --out（或 --selftest）")
    words, pinyins, freqs = load_tsv(args.input)
    build_gkdt(args.lang, words, pinyins, freqs, args.out)


if __name__ == "__main__":
    sys.exit(main())
