// 只读文件映射（词典/LM 一律 mmap 加载，不占 Java 堆，§3.3/§9.1）
// POSIX 使用 mmap；无 mmap 平台退化为整读内存（功能等价）。
#ifndef GLASSKEY_CORE_MAPPED_FILE_H_
#define GLASSKEY_CORE_MAPPED_FILE_H_

#include <cstddef>
#include <cstdint>

namespace gk {

class MappedFile {
 public:
  MappedFile() = default;
  ~MappedFile();
  MappedFile(const MappedFile&) = delete;
  MappedFile& operator=(const MappedFile&) = delete;

  bool Open(const char* path);
  void Close();
  const uint8_t* data() const { return data_; }
  size_t size() const { return size_; }
  bool valid() const { return data_ != nullptr && size_ > 0; }

 private:
  uint8_t* data_ = nullptr;
  size_t size_ = 0;
#if defined(_WIN32)
  void* handle_ = nullptr;
  void* map_ = nullptr;
#else
  int fd_ = -1;
#endif
};

}  // namespace gk

#endif  // GLASSKEY_CORE_MAPPED_FILE_H_
