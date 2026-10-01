#pragma once

#include <jni.h>

#include <cstdint>

namespace aether {

// Mirrors art::hiddenapi::EnforcementPolicy. The numeric values have been
// stable since the mechanism was introduced in Android 9 (API 28).
enum class EnforcementPolicy : uint32_t {
  kNoChecks = 0,
  kJustWarn = 1,
  kDarkGreyAndBlackList = 2,
  kBlacklistOnly = 3,
};

enum class UnsealStrategy : int {
  // Android <= 27: the restriction does not exist, nothing was written.
  kNotRequired = 0,
  // We located art::Runtime::instance_ and cleared the policy word(s).
  kRuntimePolicyWrite = 1,
  // Could not locate the Runtime, or calibration never succeeded.
  kFailed = 2,
};

struct UnsealReport {
  UnsealStrategy strategy = UnsealStrategy::kFailed;
  int api_level = 0;
  // Address of the art::Runtime object, 0 if unresolved.
  uintptr_t runtime = 0;
  // Byte offsets inside the Runtime object that we cleared.
  uint32_t offsets[4] = {0, 0, 0, 0};
  uint32_t original_values[4] = {0, 0, 0, 0};
  uint32_t offsets_cleared = 0;
  // How many candidate slots the calibration sweep had to try.
  uint32_t probes = 0;
};

/**
 * Disable ART's hidden-API enforcement for this process.
 *
 * Why this exists: AetherEngine is an *in-process* container. Replacing the
 * Activity lifecycle, the LoadedApk and the ClassLoader requires calling
 * `Activity.attach()`, `AssetManager.addAssetPath()`, `LoadedApk` setters and
 * `VMRuntime.setHiddenApiExemptions()` — all of which are on the hidden-API
 * blocklist from API 28 onwards. Without this, the guest dies with
 * NoSuchMethodError on the very first frame.
 *
 * Strategy (deliberately version-agnostic):
 *
 *   1. Resolve the `art::Runtime::instance_` static from the already-loaded
 *      libart.so. This is a stable, exported C++ symbol.
 *   2. The byte offset of `hidden_api_policy_` inside `art::Runtime` is NOT
 *      stable across Android releases (and on API 30+ there are two policy
 *      words: the normal one and `core_platform_api_policy_`). Rather than
 *      carry a hand-maintained offset table that silently breaks on every
 *      OEM fork, we *calibrate*: sweep word-aligned slots, temporarily write
 *      kNoChecks, and ask Java to probe a known blocklisted reflection. A
 *      slot is accepted only if the probe flips from false to true; every
 *      rejected slot is restored byte-for-byte.
 *
 * `probe` is a `dev.aether.host.native.HiddenApiProbe` instance whose
 * `tryBlacklistedReflection()` returns true once reflection escapes the
 * sandbox. It is called with a JNIEnv that is already attached.
 *
 * The function never throws and never leaves the Runtime in a modified state
 * unless calibration explicitly succeeded.
 */
UnsealReport UnsealHiddenApi(JNIEnv* env, jobject probe);

/**
 * Call `dalvik.system.VMRuntime.setHiddenApiExemptions(String[])`.
 *
 * Only meaningful *after* UnsealHiddenApi() succeeded; it is the belt to the
 * calibration's braces, making the exemption permanent for the process even if
 * something later re-writes the policy word.
 */
bool SetHiddenApiExemptions(JNIEnv* env, jobjectArray prefixes);

/** `ro.build.version.sdk`, cached after the first call. */
int AndroidApiLevel();

}  // namespace aether
