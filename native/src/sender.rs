//! Gönderim işi: seçilen dosyalar, bulunan her cihaza (MyL, ATV Basic, Plan…) `qshare_core` istemcisiyle yollanır.
//!
//! * Cihaz bulma (mDNS/NSD) Android tarafında kalır; buraya yalnızca `ip:port` verilir (`add_peer`).
//! * Cihazlar bulundukça eklenebilir: her cihaz kendi iş parçacığında, dosyalar sırayla gönderilir.
//! * `close()` "başka cihaz eklenmeyecek" demektir; tüm cihazlar bitince `Update::Done` bildirilir.
//! * Kaynak ya yol (`Src::Path`) ya da Kotlin'in devrettiği açık dosya tanıtıcısıdır (`Src::Fd`, `content://`).
//!   Tanıtıcı `pread` ile okunur; böylece aynı dosya birden çok cihaza, konum paylaşmadan gönderilebilir.

use qshare_core::share::client::{self, SendStatus};
use std::fs::File;
use std::io::{self, Read};
use std::os::unix::fs::FileExt;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

/// İlerleme bildirimleri arasındaki en kısa süre.
const PROGRESS_EVERY: Duration = Duration::from_millis(250);

pub enum Src {
    Path(PathBuf),
    /// Sahipliği Rust'a geçmiş, aranabilir (regular file) tanıtıcı ve bayt uzunluğu.
    Fd { file: Arc<File>, len: u64 },
}

pub struct Item {
    /// Alıcıda görünecek ad (`X-Name`).
    pub name: String,
    pub src: Src,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Update {
    /// `dev` cihazına `name` dosyası gönderiliyor (%`pct`).
    Progress { name: String, dev: String, pct: u8 },
    /// Tüm cihazlar bitti. `dev_ok`: tüm dosyaları alan cihaz sayısı, `dev_fail`: en az bir dosyada hata olan.
    Done { dev_ok: usize, dev_fail: usize },
}

pub type Notify = Arc<dyn Fn(Update) + Send + Sync>;

/// `pread` ile okuyan, kendi konumunu tutan okuyucu.
struct AtReader {
    f: Arc<File>,
    off: u64,
}

impl Read for AtReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.f.read_at(buf, self.off)?;
        self.off += n as u64;
        Ok(n)
    }
}

#[derive(Default)]
struct St {
    active: usize,
    ok: usize,
    fail: usize,
    closed: bool,
    done: bool,
}

struct Inner {
    items: Vec<Item>,
    notify: Notify,
    st: Mutex<St>,
}

fn lock(m: &Mutex<St>) -> MutexGuard<'_, St> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

#[derive(Clone)]
pub struct Job {
    inner: Arc<Inner>,
}

impl Job {
    pub fn new(items: Vec<Item>, notify: Notify) -> Job {
        Job { inner: Arc::new(Inner { items, notify, st: Mutex::new(St::default()) }) }
    }

    /// Cihazı ekler ve hemen göndermeye başlar. `close()` sonrasında eklenenler yok sayılır.
    pub fn add_peer(&self, dev: String, addr: String) {
        {
            let mut s = lock(&self.inner.st);
            if s.closed || s.done {
                return;
            }
            s.active += 1;
        }
        let inner = self.inner.clone();
        let started = thread::Builder::new().name("qs-send".into()).spawn(move || {
            let ok = send_peer(&inner, &dev, &addr);
            {
                let mut s = lock(&inner.st);
                s.active -= 1;
                if ok {
                    s.ok += 1;
                } else {
                    s.fail += 1;
                }
            }
            finish_if_done(&inner);
        });
        if started.is_err() {
            {
                let mut s = lock(&self.inner.st);
                s.active -= 1;
                s.fail += 1;
            }
            finish_if_done(&self.inner);
        }
    }

    /// Başka cihaz eklenmeyecek.
    pub fn close(&self) {
        lock(&self.inner.st).closed = true;
        finish_if_done(&self.inner);
    }
}

fn finish_if_done(inner: &Inner) {
    let (ok, fail) = {
        let mut s = lock(&inner.st);
        if s.done || !s.closed || s.active > 0 {
            return;
        }
        s.done = true;
        (s.ok, s.fail)
    };
    (inner.notify)(Update::Done { dev_ok: ok, dev_fail: fail });
}

fn send_peer(inner: &Inner, dev: &str, addr: &str) -> bool {
    if inner.items.is_empty() {
        return false;
    }
    let mut all = true;
    for item in &inner.items {
        if !send_item(inner, dev, addr, item) {
            all = false;
        }
    }
    all
}

