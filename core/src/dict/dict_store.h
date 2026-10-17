// GKDT 词典容器读取器（§7.3 词典二进制格式规格）
// 布局：
//   [magic "GKDT"][version:u16][section_count:u16]
//   [header × n : {type:u16, reserved:u16, offset:u32, size:u32, crc32:u32}]
//   sections:
//     1 META(json)  2 DAT(base/check)  3 WORDS(word\0pinyin\0...)
//     4 FREQ(u32×n)  5 WORD_IDX(u32×(n+1))  6 NGRAM(保留)
//     7 WLIST_IDX(u32×(k+1))  8 WLIST(u32 id 流)  ← DAT 终结指向 id 集合（同音词）
// DAT 语义（含终结边）：
//   node 0 为根；边 c: m = base[node] + (uint8)c，有效当且仅当 check[m]==node；
//   词以 key+'\\0' 入树，终结节点 base>=0 即 WLIST 下标（词 id 集合，§4.3）。
// 校验策略：惰性 CRC（首次访问段时校验），避免首帧开销（§7.3）。
#ifndef GLASSKEY_CORE_DICT_STORE_H_
#define GLASSKEY_CORE_DICT_STORE_H_

#include <cstdint>
#include <string>

#include "common/mapped_file.h"
#include "glasskey/gk_types.h"

namespace gk {

class DictStore {
 public:
  static constexpr uint16_t kVersion = 1;

  enum SectionType : uint16_t {
    SEC_META = 1,
    SEC_DAT = 2,
    SEC_WORDS = 3,
    SEC_FREQ = 4,
    SEC_WORD_IDX = 5,
    SEC_NGRAM = 6,
    SEC_WLIST_IDX = 7,
    SEC_WLIST = 8,
  };

  DictStore() = default;
  ~DictStore() = default;
  DictStore(const DictStore&) = delete;
  DictStore& operator=(const DictStore&) = delete;

  /// 打开并解析头部；失败返回 false 并设置 *err（E_DICT_MISSING / E_DICT_VERSION）。
  bool Open(const char* path, int* err);

  /// 惰性 CRC 校验（首次访问各段时调用）；失败置 last_error=E_DICT_CRC。
  bool EnsureSection(uint16_t type);

  /// 精确查询：key 为词库键（拼音音节以单空格分隔，或英文小写词）。
  /// 返回写入 out_ids 的数量；查无返回 0。
  int QueryWords(const char* key, uint32_t* out_ids, int cap);

  /// 前缀补全查询（供英文补全/联想通道），最多返回 cap 条。
  int QueryPrefix(const char* prefix, uint32_t* out_ids, int cap);

  /// DAT 增量走树（供解码器逐字节扩展音节链）；失败返回 -1。
  int32_t Step(int32_t node, uint8_t c);
  /// 从当前节点走 '\0' 终结边取 WLIST 下标（词 id 集合）；非终结返回 -1。
  int32_t TerminalList(int32_t node);
  /// 词 id 集合访问。
  int ListCount(int32_t list_idx) const;
  uint32_t ListItem(int32_t list_idx, int k) const;

  int32_t WordCount() const { return word_count_; }
  const char* WordText(uint32_t id) const;
  const char* WordPinyin(uint32_t id) const;
  uint32_t WordFreq(uint32_t id) const;
  const std::string& Meta() const { return meta_; }
  bool valid() const { return valid_; }
  int last_error() const { return last_error_; }
  void set_last_error(int e) const { last_error_ = e; }

 private:
  struct Sec {
    uint32_t offset = 0;
    uint32_t size = 0;
    uint32_t crc = 0;
    bool checked = false;
  };
  const Sec* Find(uint16_t type) const;
  const uint8_t* SecData(uint16_t type, uint32_t* size);
  int32_t DatStep(int32_t node, uint8_t c) const;
  int32_t DatAdvance(int32_t node, const char* bytes, int len) const;
  int32_t DatTerminal(int32_t node) const;  // 走 '\\0' 终结边，返回 word id 或 -1
  void DatCollect(int32_t node, uint32_t* out, int cap, int* n, int depth) const;

  MappedFile file_;
  Sec secs_[9];  // 下标 = 段类型（1..8），0 弃用
  uint16_t section_count_ = 0;
  int32_t dat_count_ = 0;
  const int32_t* dat_base_ = nullptr;
  const int32_t* dat_check_ = nullptr;
  const uint32_t* word_idx_ = nullptr;  // word_count+1 项
  const uint32_t* freq_ = nullptr;
  const uint8_t* words_ = nullptr;
  uint32_t words_size_ = 0;
  const uint32_t* wlist_idx_ = nullptr;  // DAT 终结 → 词 id 集合
  int32_t wlist_idx_count_ = 0;
  const uint32_t* wlist_ = nullptr;
  int32_t wlist_count_ = 0;
  int32_t word_count_ = 0;
  std::string meta_;
  bool valid_ = false;
  mutable int last_error_ = IME_OK;
};

}  // namespace gk

#endif  // GLASSKEY_CORE_DICT_STORE_H_
