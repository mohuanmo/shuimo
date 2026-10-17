#include "syllable_table.h"

#include <cstring>

namespace gk {

namespace {
// 合法拼音音节全表（按字典序）
const char* const kSyllables[] = {
    "a", "ai", "an", "ang", "ao",
    "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao",
    "bie", "bin", "bing", "bo", "bu",
    "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan",
    "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua",
    "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu",
    "cuan", "cui", "cun", "cuo",
    "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia",
    "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
    "e", "ei", "en", "eng", "er",
    "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
    "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou",
    "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
    "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou",
    "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
    "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu",
    "ju", "juan", "jue", "jun",
    "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou",
    "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
    "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian",
    "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan",
    "lun", "luo", "lv", "lve",
    "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian",
    "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
    "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian",
    "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan",
    "nuo", "nv", "nve",
    "o", "ou",
    "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao",
    "pie", "pin", "ping", "po", "pou", "pu",
    "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
    "qu", "quan", "que", "qun",
    "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua",
    "ruan", "rui", "run", "ruo",
    "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan",
    "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua",
    "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su",
    "suan", "sui", "sun", "suo",
    "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie",
    "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
    "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
    "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu",
    "xu", "xuan", "xue", "xun",
    "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you",
    "yu", "yuan", "yue", "yun",
    "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai",
    "zhan", "zhang", "zhao", "zhe", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu",
    "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou",
    "zu", "zuan", "zui", "zun", "zuo",
};
constexpr int kCount = static_cast<int>(sizeof(kSyllables) / sizeof(kSyllables[0]));

// T9 键位：2abc 3def 4ghi 5jkl 6mno 7pqrs 8tuv 9wxyz
inline char DigitOf(char c) {
  switch (c) {
    case 'a': case 'b': case 'c': return '2';
    case 'd': case 'e': case 'f': return '3';
    case 'g': case 'h': case 'i': return '4';
    case 'j': case 'k': case 'l': return '5';
    case 'm': case 'n': case 'o': return '6';
    case 'p': case 'q': case 'r': case 's': return '7';
    case 't': case 'u': case 'v': return '8';
    case 'w': case 'x': case 'y': case 'z': return '9';
    default: return 0;
  }
}

struct DigitCache {
  char digits[kCount][8];
  DigitCache() {
    for (int i = 0; i < kCount; ++i) {
      int j = 0;
      for (const char* p = kSyllables[i]; *p != '\0' && j < 7; ++p) digits[i][j++] = DigitOf(*p);
      digits[i][j] = '\0';
    }
  }
};
const DigitCache& Digits() {
  static const DigitCache cache;
  return cache;
}
}  // namespace

int SyllableCount() { return kCount; }

const char* SyllableAt(int i) {
  if (i < 0 || i >= kCount) return nullptr;
  return kSyllables[i];
}

int FindSyllable(const char* s, int len) {
  if (s == nullptr || len <= 0 || len > kMaxSyllableLen) return -1;
  int lo = 0, hi = kCount - 1;
  while (lo <= hi) {
    int mid = (lo + hi) / 2;
    int cmp = std::strncmp(kSyllables[mid], s, static_cast<size_t>(len));
    if (cmp == 0 && kSyllables[mid][len] == '\0') return mid;
    // cmp > 0：音节字典序大于目标；cmp == 0 且音节更长（目标是其前缀）：同样大于目标
    if (cmp < 0) lo = mid + 1;
    else hi = mid - 1;
  }
  return -1;
}

const char* SyllableDigits(int i) {
  if (i < 0 || i >= kCount) return nullptr;
  Digits();  // 确保初始化
  return Digits().digits[i];
}

bool FirstLetterSpan(char c, int* out_begin, int* out_end) {
  if (out_begin == nullptr || out_end == nullptr) return false;
  if (c < 'a' || c > 'z') return false;
  // 音节表按字典序 → 二分找第一个 >= c 的位置（lower bound）
  int lo = 0, hi = kCount;
  while (lo < hi) {
    int mid = lo + (hi - lo) / 2;
    if (kSyllables[mid][0] < c) lo = mid + 1;
    else hi = mid;
  }
  int begin = lo;
  int end = lo;
  while (end < kCount && kSyllables[end][0] == c) ++end;
  if (begin >= end) return false;
  *out_begin = begin;
  *out_end = end;
  return true;
}

}  // namespace gk
