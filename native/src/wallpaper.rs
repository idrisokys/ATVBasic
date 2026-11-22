//! Duvar kağıdı: Wallhaven adres havuzu ve görüntü işleme.
//!
//! HTTPS aktarımı (Rust std'de TLS yoktur) Android tarafında `HttpURLConnection` ile yapılır; geri kalan her şey burada:
//! * arama adresi, yanıtı ayrıştırma (JSON), karıştırılmış adres havuzu ve 60 dk geçerlilik süresi,
//! * indirilen resmi ekran boyutuna ölçekleme (sığdır / doldur), JPEG olarak atomik yazma,
//! * sistem duvar kağıdı için kırpmadan sığdırılmış JPEG.

use crate::json;
use image::codecs::jpeg::JpegEncoder;
use image::imageops::{self, FilterType};
use image::{ExtendedColorType, ImageEncoder, ImageReader, Rgb, RgbImage};
use qshare_core::share::proto::{mtime_ms, rand8};
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const POOL_TTL: Duration = Duration::from_secs(60 * 60);
/// Çok büyük orijinaller atlanır (yavaş, bellek yiyici).
const MAX_IMAGE_BYTES: f64 = 8.0 * 1024.0 * 1024.0;
/// Çözülecek resmin en çok piksel sayısı (bellek koruması; ~180 MB RGB).
const MAX_PIXELS: u64 = 60_000_000;
const LAUNCHER_QUALITY: u8 = 88;
const SYSTEM_QUALITY: u8 = 95;

struct Pool {
    urls: Vec<String>,
    loaded: Option<Instant>,
}

static POOL: Mutex<Pool> = Mutex::new(Pool { urls: Vec::new(), loaded: None });

fn pool() -> std::sync::MutexGuard<'static, Pool> {
    POOL.lock().unwrap_or_else(|e| e.into_inner())
}

fn rand_u64() -> u64 {
    static C: AtomicU64 = AtomicU64::new(0);
    let n = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
    let mut x = n ^ (std::process::id() as u64).rotate_left(32) ^ C.fetch_add(0x9E37_79B9_7F4A_7C15, Ordering::Relaxed);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    x ^ (x >> 31)
}

fn shuffle<T>(v: &mut [T]) {
    for i in (1..v.len()).rev() {
        let j = (rand_u64() % (i as u64 + 1)) as usize;
        v.swap(i, j);
    }
}

// ------------------------------------------------------------------ adresler

/// Wallhaven arama adresi: doğa, 16:9, en az Full HD, en çok favorilenen, yalnızca SFW; rastgele 1–5. sayfa.
pub fn listing_url() -> String {
    let page = 1 + rand_u64() % 5;
    format!(
        "https://wallhaven.cc/api/v1/search?categories=100&purity=100&q=nature&atleast=1920x1080&ratios=16x9&sorting=favorites&order=desc&page={page}"
    )
}

/// Wallhaven erişilemezse kullanılan yedek kaynak.
pub fn fallback_url() -> String {
    let ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    format!("https://picsum.photos/1920/1080.jpg?random={ms}")
}

/// Arama yanıtındaki `data[].path` adresleri (yalnızca https; `file_size` 8 MB üstü olanlar atlanır).
pub fn parse_listing(body: &str) -> Vec<String> {
    let Some(root) = json::parse(body) else { return Vec::new() };
    let Some(data) = root.get("data").and_then(|d| d.as_arr()) else { return Vec::new() };
    data.iter()
        .filter_map(|it| {
            let size = it.get("file_size").and_then(|v| v.as_f64()).unwrap_or(0.0);
            if size > MAX_IMAGE_BYTES {
                return None;
            }
            let path = it.get("path")?.as_str()?;
            if path.starts_with("https://") {
                Some(path.to_string())
            } else {
                None
            }
        })
        .collect()
}

