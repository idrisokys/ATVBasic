//! Klasör eşitleme istemcisi: Link (`.url`/`.txt`) ya da Download (her dosya) klasörünü karşı cihazla karşılaştırır.
//!
//! Hız tasarımı:
//! 1. Yerel tarama ile karşı manifesto indirmesi aynı anda yapılır.
//! 2. Önce tam bir **plan** çıkarılır (gönder / al / klasör aç / sil), sonra toplu yürütülür.
//! 3. Karşı cihaz protokol 2 destekliyorsa (`X-Plan-Proto: 2`) dosyalar ~8 MiB / 512 dosyalık paketlere
//!    bölünüp en çok 4 paralel bağlantıda tek akışla gider/gelir (`bundle`); desteklemiyorsa (Android)
//!    dosya başına isteklere paralel olarak geri dönülür.
//!
//! Modlar: 0 = iki cihaz aynı olsun (birleştirir; yeni değişiklik tarihi kazanır), 1 = bu cihaza göre
//! (karşıdaki bu cihazın aynısı olur), 2 = karşıdaki cihaza göre (bu cihaz karşıdakinin aynısı olur).
//! Silmeler `Scope::trash` boşsa KALICIDIR (macOS Plan), doluysa geri alınabilir bir klasöre taşınır (Android `.onemsiz`).

use super::bundle;
use super::client::Conn;
use super::proto::*;
use crate::unicode::nfc;
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

/// Eşitlemede aynı anda açık bağlantı sayısı.
const WORKERS: usize = 4;
const CHUNK_BYTES: i64 = 8 << 20;
const CHUNK_FILES: usize = 512;

#[derive(Clone, Debug)]
pub struct Item {
    pub rel: String,
    pub dir: bool,
    pub size: i64,
    pub mtime: i64,
}

#[derive(Clone, Debug)]
pub struct Peer {
    pub name: String,
    /// `ip:port`
    pub addr: String,
}

#[derive(Default, Debug)]
pub struct Outcome {
    pub peers: u32,
    pub sent: u32,
    pub got: u32,
    pub deleted: u32,
    pub mode: u32,
    pub errors: Vec<String>,
    pub error: Option<String>,
}

impl Outcome {
    /// Panelde gösterilen özet.
    pub fn message(&self) -> String {
        if let Some(e) = &self.error {
            return l(&format!("Eşitleme hatası: {e}"), &format!("Sync error: {e}"));
        }
        if self.peers == 0 {
            return self.errors.first().cloned().unwrap_or_else(|| l("Açık cihaz bulunamadı", "No open device found"));
        }
        let (p, s, g, d) = (self.peers, self.sent, self.got, self.deleted);
        let base = match self.mode {
            2 => l(
                &format!("Karşıdaki cihaza göre eşitlendi: {g} alındı, {d} silindi"),
                &format!("Synced to the other device: {g} received, {d} deleted"),
            ),
            1 => l(
                &format!("Bu cihaz esas alındı: {p} cihaz, {s} gönderildi, {d} silindi"),
                &format!("This device is now the master: {p} device(s), {s} sent, {d} deleted"),
            ),
            _ => l(
                &format!("Eşitlendi: {p} cihaz, {s} gönderildi, {g} alındı"),
                &format!("Synced: {p} device(s), {s} sent, {g} received"),
            ),
        };
        match self.errors.first() {
            Some(e) => format!("{base}\n{e}"),
            None => base,
        }
    }
}

/// Eşitlemenin çalıştığı klasör ve kuralları.
/// * Link (`dl == false`): `<ana dizin>/Link`, yalnızca `.url`/`.txt`.
/// * Download (`dl == true`): İndirilenler, gizli/geçici olmayan her dosya; istekler `X-Root: dl` taşır.
/// * `trash`: dolu ise silinen öğeler buraya taşınır (Android `.onemsiz`, geri alınabilir); boş ise kalıcı silinir.
/// * `skip_hidden_dirs`: `.` ile başlayan klasörler taranmaz. macOS Plan: her zaman; Android: yalnızca Download.
#[derive(Clone, Debug)]
pub struct Scope {
    pub root: PathBuf,
    pub dl: bool,
    pub trash: Option<PathBuf>,
    pub skip_hidden_dirs: bool,
}

