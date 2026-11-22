//! Toplu aktarım biçimi (protokol 2, yalnızca Plan ↔ Plan). Tek bağlantıda yüzlerce küçük dosya:
//! bağlantı kurma ve istek başına gecikme dosya sayısıyla çarpılmaz.
//!
//! Akış: [kayıt]… + bitiş. Kayıt = [ad uzunluğu u32][ad: UTF-8 göreli yol][mtime i64 ms][boyut u64][bayt…]
//! `boyut == DIR_MARK` klasör demektir (bayt yok). Ad uzunluğu 0 = akış sonu. Sayılar little-endian.
//! Boyut önceden toplanmaz: gönderen dosyaları okudukça akıtır (Content-Length gerekmez).

use super::proto::*;
use super::sync::Item;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};

pub const PROTO: u8 = 2;
pub const DIR_MARK: u64 = u64::MAX;
const MAX_NAME: usize = 1024;
const BUF: usize = 1 << 20;

fn write_head<W: Write>(w: &mut W, rel: &str, mtime: i64, size: u64) -> io::Result<()> {
    let n = rel.as_bytes();
    w.write_all(&(n.len() as u32).to_le_bytes())?;
    w.write_all(n)?;
    w.write_all(&mtime.to_le_bytes())?;
    w.write_all(&size.to_le_bytes())
}

/// Öğeleri `w`'ye akıtır; sonuna bitiş işareti yazar. Dönüş: gönderilen dosya sayısı.
/// Okunamayan dosya atlanır; gönderim sırasında küçülen dosya akışı keser (alıcı kesik dosyayı atar).
pub fn send_items<W: Write>(w: &mut W, root: &Path, items: &[Item]) -> Result<u32> {
    let mut buf = vec![0u8; BUF];
    let mut files = 0;
    for it in items {
        if it.dir {
            write_head(w, &it.rel, 0, DIR_MARK)?;
            continue;
        }
        let Some(path) = resolve(&it.rel, root, false) else { continue };
        let Ok(mut f) = File::open(&path) else { continue };
        let Ok(md) = f.metadata() else { continue };
        let size = md.len();
        write_head(w, &it.rel, mtime_ms(&md), size)?;
        let mut left = size;
        while left > 0 {
            let want = (left as usize).min(buf.len());
            let n = f.read(&mut buf[..want])?;
            if n == 0 {
                return err("dosya değişti", "file changed");
            }
            w.write_all(&buf[..n])?;
            left -= n as u64;
        }
        files += 1;
    }
    w.write_all(&0u32.to_le_bytes())?;
    Ok(files)
}

#[derive(Default, Debug)]
pub struct Stats {
    pub files: u32,
    pub dirs: u32,
    pub failed: u32,
}

