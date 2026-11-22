//! Protokol yardımcıları: kodlama, ad temizleme, güvenli yol çözümleme, başlık ayrıştırma.

use std::collections::HashMap;
use std::fmt;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub const MAX_HEADER: usize = 16 * 1024;
/// Dosya sistemlerinin zaman çözünürlüğü farkı için tolerans (ms).
pub const TOL_MS: i64 = 2000;
pub const MAX_DEPTH: usize = 24;

static ENGLISH: AtomicBool = AtomicBool::new(false);

/// Hata/ileti dili (Swift `Lang.isTurkish` karşılığı).
pub fn set_english(on: bool) {
    ENGLISH.store(on, Ordering::Relaxed);
}

/// Türkçe/İngilizce metin seçici (Swift `L`).
pub fn l(tr: &str, en: &str) -> String {
    if ENGLISH.load(Ordering::Relaxed) { en.to_string() } else { tr.to_string() }
}

#[derive(Debug, Clone)]
pub struct ShareError(pub String);

impl fmt::Display for ShareError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for ShareError {}

impl From<io::Error> for ShareError {
    fn from(e: io::Error) -> Self {
        match e.kind() {
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => ShareError(l("zaman aşımı", "timeout")),
            io::ErrorKind::UnexpectedEof => ShareError(l("alma yarım kaldı", "incomplete")),
            _ => ShareError(e.to_string()),
        }
    }
}

pub type Result<T> = std::result::Result<T, ShareError>;

pub fn err<T>(tr: &str, en: &str) -> Result<T> {
    Err(ShareError(l(tr, en)))
}

/// Yüzde kodlama: yalnızca ASCII harf/rakam ve `-._~` açık kalır (UTF-8 baytları %XX olur).
pub fn enc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            o.push(b as char);
        } else {
            o.push_str(&format!("%{:02X}", b));
        }
    }
    o
}

