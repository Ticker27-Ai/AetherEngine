#include "hidden_api.h"

#include <android/api-level.h>
#include <dlfcn.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <mutex>

#include "log.h"

namespace aether {
namespace {

constexpr const char* kRuntimeInstanceSymbol = "_ZN3art7Runtime9instance_E";
constexpr const char* kVmRuntimeClass = "dalvik/system/VMRuntime";

// art::Runtime is a large singleton; both policy words live in its first few
// hundred bytes on every release we have inspected. Sweeping 4 KiB costs ~1024
// reads and is done exactly once per process.
constexpr uint32_t kScanWindow = 4096;
constexpr uint32_t kMaxOffsets = 4;

// Two enforcement words are adjacent members of art::Runtime, so once the
// first one is found we only accept neighbours inside this window. This is the
// guardrail that keeps the sweep from zeroing an unrelated uint32 that happens
// to hold the value 2.
constexpr uint32_t kAdjacencyWindow = 128;

constexpr uint32_t kMaxPlausiblePolicy = 3;

int ReadApiLevel() {
  char buf[PROP_VALUE_MAX + 1] = {0};
  __system_property_get("ro.build.version.sdk", buf);
  const int value = atoi(buf);
  return value > 0 ? value : __ANDROID_API__;
}

uint32_t* SlotAt(uintptr_t runtime, uint32_t offset) {
  return reinterpret_cast<uint32_t*>(runtime + offset);
}

}  // namespace

int AndroidApiLevel() {
  static const int level = ReadApiLevel();
  return level;
}

UnsealReport UnsealHiddenApi(JNIEnv* env, jobject probe) {
  static std::once_flag once;
  static UnsealReport report;

  std::call_once(once, [&] {
    report.api_level = AndroidApiLevel();

    if (report.api_level < 28) {
      report.strategy = UnsealStrategy::kNotRequired;
      LOGI("API %d: hidden-API enforcement does not exist, nothing to do.", report.api_level);
      return;
    }
    if (env == nullptr || probe == nullptr) {
      LOGE("unseal called without a probe callback");
      report.strategy = UnsealStrategy::kFailed;
      return;
    }

    // --- 1. Locate the ART runtime singleton -----------------------------
    void* symbol = dlsym(RTLD_DEFAULT, kRuntimeInstanceSymbol);
    if (symbol == nullptr) {
      // Some vendor builds (and the zygote's pre-fork state) do not expose it
      // through the default namespace; try an explicit handle.
      if (void* art = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD)) {
        symbol = dlsym(art, kRuntimeInstanceSymbol);
      }
    }
    if (symbol == nullptr) {
      LOGE("cannot resolve %s: %s", kRuntimeInstanceSymbol, dlerror());
      report.strategy = UnsealStrategy::kFailed;
      return;
    }

    const uintptr_t runtime = *reinterpret_cast<uintptr_t*>(symbol);
    if (runtime == 0) {
      LOGE("art::Runtime::instance_ is null — process is not yet initialised");
      report.strategy = UnsealStrategy::kFailed;
      return;
    }
    report.runtime = runtime;

    // --- 2. Resolve the Java-side probe ----------------------------------
    jclass probe_class = env->GetObjectClass(probe);
    if (probe_class == nullptr) {
      LOGE("probe object has no class");
      report.strategy = UnsealStrategy::kFailed;
      return;
    }
    const jmethodID probe_method =
        env->GetMethodID(probe_class, "tryBlacklistedReflection", "()Z");
    env->DeleteLocalRef(probe_class);
    if (probe_method == nullptr) {
      env->ExceptionClear();
      LOGE("HiddenApiProbe.tryBlacklistedReflection() not found");
      report.strategy = UnsealStrategy::kFailed;
      return;
    }

    auto run_probe = [&]() -> bool {
      const jboolean ok = env->CallBooleanMethod(probe, probe_method);
      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
      }
      return ok == JNI_TRUE;
    };

    // Already unrestricted? (Eng/userdebug builds, or an OEM that disabled
    // the policy.) Touch nothing.
    if (run_probe()) {
      report.strategy = UnsealStrategy::kNotRequired;
      LOGI("hidden APIs are already accessible (policy disabled by the platform).");
      return;
    }

    // --- 3. Calibrate: find the policy word(s) by experiment -------------
    uint32_t accepted[kMaxOffsets] = {0, 0, 0, 0};
    uint32_t accepted_count = 0;
    uintptr_t anchor = 0;  // offset of the first accepted slot

    for (uint32_t offset = 0; offset < kScanWindow; offset += sizeof(uint32_t)) {
      if (accepted_count >= kMaxOffsets) break;

      volatile uint32_t* slot = SlotAt(runtime, offset);
      const uint32_t original = *slot;

      // A policy word under enforcement holds a small enum; anything else is
      // not what we are looking for.
      if (original == 0 || original > kMaxPlausiblePolicy) continue;

      // Once we have a confirmed hit, only consider its neighbours.
      if (accepted_count > 0) {
        const uint32_t delta = offset > anchor ? offset - anchor : anchor - offset;
        if (delta > kAdjacencyWindow) continue;
      }

      ++report.probes;
      *slot = static_cast<uint32_t>(EnforcementPolicy::kNoChecks);

      if (run_probe()) {
        accepted[accepted_count] = offset;
        report.offsets[accepted_count] = offset;
        report.original_values[accepted_count] = original;
        ++accepted_count;
        if (accepted_count == 1) anchor = offset;
        LOGI("cleared enforcement policy at Runtime+%u (was %u)", offset, original);
      } else {
        // Not it. Restore byte-for-byte and keep sweeping.
        *slot = original;
      }
    }

    if (accepted_count == 0) {
      LOGE("calibration failed after %u probes; hidden APIs stay blocked", report.probes);
      report.strategy = UnsealStrategy::kFailed;
      return;
    }

    // Redundant safety net: re-assert the cleared values. If two threads raced
    // during calibration the last writer must still be kNoChecks.
    for (uint32_t i = 0; i < accepted_count; ++i) {
      *SlotAt(runtime, accepted[i]) = static_cast<uint32_t>(EnforcementPolicy::kNoChecks);
    }

    report.offsets_cleared = accepted_count;
    report.strategy = UnsealStrategy::kRuntimePolicyWrite;
    LOGI("hidden-API enforcement disabled (%u word(s), %u probes, API %d)",
         accepted_count, report.probes, report.api_level);
  });

  return report;
}

