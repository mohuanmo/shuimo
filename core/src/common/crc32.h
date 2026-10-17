// CRC32 (IEEE, 与 zlib 兼容) - 用于 GKDT 各段头部校验（§7.3）
#ifndef GLASSKEY_CORE_CRC32_H_
#define GLASSKEY_CORE_CRC32_H_

#include <cstddef>
#include <cstdint>

namespace gk {
uint32_t Crc32(const void* data, size_t len, uint32_t seed = 0);
}  // namespace gk

#endif  // GLASSKEY_CORE_CRC32_H_
