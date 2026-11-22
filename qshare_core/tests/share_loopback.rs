//! Sunucu ve istemci aynı süreçte, 127.0.0.1 üzerinden uçtan uca denenir.

use qshare_core::share::client;
use qshare_core::share::server::{Config, Event, Server};
use qshare_core::share::sync;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

/// Link kapsamı, Plan (macOS) davranışı: kalıcı silme, gizli klasörler atlanır.
fn sc(p: &Path) -> sync::Scope {
    sync::Scope::link(p)
}

fn tmp(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("plan_share_{tag}_{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn start(recv: &Path, link: Option<PathBuf>) -> (Server, Arc<Mutex<Vec<Event>>>) {
    start_with(recv, link, false)
}

fn start_with(recv: &Path, link: Option<PathBuf>, legacy_only: bool) -> (Server, Arc<Mutex<Vec<Event>>>) {
    let ev = Arc::new(Mutex::new(Vec::new()));
    let e2 = ev.clone();
    let s = Server::start(
        Config { recv_dir: recv.to_path_buf(), link_root: link, legacy_only, ..Default::default() },
        0,
        Arc::new(move |e| e2.lock().unwrap().push(e)),
    )
    .unwrap();
    (s, ev)
}

fn write(root: &Path, rel: &str, body: &str) {
    let f = root.join(rel);
    fs::create_dir_all(f.parent().unwrap()).unwrap();
    fs::write(f, body).unwrap();
}

fn raw(addr: &str, req: &[u8]) -> String {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    s.write_all(req).unwrap();
    let mut buf = [0u8; 512];
    let n = s.read(&mut buf).unwrap_or(0);
    String::from_utf8_lossy(&buf[..n]).into_owned()
}

#[test]
fn sends_file_and_keeps_both_copies() {
    let base = tmp("send");
    let recv = base.join("in");
    let src = base.join("Rapor.pdf");
    fs::write(&src, vec![7u8; 3_000_000]).unwrap();
    let (srv, ev) = start(&recv, None);
    let addr = format!("127.0.0.1:{}", srv.port());

    let mut last = 0;
    assert!(client::send_file(&addr, &src, &mut |s, _| last = s));
    assert_eq!(last, 3_000_000);
    assert!(client::send_file(&addr, &src, &mut |_, _| {}));

    assert_eq!(fs::read(recv.join("Rapor.pdf")).unwrap().len(), 3_000_000);
    assert!(recv.join("Rapor (2).pdf").exists());
    let evs = ev.lock().unwrap();
    assert!(evs.iter().any(|e| matches!(e, Event::Received { name, .. } if name == "Rapor (2).pdf")));
    assert!(evs.iter().any(|e| matches!(e, Event::Receiving(n) if n == "Rapor.pdf")));
    // .part artığı kalmamalı
    assert!(fs::read_dir(&recv).unwrap().all(|e| !e.unwrap().file_name().to_string_lossy().ends_with(".part")));
}

#[test]
fn empty_file_and_turkish_name() {
    let base = tmp("empty");
    let recv = base.join("in");
    let src = base.join("Şükrü’nün notu.txt");
    fs::write(&src, "").unwrap();
    let (srv, _ev) = start(&recv, None);
    assert!(client::send_file(&format!("127.0.0.1:{}", srv.port()), &src, &mut |_, _| {}));
    assert!(recv.join("Şükrü’nün notu.txt").exists());
}

#[test]
fn rejects_bad_requests_and_traversal() {
    let base = tmp("bad");
    let recv = base.join("in");
    let link = base.join("Link");
    fs::create_dir_all(&link).unwrap();
    let (srv, _ev) = start(&recv, Some(link.clone()));
    let addr = format!("127.0.0.1:{}", srv.port());

    assert!(raw(&addr, b"POST /u HTTP/1.1\r\nX-Name: a\r\n\r\n").starts_with("HTTP/1.1 400"));
    assert!(raw(&addr, b"GET /bogus HTTP/1.1\r\n\r\n").starts_with("HTTP/1.1 500"));
    assert!(raw(&addr, b"POST /u HTTP/1.1\r\nX-Sync: 1\r\nX-Name: ..%2Fx.txt\r\nContent-Length: 1\r\n\r\n").starts_with("HTTP/1.1 400"));
    assert!(raw(&addr, b"GET /f HTTP/1.1\r\nX-Name: %2E%2E%2Fsecret.txt\r\n\r\n").starts_with("HTTP/1.1 404"));

    // Yol parçaları atılır: dosya yalnızca alma klasörüne "x" olarak yazılır
    let r = raw(&addr, b"POST /u HTTP/1.1\r\nX-Name: ..%2F..%2Fx\r\nContent-Length: 2\r\n\r\nhi");
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    assert_eq!(fs::read_to_string(recv.join("x")).unwrap(), "hi");
    assert!(!base.join("x").exists());
}

#[test]
fn sync_merge_and_both_mirrors() {
    let base = tmp("sync");

    let (a, b) = (base.join("A"), base.join("B"));
    fs::create_dir_all(&a).unwrap();
    fs::create_dir_all(&b).unwrap();
    write(&a, "alt/n1.txt", "bir");
    write(&b, "alt2/n2.url", "iki");
    write(&a, "ortak.txt", "A sürümü");
    write(&b, "ortak.txt", "B sürümü");
    let newer = SystemTime::now() + Duration::from_secs(3600);
    fs::File::options().write(true).open(a.join("ortak.txt")).unwrap().set_modified(newer).unwrap();

    let (srv, _ev) = start(&base.join("recvB"), Some(b.clone()));
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv.port()) };

    // Mod 0: birleştir; ortak dosyada yeni olan kazanır
    let r = sync::run(0, Some(&sc(&a)), &[peer.clone()]);
    assert!(r.error.is_none() && r.errors.is_empty(), "{r:?}");
    assert_eq!(r.peers, 1);
    assert_eq!(fs::read_to_string(a.join("alt2/n2.url")).unwrap(), "iki");
    assert_eq!(fs::read_to_string(b.join("alt/n1.txt")).unwrap(), "bir");
    assert_eq!(fs::read_to_string(b.join("ortak.txt")).unwrap(), "A sürümü");

    // Mod 1: bu cihaza göre — karşıdaki fazlalık kalıcı silinir
    write(&b, "fazla.txt", "x");
    let r = sync::run(1, Some(&sc(&a)), &[peer.clone()]);
    assert!(r.errors.is_empty(), "{r:?}");
    assert!(!b.join("fazla.txt").exists());

    // Mod 2: karşıdakine göre — bizdeki fazlalık silinir
    write(&a, "yerel.txt", "y");
    let r = sync::run(2, Some(&sc(&a)), &[peer]);
    assert!(r.errors.is_empty(), "{r:?}");
    assert!(!a.join("yerel.txt").exists());
    assert!(r.message().contains("1 silindi") || r.message().contains("1 deleted"), "{}", r.message());
}

