// v1.3 兜底与上下文通道回归：字词联想（首字母简拼）、字组合兜底、
// 尾段简拼（非法拼音自动切分）、上文结合候选重排（§4.2 增 / §4.5 增）。
// 小词典 fixture 断言确定性结果；大词典（存在时）验证真实规模行为。
#include <cstring>
#include <string>
#include <vector>

#include "engine.h"
#include "gk_test.h"

using namespace gk;

namespace {

const char* kZhBig = "../../app/src/main/assets/dict/gk_zh.gkdt";

bool BigDictExists() {
  FILE* f = std::fopen(kZhBig, "rb");
  if (f == nullptr) return false;
  std::fclose(f);
  return true;
}

std::string CandidatesJoin(ImeEngine& e) {
  std::string s;
  for (int i = 0; i < e.CandidateCount(); ++i) {
    char buf[256];
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK) s += buf, s += "|";
  }
  return s;
}

bool CandidatesContain(ImeEngine& e, const char* text) {
  char buf[256];
  for (int i = 0; i < e.CandidateCount(); ++i) {
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK && std::strcmp(buf, text) == 0) return true;
  }
  return false;
}

int CandidatesIndexOf(ImeEngine& e, const char* text) {
  char buf[256];
  for (int i = 0; i < e.CandidateCount(); ++i) {
    if (e.GetCandidate(i, buf, sizeof(buf)) == IME_OK && std::strcmp(buf, text) == 0) return i;
  }
  return -1;
}

void Type(ImeEngine& e, const char* s) {
  for (const char* p = s; *p != '\0'; ++p) e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
}

}  // namespace

void test_fallback_fixture() {
  EngineConfig cfg{};
  cfg.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg.latin_path = nullptr;
  cfg.fuzzy_mask = FUZZY_NONE;
  cfg.max_candidates = 10;
  cfg.beam_width = 12;
  cfg.enable_learning = 1;
  ImeEngine e;
  GK_CHECK_EQ(e.Init(cfg), IME_OK, "兜底测试引擎初始化");
  e.SetLayout(LAYOUT_PINYIN_26);

  // —— 通道 A：单字母简拼 → 高频字候选池（用户诉求：输入 d 不再候选空白）——
  e.Reset();
  Type(e, "d");
  GK_CHECK(e.CandidateCount() > 0, "单字母 d 有候选（兜底生效）");
  char buf[64];
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "的", "d 首选 = 的（fixture 词频最高）");
  GK_CHECK_STR_EQ(e.GetCandidatePinyin(0, buf, sizeof(buf)) == IME_OK ? buf : "", "de",
                  "简拼候选拼音回填完整音节");

  // —— 通道 A：双字母简拼（sj = shi jie 首字母）——
  e.Reset();
  Type(e, "sj");
  GK_CHECK(e.CandidateCount() > 0, "简拼 sj 有候选");
  GK_CHECK(CandidatesContain(e, "世界"), "sj → 世界（搜狗式首字母简拼）");

  // —— 字组合兜底：dd 无 DAT 词时用最热音节×最高频单字拼组合 ——
  e.Reset();
  Type(e, "dd");
  GK_CHECK(CandidatesContain(e, "的的"), "dd → 的的（字组合兜底）");
  GK_CHECK(e.CandidateCount() > 0, "dd 候选非空");

  // —— 三字母简拼不崩、预算受控 ——
  e.Reset();
  Type(e, "ddd");
  GK_CHECK(e.CandidateCount() >= 0, "ddd 不崩（无词时允许空候选）");

  // —— 回归保护：正常全拼路径不受新通道影响 ——
  e.Reset();
  Type(e, "nihao");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "你好", "全拼 nihao 首选 = 你好（回归）");

  // —— 回归保护：T9 数字串不触发简拼通道 ——
  e.Reset();
  e.SetLayout(LAYOUT_PINYIN_9);
  Type(e, "64426");
  GK_CHECK(CandidatesContain(e, "你好"), "T9 64426 → 你好（数字串不入简拼）");
  e.SetLayout(LAYOUT_PINYIN_26);
}

