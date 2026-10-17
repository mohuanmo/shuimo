#include "segment.h"

#include <cstring>
#include <string>

namespace gk {

// —— 自动分词 DP 常量（v1.2）——
// 段代价 = kSegBase + kPerByte×len：偏好长音节，但段开销保证单音节整体
// 优于两切（xian=-1 < xi an=+4）。兜底段额外惩罚，仅在音节图无法覆盖时使用。
namespace {
constexpr float kSegBase = 8.0f;
constexpr float kPerByte = -3.0f;
constexpr float kFallbackExtra = 5.0f;
constexpr float kInf = 1e9f;
}  // namespace

bool IsSyllable(const char* s, int len) {
  if (s == nullptr || len <= 0 || len > kMaxSyllableLen) return false;
  return FindSyllable(s, len) >= 0;
}

bool IsSyllablePrefix(const char* s, int len) {
  if (s == nullptr || len <= 0 || len > kMaxSyllableLen) return false;
  int lo = 0, hi = SyllableCount();
  while (lo < hi) {
    int mid = lo + (hi - lo) / 2;
    if (std::strncmp(SyllableAt(mid), s, static_cast<size_t>(len)) < 0) lo = mid + 1;
    else hi = mid;
  }
  return lo < SyllableCount() &&
         std::strncmp(SyllableAt(lo), s, static_cast<size_t>(len)) == 0;
}

std::vector<DisplaySeg> ComputeDisplaySegments(const std::string& input) {
  const int n = static_cast<int>(input.size());
  std::vector<DisplaySeg> empty;
  if (n == 0) return empty;
  if (n > kMaxCompositionBytes) return empty;

  // 音节图与解码层完全一致（字母/T9 同源）；DP 只向前松弛（位置 DAG）。
  Segmenter seg;
  int layout = (input[0] >= '2' && input[0] <= '9') ? LAYOUT_PINYIN_9 : LAYOUT_PINYIN_26;
  seg.Build(input.data(), n, layout, FUZZY_NONE);

  std::vector<float> cost(n + 1, kInf);
  std::vector<int> from(n + 1, -1);   // 前驱位置
  std::vector<int> kind(n + 1, -1);   // 0=音节段 1=兜底段 2=锚点
  cost[0] = 0.0f;
  for (int pos = 0; pos < n; ++pos) {
    if (cost[pos] >= kInf) continue;
    // 音节边 + 分隔符边（Segmenter 已对 ' 生成 kNoSyllable 边）
    for (const SylEdge& e : seg.EdgesAt(pos)) {
      if (e.syl == kNoSyllable) {
        float c2 = cost[pos];  // 锚点零代价
        if (c2 < cost[e.end]) { cost[e.end] = c2; from[e.end] = pos; kind[e.end] = 2; }
        continue;
      }
      float c2 = cost[pos] + kSegBase + kPerByte * static_cast<float>(e.end - pos);
      if (c2 < cost[e.end]) { cost[e.end] = c2; from[e.end] = pos; kind[e.end] = 0; }
    }
    // 兜底：单个合法输入字符（不完整音节尾部 / 未成音节字母）
    char c = input[static_cast<size_t>(pos)];
    bool alnum = (c >= 'a' && c <= 'z') || (c >= '2' && c <= '9');
    if (alnum) {
      float c2 = cost[pos] + kSegBase + kPerByte + kFallbackExtra;
      if (c2 < cost[pos + 1]) { cost[pos + 1] = c2; from[pos + 1] = pos; kind[pos + 1] = 1; }
    }
  }
  if (cost[n] >= kInf) return empty;  // 不可达（防御：含非法字符）

  // 回溯（自尾向头）→ 段列表。锚点在回溯序中先于其前驱段出现，
  // 用 pending 标记传递给下一个收集的段（即锚点之前的段）。
  std::vector<DisplaySeg> segs;
  bool pending_anchor = false;
  int cur = n;
  int guard = 0;
  while (cur > 0 && guard++ < 256) {
    int p = from[static_cast<size_t>(cur)];
    int k = kind[static_cast<size_t>(cur)];
    if (p < 0) return empty;
    if (k == 2) {
      pending_anchor = true;  // 锚点：不占段，附着其前段
    } else {
      DisplaySeg s;
      s.start = static_cast<uint16_t>(p);
      s.end = static_cast<uint16_t>(cur);
      s.fallback = (k == 1);
      s.anchored = pending_anchor;
      pending_anchor = false;
      segs.push_back(s);
    }
    cur = p;
  }
  for (size_t i = 0; i < segs.size() / 2; ++i) {
    DisplaySeg t = segs[i];
    segs[i] = segs[segs.size() - 1 - i];
    segs[segs.size() - 1 - i] = t;
  }
  return segs;
}

std::string SegmentedDisplay(const std::string& input) {
  auto segs = ComputeDisplaySegments(input);
  std::string out;
  for (size_t i = 0; i < segs.size(); ++i) {
    if (i > 0 && !segs[i - 1].anchored) out.push_back(' ');  // 前段带锚点 → 锚点已是可见分隔
    out.append(input, segs[i].start, segs[i].end - segs[i].start);
    if (segs[i].anchored) out.push_back(kSeparator);
  }
  return out;
}

const std::vector<SylEdge>& Segmenter::EdgesAt(int pos) const {
  if (pos < 0 || pos > len_) {
    static const std::vector<SylEdge> kEmpty;
    return kEmpty;
  }
  return edges_[pos];
}

void Segmenter::Build(const char* input, int len, int layout, uint32_t fuzzy) {
  (void)fuzzy;  // 模糊音在键变体层处理
  for (auto& e : edges_) e.clear();
  len_ = len > kMaxCompositionBytes ? kMaxCompositionBytes : (len < 0 ? 0 : len);
  if (len_ == 0) return;
  if (layout == LAYOUT_PINYIN_9) BuildDigits(input);
  else BuildLetters(input);
}

void Segmenter::BuildLetters(const char* input) {
  for (int pos = 0; pos < len_; ++pos) {
    if (input[pos] == kSeparator) {
      edges_[pos].push_back(SylEdge{static_cast<uint16_t>(pos),
                                    static_cast<uint16_t>(pos + 1), kNoSyllable, 0.0f});
      continue;
    }
    if (input[pos] < 'a' || input[pos] > 'z') continue;
    int remain = len_ - pos;
    for (int i = 0; i < SyllableCount(); ++i) {
      const char* syl = SyllableAt(i);
      int slen = static_cast<int>(std::strlen(syl));
      if (slen > remain) continue;
      if (std::memcmp(syl, input + pos, static_cast<size_t>(slen)) == 0) {
        edges_[pos].push_back(SylEdge{static_cast<uint16_t>(pos),
                                      static_cast<uint16_t>(pos + slen),
                                      static_cast<uint16_t>(i), 0.0f});
      }
    }
  }
}

void Segmenter::BuildDigits(const char* input) {
  for (int pos = 0; pos < len_; ++pos) {
    if (input[pos] < '2' || input[pos] > '9') continue;  // 1 键为标点（UI 层处理）
    int remain = len_ - pos;
    for (int i = 0; i < SyllableCount(); ++i) {
      const char* dig = SyllableDigits(i);
      int dlen = static_cast<int>(std::strlen(dig));
      if (dlen > remain || dlen < 1) continue;
      if (std::memcmp(dig, input + pos, static_cast<size_t>(dlen)) == 0) {
        edges_[pos].push_back(SylEdge{static_cast<uint16_t>(pos),
                                      static_cast<uint16_t>(pos + dlen),
                                      static_cast<uint16_t>(i), 0.0f});
      }
    }
  }
}

int KeyVariants(const char* syl, uint32_t fuzzy, char out[][8], float* pen, int cap) {
  if (syl == nullptr || syl[0] == '\0' || out == nullptr || pen == nullptr || cap <= 0) return 0;
  const std::string s(syl);
  int limit = cap < kMaxKeyVariants ? cap : kMaxKeyVariants;

  // 声母切分（最长 2 字母）
  std::string initial = s.substr(0, 1);
  std::string rest = s.substr(1);
  if (s.size() >= 2 && (s.compare(0, 2, "zh") == 0 || s.compare(0, 2, "ch") == 0 ||
                        s.compare(0, 2, "sh") == 0)) {
    initial = s.substr(0, 2);
    rest = s.substr(2);
  }
  // 声母等价类（规范形 → 成员）
  std::vector<std::string> imems{initial};
  if ((fuzzy & FUZZY_ZH_Z) != 0 && (initial == "z" || initial == "zh")) {
    imems = {"z", "zh"};
    if (initial == "zh") rest = s.substr(2);
    else rest = s.substr(1);
  } else if ((fuzzy & FUZZY_CH_C) != 0 && (initial == "c" || initial == "ch")) {
    imems = {"c", "ch"};
    rest = initial == "ch" ? s.substr(2) : s.substr(1);
  } else if ((fuzzy & FUZZY_SH_S) != 0 && (initial == "s" || initial == "sh")) {
    imems = {"s", "sh"};
    rest = initial == "sh" ? s.substr(2) : s.substr(1);
  } else if ((fuzzy & FUZZY_N_L) != 0 && (initial == "n" || initial == "l")) {
    imems = {"n", "l"};
    rest = s.substr(1);
  } else if ((fuzzy & FUZZY_F_H) != 0 && (initial == "f" || initial == "h")) {
    imems = {"f", "h"};
    rest = s.substr(1);
  }

  // 韵尾等价类（an↔ang / en↔eng / in↔ing，映射到规范形再展开）
  std::vector<std::string> tmems{rest};
  if ((fuzzy & FUZZY_AN_ANG) != 0 && rest.size() >= 3 &&
      rest.compare(rest.size() - 3, 3, "ang") == 0) {
    tmems = {rest.substr(0, rest.size() - 3) + "an", rest};  // {an, ang}
  } else if ((fuzzy & FUZZY_AN_ANG) != 0 && rest.size() >= 2 &&
             rest.compare(rest.size() - 2, 2, "an") == 0 && rest.size() <= 4) {
    tmems = {rest, rest.substr(0, rest.size() - 2) + "ang"};
  } else if ((fuzzy & FUZZY_EN_ENG) != 0 && rest.size() >= 3 &&
             rest.compare(rest.size() - 3, 3, "eng") == 0) {
    tmems = {rest.substr(0, rest.size() - 3) + "en", rest};
  } else if ((fuzzy & FUZZY_EN_ENG) != 0 && rest.size() >= 2 &&
             rest.compare(rest.size() - 2, 2, "en") == 0 && rest.size() <= 4) {
    tmems = {rest, rest.substr(0, rest.size() - 2) + "eng"};
  } else if ((fuzzy & FUZZY_IN_ING) != 0 && rest.size() >= 3 &&
             rest.compare(rest.size() - 3, 3, "ing") == 0) {
    tmems = {rest.substr(0, rest.size() - 3) + "in", rest};
  } else if ((fuzzy & FUZZY_IN_ING) != 0 && rest.size() >= 2 &&
             rest.compare(rest.size() - 2, 2, "in") == 0 && rest.size() <= 4) {
    tmems = {rest, rest.substr(0, rest.size() - 2) + "ing"};
  }

  int n = 0;
  for (const auto& i : imems) {
    for (const auto& t : tmems) {
      std::string key = i + t;
      bool dup = false;
      for (int k = 0; k < n; ++k) {
        if (key == out[k]) { dup = true; break; }
      }
      if (dup || n >= limit) continue;
      std::memset(out[n], 0, 8);
      std::memcpy(out[n], key.c_str(), key.size() < 7 ? key.size() : 7);
      pen[n] = key == s ? 0.0f : 0.5f;
      ++n;
    }
  }
  return n;
}

}  // namespace gk