/// Yanıtı ayrıştırıp havuzu yeniler (karıştırır, süreyi başlatır). Havuza giren adres sayısı.
pub fn pool_load(body: &str) -> usize {
    let mut urls = parse_listing(body);
    shuffle(&mut urls);
    let n = urls.len();
    let mut p = pool();
    p.urls = urls;
    p.loaded = Some(Instant::now());
    n
}

/// Havuzdan bir adres alır. Havuz boşsa ya da 60 dk'dan eskiyse `None`: çağıran yeni arama yanıtıyla `pool_load` yapmalı.
pub fn pool_next() -> Option<String> {
    let mut p = pool();
    let stale = p.loaded.map_or(true, |t| t.elapsed() > POOL_TTL);
    if stale {
        p.urls.clear();
        return None;
    }
    p.urls.pop()
}

// ------------------------------------------------------------------ boyutlar

/// Launcher'ın kendi duvar kağıdı: 1080p yeterli, 4K TV'de bellek düşük kalır (görünüm büyütür).
pub fn launcher_target(display_w: u32, display_h: u32) -> (u32, u32) {
    (display_w.max(display_h).min(1920), display_w.min(display_h).min(1080))
}

/// Sistem duvar kağıdı: telefonda dikey, TV'de yatay ekran boyutu; en uzun kenar 2560'ı geçmez.
pub fn system_target(display_w: u32, display_h: u32, tv: bool) -> (u32, u32) {
    let mut a = display_w.min(display_h) as f64;
    let mut b = display_w.max(display_h) as f64;
    if b > 2560.0 {
        a = (a * 2560.0 / b).round();
        b = 2560.0;
    }
    let (a, b) = (a.max(1.0) as u32, b as u32);
    if tv {
        (b, a)
    } else {
        (a, b)
    }
}

// ------------------------------------------------------------------ görüntü

/// `src`'yi `tw`×`th` tuvale yerleştirir. `fit`: bütün resim görünür (kenarlar siyah); değilse tuval dolar, taşan kırpılır.
fn render(src: &str, tw: u32, th: u32, fit: bool) -> Option<RgbImage> {
    if tw == 0 || th == 0 {
        return None;
    }
    let (w, h) = ImageReader::open(src).ok()?.with_guessed_format().ok()?.into_dimensions().ok()?;
    if w == 0 || h == 0 || (w as u64) * (h as u64) > MAX_PIXELS {
        return None;
    }
    let img = ImageReader::open(src).ok()?.with_guessed_format().ok()?.decode().ok()?.into_rgb8();

    let (sx, sy) = (tw as f64 / w as f64, th as f64 / h as f64);
    let s = if fit { sx.min(sy) } else { sx.max(sy) };
    let nw = ((w as f64 * s).round() as u32).max(1);
    let nh = ((h as f64 * s).round() as u32).max(1);
    let scaled = if (nw, nh) == (w, h) { img } else { imageops::resize(&img, nw, nh, FilterType::Triangle) };

    let mut canvas = RgbImage::from_pixel(tw, th, Rgb([0, 0, 0]));
    let x = (tw as i64 - nw as i64) / 2;
    let y = (th as i64 - nh as i64) / 2;
    imageops::overlay(&mut canvas, &scaled, x, y);
    Some(canvas)
}

/// JPEG'i yanına geçici dosyaya yazıp `dest`'e yeniden adlandırır (okuyan yarım dosya görmez).
fn write_jpeg(img: &RgbImage, dest: &Path, quality: u8) -> bool {
    let tmp = PathBuf::from(format!("{}.{}.tmp", dest.display(), rand8()));
    let wrote = (|| -> Option<()> {
        let mut w = BufWriter::new(File::create(&tmp).ok()?);
        JpegEncoder::new_with_quality(&mut w, quality)
            .write_image(img.as_raw(), img.width(), img.height(), ExtendedColorType::Rgb8)
            .ok()?;
        w.flush().ok()
    })();
    if wrote.is_some() && fs::rename(&tmp, dest).is_ok() {
        true
    } else {
        let _ = fs::remove_file(&tmp);
        false
    }
}

