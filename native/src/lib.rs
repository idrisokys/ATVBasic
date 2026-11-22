//! ATV Basic yerel kütüphanesi (JNI). Kotlin tarafı: `com.ATVBasic.launcher.Native` → libatvnative.so.
//!
//! Arayüz dışındaki iş mantığı burada, Rust'tadır:
//! * `sender` + `share_jni`: paylaşım (alma sunucusu ve gönderme; MyL ile ORTAK `qshare_core` protokolü)
//! * `files`: dosya seçici listeleme, açma sağlayıcısı için güvenli yol çözümleme
//! * `wallpaper` + `json`: duvar kağıdı adres havuzu, ölçekleme ve JPEG yazma
//!
//! Android'e özgü kalanlar (NSD/mDNS, bildirim, MediaStore, PackageManager, ağ aktarımı HTTPS) yalnızca Android API'leriyle
//! yapılabildiği için Kotlin'dedir. Her giriş noktası panikleri yakalar; hata durumunda null / -1 / false döner.

mod files;
mod json;
mod sender;
mod share_jni;
mod util;
mod wallpaper;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use std::ptr::null_mut;
use util::{guard, jstr, to_jstr_array};

fn ret_str(env: &mut JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(v) => env.new_string(v).map(|j| j.into_raw()).unwrap_or(null_mut()),
        None => null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nVersion<'l>(_env: JNIEnv<'l>, _c: JClass<'l>) -> jint {
    1
}

// ------------------------------------------------------------------ dosyalar

/// Klasör içeriği: her öğe için 3 alan [ad, "1" klasör / "0" dosya, boyut]. Okunamazsa null.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nListDir<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, dir: JString<'l>) -> jobjectArray {
    guard(null_mut(), || {
        let Some(d) = jstr(&mut env, &dir) else { return null_mut() };
        match files::list_dir(&d) {
            Some(v) => {
                let mut flat = Vec::with_capacity(v.len() * 3);
                for e in v {
                    flat.push(e.name);
                    flat.push(if e.dir { "1" } else { "0" }.to_string());
                    flat.push(e.size.to_string());
                }
                to_jstr_array(&mut env, &flat)
            }
            None => null_mut(),
        }
    })
}

/// `path` gerçek yolu `root` içinde kalan bir düz dosyaysa o yolu, değilse null döner.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nResolveInside<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    root: JString<'l>,
    path: JString<'l>,
) -> jstring {
    guard(null_mut(), || {
        let (Some(r), Some(p)) = (jstr(&mut env, &root), jstr(&mut env, &path)) else { return null_mut() };
        let out = files::resolve_inside(&r, &p);
        ret_str(&mut env, out)
    })
}

// ------------------------------------------------------------------ duvar kağıdı

#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpListingUrl<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> jstring {
    guard(null_mut(), || ret_str(&mut env, Some(wallpaper::listing_url())))
}

#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpFallbackUrl<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> jstring {
    guard(null_mut(), || ret_str(&mut env, Some(wallpaper::fallback_url())))
}

/// Arama yanıtıyla (JSON) adres havuzunu yeniler. Havuza giren adres sayısı; hata: -1.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpPoolLoad<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, body: JString<'l>) -> jint {
    guard(-1, || match jstr(&mut env, &body) {
        Some(b) => wallpaper::pool_load(&b).min(i32::MAX as usize) as jint,
        None => -1,
    })
}

/// Havuzdan bir resim adresi; havuz boş ya da eskiyse null (çağıran yeni arama yanıtıyla `nWpPoolLoad` yapar).
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpNext<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> jstring {
    guard(null_mut(), || ret_str(&mut env, wallpaper::pool_next()))
}

/// İndirilen/seçilen resmi ekran boyutlu JPEG duvar kağıdına çevirir; `src` her durumda silinir.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpImport<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    src: JString<'l>,
    dest: JString<'l>,
    display_w: jint,
    display_h: jint,
    fit: jboolean,
) -> jboolean {
    guard(0, || {
        let (Some(s), Some(d)) = (jstr(&mut env, &src), jstr(&mut env, &dest)) else { return 0 };
        wallpaper::import(&s, &d, display_w.max(1) as u32, display_h.max(1) as u32, fit != 0) as jboolean
    })
}

/// Sistem duvar kağıdı için kırpmadan sığdırılmış JPEG üretir (`src` silinmez).
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpSystemImage<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    src: JString<'l>,
    out: JString<'l>,
    display_w: jint,
    display_h: jint,
    tv: jboolean,
) -> jboolean {
    guard(0, || {
        let (Some(s), Some(o)) = (jstr(&mut env, &src), jstr(&mut env, &out)) else { return 0 };
        wallpaper::system_image(&s, &o, display_w.max(1) as u32, display_h.max(1) as u32, tv != 0) as jboolean
    })
}

/// Duvar kağıdı dosyası değişince değişen değer; dosya yoksa 0.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nWpStamp<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, path: JString<'l>) -> jlong {
    guard(0, || match jstr(&mut env, &path) {
        Some(p) => wallpaper::stamp(&p),
        None => 0,
    })
}
