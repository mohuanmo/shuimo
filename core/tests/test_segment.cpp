#include <cstring>
#include "gk_test.h"
#include "pinyin/segment.h"

using namespace gk;

static bool HasEdge(const Segmenter& s, int pos, const char* syl) {
  int idx = FindSyllable(syl, static_cast<int>(std::strlen(syl)));
  if (idx < 0) return false;
  for (const SylEdge& e : s.EdgesAt(pos)) {
    if (e.syl == static_cast<uint16_t>(idx) && e.penalty == 0.0f) return true;
  }
  return false;
}

static bool HasFuzzyEdge(const Segmenter& s, int pos, const char* syl) {
  int idx = FindSyllable(syl, static_cast<int>(std::strlen(syl)));
  if (idx < 0) return false;
  for (const SylEdge& e : s.EdgesAt(pos)) {
    if (e.syl == static_cast<uint16_t>(idx) && e.penalty > 0.0f) return true;
  }
  return false;
}

void test_segment_basic() {
  Segmenter seg;
  // "xian" 歧义路径全保留（§4.2.1）
  seg.Build("xian", 4, LAYOUT_PINYIN_26, FUZZY_NONE);
  GK_CHECK(HasEdge(seg, 0, "xian"), "xian → xian 路径");
  GK_CHECK(HasEdge(seg, 0, "xi"), "xian → xi 路径");
  GK_CHECK(HasEdge(seg, 2, "an"), "xian → xi|an 路径");
  // "fangan" 多路径
  seg.Build("fangan", 6, LAYOUT_PINYIN_26, FUZZY_NONE);
  GK_CHECK(HasEdge(seg, 0, "fan"), "fangan → fan");
  GK_CHECK(HasEdge(seg, 3, "gan"), "fangan → fan|gan");
  GK_CHECK(HasEdge(seg, 0, "fang"), "fangan → fang");
  GK_CHECK(HasEdge(seg, 4, "an"), "fangan → fang|an");
  // 分隔符强制切分
  seg.Build("xi'an", 5, LAYOUT_PINYIN_26, FUZZY_NONE);
  const auto& e2 = seg.EdgesAt(2);  // 分隔符位于位置 2
  bool sep_edge = false;
  for (const SylEdge& e : e2) {
    if (e.syl == kNoSyllable) sep_edge = true;
  }
  GK_CHECK(sep_edge, "分隔符产生边界边");
  GK_CHECK(!HasEdge(seg, 0, "xian"), "分隔符阻断 xian 路径");
  GK_CHECK(HasEdge(seg, 3, "an"), "分隔符后 an 可匹配");
}

void test_segment_fuzzy() {
  char v[kMaxKeyVariants][8];
  float pen[kMaxKeyVariants];
  auto has = [&](int n, const char* key, bool fuzzy_pen) {
    for (int i = 0; i < n; ++i) {
      if (std::strcmp(v[i], key) == 0) {
        return fuzzy_pen ? pen[i] > 0.0f : pen[i] == 0.0f;
      }
    }
    return false;
  };
  // sh/s 双向（§4.2.1 用户可自选组合）
  int n = KeyVariants("si", FUZZY_SH_S, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "si", false), "si 自身惩罚 0");
  GK_CHECK(has(n, "shi", true), "si → shi 变体（惩罚 > 0）");
  n = KeyVariants("shi", FUZZY_SH_S, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "shi", false), "shi 自身");
  GK_CHECK(has(n, "si", true), "shi → si 变体（双向对称）");
  // an/ang 双向
  n = KeyVariants("fan", FUZZY_AN_ANG, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "fang", true), "fan → fang");
  n = KeyVariants("fang", FUZZY_AN_ANG, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "fan", true), "fang → fan");
  // n/l 双向
  n = KeyVariants("li", FUZZY_N_L, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "ni", true), "li → ni");
  n = KeyVariants("ni", FUZZY_N_L, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "li", true), "ni → li");
  // 关闭模糊 → 仅自身
  n = KeyVariants("xian", FUZZY_NONE, v, pen, kMaxKeyVariants);
  GK_CHECK_EQ(n, 1, "关闭模糊仅 1 变体");
  GK_CHECK(has(n, "xian", false), "自身不变");
  // 多类叠加
  n = KeyVariants("chang", FUZZY_CH_C | FUZZY_AN_ANG, v, pen, kMaxKeyVariants);
  GK_CHECK(has(n, "chang", false), "chang 自身");
  GK_CHECK(has(n, "cang", true), "chang → cang");
  GK_CHECK(has(n, "chan", true), "chang → chan");
}