impl Scope {
    /// macOS Plan'ın Link kapsamı: yalnızca `.url`/`.txt`, gizli klasörler atlanır, silme kalıcıdır.
    pub fn link(root: impl Into<PathBuf>) -> Scope {
        Scope { root: root.into(), dl: false, trash: None, skip_hidden_dirs: true }
    }

    pub fn resolve(&self, rel: &str, dir: bool) -> Option<PathBuf> {
        resolve_scoped(rel, &self.root, dir, self.dl)
    }

    /// Karşı cihaza giden isteklere eklenen başlık satırı.
    pub fn hdr(&self) -> &'static str {
        if self.dl { "X-Root: dl\r\n" } else { "" }
    }

    /// Silme: `trash` varsa oraya taşır, yoksa kalıcı siler.
    pub fn remove(&self, p: &Path) -> io::Result<()> {
        match &self.trash {
            Some(t) => move_to_trash(p, t),
            None => remove_any(p),
        }
    }
}

/// Android `moveToOnemsiz`: öğeyi `trash` klasörüne (`.nomedia` ile) taşır; aynı ad varsa `ad_1`, `ad_2`…
pub fn move_to_trash(p: &Path, trash: &Path) -> io::Result<()> {
    fs::create_dir_all(trash)?;
    let _ = OpenOptions::new().write(true).create(true).open(trash.join(".nomedia"));
    let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("f");
    let t = unique_us(trash, name);
    if fs::rename(p, &t).is_ok() {
        return Ok(());
    }
    // Başka bölümdeyse kopyala, başarılıysa kaynağı sil
    if p.is_dir() {
        if copy_dir(p, &t).is_ok() {
            fs::remove_dir_all(p)
        } else {
            let _ = fs::remove_dir_all(&t);
            Err(io::Error::new(io::ErrorKind::Other, "move failed"))
        }
    } else {
        fs::copy(p, &t)?;
        if let Ok(m) = fs::metadata(p).and_then(|m| m.modified()) {
            if let Ok(f) = OpenOptions::new().write(true).open(&t) {
                let _ = f.set_modified(m);
            }
        }
        if fs::remove_file(p).is_ok() {
            Ok(())
        } else {
            let _ = fs::remove_file(&t);
            Err(io::Error::new(io::ErrorKind::Other, "move failed"))
        }
    }
}

fn copy_dir(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    for e in fs::read_dir(from)? {
        let e = e?;
        let dst = to.join(e.file_name());
        if e.file_type()?.is_dir() {
            copy_dir(&e.path(), &dst)?;
        } else {
            fs::copy(e.path(), &dst)?;
        }
    }
    Ok(())
}

/// Karşı taraf zamanı saniye olarak bildirirse (1e11'den küçük) milisaniyeye çevirir.
pub fn ms(t: i64) -> i64 {
    if t > 0 && t < 100_000_000_000 { t * 1000 } else { t }
}

// ---------- Yerel ağaç ----------

/// Yerel ağaç: göreli yol → öğe (yollar NFC; iki platformda aynı yazım). Gizli klasörler `skip_hidden_dirs`'e göre atlanır.
pub fn local(sc: &Scope) -> HashMap<String, Item> {
    let mut out = HashMap::new();
    walk(&sc.root, "", 0, sc, &mut out);
    out
}

fn walk(d: &Path, prefix: &str, depth: usize, sc: &Scope, out: &mut HashMap<String, Item>) {
    if depth > MAX_DEPTH {
        return;
    }
    let Ok(rd) = fs::read_dir(d) else { return };
    for e in rd.flatten() {
        let Some(raw) = e.file_name().to_str().map(|s| s.to_string()) else { continue };
        let name = nfc(&raw);
        let Ok(ft) = e.file_type() else { continue };
        let rel = format!("{prefix}{name}");
        if ft.is_dir() {
            if sc.skip_hidden_dirs && name.starts_with('.') {
                continue;
            }
            out.insert(rel.clone(), Item { rel: rel.clone(), dir: true, size: 0, mtime: 0 });
            walk(&e.path(), &format!("{rel}/"), depth + 1, sc, out);
        } else if is_sync_name(&name, sc.dl) {
            let (size, mtime) = fs::metadata(e.path()).map(|m| (m.len() as i64, mtime_ms(&m))).unwrap_or((0, 0));
            out.insert(rel.clone(), Item { rel, dir: false, size, mtime });
        }
    }
}