bool SetHiddenApiExemptions(JNIEnv* env, jobjectArray prefixes) {
  if (env == nullptr) return false;

  jclass vm_runtime = env->FindClass(kVmRuntimeClass);
  if (vm_runtime == nullptr) {
    env->ExceptionClear();
    LOGE("cannot find %s", kVmRuntimeClass);
    return false;
  }

  jmethodID get_runtime =
      env->GetStaticMethodID(vm_runtime, "getRuntime", "()Ldalvik/system/VMRuntime;");
  if (get_runtime == nullptr) {
    env->ExceptionClear();
    LOGE("VMRuntime.getRuntime() not found");
    env->DeleteLocalRef(vm_runtime);
    return false;
  }

  jmethodID set_exemptions =
      env->GetMethodID(vm_runtime, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
  if (set_exemptions == nullptr) {
    env->ExceptionClear();
    LOGE("VMRuntime.setHiddenApiExemptions() not found");
    env->DeleteLocalRef(vm_runtime);
    return false;
  }

  jobject runtime = env->CallStaticObjectMethod(vm_runtime, get_runtime);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    env->DeleteLocalRef(vm_runtime);
    return false;
  }

  env->CallVoidMethod(runtime, set_exemptions, prefixes);
  const bool threw = env->ExceptionCheck();
  if (threw) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }

  env->DeleteLocalRef(runtime);
  env->DeleteLocalRef(vm_runtime);
  return !threw;
}

}  // namespace aether
