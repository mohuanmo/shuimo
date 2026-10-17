// 极简测试框架（避免引入 gtest 外部依赖，保持核心零第三方）
#ifndef GK_TEST_H_
#define GK_TEST_H_

#include <cstdio>
#include <string>

inline int gk_failures = 0;
inline int gk_checks = 0;

#define GK_CHECK(cond, msg)                                              \
  do {                                                                   \
    ++gk_checks;                                                         \
    if (!(cond)) {                                                       \
      ++gk_failures;                                                     \
      const std::string gk_msg_(msg);                                    \
      std::printf("  [FAIL] %s:%d  %s\n", __FILE__, __LINE__,            \
                  gk_msg_.c_str());                                      \
    }                                                                    \
  } while (0)

#define GK_CHECK_EQ(a, b, msg)                                                     \
  do {                                                                             \
    ++gk_checks;                                                                   \
    if (!((a) == (b))) {                                                           \
      ++gk_failures;                                                               \
      std::printf("  [FAIL] %s:%d  %s (actual=%lld expected=%lld)\n", __FILE__,    \
                  __LINE__, (msg), static_cast<long long>(a),                      \
                  static_cast<long long>(b));                                      \
    }                                                                              \
  } while (0)

#define GK_CHECK_STR_EQ(a, b, msg)                                            \
  do {                                                                        \
    ++gk_checks;                                                              \
    const char* gk_a_ = (a) != nullptr ? (a) : "(null)";                      \
    const char* gk_b_ = (b) != nullptr ? (b) : "(null)";                      \
    if (std::string(gk_a_) != std::string(gk_b_)) {                           \
      ++gk_failures;                                                          \
      std::printf("  [FAIL] %s:%d  %s (actual=\"%s\" expected=\"%s\")\n",     \
                  __FILE__, __LINE__, (msg), gk_a_, gk_b_);                   \
    }                                                                         \
  } while (0)

#define GK_RUN(fn)                                    \
  do {                                                \
    std::printf("[RUN ] %s\n", #fn);                  \
    fn();                                             \
  } while (0)

inline int gk_summary() {
  std::printf("\n==== %d checks, %d failures ====\n", gk_checks, gk_failures);
  return gk_failures == 0 ? 0 : 1;
}

#endif  // GK_TEST_H_
