#include "engine.h"

#include <cstdio>
#include <cstring>

namespace gk {
namespace {
inline bool IsLower(char c) { return c >= 'a' && c <= 'z'; }
}  // namespace

int ImeEngine::Init(const EngineConfig& cfg) {
  fuzzy_ = cfg.fuzzy_mask != 0 ? cfg.fuzzy_mask : FUZZY_DEFAULT;
  beam_ = cfg.beam_width == 0 ? 10 : static_cast<int>(cfg.beam_width);
  max_cand_ = cfg.max_candidates == 0 ? 30 : static_cast<int>(cfg.max_candidates);  // v1.4：默认 30
  learning_ = cfg.enable_learning != 0;

  int err = IME_OK;
  if (cfg.dict_path == nullptr || !zh_dict_.Open(cfg.dict_path, &err)) {
    last_error_ = err != IME_OK ? err : E_DICT_MISSING;
    return last_error_;
  }
  if (cfg.latin_path != nullptr && cfg.latin_path[0] != '\0') {
    int err2 = IME_OK;
    has_latin_ = latin_dict_.Open(cfg.latin_path, &err2);
    if (!has_latin_) last_error_ = err2;  // 英文词典可选，缺失不致命
  }
  py_.SetDict(&zh_dict_);
  py_.SetUserModel(&um_);
  py_.SetConfig(fuzzy_, beam_, max_cand_);
  if (has_latin_) {
    latin_.SetDict(&latin_dict_);
    latin_.SetUserModel(&um_);
  }
  SetLayout(layout_);
  return IME_OK;
}

void ImeEngine::SetLayout(int32_t layout) {
  if (layout < LAYOUT_PINYIN_26 || layout > LAYOUT_LATIN) return;
  layout_ = layout;
  Reset();
}

void ImeEngine::SetFuzzy(uint32_t mask) {
  fuzzy_ = mask != 0 ? mask : FUZZY_NONE;
  py_.SetConfig(fuzzy_, beam_, max_cand_);
  PushComposition();
}

bool ImeEngine::CopyOut(const std::string& s, char* buf, int32_t cap) const {
  if (buf == nullptr || cap <= 0) return false;
  size_t n = s.size();
  if (static_cast<int>(n) + 1 > cap) {
    if (cap > 0) buf[0] = '\0';
    return false;
  }
  std::memcpy(buf, s.data(), n);
  buf[n] = '\0';
  return true;
}

void ImeEngine::PushComposition() {
  if (layout_ == LAYOUT_LATIN) latin_.SetInput(comp_);
  else py_.SetInput(comp_);
}

int ImeEngine::ProcessKey(uint32_t code, int32_t action, int32_t meta) {
  if (action != 0) return IME_OK;  // 仅处理按下
  (void)meta;
  char c = static_cast<char>(code);
  bool accept = false;
  switch (layout_) {
    case LAYOUT_PINYIN_26: accept = IsLower(c) || c == kSeparator; break;
    case LAYOUT_PINYIN_9: accept = (c >= '2' && c <= '9') || c == kSeparator; break;
    case LAYOUT_LATIN: accept = IsLower(c); break;
    default: return E_INVALID;
  }
  if (!accept) return IME_OK;  // 非法字符静默忽略（由 UI 层决定是否上屏）
  if (comp_.size() >= static_cast<size_t>(kMaxCompositionBytes)) return IME_OK;  // 超长仅回显
  comp_.push_back(c);
  PushComposition();
  return IME_OK;
}

int ImeEngine::Backspace() {
  if (comp_.empty()) return 0;  // 由上层编辑正文
  comp_.pop_back();
  PushComposition();
  return 1;
}

int ImeEngine::Reset() {
  comp_.clear();
  py_.Reset();
  latin_.Reset();
  return IME_OK;
}

int ImeEngine::GetInput(char* buf, int32_t cap) const { return CopyOut(comp_, buf, cap) ? 0 : E_BUFFER_SMALL; }

int ImeEngine::GetSegmentedInput(char* buf, int32_t cap) const {
  if (layout_ == LAYOUT_LATIN) return CopyOut(comp_, buf, cap) ? IME_OK : E_BUFFER_SMALL;
  return CopyOut(SegmentedDisplay(comp_), buf, cap) ? IME_OK : E_BUFFER_SMALL;
}

int ImeEngine::InsertManualSeparator() {
  if (layout_ == LAYOUT_LATIN) return 0;
  if (comp_.empty() || static_cast<int>(comp_.size()) >= kMaxCompositionBytes) return 0;
  auto segs = ComputeDisplaySegments(comp_);
  if (segs.empty()) return 0;
  // 1) 首个未锚定段间边界（升序）
  for (size_t k = 1; k < segs.size(); ++k) {
    if (!segs[k - 1].anchored) {
      comp_.insert(comp_.begin() + segs[k].start, kSeparator);
      PushComposition();
      return 1;
    }
  }
  // 2) 段内细分（T9 数字模式切分语义不同，跳过）
  bool t9 = comp_[0] >= '2' && comp_[0] <= '9';
  if (!t9) {
    for (const auto& s : segs) {
      int len = s.end - s.start;
      if (len < 2) continue;
      const char* p = comp_.data() + s.start;
      // 2a. 双音节最短切分：xian→xi'an、zhuang→zhu'ang
      for (int cut = 1; cut < len; ++cut) {
        if (IsSyllable(p, cut) && IsSyllable(p + cut, len - cut)) {
          comp_.insert(comp_.begin() + s.start + cut, kSeparator);
          PushComposition();
          return 1;
        }
      }
      // 2b. 音节 + 音节前缀（cut≥2 且剩余≥2，避免"完整音节+单字母"的无意义拆分）
      for (int cut = 2; cut < len - 1; ++cut) {
        if (IsSyllable(p, cut) && IsSyllablePrefix(p + cut, len - cut)) {
          comp_.insert(comp_.begin() + s.start + cut, kSeparator);
          PushComposition();
          return 1;
        }
      }
    }
  }
  return 0;
}

int ImeEngine::CandidateCount() const {
  if (layout_ == LAYOUT_LATIN) return latin_.CandidateCount();
  return py_.CandidateCount();
}

int ImeEngine::GetCandidate(int32_t index, char* buf, int32_t cap) const {
  std::string s;
  if (layout_ == LAYOUT_LATIN) {
    const char* t = latin_.candidate(index);
    if (t != nullptr) s = t;
  } else {
    if (index < 0 || index >= py_.CandidateCount()) return E_INVALID;
    s = py_.candidate(index).text;
  }
  return CopyOut(s, buf, cap) ? IME_OK : E_BUFFER_SMALL;
}

int ImeEngine::GetCandidatePinyin(int32_t index, char* buf, int32_t cap) const {
  if (layout_ == LAYOUT_LATIN || index < 0 || index >= py_.CandidateCount()) return E_INVALID;
  return CopyOut(py_.candidate(index).pinyin, buf, cap) ? IME_OK : E_BUFFER_SMALL;
}

int ImeEngine::CommitCandidate(int32_t index, char* out_text, int32_t text_cap,
                               char* out_pinyin, int32_t py_cap) {
  std::string text, py;
  if (layout_ == LAYOUT_LATIN) {
    text = latin_.Commit(index);
    if (text.empty()) return 0;
    comp_.clear();
    if (!CopyOut(text, out_text, text_cap)) return 0;
    CopyOut(std::string(), out_pinyin, py_cap);  // 拉丁候选无拼音
    return 1;
  }
  auto r = py_.Commit(index);
  if (!r.ok) return 0;
  text = r.text;
  py = r.pinyin;
  comp_ = py_.input();  // 词组候选：合成区回填剩余音节
  if (!CopyOut(text, out_text, text_cap)) return 0;
  CopyOut(py, out_pinyin, py_cap);
  return 1;
}

int ImeEngine::T9PinyinCount() const { return py_.T9PinyinCount(); }

int ImeEngine::GetT9Pinyin(int32_t i, char* buf, int32_t cap) const {
  return CopyOut(py_.T9PinyinAt(i), buf, cap) ? IME_OK : E_BUFFER_SMALL;
}

int ImeEngine::SetContext(const char* before, int32_t len) {
  py_.SetContext(before, before != nullptr ? len : 0);
  return IME_OK;
}

int ImeEngine::Learn(const char* word, const char* pinyin, int32_t source) {
  if (word == nullptr) return E_INVALID;
  if (learning_) um_.Learn(word, pinyin, source);
  return IME_OK;
}

int ImeEngine::LoadUserWords(const char* blob, int32_t len) {
  um_.Load(blob, len);
  return IME_OK;
}

int ImeEngine::SessionEnd() {
  um_.Decay();  // §4.5 时间衰减
  return IME_OK;
}

}  // namespace gk
