//! Paylaşım için JNI girişleri: alma sunucusu ve gönderme işi.
//! Protokol, sunucu ve istemci MyLauncher (MyL) ve Plan (macOS) ile ORTAK `qshare_core` crate'indedir; bu yüzden
//! ATV Basic ↔ MyL aynı tel biçimiyle (`POST /u`, `X-Name`, `Content-Length`; mDNS `_qshare._tcp`) konuşur.
//! Kotlin tarafı: `com.ATVBasic.launcher.Native`.
//!
//! * Olaylar Kotlin'e geri çağrıyla DEĞİL, kuyruk + `…Poll` ile iletilir: Rust iş parçacıklarının JVM'e
//!   bağlanması gerekmez; Kotlin tarafı tek bir arka plan iş parçacığında bekler.
//! * Her giriş noktası panikleri yakalar; hata durumunda null / -1 döner.

use crate::sender::{Item, Job, Notify, Src, Update};
use crate::util::{guard, jint_array, jlong_array, jstr, jstr_array, to_jstr_array};
use jni::objects::{JClass, JIntArray, JLongArray, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray};
use jni::JNIEnv;
use qshare_core::share::proto;
use qshare_core::share::server::{Config, Event, Server, Sink};
use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::os::unix::io::FromRawFd;
use std::path::PathBuf;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::Duration;

/// Olay kuyruğunun en büyük uzunluğu (Kotlin dinlemiyorsa bellek şişmesin).
const QUEUE_MAX: usize = 1000;
/// `…Poll` en çok bu kadar bekler; olay yoksa boş dizi döner (Kotlin döngüsü kapanma kontrolü yapabilsin).
const POLL_WAIT: Duration = Duration::from_millis(1500);

/// Sunucu olayı: (tür, ad, klasör). Türler (Kotlin `Native.EV_*`): 1 alınıyor(ad), 2 alındı(ad, klasör),
/// 3 alma yarım kaldı(ad), 4 eşitleme dosyaları değiştirdi, 5 bağlantı başladı, 6 bağlantı bitti.
type RecvEv = (u8, String, String);

/// Gönderim olayı: `["1", ad, cihaz, yüzde]` ilerleme, `["2", tamam, hatalı, ""]` bitti (Kotlin `Native.SEND_*`).
type SendEv = Vec<String>;

/// Bloklayan, sınırlı, kapatılabilir olay kuyruğu.
struct Queue<T> {
    q: Mutex<VecDeque<T>>,
    cv: Condvar,
    closed: AtomicBool,
}

impl<T> Queue<T> {
    fn new() -> Arc<Queue<T>> {
        Arc::new(Queue { q: Mutex::new(VecDeque::new()), cv: Condvar::new(), closed: AtomicBool::new(false) })
    }

    fn push(&self, v: T) {
        if let Ok(mut q) = self.q.lock() {
            if q.len() >= QUEUE_MAX {
                q.pop_front();
            }
            q.push_back(v);
        }
        self.cv.notify_one();
    }

    fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
        self.cv.notify_all();
    }

    /// `Some(Some(olay))`: olay · `Some(None)`: zaman aşımı · `None`: kapandı ve kuyruk boşaldı.
    fn poll(&self) -> Option<Option<T>> {
        let mut q = self.q.lock().ok()?;
        loop {
            if let Some(v) = q.pop_front() {
                return Some(Some(v));
            }
            if self.closed.load(Ordering::Relaxed) {
                return None;
            }
            let (g, t) = self.cv.wait_timeout(q, POLL_WAIT).ok()?;
            q = g;
            if t.timed_out() && q.is_empty() {
                return Some(None);
            }
        }
    }
}

static NEXT: AtomicI64 = AtomicI64::new(1);

type ServerEntry = (Server, Arc<Queue<RecvEv>>);
type SendEntry = (Job, Arc<Queue<SendEv>>);

fn servers() -> &'static Mutex<HashMap<i64, ServerEntry>> {
    static R: OnceLock<Mutex<HashMap<i64, ServerEntry>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

fn sends() -> &'static Mutex<HashMap<i64, SendEntry>> {
    static R: OnceLock<Mutex<HashMap<i64, SendEntry>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