/// Manifesto gövdesi: her satır `D|F \t boyut \t mtime(ms) \t yüzde-kodlu yol`.
pub fn manifest_body(sc: &Scope) -> String {
    let mut out = String::new();
    for it in local(sc).values() {
        out.push_str(&format!("{}\t{}\t{}\t{}\n", if it.dir { "D" } else { "F" }, it.size, it.mtime, enc(&it.rel)));
    }
    out
}

/// Dosya ya da klasörü kalıcı siler.
pub fn remove_any(p: &Path) -> io::Result<()> {
    if p.is_dir() { fs::remove_dir_all(p) } else { fs::remove_file(p) }
}

// ---------- Karşı cihaz: manifesto ----------

pub struct Manifest {
    pub items: HashMap<String, Item>,
    /// Karşı cihazın protokol sürümü (1 = eski/Android, 2 = toplu aktarım destekli)
    pub proto: u8,
    /// NFC'ye çevrilmeden önceki özgün yollar (yalnızca farklı olanlar): karşıya giden isteklerde bunlar kullanılır
    pub raw: HashMap<String, String>,
}

fn status_err(rel: &str, what: &str) -> ShareError {
    ShareError(format!("{rel}: {what}"))
}

pub fn fetch_manifest(p: &Peer, sc: &Scope) -> Result<Manifest> {
    let mut c = Conn::connect(&p.addr)?;
    c.send(format!("GET /m HTTP/1.1\r\nHost: qs\r\n{}Connection: close\r\n\r\n", sc.hdr()).as_bytes())?;
    let h = c.read_head()?;
    if h.status() != 200 {
        let code = h.status();
        return err(
            &format!("karşı cihaz Link eşitlemesini desteklemiyor (kod {code}); orada uygulamayı güncelleyin"),
            &format!("the other device does not support Link sync (code {code}); update the app there"),
        );
    }
    if sc.dl && h.header("x-root") != Some("dl") {
        return err(
            "karşı cihaz Download eşitlemesini desteklemiyor; orada uygulamayı güncelleyin",
            "the other device does not support Download sync; update the app there",
        );
    }
    // Download eşitlemesi yalnızca eski (dosya başına) protokolle yürür
    let proto = if sc.dl { 1 } else { h.header("x-plan-proto").and_then(|v| v.parse::<u8>().ok()).unwrap_or(1) };
    let len = h.header("content-length").and_then(|v| v.parse::<usize>().ok()).unwrap_or(0).min(8 << 20);
    let mut data = Vec::with_capacity(len);
    c.read_body(len, &mut |b| {
        data.extend_from_slice(b);
        Ok(())
    })?;
    let mut items = HashMap::new();
    let mut raws = HashMap::new();
    for ln in String::from_utf8_lossy(&data).split('\n') {
        let a: Vec<&str> = ln.split('\t').collect();
        if a.len() != 4 {
            continue;
        }
        let raw = dec(a[3].trim()).trim_matches('/').to_string();
        if raw.is_empty() {
            continue;
        }
        let rel = nfc(&raw);
        if rel != raw {
            raws.insert(rel.clone(), raw);
        }
        items.insert(
            rel.clone(),
            Item {
                rel,
                dir: a[0].trim() == "D",
                size: a[1].trim().parse().unwrap_or(0),
                mtime: ms(a[2].trim().parse().unwrap_or(0)),
            },
        );
    }
    Ok(Manifest { items, proto, raw: raws })
}

// ---------- Eski protokol (dosya başına istek; Android uyumu) ----------