#[test]
fn sync_without_root_or_targets_reports_error() {
    assert!(sync::run(0, None, &[]).error.is_some());
    let d = tmp("noperr");
    assert!(sync::run(0, Some(&sc(&d)), &[]).error.is_some());
}

/// 300 küçük dosya + 12 MB'lık bir dosya; toplu (proto 2) ve eski (dosya başına) yolun ikisi de aynı sonucu vermeli.
fn bulk_roundtrip(legacy_only: bool) {
    let base = tmp(if legacy_only { "bulk_legacy" } else { "bulk_v2" });
    let (a, b) = (base.join("A"), base.join("B"));
    fs::create_dir_all(&a).unwrap();
    fs::create_dir_all(&b).unwrap();
    for i in 0..300 {
        write(&a, &format!("k{}/not{i}.txt", i % 7), &format!("içerik {i}"));
    }
    fs::write(a.join("buyuk.txt"), vec![b'x'; 12_000_000]).unwrap();
    write(&b, "karsi/b.url", "b");

    let (srv, _ev) = start_with(&base.join("recvB"), Some(b.clone()), legacy_only);
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv.port()) };
    let r = sync::run(0, Some(&sc(&a)), &[peer.clone()]);
    assert!(r.errors.is_empty(), "{r:?}");
    assert_eq!(r.sent, 301, "{r:?}");
    assert_eq!(r.got, 1, "{r:?}");
    for i in 0..300 {
        assert_eq!(fs::read_to_string(b.join(format!("k{}/not{i}.txt", i % 7))).unwrap(), format!("içerik {i}"));
    }
    assert_eq!(fs::metadata(b.join("buyuk.txt")).unwrap().len(), 12_000_000);
    assert_eq!(fs::read_to_string(a.join("karsi/b.url")).unwrap(), "b");

    // İkinci eşitleme: değişiklik yok → hiçbir şey gitmez/gelmez (mtime korunduğu için)
    let r = sync::run(0, Some(&sc(&a)), &[peer]);
    assert!(r.errors.is_empty(), "{r:?}");
    assert_eq!((r.sent, r.got), (0, 0), "{r:?}");
}

