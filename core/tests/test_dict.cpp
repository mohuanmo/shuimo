#include <cstdio>
#include <cstring>
#include <string>

#include "dict/dict_store.h"
#include "gk_test.h"

using namespace gk;

static const char* kZhDict = "fixtures/gk_test_zh.gkdt";
static const char* kEnDict = "fixtures/gk_test_en.gkdt";

void test_dict_fixture() {
  DictStore dict;
  int err = IME_OK;
  GK_CHECK(dict.Open(kZhDict, &err), "打开中文测试词典");
  GK_CHECK_EQ(err, IME_OK, "无错误");
  GK_CHECK(dict.WordCount() > 20, "词条数合理");

  // 精确查询 + 同音词集合（§4.3 词 id 集合）
  uint32_t ids[16];
  int n = dict.QueryWords("ni hao", ids, 16);
  GK_CHECK_EQ(n, 1, "ni hao → 1 词");
  GK_CHECK_STR_EQ(dict.WordText(ids[0]), "你好", "ni hao → 你好");

  n = dict.QueryWords("shi shi", ids, 16);
  GK_CHECK(n >= 4, "shi shi → 同音词集合 ≥4（时事/实施/事实/逝世）");
  bool has_shishi_word = false;
  for (int i = 0; i < n; ++i) {
    const char* w = dict.WordText(ids[i]);
    if (std::strcmp(w, "时事") == 0 || std::strcmp(w, "实施") == 0) has_shishi_word = true;
    GK_CHECK(dict.WordFreq(ids[i]) > 0, "词频 > 0");
    GK_CHECK(dict.WordPinyin(ids[i]) != nullptr, "拼音非空");
  }
  GK_CHECK(has_shishi_word, "同音词集合含目标词");

  // 前缀补全通道（英文词典）
  DictStore en;
  GK_CHECK(en.Open(kEnDict, &err), "打开英文测试词典");
  n = en.QueryPrefix("hel", ids, 16);
  GK_CHECK(n >= 1, "hel 前缀 → hello");
  bool has_hello = false;
  for (int i = 0; i < n; ++i) {
    if (std::strcmp(en.WordText(ids[i]), "hello") == 0) has_hello = true;
  }
  GK_CHECK(has_hello, "补全含 hello");

  // 反例
  GK_CHECK_EQ(dict.QueryWords("zzzz", ids, 16), 0, "无效拼音查不到");
}

void test_dict_crc() {
  // 复制词典并篡改 WORDS 段内容 → 惰性 CRC 校验失败（§7.3）
  FILE* f = std::fopen(kZhDict, "rb");
  GK_CHECK(f != nullptr, "读测试词典");
  static uint8_t buf[1 << 20];
  size_t len = std::fread(buf, 1, sizeof(buf), f);
  std::fclose(f);
  GK_CHECK(len > 100, "词典内容读入");

  // 找到 WORDS 段头并翻转段内一个字节
  uint16_t count = static_cast<uint16_t>(buf[6] | (buf[7] << 8));
  bool flipped = false;
  for (uint16_t i = 0; i < count && !flipped; ++i) {
    const uint8_t* h = buf + 8 + 16u * i;
    uint16_t type = static_cast<uint16_t>(h[0] | (h[1] << 8));
    if (type != DictStore::SEC_WORDS) continue;
    uint32_t off = h[4] | (h[5] << 8) | (h[6] << 16) | (static_cast<uint32_t>(h[7]) << 24);
    buf[off] ^= 0xFF;
    flipped = true;
  }
  GK_CHECK(flipped, "定位并篡改 WORDS 段");

  const char* tmp = "/tmp/gk_crc_bad.gkdt";
  f = std::fopen(tmp, "wb");
  std::fwrite(buf, 1, len, f);
  std::fclose(f);

  DictStore bad;
  int err = IME_OK;
  GK_CHECK(bad.Open(tmp, &err), "头部解析仍成功（惰性校验）");
  GK_CHECK(!bad.EnsureSection(DictStore::SEC_WORDS), "WORDS 段 CRC 校验失败");
  GK_CHECK_EQ(bad.last_error(), E_DICT_CRC, "错误码 E_DICT_CRC");
}
