// ImeEngine 门面：组合词典/用户模型/拼音引擎/拉丁引擎（JNI 层仅做转换，§8.1）
#ifndef GLASSKEY_CORE_ENGINE_H_
#define GLASSKEY_CORE_ENGINE_H_

#include <string>

#include "glasskey/gk_types.h"
#include "dict/dict_store.h"
#include "latin/latin_engine.h"
#include "pinyin/pinyin_engine.h"
#include "user/user_model.h"

namespace gk {

class ImeEngine {
 public:
  ImeEngine() = default;
  ~ImeEngine() = default;
  ImeEngine(const ImeEngine&) = delete;
  ImeEngine& operator=(const ImeEngine&) = delete;

  int Init(const EngineConfig& cfg);

  void SetLayout(int32_t layout);
  void SetFuzzy(uint32_t mask);
  void SetLearning(int enabled) { learning_ = enabled != 0; }

  int ProcessKey(uint32_t code, int32_t action, int32_t meta);
  int Backspace();
  int Reset();
  int GetInput(char* buf, int32_t cap) const;
  /// 自动分词显示串（v1.2）：音节边界空格呈现、锚点紧贴前段；每击键重算。
  /// 拉丁模式无音节概念，返回原文。
  int GetSegmentedInput(char* buf, int32_t cap) const;
  /// 手动分词（v1.2）：在首个未锚定边界插入分隔符并锚定。
  /// 优先段间边界；无段间边界时对首个可细分音节段做"双音节切分"，
  /// 再退"音节+前缀"。成功返回 1（合成串更新并重解码），无处可插返回 0。
  int InsertManualSeparator();

  /// 设置上文（v1.3 上下文通道）：光标前已上屏文本，转发拼音引擎做候选重排。
  int SetContext(const char* before, int32_t len);

  int CandidateCount() const;
  int GetCandidate(int32_t index, char* buf, int32_t cap) const;
  int GetCandidatePinyin(int32_t index, char* buf, int32_t cap) const;
  /// 返回提交词数；out_text/out_pinyin 填本次上屏内容（供持久层回写）。
  int CommitCandidate(int32_t index, char* out_text, int32_t text_cap, char* out_pinyin,
                      int32_t py_cap);

  int T9PinyinCount() const;
  int GetT9Pinyin(int32_t i, char* buf, int32_t cap) const;

  int Learn(const char* word, const char* pinyin, int32_t source);
  int LoadUserWords(const char* blob, int32_t len);
  int SessionEnd();
  int last_error() const { return last_error_; }

 private:
  bool CopyOut(const std::string& s, char* buf, int32_t cap) const;
  void PushComposition();

  DictStore zh_dict_;
  DictStore latin_dict_;
  bool has_latin_ = false;
  UserModel um_;
  PinyinEngine py_;
  LatinEngine latin_;
  std::string comp_;
  int32_t layout_ = LAYOUT_PINYIN_26;
  uint32_t fuzzy_ = FUZZY_DEFAULT;
  int beam_ = 10;
  int max_cand_ = 10;
  bool learning_ = true;
  int last_error_ = IME_OK;
};

}  // namespace gk

#endif  // GLASSKEY_CORE_ENGINE_H_
