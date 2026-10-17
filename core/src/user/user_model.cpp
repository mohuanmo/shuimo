#include "user_model.h"

#include <cmath>
#include <cstring>

namespace gk {

std::string UserModel::MakeKey(const char* word, const char* pinyin) {
  std::string k(word != nullptr ? word : "");
  k.push_back('\x01');
  k.append(pinyin != nullptr ? pinyin : "");
  return k;
}

int UserModel::Load(const char* blob, int32_t len) {
  if (blob == nullptr || len <= 0) return 0;
  int count = 0;
  const char* p = blob;
  const char* end = blob + len;
  while (p < end) {
    const char* w = static_cast<const char*>(std::memchr(p, '\0', static_cast<size_t>(end - p)));
    if (w == nullptr) break;
    const char* q = w + 1;
    if (q >= end) break;
    const char* y = static_cast<const char*>(std::memchr(q, '\0', static_cast<size_t>(end - q)));
    if (y == nullptr) break;
    const char* f = y + 1;
    if (f + 4 > end) break;
    uint32_t freq = static_cast<uint32_t>(static_cast<uint8_t>(f[0])) |
                    (static_cast<uint32_t>(static_cast<uint8_t>(f[1])) << 8) |
                    (static_cast<uint32_t>(static_cast<uint8_t>(f[2])) << 16) |
                    (static_cast<uint32_t>(static_cast<uint8_t>(f[3])) << 24);
    std::string key = MakeKey(p, q);
    Entry& e = map_[key];
    e.word.assign(p, static_cast<size_t>(w - p));
    e.pinyin.assign(q, static_cast<size_t>(y - q));
    e.freq = freq;
    ++count;
    p = f + 4;
  }
  return count;
}

uint32_t UserModel::Learn(const char* word, const char* pinyin, int32_t source) {
  if (word == nullptr || word[0] == '\0') return 0;
  Entry& e = map_[MakeKey(word, pinyin)];
  e.word = word;
  if (pinyin != nullptr) e.pinyin = pinyin;
  e.freq += 1;
  if (source >= 0 && source > static_cast<int32_t>(e.source)) e.source = static_cast<uint32_t>(source);
  return e.freq;
}

float UserModel::Boost(const char* word) const {
  if (word == nullptr || word[0] == '\0') return 0.0f;
  // 与 pinyin 无关的词级 Boost：取同词最大 freq
  uint32_t best = 0;
  for (const auto& kv : map_) {
    if (kv.second.word == word && kv.second.freq > best) best = kv.second.freq;
  }
  if (best == 0) return 0.0f;
  float b = std::log2(static_cast<float>(best) + 1.0f);
  if (b > 6.0f) b = 6.0f;  // 封顶后统一缩放（§4.5 Boost ≤ 4.8）
  return b * 0.8f;
}

void UserModel::Decay() {
  for (auto& kv : map_) {
    uint32_t& f = kv.second.freq;
    if (f > 1) {
      uint32_t nf = static_cast<uint32_t>(static_cast<double>(f) * 0.99);
      f = nf > 0 ? nf : 1;
    }
  }
}

void UserModel::Clear() { map_.clear(); }

}  // namespace gk
