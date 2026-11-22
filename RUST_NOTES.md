# ATV Basic 5.0 — arayüz dışındaki kodun Rust'a taşınması

**Değişmeyenler:** `LauncherActivity`, `AppEntry`, `IconLoader`, `HomeRole`, `Notice*`, tüm `res/` (düzen, renk, metin, simge), `AndroidManifest.xml`.
Kotlin sınıflarının dışa açık API'si aynı kaldı (`ShareService.isOn/start/stop/running/onChange`, `WallpaperStore.stamp/load/saveToDownloads`,
`WallpaperSource.fetchAndSet`), bu yüzden ana ekran koduna dokunulmadı.

**Rust'a taşınanlar (`native/`, `libatvnative.so`):**
- `sender.rs`, `share_jni.rs`: alma sunucusu ve gönderme (MyL ile ORTAK `qshare_core`)
- `files.rs`: dosya seçici listeleme, `OpenProvider` için güvenli yol çözümleme
- `wallpaper.rs`, `json.rs`: Wallhaven yanıtı ayrıştırma, adres havuzu (60 dk), ölçekleme (doldur/sığdır), atomik JPEG yazma, sistem duvar kağıdı görüntüsü, değişim damgası

**Kotlin'de kalması gerekenler (Android API'si şart):** mDNS (NsdManager), bildirimler, Wi-Fi/CPU kilitleri, depolama izinleri ve MediaStore,
`content://` çözümleme, HTTPS (Rust std'de TLS yok; `HttpURLConnection`), `WallpaperManager`, `PackageManager` (uygulama listesi, simgeler).

**Davranış farkları (MyL ile uyum için):**
- Aynı adlı gelen dosya artık üzerine yazılmaz; `ad_1.uzantı` olur.
- Alma sırasında yüzde yerine belirsiz ilerleme çubuğu gösterilir (sunucu olayları yüzde taşımaz).
- Android 10'da "tüm dosyalar" izni yokken dosya önce uygulamanın gelen kutusuna yazılır, sonra MediaStore ile İndirilenler'e taşınır.
- Gönderimde `content://` kaynağı düz bir dosyaysa açık tanıtıcı olarak Rust'a devredilir; aranamayan kaynaklar (boru vb.) önce geçici dosyaya kopyalanır.
- Link-local IPv6 cihaz adresleri atlanır (IPv4 tercih edilir).
- APK'ya `armeabi-v7a` da eklendi (birçok Android TV kutusu 32 bit).

**Doğrulama durumu:** `qshare_core` (14 test), `json.rs`, `files.rs`, `sender.rs` ve `wallpaper.rs`'in görüntü dışı mantığı Rust ile derlenip test edildi.
`jni` ve `image` crate'lerine bağlı kod (JNI giriş noktaları, ölçekleme/JPEG) ile Kotlin ve Gradle tarafı bu ortamda (crates.io ve Android SDK yok) **derlenmedi**;
ilk CI çalıştırmasında derleme hataları çıkabilir.
