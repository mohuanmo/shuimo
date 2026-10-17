#include "latin_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace gk {

void LatinEngine::SetDict(DictStore* dict) {
  dict_ = dict;
  BuildAdjacency();
}

/// v1.1：邻键矩阵 + 词长缓存一次性预计算。
/// 原实现 Adjacent() 每次 O(26) 字符扫描、词长每轮 strlen——
/// 6 万词全表 DP 场景下分别贡献 ~300ms/次 解码（实测）。
void LatinEngine::BuildAdjacency() {
  static const char* kRows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
  for (int r = 0; r < 3; ++r) {
    for (int c = 0; kRows[r][c] != '\0'; ++c) {
      uint8_t ch = static_cast<uint8_t>(kRows[r][c]);
      for (int dr = -1; dr <= 1; ++dr) {
        if (r + dr < 0 || r + dr > 2) continue;
        for (int dc = -1; dc <= 1; ++dc) {
          const char* row = kRows[r + dr];
          if (c + dc < 0 || c + dc >= static_cast<int>(std::strlen(row))) continue;
          adj_[ch][static_cast<uint8_t>(row[c + dc])] = true;
        }
      }
    }
  }
  word_lens_.clear();
  if (dict_ != nullptr && dict_->valid()) {
    int n = dict_->WordCount();
    word_lens_.resize(static_cast<size_t>(n));
    for (uint32_t i = 0; i < static_cast<uint32_t>(n); ++i) {
      const char* w = dict_->WordText(i);
      size_t len = 0;
      if (w != nullptr) {
        // 英文词纯 ASCII，词长 = 字节数（与 strlen 等价，免每次调用）
        while (w[len] != '\0') ++len;
        if (len > 255) len = 255;
      }
      word_lens_[i] = static_cast<uint8_t>(len);
    }
  }
}

const char* LatinEngine::candidate(int i) const {
  if (i < 0 || i >= static_cast<int>(store_.size())) return nullptr;
  return store_[static_cast<size_t>(i)].c_str();
}

float LatinEngine::EditCost(const char* a, int la, const char* b, int lb,
                            float max_cost, int* adj_subs) const {
  if (adj_subs != nullptr) *adj_subs = 0;
  // 长度差预剪枝（v1.1 大词典）：编辑距离 ≥ 长度差，超出预算直接返回，
  // 避免 6 万词 × O(la·lb) DP 全表扫描
  if (la > 20 || lb > 20 || std::abs(la - lb) > static_cast<int>(max_cost)) return 99.0f;
  float d[22][22];
  for (int i = 0; i <= la; ++i) d[i][0] = static_cast<float>(i);
  for (int j = 0; j <= lb; ++j) d[0][j] = static_cast<float>(j);
  for (int i = 1; i <= la; ++i) {
    for (int j = 1; j <= lb; ++j) {
      float sub;
      if (a[i - 1] == b[j - 1]) {
        sub = d[i - 1][j - 1];
      } else {
        sub = d[i - 1][j - 1] + (Adjacent(a[i - 1], b[j - 1]) ? 0.55f : 1.0f);
      }
      float ins = d[i - 1][j] + 1.0f;
      float del = d[i][j - 1] + 1.0f;
      float best = std::min({sub, ins, del});
      // Damerau 转置（teh→the 等高频错拼）
      if (i >= 2 && j >= 2 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
        best = std::min(best, d[i - 2][j - 2] + 0.8f);
      }
      d[i][j] = best;
    }
  }
  if (adj_subs != nullptr) {
    int mn = la < lb ? la : lb;
    for (int i = 0; i < mn; ++i) {
      if (a[i] != b[i] && Adjacent(a[i], b[i])) *adj_subs += 1;
    }
  }
  return d[la][lb] <= max_cost ? d[la][lb] : 99.0f;
}

void LatinEngine::SetInput(const std::string& letters) {
  input_ = letters;
  if (input_.size() > 32) input_.resize(32);
  Decode();
}