#[test]
fn bulk_sync_with_bundles() {
    bulk_roundtrip(false);
}

#[test]
fn bulk_sync_legacy_fallback() {
    bulk_roundtrip(true);
}

#[test]
fn parallel_send_of_many_files() {
    let base = tmp("par");
    let recv = base.join("in");
    let mut files = Vec::new();
    for i in 0..9 {
        let f = base.join(format!("d{i}.bin"));
        fs::write(&f, vec![i as u8; 600_000 + i * 1000]).unwrap();
        files.push(f);
    }
    let (srv, _ev) = start(&recv, None);
    let addr = format!("127.0.0.1:{}", srv.port());
    assert_eq!(client::send_files(&addr, &files, None), 9);
    for (i, _) in files.iter().enumerate() {
        assert_eq!(fs::metadata(recv.join(format!("d{i}.bin"))).unwrap().len(), (600_000 + i * 1000) as u64);
    }
}

#[test]
fn android_names_and_busy_events() {
    // Aynı ad: ad_1.uzantı (Plan'da "ad (2).uzantı"); her bağlantı Busy(true)…Busy(false) çifti üretir
    let base = tmp("andr");
    let recv = base.join("in");
    let src = base.join("Rapor.pdf");
    fs::write(&src, b"abc").unwrap();
    let ev = Arc::new(Mutex::new(Vec::new()));
    let e2 = ev.clone();
    let srv = Server::start(
        Config { recv_dir: recv.clone(), android_names: true, legacy_only: true, ..Default::default() },
        0,
        Arc::new(move |e| e2.lock().unwrap().push(e)),
    )
    .unwrap();
    let addr = format!("127.0.0.1:{}", srv.port());
    assert!(client::send_file(&addr, &src, &mut |_, _| {}));
    assert!(client::send_file(&addr, &src, &mut |_, _| {}));
    assert!(recv.join("Rapor.pdf").exists());
    assert!(recv.join("Rapor_1.pdf").exists());
    std::thread::sleep(Duration::from_millis(300)); // bağlantı işleyicisi yanıttan sonra biter
    let evs = ev.lock().unwrap();
    let on = evs.iter().filter(|e| matches!(e, Event::Busy(true))).count();
    let off = evs.iter().filter(|e| matches!(e, Event::Busy(false))).count();
    assert_eq!((on, off), (2, 2));
}

#[test]
fn send_reader_reports_statuses() {
    let base = tmp("rdr");
    let (srv, _ev) = start(&base.join("in"), None);
    let addr = format!("127.0.0.1:{}", srv.port());
    let mut data: &[u8] = b"merhaba";
    assert_eq!(client::send_reader(&addr, "m.txt", 7, &mut data, &mut |_, _| {}), client::SendStatus::Ok);
    assert_eq!(fs::read_to_string(base.join("in").join("m.txt")).unwrap(), "merhaba");
    // Bildirilenden kısa kaynak
    let mut short: &[u8] = b"abc";
    assert_eq!(client::send_reader(&addr, "k.txt", 10, &mut short, &mut |_, _| {}), client::SendStatus::Short);
    // Dinleyen yok
    let mut d: &[u8] = b"x";
    assert_eq!(client::send_reader("127.0.0.1:1", "x.txt", 1, &mut d, &mut |_, _| {}), client::SendStatus::Connect);
}

#[test]
fn download_scope_syncs_any_type_and_deletes_go_to_trash() {
    let base = tmp("dl");
    let (a, b) = (base.join("A"), base.join("B"));
    let trash = base.join(".onemsiz");
    fs::create_dir_all(&a).unwrap();
    fs::create_dir_all(&b).unwrap();
    write(&a, "foto.jpg", "J");
    write(&a, ".gizli.jpg", "G"); // gizli: eşitlenmez
    write(&a, "yarim.part", "P"); // geçici: eşitlenmez
    write(&b, "fazla.apk", "F");
    let srv = Server::start(
        Config {
            recv_dir: base.join("recv"),
            dl_root: Some(b.clone()),
            trash: Some(trash.clone()),
            legacy_only: true,
            android_names: true,
            ..Default::default()
        },
        0,
        Arc::new(|_: Event| {}),
    )
    .unwrap();
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv.port()) };
    let dl = sync::Scope { root: a.clone(), dl: true, trash: Some(trash.clone()), skip_hidden_dirs: true };

    let r = sync::run(0, Some(&dl), &[peer.clone()]);
    assert!(r.error.is_none() && r.errors.is_empty(), "{r:?}");
    assert_eq!(fs::read_to_string(b.join("foto.jpg")).unwrap(), "J");
    assert_eq!(fs::read_to_string(a.join("fazla.apk")).unwrap(), "F");
    assert!(!b.join(".gizli.jpg").exists() && !b.join("yarim.part").exists());

    // Mod 1: karşıdaki fazlalık kalıcı silinmez, .onemsiz klasörüne taşınır
    write(&b, "fazla2.apk", "2");
    let r = sync::run(1, Some(&dl), &[peer.clone()]);
    assert!(r.errors.is_empty(), "{r:?}");
    assert!(!b.join("fazla2.apk").exists());
    assert_eq!(fs::read_to_string(trash.join("fazla2.apk")).unwrap(), "2");
    assert!(trash.join(".nomedia").exists());

    // Link kapsamı bu sunucuda kapalı (link_root yok): hata bildirilir, çökmez
    let r = sync::run(0, Some(&sc(&a)), &[peer]);
    assert_eq!(r.peers, 0);
    assert!(!r.errors.is_empty());
}

