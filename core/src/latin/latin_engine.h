// 英文引擎（§4.4）：词典补全 + 编辑距离自动更正（QWERTY 邻键加权）
// 英文引擎（§4.4）：词典补全 + 编辑距离自动更正（QWERTY 邻键加权）
#ifndef GLASSKEY_CORE_LATIN_ENGINE_H_
#define GLASSKEY_CORE_LATIN_ENGINE_H_

#include <cstdint>
#include <string>
#include <vector>

#include "dict/dict_store.h"
#include "user/user_model.h"

namespace gk {

class LatinEngine {
 public:
  void SetDict(DictStore* dict);
  void SetUserModel(UserModel* um) { um_ = um; }

  /// 输入小写字母串（UI 层负责大小写视觉状态），触发重解码。
  void SetInput(const std::string& letters);
  void Reset() { input_.clear(); cands_.clear(); store_.clear(); }
  void Backspace() {
    if (!input_.empty()) input_.pop_back();
    Decode();
  }
  const std::string& input() const { return input_; }

  int CandidateCount() const { return static_cast<int>(cands_.size()); }
  /// 返回候选文本；store_ 持有生命周期。
  const char* candidate(int i) const;
  /// 提交候选（学习回写），清空合成区。返回文本。
  std::string Commit(int index);

 private:
  static constexpr int kMaxCandidates = 30;  // v1.4：统一候选上限 30
  void Decode();
  void BuildAdjacency();   ///< v1.1：邻键矩阵 + 词长缓存预计算（大词典性能）
  /// 带邻键权重的受限编辑距离（长度由调用方缓存预剪枝后传入）；adj_subs 输出邻键替换次数。
  float EditCost(const char* a, int la, const char* b, int lb, float max_cost,
                 int* adj_subs) const;
  /// 邻键查询（O(1) 矩阵查表；v1.1 前为线性扫描，6 万词全表 DP 时不可接受）
  bool Adjacent(char a, char b) const {
    return adj_[static_cast<uint8_t>(a)][static_cast<uint8_t>(b)];
  }

  std::string input_;
  std::vector<int> cands_;              ///< 词 id
  std::vector<std::string> store_;      ///< 候选文本持有
  std::vector<uint8_t> word_lens_;      ///< v1.1：词长缓存（避免每词 strlen）
  bool adj_[256][256] = {};             ///< 邻键矩阵（构建时预计算）
  DictStore* dict_ = nullptr;
  UserModel* um_ = nullptr;
};

}  // namespace gk

#endif  // GLASSKEY_CORE_LATIN_ENGINE_H_
