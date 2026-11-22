appops set io.github.idrisokys.atvbasic MANAGE_EXTERNAL_STORAGE allow

appops set io.github.idrisokys.atvbasic SYSTEM_ALERT_WINDOW allow



# ATV Basic

Android TV için sade, hafif ve hızlı bir ana ekran (launcher) uygulaması.
Gereksiz hiçbir şey yok: duvar kağıdı, saat, tarih ve favori uygulamaların.

## Özellikler

### 🏠 Sade ana ekran
- Tam ekran güzel bir duvar kağıdı
- Saat ve tarih (Türkçe)
- En sevdiğin 6 uygulama altta, tek satırda hazır bekler

### 📱 Tüm uygulamalar
- Alt satırdaki uygulamalardan birinde **Aşağı** tuşuna basınca tüm uygulamaların listesi açılır
- Listeyi kapatmak için ilk satırda **Yukarı** veya **Geri** tuşuna bas

### ⭐ Favori uygulamalar (Dock)
- Uygulamaları alt satıra ekleyebilir, değiştirebilir veya kaldırabilirsin
- Kumandadaki **Menü** tuşuna ya da uzun basarak seçenekleri aç
- Aynı menüden uygulama bilgisine bakabilir veya uygulamayı kaldırabilirsin

### 🖼️ Duvar kağıtları
- Resimler internetten otomatik gelir (doğa manzaraları; Wallhaven erişilemezse otomatik olarak yedek kaynak kullanılır)
- **Sonraki resim** düğmesiyle istediğin an yenisine geç
- Aynı düğmeye uzun basarsan **slayt gösterisi** başlar, her 60 saniyede bir resim değişir (tekrar uzun basınca durur)
- Beğendiğin resmi kaydetmek için **Ayarlar** simgesine uzun bas, resim İndirilenler klasörüne kaydolur
- Telefondan ya da tarayıcıdan herhangi bir resmi **Paylaş → "Duvar kağıdı yap"** diyerek duvar kağıdı yapabilirsin

### 📡 Paylaşım
- Ayarlar simgesinin yanındaki **paylaşım** simgesine bas: paylaşım açılır ve simge gök mavisi olur
- Aynı simgeye tekrar basınca paylaşım kapanır
- Açıkken aynı ağdaki QuickShare cihazları (Android, Mac) bu cihaza dosya gönderebilir; dosyalar ana dizindeki **/Link** klasörüne kaydolur (TV'de izin ekranı yoksa: `adb shell appops set io.github.idrisokys.atvbasic MANAGE_EXTERNAL_STORAGE allow`) (Android 11+ için "Tüm dosyalara erişim" izni gerekir; verilmezse **İndirilenler/Link**'e kaydolur)
- Başka bir ekranı, düğmesi ya da cihaz listesi yoktur
- **Dosya gönderme:** dosya yöneticisinde bir ya da birkaç dosya seç → **Paylaş → "Açık cihazlara gönder"**. Dosyalar, aynı ağda o an paylaşımı açık olan tüm cihazlara otomatik gönderilir (cihaz seçme ekranı yoktur); sonuç ekranda kısa bir mesajla ve bildirimle gösterilir

### ⚙️ Ayarlar
- Ayarlar simgesine basınca doğrudan cihazın sistem ayarları açılır

### 😌 Göz yormaz
- Bir süre dokunmazsan menüler kendiliğinden gizlenir, sadece duvar kağıdı kalır
- Herhangi bir tuşa basınca tekrar görünür

### 🔋 Hafif
- Az bellek kullanır, arka planda ağırlık yapmaz
- Yeni uygulama yüklediğinde ya da sildiğinde liste kendiliğinden güncellenir

### 🎮 Kumanda ve dokunmatik uyumlu
- Kumanda ile rahat kullanılır
- Dokunmatik ekranlı cihazlarda da çalışır

## Kurulum
1. [Releases / Actions](../../actions) bölümünden `ATV-Basic.apk` dosyasını indir
2. Cihaza kur
3. Ana ekrana basınca "ATV Basic varsayılan launcher olsun mu?" sorusuna **Evet** de

## Geliştirme

