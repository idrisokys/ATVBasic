//! Alma sunucusu (Swift `QuickShare` dinleyicisi + `ConnHandler`).
//!
//! * `POST /u`  dosya al (`X-Name`, `Content-Length`); `X-Sync: 1` ile Link içine, `X-Mtime` korunarak yazılır
//! * `GET /m`, `GET /f`, `DELETE /x`, `POST /d`  Notlarım eşitleme uç noktaları (hepsi Link içinde; silme kalıcı)
//! * `POST /b` (toplu al, akış) ve `POST /g` (toplu ver) — protokol 2, yalnızca Plan ↔ Plan
//!
//! Her bağlantı ayrı iş parçacığında işlenir; ilerleme/sonuç `Event` olarak bildirilir.

use super::proto::*;
use super::bundle;
use super::sync::{manifest_body, ms, Item, Scope};
use std::fs::{self, File};
use std::io::{self, BufWriter, ErrorKind, Read, Write};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// Dosya geliyor (ad)
    Receiving(String),
    /// Dosya alındı (ad, kaydedilen klasör)
    Received { name: String, folder: PathBuf },
    /// Alma yarım kaldı (ad)
    Failed(String),
    /// Eşitleme `~/Link` içeriğini değiştirdi
    NotesChanged,
    /// Bir bağlantı işlenmeye başladı (`true`) / bitti (`false`): Android'de Wi-Fi/CPU kilidi tutmak için
    Busy(bool),
}

pub type Sink = Arc<dyn Fn(Event) + Send + Sync>;

#[derive(Clone, Default)]
pub struct Config {
    /// Düz dosyaların kaydedildiği klasör (varsayılan: İndirilenler)
    pub recv_dir: PathBuf,
    /// `~/Link` (eşitleme için); `None` ise eşitleme uç noktaları 500 döner
    pub link_root: Option<PathBuf>,
    /// Protokol 2'yi gizler (eski Android gibi davranır; Android sunucusu bunu `true` kullanır)
    pub legacy_only: bool,
    /// Android: Download eşitleme klasörü (`X-Root: dl` istekleri); `None` ise Download eşitlemesi kapalıdır
    pub dl_root: Option<PathBuf>,
    /// Android: silinen öğelerin taşındığı klasör (`.onemsiz`); `None` ise silme kalıcıdır
    pub trash: Option<PathBuf>,
    /// Android: aynı adlı alınan dosya `ad_1.uzantı` olarak kaydedilir (Plan'da `ad (2).uzantı`)
    pub android_names: bool,
    /// Android: Link klasöründeki gizli (`.` ile başlayan) klasörler de eşitlenir. Plan'da (varsayılan `false`) atlanır.
    /// Download kapsamında gizli klasörler her zaman atlanır.
    pub link_hidden_dirs: bool,
}

fn scope_for(cfg: &Config, dl: bool) -> Option<Scope> {
    let root = if dl { cfg.dl_root.clone() } else { cfg.link_root.clone() }?;
    Some(Scope { root, dl, trash: cfg.trash.clone(), skip_hidden_dirs: dl || !cfg.link_hidden_dirs })
}

/// Bağlantı işlenirken `Event::Busy(true)` ... `Event::Busy(false)` çiftini her çıkış yolunda garanti eder.
struct BusyGuard<'a>(&'a Sink);

impl Drop for BusyGuard<'_> {
    fn drop(&mut self) {
        (self.0)(Event::Busy(false));
    }
}

pub struct Server {
    stop: Arc<AtomicBool>,
    port: u16,
    accept: Option<JoinHandle<()>>,
}

