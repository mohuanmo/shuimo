#include <cstring>
#include <string>

#include "gk_test.h"
#include "pinyin/syllable_table.h"

using namespace gk;

void test_syllable_table() {
  GK_CHECK(SyllableCount() > 400, "音节表应覆盖 400+ 合法音节");
  // 有序性（§10.4 单测）
  for (int i = 1; i < SyllableCount(); ++i) {
    if (std::strcmp(SyllableAt(i - 1), SyllableAt(i)) >= 0) {
      GK_CHECK(false, "音节表必须按字典序排列");
      break;
    }
  }
  GK_CHECK_STR_EQ(SyllableAt(0), "a", "首音节");
  // 精确查找
  GK_CHECK_EQ(FindSyllable("xian", 4) >= 0, 1, "xian 应为合法音节");
  GK_CHECK_EQ(FindSyllable("xi", 2) >= 0, 1, "xi 应为合法音节");
  GK_CHECK_EQ(FindSyllable("an", 2) >= 0, 1, "an 应为合法音节");
  GK_CHECK_EQ(FindSyllable("qzx", 3), -1, "非法音节查不到");
  GK_CHECK_EQ(FindSyllable("xz", 2), -1, "非法组合查不到");
  GK_CHECK_EQ(FindSyllable("xian", 4) >= 0, 1, "xian 应为合法音节");
  // T9 数字模式（2abc/3def/4ghi/5jkl/6mno/7pqrs/8tuv/9wxyz）
  int ni = FindSyllable("ni", 2);
  int hao = FindSyllable("hao", 3);
  GK_CHECK_STR_EQ(SyllableDigits(ni), "64", "ni → 64");
  GK_CHECK_STR_EQ(SyllableDigits(hao), "426", "hao → 426");
  int zhuang = FindSyllable("zhuang", 6);
  GK_CHECK_STR_EQ(SyllableDigits(zhuang), "948264", "zhuang → 948264");
}
