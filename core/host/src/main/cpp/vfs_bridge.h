#pragma once

#include <jni.h>

namespace aether {
namespace vfs {

/**
 * Thin C++ shim over the `aether-vfs` C ABI.
 *
 * Why a shim instead of calling Rust directly from Kotlin: the Rust crate
 * exposes a C ABI (handle + two-step buffer protocol) that is awkward from
 * JNI, and we want a single place that owns `GetStringUTFChars` /
 * `NewStringUTF` lifetimes. All policy decisions stay in Rust — C++ performs
 * no path logic whatsoever.
 */

// Error codes mirrored from core/vfs/src/ffi.rs. Keep in sync.
enum ErrorCode {
  kOk = 0,
  kInvalidHandle = -1,
  kNullPointer = -2,
  kInvalidUtf8 = -3,
  kDenied = -4,
  kMalformed = -5,
  kBufferTooSmall = -6,
  kBadArgument = -7,
  kPanic = -99,
};

/** Shared-media policy, mirrored from aether_vfs::SharedMedia. */
enum SharedMedia { kPassthrough = 0, kIsolate = 1, kDeny = 2 };

int64_t Create(JNIEnv* env, jstring guest_package, jstring host_package,
               jstring host_data_dir, jint user_id, jboolean strict,
               jint shared_media);

void Destroy(int64_t handle);

/** Returns a new local jstring, or nullptr when the path is denied. */
jstring ToHost(JNIEnv* env, int64_t handle, jstring guest_path);

/** Returns a new local jstring, or nullptr when unmapped. Empty string = keep host path. */
jstring ToGuest(JNIEnv* env, int64_t handle, jstring host_path);

jboolean Check(JNIEnv* env, int64_t handle, jstring guest_path, jboolean write);

jint AddRule(JNIEnv* env, int64_t handle, jstring template_str, jint action,
             jstring bucket);

jint AddLink(JNIEnv* env, int64_t handle, jstring from, jstring to);

/** Most recent error for this thread, as a local jstring. Never null. */
jstring LastError(JNIEnv* env);

int AbiVersion();

}  // namespace vfs
}  // namespace aether
