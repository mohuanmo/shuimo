// GlassKey Core - 公共类型、常量与错误码
// 对应《输入法App工程设计方案》§8.1 C ABI 契约
// 平台无关：本头文件及其实现不得依赖任何 Android API
#pragma once

#include <cstddef>
#include <cstdint>

namespace gk {

/// 统一错误码（§8.1 错误码枚举）
enum ErrorCode : int32_t {
  IME_OK = 0,
  E_INVALID = -1,        ///< 非法参数
  E_DICT_MISSING = -2,   ///< 词典文件缺失
  E_DICT_CRC = -3,       ///< 词典段 CRC 校验失败
  E_DICT_VERSION = -4,   ///< 词典版本不兼容
  E_OOM = -5,            ///< 内存不足
  E_NOT_INIT = -6,       ///< 引擎未初始化
  E_BUFFER_SMALL = -7,   ///< 输出缓冲区不足
};

/// 输入模式（§8.1 ime_set_layout）
enum Layout : int32_t {
  LAYOUT_PINYIN_26 = 0,  ///< 26 键拼音
  LAYOUT_PINYIN_9 = 1,   ///< 9 键拼音（T9）
  LAYOUT_LATIN = 2,      ///< 英文键盘
};

/// 学习来源（对应 §7.2 user_word.source）
enum LearnSource : int32_t {
  LEARN_SELECT = 0,   ///< 选词学习
  LEARN_COINED = 1,   ///< 自造词
  LEARN_IMPORT = 2,   ///< 导入
  LEARN_SENTENCE = 3, ///< 整句固化
};

/// 模糊音位掩码（用户可自选组合，§4.2.1）
enum FuzzyMask : uint32_t {
  FUZZY_NONE = 0,
  FUZZY_ZH_Z = 1u << 0,  ///< zh/z
  FUZZY_CH_C = 1u << 1,  ///< ch/c
  FUZZY_SH_S = 1u << 2,  ///< sh/s
  FUZZY_N_L = 1u << 3,   ///< n/l
  FUZZY_F_H = 1u << 4,   ///< f/h
  FUZZY_AN_ANG = 1u << 5, ///< an/ang
  FUZZY_EN_ENG = 1u << 6, ///< en/eng
  FUZZY_IN_ING = 1u << 7, ///< in/ing
  FUZZY_DEFAULT = FUZZY_ZH_Z | FUZZY_CH_C | FUZZY_SH_S | FUZZY_AN_ANG | FUZZY_EN_ENG | FUZZY_IN_ING,
};

/// 候选词
struct Candidate {
  const char* text;    ///< UTF-8 词文本（指向引擎内部缓冲，选词后失效）
  const char* pinyin;  ///< 该词对应拼音（空格分隔音节）
  float score;         ///< 综合得分（调试/降序排序用）
};

/// 引擎创建配置（§8.1 ime_engine_create cfg）
struct EngineConfig {
  const char* dict_path;      ///< 中文词典 GKDT 路径（mmap）
  const char* latin_path;     ///< 英文词典 GKDT 路径（可空）
  uint32_t fuzzy_mask;        ///< 模糊音组合
  uint32_t max_candidates;    ///< 候选上限（默认 10）
  uint32_t beam_width;        ///< Beam 宽度（8–16，§4.2.3）
  uint8_t enable_learning;    ///< 用户模型学习开关（密码场景关闭，S-03）
};

/// 布局常量字符串（JNI 层透传展示用）
inline constexpr char kVersion[] = "0.1.0-m1";

/// 合成区最大长度（字节）：超长停止解码仅回显（§5.1 异常与边界）。
/// v1.1：20 → 64。20 字节仅容纳 4~5 个汉字的拼音，实测长句输入会频繁
/// 触顶导致"后半句没有候选"；64 字节（约 12~16 字整句）覆盖主流整句输入，
/// 且解码开销仍受 Beam 宽度与 Walk 预算约束（§4.1 性能红线）。
inline constexpr int kMaxCompositionBytes = 64;

}  // namespace gk