| Bileşen | Sürüm |
| --- | --- |
| Dil | Kotlin (AGP 9 yerleşik Kotlin desteği) |
| Android Gradle Plugin | 9.5.1 |
| Gradle | 9.6.0 |
| JDK | 17 |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 26 (Android 8.0) |

Kotlin tarafında üçüncü parti kütüphane yoktur; yalnızca Android framework ve Kotlin standart kütüphanesi kullanılır.
Arayüz dışındaki iş mantığı Rust'tadır (aşağıya bakın).

### Rust yerel kütüphanesi (`native/` → `libatvnative.so`)

Arayüz (ana ekran, simgeler, dock, menüler) Kotlin'de kalır; geri kalan iş mantığı Rust'a taşındı. **Yedek Kotlin kodu yoktur**:
kütüphane yüklenemezse launcher açılır ama paylaşım ve duvar kağıdı özellikleri çalışmaz (`Native.ok == false`).

| Alan | Rust | Kotlin'de kalan (yalnızca Android API'siyle yapılabilenler) |
| --- | --- | --- |
| Alma sunucusu | `qshare_core::share::server` (HTTP, `.part` → yeniden adlandırma, ad temizleme, `ad_1.uzantı`) | `ShareService`: mDNS duyurusu (NsdManager), bildirim, Wi-Fi/CPU kilidi, MediaStore (Android 10) |
| Gönderme | `sender.rs` + `qshare_core::share::client` (cihaz başına iş parçacığı, açık dosya tanıtıcısından `pread`) | `ShareSendService`: cihaz bulma (NsdManager), `content://` → tanıtıcı, bildirim |
| Dosya seçici | `files.rs` (listeleme, kök dışına çıkmayı engelleyen yol çözümleme) | `FilePickActivity` arayüzü, `OpenProvider` |
| Duvar kağıdı | `wallpaper.rs` + `json.rs` (arama yanıtı ayrıştırma, adres havuzu, ölçekleme, JPEG yazma) | HTTPS aktarımı (Rust std'de TLS yok), `WallpaperManager`, MediaStore |

**MyL ile paylaşım:** alma/gönderme kodu MyL ve Plan (macOS) ile aynı `qshare_core` crate'idir (`qshare_core/` klasörü MyL deposundakinin kopyasıdır).
Tel biçimi (`POST /u`, `X-Name`, `Content-Length`) ve mDNS kaydı (`_qshare._tcp.`, `n` / `i` öznitelikleri) aynıdır; ATV Basic ile MyL
birbirine dosya gönderir ve alır. MyL'nin Link/Download eşitlemesi ATV Basic'te kapalıdır (sunucu bu uç noktalara 500 döner).
Aynı adlı dosya gelirse üzerine yazılmaz, MyL'deki gibi `ad_1.uzantı` olarak kaydedilir.

Derleme: `rustup target add aarch64-linux-android armv7-linux-androideabi`, `cargo install cargo-ndk --locked`, `ANDROID_NDK_HOME` ayarlı olsun;
`gradle assembleRelease` içindeki `buildRust` görevi `.so`'ları (arm64-v8a + armeabi-v7a) üretir. CI'da (`CI=true`) cargo-ndk yoksa derleme durur.
Testler: `cd qshare_core && cargo test --release` ve `cd native && cargo test --release`.

Yerelde derlemek için: `gradle wrapper --gradle-version 9.6.0` komutunu bir kez çalıştırıp
`./gradlew assembleRelease` kullan (ya da projeyi Android Studio ile aç).

### Sabit imza (güncellemelerin üstüne kurulabilmesi için)
Anahtar verilmezse her derleme farklı bir debug anahtarıyla imzalanır ve yeni APK eskisinin üstüne
kurulamaz (önce kaldırmak gerekir). Bunu önlemek için bir kez anahtar üretip GitHub
**Settings → Secrets and variables → Actions** altına şu dört secret'ı ekle:

- `KEYSTORE_BASE64` (`base64 -w0 release.jks` çıktısı)
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Yerelde aynı işi `ATV_KEYSTORE_FILE`, `ATV_KEYSTORE_PASSWORD`, `ATV_KEY_ALIAS`, `ATV_KEY_PASSWORD`
ortam değişkenleri yapar.
