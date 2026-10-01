#include "native_extract.h"

#include <sys/stat.h>
#include <sys/system_properties.h>

#include <cstdio>
#include <cstring>

#include "log.h"

namespace aether {
namespace {

// ELF constants, spelled out: <elf.h> is not part of the NDK's stable API
// surface and we only ever touch the 24 bytes of the header.
constexpr unsigned char kElfMagic[4] = {0x7f, 'E', 'L', 'F'};
constexpr int kEiClass = 4;
constexpr int kEiData = 5;

uint16_t ReadHalf(const unsigned char* p, bool big_endian) {
  return big_endian ? static_cast<uint16_t>((p[0] << 8) | p[1])
                    : static_cast<uint16_t>((p[1] << 8) | p[0]);
}

}  // namespace

ElfProbe ProbeElf(const char* path) {
  ElfProbe probe;
  if (path == nullptr) return probe;

  FILE* f = fopen(path, "rb");
  if (f == nullptr) {
    LOGW("probe: cannot open %s", path);
    return probe;
  }

  unsigned char header[24] = {0};
  const size_t bytes = fread(header, 1, sizeof(header), f);
  fclose(f);

  if (bytes < sizeof(header)) {
    LOGW("probe: %s is too short to be an ELF (%zu bytes)", path, bytes);
    return probe;
  }
  if (memcmp(header, kElfMagic, sizeof(kElfMagic)) != 0) {
    LOGW("probe: %s has no ELF magic", path);
    return probe;
  }

  const bool big_endian = header[kEiData] == 2;
  probe.elf_class = header[kEiClass];
  probe.type = ReadHalf(&header[16], big_endian);
  probe.machine = ReadHalf(&header[18], big_endian);
  probe.valid = (probe.elf_class == 1 || probe.elf_class == 2) && probe.machine != 0;

  if (!probe.valid) {
    LOGW("probe: %s has EI_CLASS=%d machine=%d", path, probe.elf_class, probe.machine);
  }
  return probe;
}

bool MarkExecutable(const char* path) {
  if (path == nullptr) return false;
  // 0755: the linker needs the execute bit. SELinux allows exec on
  // app_data_file only for files the app itself created, which is why we
  // always *extract* rather than mmap straight out of the guest APK.
  if (chmod(path, 0755) != 0) {
    LOGW("chmod 0755 failed for %s", path);
    return false;
  }
  return true;
}

const char* CurrentAbi() {
  static char abi[PROP_VALUE_MAX + 1] = {0};
  if (abi[0] == '\0') {
    if (__system_property_get("ro.product.cpu.abi", abi) == 0) {
      strncpy(abi, "unknown", sizeof(abi) - 1);
    }
  }
  return abi;
}

}  // namespace aether