/// Akışı bitiş işaretine kadar okuyup `root` altına yazar. Her dosya `.part`'a yazılır, tamamlanınca
/// değişiklik zamanı korunarak yerine taşınır. Geçersiz/yazılamayan kayıt sayılır ama akış hizası bozulmaz.
/// Akış ortada kesilirse `Err` (yarım `.part` silinir).
pub fn receive<R: Read>(r: &mut R, root: &Path) -> Result<Stats> {
    let mut st = Stats::default();
    let mut buf = vec![0u8; BUF];
    loop {
        let mut b4 = [0u8; 4];
        r.read_exact(&mut b4)?;
        let n = u32::from_le_bytes(b4) as usize;
        if n == 0 {
            return Ok(st);
        }
        if n > MAX_NAME {
            return err("geçersiz akış", "invalid stream");
        }
        let mut nb = vec![0u8; n];
        r.read_exact(&mut nb)?;
        let rel = String::from_utf8_lossy(&nb).into_owned();
        let mut b8 = [0u8; 8];
        r.read_exact(&mut b8)?;
        let mtime = i64::from_le_bytes(b8);
        r.read_exact(&mut b8)?;
        let size = u64::from_le_bytes(b8);

        if size == DIR_MARK {
            match resolve(&rel, root, true) {
                Some(d) if fs::create_dir_all(&d).is_ok() => st.dirs += 1,
                _ => st.failed += 1,
            }
            continue;
        }

        let dest = resolve(&rel, root, false);
        let mut out: Option<(File, PathBuf)> = None;
        if let Some(d) = &dest {
            if let Some(par) = d.parent() {
                let _ = fs::create_dir_all(par);
                let fname = d.file_name().and_then(|n| n.to_str()).unwrap_or("f");
                let tmp = par.join(format!("{fname}.{}.part", rand8()));
                if let Ok(f) = OpenOptions::new().write(true).create_new(true).open(&tmp) {
                    out = Some((f, tmp));
                }
            }
        }
        let mut werr = out.is_none();
        let mut left = size;
        while left > 0 {
            let want = (left as usize).min(buf.len());
            if let Err(e) = r.read_exact(&mut buf[..want]) {
                if let Some((f, t)) = out.take() {
                    drop(f);
                    let _ = fs::remove_file(t);
                }
                return Err(e.into());
            }
            if !werr {
                if let Some((f, _)) = out.as_mut() {
                    if f.write_all(&buf[..want]).is_err() {
                        werr = true;
                    }
                }
            }
            left -= want as u64;
        }
        match (out.take(), &dest) {
            (Some((f, tmp)), Some(d)) if !werr => {
                if mtime > 0 {
                    let _ = f.set_modified(time_from_ms(mtime));
                }
                drop(f);
                let ok = (|| -> io::Result<()> {
                    if d.is_dir() { fs::remove_dir_all(d)? } else if d.exists() { fs::remove_file(d)? }
                    fs::rename(&tmp, d)
                })()
                .is_ok();
                if ok {
                    st.files += 1;
                } else {
                    let _ = fs::remove_file(&tmp);
                    st.failed += 1;
                }
            }
            (Some((f, tmp)), _) => {
                drop(f);
                let _ = fs::remove_file(tmp);
                st.failed += 1;
            }
            (None, _) => st.failed += 1,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_through_memory() {
        let base = std::env::temp_dir().join(format!("plan_bundle_{}", std::process::id()));
        let (a, b) = (base.join("a"), base.join("b"));
        fs::create_dir_all(a.join("alt")).unwrap();
        fs::create_dir_all(&b).unwrap();
        fs::write(a.join("alt/n.txt"), "merhaba").unwrap();
        fs::write(a.join("e.url"), "").unwrap();
        let items = vec![
            Item { rel: "alt".into(), dir: true, size: 0, mtime: 0 },
            Item { rel: "alt/n.txt".into(), dir: false, size: 7, mtime: 0 },
            Item { rel: "e.url".into(), dir: false, size: 0, mtime: 0 },
            Item { rel: "yok.txt".into(), dir: false, size: 0, mtime: 0 },
        ];
        let mut wire = Vec::new();
        assert_eq!(send_items(&mut wire, &a, &items).unwrap(), 2);
        let st = receive(&mut &wire[..], &b).unwrap();
        assert_eq!((st.files, st.dirs, st.failed), (2, 1, 0));
        assert_eq!(fs::read_to_string(b.join("alt/n.txt")).unwrap(), "merhaba");
        // kesik akış hata verir ve .part bırakmaz
        let cut = &wire[..wire.len() - 6];
        assert!(receive(&mut &cut[..], &base.join("c")).is_err());
        let _ = fs::remove_dir_all(base);
    }

    #[test]
    fn unsafe_names_are_counted_not_written() {
        let base = std::env::temp_dir().join(format!("plan_bundle2_{}", std::process::id()));
        fs::create_dir_all(&base).unwrap();
        let mut wire = Vec::new();
        write_head(&mut wire, "../evil.txt", 0, 2).unwrap();
        wire.extend_from_slice(b"hi");
        wire.extend_from_slice(&0u32.to_le_bytes());
        let st = receive(&mut &wire[..], &base).unwrap();
        assert_eq!(st.failed, 1);
        assert!(!base.parent().unwrap().join("evil.txt").exists());
        let _ = fs::remove_dir_all(base);
    }
}