fn opt_path(env: &mut JNIEnv, s: &JString) -> Option<PathBuf> {
    jstr(env, s).filter(|v| !v.is_empty()).map(PathBuf::from)
}

/// Arayüz dili: `true` = İngilizce (Rust'tan dönen hata metinleri için).
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nSetLang<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, english: jni::sys::jboolean) {
    guard((), || proto::set_english(english != 0));
}

// ------------------------------------------------------------------ alma sunucusu

/// Alma sunucusunu başlatır. Dönüş: `(tanıtıcı << 16) | port`; hata: -1. `port` 0 ise boş port seçilir.
/// `link_root` / `dl_root` / `trash` boş (null ya da "") olabilir: Link / Download eşitlemesi kapalı, silme kalıcı.
/// Sunucu dosya başına (eski) protokolü konuşur; MyL'nin Rust sunucusuyla birebir aynı yapılandırma.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nServerStart<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    recv_dir: JString<'l>,
    link_root: JString<'l>,
    dl_root: JString<'l>,
    trash: JString<'l>,
    port: jint,
) -> jlong {
    guard(-1i64, || {
        let Some(recv) = opt_path(&mut env, &recv_dir) else { return -1 };
        let link = opt_path(&mut env, &link_root);
        let dl = opt_path(&mut env, &dl_root);
        let trash = opt_path(&mut env, &trash);
        if !(0..=65535).contains(&port) {
            return -1;
        }
        let cfg = Config {
            recv_dir: recv,
            link_root: link,
            legacy_only: true,
            dl_root: dl,
            trash,
            android_names: true,
            link_hidden_dirs: true,
        };
        let q: Arc<Queue<RecvEv>> = Queue::new();
        let q2 = q.clone();
        let sink: Sink = Arc::new(move |e: Event| {
            let ev: RecvEv = match e {
                Event::Receiving(n) => (1, n, String::new()),
                Event::Received { name, folder } => (2, name, folder.to_string_lossy().into_owned()),
                Event::Failed(n) => (3, n, String::new()),
                Event::NotesChanged => (4, String::new(), String::new()),
                Event::Busy(true) => (5, String::new(), String::new()),
                Event::Busy(false) => (6, String::new(), String::new()),
            };
            q2.push(ev);
        });
        match Server::start(cfg, port as u16, sink) {
            Ok(s) => {
                let p = s.port() as i64;
                let id = NEXT.fetch_add(1, Ordering::Relaxed);
                match servers().lock() {
                    Ok(mut g) => {
                        g.insert(id, (s, q));
                        (id << 16) | p
                    }
                    Err(_) => -1,
                }
            }
            Err(_) => -1,
        }
    })
}

/// Sunucu olayını bekler: [tür, ad, klasör]; zaman aşımında boş dizi; sunucu kapandıysa null.
/// Engelleyicidir (en çok ~1,5 sn): tek bir arka plan iş parçacığında döngüyle çağrılmalıdır.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nServerPoll<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) -> jobjectArray {
    guard(null_mut(), || {
        let q = match servers().lock().ok().and_then(|g| g.get(&id).map(|e| e.1.clone())) {
            Some(q) => q,
            None => return null_mut(),
        };
        match q.poll() {
            Some(Some((k, a, b))) => to_jstr_array(&mut env, &[k.to_string(), a, b]),
            Some(None) => to_jstr_array(&mut env, &[]),
            None => null_mut(),
        }
    })
}

/// Sunucuyu durdurur (dinleyici kapanır; `nServerPoll` null döner; süren aktarımlar kendi başına biter).
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nServerStop<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) {
    guard((), || {
        let e = servers().lock().ok().and_then(|mut g| g.remove(&id));
        if let Some((srv, q)) = e {
            q.close();
            drop(srv);
        }
    })
}

// ------------------------------------------------------------------ gönderme

