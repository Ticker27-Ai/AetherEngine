# AetherEngine — PHASE 0 & PHASE 1

**เป้าหมาย:** `com.miniclip.eightballpool` (8 Ball Pool — Miniclip.com)
**สถาปัตยกรรม:** In-Process Virtualization (เปลี่ยนจาก "สอดส่องจากข้างนอก" เป็น "ควบคุมจากข้างใน")

---

## 1. สถานะความพร้อม (อ่านก่อนเชื่ออย่างอื่น)

| ชั้น | ภาษา | สถานะ | การตรวจสอบ |
|---|---|---|---|
| VFS policy engine | Rust | ✅ สมบูรณ์ | **46 tests ผ่าน** (รันจริงบนเครื่อง) |
| Native bridge (unseal/ELF/VFS) | C++ | ⚠️ เขียนครบ | ยังไม่เคยคอมไพล์ (ต้อง NDK) |
| Layer 1 — The Host | Kotlin | ⚠️ เขียนครบ | ยังไม่เคยคอมไพล์ (ต้อง Android SDK) |
| Build wiring | Gradle/CMake | ⚠️ เขียนครบ | ยังไม่เคยรัน |
| ความสอดคล้องข้ามภาษา | — | ✅ ผ่าน | `check-readiness.py` 16/16 |

> **พูดตรง ๆ:** โค้ด Kotlin และ C++ ใน repo นี้ **ยังไม่เคยผ่านคอมไพเลอร์เลย**
> เพราะ environment นี้ไม่มี Android SDK/NDK สิ่งที่พิสูจน์ได้คือ (ก) Rust 46 tests ผ่านจริง
> และ (ข) การตรวจสอบเชิงโครงสร้าง/ข้ามภาษา 16 ข้อผ่าน
> **ต้องรัน `./gradlew assembleDebug` บนเครื่องที่มี SDK 35 + NDK r27c เท่านั้นถึงจะรู้ว่าคอมไพล์จริง**

---

## 2. วิธีประกอบ (assemble)

```bash
# 0) ตรวจสอบความพร้อมเชิงโครงสร้าง
python3 tools/check-readiness.py          # exit 0 = ผ่าน

# 1) Rust policy engine -> staticlib ทุก ABI
./tools/build-rust.sh --release --test

# 2) Android (ต้องการ ANDROID_HOME + NDK r27c)
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:host:assembleDebug
./gradlew :app:assembleDebug

# 3) ติดตั้ง APK ของเป้าหมาย (ต้องเป็นทุก splits!)
adb shell mkdir -p /sdcard/Aether/base
adb push base.apk split_config.arm64_v8a.apk /sdcard/Aether/base/
```

### ⚠️ สิ่งที่จะทำให้ประกอบไม่ผ่าน (เจอบ่อย)
1. **ไม่รัน `build-rust.sh` ก่อน** → CMake `FATAL_ERROR: AETHER_VFS_LIB not found`
2. **NDK ไม่ใช่ r27c** → `build.gradle.kts` ล็อกเวอร์ชันไว้กับ CI
3. **Gradle daemon หน่วยความจำน้อย** → `gradle.properties` ให้ `-Xmx4096m` ไว้แล้ว

---

## 3. PHASE 0 — CI/CD & Monorepo

```
aether-engine/
├── settings.gradle.kts          # 4 ภาษาใน repo เดียว
├── build.gradle.kts             # + checkVfs, dumpVfsPolicy
├── gradle/libs.versions.toml    # AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35
├── .github/workflows/
│   ├── ci.yml                   # rust + android + device matrix (API 28/30/33/35)
│   ├── native.yml               # cargo-ndk ข้าม ABI
│   └── release.yml              # signed AAB/APK + SHA256SUMS
├── core/vfs/         (Rust)     # policy engine — หัวใจด้านความปลอดภัย
├── core/host/        (Kotlin+C++)
├── app/              (Kotlin)   # shell บาง ๆ
├── guests/           (TOML)     # per-game profile
├── tools/                       # build-rust.sh, gen-stubs, check-readiness.py
└── docs/
```