impl Server {
    /// `port` 0 ise işletim sistemi boş bir port seçer (`port()` ile okunur).
    pub fn start(cfg: Config, port: u16, sink: Sink) -> std::io::Result<Server> {
        let listener = TcpListener::bind(("::", port)).or_else(|_| TcpListener::bind(("0.0.0.0", port)))?;
        // Bloklayan accept: boşta uyanmaz, bağlantıya anında cevap verir. Durdururken `Drop` kendine bağlanıp uyandırır.
        listener.set_nonblocking(false)?;
        let port = listener.local_addr()?.port();
        let _ = fs::create_dir_all(&cfg.recv_dir);
        let stop = Arc::new(AtomicBool::new(false));
        let stop2 = stop.clone();
        let cfg = Arc::new(cfg);
        let accept = thread::Builder::new().name("qs-accept".into()).spawn(move || {
            while !stop2.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((st, _)) => {
                        if stop2.load(Ordering::Relaxed) {
                            break; // uyandırma bağlantısı
                        }
                        let (cfg, sink) = (cfg.clone(), sink.clone());
                        let _ = thread::Builder::new().name("qs-conn".into()).spawn(move || {
                            let _ = st.set_nonblocking(false);
                            handle(st, &cfg, &sink);
                        });
                    }
                    Err(e) if e.kind() == ErrorKind::Interrupted => {}
                    Err(_) => thread::sleep(Duration::from_millis(200)),
                }
            }
        })?;
        Ok(Server { stop, port, accept: Some(accept) })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn stop(self) {}
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.accept.take() {
            // Bloklayan accept'i uyandır: kendimize kısa bir bağlantı aç (çift yığın veya yalnız IPv4 dinleyici).
            let wake = Duration::from_millis(500);
            let _ = TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, self.port)), wake)
                .or_else(|_| TcpStream::connect_timeout(&SocketAddr::from((Ipv6Addr::LOCALHOST, self.port)), wake));
            let _ = h.join();
        }
    }
}

/// Başlıktan artakalan baytlar önce, sonra soket.
struct Pre<'a> {
    rest: Vec<u8>,
    pos: usize,
    st: &'a mut TcpStream,
}

impl Read for Pre<'_> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if self.pos < self.rest.len() {
            let n = (self.rest.len() - self.pos).min(out.len());
            out[..n].copy_from_slice(&self.rest[self.pos..self.pos + n]);
            self.pos += n;
            return Ok(n);
        }
        self.st.read(out)
    }
}

fn respond(st: &mut TcpStream, status: &str, extra: &str, body: &[u8]) {
    let head = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\n{extra}Connection: close\r\n\r\n", body.len());
    let _ = st.write_all(head.as_bytes());
    let _ = st.write_all(body);
    let _ = st.flush();
}

fn reply(st: &mut TcpStream, status: &str) {
    respond(st, status, "", &[]);
}

