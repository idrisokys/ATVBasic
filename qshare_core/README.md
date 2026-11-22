# qshare_core

Hızlı Paylaş ve klasör eşitleme için **tek ortak Rust çekirdeği**. İki uygulama aynı kodu kullanır:

| Uygulama | Köprü | Dosya |
|---|---|---|
| **Plan** (macOS, Swift) | C ABI `plan_qs_*` (statik kütüphane) | `rust/plan_core/src/ffi_share.rs` |
| **MyLauncher** (Android, Kotlin) | JNI `Native.serverStart / serverPoll / send / sync` | `native/src/share_jni.rs` |

Bağımlılık yoktur (yalnızca `std`); bu yüzden çevrimdışı derlenir ve hem `staticlib` hem `cdylib` içine gömülebilir.

## İçerik

- `src/share/server.rs` – alma sunucusu (`POST /u`, `GET /m`, `GET /f`, `DELETE /x`, `POST /d`, toplu `POST /b`, `POST /g`)
- `src/share/client.rs` – dosya/klasör gönderme, `send_reader` (açık dosya tanıtıcısından gönderme)
- `src/share/sync.rs` – Link/Download eşitleme istemcisi: manifesto, 3 mod, plan çıkarma, paralel yürütme
- `src/share/proto.rs` – yüzde kodlama, ad temizleme, güvenli yol çözümleme (`..` ve kök dışına çıkma engeli)
- `src/share/bundle.rs` – tek bağlantıda çok dosya (protokol 2, yalnızca Plan ↔ Plan)
- `src/unicode.rs` – NFC/NFD; tablolar `tools/gen_unicode.py` ile üretilir

mDNS/Bonjour/NSD yayını ve cihaz bulma platform tarafında (Swift/Kotlin) kalır; Rust'a `ip:port` verilir.

## Platform farkları (yapılandırma)

| Davranış | Plan (macOS) | MyLauncher (Android) |
|---|---|---|
| Kapsam | `Scope::link(root)` | Link ya da Download (`Scope { dl: true, … }`, `X-Root: dl`) |
| Silme | kalıcı (`trash: None`) | `.onemsiz` klasörüne taşınır (`trash: Some(..)`) |
| Aynı adlı alınan dosya | `ad (2).uzantı` | `ad_1.uzantı` (`Config::android_names`) |
| Link'te gizli klasörler | atlanır | eşitlenir (`link_hidden_dirs`); Download'da atlanır |
| Toplu protokol (`/b`, `/g`) | açık | kapalı (`legacy_only`) |
| `Event::Busy` | yok sayılır | Wi-Fi/CPU kilidi için kullanılır |

## Test

```
cargo test --release
```

Sunucu ve istemci aynı süreçte 127.0.0.1 üzerinden uçtan uca denenir (`tests/share_loopback.rs`).

## İki repoda kullanım

Crate her iki repoda da kök dizinde `qshare_core/` olarak durur ve `path` bağımlılığıyla bağlanır
(Plan: `../../qshare_core`, MyLauncher: `../qshare_core`). Tek kaynaktan yönetmek için ayrı bir repo yapıp
iki projeye submodule olarak ekleyin:

```
git submodule add <bu-reponun-adresi> qshare_core
```

CI'da `actions/checkout@v4` adımına `with: { submodules: true }` ekleyin.
