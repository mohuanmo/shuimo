#include <cstring>
#include <string>
#include <vector>

#include "engine.h"
#include "gk_test.h"

using namespace gk;

static std::string CandidatesJoin(ImeEngine& e) {
  std::string s;
  for (int i = 0; i < e.CandidateCount(); ++i) {
    char buf[64];
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK) {
      if (!s.empty()) s += "|";
      s += buf;
    }
  }
  return s;
}

static bool CandidatesContain(ImeEngine& e, const char* text) {
  char buf[64];
  for (int i = 0; i < e.CandidateCount(); ++i) {
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK && std::strcmp(buf, text) == 0) return true;
  }
  return false;
}

void test_decode_golden() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.latin_path = nullptr;
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;

  ImeEngine e;
  GK_CHECK_EQ(e.Init(cfg), IME_OK, "引擎初始化");

  // nihao → 你好 首选（§9.1 回归基线示意）
  e.SetLayout(LAYOUT_PINYIN_26);
  for (char c : std::string("nihao")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  char buf[128];
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "nihao", "合成串回显");
  GK_CHECK(e.CandidateCount() > 0, "有候选");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "你好", "nihao 首选 = 你好");
  GK_CHECK(CandidatesContain(e, "你"), "含单字候选");
  GK_CHECK(e.CandidateCount() >= 2, "候选列表含整句与单字选项");

  // 分隔符：xi'an → 西安（歧义切分回填）
  e.Reset();
  for (char c : std::string("xi'an")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  GK_CHECK(CandidatesContain(e, "西安"), "xi'an → 西安");

  // 整句：jintiandetianqi → 今天…天气（Beam 整句解码，§4.2.3）
  e.Reset();
  for (char c : std::string("jintiandetianqi")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  std::string joined = CandidatesJoin(e);
  GK_CHECK(joined.find("今天") != std::string::npos, "整句候选含今天: " + joined);
  GK_CHECK(joined.find("天气") != std::string::npos, "整句候选含天气: " + joined);

  // 词组提交后合成区回填（§8.1 ime_commit_candidate）
  e.Reset();
  for (char c : std::string("jintian")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  char text[64], py[64];
  int words = e.CommitCandidate(0, text, sizeof(text), py, sizeof(py));
  GK_CHECK_EQ(words, 1, "提交成功");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "", "整词提交后合成区清空");

  // 超长合成仅回显（§5.1：>64 字节停止解码，v1.1 合成区扩容 20→64）
  e.Reset();
  std::string long_input(90, 'a');
  for (char c : long_input) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_EQ(std::string(buf).size(), 64, "合成区封顶 64 字节");
}

void test_t9() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ImeEngine e;
  e.Init(cfg);
  e.SetLayout(LAYOUT_PINYIN_9);

  // 64426 → ni/hao → 你好（§4.2.2）
  for (char c : std::string("64426")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  GK_CHECK(e.CandidateCount() > 0, "T9 有候选");
  GK_CHECK(CandidatesContain(e, "你好"), "T9 64426 → 你好");

  // 可能的拼音串横滑条（§4.2.2）
  int n = e.T9PinyinCount();
  GK_CHECK(n > 0, "T9 可能拼音串非空");
  char buf[32];
  bool has_ni_or_hao = false;
  for (int i = 0; i < n; ++i) {
    e.GetT9Pinyin(i, buf, sizeof(buf));
    if (std::strcmp(buf, "ni") == 0 || std::strcmp(buf, "hao") == 0) has_ni_or_hao = true;
  }
  GK_CHECK(has_ni_or_hao, "可能拼音串含 ni/hao");
}

void test_fuzzy_e2e() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.fuzzy_mask = FUZZY_SH_S;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ImeEngine e;
  e.Init(cfg);
  e.SetLayout(LAYOUT_PINYIN_26);
  for (char c : std::string("sishi")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  GK_CHECK(CandidatesContain(e, "时事"), "sishi(模糊 sh/s) → 时事");
  GK_CHECK(CandidatesContain(e, "实施"), "sishi(模糊) → 实施");
}

void test_user_model() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ImeEngine e;
  e.Init(cfg);
  e.SetLayout(LAYOUT_PINYIN_26);

  // 用户模型 Boost 通道重排（§4.5）：导入"县"的高频记录 → "xian" 首选变县
  for (char c : std::string("xian")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  char buf[64];
  e.GetCandidate(0, buf, sizeof(buf));
  std::string before = buf;
  e.Reset();
  const char* blob = "县\0xian\0\x40\x00\x00\x00";  // 3+1+4+1+4 = 13 字节
  GK_CHECK_EQ(e.LoadUserWords(blob, 13), IME_OK, "装载用户词");
  for (char c : std::string("xian")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e.GetCandidate(0, buf, sizeof(buf));
  std::string after = buf;
  GK_CHECK(after == "县", "用户词 Boost 后首选 = 县 (before=" + before + " after=" + after + ")");

  // 学习接口 + 会话结束衰减（§4.5 freq *= 0.99）
  e.Learn("县", "xian", LEARN_SELECT);
  e.SessionEnd();  // 不崩溃即通过（衰减语义见 user_model 单测）

  // 关闭学习开关（S-03 敏感场景）
  ImeEngine e2;
  EngineConfig cfg2 = cfg;
  cfg2.enable_learning = 0;
  e2.Init(cfg2);
  e2.SetLayout(LAYOUT_PINYIN_26);
  e2.Learn("县", "xian", LEARN_SELECT);
  for (char c : std::string("xian")) e2.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e2.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK(std::string(buf) != "县", "learning=0 时不应用用户词");
}

void test_latin_engine() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.latin_path = "fixtures/gk_test_en.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.enable_learning = 1;
  ImeEngine e;
  e.Init(cfg);
  e.SetLayout(LAYOUT_LATIN);

  char buf[64];
  // 前缀补全（§4.4）
  for (char c : std::string("hell")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "hello", "hell → hello 补全首选");

  // 自动更正 + 自动撤销由上层（退格恢复原词），此处验证更正通道
  e.Reset();
  for (char c : std::string("teh")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "the", "teh → the 更正首选");

  e.Reset();
  for (char c : std::string("appl")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "apple", "appl → apple 补全首选");

  // 提交清空合成区
  e.Reset();
  for (char c : std::string("world")) e.ProcessKey(static_cast<uint32_t>(c), 0, 0);
  char text[64], py[64];
  GK_CHECK_EQ(e.CommitCandidate(0, text, sizeof(text), py, sizeof(py)), 1, "提交 world");
  GK_CHECK_STR_EQ(text, "world", "提交文本");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "", "提交后合成区空");
}
