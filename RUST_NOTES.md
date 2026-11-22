# ATV Basic 5.0 — arayüz dışındaki kodun Rust'a taşınması

**Değişmeyenler:** `LauncherActivity`, `AppEntry`, `IconLoader`, `HomeRole`, `Notice*`, tüm `res/` (düzen, renk, metin, simge), `AndroidManifest.xml`.
Kotlin sınıflarının dışa açık API'si aynı kaldı (`ShareService.isOn/start/stop/running/onChange`, `WallpaperStore.stamp/load/saveToDownloads`,
`WallpaperSource.fetchAndSet`), bu yüzden ana ekran koduna dokunulmadı.

**Rust'a taşınanlar (`native/`, `libatvnative.so`):**
- `sender.rs`, `share_jni.rs`: alma sunucusu ve gönderme (MyL ile ORTAK `qshare_core`)
- `files.rs`: dosya seçici listeleme, `OpenProvider` için güvenli yol çözümleme
- `wallpaper.rs`, `json.rs`: Wallhaven yanıtı ayrıştırma, adres havuzu (60 dk), ölçekleme (doldur/sığdır), atomik JPEG yazma, sistem duvar kağıdı görüntüsü, değişim damgası

**Kotlin'de kalması gerekenler (Android API'si şart):** mDNS YAYINI (NsdManager `registerService`), bildirimler, Wi-Fi/CPU kilitleri, depolama izinleri ve MediaStore,
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

## Sonradan eklenenler (hız çalışması)

- **Alma sunucusu (`qshare_core/share/server.rs`):** 40 ms'lik `sleep` yoklaması kalktı; bloklayan `accept` kullanılır, durdururken sunucu kendine bağlanıp uyanır (boşta uyanma ve 0-40 ms gecikme yok).
- **Cihaz bulma (`qshare_core/mdns.rs`, `Native.mdnsBrowse`):** tek UDP soketiyle `_qshare._tcp` taranır, bulunan tüm cihazların SRV/TXT/A kayıtları paralel istenir (NsdManager aynı anda tek çözümlemeye izin verir).
  5353 portuna bağlanılmaz; sorgu 5353 dışı kaynak portundan gider ve yanıtlayıcılar RFC 6762 §6.7'ye göre doğrudan bize yanıt verir. Yalnızca IPv4.
  `ShareSendService.Job` bunu NsdManager'ın YANINDA çalıştırır (yerine değil): hiçbir şey bulunamazsa NsdManager sonuçları geçerlidir; aynı `ip:port` iki kez gönderilmez.
  Tarama boyunca `MulticastLock` tutulur (manifestte `CHANGE_WIFI_MULTICAST_STATE` zaten var). Yayın (duyuru) hâlâ NsdManager'dadır.

**Doğrulama durumu (bu ekleme için):** `mdns.rs` ve `server.rs` önceki oturumda `cargo test` ile geçmişti; bu oturumda Rust derleyicisi ve Android SDK'sı yoktu,
dosyalar yeniden uygulandı ve yalnızca elle gözden geçirildi. JNI girişleri ve Kotlin tarafı derlenmedi. İlk CI çalıştırmasını izleyin;
gerçek cihazda (özellikle multicast sorunlu TV kutularında) `adb logcat` ile keşfin NsdManager'dan önce/sonra bulduğunu doğrulayın.
