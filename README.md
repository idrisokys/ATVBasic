

https://github.com/user-attachments/assets/293e2a0f-897b-41d8-bb9d-e66d247ddb8c

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

Üçüncü parti kütüphane yoktur; yalnızca Android framework ve Kotlin standart kütüphanesi kullanılır.

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
