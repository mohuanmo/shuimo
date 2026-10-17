// 拼音音节表（声母×韵母合法组合，§4.2.1）
// 列表按字典序排列（tests 断言有序性）；最长 6 字母。
#ifndef GLASSKEY_CORE_SYLLABLE_TABLE_H_
#define GLASSKEY_CORE_SYLLABLE_TABLE_H_

#include <cstdint>

namespace gk {

int SyllableCount();
/// 第 i 个音节（字典序）。
const char* SyllableAt(int i);
/// 精确查找音节，未找到返回 -1。
int FindSyllable(const char* s, int len);
/// T9 数字串（2abc/3def/4ghi/5jkl/6mno/7pqrs/8tuv/9wxyz），内部惰性构建。
const char* SyllableDigits(int i);
/// 音节最大长度。
constexpr int kMaxSyllableLen = 6;

/// 首字母音节区间（v1.3 简拼通道）：音节表按字典序排列，故以 c 开头的音节
/// 构成连续区间 [out_begin, out_end)。无该首字母音节时返回 false。
bool FirstLetterSpan(char c, int* out_begin, int* out_end);

}  // namespace gk

#endif  // GLASSKEY_CORE_SYLLABLE_TABLE_H_
