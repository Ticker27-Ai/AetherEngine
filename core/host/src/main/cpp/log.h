#pragma once

#include <android/log.h>

#ifndef AETHER_LOG_TAG
#define AETHER_LOG_TAG "AetherNative"
#endif

// The native layer runs in the guest's critical path (class loading, IO
// redirection). Logging is compiled out of release builds entirely rather than
// merely silenced, so a hot loop cannot be slowed by log formatting.
#ifdef NDEBUG
#define AETHER_LOG(prio, ...) ((void)0)
#else
#define AETHER_LOG(prio, ...) \
  __android_log_print(prio, AETHER_LOG_TAG, __VA_ARGS__)
#endif

#define LOGV(...) AETHER_LOG(ANDROID_LOG_VERBOSE, __VA_ARGS__)
#define LOGD(...) AETHER_LOG(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define LOGI(...) AETHER_LOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) AETHER_LOG(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) AETHER_LOG(ANDROID_LOG_ERROR, __VA_ARGS__)

// Abort with a diagnostic in debug builds; degrade silently in release.
#ifdef NDEBUG
#define AETHER_FATAL(...) ((void)0)
#else
#define AETHER_FATAL(...)                       \
  do {                                          \
    LOGE(__VA_ARGS__);                          \
    __android_log_assert(nullptr, AETHER_LOG_TAG, __VA_ARGS__); \
  } while (0)
#endif
