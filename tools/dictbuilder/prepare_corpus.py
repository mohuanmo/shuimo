#!/usr/bin/env python3
"""大词库语料管线（tools/dictbuilder，方案 §3.4 / §4.3 / §10.1）

数据来源（方案 §3.6 批准的开放语料，锁定快照）：
  1. CC-CEDICT (CC-BY-SA 4.0)：简体词 + 带调拼音 —— 提供"词→拼音"标注
  2. jieba dict.txt (MIT)：词 + 真实词频 —— 提供频率通道
  3. Norvig count_1w (公开词频统计)：英文词频

中文合并策略：
  - CEDICT ∩ jieba：拼音（CEDICT）+ 词频（jieba）——最优通道
  - 仅 jieba：pypinyin 自动标注（词长 ≤ 6，多音字取常用读音）
  - 仅 CEDICT：按词长给基准频率（单字 3000 / 双字 1500 / 三字 600 / ≥4 字 250）
  - 合法性硬校验：拼音音节必须全部 ∈ 音节表（解析 syllable_table.cpp），
    否则丢弃（保证 DAT 键可被引擎切分/查询，杜绝"幽灵词条"）
  - 拼音规范化：去声调（NFD 组合符剥离）、ü→v（引擎音节表约定 lv/nv/lve/nve）
  - 词形过滤：仅保留纯汉字词（含正式汉字区 CJK + 扩展A 兼容区）；
    单字排除纯标点/字母

英文合并策略：
  - count_1w top-N（默认 60000），长度 2..16，过滤 ASCII 控制词与粗俗词黑名单

输出 TSV：word \t pinyin(空格分隔 or "-") \t freq —— 与 build_gkdt.py 对接。
词频对齐：统一映射到 log 尺度基准（max_freq → 50000），避免两来源相差数量级。

用法：
  python3 prepare_corpus.py --zh-cedict cedict.txt --zh-jieba jieba_dict.txt \
      --zh-out seed/words_zh_big.tsv
  python3 prepare_corpus.py --en-count count_1w.txt --en-out seed/words_en_big.tsv
"""
import argparse
import os
import re
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- 音节表加载

def load_syllables_from_cpp():
    """解析 core/src/pinyin/syllable_table.cpp 的字符串字面量（唯一可信源）。"""
    cpp = os.path.join(HERE, "..", "..", "core", "src", "pinyin", "syllable_table.cpp")
    with open(cpp, "r", encoding="utf-8") as f:
        src = f.read()
    # 抓取 kSyllables 数组初始化列表中的所有 "xxx"
    m = re.search(r"kSyllables\[\]\s*=\s*\{(.*?)\};", src, re.S)
    if not m:
        raise RuntimeError("syllable_table.cpp 中未找到 kSyllables 数组")
    syls = set(re.findall(r'"([a-z]+)"', m.group(1)))
    if len(syls) < 400:
        raise RuntimeError(f"音节数异常: {len(syls)}")
    return syls

# ---------------------------------------------------------------- 拼音规范化

_TONE_MAP = {}
for _base, _marks in (("a", "āáǎà"), ("e", "ēéěè"), ("i", "īíǐì"),
                      ("o", "ōóǒò"), ("u", "ūúǔù"), ("ü", "ǖǘǚǜ"),
                      ("n", "ńňǹ")):
    for _i, _ch in enumerate(_marks):
        _TONE_MAP[_ch] = (_base, _i + 1)


def strip_tone(syl: str) -> str:
    """lv4 → lv；zhōng → zhong；ü4 → v4。输入为 CEDICT 小写带调音节。"""
    out = []
    tone = 0
    for ch in syl:
        if ch in _TONE_MAP:
            base, t = _TONE_MAP[ch]
            out.append(base)
            tone = max(tone, t if t < 5 else 0)
        else:
            out.append(ch)
    s = "".join(out)
    # CEDICT 约定：ü 后无数字标注（如 "lv" 不存在，是 "lü"）→ 已由 _TONE_MAP 处理
    return s.replace("ü", "v")


def norm_pinyin(raw: str, syllables: set):
    """CEDICT 拼音串 → 引擎音节序列；任何音节非法返回 None。"""
    raw = raw.strip().strip("[]")
    parts = [strip_tone(p.lower()) for p in raw.split()]
    if not parts:
        return None
    for p in parts:
        if p not in syllables:
            return None
        if p == "r" and len(parts) == 1:  # 单儿化音节不成词
            return None
    return " ".join(parts)


_HAN_RE = re.compile(r"^[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]+$")


def is_han(word: str) -> bool:
    return bool(_HAN_RE.match(word)) and len(word) >= 1

# ---------------------------------------------------------------- 中文语料

def parse_cedict(path):
    """返回 [(简体, 带调拼音串)]，去重。"""
    out = {}
    pat = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]")
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            m = pat.match(line)
            if not m:
                continue
            simp = m.group(2)
            if not is_han(simp) or len(simp) > 8:
                continue
            py = norm_pinyin(m.group(3), _SYL)
            if py is None:
                continue
            out.setdefault(simp, py)
    return out


