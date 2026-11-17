<p align="center"><img src="docs/icon-512.png" width="160" alt="NeonDrive icon"></p>

<h1 align="center">NeonDrive</h1>
<p align="center">محاكي سيكا احترافي للأندرويد بثيم نيون — Mega Drive / Genesis · Master System · Game Gear · SG-1000</p>

---

## المميزات (النسخة 0.1)

| الميزة | التفاصيل |
|---|---|
| 🎮 محرك دقيق | Genesis Plus GX عبر LibretroDroid (OpenGL ES 2) |
| ⚡ بروفايل أداء تلقائي | يقرأ المعالج وكارت الشاشة (GL_RENDERER) والرام ويصنف الجهاز (اقتصادي / متوسط / عالي) ويختار الفلتر المناسب |
| 🖼️ فلاتر الصورة | بكسل، حاد، تنعيم ذكي CUT2، دقة فائقة CUT3، CRT، LCD |
| 📺 شاشة عريضة | نواة Genesis Plus GX Wide بنسبة 16:9 (تجريبي) |
| 🔎 العثور على الألعاب | فحص المجلدات (وحتى ملفات zip)، والتعرف على كل لعبة ببصمة CRC32 من قاعدة No-Intro |
| 🖼️ الأغلفة | تحميل تلقائي من libretro-thumbnails |
| 🧩 جيتات أونلاين | تحميل أكواد Game Genie / Action Replay من libretro-database مع إمكانية إضافة أكواد يدوية |
| 💾 الحفظ | 4 خانات حفظ لحظي + حفظ تلقائي للـ SRAM |
| ⏪ الرجوع بالزمن | Rewind بذاكرة تتحدد حسب قوة الجهاز |
| ⏩ تسريع | ×2 أو ×4 حسب الجهاز |
| 🕹️ التحكم | أزرار لمس نيون (3 أو 6 أزرار) مع اهتزاز وتحكم بالشفافية والحجم + دعم أيادي البلوتوث وإخفاء أزرار اللمس تلقائياً |

**الحد الأدنى:** أندرويد 7.0 (API 24) · المعماريات: arm64-v8a و armeabi-v7a

## البناء

### تلقائياً على GitHub (بدون كمبيوتر قوي)
كل `push` على فرع `main` يبني APK تلقائياً. تلقاه بتبويب **Actions** ← آخر تشغيل ← **Artifacts**.
لنشر إصدار رسمي: سوِّ tag مثل `v0.1.0` وراح ينزل الـ APK بصفحة **Releases**.

### محلياً
```bash
bash scripts/fetch_cores.sh     # يحمّل نواة Genesis Plus GX لكل معمارية
./gradlew assembleRelease
```

### توقيع النسخة (اختياري)
أضف هذي الأسرار بإعدادات المستودع ← Secrets ← Actions:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
بدونها يتوقّع بمفتاح debug (يكفي للتجربة).

## هيكل المشروع
```
app/src/main/java/com/neondrive/emu/
├── MainActivity.kt            الشاشة الرئيسية
├── data/                      المكتبة، قاعدة البيانات (CRC)، الجيتات، الإعدادات
├── device/DeviceProfiler.kt   فحص المعالج وكارت الشاشة واختيار الفلتر
├── game/                      شاشة اللعب، أزرار اللمس، قائمة الإيقاف
└── ui/                        الثيم النيون، المكتبة، الإعدادات
```

## الخطة القادمة
- [ ] Sega CD و 32X
- [ ] RetroAchievements
- [ ] مزامنة الحفظ مع Google Drive
- [ ] لعب أونلاين (Netplay)
- [ ] محرر تخطيط أزرار اللمس بالسحب
- [ ] صورة مصغرة لكل خانة حفظ

## الترخيص والملاحظات القانونية
- الكود مرخّص **GPL-3.0-or-later** (لأن LibretroDroid مرخصة GPLv3).
- نواة **Genesis Plus GX** مرخصة للاستخدام **غير التجاري** فقط، لذلك التطبيق مجاني ولا يُباع.
- التطبيق **لا يحتوي على أي ألعاب أو BIOS**. المستخدم يضيف نسخه الخاصة بنفسه.
- SEGA و Genesis و Mega Drive علامات تجارية لشركة SEGA، والمشروع غير مرتبط بها.

## شكر
[LibretroDroid](https://github.com/Swordfish90/LibretroDroid) · [Genesis Plus GX](https://github.com/libretro/Genesis-Plus-GX) · [libretro-database](https://github.com/libretro/libretro-database) · [libretro-thumbnails](https://thumbnails.libretro.com)
