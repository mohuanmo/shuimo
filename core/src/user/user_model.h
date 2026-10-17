// 用户模型（§4.5）：选词学习 / 自造词 / 时间衰减 / Boost 通道
// 纯内存实现；持久化由 Android 层 SQLite（§7.2 user_word）负责。
#ifndef GLASSKEY_CORE_USER_MODEL_H_
#define GLASSKEY_CORE_USER_MODEL_H_

#include <cstdint>
#include <string>
#include <unordered_map>

namespace gk {

class UserModel {
 public:
  struct Entry {
    std::string word;
    std::string pinyin;
    uint32_t freq = 0;
    uint32_t source = 0;
  };

  /// blob 格式：word'\0'pinyin'\0'freq(u32 LE) 逐条拼接（与 C ABI 一致）。
  /// 返回成功装载的条数。
  int Load(const char* blob, int32_t len);

  /// 学习：freq += 1（source 更新为较大值语义见 §7.2）。返回新 freq。
  uint32_t Learn(const char* word, const char* pinyin, int32_t source);

  /// 重排 Boost（§4.5）：0 表示无用户数据。
  float Boost(const char* word) const;

  /// 会话结束时间衰减：freq *= 0.99（向下取整，最低保留 1）。
  void Decay();

  void Clear();
  size_t size() const { return map_.size(); }

 private:
  static std::string MakeKey(const char* word, const char* pinyin);
  std::unordered_map<std::string, Entry> map_;
};

}  // namespace gk

#endif  // GLASSKEY_CORE_USER_MODEL_H_