fn send_item(inner: &Inner, dev: &str, addr: &str, item: &Item) -> bool {
    let name = item.name.as_str();
    let mut last_pct: i32 = -1;
    let mut last_at: Option<Instant> = None;
    let mut prog = |sent: u64, total: u64| {
        let pct = if total > 0 { (sent.saturating_mul(100) / total).min(100) as u8 } else { 100 };
        let due = last_at.map_or(true, |t| t.elapsed() >= PROGRESS_EVERY);
        if pct as i32 != last_pct && (pct == 100 || due) {
            last_pct = pct as i32;
            last_at = Some(Instant::now());
            (inner.notify)(Update::Progress { name: name.to_string(), dev: dev.to_string(), pct });
        }
    };
    let status = match &item.src {
        Src::Path(p) => {
            let mut f = match File::open(p) {
                Ok(f) => f,
                Err(_) => return false,
            };
            let len = match f.metadata() {
                Ok(m) => m.len(),
                Err(_) => return false,
            };
            client::send_reader(addr, name, len, &mut f, &mut prog)
        }
        Src::Fd { file, len } => {
            let mut r = AtReader { f: file.clone(), off: 0 };
            client::send_reader(addr, name, *len, &mut r, &mut prog)
        }
    };
    status == SendStatus::Ok
}

#[cfg(test)]
mod tests {
    use super::*;
    use qshare_core::share::server::{Config, Server};
    use std::fs;
    use std::path::Path;
    use std::sync::mpsc;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("atv_send_{tag}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn server(recv: &Path) -> Server {
        let cfg = Config { recv_dir: recv.to_path_buf(), legacy_only: true, android_names: true, ..Default::default() };
        Server::start(cfg, 0, Arc::new(|_| {})).unwrap()
    }

    fn done_channel() -> (Notify, mpsc::Receiver<Update>) {
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let n: Notify = Arc::new(move |u| {
            if let Update::Done { .. } = u {
                let _ = tx.lock().unwrap().send(u);
            }
        });
        (n, rx)
    }

    #[test]
    fn sends_a_path_to_a_qshare_server() {
        let base = tmp("path");
        let recv = base.join("in");
        fs::create_dir_all(&recv).unwrap();
        let src = base.join("a.txt");
        fs::write(&src, b"merhaba").unwrap();
        let srv = server(&recv);

        let (n, rx) = done_channel();
        let job = Job::new(vec![Item { name: "a.txt".into(), src: Src::Path(src) }], n);
        job.add_peer("test".into(), format!("127.0.0.1:{}", srv.port()));
        job.close();

        let u = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        assert_eq!(u, Update::Done { dev_ok: 1, dev_fail: 0 });
        assert_eq!(fs::read(recv.join("a.txt")).unwrap(), b"merhaba");
        drop(srv);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn sends_one_fd_to_two_peers() {
        let base = tmp("fd");
        let (r1, r2) = (base.join("in1"), base.join("in2"));
        fs::create_dir_all(&r1).unwrap();
        fs::create_dir_all(&r2).unwrap();
        let src = base.join("src.bin");
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        fs::write(&src, &data).unwrap();
        let (s1, s2) = (server(&r1), server(&r2));

        let f = File::open(&src).unwrap();
        let (n, rx) = done_channel();
        let job = Job::new(vec![Item { name: "veri.bin".into(), src: Src::Fd { file: Arc::new(f), len: data.len() as u64 } }], n);
        job.add_peer("bir".into(), format!("127.0.0.1:{}", s1.port()));
        job.add_peer("iki".into(), format!("127.0.0.1:{}", s2.port()));
        job.close();

        let u = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        assert_eq!(u, Update::Done { dev_ok: 2, dev_fail: 0 });
        assert_eq!(fs::read(r1.join("veri.bin")).unwrap(), data);
        assert_eq!(fs::read(r2.join("veri.bin")).unwrap(), data);
        drop((s1, s2));
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn unreachable_peer_counts_as_failure() {
        let (n, rx) = done_channel();
        let job = Job::new(vec![Item { name: "x".into(), src: Src::Path(PathBuf::from("/nonexistent/x")) }], n);
        // 127.0.0.1:1 dinlenmiyor
        job.add_peer("yok".into(), "127.0.0.1:1".into());
        job.close();
        let u = rx.recv_timeout(Duration::from_secs(15)).unwrap();
        assert_eq!(u, Update::Done { dev_ok: 0, dev_fail: 1 });
    }

    #[test]
    fn close_without_peers_finishes_immediately() {
        let (n, rx) = done_channel();
        let job = Job::new(Vec::new(), n);
        job.close();
        assert_eq!(rx.recv_timeout(Duration::from_secs(2)).unwrap(), Update::Done { dev_ok: 0, dev_fail: 0 });
    }
}
