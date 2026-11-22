//! İstemci: bağlantı, dosya ve klasör gönderme (Swift `QuickShare.send/sendOne/zipFolder`).

use super::proto::*;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const IO_TIMEOUT: Duration = Duration::from_secs(30);
const SEND_BUF: usize = 4 << 20;
/// Aynı anda açık gönderme bağlantısı sayısı.
pub const SEND_WORKERS: usize = 4;

/// Tek istekli bağlantı (`Connection: close`). Başlık/gövde okuma tamponludur.
pub struct Conn {
    s: TcpStream,
    buf: Vec<u8>,
}

impl Conn {
    /// `addr`: `ip:port` (IPv6 için `[::1]:port`). IPv4 adresler önce denenir.
    pub fn connect(addr: &str) -> Result<Conn> {
        let fail = || ShareError(l("bağlantı kurulamadı", "could not connect"));
        let mut list: Vec<_> = addr.to_socket_addrs().map_err(|_| fail())?.collect();
        list.sort_by_key(|a| a.is_ipv6());
        for a in list {
            if let Ok(s) = TcpStream::connect_timeout(&a, CONNECT_TIMEOUT) {
                let _ = s.set_read_timeout(Some(IO_TIMEOUT));
                let _ = s.set_write_timeout(Some(IO_TIMEOUT));
                let _ = s.set_nodelay(true);
                return Ok(Conn { s, buf: Vec::new() });
            }
        }
        Err(fail())
    }

    /// Ham soket (toplu akış için tamponlu yazıcı buna sarılır).
    pub fn stream_mut(&mut self) -> &mut TcpStream {
        &mut self.s
    }

    pub fn send(&mut self, data: &[u8]) -> Result<()> {
        self.s.write_all(data)?;
        Ok(())
    }

    /// Ağdan biraz okuyup tampona ekler; bağlantı kapandıysa `false`.
    fn fill(&mut self) -> Result<bool> {
        let mut tmp = [0u8; 64 * 1024];
        let n = self.s.read(&mut tmp)?;
        if n == 0 {
            return Ok(false);
        }
        self.buf.extend_from_slice(&tmp[..n]);
        Ok(true)
    }

    pub fn read_head(&mut self) -> Result<Head> {
        loop {
            if let Some(i) = find_head_end(&self.buf) {
                let head = parse_head(&self.buf[..i]);
                self.buf.drain(..i + 4);
                return Ok(head);
            }
            if self.buf.len() > MAX_HEADER {
                return err("geçersiz yanıt", "invalid response");
            }
            if !self.fill()? {
                return err("bağlantı beklenmedik şekilde kapandı", "connection closed unexpectedly");
            }
        }
    }

    /// Tam `len` bayt gövdeyi parça parça `sink`'e verir.
    pub fn read_body(&mut self, len: usize, sink: &mut dyn FnMut(&[u8]) -> std::io::Result<()>) -> Result<()> {
        let mut left = len;
        while left > 0 {
            if self.buf.is_empty() && !self.fill()? {
                return err("alma yarım kaldı", "incomplete");
            }
            let n = self.buf.len().min(left);
            sink(&self.buf[..n])?;
            self.buf.drain(..n);
            left -= n;
        }
        Ok(())
    }
}

/// Başlıktan sonra tamponda kalan baytlar önce okunur, sonra soket.
impl Read for Conn {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if !self.buf.is_empty() {
            let n = self.buf.len().min(out.len());
            out[..n].copy_from_slice(&self.buf[..n]);
            self.buf.drain(..n);
            return Ok(n);
        }
        self.s.read(out)
    }
}

/// Tek dosya gönderir (`POST /u`, `X-Name`, `Content-Length`); alıcı `200` dönerse `true`.
/// `progress(gönderilen, toplam)` her parçadan sonra çağrılır.
pub fn send_file(addr: &str, path: &Path, progress: &mut dyn FnMut(u64, u64)) -> bool {
    send_file_inner(addr, path, progress).unwrap_or(false)
}

