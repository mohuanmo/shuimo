// 拼音引擎：合成区管理 → 音节切分 → 词典增链收集 → Beam 整句解码 → 候选（§4.1/§4.2）
#ifndef GLASSKEY_CORE_PINYIN_ENGINE_H_
#define GLASSKEY_CORE_PINYIN_ENGINE_H_

#include <string>
#include <vector>

#include "dict/dict_store.h"
#include "segment.h"
#include "user/user_model.h"

namespace gk {

struct Cand {
  std::string text;
  std::string pinyin;
  float score = 0.0f;
  bool full = false;     ///< true=整句候选；false=首词（词组）候选
  uint16_t end = 0;      ///< 词组候选：覆盖到的输入串位置
};

class PinyinEngine {
 public:
  void SetDict(DictStore* dict);  ///< 同时按词典词频总和计算词插入惩罚（unigram 归一化）
  void SetUserModel(UserModel* um) { um_ = um; }
  void SetConfig(uint32_t fuzzy, int beam_width, int max_candidates);

  void SetInput(const std::string& comp);  ///< 触发重解码
  void Backspace();
  void Reset();
  const std::string& input() const { return input_; }

  /// 设置上文（v1.3 上下文通道）：光标前已上屏文本（UTF-8），引擎截取尾部
  /// ≤18 字节参与候选重排（重复惩罚/让位）。空串或 nullptr 清空。
  void SetContext(const char* before, int len);

  int CandidateCount() const { return static_cast<int>(cands_.size()); }
  const Cand& candidate(int i) const { return cands_[i]; }

  struct CommitResult {
    bool ok = false;
    std::string text;       ///< 本次上屏文本
    std::string pinyin;     ///< 对应拼音（空格分隔）
    bool sentence = false;  ///< 是否整句（≥2 词）
  };
  CommitResult Commit(int index);

  int T9PinyinCount() const;
  std::string T9PinyinAt(int i) const;
  std::vector<std::string> T9List() const;

 private:
  static constexpr int kMaxPos = gk::kMaxCompositionBytes + 8;  // 位置桶数（v1.1：24→72，匹配合成区 64 字节）
  static constexpr int kMaxChain = 4;      // 词组最长音节数
  static constexpr int kWordsPerKey = 8;   // 单键最多词 id
  static constexpr int kMaxKeyBytes = 34;  // DAT 键长上限（音节+空格）
  static constexpr int kWalkBudget = 64;   // 单链起始点走树预算（防变体爆炸）

  struct WordEdge {
    uint16_t start = 0, end = 0;
    float penalty = 0.0f;
    uint32_t ids[kWordsPerKey]{};
    uint8_t n = 0;
  };
  struct BeamState {
    uint16_t pos = 0;        ///< 词尾位置（状态所在桶）
    uint16_t src_pos = 0;    ///< 前驱状态所在桶（词起点）
    int32_t edge_idx = -1;   ///< 前驱桶到本桶使用的 WordEdge 下标
    int32_t word_slot = -1;  ///< 该边使用的词 id 下标
    int32_t src_idx = -1;    ///< 前驱状态在其桶内下标（回溯指针）
    float score = 0.0f;
    uint32_t word_id = 0;
  };

  void Decode();
  void CollectEdges();
  void Walk(int pos, int depth, int32_t node, int chain_start, float penalty, int budget);
  void BeamSearch();
  float WordScore(uint32_t id, float edge_penalty) const;
  void BuildCandidates();
  bool SentenceFrom(int end_pos, int32_t idx, std::string* text, std::string* py) const;

  // —— v1.3 兜底与上下文通道（词联想/分词增强）——
  /// 首字母简拼位置（音节级高频摘要，用于字组合兜底候选）
  struct AbbrevTop {
    std::string syl;    ///< 音节
    std::string word;   ///< 该音节键下最高频词
    float freq_sum = 0.f;  ///< 该音节键词频总和（音节热度）
    float top_freq = 0.f;  ///< 最高频词词频（组合候选打分）
  };
  /// 首字母简拼 DFS：把每个字母视为音节首字母，沿 DAT 展开收集词。
  /// budget 为扩展次数预算（防组合爆炸）；depth 为已消费字母数。
  void AbbrevExpand(int letter_pos, int32_t node, float penalty, int* budget,
                    std::vector<Cand>* out, int max_out, int depth);
  /// 指定首字母下音节热度（词频总和）前 k 名及其最高频词。
  void TopSyllablesForLetter(char c, int k, std::vector<AbbrevTop>* out);
  /// 通道 A（整串简拼）：合成串全为字母且主通道无词边时启用 ——
  /// 单字母 d → 高频字候选池；dd → 大大/抵达/地大/的的（搜狗式首字母简拼，§4.2 增）。
  void BuildAbbrevCandidates();
  /// 通道 B（尾段简拼）：主解码覆盖"合法前缀 + 字母残段"（如 xianz）时 ——
  /// 前缀句 + 尾段首字母展开词组合成"现在"类候选，插入部分匹配候选之前。
  void BuildTailAbbrevCandidates();
  /// 上下文重排：候选首字与上文末字重复时让位（叠词豁免，§4.5 增）。
  void ApplyContextRerank();

  std::string input_;
  std::string context_tail_;  ///< 上文尾部（≤18 字节 UTF-8，SetContext 注入）
  Segmenter seg_;
  std::vector<WordEdge> wedges_[kMaxPos + 1];
  std::vector<BeamState> beams_[kMaxPos + 1];
  std::vector<Cand> cands_;
  DictStore* dict_ = nullptr;
  UserModel* um_ = nullptr;
  float wip_ = 6.0f;  ///< 词插入惩罚 = log10(Σfreq)：路径分变为 unigram 概率乘积
  uint32_t fuzzy_ = FUZZY_DEFAULT;
  int beam_width_ = 10;
  int max_candidates_ = 10;
};

}  // namespace gk

#endif  // GLASSKEY_CORE_PINYIN_ENGINE_H_
