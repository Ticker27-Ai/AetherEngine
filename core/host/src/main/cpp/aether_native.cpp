#include <jni.h>

#include <cstdint>
#include <iterator>
#include <vector>

#include "hidden_api.h"
#include "log.h"
#include "native_extract.h"
#include "vfs_bridge.h"

namespace {

constexpr const char* kAetherNativeClass = "dev/aether/host/native/AetherNative";

using aether::AndroidApiLevel;

jintArray NativeUnsealHiddenApi(JNIEnv* env, jclass, jobject probe) {
  const aether::UnsealReport report = aether::UnsealHiddenApi(env, probe);

  // [strategy, apiLevel, clearedCount, probes, offset0, offset1, offset2, offset3]
  const jint out[8] = {
      static_cast<jint>(report.strategy),
      report.api_level,
      static_cast<jint>(report.offsets_cleared),
      static_cast<jint>(report.probes),
      static_cast<jint>(report.offsets[0]),
      static_cast<jint>(report.offsets[1]),
      static_cast<jint>(report.offsets[2]),
      static_cast<jint>(report.offsets[3]),
  };
  jintArray result = env->NewIntArray(8);
  if (result != nullptr) {
    env->SetIntArrayRegion(result, 0, 8, out);
  }
  return result;
}

jboolean NativeSetHiddenApiExemptions(JNIEnv* env, jclass, jobjectArray prefixes) {
  return aether::SetHiddenApiExemptions(env, prefixes) ? JNI_TRUE : JNI_FALSE;
}

jint NativeApiLevel(JNIEnv*, jclass) { return AndroidApiLevel(); }

jstring NativeCurrentAbi(JNIEnv* env, jclass) {
  return env->NewStringUTF(aether::CurrentAbi());
}

jboolean NativeMarkExecutable(JNIEnv* env, jclass, jstring path) {
  if (path == nullptr) return JNI_FALSE;
  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) return JNI_FALSE;
  const bool ok = aether::MarkExecutable(chars);
  env->ReleaseStringUTFChars(path, chars);
  return ok ? JNI_TRUE : JNI_FALSE;
}

/** [valid, elfClass, machine, type] */
jintArray NativeProbeElf(JNIEnv* env, jclass, jstring path) {
  const jint failure[4] = {0, 0, 0, 0};
  jintArray result = env->NewIntArray(4);
  if (result == nullptr) return nullptr;
  if (path == nullptr) {
    env->SetIntArrayRegion(result, 0, 4, failure);
    return result;
  }

  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) {
    env->SetIntArrayRegion(result, 0, 4, failure);
    return result;
  }
  const aether::ElfProbe probe = aether::ProbeElf(chars);
  env->ReleaseStringUTFChars(path, chars);

  const jint out[4] = {probe.valid ? 1 : 0, probe.elf_class, probe.machine, probe.type};
  env->SetIntArrayRegion(result, 0, 4, out);
  return result;
}

// ---------------------------- VFS shim -------------------------------------

jlong NativeVfsCreate(JNIEnv* env, jclass, jstring guest_package,
                      jstring host_package, jstring host_data_dir, jint user_id,
                      jboolean strict, jint shared_media) {
  return static_cast<jlong>(aether::vfs::Create(env, guest_package, host_package,
                                                host_data_dir, user_id, strict,
                                                shared_media));
}

void NativeVfsDestroy(JNIEnv*, jclass, jlong handle) {
  aether::vfs::Destroy(static_cast<int64_t>(handle));
}

jstring NativeVfsToHost(JNIEnv* env, jclass, jlong handle, jstring guest_path) {
  return aether::vfs::ToHost(env, static_cast<int64_t>(handle), guest_path);
}

jstring NativeVfsToGuest(JNIEnv* env, jclass, jlong handle, jstring host_path) {
  return aether::vfs::ToGuest(env, static_cast<int64_t>(handle), host_path);
}

jboolean NativeVfsCheck(JNIEnv* env, jclass, jlong handle, jstring guest_path,
                        jboolean write) {
  return aether::vfs::Check(env, static_cast<int64_t>(handle), guest_path, write);
}

jint NativeVfsAddRule(JNIEnv* env, jclass, jlong handle, jstring template_str,
                      jint action, jstring bucket) {
  return aether::vfs::AddRule(env, static_cast<int64_t>(handle), template_str,
                              action, bucket);
}

jint NativeVfsAddLink(JNIEnv* env, jclass, jlong handle, jstring from, jstring to) {
  return aether::vfs::AddLink(env, static_cast<int64_t>(handle), from, to);
}

jstring NativeVfsLastError(JNIEnv* env, jclass) { return aether::vfs::LastError(env); }

jint NativeVfsAbiVersion(JNIEnv*, jclass) { return aether::vfs::AbiVersion(); }

constexpr JNINativeMethod kMethods[] = {
    {"unsealHiddenApi", "(Ldev/aether/host/native/HiddenApiProbe;)[I",
     reinterpret_cast<void*>(&NativeUnsealHiddenApi)},
    {"setHiddenApiExemptions", "([Ljava/lang/String;)Z",
     reinterpret_cast<void*>(&NativeSetHiddenApiExemptions)},
    {"apiLevel", "()I", reinterpret_cast<void*>(&NativeApiLevel)},
    {"currentAbi", "()Ljava/lang/String;", reinterpret_cast<void*>(&NativeCurrentAbi)},
    {"markExecutable", "(Ljava/lang/String;)Z",
     reinterpret_cast<void*>(&NativeMarkExecutable)},
    {"probeElf", "(Ljava/lang/String;)[I", reinterpret_cast<void*>(&NativeProbeElf)},

    {"vfsCreate",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZI)J",
     reinterpret_cast<void*>(&NativeVfsCreate)},
    {"vfsDestroy", "(J)V", reinterpret_cast<void*>(&NativeVfsDestroy)},
    {"vfsToHost", "(JLjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(&NativeVfsToHost)},
    {"vfsToGuest", "(JLjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(&NativeVfsToGuest)},
    {"vfsCheck", "(JLjava/lang/String;Z)Z", reinterpret_cast<void*>(&NativeVfsCheck)},
    {"vfsAddRule", "(JLjava/lang/String;ILjava/lang/String;)I",
     reinterpret_cast<void*>(&NativeVfsAddRule)},
    {"vfsAddLink", "(JLjava/lang/String;Ljava/lang/String;)I",
     reinterpret_cast<void*>(&NativeVfsAddLink)},
    {"vfsLastError", "()Ljava/lang/String;", reinterpret_cast<void*>(&NativeVfsLastError)},
    {"vfsAbiVersion", "()I", reinterpret_cast<void*>(&NativeVfsAbiVersion)},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass clazz = env->FindClass(kAetherNativeClass);
  if (clazz == nullptr) {
    LOGE("JNI_OnLoad: cannot find %s", kAetherNativeClass);
    return JNI_ERR;
  }

  const jint registered = env->RegisterNatives(
      clazz, kMethods, static_cast<jint>(std::size(kMethods)));
  env->DeleteLocalRef(clazz);
  if (registered != JNI_OK) {
    LOGE("JNI_OnLoad: RegisterNatives failed (%d)", registered);
    return JNI_ERR;
  }

  LOGI("aether-native loaded (API %d, %zu methods)", AndroidApiLevel(),
       std::size(kMethods));
  return JNI_VERSION_1_6;
}