#[test]
fn plan_defaults_use_paren_names_and_skip_hidden_dirs() {
    // Varsayılan Config (Plan): aynı ad "ad (2).uzantı" olur; Link'te gizli klasör eşitlenmez
    let base = tmp("plandef");
    let recv = base.join("in");
    let src = base.join("Rapor.pdf");
    fs::write(&src, b"abc").unwrap();
    let (srv, _ev) = start(&recv, None);
    let addr = format!("127.0.0.1:{}", srv.port());
    assert!(client::send_file(&addr, &src, &mut |_, _| {}));
    assert!(client::send_file(&addr, &src, &mut |_, _| {}));
    assert!(recv.join("Rapor.pdf").exists());
    assert!(recv.join("Rapor (2).pdf").exists());
    assert!(!recv.join("Rapor_1.pdf").exists());

    let (a, b) = (base.join("A"), base.join("B"));
    write(&a, "not.txt", "N");
    write(&a, ".gizli/ic.txt", "G");
    fs::create_dir_all(&b).unwrap();
    let (srv2, _ev2) = start(&base.join("in2"), Some(b.clone()));
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv2.port()) };
    let r = sync::run(0, Some(&sc(&a)), &[peer]);
    assert!(r.error.is_none() && r.errors.is_empty(), "{r:?}");
    assert_eq!(fs::read_to_string(b.join("not.txt")).unwrap(), "N");
    assert!(!b.join(".gizli").exists());
}

#[test]
fn android_link_scope_syncs_hidden_dirs_when_enabled() {
    // Android: Link kapsamında gizli klasörler de eşitlenir (Config::link_hidden_dirs + Scope::skip_hidden_dirs = false)
    let base = tmp("hidlink");
    let (a, b) = (base.join("A"), base.join("B"));
    write(&a, ".klasor/ic.txt", "H");
    fs::create_dir_all(&b).unwrap();
    let srv = Server::start(
        Config {
            recv_dir: base.join("in"),
            link_root: Some(b.clone()),
            legacy_only: true,
            android_names: true,
            link_hidden_dirs: true,
            ..Default::default()
        },
        0,
        Arc::new(|_: Event| {}),
    )
    .unwrap();
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv.port()) };
    let scope = sync::Scope { root: a.clone(), dl: false, trash: None, skip_hidden_dirs: false };
    let r = sync::run(0, Some(&scope), &[peer]);
    assert!(r.error.is_none() && r.errors.is_empty(), "{r:?}");
    assert_eq!(fs::read_to_string(b.join(".klasor").join("ic.txt")).unwrap(), "H");
}

#[test]
fn download_request_is_refused_when_server_has_no_download_root() {
    // Plan sunucusu (dl_root yok) bir Download isteğini Link klasörüyle KARIŞTIRMAZ: hata döner, dosya akmaz
    let base = tmp("nodl");
    let (a, b) = (base.join("A"), base.join("B"));
    write(&a, "foto.jpg", "J");
    write(&b, "not.txt", "N");
    let (srv, _ev) = start(&base.join("in"), Some(b.clone()));
    let peer = sync::Peer { name: "B".into(), addr: format!("127.0.0.1:{}", srv.port()) };
    let dl = sync::Scope { root: a.clone(), dl: true, trash: None, skip_hidden_dirs: true };
    let r = sync::run(0, Some(&dl), &[peer]);
    assert_eq!(r.peers, 0);
    assert!(!r.errors.is_empty(), "{r:?}");
    assert!(!b.join("foto.jpg").exists());
    assert!(!a.join("not.txt").exists());
}