def parse_jieba(path):
    """返回 {词: 词频}。"""
    freq = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split(" ")
            if len(parts) < 2:
                continue
            w = parts[0]
            try:
                f32 = int(parts[1])
            except ValueError:
                continue
            if w and f32 > 0:
                freq[w] = f32
    return freq


def pypinyin_of(word: str):
    from pypinyin import lazy_pinyin, Style
    parts = lazy_pinyin(word, style=Style.NORMAL, errors=lambda x: ["_BAD_"])
    if any(p == "_BAD_" or not p for p in parts):
        return None
    py = " ".join(p.lower() for p in parts)
    for p in py.split():
        if p not in _SYL:
            return None
    return py


_BASE_FREQ = {1: 3000, 2: 1500, 3: 600}


def build_zh(cedict_path, jieba_path, out_path, max_words=200000):
    cedict = parse_cedict(cedict_path)
    jieba = parse_jieba(jieba_path)
    han_jieba = {w: f for w, f in jieba.items() if is_han(w) and 1 <= len(w) <= 8}

    rows = {}  # word -> [pinyin, freq]
    # 通道 1：jieba 提供频率（主干，真实词频）
    for w, f in han_jieba.items():
        py = cedict.get(w)
        if py is None and len(w) <= 6:
            py = pypinyin_of(w)
        if py is None:
            continue
        rows[w] = [py, f]
    # 通道 2：CEDICT 独有词条（基准频率）
    for w, py in cedict.items():
        if w in rows:
            continue
        rows[w] = [py, _BASE_FREQ.get(len(w), 250)]
    # 通道 3：jieba 单字兜底（pypinyin 标注）已含于通道 1

    # 频率对齐：jieba 最大词频 → 50000，log 尺度
    if rows:
        mx = max(f for _, f in rows.values())
        import math
        scale = 50000.0 / math.log10(mx + 10)
        items = []
        for w, (py, f) in rows.items():
            items.append((w, py, max(1, int(math.log10(f + 10) * scale))))
        items.sort(key=lambda x: -x[2])
        items = items[:max_words]
    else:
        items = []
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# GlassKey 中文大词库：CC-CEDICT(CC-BY-SA 4.0) + jieba词频(MIT) + pypinyin(MIT)\n")
        f.write("# 拼音已规范化（无声调、ü→v），音节合法性已按 syllable_table.cpp 校验\n")
        for w, py, fr in items:
            f.write(f"{w}\t{py}\t{fr}\n")
    print(f"[zh] 输出 {len(items)} 词条 → {out_path}")
    n1 = sum(1 for w, _, _ in items if w in han_jieba)
    print(f"[zh] 其中 jieba 频率通道 {n1} 条，CEDICT 基准通道 {len(items) - n1} 条")

# ---------------------------------------------------------------- 英文语料

_EN_BLOCK = {
    # 高频粗俗/侮辱词黑名单（开源分发用，保守过滤）
    "fuck", "fucking", "shit", "bitch", "asshole", "dick", "cunt", "nigger",
    "nigga", "whore", "slut", "bastard", "penis", "vagina", "rape", "porn",
    "faggot", "retard", "wanker", "prick", "bollocks",
}


def build_en(count_path, out_path, top_n=60000):
    rows = []
    with open(count_path, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) != 2:
                continue
            w, c = parts
            if not w.isalpha() or not w.isascii() or not w.islower():
                continue
            if not (2 <= len(w) <= 16) or w in _EN_BLOCK:
                continue
            rows.append((w, int(c)))
    rows.sort(key=lambda x: -x[1])
    rows = rows[:top_n]
    mx = rows[0][1] if rows else 1
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# GlassKey 英文大词库：Norvig count_1w 公开词频统计（锁定快照）\n")
        for w, c in rows:
            fr = max(1, int(50000 * (c / mx) ** 0.5))  # 平滑：词频跨度过大压缩
            f.write(f"{w}\t-\t{fr}\n")
    print(f"[en] 输出 {len(rows)} 词条 → {out_path}")

# ---------------------------------------------------------------- main

_SYL = set()


def main():
    global _SYL
    ap = argparse.ArgumentParser()
    ap.add_argument("--zh-cedict")
    ap.add_argument("--zh-jieba")
    ap.add_argument("--zh-out", default=os.path.join(HERE, "seed", "words_zh_big.tsv"))
    ap.add_argument("--en-count")
    ap.add_argument("--en-out", default=os.path.join(HERE, "seed", "words_en_big.tsv"))
    ap.add_argument("--zh-max", type=int, default=200000)
    ap.add_argument("--en-top", type=int, default=60000)
    args = ap.parse_args()

    _SYL.update(load_syllables_from_cpp())
    print(f"[syl] 合法音节 {len(_SYL)} 个")

    if args.zh_cedict and args.zh_jieba:
        build_zh(args.zh_cedict, args.zh_jieba, args.zh_out, args.zh_max)
    if args.en_count:
        build_en(args.en_count, args.en_out, args.en_top)


if __name__ == "__main__":
    sys.exit(main())