void test_fallback_bigdict() {
  if (!BigDictExists()) {
    std::printf("  [SKIP] 大词典未构建（tools/dictbuilder 产物缺失）\n");
    return;
  }
  EngineConfig cfg{};
  cfg.dict_path = kZhBig;
  cfg.latin_path = nullptr;
  cfg.fuzzy_mask = FUZZY_DEFAULT;
  cfg.max_candidates = 10;
  cfg.beam_width = 10;
  cfg.enable_learning = 1;
  ImeEngine e;
  GK_CHECK_EQ(e.Init(cfg), IME_OK, "大词典引擎初始化");
  e.SetLayout(LAYOUT_PINYIN_26);
  char buf[256];

  // —— 单字母简拼：d 首选 = 的（词频 46277 居首）——
  Type(e, "d");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "的", "大词典 d 首选 = 的");
  GK_CHECK(e.CandidateCount() >= 3, "d 候选池丰富（的/大/到…）");

  // —— 双字母简拼：dd → 高频简拼词 + 字组合，候选池不空白 ——
  // （用户举例"地大/的的"为场景说明；断言锚定行为：词候选来自 DAT 真词，
  //   字组合来自最热音节单字拼接，均按词频/热度排序，不硬编码具体词形）
  e.Reset();
  Type(e, "dd");
  GK_CHECK(e.CandidateCount() >= 4, "dd 候选池丰富（简拼词 + 字组合）");
  char first_dd[64];
  e.GetCandidate(0, first_dd, sizeof(first_dd));
  GK_CHECK(std::strlen(first_dd) >= 6, "dd 首选为双字简拼词（得到/达到类）");
  GK_CHECK(CandidatesContain(e, "得到") || CandidatesContain(e, "达到") ||
               CandidatesContain(e, "大道"),
           "dd 含高频双音节简拼词");

  // —— 尾段简拼：xianz → 现在（前缀合法 + 尾段 z 直查真词）——
  e.Reset();
  Type(e, "xianz");
  GK_CHECK(e.CandidateCount() > 0, "xianz 候选非空（非法拼音自动切分）");
  int idx_now = CandidatesIndexOf(e, "现在");
  GK_CHECK(idx_now >= 0, "xianz 含 现在（尾段简拼直查）");
  if (idx_now >= 0) {
    GK_CHECK_EQ(idx_now, 0, "现在 排首位（整词意图强于前缀单字）");
    e.GetCandidatePinyin(idx_now, buf, sizeof(buf));
    GK_CHECK(std::strstr(buf, "zai") != nullptr, "现在 拼音含 zai");
  }

  // —— 回归：全拼 xianzai 正常整句 ——
  e.Reset();
  Type(e, "xianzai");
  GK_CHECK(CandidatesContain(e, "现在"), "全拼 xianzai → 现在（回归）");

  // —— 上下文重排：上文"的"后输入 d，首选让位 ——
  e.Reset();
  e.SetContext("的", 3);
  Type(e, "d");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK(std::strcmp(buf, "的") != 0, "上文'的'+d 首选非'的'（结合前文排列）");
  GK_CHECK(e.CandidateCount() >= 2, "重排后仍有完整候选池");
  int idx_de = CandidatesIndexOf(e, "的");
  GK_CHECK(idx_de > 0, "'的' 让位但仍在候选中");

  // —— 上下文清空：SetContext(nullptr) 恢复默认首选 ——
  // （叠词豁免为 ApplyContextRerank 内防御性分支：候选 AA 叠词形态时不让位，
  //   fixture 层由通道 A 字组合覆盖，此处不再重复硬编码断言）
  e.Reset();
  e.SetContext(nullptr, 0);
  Type(e, "d");
  e.GetCandidate(0, buf, sizeof(buf));
  GK_CHECK_STR_EQ(buf, "的", "清空上文后 d 首选恢复 = 的");
}
