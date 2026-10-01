# ระบบ GMS (Google Mobile Services) — จุดวิกฤตที่สุดของ AetherEngine

**เป้าหมาย:** `com.miniclip.eightballpool` (8 Ball Pool — Miniclip.com)

---

## 0. หลักฐานจากรายการ Permission จริงของเกม

ดึงจากแพ็กเกจที่เผยแพร่จริง (v55.10.1 / v56.x, targetSdk 34, arm64-v8a + armeabi-v7a):

| Permission | แปลว่าอะไร | ผลต่อคอนเทนเนอร์ |
|---|---|---|
| `com.android.vending.BILLING` | **ใช้ Play Billing จริง** | 🔴 IAP พังแน่นอน |
| `com.google.android.c2dm.permission.RECEIVE` | FCM/GCM push | 🔴 push ไม่ถึง |
| `com.miniclip.eightballpool.permission.C2D_MESSAGE` | FCM identity ผูกแพ็กเกจ | 🔴 ตามข้างบน |
| `com.google.android.gms.permission.AD_ID` | ต้องการ Advertising ID | 🟡 ใช้ได้ (per-device) |
| `ACCESS_ADSERVICES_AD_ID` / `_ATTRIBUTION` / `_TOPICS` | Privacy Sandbox / Topics API | 🟡 อาจคืนค่าผิดแพ็กเกจ |
| `com.applovin.array.apphub.permission.BIND_APPHUB_SERVICE` | **ใช้ AppLovin MAX** (ไม่ใช่แค่ AdMob) | 🟡 โฆษณา init อาจ fail |
| `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE` | Install Referrer ผ่าน Play Store | 🟡 ต้อง spoof |
| `com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA` | **มี code path สำหรับ Huawei AppGallery** | 🟢 ข่าวดีมาก |
| `PACKAGE_USAGE_STATS` | ตรวจสอบแอปอื่น | 🔴 เป็น third-party permission ต้องอนุญาตพิเศษ |
| `FOREGROUND_SERVICE`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS` | มาตรฐาน | 🟢 โฮสต์ต้องมีด้วย |

### ข้อสรุปที่สำคัญที่สุดจากตารางนี้

**เกมมี login หลักเป็น Miniclip ID และ Facebook** (ตามหน้า Play Store: *"Sign in for free with your
Miniclip or Facebook account"*) — **ไม่ใช่ Google Play Games**
และยังมี **Huawei AppGallery variant** ซึ่งหมายความว่าโค้ดของเกมมีเส้นทางสำหรับ
**อุปกรณ์ที่ไม่มี GMS อยู่แล้ว**

นี่เปลี่ยนข้อสรุปเชิงวิศวกรรมอย่างสิ้นเชิง:

> เกมนี้ **ไม่จำเป็นต้องมี GMS identity** ก็สามารถบูตและเล่นได้
> สิ่งที่จะพังคือ **IAP, FCM push, และ Play Integrity** — ไม่ใช่ตัวเกม

---

## 1. ปัญหาหลัก: Identity อยู่ที่ UID ไม่ใช่ที่ Context

```
แขกและโฮสต์อยู่ใน process เดียวกัน -> UID เดียวกัน
                                  -> PMS เห็นแพ็กเกจเดียวกัน
                                  -> GMS เห็น "dev.aether.host"
                                  -> ไม่ใช่ "com.miniclip.eightballpool"