fn handle(mut st: TcpStream, cfg: &Config, sink: &Sink) {
    sink(Event::Busy(true));
    let _busy = BusyGuard(sink);
    let _ = st.set_read_timeout(Some(Duration::from_secs(30)));
    let _ = st.set_write_timeout(Some(Duration::from_secs(30)));
    let mut buf: Vec<u8> = Vec::new();
    let mut tmp = vec![0u8; 1 << 20];
    let end = loop {
        if let Some(i) = find_head_end(&buf) {
            break i;
        }
        if buf.len() > MAX_HEADER {
            reply(&mut st, "400 Bad Request");
            return;
        }
        match st.read(&mut tmp) {
            Ok(0) | Err(_) => return,
            Ok(n) => buf.extend_from_slice(&tmp[..n]),
        }
    };
    let head = parse_head(&buf[..end]);
    let rest = buf[end + 4..].to_vec();
    let (method, target) = (head.method().to_string(), head.target().to_string());

    let len: i64 = head.header("content-length").and_then(|v| v.parse().ok()).unwrap_or(-1);
    let name = head.header("x-name").map(dec).unwrap_or_else(|| "file".to_string());
    let sync = head.header("x-sync") == Some("1");
    let is_dir = head.header("x-dir") == Some("1");
    let mtime: i64 = head.header("x-mtime").and_then(|v| v.parse().ok()).map(ms).unwrap_or(-1);
    let dl = head.header("x-root") == Some("dl");

    // Protokol 2: toplu aktarım
    if !cfg.legacy_only && !dl && method == "POST" && (target == "/b" || target == "/g") {
        serve_bundle(&mut st, cfg, sink, &target, rest, len, &mut tmp);
        return;
    }

    // Notlarım eşitleme uç noktaları
    if (method == "GET" && (target == "/m" || target == "/f"))
        || (method == "DELETE" && target == "/x")
        || (method == "POST" && target == "/d")
    {
        serve_sync(&mut st, cfg, sink, &target, &name, is_dir, dl);
        return;
    }
    if method != "POST" || target != "/u" || len < 0 {
        reply(&mut st, if target == "/u" { "400 Bad Request" } else { "500 Error" });
        return;
    }

    // Hedef: eşitlemede Link içindeki göreli yol, aksi halde alma klasörü
    let (save_to, part_name, sync_dest, name) = if sync {
        let Some(sc) = scope_for(cfg, dl) else { return reply(&mut st, "400 Bad Request") };
        let Some(dst) = sc.resolve(&name, false) else { return reply(&mut st, "400 Bad Request") };
        let dir = dst.parent().unwrap_or(sc.root.as_path()).to_path_buf();
        let pn = dst.file_name().and_then(|n| n.to_str()).unwrap_or("f").to_string();
        let _ = fs::create_dir_all(&dir);
        (dir, pn.clone(), Some(dst), pn)
    } else {
        let n = clean_name(&name);
        (cfg.recv_dir.clone(), n.clone(), None, n)
    };
    let part = save_to.join(format!("{part_name}.{}.part", rand8()));
    let Ok(mut f) = File::create(&part) else { return reply(&mut st, "500 Error") };
    if sync_dest.is_none() {
        sink(Event::Receiving(name.clone()));
    }

    // Gövde
    let mut left = len as u64;
    let mut werr = false;
    let mut feed = |d: &[u8], f: &mut File, left: &mut u64| {
        let n = (d.len() as u64).min(*left) as usize;
        if n > 0 && f.write_all(&d[..n]).is_err() {
            werr = true;
        }
        *left -= n as u64;
    };
    feed(&rest, &mut f, &mut left);
    while left > 0 {
        match st.read(&mut tmp) {
            Ok(0) | Err(_) => {
                drop(f);
                let _ = fs::remove_file(&part);
                return;
            }
            Ok(n) => feed(&tmp[..n], &mut f, &mut left),
        }
    }

    // Tamamlama
    let mut ok = !werr;
    let mut name = name;
    let mut folder = cfg.recv_dir.clone();
    if ok {
        if let Some(d) = &sync_dest {
            if mtime > 0 {
                let _ = f.set_modified(time_from_ms(mtime));
            }
            drop(f);
            ok = (|| -> std::io::Result<()> {
                if d.is_dir() { fs::remove_dir_all(d)? } else if d.exists() { fs::remove_file(d)? }
                fs::rename(&part, d)
            })()
            .is_ok();
        } else {
            drop(f);
            let dest = if cfg.android_names { unique_us(&cfg.recv_dir, &name) } else { unique(&cfg.recv_dir, &name) };
            if fs::rename(&part, &dest).is_ok() {
                name = dest.file_name().and_then(|n| n.to_str()).unwrap_or(&name).to_string();
                folder = dest.parent().map(|p| p.to_path_buf()).unwrap_or(folder);
            } else {
                ok = false;
            }
        }
    } else {
        drop(f);
    }
    if !ok {
        let _ = fs::remove_file(&part);
        sink(Event::Failed(name.clone()));
    } else if sync_dest.is_none() {
        sink(Event::Received { name: name.clone(), folder });
    } else {
        sink(Event::NotesChanged);
    }
    reply(&mut st, if ok { "200 OK" } else { "500 Error" });
}