void LatinEngine::Decode() {
  cands_.clear();
  store_.clear();
  if (input_.empty() || dict_ == nullptr || !dict_->valid()) return;
  const int n = dict_->WordCount();

  // 1) 前缀补全通道
  std::vector<uint32_t> ids(64);
  int nc = dict_->QueryPrefix(input_.c_str(), ids.data(), static_cast<int>(ids.size()));
  std::vector<std::pair<float, uint32_t>> scored;
  for (int i = 0; i < nc; ++i) {
    uint32_t id = ids[static_cast<size_t>(i)];
    const char* w = dict_->WordText(id);
    if (w == nullptr) continue;
    // 前缀补全固定小加成（输入即词前缀，用户意图明确）
    float sc = std::log10(static_cast<float>(dict_->WordFreq(id)) + 1.0f) + 0.5f;
    if (um_ != nullptr) sc += um_->Boost(w);
    scored.emplace_back(sc, id);
  }
  // 2) 自动更正通道（编辑距离 + 邻键加权，§4.4；编辑代价主导，避免压过补全通道）
  //    v1.1：词长缓存 + 长度差预剪枝 + DP 内 O(1) 邻键查表（大词典性能关键）
  if (input_.size() >= 3) {
    const int la = static_cast<int>(input_.size());
    float max_cost = input_.size() >= 5 ? 2.0f : 1.0f;
    for (uint32_t id = 0; id < static_cast<uint32_t>(n); ++id) {
      if (id >= word_lens_.size()) break;
      int lb = static_cast<int>(word_lens_[id]);
      if (lb == 0 || std::abs(la - lb) > static_cast<int>(max_cost)) continue;
      const char* w = dict_->WordText(id);
      if (w == nullptr) continue;
      // 首字符快筛（v1.1）：目标词首字符必须与输入首字符相同或互为邻键
      // （Adjacent 含自身）。真实错拼 90%+ 满足该条件（teh/the、helo/hello、
      // adress/address），可再剪掉 ~90% DP 调用；词首双错的边缘场景由
      // 前缀补全通道兜底，可接受（§4.4 更正误伤率优先级高于召回长尾）。
      if (lb > 0 && !Adjacent(input_[0], w[0])) continue;
      int adj = 0;
      float cost = EditCost(input_.c_str(), la, w, lb, max_cost, &adj);
      if (cost >= 99.0f) continue;
      float sc = std::log10(static_cast<float>(dict_->WordFreq(id)) + 1.0f) -
                 0.9f * cost + 0.25f * static_cast<float>(adj);
      if (um_ != nullptr) sc += um_->Boost(w);
      scored.emplace_back(sc, id);
    }
  }
  std::stable_sort(scored.begin(), scored.end(),
                   [](const auto& a, const auto& b) { return a.first > b.first; });
  // 去重 + 截断
  uint32_t last = 0xFFFFFFFFu;
  int taken = 0;
  for (const auto& p : scored) {
    if (p.second == last) continue;
    last = p.second;
    const char* w = dict_->WordText(p.second);
    if (w == nullptr) continue;
    bool dup = false;
    for (const auto& s : store_) {
      if (s == w) { dup = true; break; }
    }
    if (dup) continue;
    store_.emplace_back(w);
    cands_.push_back(static_cast<int>(p.second));
    if (++taken >= kMaxCandidates) break;
  }
}

std::string LatinEngine::Commit(int index) {
  if (index < 0 || index >= static_cast<int>(cands_.size())) return "";
  uint32_t id = static_cast<uint32_t>(cands_[static_cast<size_t>(index)]);
  std::string text = dict_->WordText(id) != nullptr ? dict_->WordText(id) : "";
  if (um_ != nullptr && !text.empty()) {
    um_->Learn(text.c_str(), "", LEARN_SELECT);
  }
  Reset();
  return text;
}

}  // namespace gk
