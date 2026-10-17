// v1.2 自动分词显示 + 手动分词锚定（§4.2.1 扩展）
#include <cstring>
#include <string>

#include "engine.h"
#include "glasskey/gk_api.h"
#include "gk_test.h"

using namespace gk;

static void Type(ImeEngine& e, const char* keys) {
  for (const char* p = keys; *p != '\0'; ++p) {
    e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  }
}

static bool CandidatesContain(ImeEngine& e, const char* text) {
  char buf[64];
  for (int i = 0; i < e.CandidateCount(); ++i) {
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK && std::strcmp(buf, text) == 0) return true;
  }
  return false;
}

static std::string DisplayOf(const char* input) { return SegmentedDisplay(input); }

void test_segment_display_basic() {
  std::string d;  // 先落变量再断言，避免临时对象 c_str() 悬垂
  d = DisplayOf("xian");
  GK_CHECK_STR_EQ(d.c_str(), "xian", "xian 单音节优先");
  d = DisplayOf("xianzai");
  GK_CHECK_STR_EQ(d.c_str(), "xian zai", "xianzai 两音节");
  d = DisplayOf("nihao");
  GK_CHECK_STR_EQ(d.c_str(), "ni hao", "nihao 两音节");
  d = DisplayOf("jintiandetianqi");
  GK_CHECK_STR_EQ(d.c_str(), "jin tian de tian qi", "整句音节链");
  d = DisplayOf("fangan");
  GK_CHECK_STR_EQ(d.c_str(), "fan gan", "歧义串确定性切分（fan|gan 与 fang|an 同代价取先）");
  d = DisplayOf("");
  GK_CHECK_STR_EQ(d.c_str(), "", "空串");
  // 不完整音节尾部（兜底段）
  d = DisplayOf("xianz");
  GK_CHECK_STR_EQ(d.c_str(), "xian z", "尾部残字符兜底");
  d = DisplayOf("z");
  GK_CHECK_STR_EQ(d.c_str(), "z", "单字符兜底");
  // 手动锚点紧贴前段、锚后无空格
  d = DisplayOf("xi'an");
  GK_CHECK_STR_EQ(d.c_str(), "xi'an", "锚点原样呈现");
  d = DisplayOf("xianz'ai");
  GK_CHECK_STR_EQ(d.c_str(), "xian z'ai", "锚点+自动边界混合");
}

void test_segment_display_t9() {
  // T9 数字串：数字模式音节切分（9426=xian, 9426924=xian zai? z=9,a=2,i=4）
  GK_CHECK_STR_EQ(DisplayOf("9426").c_str(), "9426", "T9 单音节");
}

void test_compute_segments_structure() {
  auto segs = ComputeDisplaySegments("xianzai");
  GK_CHECK_EQ(static_cast<int>(segs.size()), 2, "xianzai 两段");
  if (segs.size() == 2) {
    GK_CHECK(segs[0].start == 0 && segs[0].end == 4, "首段 xian 范围");
    GK_CHECK(segs[1].start == 4 && segs[1].end == 7, "次段 zai 范围");
    GK_CHECK(!segs[0].anchored && !segs[1].anchored, "无手动锚点");
    GK_CHECK(!segs[0].fallback && !segs[1].fallback, "均为合法音节段");
  }
  auto segs2 = ComputeDisplaySegments("xi'an");
  GK_CHECK_EQ(static_cast<int>(segs2.size()), 2, "xi'an 两段");
  if (segs2.size() == 2) {
    GK_CHECK(segs2[0].anchored, "前段被锚定");
    GK_CHECK(!segs2[1].anchored, "后段未锚定");
  }
  auto segs3 = ComputeDisplaySegments("xianz");
  GK_CHECK_EQ(static_cast<int>(segs3.size()), 2, "xianz 两段");
  if (segs3.size() == 2) {
    GK_CHECK(segs3[1].fallback, "残段为兜底段");
  }
}

void test_manual_separator_anchor() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ImeEngine e;
  GK_CHECK_EQ(e.Init(cfg), IME_OK, "引擎初始化");
  e.SetLayout(LAYOUT_PINYIN_26);

  // —— 核心场景：xian 想打"西安" ——
  Type(e, "xian");
  GK_CHECK_EQ(e.InsertManualSeparator(), 1, "xian 插入锚点成功");
  char buf[128];
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an", "xian → xi'an");
  e.GetSegmentedInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an", "分词显示串一致");
  GK_CHECK(CandidatesContain(e, "西安"), "xi'an 候选含西安");

  // 锚点随输入保持：继续打字不消失
  Type(e, "zai");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'anzai", "锚点持久（xi'anzai）");
  e.GetSegmentedInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an zai", "显示串 xi'an zai（锚后无空格）");

  // —— xianzai：段间边界锚定，连点逐层细分 ——
  e.Reset();
  Type(e, "xianzai");
  e.GetSegmentedInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xian zai", "自动分词显示");
  GK_CHECK_EQ(e.InsertManualSeparator(), 1, "段间锚定成功");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xian'zai", "xianzai → xian'zai");
  GK_CHECK_EQ(e.InsertManualSeparator(), 1, "段内细分成功");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an'zai", "xian'zai → xi'an'zai");
  GK_CHECK_EQ(e.InsertManualSeparator(), 0, "全部锚定无可插");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an'zai", "状态不变");

  // —— 残段：xianz 段间兜底边界 ——
  e.Reset();
  Type(e, "xianz");
  GK_CHECK_EQ(e.InsertManualSeparator(), 1, "残段插入成功");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xian'z", "xianz → xian'z");
  GK_CHECK_EQ(e.InsertManualSeparator(), 1, "再细分 xian 段");
  e.GetInput(buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xi'an'z", "xian'z → xi'an'z");

  // —— 边界情况 ——
  e.Reset();
  GK_CHECK_EQ(e.InsertManualSeparator(), 0, "空串无可插");
  Type(e, "a");
  GK_CHECK_EQ(e.InsertManualSeparator(), 0, "单字符无可插");

  // —— 退格：锚点即普通字符，逐字退掉 ——
  e.Reset();
  Type(e, "xian");
  e.InsertManualSeparator();
  for (int i = 0; i < 5; ++i) GK_CHECK_EQ(e.Backspace(), 1, "逐字退格");
  GK_CHECK_EQ(e.Backspace(), 0, "合成区已空");

  // —— 候选不受锚点显示影响：nihao 整句仍首选"你好" ——
  e.Reset();
  Type(e, "nihao");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "你好", "nihao 首选不变（回归）");
}

void test_segmented_c_api() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ime_h h = ime_engine_create(&cfg);
  GK_CHECK(h != nullptr, "C API 引擎创建");
  if (h == nullptr) return;

  char buf[128];
  for (const char* p = "xianzai"; *p != '\0'; ++p) {
    ime_process_key(h, static_cast<uint32_t>(*p), 0, 0);
  }
  int n = ime_get_segmented_input(h, buf, sizeof(buf));
  GK_CHECK_EQ(n, IME_OK, "C API 分词串返回 OK");
  GK_CHECK_STR_EQ(buf, "xian zai", "C API 自动分词");
  GK_CHECK_EQ(ime_insert_manual_separator(h), 1, "C API 手动分词");
  ime_get_input(h, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "xian'zai", "C API 锚定合成串");
  ime_engine_destroy(h);
}