fn send_file_inner(addr: &str, path: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<bool> {
    let mut f = File::open(path)?;
    let size = f.metadata()?.len();
    let mut c = Conn::connect(addr)?;
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let head = format!(
        "POST /u HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        enc(name),
        size
    );
    c.send(head.as_bytes())?;
    let mut chunk = vec![0u8; SEND_BUF];
    let mut sent = 0u64;
    while sent < size {
        let want = ((size - sent) as usize).min(chunk.len());
        let n = f.read(&mut chunk[..want])?;
        if n == 0 {
            return Ok(false); // dosya gönderim sırasında küçüldü
        }
        c.send(&chunk[..n])?;
        sent += n as u64;
        progress(sent, size);
    }
    let h = c.read_head()?;
    Ok(h.status() == 200)
}

/// `send_reader` sonucu.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SendStatus {
    Ok = 0,
    /// Bağlanılamadı (veri gönderilmedi; yeniden denemek güvenli)
    Connect = 1,
    /// Kaynak bildirilen uzunluktan kısa çıktı
    Short = 2,
    /// Karşı cihaz `200` dönmedi
    Refused = 3,
    /// Gönderim sırasında G/Ç hatası
    Io = 4,
}

/// Android: açık bir okuyucudan (ör. `ContentResolver` dosya tanıtıcısı) tek dosya gönderir.
/// `len` bayt okunup `POST /u` ile yollanır; `progress(gönderilen, toplam)` her parçadan sonra çağrılır.
pub fn send_reader(addr: &str, name: &str, len: u64, src: &mut dyn Read, progress: &mut dyn FnMut(u64, u64)) -> SendStatus {
    let mut c = match Conn::connect(addr) {
        Ok(c) => c,
        Err(_) => return SendStatus::Connect,
    };
    let head = format!(
        "POST /u HTTP/1.1\r\nHost: qs\r\nX-Name: {}\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        enc(name),
        len
    );
    if c.send(head.as_bytes()).is_err() {
        return SendStatus::Io;
    }
    let mut chunk = vec![0u8; 1 << 20];
    let mut sent = 0u64;
    while sent < len {
        let want = ((len - sent) as usize).min(chunk.len());
        let n = match src.read(&mut chunk[..want]) {
            Ok(n) => n,
            Err(_) => return SendStatus::Io,
        };
        if n == 0 {
            return SendStatus::Short; // kaynak gönderim sırasında küçüldü; alıcıyı 30 sn bekletme
        }
        if c.send(&chunk[..n]).is_err() {
            return SendStatus::Io;
        }
        sent += n as u64;
        progress(sent, len);
    }
    match c.read_head() {
        Ok(h) if h.status() == 200 => SendStatus::Ok,
        _ => SendStatus::Refused,
    }
}

/// Klasörü geçici bir dizine `<ad>.zip` olarak sıkıştırır (macOS `ditto`). Dönüş: (geçici dizin, zip yolu).
pub fn zip_folder(dir: &Path) -> Option<(PathBuf, PathBuf)> {
    let tmp = std::env::temp_dir().join(format!("qs-{}", rand8()));
    fs::create_dir_all(&tmp).ok()?;
    let base = dir.file_name().and_then(|n| n.to_str()).unwrap_or("klasor");
    let out = tmp.join(format!("{base}.zip"));
    let ok = Command::new("/usr/bin/ditto")
        .args(["-c", "-k", "--sequesterRsrc", "--keepParent"])
        .arg(dir)
        .arg(&out)
        .status()
        .map(|s| s.success())
        .unwrap_or(false);
    if ok {
        Some((tmp, out))
    } else {
        let _ = fs::remove_dir_all(&tmp);
        None
    }
}

fn send_one(addr: &str, f: &Path, idx: usize, progress: Option<&(dyn Fn(usize, u64, u64) + Sync)>) -> bool {
    let mut p = |s: u64, t: u64| {
        if let Some(pr) = progress {
            pr(idx, s, t)
        }
    };
    if f.is_dir() {
        let Some((tmp, z)) = zip_folder(f) else { return false };
        let ok = send_file(addr, &z, &mut p);
        let _ = fs::remove_dir_all(tmp);
        ok
    } else {
        send_file(addr, f, &mut p)
    }
}

/// Dosyaları paralel (en çok `SEND_WORKERS` bağlantı) gönderir; klasörler zip'lenir.
/// Dönüş: başarıyla giden dosya sayısı. `progress(dosya_sırası, gönderilen, toplam)` iş parçacıklarından çağrılır.
pub fn send_files(addr: &str, files: &[PathBuf], progress: Option<&(dyn Fn(usize, u64, u64) + Sync)>) -> usize {
    let idx: Vec<usize> = (0..files.len()).collect();
    par_map(&idx, SEND_WORKERS, &|&i: &usize| send_one(addr, &files[i], i, progress))
        .into_iter()
        .filter(|ok| *ok)
        .count()
}
