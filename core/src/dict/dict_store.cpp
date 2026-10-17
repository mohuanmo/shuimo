#include "dict_store.h"

#include <cstring>
#include <vector>

#include "common/crc32.h"

namespace gk {
namespace {
constexpr char kMagic[4] = {'G', 'K', 'D', 'T'};
constexpr int kMaxKeyLen = 96;    // DAT 查询键上限
constexpr int kMaxDfsDepth = 96;  // 前缀收集深度上限

uint16_t RdU16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t RdU32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
}  // namespace

const DictStore::Sec* DictStore::Find(uint16_t type) const {
  if (type < 1 || type > 8) return nullptr;
  const Sec& s = secs_[type];
  return s.size > 0 ? &s : nullptr;
}

bool DictStore::Open(const char* path, int* err) {
  if (err != nullptr) *err = IME_OK;
  if (!file_.Open(path)) {
    if (err != nullptr) *err = E_DICT_MISSING;
    last_error_ = E_DICT_MISSING;
    return false;
  }
  const uint8_t* d = file_.data();
  const size_t n = file_.size();
  if (n < 8 || std::memcmp(d, kMagic, 4) != 0) {
    if (err != nullptr) *err = E_DICT_VERSION;
    last_error_ = E_DICT_VERSION;
    return false;
  }
  uint16_t version = RdU16(d + 4);
  if (version != kVersion) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
    return false;
  }
  section_count_ = RdU16(d + 6);
  if (section_count_ > 8 || n < 8u + 16u * section_count_) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
    return false;
  }
  for (uint16_t i = 0; i < section_count_; ++i) {
    const uint8_t* h = d + 8 + 16u * i;
    uint16_t type = RdU16(h);
    if (type < 1 || type > 8) continue;  // 段类型 1..8（SEC_WLIST=8 必须接收）
    Sec s{};
    s.offset = RdU32(h + 4);
    s.size = RdU32(h + 8);
    s.crc = RdU32(h + 12);
    if (static_cast<size_t>(s.offset) + s.size > n) {  // 越界即坏档
      last_error_ = E_DICT_CRC;
      if (err != nullptr) *err = E_DICT_CRC;
      return false;
    }
    secs_[type] = s;
  }
  // DAT 段：头部为 node_count，随后 base/check 两个数组
  const Sec* dat = Find(SEC_DAT);
  if (dat == nullptr || dat->size < 4) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
    return false;
  }
  const uint8_t* db = d + dat->offset;
  dat_count_ = static_cast<int32_t>(RdU32(db));
  if (dat_count_ < 1 || 4u + 8u * static_cast<uint32_t>(dat_count_) != dat->size) {
    last_error_ = E_DICT_CRC;
    if (err != nullptr) *err = E_DICT_CRC;
    return false;
  }
  dat_base_ = reinterpret_cast<const int32_t*>(db + 4);
  dat_check_ = dat_base_ + dat_count_;
  // WORD_IDX 决定词条数
  const Sec* widx = Find(SEC_WORD_IDX);
  if (widx == nullptr || widx->size < 4) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
    return false;
  }
  word_idx_ = reinterpret_cast<const uint32_t*>(d + widx->offset);
  word_count_ = static_cast<int32_t>(widx->size / 4) - 1;
  const Sec* words = Find(SEC_WORDS);
  words_ = words != nullptr ? d + words->offset : nullptr;
  words_size_ = words != nullptr ? words->size : 0;
  const Sec* freq = Find(SEC_FREQ);
  freq_ = freq != nullptr ? reinterpret_cast<const uint32_t*>(d + freq->offset) : nullptr;
  // WLIST：DAT 终结 → 词 id 集合（同音词，§4.3）
  const Sec* wlidx = Find(SEC_WLIST_IDX);
  const Sec* wl = Find(SEC_WLIST);
  if (wlidx == nullptr || wl == nullptr) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
    return false;
  }
  wlist_idx_ = reinterpret_cast<const uint32_t*>(d + wlidx->offset);
  wlist_idx_count_ = static_cast<int32_t>(wlidx->size / 4);
  wlist_ = reinterpret_cast<const uint32_t*>(d + wl->offset);
  wlist_count_ = static_cast<int32_t>(wl->size / 4);
  if (wlist_idx_count_ < 1) {
    last_error_ = E_DICT_CRC;
    if (err != nullptr) *err = E_DICT_CRC;
    return false;
  }
  const Sec* meta = Find(SEC_META);
  if (meta != nullptr && meta->size > 0) {
    meta_.assign(reinterpret_cast<const char*>(d + meta->offset), meta->size);
  }
  valid_ = word_count_ >= 0 && words_ != nullptr;
  if (!valid_) {
    last_error_ = E_DICT_VERSION;
    if (err != nullptr) *err = E_DICT_VERSION;
  }
  return valid_;
}

bool DictStore::EnsureSection(uint16_t type) {
  Sec* s = const_cast<Sec*>(Find(type));
  if (s == nullptr) return false;
  if (s->checked) return true;
  if (Crc32(file_.data() + s->offset, s->size) != s->crc) {
    last_error_ = E_DICT_CRC;
    return false;
  }
  s->checked = true;
  return true;
}

const uint8_t* DictStore::SecData(uint16_t type, uint32_t* size) {
  if (!EnsureSection(type)) return nullptr;
  const Sec* s = Find(type);
  if (s == nullptr) return nullptr;
  if (size != nullptr) *size = s->size;
  return file_.data() + s->offset;
}