fn hexv(b: Option<&u8>) -> Option<u8> {
    match *b? {
        c @ b'0'..=b'9' => Some(c - b'0'),
        c @ b'a'..=b'f' => Some(c - b'a' + 10),
        c @ b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// `+` → boşluk, sonra yüzde çözme. Geçersizse girdi olduğu gibi döner (Swift ile aynı).
pub fn dec(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => match (hexv(b.get(i + 1)), hexv(b.get(i + 2))) {
                (Some(h), Some(lo)) => {
                    out.push(h << 4 | lo);
                    i += 3;
                }
                _ => return s.to_string(),
            },
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8(out).unwrap_or_else(|_| s.to_string())
}

pub fn is_note_name(n: &str) -> bool {
    let l = n.to_lowercase();
    !l.ends_with(".part") && (l.ends_with(".url") || l.ends_with(".txt"))
}

/// Android ile aynı temizleme: yol parçaları atılır, kontrol karakterleri ve `: * ? " < > |` → `_`,
/// baştaki noktalar atılır, en çok 120 karakter.
pub fn clean_name(s: &str) -> String {
    let n = s.rsplit('/').next().unwrap_or(s);
    let n = n.rsplit('\\').next().unwrap_or(n);
    let n: String = n
        .chars()
        .map(|c| if (c as u32) < 32 || ":*?\"<>|".contains(c) { '_' } else { c })
        .collect();
    let n: String = n.trim_start_matches('.').chars().take(120).collect();
    if n.is_empty() { "file".to_string() } else { n }
}

/// Aynı adlı dosya varsa üzerine yazmak yerine `ad (2).uzantı` üretir.
pub fn unique(dir: &Path, name: &str) -> PathBuf {
    let mut u = dir.join(name);
    if !u.exists() && u.symlink_metadata().is_err() {
        return u;
    }
    let (base, ext) = match name.rfind('.') {
        Some(i) if i > 0 && i + 1 < name.len() => (&name[..i], &name[i + 1..]),
        _ => (name, ""),
    };
    let mut i = 2;
    while u.exists() || u.symlink_metadata().is_ok() {
        u = dir.join(if ext.is_empty() { format!("{base} ({i})") } else { format!("{base} ({i}).{ext}") });
        i += 1;
    }
    u
}

/// 8 onaltılık haneli rastgele ek (`.part` dosyaları için).
pub fn rand8() -> String {
    static C: AtomicU64 = AtomicU64::new(0);
    let n = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
    let mut x = n ^ (std::process::id() as u64).rotate_left(32) ^ C.fetch_add(0x9E37_79B9_7F4A_7C15, Ordering::Relaxed);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    x ^= x >> 31;
    format!("{:08x}", x as u32)
}

/// Var olan en uzun üst yolu `canonicalize` eder, kalanı ekler (henüz olmayan dosyalar için).
fn canon_lenient(p: &Path) -> PathBuf {
    let mut tail = Vec::new();
    let mut cur = p.to_path_buf();
    loop {
        if let Ok(c) = cur.canonicalize() {
            let mut r = c;
            for t in tail.iter().rev() {
                r.push(t);
            }
            return r;
        }
        match (cur.file_name().map(|s| s.to_os_string()), cur.parent().map(|p| p.to_path_buf())) {
            (Some(n), Some(par)) => {
                tail.push(n);
                cur = par;
            }
            _ => {
                let mut r = cur;
                for t in tail.iter().rev() {
                    r.push(t);
                }
                return r;
            }
        }
    }
}

/// Android `NoteSync.isNoteName`: Link'te yalnızca `.url`/`.txt`; Download'da (`dl`) gizli ya da geçici olmayan her dosya.
pub fn is_sync_name(n: &str, dl: bool) -> bool {
    if !dl {
        return is_note_name(n);
    }
    let l = n.to_lowercase();
    !n.starts_with('.')
        && !n.chars().any(|c| (c as u32) < 32 || ":*?\"<>|\\".contains(c))
        && !l.ends_with(".part")
        && !l.ends_with(".tmp")
        && !l.ends_with(".crdownload")
}

/// Android `uniqueFile`: aynı adlı dosya varsa `ad_1.uzantı`, `ad_2.uzantı`… üretir.
pub fn unique_us(dir: &Path, name: &str) -> PathBuf {
    let mut t = dir.join(name);
    let (base, ext) = match name.rfind('.') {
        Some(i) => (&name[..i], &name[i..]),
        None => (name, ""),
    };
    let mut n = 1;
    while t.exists() || t.symlink_metadata().is_ok() {
        t = dir.join(format!("{base}_{n}{ext}"));
        n += 1;
    }
    t
}

/// Karşı taraftan gelen göreli yolu Link klasörü içinde güvenli bir yola çevirir; geçersizse `None`.
/// Her bölüm ayrı doğrulanır (`..`, kontrol karakterleri yok), sonuç Link dışına çıkamaz.
pub fn resolve(rel: &str, root: &Path, dir: bool) -> Option<PathBuf> {
    resolve_scoped(rel, root, dir, false)
}

/// `resolve`'un Download (`dl`) kipini de bilen sürümü.
pub fn resolve_scoped(rel: &str, root: &Path, dir: bool, dl: bool) -> Option<PathBuf> {
    let segs: Vec<&str> = rel.split('/').collect();
    if segs.is_empty() || segs.len() > MAX_DEPTH {
        return None;
    }
    for sg in &segs {
        if sg.is_empty() || *sg == "." || *sg == ".." || sg.len() > 240 {
            return None;
        }
        if sg.chars().any(|c| (c as u32) < 32 || ":*?\"<>|\\".contains(c)) {
            return None;
        }
    }
    if !dir {
        let last = segs.last()?;
        if !(is_sync_name(last, dl) || (!dl && *last == ".order.txt")) {
            return None;
        }
    }
    let mut f = root.to_path_buf();
    for sg in &segs {
        f.push(sg);
    }
    let bc = canon_lenient(root);
    let fc = canon_lenient(&f);
    if fc != bc && fc.starts_with(&bc) { Some(f) } else { None }
}

pub fn mtime_ms(md: &std::fs::Metadata) -> i64 {
    md.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn time_from_ms(ms: i64) -> SystemTime {
    UNIX_EPOCH + std::time::Duration::from_millis(ms.max(0) as u64)
}

/// `items` üzerinde `f`'yi en çok `workers` iş parçacığıyla paralel çalıştırır (sonuç sırası belirsiz).
pub fn par_map<T: Sync, R: Send>(items: &[T], workers: usize, f: &(dyn Fn(&T) -> R + Sync)) -> Vec<R> {
    if items.is_empty() {
        return Vec::new();
    }
    let next = AtomicUsize::new(0);
    let out = Mutex::new(Vec::with_capacity(items.len()));
    std::thread::scope(|s| {
        for _ in 0..workers.clamp(1, items.len()) {
            s.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= items.len() {
                    break;
                }
                let r = f(&items[i]);
                out.lock().unwrap().push(r);
            });
        }
    });
    out.into_inner().unwrap()
}

// ---------- HTTP benzeri başlık ----------

pub fn find_head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

pub struct Head {
    pub first: Vec<String>,
    pub headers: HashMap<String, String>,
}

impl Head {
    pub fn method(&self) -> &str {
        self.first.first().map(|s| s.as_str()).unwrap_or("")
    }
    pub fn target(&self) -> &str {
        self.first.get(1).map(|s| s.as_str()).unwrap_or("")
    }
    /// Yanıt satırındaki durum kodu (`HTTP/1.1 200 OK` → 200).
    pub fn status(&self) -> u16 {
        self.first.get(1).and_then(|s| s.parse().ok()).unwrap_or(0)
    }
    pub fn header(&self, k: &str) -> Option<&str> {
        self.headers.get(k).map(|s| s.as_str())
    }
}

/// `head` son `\r\n\r\n` hariç başlık bloğudur. Başlık adları küçük harfe çevrilir, değerler kırpılır.
pub fn parse_head(head: &[u8]) -> Head {
    let text = String::from_utf8_lossy(head);
    let mut lines = text.split("\r\n");
    let first = lines.next().unwrap_or("").split(' ').filter(|s| !s.is_empty()).map(String::from).collect();
    let mut headers = HashMap::new();
    for ln in lines {
        if let Some(i) = ln.find(':') {
            headers.insert(ln[..i].to_lowercase(), ln[i + 1..].trim().to_string());
        }
    }
    Head { first, headers }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enc_dec_roundtrip() {
        let s = "Güzel not (1).txt";
        assert_eq!(dec(&enc(s)), s);
        assert_eq!(enc("a b"), "a%20b");
        assert_eq!(dec("a+b%20c"), "a b c");
        assert_eq!(dec("%zz"), "%zz");
    }

    #[test]
    fn clean_name_rules() {
        assert_eq!(clean_name("../../etc/passwd"), "passwd");
        assert_eq!(clean_name("C:\\x\\y?.txt"), "y_.txt");
        assert_eq!(clean_name("...gizli"), "gizli");
        assert_eq!(clean_name("..."), "file");
        assert_eq!(clean_name(&"a".repeat(300)).chars().count(), 120);
    }

    #[test]
    fn resolve_is_safe() {
        let d = std::env::temp_dir().join(format!("plan_proto_{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        assert!(resolve("../evil.url", &d, false).is_none());
        assert!(resolve("a/../../evil.url", &d, false).is_none());
        assert!(resolve("note.exe", &d, false).is_none());
        assert!(resolve("/abs.url", &d, false).is_none());
        assert!(resolve("folder/note.url", &d, false).is_some());
        assert!(resolve("folder", &d, true).is_some());
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn unique_adds_counter() {
        let d = std::env::temp_dir().join(format!("plan_uniq_{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("a.txt"), "x").unwrap();
        assert_eq!(unique(&d, "a.txt").file_name().unwrap(), "a (2).txt");
        assert_eq!(unique(&d, "b.txt").file_name().unwrap(), "b.txt");
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn head_parsing() {
        let h = parse_head(b"POST /u HTTP/1.1\r\nX-Name: a%20b\r\nContent-Length:  12 ");
        assert_eq!(h.method(), "POST");
        assert_eq!(h.target(), "/u");
        assert_eq!(h.header("content-length"), Some("12"));
        assert_eq!(parse_head(b"HTTP/1.1 200 OK").status(), 200);
    }
}
