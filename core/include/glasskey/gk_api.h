// GlassKey Core - 稳定 C ABI（§8.1）
// 契约要求：
//  - 所有句柄操作非线程安全（IME 会话单线程调用）；
//  - 结构体按版本字段前向兼容；
//  - 禁止 C++ 异常跨越本边界。
#ifndef GLASSKEY_GK_API_H_
#define GLASSKEY_GK_API_H_

#include "glasskey/gk_types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void* ime_h;

/// 创建引擎：加载词典（mmap）、初始化解码器。返回 NULL 表示失败。
ime_h ime_engine_create(const gk::EngineConfig* cfg);

/// 销毁引擎，释放全部资源（含 mmap 映射）。
void ime_engine_destroy(ime_h h);

/// 切换输入模式（26 键 / 9 键 / 英文）。切换时清空合成区。
int ime_set_layout(ime_h h, int32_t layout);

/// 设置模糊音组合（位掩码，见 gk::FuzzyMask）。
int ime_set_fuzzy(ime_h h, uint32_t mask);

/// 学习开关（密码场景等敏感场景关闭，S-03）。
int ime_set_learning(ime_h h, int enabled);

/// 处理一次击键：code 为 Unicode 字符（字母 a-z、数字 2-9 或分隔符 '\''）。
/// action：0=按下，1=抬起（仅按下处理）。返回 IME_OK 或错误码。
int ime_process_key(ime_h h, uint32_t code, int32_t action, int32_t meta);

/// 退格：删除合成区最后一个字符；合成区为空返回 0（由上层编辑正文）。
int ime_backspace(ime_h h);

/// 清空合成区（移动光标/焦点切换时调用）。
int ime_reset(ime_h h);

/// 取当前合成串（拼音字母串或 T9 数字串）。返回所需字节数（不含 \0）。
int ime_get_input(ime_h h, char* buf, int32_t cap);

/// 取自动分词显示串（v1.2）：音节边界以空格呈现、手动锚点紧贴前段，
/// 每击键重算。拉丁模式返回原文。返回所需字节数（不含 \0）。
int ime_get_segmented_input(ime_h h, char* buf, int32_t cap);

/// 手动分词（v1.2）：在首个未锚定的音节边界插入分隔符 '\''（被锚定，
/// 后续输入保持不动）；已全部锚定且无段可细分时返回 0。
int ime_insert_manual_separator(ime_h h);

/// 设置上文（v1.3 上下文通道）：光标前已上屏文本（UTF-8），引擎截取尾部
/// ≤18 字节参与候选重排（重复让位/叠词豁免）。空串或 NULL 清空。
int ime_set_context(ime_h h, const char* before, int32_t len);

/// 设置上文（v1.3 上下文通道）：光标前已上屏文本（UTF-8），供候选
/// 重排（上文重复让位/结合前文排列）。光标移动或焦点切换时更新。
int ime_set_context(ime_h h, const char* before, int32_t len);

/// 候选数量（当前页 0..ime_candidate_count-1）。
int ime_candidate_count(ime_h h);

/// 取第 index 个候选文本。返回 IME_OK / E_BUFFER_SMALL。
int ime_get_candidate(ime_h h, int32_t index, char* buf, int32_t cap);

/// 取第 index 个候选的拼音（空格分隔）。
int ime_get_candidate_pinyin(ime_h h, int32_t index, char* buf, int32_t cap);

/// 选词上屏：提交候选 index，触发内部学习回写（§4.5）。
/// 词组候选：合成区回填剩余音节；整句候选：清空合成区。
/// out_text / out_pinyin 输出本次上屏内容（供持久层 SQLite 回写）。
/// 返回提交词数（0 表示无效 index）。
int ime_commit_candidate(ime_h h, int32_t index, char* out_text, int32_t text_cap,
                         char* out_pinyin, int32_t pinyin_cap);

/// T9 模式：取"可能的拼音串"列表条数（§4.2.2 横滑条）。
int ime_t9_pinyin_count(ime_h h);

/// T9 模式：取第 i 个可能拼音串。
int ime_get_t9_pinyin(ime_h h, int32_t i, char* buf, int32_t cap);

/// 供导入/手动管理的学习接口（§8.1 ime_learn）。
int ime_learn(ime_h h, const char* word, const char* pinyin, int32_t source);

/// 从上层持久层装载用户词库（Kotlin SQLite → native 内存模型）。
/// blob 格式：word'\0'pinyin'\0'freq(u32 LE) 逐条拼接。
int ime_load_user_words(ime_h h, const char* blob, int32_t len);

/// 会话结束：触发时间衰减 freq *= 0.99（§4.5）。
int ime_session_end(ime_h h);

/// 最近一次错误码。
int ime_last_error(ime_h h);

/// 引擎版本。
const char* ime_version(void);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // GLASSKEY_GK_API_H_
