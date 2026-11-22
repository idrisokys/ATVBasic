//! Dosya seçici için klasör listeleme ve içerik sağlayıcı için güvenli yol çözümleme.

use std::fs;
use std::path::Path;

pub struct Entry {
    pub name: String,
    pub dir: bool,
    pub size: u64,
}

/// Klasör içeriği: gizliler (`.` ile başlayanlar) hariç; önce klasörler, sonra dosyalar; her grup küçük harfe duyarsız ada göre.
/// Klasör okunamazsa `None`. Sembolik bağlar hedeflerinin türüne göre sınıflanır; bozuk bağlar atlanır.
pub fn list_dir(dir: &str) -> Option<Vec<Entry>> {
    let rd = fs::read_dir(dir).ok()?;
    let mut out: Vec<Entry> = Vec::new();
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        if name.starts_with('.') {
            continue;
        }
        let Ok(md) = fs::metadata(e.path()) else { continue };
        if md.is_dir() {
            out.push(Entry { name, dir: true, size: 0 });
        } else if md.is_file() {
            out.push(Entry { name, dir: false, size: md.len() });
        }
    }
    out.sort_by(|a, b| b.dir.cmp(&a.dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())).then_with(|| a.name.cmp(&b.name)));
    Some(out)
}

/// `path` gerçek yolu (sembolik bağlar çözülmüş) `root` içinde kalan DÜZ bir dosyaysa o yolu, değilse `None`.
pub fn resolve_inside(root: &str, path: &str) -> Option<String> {
    let root = fs::canonicalize(Path::new(root)).ok()?;
    let f = fs::canonicalize(Path::new(path)).ok()?;
    if f != root && !f.starts_with(&root) {
        return None;
    }
    if !fs::metadata(&f).ok()?.is_file() {
        return None;
    }
    f.to_str().map(|s| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("atv_files_{tag}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn lists_dirs_first_sorted_without_hidden() {
        let d = tmp("list");
        fs::create_dir(d.join("Zeta")).unwrap();
        fs::create_dir(d.join("alfa")).unwrap();
        fs::create_dir(d.join(".gizli")).unwrap();
        fs::write(d.join("b.txt"), b"12345").unwrap();
        fs::write(d.join("A.txt"), b"").unwrap();
        fs::write(d.join(".x"), b"1").unwrap();
        let v = list_dir(d.to_str().unwrap()).unwrap();
        let names: Vec<_> = v.iter().map(|e| (e.name.as_str(), e.dir, e.size)).collect();
        assert_eq!(names, [("alfa", true, 0), ("Zeta", true, 0), ("A.txt", false, 0), ("b.txt", false, 5)]);
        assert!(list_dir("/nonexistent/zzz").is_none());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn resolve_blocks_escapes_and_non_files() {
        let d = tmp("resolve");
        let root = d.join("root");
        fs::create_dir_all(root.join("sub")).unwrap();
        fs::write(root.join("sub/a.txt"), b"x").unwrap();
        fs::write(d.join("out.txt"), b"y").unwrap();
        let r = root.to_str().unwrap();
        let canon = fs::canonicalize(&root).unwrap();

        assert_eq!(resolve_inside(r, root.join("sub/a.txt").to_str().unwrap()), Some(canon.join("sub/a.txt").to_str().unwrap().to_string()));
        // .. ile kaçış
        assert!(resolve_inside(r, root.join("sub/../../out.txt").to_str().unwrap()).is_none());
        // kök dışı
        assert!(resolve_inside(r, d.join("out.txt").to_str().unwrap()).is_none());
        // klasör, olmayan dosya
        assert!(resolve_inside(r, root.join("sub").to_str().unwrap()).is_none());
        assert!(resolve_inside(r, root.join("yok").to_str().unwrap()).is_none());
        // kök dışını gösteren sembolik bağ
        std::os::unix::fs::symlink(d.join("out.txt"), root.join("link.txt")).unwrap();
        assert!(resolve_inside(r, root.join("link.txt").to_str().unwrap()).is_none());
        // kök adıyla başlayan kardeş klasör ("root2") kök sayılmaz
        fs::create_dir_all(d.join("root2")).unwrap();
        fs::write(d.join("root2/b.txt"), b"z").unwrap();
        assert!(resolve_inside(r, d.join("root2/b.txt").to_str().unwrap()).is_none());
        let _ = fs::remove_dir_all(&d);
    }
}
