// 音节切分（§4.2.1 / §4.2.2）+ 模糊音键变体（§4.2.1）
// 切分策略：26 键保留全部精确歧义路径（xian → xian / xi'an）；分隔符强制切分；
// T9 数字串 → 音节数字模式匹配。
// 模糊音策略：切分保持精确，模糊在 DAT 查询键上双向展开（zh/z、ch/c、sh/s、
// n/l、f/h、an/ang、en/eng、in/ing），即"边权置换"的等价实现，不重建词典。
#ifndef GLASSKEY_CORE_SEGMENT_H_
#define GLASSKEY_CORE_SEGMENT_H_

#include <string>
#include <vector>

#include "glasskey/gk_types.h"
#include "syllable_table.h"

namespace gk {

/// 合成分隔符（强制切分）
constexpr char kSeparator = '\'';
constexpr uint16_t kNoSyllable = 0xFFFF;

struct SylEdge {
  uint16_t start;   // 输入串起点（字节）
  uint16_t end;     // 输入串终点（字节）
  uint16_t syl;     // 音节下标；kNoSyllable 表示分隔符边界
  float penalty;    // 保留字段（模糊惩罚已迁移至键变体）
};

class Segmenter {
 public:
  /// layout: gk::Layout；fuzzy 由 KeyVariants 使用，切分本身只用精确匹配
  void Build(const char* input, int len, int layout, uint32_t fuzzy);
  int len() const { return len_; }
  int pos_count() const { return len_ + 1; }
  const std::vector<SylEdge>& EdgesAt(int pos) const;

 private:
  void BuildLetters(const char* input);
  void BuildDigits(const char* input);

  std::vector<SylEdge> edges_[gk::kMaxCompositionBytes + 8];  // 合成区 ≤64 字节（v1.1）
  int len_ = 0;
};

/// 模糊音键变体：typed 音节 → DAT 查询键集合（含自身，双向对称）。
/// 例如 sh/s：si → {si, shi}；an/ang：fan → {fan, fang}；n/l：li → {li, ni}。
/// pen[i] 为对应惩罚（自身为 0）；返回变体数（≤ cap，内部上限 6）。
int KeyVariants(const char* syl, uint32_t fuzzy, char out[][8], float* pen, int cap);

/// 单个模糊音类展开的成员数上限 × 韵尾类上限
constexpr int kMaxKeyVariants = 6;

/// 精确判断 [s, s+len) 是否为合法音节（字典序二分）。
bool IsSyllable(const char* s, int len);

/// 精确判断 [s, s+len) 是否为至少一个合法音节的前缀（如 "x" "xi" "xia"…）。
bool IsSyllablePrefix(const char* s, int len);

/// 合成串的展示切分段（v1.2 自动分词显示 + 手动分词共用）。
struct DisplaySeg {
  uint16_t start = 0;    ///< 字节起点
  uint16_t end = 0;      ///< 字节终点（不含尾随分隔符）
  bool anchored = false; ///< 段尾紧随手动锚点 '\''
  bool fallback = false; ///< 非音节兜底段（不完整音节尾部等）
};

/// 对合成串做最小代价音节切分 DP（与解码切分共用 Segmenter 音节图，
/// 每击键由 UI 层重算）。返回段列表（含锚点段信息）；空串返回空。
/// 代价：合法音节段 8-3×len（偏好长音节），兜底单字符段 10，锚点 0；
/// 段数惩罚内嵌于段开销，保证 "xian" 优先单音节而非 "xi an"。
std::vector<DisplaySeg> ComputeDisplaySegments(const std::string& input);

/// 自动分词显示串：音节边界以空格呈现，手动锚点 '\'' 紧贴前段原样呈现。
/// 例：xianzai→"xian zai"；xi'an→"xi'an"；xianz→"xian z"。
std::string SegmentedDisplay(const std::string& input);

}  // namespace gk

#endif  // GLASSKEY_CORE_SEGMENT_H_
