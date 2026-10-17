#include "mapped_file.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

#if !defined(_WIN32)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace gk {

MappedFile::~MappedFile() { Close(); }

bool MappedFile::Open(const char* path) {
  Close();
  if (path == nullptr) return false;
#if !defined(_WIN32)
  fd_ = ::open(path, O_RDONLY | O_CLOEXEC);
  if (fd_ < 0) return false;
  struct stat st{};
  if (::fstat(fd_, &st) != 0 || st.st_size <= 0) {
    Close();
    return false;
  }
  size_ = static_cast<size_t>(st.st_size);
  void* p = ::mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
  if (p == MAP_FAILED) {
    Close();
    return false;
  }
  data_ = static_cast<uint8_t*>(p);
  ::madvise(data_, size_, MADV_RANDOM);
  return true;
#else
  FILE* f = std::fopen(path, "rb");
  if (f == nullptr) return false;
  std::fseek(f, 0, SEEK_END);
  long n = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (n <= 0) { std::fclose(f); return false; }
  size_ = static_cast<size_t>(n);
  data_ = static_cast<uint8_t*>(std::malloc(size_));
  size_t rd = data_ ? std::fread(data_, 1, size_, f) : 0;
  std::fclose(f);
  if (rd != size_) { Close(); return false; }
  return true;
#endif
}

void MappedFile::Close() {
#if !defined(_WIN32)
  if (data_ != nullptr) ::munmap(data_, size_);
  if (fd_ >= 0) ::close(fd_);
  fd_ = -1;
#else
  std::free(data_);
#endif
  data_ = nullptr;
  size_ = 0;
}

}  // namespace gk
