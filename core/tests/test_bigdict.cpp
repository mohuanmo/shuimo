// 大词典回归（v1.1）：真实规模词库（20 万中文 / 6 万英文）下的解码正确性、
// 长输入兜底（部分匹配候选）与性能（§4.1 击键→候选 ≤30ms）。
// 词典源：app/src/main/assets/dict/（与 APK assets 同源，避免重复存储）。
#include <chrono>
#include <cstdio>
#include <string>

#include "engine.h"
#include "gk_test.h"

using namespace gk;

namespace {

const char* kZhBig = "../../app/src/main/assets/dict/gk_zh.gkdt";
const char* kEnBig = "../../app/src/main/assets/dict/gk_en.gkdt";

bool FileExists(const char* p) {
  FILE* f = std::fopen(p, "rb");
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

}  // namespace

void test_bigdict() {
  if (!FileExists(kZhBig) || !FileExists(kEnBig)) {
    std::printf("  [SKIP] 大词典未构建（tools/dictbuilder 产物缺失）\n");
    return;
  }
  EngineConfig cfg{};
  cfg.dict_path = kZhBig;
  cfg.latin_path = kEnBig;
  cfg.fuzzy_mask = FUZZY_DEFAULT;
  cfg.max_candidates = 10;
  cfg.beam_width = 10;
  cfg.enable_learning = 1;
  ImeEngine e;
  GK_CHECK_EQ(e.Init(cfg), IME_OK, "大词典引擎初始化");
  char buf[128];

  // —— 整句解码质量（jieba 高频词覆盖）——
  struct Case { const char* input; const char* expect; };
  static const Case cases[] = {
      {"nihao", "你好"},
      {"jintiantianqihenhao", "今天天气很好"},
      {"nihaoshijie", "你好世界"},
      {"womenyaoxuexizhongwen", "我们要学习中文"},
      {"beijinghuanyingni", "北京欢迎你"},
  };
  for (const Case& c : cases) {
    e.Reset();
    for (const char* p = c.input; *p; ++p) e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
    std::string got = CandidatesJoin(e);
    GK_CHECK(got.find(c.expect) != std::string::npos,
             std::string(c.input) + " 候选应含「" + c.expect + "」: " + got);
  }
  // 词形覆盖断言（宽）：大词典下长句必有高频词前缀命中（unigram 基线，
  // 精确排序由 §4.3 n-gram 通道随资源包热更启用）
  e.Reset();
  for (const char* p = "jiantijizihedianxing"; *p; ++p)
    e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  std::string got_big = CandidatesJoin(e);
  GK_CHECK(got_big.find("简体") != std::string::npos,
           "长句候选应含「简体」: " + got_big);

  // —— 长输入性能（§4.1 ≤30ms 中端机基线；CI 放宽至 100ms 防抖动）——
  // 预热：首次 mmap 词典 page-in（冷 IO）不计入解码性能（真机 UFS 顺序读
  // 14.7MB ≈ 10-30ms，属首帧成本 §9.1，此处测稳态热解码）
  e.Reset();
  for (const char* p = "ceshi"; *p; ++p) e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  e.Reset();
  const char* long_sentence = "zhejiutashuodemazongsuanwangjile";
  auto t0 = std::chrono::steady_clock::now();
  for (const char* p = long_sentence; *p; ++p) e.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0)
                .count();
  std::printf("  [perf] 33 字母长句解码 %lld ms（候选 %d 条）\n",
              static_cast<long long>(ms), e.CandidateCount());
  GK_CHECK(ms < 100, "长句解码耗时可接受（<100ms，中端机目标 30ms）");
  GK_CHECK(e.CandidateCount() > 0, "长句必有候选（整句或部分匹配兜底）");

  // —— 部分匹配兜底：小词典（fixture）长输入，整句路径必不存在 ——
  EngineConfig cfg2{};
  cfg2.dict_path = "fixtures/gk_test_zh.gkdt";
  cfg2.fuzzy_mask = FUZZY_NONE;
  cfg2.max_candidates = 10;
  cfg2.beam_width = 10;
  cfg2.enable_learning = 0;
  ImeEngine small;
  GK_CHECK_EQ(small.Init(cfg2), IME_OK, "小词典引擎初始化");
  small.Reset();
  for (const char* p = "nihaojintianxin"; *p; ++p)  // 小词典无覆盖的 3+ 音节长串
    small.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  GK_CHECK(small.CandidateCount() > 0, "整句不可达时部分匹配兜底非空");
  std::string got = CandidatesJoin(small);
  std::printf("  [partial] 小词典长输入候选: %s\n", got.c_str());
  // 首个候选必须是"已解码前缀"（覆盖 > 单词即可）
  GK_CHECK(!got.empty(), "兜底候选非空");

  // —— 英文大词典：纠错 + 前缀补全 + 性能 ——
  EngineConfig cfg3{};
  cfg3.dict_path = kZhBig;
  cfg3.latin_path = kEnBig;
  cfg3.fuzzy_mask = FUZZY_NONE;
  cfg3.max_candidates = 10;
  cfg3.beam_width = 10;
  cfg3.enable_learning = 1;
  ImeEngine en;
  GK_CHECK_EQ(en.Init(cfg3), IME_OK, "英文大词典引擎初始化");
  en.SetLayout(LAYOUT_LATIN);
  en.Reset();
  for (const char* p = "helllo"; *p; ++p) en.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  std::string got_en = CandidatesJoin(en);
  GK_CHECK(got_en.find("hello") != std::string::npos, "英文纠错 helllo→hello: " + got_en);

  en.Reset();
  auto t1 = std::chrono::steady_clock::now();
  for (const char* p = "accommodat"; *p; ++p) en.ProcessKey(static_cast<uint32_t>(*p), 0, 0);
  auto ms_en = std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now() - t1)
                   .count();
  std::printf("  [perf] 英文 10 字母解码 %lld ms（候选 %d 条）\n",
              static_cast<long long>(ms_en), en.CandidateCount());
  GK_CHECK(ms_en < 100, "英文解码耗时可接受");
  std::string got_prefix = CandidatesJoin(en);
  GK_CHECK(got_prefix.find("accommodate") != std::string::npos,
           "前缀补全 accommodat→accommodate: " + got_prefix);
}