int32_t DictStore::DatStep(int32_t node, uint8_t c) const {
  if (node < 0 || node >= dat_count_) return -1;
  int32_t m = dat_base_[node] + static_cast<int32_t>(c);
  if (m < 0 || m >= dat_count_ || dat_check_[m] != node) return -1;
  return m;
}

int32_t DictStore::DatAdvance(int32_t node, const char* bytes, int len) const {
  for (int i = 0; i < len; ++i) {
    node = DatStep(node, static_cast<uint8_t>(bytes[i]));
    if (node < 0) return -1;
  }
  return node;
}

int32_t DictStore::DatTerminal(int32_t node) const {
  int32_t t = DatStep(node, 0);  // '\0' 终结边
  if (t < 0) return -1;
  int32_t v = dat_base_[t];
  return v >= 0 ? v : -1;  // WLIST 下标
}

int32_t DictStore::Step(int32_t node, uint8_t c) { return DatStep(node, c); }

int32_t DictStore::TerminalList(int32_t node) { return DatTerminal(node); }

int DictStore::ListCount(int32_t list_idx) const {
  if (list_idx < 0 || list_idx + 1 >= wlist_idx_count_) return 0;
  uint32_t a = wlist_idx_[list_idx];
  uint32_t b = wlist_idx_[list_idx + 1];
  if (a > b || b > static_cast<uint32_t>(wlist_count_)) return 0;
  return static_cast<int>(b - a);
}

uint32_t DictStore::ListItem(int32_t list_idx, int k) const {
  if (list_idx < 0 || list_idx + 1 >= wlist_idx_count_ || k < 0) return 0;
  uint32_t a = wlist_idx_[list_idx];
  uint32_t b = wlist_idx_[list_idx + 1];
  if (a + static_cast<uint32_t>(k) >= b) return 0;
  return wlist_[a + static_cast<uint32_t>(k)];
}

int DictStore::QueryWords(const char* key, uint32_t* out_ids, int cap) {
  if (key == nullptr || out_ids == nullptr || cap <= 0 || !valid_) return 0;
  if (!EnsureSection(SEC_DAT)) return 0;
  int len = static_cast<int>(std::strlen(key));
  if (len <= 0 || len > kMaxKeyLen) return 0;
  int32_t node = DatAdvance(0, key, len);
  if (node < 0) return 0;
  int32_t li = DatTerminal(node);
  if (li < 0) return 0;
  int n = ListCount(li);
  if (n > cap) n = cap;
  for (int i = 0; i < n; ++i) out_ids[i] = ListItem(li, i);
  return n;
}

void DictStore::DatCollect(int32_t node, uint32_t* out, int cap, int* n, int depth) const {
  if (node < 0 || node >= dat_count_ || *n >= cap || depth > kMaxDfsDepth) return;
  // 终结边优先（更短补全）
  int32_t t = DatStep(node, 0);
  if (t >= 0) {
    int32_t v = dat_base_[t];
    if (v >= 0) {
      out[(*n)++] = static_cast<uint32_t>(v);
      if (*n >= cap) return;
    }
  }
  for (int c = 1; c < 256; ++c) {
    int32_t m = DatStep(node, static_cast<uint8_t>(c));
    if (m >= 0) DatCollect(m, out, cap, n, depth + 1);
    if (*n >= cap) return;
  }
}

int DictStore::QueryPrefix(const char* prefix, uint32_t* out_ids, int cap) {
  if (prefix == nullptr || out_ids == nullptr || cap <= 0 || !valid_) return 0;
  if (!EnsureSection(SEC_DAT)) return 0;
  int len = static_cast<int>(std::strlen(prefix));
  if (len <= 0 || len > kMaxKeyLen) return 0;
  int32_t node = DatAdvance(0, prefix, len);
  if (node < 0) return 0;
  uint32_t lists[64];
  int nl = 0;
  DatCollect(node, lists, 64, &nl, 0);
  int total = 0;
  for (int i = 0; i < nl && total < cap; ++i) {
    int cnt = ListCount(static_cast<int32_t>(lists[i]));
    for (int k = 0; k < cnt && total < cap; ++k) {
      out_ids[total++] = ListItem(static_cast<int32_t>(lists[i]), k);
    }
  }
  return total;
}

const char* DictStore::WordText(uint32_t id) const {
  if (id >= static_cast<uint32_t>(word_count_) || words_ == nullptr) return nullptr;
  uint32_t off = word_idx_[id];
  if (off >= words_size_) return nullptr;
  return reinterpret_cast<const char*>(words_ + off);
}

const char* DictStore::WordPinyin(uint32_t id) const {
  if (id >= static_cast<uint32_t>(word_count_) || words_ == nullptr) return nullptr;
  uint32_t off = word_idx_[id];
  if (off >= words_size_) return nullptr;
  const uint8_t* p = words_ + off;
  while (off < words_size_ && *p != 0) { ++p; ++off; }
  if (off + 1 >= words_size_) return "";
  return reinterpret_cast<const char*>(p + 1);
}

uint32_t DictStore::WordFreq(uint32_t id) const {
  if (freq_ == nullptr || id >= static_cast<uint32_t>(word_count_)) return 0;
  return freq_[id];
}

}  // namespace gk
