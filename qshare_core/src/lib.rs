//! Hızlı Paylaş ve klasör eşitleme çekirdeği — Plan (macOS) ile MyLauncher (Android) için TEK ortak kod.
//!
//! * `share`   – alma sunucusu, dosya/klasör gönderme, Link/Download klasör eşitlemesi (tel protokolü burada)
//! * `unicode` – NFC/NFD (macOS dosya adları ayrışık olabilir; iki cihazda adlar aynı yazıma çevrilir)
//!
//! Platform kabukları bu crate'in üstüne ince bir köprü koyar: Plan'da C ABI (`plan_qs_*`), Android'de JNI
//! (`Native.serverStart / serverPoll / send / sync`). Platforma özgü davranış `share::server::Config` ve
//! `share::sync::Scope` alanlarıyla seçilir; protokol ve eşitleme mantığı iki tarafta aynı koddur.
//! mDNS/NSD (`_qshare._tcp`) YAYINI platform tarafında kalır (NsdManager / Bonjour); cihaz BULMA ise `mdns` modülünde
//! (tek soketle paralel çözümleme) yapılır ve platformun kendi aramasına ek olarak çalışır.

pub mod mdns;
pub mod share;
pub mod unicode;
mod unicode_tables;
