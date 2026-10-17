#include <cstring>
#include <string>

#include "gk_test.h"
#include "glasskey/gk_api.h"

using namespace gk;

/// C ABI 全链路（§8.1 契约）：创建→击键→候选→选词上屏→学习→销毁
void test_c_api() {
  // 错误路径：词典缺失
  EngineConfig bad{};
  bad.dict_path = "/tmp/不存在的词典.gkdt";
  GK_CHECK(ime_engine_create(&bad) == nullptr, "词典缺失返回 nullptr");
  GK_CHECK_STR_EQ(ime_version(), gk::kVersion, "版本号");

  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.latin_path = "fixtures/gk_test_en.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 10;
  cfg.enable_learning = 1;
  ime_h h = ime_engine_create(&cfg);
  GK_CHECK(h != nullptr, "C ABI 创建引擎");

  // 26 键 → nihao → 首选 你好 → 提交
  ime_set_layout(h, LAYOUT_PINYIN_26);
  for (char c : std::string("nihao")) ime_process_key(h, static_cast<uint32_t>(c), 0, 0);
  GK_CHECK(ime_candidate_count(h) > 0, "有候选");
  char buf[64];
  ime_get_candidate(h, 0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "你好", "C ABI nihao 首选");

  char text[64], py[64];
  int committed = ime_commit_candidate(h, 0, text, sizeof(text), py, sizeof(py));
  GK_CHECK_EQ(committed, 1, "提交返回词数");
  GK_CHECK_STR_EQ(text, "你好", "上屏文本");
  GK_CHECK_STR_EQ(py, "ni hao", "上屏拼音");

  // 退格边界：空合成区返回 0
  GK_CHECK_EQ(ime_backspace(h), 0, "空合成区退格返回 0");
  ime_process_key(h, 'a', 0, 0);
  GK_CHECK_EQ(ime_backspace(h), 1, "非空退格返回 1");

  // T9 拼音串
  ime_reset(h);
  ime_set_layout(h, LAYOUT_PINYIN_9);
  for (char c : std::string("64426")) ime_process_key(h, static_cast<uint32_t>(c), 0, 0);
  GK_CHECK(ime_t9_pinyin_count(h) > 0, "T9 拼音串数量");
  ime_get_t9_pinyin(h, 0, buf, sizeof(buf));
  GK_CHECK(std::strlen(buf) > 0, "T9 拼音串内容");

  // 用户词装载 blob（word\0pinyin\0freq u32 LE）
  ime_reset(h);
  const char* blob = "西安\0xi an\0\x10\x27\x00\x00";
  GK_CHECK_EQ(ime_load_user_words(h, blob, 17), IME_OK, "装载用户词 blob");

  ime_reset(h);
  ime_set_fuzzy(h, FUZZY_SH_S);
  ime_set_layout(h, LAYOUT_PINYIN_26);
  for (char c : std::string("sishi")) ime_process_key(h, static_cast<uint32_t>(c), 0, 0);
  ime_get_candidate(h, 0, buf, sizeof(buf));
  GK_CHECK(std::string(buf) == "时事" || std::string(buf) == "实施", "C ABI 模糊音候选");

  ime_session_end(h);
  GK_CHECK_EQ(ime_last_error(h), IME_OK, "无错误残留");
  ime_engine_destroy(h);

  // 坏词典（乱码文件）→ E_DICT_VERSION
  FILE* f = std::fopen("/tmp/gk_garbage.gkdt", "wb");
  std::fwrite("GARBAGE_GARBAGE_GARBAGE", 1, 23, f);
  std::fclose(f);
  EngineConfig g{};
  g.dict_path = "/tmp/gk_garbage.gkdt";
  ime_h h2 = ime_engine_create(&g);
  GK_CHECK(h2 == nullptr, "乱码词典拒绝加载");
}