**ทำไม Rust ถึงเป็น job ที่เข้มงวดที่สุดใน CI:** มันคือ security boundary
— กฎผิดหนึ่งข้อไม่ใช่ crash แต่คือ **ข้อมูลรั่วเงียบ** จึงมี `fmt --check`, `clippy -D warnings`,
และ golden file `docs/vfs-policy.txt`

---

## 4. PHASE 1 — The Host

### 4.1 DynamicApkLoader
ลำดับการทำงาน **สลับไม่ได้**:

1. **unseal hidden API** — ทุกขั้นต่อไปใช้ blacklisted member
2. **resolve + verify splits** — 8 Ball Pool เป็น App Bundle
   ขาด `split_config.arm64_v8a.apk` = บูตติดแล้วตายที่ native lib
3. **extract native libs** — `dlopen` จากใน APK ไม่ได้ตั้งแต่ API 24;
   ต้องแตกลง data dir ของโฮสต์ (linker namespace)
4. **GuestClassLoader** — child-first ยกเว้น platform package
   (ไม่งั้นแขกได้ library เวอร์ชันของโฮสต์)
5. **Resources** — `addAssetPath()` **ทุก** split ไม่ใช่แค่ base
6. **PackageParserHidden** — หา `parsePackage` ด้วยการลองทุก signature
   (API 21-33+ ไม่เหมือนกัน) แทนที่จะ branch ตาม SDK_INT
7. **VfsRouter** จาก profile
8. **Application** ผ่าน `Instrumentation.newApplication()`

### 4.2 VirtualActivity
ระบบสตาร์ทได้แค่ Activity ที่ประกาศใน manifest จริง → ใช้ **stub pool ที่ generate**
(27 ตัว: standard 12 / singleTop 6 / singleTask 3 / singleInstance 2 / transparent 4)
เพราะ `launchMode`, `theme`, `configChanges` ถูกอ่านจาก manifest entry **ก่อนโค้ดเราทำงาน**

`ActivityAttacher` แก้ปัญหา `Activity.attach()` ที่ signature เปลี่ยนทุกเวอร์ชัน
โดย **resolve parameter ตาม type** ไม่ใช่ตามตำแหน่ง

### 4.3 HostInitializer
1. unseal → 2. Instrumentation (rewrite `startActivity` ไป stub)
3. `IActivityManager` proxy → 4. `mH` callback (API 28+ `EXECUTE_TRANSACTION`)

ทุก hook เป็น best-effort: ล้มเหลวแล้ว log แล้วไปต่อ (degraded mode ดีกว่าไม่สตาร์ท)

---

## 5. สิ่งที่ยังต้องทำ

- [ ] **คอมไพล์จริง** บนเครื่องที่มี SDK/NDK (บล็อกอยู่ — ต้องทำก่อนอย่างอื่น)
- [ ] Phase 2: IO redirection (`libc open/stat` hooks) — ตอนนี้ VFS ยังไม่ถูกบังคับใช้
- [ ] `GuestContext`: `startActivity` / `bindService` / `getSystemService`
- [ ] ContentProvider proxy, BroadcastReceiver proxy
- [ ] Flutter shell (`ui/flutter`) — ตอนนี้มีแค่โครง
- [ ] T2 GMS (signature spoofing) — ต้องสิทธิ์ระบบ

---

## 6. อ่านต่อ

- `docs/GMS-ARCHITECTURE.md` — ทำไม IAP/FCM ถึงทำไม่ได้ และทำไมเกมยังเล่นได้
- `docs/vfs-policy.txt` — กฎ isolation ทั้งหมด (golden file)
- `guests/com.miniclip.eightballpool.toml` — profile ของเป้าหมาย
