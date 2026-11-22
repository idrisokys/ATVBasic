//! Hızlı Paylaş (dosya alma/gönderme) ve klasör eşitlemesi — Swift `QuickShare`/`NoteSync` ile Kotlin `QuickShare`/`NoteSync`'in ortak Rust karşılığı.
//!
//! Tel protokolü Android ile aynıdır (düz TCP + HTTP/1.1 benzeri):
//!   POST /u  (X-Name, Content-Length; eşitlemede X-Sync: 1, X-Mtime)  dosya yaz
//!   GET  /m  Link manifestosu      GET /f  dosya oku (X-Name)
//!   POST /d  klasör oluştur        DELETE /x  sil (X-Name, X-Dir; `trash` varsa oraya taşınır)
//!
//! Protokol 2 (yalnızca Plan ↔ Plan; `GET /m` yanıtındaki `X-Plan-Proto: 2` ile anlaşılır):
//!   POST /b  toplu gönder (akış)   POST /g  toplu al (gövde: satır satır ad)
//! Eski cihazlar (Android) için dosya başına isteklere geri dönülür.
//!
//! * `bundle` – toplu aktarım biçimi (tek bağlantıda çok dosya)
//! * `proto`  – kodlama, paralel çalıştırıcı, ad temizleme, güvenli yol çözümleme, başlık ayrıştırma
//! * `server` – alma sunucusu (dinleyici + bağlantı işleyici)
//! * `client` – bağlantı, dosya/klasör gönderme
//! * `sync`   – Link/Download eşitleme istemcisi (modlar 0/1/2) ve yerel manifesto
//!
//! mDNS/Bonjour/NSD (`_qshare._tcp`) yayını ve cihaz bulma platform tarafında (Swift/Kotlin) kalır; Rust'a `ip:port` verilir.
//!
//! Bu modül Plan (macOS) ve MyLauncher (Android) için ortaktır; platform farkları yapılandırmayla seçilir:
//! * `Scope`: Link (.url/.txt) ya da Download (`X-Root: dl`, her dosya türü) eşitlemesi (`Scope::link` = Plan)
//! * `Scope::trash` / `Config::trash`: silinenler geri alınabilir klasöre taşınır (Android `.onemsiz`); boşsa kalıcı silinir (Plan)
//! * `Config::android_names`: alınan aynı adlı dosya `ad_1.uzantı` (Android) ya da `ad (2).uzantı` (Plan) olur
//! * `Config::link_hidden_dirs` / `Scope::skip_hidden_dirs`: Link'te gizli klasörlerin eşitlenip eşitlenmeyeceği
//! * `Event::Busy`: Android Wi-Fi/CPU kilidini bağlantı süresince tutar; Plan yok sayar
//! * `client::send_reader`: açık bir okuyucudan (Android `ContentResolver` dosya tanıtıcısı) gönderme

pub mod bundle;
pub mod client;
pub mod proto;
pub mod server;
pub mod sync;
