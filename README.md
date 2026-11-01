# ATV Basic

A tiny, dependency-free home-screen launcher for Android TV. Wallpaper, clock, a 6-app dock and a full app grid — nothing else.
Sade, bağımlılıksız bir Android TV ana ekran başlatıcısı: duvar kağıdı, saat, 6 uygulamalı dock ve tam uygulama listesi.

**Languages / Diller:** English and Türkçe. The launcher follows the device language (Turkish device → Turkish UI and dates; anything else → English). Language changes apply immediately, no restart.

[English](#english) · [Türkçe](#türkçe)

---

## English

### Home screen
- **Wallpaper** with a light dark scrim so text stays readable.
- **Clock and date** (24 h clock; date in the device language, e.g. "Sunday, November 1" / "1 Kasım Pazar").
- **Settings icon** (small, transparent gear, top right).
- **Dock** with 6 slots for your favourite apps.
- **Transparent app icons** — banners/icons are drawn without a tile background.

### Navigation (D-pad)
| Action | Result |
| --- | --- |
| Down on the dock | Open the full app grid |
| Up on the grid's first row / Back | Close the grid |
| Menu button or long-press on a dock slot | Change app · Remove from dock · App info |
| Menu button or long-press on a grid app | Add to dock · App info · Uninstall (non-system apps) |
| Home pressed again | Back to the first dock slot |

An empty dock slot shows "+ Add app" and opens an app picker.

### App grid
- Lists every launchable app (Android TV apps preferred over phone entries), sorted by name, one entry per package.
- **Apps already in the dock are hidden from the grid**; they reappear when removed from the dock.
- Adding to a full dock asks which app to replace. An app can only live in one dock slot.
- Updates automatically when apps are installed or removed.

### Idle hiding
After **20 seconds without input** the whole UI (clock, date, settings, dock, grid) hides and only the wallpaper remains. Any key or touch brings it back; that first key/touch is swallowed so nothing is launched by accident. It does not hide while a menu is open.

### Wallpaper (Settings)
- **Set wallpaper from file** — pick any image with the system file picker.
- **Picsum wallpaper** — random photo at your screen size; variants: random, grayscale, blurred (picsum.photos).
- **Unsplash wallpaper** — random landscape from a curated list of Unsplash photos, cropped to your screen. (Unsplash's keyless random endpoint no longer exists and its official API needs a key, so photo IDs are listed in `WallpaperSource.java`; edit that list to change them.)
- **Auto-change (30 s)** — tap to cycle *Off → Picsum → Unsplash*. While the home screen is visible a new wallpaper is fetched every 30 seconds. Off by default (it uses network continuously).
- **System settings** — shortcut to the Android settings.

Wallpapers are scaled/cropped to the screen and stored as a single JPEG in app storage. There is no "remove wallpaper" option; just pick another one.

### Efficiency
- Zero third-party dependencies; release builds are minified and resource-shrunk.
- When the launcher is hidden it releases the wallpaper bitmap, icon cache and grid views.
- Icons render on one low-priority worker thread into a small LRU cache (~36 icons).
- Package changes are coalesced (500 ms) before rescanning.
- Wallpaper downloads run on their own low-priority thread, never blocking icon loading.

### Permissions
`INTERNET` (wallpaper download), `REQUEST_DELETE_PACKAGES` (uninstall dialog). App visibility uses `<queries>` instead of `QUERY_ALL_PACKAGES`.

### Build
Android Studio or `./gradlew assembleRelease` (minSdk 26, targetSdk/compileSdk 36, Java 17). The release type is signed with the debug key for easy sideloading.

### Source map
| File | Role |
| --- | --- |
| `LauncherActivity` | Home screen, dock, grid, menus, idle hiding, auto-rotation |
| `SettingsActivity` | Floating settings panel |
| `AppEntry` | Queries launchable apps |
| `IconLoader` | Background icon rendering + LRU cache |
| `WallpaperStore` | Saves/loads/scales the wallpaper JPEG |
| `WallpaperSource` | Picsum and Unsplash downloads |
| `res/values`, `res/values-tr` | English (default) and Turkish strings |

---

## Türkçe

### Ana ekran
- **Duvar kağıdı** ve yazıların okunması için hafif karartma.
- **Saat ve tarih** (24 saat; tarih cihaz dilinde, örn. "1 Kasım Pazar").
- **Ayarlar ikonu** (sağ üstte küçük, şeffaf dişli).
- **Dock**: sık kullanılan 6 uygulama için 6 yuva.
- **Şeffaf uygulama ikonları** — ikonların arkasında kutu yok.

### Kumanda ile gezinme
| İşlem | Sonuç |
| --- | --- |
| Dock'ta aşağı | Tüm uygulamaları aç |
| Listenin ilk satırında yukarı / Geri | Listeyi kapat |
| Menü tuşu veya dock yuvasına uzun basma | Uygulamayı değiştir · Dock'tan kaldır · Uygulama bilgisi |
| Menü tuşu veya listedeki uygulamaya uzun basma | Dock'a ekle · Uygulama bilgisi · Kaldır (sistem uygulaması değilse) |
| Home'a tekrar basma | İlk dock yuvasına dön |

Boş yuva "+ Uygulama ekle" yazar ve uygulama seçtirir.

### Uygulama listesi
- Başlatılabilen tüm uygulamaları, ada göre sıralı ve pakete bir kayıt olacak şekilde listeler (TV uygulamaları öncelikli).
- **Dock'taki uygulamalar listede gizlenir**; dock'tan kaldırılınca geri gelir.
- Dolu dock'a eklerken hangisinin değişeceği sorulur. Bir uygulama yalnızca tek yuvada olabilir.
- Uygulama kurulup kaldırılınca liste kendiliğinden güncellenir.

### Boşta gizleme
**20 saniye** tuşa basılmazsa tüm arayüz (saat, tarih, ayarlar, dock, liste) gizlenir, yalnızca duvar kağıdı kalır. Herhangi bir tuş veya dokunuş geri getirir; bu ilk tuş/dokunuş işlem yapmaz, yanlışlıkla uygulama açılmaz. Menü açıkken gizlenmez.

### Duvar kağıdı (Ayarlar)
- **Dosyadan duvar kağıdı ayarla** — sistem dosya seçicisiyle herhangi bir resim.
- **Picsum duvar kağıdı** — ekran boyutunda rastgele fotoğraf; rastgele, siyah-beyaz, bulanık seçenekleri (picsum.photos).
- **Unsplash duvar kağıdı** — Unsplash'ten seçilmiş manzara listesinden rastgele, ekrana kırpılarak. (Unsplash'in anahtarsız rastgele adresi kapandı, resmi API anahtar istiyor; bu yüzden fotoğraf ID'leri `WallpaperSource.java` içinde; listeyi oradan değiştirebilirsiniz.)
- **Otomatik değiştir (30 sn)** — dokundukça *Kapalı → Picsum → Unsplash* arasında döner. Ana ekran görünürken her 30 saniyede yeni duvar kağıdı iner. Varsayılan kapalıdır (sürekli internet kullanır).
- **Sistem ayarları** — Android ayarlarına kısayol.

Duvar kağıdı ekrana göre ölçeklenip kırpılır ve uygulama deposunda tek bir JPEG olarak saklanır. "Duvar kağıdını kaldır" seçeneği yoktur; başka bir tane seçmeniz yeterli.

### Verimlilik
- Dış kütüphane yok; release derlemesi küçültülür.
- Gizlenince duvar kağıdı, ikon önbelleği ve liste görünümleri bellekten bırakılır.
- İkonlar düşük öncelikli tek iş parçacığında çizilir, küçük bir LRU önbellekte (~36 ikon) tutulur.
- Paket değişiklikleri 500 ms birleştirilip bir kez taranır.
- Duvar kağıdı indirmeleri ayrı iş parçacığında çalışır, ikon yüklemeyi engellemez.

### İzinler
`INTERNET` (duvar kağıdı indirme), `REQUEST_DELETE_PACKAGES` (kaldırma penceresi). Uygulama listesi için `QUERY_ALL_PACKAGES` yerine `<queries>` kullanılır.

### Derleme
Android Studio veya `./gradlew assembleRelease` (minSdk 26, hedef/compileSdk 36, Java 17). Release, kolay yükleme için debug anahtarıyla imzalanır.