```

`GoogleApiAvailability`, `GamesClient`, `BillingClient`, `FirebaseApp` ตรวจสอบตัวตนผ่าน
`PackageManagerService` (system_process) **ไม่ใช่ผ่าน Context ที่เราส่งให้**
การทำ `GuestContext.getPackageName()` ให้คืนค่าแขกจึงเป็นเงื่อนไขที่
**จำเป็นแต่ไม่พอ** — GMS จะถามกลับไปที่ PMS ด้วย UID ของโฮสต์แล้วพบว่าไม่ตรงกัน

---

## 2. สามระดับของ GMS Bridge

| Tier | ชื่อ | ทำได้ | ต้องการสิทธิ์ |
|------|------|-------|---------------|
| **T0** | `PASS_THROUGH` | เรียก GMS ได้จริง แต่เห็นตัวตนโฮสต์ | ไม่มี |
| **T1** | `IN_PROCESS_SPOOF` | หลอก SDK ที่ถาม `PackageManager` **ใน process เรา** | ไม่มี (แต่ต้อง unseal hidden API) |
| **T2** | `SIGNATURE_SPOOFING` | หลอกตัวตนต่อหน้า GMS จริง (microG / system app) | root หรือ system app |

---

## 3. เมทริกซ์ส่วนประกอบ (อัปเดตจากหลักฐานจริง)

| ส่วนประกอบ | สถานะในเกม | พฤติกรรมในคอนเทนเนอร์ | ความรุนแรง | Tier ที่พอ |
|---|---|---|---|---|
| GMS availability check | มี | ผ่าน (GMS ติดตั้งจริง) | 🟢 | T0 |
| **Miniclip ID / Facebook login** | **มี (หลัก)** | HTTPS + FB SDK → ทำงานได้ | 🟢 | T0 + VFS rule |
| Play Games sign-in | ไม่ใช่หลัก | ไม่ได้ | 🟡 | T2 |
| **Play Billing / IAP** | **มี (`vending.BILLING`)** | `BILLING_UNAVAILABLE` / `DEVELOPER_ERROR` | 🔴 | ❌ T2 |
| **FCM push** | **มี (C2D_MESSAGE)** | โทเค็นออกให้โฮสต์ | 🔴 | ❌ T2 |
| Advertising ID | มี | ใช้ได้จริง (per-device) | 🟢 | T0 |
| AppLovin MAX | มี | init อาจ fail ถ้าเช็คแพ็กเกจตัวเอง | 🟡 | T1 |
| Install Referrer | มี | ไม่ได้ค่าจาก Play Store | 🟡 | T1 |
| Firebase Analytics | มี | init ได้ ข้อมูลลง project ผิด | 🟡 | T1 |
| **Play Asset Delivery** | **เป็น App Bundle** | อาจขาด asset → จอดำ | 🔴 | ✅ T1 + splits |
| Play Integrity | อาจมี | attestation ระบุโฮสต์ → ไม่ผ่าน | 🔴 | ❌ T2 |
| Huawei AppGallery path | มี | **อาจเป็นทางรอด** | 🟢 | T0 |

---

## 4. สิ่งที่ต้องทำทันที (ทำได้จริง)

### 4.1 ติดตั้ง splits ทั้งหมดล่วงหน้า — สำคัญที่สุด
เกมเป็น App Bundle ถ้าขาด `split_config.arm64_v8a.apk` เกมจะบูตแล้วตายทันทีที่แตะ native lib
และถ้าใช้ Play Asset Delivery แล้วขาดแพ็ก → **จอดำ**

`PlayAssetDelivery.resolve()` → `Splits.dexPath` / `Splits.assetPaths`
ต้องถูกส่งให้ `DynamicApkLoader` และ `AssetManager.addAssetPath()` ตามลำดับ

### 4.2 อย่าปล่อยให้ SDK init ฆ่า process
`GmsFallback.shouldSuppress()` คัดกรอง exception กลุ่ม
`GooglePlayServicesNotAvailableException`, `AssetPackException`, `BillingResult` ฯลฯ
เพื่อให้ crash guard ลดระดับเป็น warning แทน

`GmsFallback.isExpectedFailure(code)` แมปรหัส `ConnectionResult`:
- `4 SIGN_IN_REQUIRED` · `10 DEVELOPER_ERROR` · `16 API_UNAVAILABLE` · `6 RESOLUTION_REQUIRED` · `17 SIGN_IN_FAILED`
= **คาดหมายได้ในคอนเทนเนอร์** ให้ log warning แล้วไปต่อ

### 4.3 หลอกแพ็กเกจให้ SDK ที่ถามใน process (T1)
`IdentitySpoof` แทนที่ `ActivityThread.sPackageManager` ด้วย dynamic proxy
ตอบ `getPackageInfo` / `getApplicationInfo` สำหรับแพ็กเกจแขก
→ ช่วย AppLovin, Firebase init, Install Referrer และ SDK ที่ throw เมื่อได้ null

### 4.4 นโยบาย VFS ที่เกี่ยวข้อง (ใส่แล้วใน profile)
```toml
{ template = "/sdcard/Android/data/com.facebook.katana", action = "readonly" }
{ template = "/sdcard/Android/data/com.facebook.orca",   action = "readonly" }
{ template = "/data/data/com.miniclip.eightballpool/app_dumps", action = "shadow", bucket = "cache" }
```

---

## 5. สิ่งที่ยอมรับว่า "ทำไม่ได้" โดยไม่มีสิทธิ์ระบบ

1. **Play Billing** — IAP ผูกกับ package + signature ที่ลงทะเบียนไว้
   เปลี่ยน cert แล้วคำขอถูกปฏิเสธที่ระดับเซิร์ฟเวอร์ ไม่ใช่แค่ client
2. **FCM** — โทเค็นผูก package + sender id
3. **Play Integrity** — ออกแบบมาเพื่อตรวจจับสภาพแวดล้อมแบบนี้โดยตรง
   การพยายาม bypass เป็นการละเมิดเงื่อนไขการให้บริการของ Google

> **จุดยืนของโปรเจกต์นี้:** เราไม่สร้างเครื่องมือปลอมแปลงตัวตนเพื่อเลี่ยงการชำระเงิน
> หรือเลี่ยงการตรวจสอบความสมบูรณ์ เป้าหมายคือทำให้เกม **รันได้** บนเครื่องของผู้ใช้
> และ **แจ้งอย่างตรงไปตรงมา** ว่าฟีเจอร์ใดจะไม่ทำงาน

---

## 6. สรุปคำแนะนำสำหรับเป้าหมายนี้

1. เปิด **T0 + T1** เป็นค่าเริ่มต้น
2. **ติดตั้ง splits ทั้งหมด** ล่วงหน้า (สำคัญที่สุด — ขาดแล้วจอดำหรือตายที่ native lib)
3. ทำให้ SDK init **ไม่ crash**
4. ล็อกอิน **Miniclip / Facebook ควรใช้ได้** (นี่คือข่าวดี — ไม่ต้องพึ่ง GMS identity)
5. แสดงข้อความใน UI ว่า **IAP, FCM push, Cloud Save จะไม่ทำงาน**
6. หากต้องการให้มันทำงานจริง → ต้องย้ายไป T2 ซึ่งต้องการสิทธิ์ระดับระบบ