fn pull(p: &Peer, it: &Item, sc: &Scope, raw: &HashMap<String, String>) -> Result<()> {
    let Some(dest) = sc.resolve(&it.rel, false) else { return Ok(()) };
    let parent = dest.parent().unwrap_or(sc.root.as_path()).to_path_buf();
    fs::create_dir_all(&parent)?;
    let mut c = Conn::connect(&p.addr)?;
    let wire = raw.get(&it.rel).unwrap_or(&it.rel);
    c.send(format!("GET /f HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\n{}Connection: close\r\n\r\n", enc(wire), sc.hdr()).as_bytes())?;
    let h = c.read_head()?;
    if h.status() != 200 {
        return Err(status_err(&it.rel, &h.status().to_string()));
    }
    let len = h.header("content-length").and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
    let fname = dest.file_name().and_then(|n| n.to_str()).unwrap_or("f");
    let tmp = parent.join(format!("{fname}.{}.part", rand8()));
    let mut f = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&tmp)
        .map_err(|_| status_err(&it.rel, &l("yazılamadı", "write failed")))?;
    let res = (|| -> Result<()> {
        c.read_body(len, &mut |b| f.write_all(b))?;
        if let Some(mt) = h.header("x-mtime").and_then(|v| v.parse::<i64>().ok()).map(ms).filter(|&m| m > 0) {
            let _ = f.set_modified(time_from_ms(mt));
        }
        Ok(())
    })();
    drop(f);
    if let Err(e) = res {
        let _ = fs::remove_file(&tmp);
        return Err(e);
    }
    let moved = (|| -> io::Result<()> {
        if dest.exists() {
            remove_any(&dest)?
        }
        fs::rename(&tmp, &dest)
    })();
    if let Err(e) = moved {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    Ok(())
}

fn push(p: &Peer, it: &Item, sc: &Scope) -> Result<()> {
    let Some(f) = sc.resolve(&it.rel, false) else { return Ok(()) };
    let md = fs::metadata(&f)?;
    let len = md.len();
    let mt = mtime_ms(&md);
    let mut c = Conn::connect(&p.addr)?;
    let head = format!(
        "POST /u HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\nX-Sync: 1\r\nX-Mtime: {}\r\n{}Content-Length: {}\r\nConnection: close\r\n\r\n",
        enc(&it.rel),
        mt,
        sc.hdr(),
        len
    );
    c.send(head.as_bytes())?;
    let mut fh = File::open(&f)?;
    let mut buf = vec![0u8; 256 * 1024];
    let mut sent = 0u64;
    while sent < len {
        let want = ((len - sent) as usize).min(buf.len());
        let n = io::Read::read(&mut fh, &mut buf[..want])?;
        if n == 0 {
            break;
        }
        c.send(&buf[..n])?;
        sent += n as u64;
    }
    if sent != len {
        return Err(status_err(&it.rel, &l("dosya değişti", "file changed")));
    }
    if c.read_head()?.status() != 200 {
        return Err(status_err(&it.rel, &l("karşı cihaz kabul etmedi", "refused")));
    }
    Ok(())
}

fn mkdir_remote(p: &Peer, rel: &str, sc: &Scope) -> Result<()> {
    let mut c = Conn::connect(&p.addr)?;
    c.send(format!("POST /d HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\n{}Content-Length: 0\r\nConnection: close\r\n\r\n", enc(rel), sc.hdr()).as_bytes())?;
    if c.read_head()?.status() != 200 {
        return Err(status_err(rel, "mkdir"));
    }
    Ok(())
}

fn delete_remote(p: &Peer, rel: &str, dir: bool, sc: &Scope) -> Result<()> {
    // Bilerek DELETE ve Content-Length yok: bu özelliği bilmeyen eski sürüm isteği reddeder (boş dosya oluşturmaz).
    let mut c = Conn::connect(&p.addr)?;
    c.send(
        format!(
            "DELETE /x HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\nX-Dir: {}\r\n{}Connection: close\r\n\r\n",
            enc(rel),
            if dir { 1 } else { 0 },
            sc.hdr()
        )
        .as_bytes(),
    )?;
    if c.read_head()?.status() != 200 {
        return Err(status_err(rel, &l("silinemedi / eski sürüm", "could not delete / old version")));
    }
    Ok(())
}

