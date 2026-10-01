#include "vfs_bridge.h"

#include <cstdint>
#include <string>

#include "log.h"

// Declared by core/vfs (see core/vfs/src/ffi.rs). We do not ship a generated
// header: the ABI is small, stable and asserted at runtime via
// aether_vfs_abi_version().
extern "C" {

int64_t aether_vfs_create(const char* guest_package, const char* host_package,
                          const char* host_data_dir, int user_id, int strict,
                          int shared_media);
int aether_vfs_destroy(uint64_t handle);
int64_t aether_vfs_to_host_len(uint64_t handle, const char* guest_path);
int aether_vfs_to_host(uint64_t handle, const char* guest_path, char* out,
                       size_t out_cap);
int aether_vfs_to_guest(uint64_t handle, const char* host_path, char* out,
                        size_t out_cap);
int aether_vfs_check(uint64_t handle, const char* guest_path, int write_mode);
int aether_vfs_add_rule(uint64_t handle, const char* template_str, int action,
                        const char* bucket);
int aether_vfs_add_link(uint64_t handle, const char* from, const char* to);
int aether_vfs_last_error(char* out, size_t out_cap);
int aether_vfs_abi_version(void);

}  // extern "C"

namespace aether {
namespace vfs {
namespace {

constexpr int kExpectedAbiVersion = 1;

/** RAII wrapper for GetStringUTFChars / ReleaseStringUTFChars. */
class Utf8 {
 public:
  Utf8(JNIEnv* env, jstring value) : env_(env), value_(value) {
    chars_ = (value_ != nullptr) ? env_->GetStringUTFChars(value_, nullptr) : nullptr;
  }
  ~Utf8() {
    if (chars_ != nullptr && value_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }
  const char* get() const { return chars_; }
  bool ok() const { return chars_ != nullptr; }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_;
};

/** Two-step call: query the required size, then fetch. */
int Fetch(int64_t handle, const char* in,
          int64_t (*size_fn)(uint64_t, const char*),
          int (*copy_fn)(uint64_t, const char*, char*, size_t),
          std::string* out) {
  const int64_t needed = size_fn(static_cast<uint64_t>(handle), in);
  if (needed <= 0) return static_cast<int>(needed);

  std::string buffer(static_cast<size_t>(needed), '\0');
  const int rc = copy_fn(static_cast<uint64_t>(handle), in, buffer.data(),
                         buffer.size());
  if (rc != kOk) return rc;
  // The Rust side NUL-terminates; trim to the first NUL for std::string.
  out->assign(buffer.c_str());
  return kOk;
}

}  // namespace

int64_t Create(JNIEnv* env, jstring guest_package, jstring host_package,
               jstring host_data_dir, jint user_id, jboolean strict,
               jint shared_media) {
  const int abi = AbiVersion();
  if (abi != kExpectedAbiVersion) {
    LOGE("aether-vfs ABI mismatch: native=%d expected=%d", abi, kExpectedAbiVersion);
    return ErrorCode::kPanic;
  }

  const Utf8 guest(env, guest_package);
  const Utf8 host(env, host_package);
  const Utf8 dir(env, host_data_dir);
  if (!guest.ok() || !host.ok() || !dir.ok()) return ErrorCode::kInvalidUtf8;

  return aether_vfs_create(guest.get(), host.get(), dir.get(), user_id,
                           strict == JNI_TRUE ? 1 : 0, shared_media);
}

void Destroy(int64_t handle) {
  if (handle > 0) aether_vfs_destroy(static_cast<uint64_t>(handle));
}

jstring ToHost(JNIEnv* env, int64_t handle, jstring guest_path) {
  const Utf8 in(env, guest_path);
  if (!in.ok()) return nullptr;

  std::string out;
  const int rc = Fetch(handle, in.get(), &aether_vfs_to_host_len,
                       &aether_vfs_to_host, &out);
  if (rc != kOk) {
    LOGW("vfs_to_host denied/failed (%d)", rc);
    return nullptr;
  }
  return env->NewStringUTF(out.c_str());
}

jstring ToGuest(JNIEnv* env, int64_t handle, jstring host_path) {
  const Utf8 in(env, host_path);
  if (!in.ok()) return nullptr;

  // Worst case: a reverse mapping is never longer than the input plus the
  // guest prefix. 1 KiB is ample and avoids a second FFI round trip for the
  // common case.
  char buffer[1024] = {0};
  const int rc = aether_vfs_to_guest(static_cast<uint64_t>(handle), in.get(),
                                     buffer, sizeof(buffer));
  if (rc != kOk) return nullptr;
  return env->NewStringUTF(buffer);
}

jboolean Check(JNIEnv* env, int64_t handle, jstring guest_path, jboolean write) {
  const Utf8 in(env, guest_path);
  if (!in.ok()) return JNI_FALSE;
  const int rc = aether_vfs_check(static_cast<uint64_t>(handle), in.get(),
                                  write == JNI_TRUE ? 1 : 0);
  return rc == kOk ? JNI_TRUE : JNI_FALSE;
}

jint AddRule(JNIEnv* env, int64_t handle, jstring template_str, jint action,
             jstring bucket) {
  const Utf8 tpl(env, template_str);
  const Utf8 bucket_str(env, bucket);
  if (!tpl.ok() || !bucket_str.ok()) return ErrorCode::kInvalidUtf8;
  return aether_vfs_add_rule(static_cast<uint64_t>(handle), tpl.get(), action,
                             bucket_str.get());
}

jint AddLink(JNIEnv* env, int64_t handle, jstring from, jstring to) {
  const Utf8 f(env, from);
  const Utf8 t(env, to);
  if (!f.ok() || !t.ok()) return ErrorCode::kInvalidUtf8;
  return aether_vfs_add_link(static_cast<uint64_t>(handle), f.get(), t.get());
}

jstring LastError(JNIEnv* env) {
  char buffer[512] = {0};
  aether_vfs_last_error(buffer, sizeof(buffer));
  return env->NewStringUTF(buffer);
}

int AbiVersion() { return aether_vfs_abi_version(); }

}  // namespace vfs
}  // namespace aether
