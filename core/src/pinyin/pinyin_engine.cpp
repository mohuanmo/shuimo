#include "pinyin_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>

namespace gk {
namespace {
// 模糊音轻惩罚：变体音节扣 0.5×0.3=0.15 分/个（unigram 词间分差 ~0.7，
// 重惩罚会让全变体词如"时事"永远输给部分匹配的单字组合）
constexpr float kFuzzyPenaltyWeight = 0.3f;
constexpr float kUnigramScale = 1.0f;

// —— v1.3 兜底与上下文通道参数 ——
// 简拼每字母惩罚（业界惯例：全拼候选优先，简拼靠后；搜狗/百度同策略）
constexpr float kAbbrevPenaltyPerLetter = 0.3f;
constexpr int kAbbrevMaxLetters = 4;    // 简拼通道最大字母数（更长时组合爆炸且实用价值低）
constexpr int kAbbrevBudget = 4096;     // DAT 扩展预算（单次展开，防最坏 26^4）
constexpr int kAbbrevMaxOut = 8;        // 单次展开收集上限（进入候选前还会去重截断）
constexpr int kAbbrevTake = 6;          // 简拼通道最多并入候选数（词候选；字组合另计）
constexpr float kContextRepeatPenalty = 0.25f;  // 上文重复惩罚（叠词豁免）
constexpr float kContextSwapGap = 0.6f;         // 首选让位允许的最大分差

/// 取字符串最后一个 UTF-8 字符（多字节安全）；空串返回空。
std::string LastUtf8Char(const std::string& s) {
  if (s.empty()) return std::string();
  size_t i = s.size() - 1;
  while (i > 0 && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80) --i;
  return s.substr(i);
}
}  // namespace

void PinyinEngine::SetDict(DictStore* dict) {
  dict_ = dict;
  // unigram 概率归一化：P(w) = freq(w)/Σfreq，log P = log10(freq) - log10(Σfreq)
  // 每词扣 log10(Σfreq) 后，多词路径分 = 概率乘积，词典词天然优于"高频单字拼凑"
  if (dict != nullptr && dict->valid()) {
    double total = 0.0;
    for (int32_t i = 0; i < dict->WordCount(); ++i) total += dict->WordFreq(i);
    if (total > 1.0) wip_ = static_cast<float>(std::log10(total));
  }
}

void PinyinEngine::SetConfig(uint32_t fuzzy, int beam_width, int max_candidates) {
  fuzzy_ = fuzzy;
  beam_width_ = beam_width < 4 ? 4 : (beam_width > 16 ? 16 : beam_width);
  // v1.4：候选上限统一配置默认 30（普通词/单字），放宽硬顶到 50
  max_candidates_ = max_candidates < 3 ? 3 : (max_candidates > 50 ? 50 : max_candidates);
}

void PinyinEngine::SetInput(const std::string& comp) {
  input_ = comp;
  if (static_cast<int>(input_.size()) > kMaxCompositionBytes) {
    input_.resize(kMaxCompositionBytes);  // 超长仅回显（§5.1）
  }
  Decode();
}

void PinyinEngine::Backspace() {
  if (input_.empty()) return;
  input_.pop_back();  // 合成区仅 ASCII（字母/数字/'），按字节删除安全
  Decode();
}

void PinyinEngine::Reset() {
  input_.clear();
  Decode();
}

void PinyinEngine::Decode() {
  for (auto& v : wedges_) v.clear();
  for (auto& b : beams_) b.clear();
  cands_.clear();
  if (input_.empty() || dict_ == nullptr || !dict_->valid()) return;
  bool t9 = input_[0] >= '2' && input_[0] <= '9';
  seg_.Build(input_.data(), static_cast<int>(input_.size()),
             t9 ? LAYOUT_PINYIN_9 : LAYOUT_PINYIN_26, fuzzy_);
  CollectEdges();
  BeamSearch();
  BuildCandidates();
  // —— v1.3 兜底与上下文（仅 26 键；T9 数字串被全字母条件天然过滤）——
  BuildAbbrevCandidates();
  BuildTailAbbrevCandidates();
  ApplyContextRerank();
}

void PinyinEngine::SetContext(const char* before, int len) {
  context_tail_.clear();
  if (before == nullptr || len <= 0) return;
  int start = len > 18 ? len - 18 : 0;
  // UTF-8 边界对齐：不从多字节序列中间开始
  while (start < len && (static_cast<unsigned char>(before[start]) & 0xC0) == 0x80) ++start;
  context_tail_.assign(before + start, static_cast<size_t>(len - start));
}

/// 从每个起点收集"连续音节链 → DAT 词"边（增量走树 + 模糊键变体，天然剪枝）
void PinyinEngine::CollectEdges() {
  for (int p = 0; p < seg_.len(); ++p) {
    if (input_[p] == kSeparator) continue;
    Walk(p, 0, 0, p, 0.0f, kWalkBudget);
  }
}

void PinyinEngine::Walk(int pos, int depth, int32_t node, int chain_start, float penalty,
                        int budget) {
  if (budget <= 0) return;
  const auto& edges = seg_.EdgesAt(pos);
  for (const SylEdge& e : edges) {
    if (e.syl == kNoSyllable) continue;
    // 模糊音键变体（§4.2.1）：typed 音节 → {si, shi} 等双向展开
    char vkeys[kMaxKeyVariants][8];
    float vpens[kMaxKeyVariants];
    int nv = KeyVariants(SyllableAt(e.syl), fuzzy_, vkeys, vpens, kMaxKeyVariants);
    for (int vi = 0; vi < nv; ++vi) {
      int klen = static_cast<int>(std::strlen(vkeys[vi]));
      if (klen + (depth > 0 ? 1 : 0) >= kMaxKeyBytes) continue;
      // 本支路独立走 DAT（不污染兄弟支路）
      int32_t n = node;
      bool ok = true;
      if (depth > 0) {
        n = dict_->Step(n, static_cast<uint8_t>(' '));
        if (n < 0) ok = false;
      }
      for (int i = 0; ok && i < klen; ++i) {
        n = dict_->Step(n, static_cast<uint8_t>(vkeys[vi][i]));
        if (n < 0) ok = false;
      }
      if (!ok) continue;

      float pen2 = penalty + vpens[vi];
      int32_t li = dict_->TerminalList(n);
      if (li >= 0) {
        WordEdge& we = wedges_[chain_start].emplace_back();
        we.start = static_cast<uint16_t>(chain_start);
        we.end = e.end;
        we.penalty = pen2;
        int cnt = dict_->ListCount(li);
        if (cnt > kWordsPerKey) cnt = kWordsPerKey;
        for (int k = 0; k < cnt; ++k) we.ids[we.n++] = dict_->ListItem(li, k);
      }
      if (depth + 1 < kMaxChain) {
        int np = e.end;
        if (np < seg_.len() && input_[np] == kSeparator) np += 1;  // 词内分隔符吞并
        if (np > pos && np <= seg_.len()) Walk(np, depth + 1, n, chain_start, pen2, budget - 1);
      }
    }
  }
}

void PinyinEngine::BeamSearch() {
  if (wedges_[0].empty()) return;
  BeamState init;
  init.pos = 0;
  beams_[0].push_back(init);
  for (int pos = 0; pos < seg_.len(); ++pos) {
    if (beams_[pos].empty()) continue;
    // 剪枝：同位置按分数保留 beam_width_ 条
    if (static_cast<int>(beams_[pos].size()) > beam_width_) {
      std::stable_sort(beams_[pos].begin(), beams_[pos].end(),
                       [](const BeamState& a, const BeamState& b) { return a.score > b.score; });
      beams_[pos].resize(static_cast<size_t>(beam_width_));
    }
    const std::vector<BeamState>& bucket = beams_[pos];
    const auto& wlist = wedges_[pos];
    for (size_t si = 0; si < bucket.size(); ++si) {
      const BeamState st = bucket[si];  // 拷贝，防止扩容后引用失效
      for (size_t ei = 0; ei < wlist.size(); ++ei) {
        const WordEdge& we = wlist[ei];
        for (uint8_t wi = 0; wi < we.n; ++wi) {
          uint32_t id = we.ids[wi];
          BeamState ns;
          ns.pos = we.end;
          ns.src_pos = static_cast<uint16_t>(pos);
          ns.edge_idx = static_cast<int32_t>(ei);
          ns.word_slot = wi;
          ns.src_idx = static_cast<int32_t>(si);
          ns.score = st.score + WordScore(id, we.penalty);
          ns.word_id = id;
          if (ns.pos <= kMaxPos) beams_[ns.pos].push_back(ns);
        }
      }
    }
  }
}

float PinyinEngine::WordScore(uint32_t id, float edge_penalty) const {
  float s = std::log10(static_cast<float>(dict_->WordFreq(id)) + 1.0f) * kUnigramScale;
  s -= edge_penalty * kFuzzyPenaltyWeight;
  s -= wip_;  // unigram 概率归一化（log10(Σfreq)/词）
  const char* w = dict_->WordText(id);
  if (um_ != nullptr && w != nullptr) s += um_->Boost(w);
  // n-gram 通道预留（§4.3 三元模型随资源包热更启用，M1 基线：unigram + 用户模型）
  return s;
}

void PinyinEngine::BuildCandidates() {
  cands_.clear();
  // —— 整句候选：完全覆盖输入的 Beam 终结态（§4.2.3）——
  const auto& finals = beams_[seg_.len()];
  if (!finals.empty()) {
    std::vector<int32_t> order(finals.size());
    for (size_t i = 0; i < finals.size(); ++i) order[i] = static_cast<int32_t>(i);
    std::stable_sort(order.begin(), order.end(), [&finals](int32_t a, int32_t b) {
      return finals[a].score > finals[b].score;
    });
    for (int32_t oi : order) {
      if (static_cast<int>(cands_.size()) >= max_candidates_) break;
      std::string text, py;
      if (!SentenceFrom(seg_.len(), oi, &text, &py)) continue;
      bool dup = false;
      for (const Cand& c : cands_) {
        if (c.text == text) { dup = true; break; }
      }
      if (dup) continue;
      Cand c;
      c.text = std::move(text);
      c.pinyin = std::move(py);
      c.score = finals[static_cast<size_t>(oi)].score;
      c.full = true;
      cands_.push_back(std::move(c));
    }
  }
  // —— 部分匹配候选（v1.1，长输入兜底）：整句路径不存在时（词典对后续音节
  //    无覆盖），从后向前找可达的 Beam 桶，回溯出"已解码前缀"的整句形态候选。
  //    覆盖越长分数越高（多词 log 频率累加），天然按长度降序；
  //    end = 覆盖位置，Commit 后剩余输入继续合成（与词组候选同语义）。
  if (static_cast<int>(cands_.size()) < 3 && seg_.len() > 1) {
    int partials = 0;
    for (int pos = seg_.len() - 1; pos >= 1 && partials < 3; --pos) {
      const auto& bucket = beams_[static_cast<size_t>(pos)];
      if (bucket.empty()) continue;
      int32_t best = 0;
      for (int32_t i = 1; i < static_cast<int32_t>(bucket.size()); ++i) {
        if (bucket[static_cast<size_t>(i)].score > bucket[static_cast<size_t>(best)].score) best = i;
      }
      std::string text, py;
      if (!SentenceFrom(pos, best, &text, &py)) continue;
      bool dup = false;
      for (const Cand& c : cands_) {
        if (c.text == text) { dup = true; break; }
      }
      if (dup) continue;
      Cand c;
      c.text = std::move(text);
      c.pinyin = std::move(py);
      c.score = bucket[static_cast<size_t>(best)].score;
      c.full = false;
      c.end = static_cast<uint16_t>(pos);
      cands_.push_back(std::move(c));
      ++partials;
    }
  }
  // —— 词组候选：起点 0 的各词边，按综合分（含用户 Boost，§4.5）取前几 ——
  for (const WordEdge& we : wedges_[0]) {
    if (static_cast<int>(cands_.size()) >= max_candidates_) break;
    std::vector<uint32_t> ids(we.ids, we.ids + we.n);
    std::stable_sort(ids.begin(), ids.end(), [this, &we](uint32_t a, uint32_t b) {
      return WordScore(a, we.penalty) > WordScore(b, we.penalty);
    });
    int per = 0;
    for (uint32_t id : ids) {
      if (per >= 3 || static_cast<int>(cands_.size()) >= max_candidates_) break;
      const char* t = dict_->WordText(id);
      if (t == nullptr) continue;
      bool dup = false;
      for (const Cand& c : cands_) {
        if (c.text == t) { dup = true; break; }
      }
      if (dup) { ++per; continue; }
      Cand c;
      c.text = t;
      const char* py = dict_->WordPinyin(id);
      c.pinyin = py != nullptr ? py : "";
      c.score = WordScore(id, we.penalty);
      c.full = false;
      c.end = we.end;
      cands_.push_back(std::move(c));
      ++per;
    }
  }
  // 候选分组有序：整句（覆盖全输入）→ 部分匹配（覆盖长度降序）→ 首词词组。
  // v1.1 起不再全局按分混排：partial 多词分数（log 概率乘积）天然低于单词，
  // 全局混排会把长输入兜底候选沉底，导致"候选栏看似无候选"（体验回归）；
  // 用户词 Boost 仍作用于整句/部分匹配内部选词（WordScore→um_->Boost）。
}

/// 从 end_pos 桶的第 idx 个终结状态回溯整句
bool PinyinEngine::SentenceFrom(int end_pos, int32_t idx, std::string* text,
                                std::string* py) const {
  std::vector<uint32_t> seq;
  int pos = end_pos;
  int32_t cur = idx;
  int guard = 0;
  while (cur >= 0 && pos >= 0 && guard++ < 128) {  // v1.1：合成区 64 字节 → 最多 64 词
    const BeamState& st = beams_[pos][static_cast<size_t>(cur)];
    if (st.word_id != 0 || pos != 0) seq.push_back(st.word_id);
    if (st.src_idx < 0) break;
    cur = st.src_idx;
    pos = st.src_pos;
  }
  if (seq.empty()) return false;
  std::reverse(seq.begin(), seq.end());
  // 拼接词与拼音（词间无分隔，拼音单空格分隔）
  for (size_t i = 0; i < seq.size(); ++i) {
    const char* w = dict_->WordText(seq[i]);
    const char* p = dict_->WordPinyin(seq[i]);
    if (w == nullptr || p == nullptr) return false;
    text->append(w);
    if (i > 0) py->push_back(' ');
    py->append(p);
  }
  return !text->empty();
}

// —— v1.3 兜底与上下文通道实现（§4.2 增：字词联想/智能分词，对齐搜狗式简拼）——

/// 首字母简拼 DFS：每个输入字母视为一个音节的首字母，沿 DAT 展开收集
/// "全部字母恰好消费完"的词（d→的；dd→大大/地大；sj→世界/时间）。
/// out 维护 top-max_out 高分词（同层 penalty 相同，score 单调于词频），
/// budget 按音节扩展次数递减，防止 zh/chi 类宽首字母组合爆炸。
void PinyinEngine::AbbrevExpand(int letter_pos, int32_t node, float penalty, int* budget,
                                std::vector<Cand>* out, int max_out, int depth) {
  if (budget == nullptr || *budget <= 0 || out == nullptr || max_out <= 0) return;
  if (depth > kAbbrevMaxLetters) return;
  if (letter_pos >= static_cast<int>(input_.size())) return;
  int b = 0, e = 0;
  if (!FirstLetterSpan(input_[static_cast<size_t>(letter_pos)], &b, &e)) return;
  const bool last = letter_pos + 1 >= static_cast<int>(input_.size());
  for (int si = b; si < e && *budget > 0; ++si) {
    const char* syl = SyllableAt(si);
    int32_t n = node;
    bool ok = true;
    if (depth > 0) {
      n = dict_->Step(n, static_cast<uint8_t>(' '));
      if (n < 0) ok = false;
    }
    for (const char* p = syl; ok && *p != '\0'; ++p) {
      n = dict_->Step(n, static_cast<uint8_t>(*p));
      if (n < 0) ok = false;
    }
    --*budget;  // 每音节扩展计一次预算（无论成败，均匀限流）
    if (!ok) continue;
    if (last) {
      int32_t li = dict_->TerminalList(n);
      if (li < 0) continue;
      int cnt = dict_->ListCount(li);
      for (int k = 0; k < cnt && *budget > 0; ++k) {
        uint32_t id = dict_->ListItem(li, k);
        const char* t = dict_->WordText(id);
        const char* py = dict_->WordPinyin(id);
        if (t == nullptr || py == nullptr) continue;
        // 简拼惩罚：进入层前累计 + 当前层字母（收集层恒为最末字母）
        float sc = WordScore(id, 0.0f) - penalty - kAbbrevPenaltyPerLetter;
        if (static_cast<int>(out->size()) < max_out) {
          Cand c;
          c.text = t;
          c.pinyin = py;
          c.score = sc;
          c.full = true;
          out->push_back(std::move(c));
        } else {
          // top-max_out 替换：找当前最小分，新词更高则顶替
          size_t worst = 0;
          for (size_t i = 1; i < out->size(); ++i) {
            if ((*out)[i].score < (*out)[worst].score) worst = i;
          }
          if (sc > (*out)[worst].score) {
            (*out)[worst].text = t;
            (*out)[worst].pinyin = py;
            (*out)[worst].score = sc;
          }
        }
      }
    } else {
      AbbrevExpand(letter_pos + 1, n, penalty + kAbbrevPenaltyPerLetter, budget, out,
                   max_out, depth + 1);
    }
  }
}

/// 指定首字母下各音节的"热度"摘要：走 DAT 收集音节键下词频总和与最高频词，
/// 按 freq_sum 降序取前 k（d→de(的)/da(大)；供字组合兜底选字）。
void PinyinEngine::TopSyllablesForLetter(char c, int k, std::vector<AbbrevTop>* out) {
  out->clear();
  if (dict_ == nullptr || !dict_->valid() || out == nullptr || k <= 0) return;
  int b = 0, e = 0;
  if (!FirstLetterSpan(c, &b, &e)) return;
  for (int si = b; si < e; ++si) {
    const char* syl = SyllableAt(si);
    int32_t n = 0;
    bool ok = true;
    for (const char* p = syl; *p != '\0'; ++p) {
      n = dict_->Step(n, static_cast<uint8_t>(*p));
      if (n < 0) { ok = false; break; }
    }
    if (!ok) continue;
    int32_t li = dict_->TerminalList(n);
    if (li < 0) continue;
    AbbrevTop top;
    top.syl = syl;
    const int cnt = dict_->ListCount(li);
    for (int i = 0; i < cnt; ++i) {
      const uint32_t id = dict_->ListItem(li, i);
      const float f = static_cast<float>(dict_->WordFreq(id));
      top.freq_sum += f;
      if (f > top.top_freq) {
        top.top_freq = f;
        const char* t = dict_->WordText(id);
        top.word = t != nullptr ? t : "";
      }
    }
    if (top.freq_sum > 0.0f) out->push_back(std::move(top));
  }
  std::stable_sort(out->begin(), out->end(),
                   [](const AbbrevTop& a, const AbbrevTop& b) { return a.freq_sum > b.freq_sum; });
  if (static_cast<int>(out->size()) > k) out->resize(static_cast<size_t>(k));
}

/// 通道 A（整串简拼兜底）：主通道无词边（wedges_[0] 空 ⇒ 无任何合法切分起点，
/// 如 d/dd/sj/zh）时启用。词候选来自 AbbrevExpand（取前 kAbbrevTake）；
/// 双字母再补"最热音节×最高频单字"组合候选（dd→的的），对齐搜狗简拼体验。
void PinyinEngine::BuildAbbrevCandidates() {
  if (dict_ == nullptr || !dict_->valid()) return;
  const int n = static_cast<int>(input_.size());
  if (n == 0 || n > kAbbrevMaxLetters) return;
  if (!wedges_[0].empty()) return;  // 主通道已覆盖 → 非纯简拼意图
  for (const char ch : input_) {
    if (ch < 'a' || ch > 'z') return;  // 仅纯小写字母串（分隔符/数字走主通道语义）
  }

  std::vector<Cand> tmp;
  int budget = kAbbrevBudget;
  AbbrevExpand(0, 0, 0.0f, &budget, &tmp, kAbbrevMaxOut, 0);
  std::stable_sort(tmp.begin(), tmp.end(),
                   [](const Cand& a, const Cand& b) { return a.score > b.score; });

  std::vector<std::string> seen;
  int taken = 0;
  for (const Cand& c : tmp) {
    if (taken >= kAbbrevTake) break;
    bool dup = false;
    for (const std::string& s : seen) {
      if (s == c.text) { dup = true; break; }
    }
    if (dup) continue;  // 多音字同字异 id（de/di 下的"地"）只收一次
    seen.push_back(c.text);
    Cand out;
    out.text = c.text;
    out.pinyin = c.pinyin;
    out.score = c.score;
    out.full = true;  // 覆盖全部输入，Commit 后清空合成区
    cands_.push_back(std::move(out));
    ++taken;
  }

  // 双字母字组合兜底：DAT 无该组合词（如"的的"）时用两字母最热音节的最高频
  // 单字拼伪整句，分 = Σlog10(词频+1) - n·wip_ - n·简拼惩罚（unigram 乘积，
  // 概率正确：真词"地大"天然高于字组合"的的"，但两者都进候选）。
  if (n == 2) {
    std::vector<AbbrevTop> t1, t2;
    TopSyllablesForLetter(input_[0], 2, &t1);
    TopSyllablesForLetter(input_[1], 2, &t2);
    struct Combo { std::string text; std::string py; float score; };
    std::vector<Combo> combos;
    for (const AbbrevTop& a : t1) {
      if (a.word.size() != 3) continue;  // 仅拼单字（UTF-8 中文 3 字节）
      for (const AbbrevTop& b : t2) {
        if (b.word.size() != 3) continue;
        Combo cb;
        cb.text = a.word + b.word;
        cb.py = a.syl + " " + b.syl;
        cb.score = std::log10(a.top_freq + 1.0f) + std::log10(b.top_freq + 1.0f)
                 - 2.0f * wip_ - 2.0f * kAbbrevPenaltyPerLetter;
        combos.push_back(std::move(cb));
      }
    }
    std::stable_sort(combos.begin(), combos.end(),
                     [](const Combo& a, const Combo& b) { return a.score > b.score; });
    int ct = 0;
    for (const Combo& cb : combos) {
      if (ct >= 4) break;  // 2×2 全组合（地地/地大/大地/大大 类），去重后尽量多给
      bool dup = false;
      for (const Cand& x : cands_) {
        if (x.text == cb.text) { dup = true; break; }
      }
      if (dup) continue;
      Cand c;
      c.text = cb.text;
      c.pinyin = cb.py;
      c.score = cb.score;
      c.full = true;
      cands_.push_back(std::move(c));
      ++ct;
    }
  }
}

/// 通道 B（尾段简拼）：主通道已覆盖合法前缀但尾段字母无法切分（xianz→
/// "现在"）时，前缀句 top2 × 尾段"拼音键直查"真词 top2 交叉组合成整句
/// 候选，插入第一个部分匹配候选之前（组合意图强于"前缀单字+继续输入"）。
/// 直查 = 前缀拼音 + 尾段各字母枚举音节拼完整键查词典（xian+zai→现在），
/// 仅出真词，规避 beam/词边截断与伪词拼接（"先在"类字拼不生成）。
void PinyinEngine::BuildTailAbbrevCandidates() {
  if (dict_ == nullptr || !dict_->valid()) return;
  const int len = static_cast<int>(input_.size());
  if (len == 0 || wedges_[0].empty()) return;  // 无前缀 → 通道 A 职责

  for (int pos = len - 1; pos >= 1 && pos >= len - kAbbrevMaxLetters; --pos) {
    bool all_letter = true;
    for (int i = pos; i < len; ++i) {
      const char ch = input_[static_cast<size_t>(i)];
      if (ch < 'a' || ch > 'z') { all_letter = false; break; }
    }
    if (!all_letter) continue;
    const auto& bucket = beams_[static_cast<size_t>(pos)];
    if (bucket.empty()) continue;  // 前缀 [0,pos) 不可解码 → 尝试更短尾段

    // 前缀句：桶内最高分状态回溯（提供 key 前半段读音，如 "xian"）
    int32_t best = 0;
    for (int32_t i = 1; i < static_cast<int32_t>(bucket.size()); ++i) {
      if (bucket[static_cast<size_t>(i)].score > bucket[static_cast<size_t>(best)].score) best = i;
    }
    std::string ptext, prefix_py;
    if (!SentenceFrom(pos, best, &ptext, &prefix_py) || prefix_py.empty()) continue;

    // 尾段拼音键直查：前缀句拼音 + 尾段各字母枚举音节拼完整键（xian+"z"→
    // "xian zai"）查词典真词。命中词的读音覆盖前缀（"现在"=xian+zai），
    // 故直接作为完整候选（不再拼接前缀文本），top-4 收集（budget 限流）。
    const std::string tail = input_.substr(static_cast<size_t>(pos));
    std::vector<Cand> words;  // 直查命中的完整候选（top-4 维护）
    int budget = kAbbrevBudget;
    const std::function<void(int, const std::string&)> DfsKey =
        [&](int lp, const std::string& key) {
          if (budget <= 0 || words.size() >= 64) return;
          --budget;
          if (lp >= static_cast<int>(tail.size())) {
            if (key.empty()) return;
            const std::string full = prefix_py + " " + key;
            uint32_t ids[8];
            const int nw = dict_->QueryWords(full.c_str(), ids, 8);
            for (int k = 0; k < nw; ++k) {
              const char* t = dict_->WordText(ids[static_cast<size_t>(k)]);
              if (t == nullptr) continue;
              const float sc = WordScore(ids[static_cast<size_t>(k)], 0.0f);
              if (static_cast<int>(words.size()) < 4) {
                Cand c;
                c.text = t;
                c.pinyin = full;
                c.score = sc;
                c.full = true;
                words.push_back(std::move(c));
              } else {
                size_t worst = 0;
                for (size_t i = 1; i < words.size(); ++i) {
                  if (words[i].score < words[worst].score) worst = i;
                }
                if (sc > words[worst].score) {
                  words[worst].text = t;
                  words[worst].pinyin = full;
                  words[worst].score = sc;
                }
              }
            }
            return;
          }
          int b = 0, e = 0;
          if (!FirstLetterSpan(tail[static_cast<size_t>(lp)], &b, &e)) return;
          for (int si = b; si < e && budget > 0; ++si) {
            const std::string next = key.empty()
                                         ? std::string(SyllableAt(si))
                                         : key + " " + SyllableAt(si);
            DfsKey(lp + static_cast<int>(strlen(SyllableAt(si))), next);
          }
        };
    DfsKey(0, std::string());
    if (words.empty()) continue;

    // 直查词按分排序取前 2（"现在" 真词分碾压字拼）
    std::stable_sort(words.begin(), words.end(),
                     [](const Cand& a, const Cand& b) { return a.score > b.score; });
    bool added = false;
    int take = 0;
    for (const Cand& w : words) {
      if (take >= 2) break;
      bool dup = false;
      for (const Cand& x : cands_) {
        if (x.text == w.text) { dup = true; break; }
      }
      if (dup) continue;
      if (static_cast<int>(cands_.size()) >= max_candidates_ && !cands_.empty()) {
        cands_.pop_back();  // 候选满员：淘汰最弱词组候选，保障组合入场
      }
      Cand c;
      c.text = w.text;
      c.pinyin = w.pinyin;
      c.score = w.score;
      c.full = true;
      size_t at = cands_.size();
      for (size_t i = 0; i < cands_.size(); ++i) {
        if (!cands_[i].full) { at = i; break; }  // 保持分组：整句区尾插入
      }
      cands_.insert(cands_.begin() + static_cast<std::ptrdiff_t>(at), std::move(c));
      added = true;
      ++take;
    }
    if (added) break;  // 最长可解码尾段一轮即可
  }
}

/// 上下文重排：候选首字与上文末字重复时让位（用户打完"的"再打 d，首选
/// 不再是"的"而是"地/大"；结合前文排列候选，§4.5 增）。
/// AA 叠词豁免：候选以"上文末字×2"开头（上文"好"→候选"好好"）不惩罚。
void PinyinEngine::ApplyContextRerank() {
  if (context_tail_.empty() || cands_.size() < 2) return;
  const std::string last = LastUtf8Char(context_tail_);
  if (last.empty()) return;
  const std::string& first = cands_[0].text;
  if (first.size() < last.size() || first.compare(0, last.size(), last) != 0) return;
  if (first.size() >= 2 * last.size() &&
      first.compare(last.size(), last.size(), last) == 0) {
    return;  // 叠词豁免
  }
  const size_t limit = std::min<size_t>(cands_.size(), 4);
  for (size_t j = 1; j < limit; ++j) {
    const std::string& t = cands_[j].text;
    if (t.size() >= last.size() && t.compare(0, last.size(), last) == 0) continue;
    if (cands_[0].score - cands_[j].score > kContextSwapGap) return;  // 分差过大不换
    std::swap(cands_[0], cands_[j]);
    return;
  }
}

int PinyinEngine::T9PinyinCount() const { return static_cast<int>(T9List().size()); }

std::string PinyinEngine::T9PinyinAt(int i) const {
  auto v = T9List();
  if (i < 0 || i >= static_cast<int>(v.size())) return "";
  return v[static_cast<size_t>(i)];
}

std::vector<std::string> PinyinEngine::T9List() const {
  std::vector<std::string> out;
  auto push = [&](const std::string& s) {
    for (const auto& x : out) {
      if (x == s) return;
    }
    if (static_cast<int>(out.size()) < 8) out.push_back(s);
  };
  for (const SylEdge& e : seg_.EdgesAt(0)) {
    if (e.syl != kNoSyllable) push(SyllableAt(e.syl));
  }
  for (const WordEdge& we : wedges_[0]) {
    if (we.n > 0 && dict_ != nullptr) {
      const char* py = dict_->WordPinyin(we.ids[0]);
      if (py != nullptr) {
        std::string first(py);
        size_t sp = first.find(' ');
        if (sp != std::string::npos) first.resize(sp);
        push(first);
      }
    }
  }
  return out;
}

PinyinEngine::CommitResult PinyinEngine::Commit(int index) {
  CommitResult r;
  if (index < 0 || index >= static_cast<int>(cands_.size())) return r;
  const Cand& c = cands_[static_cast<size_t>(index)];
  r.text = c.text;
  r.pinyin = c.pinyin;
  r.sentence = c.full;
  if (um_ != nullptr) {
    if (c.full) um_->Learn(c.text.c_str(), c.pinyin.c_str(), LEARN_SENTENCE);
    else um_->Learn(c.text.c_str(), c.pinyin.c_str(), LEARN_SELECT);
  }
  if (c.full) {
    Reset();
  } else {
    std::string rest = input_.substr(c.end);
    if (!rest.empty() && rest[0] == kSeparator) rest.erase(rest.begin());
    SetInput(rest);
  }
  r.ok = true;
  return r;
}

}  // namespace gk