/// Gönderim işi başlatır. Her öğe için `names[i]` alıcıda görünecek ad; `paths[i]` doluysa dosya yolu,
/// boşsa `fds[i]` açık dosya tanıtıcısı (`lens[i]` bayt; aranabilir olmalı). Dönüş: iş tanıtıcısı, hata: -1.
///
/// Tanıtıcıların SAHİPLİĞİ Rust'a geçer (Kotlin `ParcelFileDescriptor.detachFd()` ile verir): bu çağrı -1
/// dönse bile Kotlin onları kapatmaz. Cihazlar sonradan `nSendPeer` ile eklenir, bitince `nSendClose` çağrılır.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nSendStart<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    names: JObjectArray<'l>,
    paths: JObjectArray<'l>,
    fds: JIntArray<'l>,
    lens: JLongArray<'l>,
) -> jlong {
    guard(-1i64, || {
        let names = jstr_array(&mut env, &names);
        let paths = jstr_array(&mut env, &paths);
        let fds = jint_array(&mut env, &fds);
        let lens = jlong_array(&mut env, &lens);

        // Önce bütün tanıtıcıları sahiplen: sonraki herhangi bir hata yolunda `File` düşünce kapanırlar.
        let owned: Vec<Option<File>> = fds
            .iter()
            .map(|&fd| if fd >= 0 { Some(unsafe { File::from_raw_fd(fd) }) } else { None })
            .collect();

        let mut items: Vec<Item> = Vec::with_capacity(names.len());
        for (i, name) in names.iter().enumerate() {
            if name.is_empty() {
                continue;
            }
            let path = paths.get(i).filter(|p| !p.is_empty());
            let src = if let Some(p) = path {
                Src::Path(PathBuf::from(p))
            } else {
                // `owned` içinden alınır (her tanıtıcı yalnızca bir öğede kullanılır)
                let Some(f) = owned.get(i).and_then(|o| o.as_ref()).and_then(|f| f.try_clone().ok()) else {
                    continue;
                };
                let Some(&len) = lens.get(i) else { continue };
                if len < 0 {
                    continue;
                }
                Src::Fd { file: Arc::new(f), len: len as u64 }
            };
            items.push(Item { name: name.clone(), src });
        }
        // `owned` burada düşer ve özgün tanıtıcıları kapatır; `try_clone` kopyaları (dup) öğelerde yaşar.

        let q: Arc<Queue<SendEv>> = Queue::new();
        let q2 = q.clone();
        let notify: Notify = Arc::new(move |u: Update| match u {
            Update::Progress { name, dev, pct } => q2.push(vec!["1".into(), name, dev, pct.to_string()]),
            Update::Done { dev_ok, dev_fail } => {
                q2.push(vec!["2".into(), dev_ok.to_string(), dev_fail.to_string(), String::new()]);
                q2.close();
            }
        });
        let job = Job::new(items, notify);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        match sends().lock() {
            Ok(mut g) => {
                g.insert(id, (job, q));
                id
            }
            Err(_) => -1,
        }
    })
}

fn find_job(id: i64) -> Option<Job> {
    sends().lock().ok().and_then(|g| g.get(&id).map(|e| e.0.clone()))
}

/// Bulunan cihazı işe ekler ve gönderimi hemen başlatır. `addr`: `ip:port`.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nSendPeer<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    id: jlong,
    dev: JString<'l>,
    addr: JString<'l>,
) {
    guard((), || {
        let (Some(d), Some(a)) = (jstr(&mut env, &dev), jstr(&mut env, &addr)) else { return };
        if let Some(job) = find_job(id) {
            job.add_peer(d, a);
        }
    })
}

/// Başka cihaz eklenmeyecek; tüm cihazlar bitince "bitti" olayı gelir.
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nSendClose<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) {
    guard((), || {
        if let Some(job) = find_job(id) {
            job.close();
        }
    })
}

/// Gönderim olayını bekler: `["1", ad, cihaz, yüzde]` ilerleme · `["2", tamam, hatalı, ""]` bitti ·
/// zaman aşımında boş dizi · iş bitip olaylar okunduysa null (iş serbest bırakılır, açık tanıtıcılar kapanır).
#[no_mangle]
pub extern "system" fn Java_com_ATVBasic_launcher_Native_nSendPoll<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) -> jobjectArray {
    guard(null_mut(), || {
        let q = match sends().lock().ok().and_then(|g| g.get(&id).map(|e| e.1.clone())) {
            Some(q) => q,
            None => return null_mut(),
        };
        match q.poll() {
            Some(Some(ev)) => to_jstr_array(&mut env, &ev),
            Some(None) => to_jstr_array(&mut env, &[]),
            None => {
                if let Ok(mut g) = sends().lock() {
                    g.remove(&id);
                }
                null_mut()
            }
        }
    })
}
