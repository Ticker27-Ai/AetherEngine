#pragma once

namespace aether {

/**
 * ELF sanity checks for the guest's native libraries.
 *
 * The classic Layer-1 failure mode is not "the .so is missing", it is "the .so
 * is for the wrong ABI" — a 32-bit-only Unity build extracted into an
 * arm64-v8a directory, or an x86 lib in a fat APK. `dlopen` then fails deep
 * inside the guest with an error message that has nothing to do with the real
 * problem, and the game crashes on a black screen.
 *
 * Probing the ELF header before we ever call System.load() turns that into an
 * actionable, install-time error.
 */
struct ElfProbe {
  bool valid = false;
  /** 0 = unknown, 1 = ELF32, 2 = ELF64 (matches EI_CLASS). */
  int elf_class = 0;
  /** ELF e_machine, e.g. 40 (ARM), 183 (AArch64), 62 (x86-64). */
  int machine = 0;
  /** ELF e_type, e.g. 3 (ET_DYN, a shared object). */
  int type = 0;
};

/** Reads and validates the ELF header at `path`. Never throws. */
ElfProbe ProbeElf(const char* path);

/** `chmod 0755`, required after extracting a .so from the guest APK. */
bool MarkExecutable(const char* path);

/** Primary ABI of this device (`ro.product.cpu.abi`), e.g. "arm64-v8a". */
const char* CurrentAbi();

}  // namespace aether