/// Launcher duvar kağıdını üretir: `src` → ekran boyutlu JPEG (`dest`). `src` her durumda silinir.
pub fn import(src: &str, dest: &str, display_w: u32, display_h: u32, fit: bool) -> bool {
    let (tw, th) = launcher_target(display_w, display_h);
    let ok = render(src, tw, th, fit).map_or(false, |im| write_jpeg(&im, Path::new(dest), LAUNCHER_QUALITY));
    let _ = fs::remove_file(src);
    ok
}

/// Sistem (ana ekran / kilit ekranı) duvar kağıdı için kırpmadan sığdırılmış JPEG üretir. `src` silinmez.
pub fn system_image(src: &str, out: &str, display_w: u32, display_h: u32, tv: bool) -> bool {
    let (tw, th) = system_target(display_w, display_h, tv);
    render(src, tw, th, true).map_or(false, |im| write_jpeg(&im, Path::new(out), SYSTEM_QUALITY))
}

/// Duvar kağıdı değişince değişen değer; dosya yoksa 0.
pub fn stamp(path: &str) -> i64 {
    match fs::metadata(path) {
        Ok(m) if m.is_file() => mtime_ms(&m).wrapping_mul(31).wrapping_add(m.len() as i64).max(1),
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("atv_wp_{tag}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    /// Soldaki yarısı kırmızı, sağdaki yarısı mavi PNG.
    fn two_tone_png(path: &Path, w: u32, h: u32) {
        let img = RgbImage::from_fn(w, h, |x, _| if x < w / 2 { Rgb([255, 0, 0]) } else { Rgb([0, 0, 255]) });
        img.save(path).unwrap();
    }

    #[test]
    fn parses_wallhaven_listing() {
        let body = r#"{"data":[
            {"id":"a","path":"https:\/\/w.wallhaven.cc\/full\/aa\/wallhaven-aa.jpg","file_size":1234567},
            {"id":"b","path":"https:\/\/w.wallhaven.cc\/full\/bb\/wallhaven-bb.jpg","file_size":9999999},
            {"id":"c","path":"http:\/\/w.wallhaven.cc\/full\/cc\/wallhaven-cc.jpg","file_size":1000},
            {"id":"d","path":"https:\/\/w.wallhaven.cc\/full\/dd\/wallhaven-dd.png"},
            {"id":"e"}
        ],"meta":{"current_page":1}}"#;
        let v = parse_listing(body);
        assert_eq!(
            v,
            [
                "https://w.wallhaven.cc/full/aa/wallhaven-aa.jpg",
                "https://w.wallhaven.cc/full/dd/wallhaven-dd.png"
            ]
        );
        assert!(parse_listing("not json").is_empty());
        assert!(parse_listing("{\"data\":5}").is_empty());
    }

    #[test]
    fn pool_serves_each_url_once_then_asks_for_reload() {
        let body = r#"{"data":[
            {"path":"https://x/1.jpg","file_size":1},
            {"path":"https://x/2.jpg","file_size":1},
            {"path":"https://x/3.jpg","file_size":1}]}"#;
        assert_eq!(pool_load(body), 3);
        let mut got = vec![pool_next().unwrap(), pool_next().unwrap(), pool_next().unwrap()];
        got.sort();
        assert_eq!(got, ["https://x/1.jpg", "https://x/2.jpg", "https://x/3.jpg"]);
        assert!(pool_next().is_none());
    }

    #[test]
    fn targets() {
        assert_eq!(launcher_target(3840, 2160), (1920, 1080));
        assert_eq!(launcher_target(1080, 1920), (1920, 1080));
        assert_eq!(launcher_target(1280, 720), (1280, 720));
        assert_eq!(system_target(1920, 1080, true), (1920, 1080));
        assert_eq!(system_target(1080, 1920, true), (1920, 1080));
        assert_eq!(system_target(1080, 2400, false), (1080, 2400));
        assert_eq!(system_target(3840, 2160, true), (2560, 1440));
    }

    #[test]
    fn urls_are_well_formed() {
        assert!(listing_url().starts_with("https://wallhaven.cc/api/v1/search?"));
        assert!(fallback_url().starts_with("https://picsum.photos/1920/1080.jpg?random="));
    }

    #[test]
    fn import_fill_crops_and_fit_letterboxes() {
        let d = tmp("import");

        // 8x4 kaynak, 100x50 hedef: oran aynı, doldur ve sığdır aynı sonucu verir; kaynak silinir.
        let src = d.join("a.png");
        two_tone_png(&src, 8, 4);
        let out = d.join("wall.jpg");
        assert!(import(src.to_str().unwrap(), out.to_str().unwrap(), 100, 50, false));
        assert!(!src.exists());
        let im = image::open(&out).unwrap().to_rgb8();
        assert_eq!(im.dimensions(), (100, 50));
        assert!(im.get_pixel(10, 25)[0] > 200); // sol kırmızı
        assert!(im.get_pixel(90, 25)[2] > 200); // sağ mavi

        // Kare kaynak (40x40) 100x50 tuvale sığdır: yanlar siyah, ortası dolu.
        let sq = d.join("sq.png");
        two_tone_png(&sq, 40, 40);
        let out2 = d.join("fit.jpg");
        assert!(import(sq.to_str().unwrap(), out2.to_str().unwrap(), 100, 50, true));
        let f = image::open(&out2).unwrap().to_rgb8();
        assert_eq!(f.dimensions(), (100, 50));
        assert!(f.get_pixel(2, 25).0.iter().all(|&c| c < 40)); // sol kenar siyah
        assert!(f.get_pixel(35, 25)[0] > 200); // sol kırmızı (ortadaki 50 piksel)
        assert!(f.get_pixel(65, 25)[2] > 200);

        // Aynı kare kaynak doldur: tuval tamamen dolu, siyah kenar yok.
        let sq2 = d.join("sq2.png");
        two_tone_png(&sq2, 40, 40);
        let out3 = d.join("fill.jpg");
        assert!(import(sq2.to_str().unwrap(), out3.to_str().unwrap(), 100, 50, false));
        let g = image::open(&out3).unwrap().to_rgb8();
        assert!(g.get_pixel(2, 25)[0] > 200);
        assert!(g.get_pixel(97, 25)[2] > 200);

        // Bozuk dosya: false döner, kaynak yine silinir, hedef oluşmaz.
        let bad = d.join("bad.png");
        fs::write(&bad, b"bu bir resim degil").unwrap();
        let out4 = d.join("bad.jpg");
        assert!(!import(bad.to_str().unwrap(), out4.to_str().unwrap(), 100, 50, false));
        assert!(!bad.exists() && !out4.exists());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn system_image_keeps_source_and_uses_tv_orientation() {
        let d = tmp("system");
        let src = d.join("a.png");
        two_tone_png(&src, 40, 40);
        let out = d.join("sys.jpg");
        assert!(system_image(src.to_str().unwrap(), out.to_str().unwrap(), 1080, 1920, true));
        assert!(src.exists());
        assert_eq!(image::open(&out).unwrap().to_rgb8().dimensions(), (1920, 1080));
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn stamp_changes_with_content() {
        let d = tmp("stamp");
        let f = d.join("w.jpg");
        assert_eq!(stamp(f.to_str().unwrap()), 0);
        fs::write(&f, b"1").unwrap();
        let a = stamp(f.to_str().unwrap());
        assert!(a > 0);
        fs::write(&f, b"123456").unwrap();
        assert_ne!(stamp(f.to_str().unwrap()), a);
        let _ = fs::remove_dir_all(&d);
    }
}