/// Eşitleme uç noktaları; `~/Link` (ya da `X-Root: dl` ile Download) içinde çalışır.
fn serve_sync(st: &mut TcpStream, cfg: &Config, sink: &Sink, target: &str, name: &str, is_dir: bool, dl: bool) {
    let Some(sc) = scope_for(cfg, dl) else { return reply(st, "500 Error") };
    match target {
        "/m" => {
            let extra = if dl {
                "X-Root: dl\r\n"
            } else if cfg.legacy_only {
                ""
            } else {
                "X-Plan-Proto: 2\r\n"
            };
            respond(st, "200 OK", extra, manifest_body(&sc).as_bytes())
        }
        "/f" => {
            let Some(path) = sc.resolve(name, false) else { return reply(st, "404 Not Found") };
            let Ok(mut file) = File::open(&path) else { return reply(st, "404 Not Found") };
            let Ok(md) = file.metadata() else { return reply(st, "404 Not Found") };
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nX-Mtime: {}\r\nConnection: close\r\n\r\n",
                md.len(),
                mtime_ms(&md)
            );
            if st.write_all(head.as_bytes()).is_ok() {
                let _ = std::io::copy(&mut file, st);
            }
            let _ = st.flush();
        }
        "/x" => {
            // Silme: `trash` varsa .onemsiz'e taşınır (geri alınabilir), yoksa kalıcı (kök dışına çıkamaz: resolve doğrular)
            let Some(f) = sc.resolve(name, is_dir) else { return reply(st, "500 Error") };
            let ok = !f.exists() || sc.remove(&f).is_ok();
            if ok {
                sink(Event::NotesChanged);
            }
            reply(st, if ok { "200 OK" } else { "500 Error" });
        }
        _ => {
            let Some(d) = sc.resolve(name, true) else { return reply(st, "500 Error") };
            let ok = d.is_dir() || fs::create_dir_all(&d).is_ok();
            if ok {
                sink(Event::NotesChanged);
            }
            reply(st, if ok { "200 OK" } else { "500 Error" });
        }
    }
}

/// Protokol 2. `/b`: istemci akıtır, biz yazarız (Content-Length yok; akış bitiş işaretiyle sona erer).
/// `/g`: gövdede satır satır göreli ad gelir, biz de bu dosyaları akıtırız.
fn serve_bundle(st: &mut TcpStream, cfg: &Config, sink: &Sink, target: &str, rest: Vec<u8>, len: i64, tmp: &mut [u8]) {
    let Some(root) = cfg.link_root.as_ref() else { return reply(st, "500 Error") };
    if target == "/b" {
        let res = {
            let mut r = Pre { rest, pos: 0, st: &mut *st };
            bundle::receive(&mut r, root)
        };
        match res {
            Ok(s) => {
                if s.files + s.dirs > 0 {
                    sink(Event::NotesChanged);
                }
                respond(st, "200 OK", &format!("X-Ok: {}\r\nX-Fail: {}\r\n", s.files + s.dirs, s.failed), &[]);
            }
            Err(_) => {} // akış kesildi: yarım dosyalar zaten silindi, yanıtlayacak kimse yok
        }
        return;
    }
    if !(0..=(4 << 20)).contains(&len) {
        return reply(st, "400 Bad Request");
    }
    let mut body = rest;
    while (body.len() as i64) < len {
        match st.read(tmp) {
            Ok(0) | Err(_) => return,
            Ok(n) => body.extend_from_slice(&tmp[..n]),
        }
    }
    body.truncate(len as usize);
    let items: Vec<Item> = String::from_utf8_lossy(&body)
        .split('\n')
        .filter(|s| !s.is_empty())
        .map(|rel| Item { rel: rel.to_string(), dir: false, size: 0, mtime: 0 })
        .collect();
    if st.write_all(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").is_err() {
        return;
    }
    let mut w = BufWriter::with_capacity(1 << 20, &mut *st);
    let _ = bundle::send_items(&mut w, root, &items);
    let _ = w.flush();
}