// ---------- Protokol 2: toplu aktarım ----------

/// Klasörler ve küçük dosyalar bir pakette toplanır; büyük dosya kendi paketini (kendi bağlantısını) alır.
fn chunks(items: &[Item]) -> Vec<Vec<Item>> {
    let mut out: Vec<Vec<Item>> = Vec::new();
    let mut cur: Vec<Item> = Vec::new();
    let mut bytes = 0i64;
    for it in items {
        if !cur.is_empty() && (bytes + it.size > CHUNK_BYTES || cur.len() >= CHUNK_FILES) {
            out.push(std::mem::take(&mut cur));
            bytes = 0;
        }
        cur.push(it.clone());
        bytes += it.size;
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

/// Paketi tek akışta gönderir. Dönüş: (pakette dosya sayısı, karşıda yazılamayan kayıt sayısı).
fn push_bundle(p: &Peer, chunk: &[Item], root: &Path) -> Result<(u32, u32)> {
    let mut c = Conn::connect(&p.addr)?;
    c.send(b"POST /b HTTP/1.1\r\nHost: qs\r\nX-Plan: 2\r\nConnection: close\r\n\r\n")?;
    {
        let mut w = BufWriter::with_capacity(1 << 20, c.stream_mut());
        bundle::send_items(&mut w, root, chunk)?;
        w.flush()?;
    }
    let h = c.read_head()?;
    if h.status() != 200 {
        return Err(status_err("bundle", &h.status().to_string()));
    }
    let files = chunk.iter().filter(|i| !i.dir).count() as u32;
    Ok((files, h.header("x-fail").and_then(|v| v.parse().ok()).unwrap_or(0)))
}

/// Paketteki dosyaları tek akışta alır. Dönüş: (alınan dosya, yazılamayan kayıt).
fn pull_bundle(p: &Peer, chunk: &[Item], root: &Path) -> Result<(u32, u32)> {
    let names = chunk.iter().map(|i| i.rel.as_str()).collect::<Vec<_>>().join("\n");
    let mut c = Conn::connect(&p.addr)?;
    c.send(
        format!("POST /g HTTP/1.1\r\nHost: qs\r\nX-Plan: 2\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", names.len())
            .as_bytes(),
    )?;
    c.send(names.as_bytes())?;
    let h = c.read_head()?;
    if h.status() != 200 {
        return Err(status_err("bundle", &h.status().to_string()));
    }
    let st = bundle::receive(&mut c, root)?;
    Ok((st.files, st.failed))
}

// ---------- Plan ----------

#[derive(Default)]
struct Plan {
    push: Vec<Item>,
    pull: Vec<Item>,
    mk_remote: Vec<Item>,
    mk_local: Vec<String>,
    del_remote: Vec<(String, bool)>,
    del_local: Vec<(String, bool)>,
}

/// Üst klasörler önce: önce `/` sayısı, sonra ad.
fn ordered<'a>(keys: impl IntoIterator<Item = &'a String>) -> Vec<String> {
    let mut v: Vec<String> = keys.into_iter().cloned().collect();
    v.sort_by(|a, b| {
        let (ca, cb) = (a.matches('/').count(), b.matches('/').count());
        ca.cmp(&cb).then_with(|| a.cmp(b))
    });
    v
}

fn in_gone(gone: &[String], k: &str) -> bool {
    gone.iter().any(|g| k.starts_with(&format!("{g}/")))
}

fn plan_merge(loc: &HashMap<String, Item>, remote: &HashMap<String, Item>) -> Plan {
    let mut pl = Plan::default();
    let keys: HashSet<&String> = loc.keys().chain(remote.keys()).collect();
    for k in ordered(keys) {
        let (l_, r_) = (loc.get(&k), remote.get(&k));
        if l_.or(r_).map(|x| x.dir) == Some(true) {
            match (l_, r_) {
                (None, _) => pl.mk_local.push(k),
                (Some(l), None) => pl.mk_remote.push(l.clone()),
                _ => {}
            }
            continue;
        }
        match (l_, r_) {
            (Some(l), Some(r)) => {
                if l.mtime - r.mtime > TOL_MS {
                    pl.push.push(l.clone());
                } else if r.mtime - l.mtime > TOL_MS {
                    pl.pull.push(r.clone());
                }
            }
            (Some(l), None) => pl.push.push(l.clone()),
            (None, Some(r)) => pl.pull.push(r.clone()),
            (None, None) => {}
        }
    }
    pl
}

/// Mod 1: karşı cihaz bu cihazın aynısı olur.
fn plan_mirror_remote(loc: &HashMap<String, Item>, remote: &HashMap<String, Item>) -> Plan {
    let mut pl = Plan::default();
    let mut gone: Vec<String> = Vec::new();
    let stale: Vec<String> = remote
        .iter()
        .filter(|(k, r)| loc.get(*k).map_or(true, |l| l.dir != r.dir))
        .map(|(k, _)| k.clone())
        .collect();
    for k in ordered(&stale) {
        if in_gone(&gone, &k) {
            continue;
        }
        let r = &remote[&k];
        pl.del_remote.push((k.clone(), r.dir));
        if r.dir {
            gone.push(k);
        }
    }
    for k in ordered(loc.keys()) {
        let l = &loc[&k];
        let mut r = remote.get(&k);
        if let Some(x) = r {
            if x.dir != l.dir || gone.contains(&k) {
                r = None;
            }
        }
        if l.dir {
            if r.is_none() {
                pl.mk_remote.push(l.clone());
            }
        } else if r.map_or(true, |r| r.size != l.size || (l.mtime - r.mtime).abs() > TOL_MS) {
            pl.push.push(l.clone());
        }
    }
    pl
}

/// Mod 2: bu cihaz karşıdakinin aynısı olur.
fn plan_mirror_local(loc: &HashMap<String, Item>, remote: &HashMap<String, Item>) -> Plan {
    let mut pl = Plan::default();
    let mut gone: Vec<String> = Vec::new();
    let stale: Vec<String> = loc
        .iter()
        .filter(|(k, l)| remote.get(*k).map_or(true, |r| r.dir != l.dir))
        .map(|(k, _)| k.clone())
        .collect();
    for k in ordered(&stale) {
        if in_gone(&gone, &k) {
            continue;
        }
        let l = &loc[&k];
        pl.del_local.push((k.clone(), l.dir));
        if l.dir {
            gone.push(k);
        }
    }
    for k in ordered(remote.keys()) {
        let r = &remote[&k];
        let mut l = loc.get(&k);
        if let Some(x) = l {
            if x.dir != r.dir || gone.contains(&k) {
                l = None;
            }
        }
        if r.dir {
            if l.is_none() {
                pl.mk_local.push(k);
            }
        } else if l.map_or(true, |l| l.size != r.size || (l.mtime - r.mtime).abs() > TOL_MS) {
            pl.pull.push(r.clone());
        }
    }
    pl
}

// ---------- Yürütme ----------

fn note(errs: &mut Vec<String>, peer: &str, e: impl std::fmt::Display) {
    errs.push(format!("{peer}: {e}"));
}

fn note_fail(errs: &mut Vec<String>, peer: &str, fail: u32) {
    if fail > 0 {
        note(errs, peer, l(&format!("{fail} öğe yazılamadı"), &format!("{fail} item(s) failed")));
    }
}

/// Planı uygular; (gönderilen, alınan, silinen) döndürür. Hatalar `errs`'e eklenir, işlem sürer.
fn execute(p: &Peer, proto: u8, sc: &Scope, raw: &HashMap<String, String>, plan: &Plan, errs: &mut Vec<String>) -> (u32, u32, u32) {
    let (mut sent, mut got, mut deleted) = (0u32, 0u32, 0u32);

    // 1) Silmeler (Android'de `sc.trash` doluysa geri alınabilir: .onemsiz klasörüne taşınır)
    for (rel, dir) in &plan.del_remote {
        match delete_remote(p, raw.get(rel).map(|x| x.as_str()).unwrap_or(rel.as_str()), *dir, sc) {
            Ok(()) => deleted += 1,
            Err(e) => note(errs, &p.name, e),
        }
    }
    for (rel, dir) in &plan.del_local {
        if let Some(f) = sc.resolve(rel, *dir) {
            match if f.exists() { sc.remove(&f) } else { Ok(()) } {
                Ok(()) => deleted += 1,
                Err(e) => note(errs, &p.name, e),
            }
        }
    }

    // 2) Yerel klasörler
    for d in &plan.mk_local {
        if let Some(dir) = sc.resolve(d, true) {
            if let Err(e) = fs::create_dir_all(dir) {
                note(errs, &p.name, e);
            }
        }
    }

    // 3) Gönder
    if proto >= bundle::PROTO {
        let mut items = plan.mk_remote.clone(); // klasörler önce
        items.extend(plan.push.iter().cloned());
        let cs = chunks(&items);
        for r in par_map(&cs, WORKERS, &|c: &Vec<Item>| push_bundle(p, c, &sc.root)) {
            match r {
                Ok((files, fail)) => {
                    sent += files.saturating_sub(fail);
                    note_fail(errs, &p.name, fail);
                }
                Err(e) => note(errs, &p.name, e),
            }
        }
    } else {
        for d in &plan.mk_remote {
            if let Err(e) = mkdir_remote(p, &d.rel, sc) {
                note(errs, &p.name, e);
            }
        }
        for r in par_map(&plan.push, WORKERS, &|it: &Item| push(p, it, sc)) {
            match r {
                Ok(()) => sent += 1,
                Err(e) => note(errs, &p.name, e),
            }
        }
    }

    // 4) Al
    if proto >= bundle::PROTO {
        let cs = chunks(&plan.pull);
        for r in par_map(&cs, WORKERS, &|c: &Vec<Item>| pull_bundle(p, c, &sc.root)) {
            match r {
                Ok((files, fail)) => {
                    got += files;
                    note_fail(errs, &p.name, fail);
                }
                Err(e) => note(errs, &p.name, e),
            }
        }
    } else {
        for r in par_map(&plan.pull, WORKERS, &|it: &Item| pull(p, it, sc, raw)) {
            match r {
                Ok(()) => got += 1,
                Err(e) => note(errs, &p.name, e),
            }
        }
    }
    (sent, got, deleted)
}

/// Seçilen cihazlarla eşitler (ağ + disk; arka plan iş parçacığında çağrılmalı).
/// mode: 0 = iki cihazda aynı olsun, 1 = bu cihaza göre, 2 = karşıdaki cihaza göre (ilk seçilen).
pub fn run(mode: u32, scope: Option<&Scope>, targets: &[Peer]) -> Outcome {
    let mut res = Outcome { mode, ..Default::default() };
    let Some(sc) = scope else {
        res.error = Some(l("Link klasörü yok", "no Link folder"));
        return res;
    };
    if targets.is_empty() {
        res.error = Some(l("cihaz seçilmedi", "no device selected"));
        return res;
    }
    let list: &[Peer] = if mode == 2 { &targets[..1] } else { targets };
    for p in list {
        let mut errs = Vec::new();
        let r: Result<()> = (|| {
            // Yerel tarama ile manifesto indirme paralel
            let (loc, m) = std::thread::scope(|s| {
                let h = s.spawn(|| local(sc));
                let m = fetch_manifest(p, sc);
                (h.join().unwrap_or_default(), m)
            });
            let m = m?;
            let plan = match mode {
                1 => plan_mirror_remote(&loc, &m.items),
                2 => plan_mirror_local(&loc, &m.items),
                _ => plan_merge(&loc, &m.items),
            };
            let (s, g, d) = execute(p, m.proto, sc, &m.raw, &plan, &mut errs);
            res.sent += s;
            res.got += g;
            res.deleted += d;
            Ok(())
        })();
        res.errors.append(&mut errs);
        match r {
            Ok(()) => res.peers += 1,
            Err(e) => res.errors.push(format!("{}: {}", p.name, e)),
        }
    }
    res
}
