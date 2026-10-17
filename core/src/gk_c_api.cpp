// C ABI 实现（§8.1）：仅做参数转换与生命周期管理，不含业务逻辑
#include <cstring>
#include <new>

#include "engine.h"
#include "glasskey/gk_api.h"

using gk::ImeEngine;

ime_h ime_engine_create(const gk::EngineConfig* cfg) {
  if (cfg == nullptr) return nullptr;
  auto* e = new (std::nothrow) ImeEngine();
  if (e == nullptr) return nullptr;
  if (e->Init(*cfg) != gk::IME_OK) {
    delete e;
    return nullptr;
  }
  return e;
}

void ime_engine_destroy(ime_h h) { delete static_cast<ImeEngine*>(h); }

int ime_set_layout(ime_h h, int32_t layout) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  e->SetLayout(layout);
  return gk::IME_OK;
}

int ime_set_fuzzy(ime_h h, uint32_t mask) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  e->SetFuzzy(mask);
  return gk::IME_OK;
}

int ime_set_learning(ime_h h, int enabled) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  e->SetLearning(enabled);
  return gk::IME_OK;
}

int ime_process_key(ime_h h, uint32_t code, int32_t action, int32_t meta) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->ProcessKey(code, action, meta);
}

int ime_backspace(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->Backspace();
}

int ime_reset(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->Reset();
}

int ime_get_input(ime_h h, char* buf, int32_t cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->GetInput(buf, cap);
}

int ime_get_segmented_input(ime_h h, char* buf, int32_t cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->GetSegmentedInput(buf, cap);
}

int ime_insert_manual_separator(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->InsertManualSeparator();
}

int ime_set_context(ime_h h, const char* before, int32_t len) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->SetContext(before, len);
}

int ime_candidate_count(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return 0;
  return e->CandidateCount();
}

int ime_get_candidate(ime_h h, int32_t index, char* buf, int32_t cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->GetCandidate(index, buf, cap);
}

int ime_get_candidate_pinyin(ime_h h, int32_t index, char* buf, int32_t cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->GetCandidatePinyin(index, buf, cap);
}

int ime_commit_candidate(ime_h h, int32_t index, char* out_text, int32_t text_cap,
                         char* out_pinyin, int32_t pinyin_cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->CommitCandidate(index, out_text, text_cap, out_pinyin, pinyin_cap);
}

int ime_t9_pinyin_count(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return 0;
  return e->T9PinyinCount();
}

int ime_get_t9_pinyin(ime_h h, int32_t i, char* buf, int32_t cap) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->GetT9Pinyin(i, buf, cap);
}

int ime_learn(ime_h h, const char* word, const char* pinyin, int32_t source) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->Learn(word, pinyin, source);
}

int ime_load_user_words(ime_h h, const char* blob, int32_t len) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->LoadUserWords(blob, len);
}

int ime_session_end(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  if (e == nullptr) return gk::E_NOT_INIT;
  return e->SessionEnd();
}

int ime_last_error(ime_h h) {
  auto* e = static_cast<ImeEngine*>(h);
  return e != nullptr ? e->last_error() : gk::E_NOT_INIT;
}

const char* ime_version(void) { return gk::kVersion; }
