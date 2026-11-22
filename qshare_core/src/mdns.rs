//! Tek seferlik mDNS / DNS-SD cihaz tarama (yalnızca std).
//!
//! Neden var: Android `NsdManager.resolveService` aynı anda tek çözümlemeye izin verir; cihazlar sırayla ve sık
//! sık hata vererek çözülür. Burada tek bir UDP soketiyle `_qshare._tcp` taranır, bulunan tüm cihazların
//! SRV / TXT / A kayıtları paralel istenir.
//!
//! Nasıl çalışır: 5353 numaralı porta BAĞLANILMAZ (sistemin mDNS hizmetiyle çakışmaz, özel izin gerekmez).
//! Sorgu, 5353 dışındaki bir kaynak portundan çok noktaya (224.0.0.251:5353) gönderilir; RFC 6762 §6.7'ye göre
//! yanıtlayıcılar (Bonjour, Android mdnsd, avahi) böyle "eski tip" sorgulara doğrudan bizim portumuza (unicast)
//! yanıt verir. Yayın (duyuru) bu modülün işi değildir; o platformda (NsdManager / Bonjour) kalır.
//!
//! Bu tarama NsdManager'ın YERİNE değil YANINDA çalıştırılmak üzere tasarlandı: hiçbir şey bulamazsa
//! (bazı yanıtlayıcılar eski tip sorguyu yok sayabilir) çağıran taraf NsdManager sonuçlarıyla devam eder.
//!
//! Yalnızca IPv4 (A kaydı) çözülür; uygulamalar zaten IPv4 adresi tercih ediyor.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const MDNS_GROUP: SocketAddrV4 = SocketAddrV4::new(Ipv4Addr::new(224, 0, 0, 251), 5353);

const T_A: u16 = 1;
const T_PTR: u16 = 12;
const T_TXT: u16 = 16;
const T_SRV: u16 = 33;

/// Bulunan bir cihaz.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Found {
    /// Servis örnek adı (ör. "QS1a2b3c4d5e"); NsdManager'daki `serviceName` ile aynıdır.
    pub instance: String,
    pub addr: Ipv4Addr,
    pub port: u16,
    pub txt: Vec<(String, String)>,
}

impl Found {
    pub fn txt_get(&self, key: &str) -> Option<&str> {
        self.txt.iter().find(|(k, _)| k.eq_ignore_ascii_case(key)).map(|(_, v)| v.as_str())
    }
}

#[derive(Debug, Clone)]
pub struct Options {
    /// Servis türü, ör. "_qshare._tcp.local"
    pub service: String,
    /// Bağlanılacak yerel IPv4 (çıkış arayüzünü belirler); None: sistem seçer.
    pub local: Option<Ipv4Addr>,
    /// Sorgunun gönderileceği adres (varsayılan mDNS grubu; testlerde yerel yanıtlayıcı).
    pub target: SocketAddr,
    /// En uzun tarama süresi.
    pub timeout: Duration,
    /// En az bir cihaz bulunduktan sonra, yeni bir şey gelmeden bu kadar sessizlik olursa biter.
    pub quiet: Duration,
    /// Bu örnek adı (kendimiz) atlanır.
    pub skip_instance: Option<String>,
}

impl Options {
    pub fn new(service: &str, local: Option<Ipv4Addr>, timeout: Duration) -> Options {
        Options {
            service: service.trim_end_matches('.').to_string(),
            local,
            target: SocketAddr::V4(MDNS_GROUP),
            timeout,
            quiet: Duration::from_millis(500),
            skip_instance: None,
        }
    }
}

// ---------------------------------------------------------------- DNS kodlama

/// "a\.b.c" gibi (nokta içeren etiket `\.` ile kaçırılmış) adı etiketlere böler.
fn split_labels(name: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut esc = false;
    for c in name.chars() {
        if esc {
            cur.push(c);
            esc = false;
        } else if c == '\\' {
            esc = true;
        } else if c == '.' {
            if !cur.is_empty() {
                out.push(std::mem::take(&mut cur));
            }
        } else {
            cur.push(c);
        }
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

fn push_name(out: &mut Vec<u8>, name: &str) {
    for l in split_labels(name) {
        let b = l.as_bytes();
        let n = b.len().min(63);
        out.push(n as u8);
        out.extend_from_slice(&b[..n]);
    }
    out.push(0);
}

/// Sorgu paketi: kimlik 0, tüm sorular sınıf IN (QU biti YOK: eski tip unicast yanıt istenir).
fn build_query(qs: &[(String, u16)]) -> Vec<u8> {
    let mut o = Vec::with_capacity(12 + qs.len() * 40);
    o.extend_from_slice(&[0, 0, 0, 0]);
    o.extend_from_slice(&(qs.len() as u16).to_be_bytes());
    o.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
    for (name, ty) in qs {
        push_name(&mut o, name);
        o.extend_from_slice(&ty.to_be_bytes());
        o.extend_from_slice(&1u16.to_be_bytes());
    }
    o
}

// ---------------------------------------------------------------- DNS ayrıştırma

fn u16_at(b: &[u8], p: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*b.get(p)?, *b.get(p + 1)?]))
}

/// Ad okur (sıkıştırma işaretçileriyle). Dönüş: (ad, adın paketteki bitiş konumu).
fn read_name(b: &[u8], start: usize) -> Option<(String, usize)> {
    let mut pos = start;
    let mut end: Option<usize> = None;
    let mut jumps = 0;
    let mut labels: Vec<String> = Vec::new();
    loop {
        let len = *b.get(pos)? as usize;
        if len == 0 {
            pos += 1;
            break;
        }
        if len & 0xC0 == 0xC0 {
            let p2 = ((len & 0x3F) << 8) | *b.get(pos + 1)? as usize;
            if end.is_none() {
                end = Some(pos + 2);
            }
            jumps += 1;
            if jumps > 16 || p2 >= b.len() {
                return None;
            }
            pos = p2;
            continue;
        }
        if len & 0xC0 != 0 {
            return None;
        }
        pos += 1;
        let raw = b.get(pos..pos + len)?;
        let s = String::from_utf8_lossy(raw);
        let mut esc = String::with_capacity(s.len());
        for c in s.chars() {
            if c == '.' || c == '\\' {
                esc.push('\\');
            }
            esc.push(c);
        }
        labels.push(esc);
        pos += len;
        if labels.len() > 64 {
            return None;
        }
    }
    Some((labels.join("."), end.unwrap_or(pos)))
}

#[derive(Debug)]
enum Rdata {
    Ptr(String),
    Srv { port: u16, target: String },
    Txt(Vec<(String, String)>),
    A(Ipv4Addr),
}

#[derive(Debug)]
struct Record {
    name: String,
    ttl: u32,
    data: Rdata,
}

/// Yanıt paketindeki ilgili kayıtlar (yanıt, yetki, ek bölümleri). Sorgu paketleri ve bozuk paketler boş döner.
fn parse_response(b: &[u8]) -> Vec<Record> {
    let mut out = Vec::new();
    if b.len() < 12 || b[2] & 0x80 == 0 {
        return out; // QR=0: sorgu
    }
    let qd = u16_at(b, 4).unwrap_or(0) as usize;
    let total = u16_at(b, 6).unwrap_or(0) as usize + u16_at(b, 8).unwrap_or(0) as usize + u16_at(b, 10).unwrap_or(0) as usize;
    let mut pos = 12;
    for _ in 0..qd {
        match read_name(b, pos) {
            Some((_, e)) => pos = e + 4,
            None => return out,
        }
    }
    for _ in 0..total {
        let Some((name, e)) = read_name(b, pos) else { return out };
        let (Some(ty), Some(rdlen)) = (u16_at(b, e), u16_at(b, e + 8)) else { return out };
        let ttl = match b.get(e + 4..e + 8) {
            Some(t) => u32::from_be_bytes([t[0], t[1], t[2], t[3]]),
            None => return out,
        };
        let rd = e + 10;
        let rdlen = rdlen as usize;
        if rd + rdlen > b.len() {
            return out;
        }
        let data = match ty {
            T_PTR => read_name(b, rd).map(|(n, _)| Rdata::Ptr(n)),
            T_SRV if rdlen >= 7 => match (u16_at(b, rd + 4), read_name(b, rd + 6)) {
                (Some(port), Some((target, _))) => Some(Rdata::Srv { port, target }),
                _ => None,
            },
            T_TXT => {
                let mut kv = Vec::new();
                let mut p = rd;
                while p < rd + rdlen {
                    let l = b[p] as usize;
                    p += 1;
                    if p + l > rd + rdlen {
                        break;
                    }
                    if l > 0 {
                        let s = String::from_utf8_lossy(&b[p..p + l]).into_owned();
                        match s.split_once('=') {
                            Some((k, v)) => kv.push((k.to_string(), v.to_string())),
                            None => kv.push((s, String::new())),
                        }
                    }
                    p += l;
                }
                Some(Rdata::Txt(kv))
            }
            T_A if rdlen == 4 => Some(Rdata::A(Ipv4Addr::new(b[rd], b[rd + 1], b[rd + 2], b[rd + 3]))),
            _ => None,
        };
        if let Some(data) = data {
            out.push(Record { name, ttl, data });
        }
        pos = rd + rdlen;
    }
    out
}

// ---------------------------------------------------------------- tarama

#[derive(Default)]
struct Inst {
    name: String,
    srv: Option<(String, u16)>,
    txt: Option<Vec<(String, String)>>,
    emitted: bool,
}

/// `Options::service` türündeki cihazları arar. Sonuçlar bulundukça döner dizide; `stop` true olursa erken biter.
pub fn browse(opt: &Options, stop: &AtomicBool) -> std::io::Result<Vec<Found>> {
    let bind = SocketAddr::from((opt.local.unwrap_or(Ipv4Addr::UNSPECIFIED), 0));
    let sock = UdpSocket::bind(bind)?;
    let _ = sock.set_multicast_ttl_v4(255);
    sock.set_read_timeout(Some(Duration::from_millis(40)))?;

    let svc_lc = opt.service.to_lowercase();
    let suffix = format!(".{}", svc_lc);
    let skip = opt.skip_instance.as_ref().map(|s| s.to_lowercase());

    let mut insts: HashMap<String, Inst> = HashMap::new();
    let mut hosts: HashMap<String, Ipv4Addr> = HashMap::new();
    let mut found: Vec<Found> = Vec::new();

    let start = Instant::now();
    let mut last_news = start;
    let mut next_ptr = 0usize;
    let ptr_at = [0u64, 250, 800, 1800];
    let mut last_follow: Option<Instant> = None;
    let mut buf = vec![0u8; 9000];

    let ptr_query = build_query(&[(opt.service.clone(), T_PTR)]);

    loop {
        if stop.load(Ordering::Relaxed) || start.elapsed() >= opt.timeout {
            break;
        }
        let el = start.elapsed();
        if next_ptr < ptr_at.len() && el >= Duration::from_millis(ptr_at[next_ptr]) {
            let _ = sock.send_to(&ptr_query, opt.target);
            next_ptr += 1;
        }

        match sock.recv_from(&mut buf) {
            Ok((n, _)) => {
                for r in parse_response(&buf[..n]) {
                    let lc = r.name.to_lowercase();
                    match r.data {
                        Rdata::Ptr(inst) if lc == svc_lc && r.ttl > 0 => {
                            let key = inst.to_lowercase();
                            if key.ends_with(&suffix) && !insts.contains_key(&key) {
                                insts.insert(key, Inst { name: inst, ..Default::default() });
                                last_news = Instant::now();
                            }
                        }
                        Rdata::Srv { port, target } if lc.ends_with(&suffix) && r.ttl > 0 => {
                            let e = insts.entry(lc.clone()).or_insert_with(|| Inst { name: r.name.clone(), ..Default::default() });
                            if e.srv.is_none() {
                                last_news = Instant::now();
                            }
                            e.srv = Some((target.to_lowercase(), port));
                        }
                        Rdata::Txt(kv) if lc.ends_with(&suffix) && r.ttl > 0 => {
                            let e = insts.entry(lc.clone()).or_insert_with(|| Inst { name: r.name.clone(), ..Default::default() });
                            if e.txt.is_none() {
                                last_news = Instant::now();
                            }
                            e.txt = Some(kv);
                        }
                        Rdata::A(ip) if r.ttl > 0 => {
                            hosts.insert(lc, ip);
                        }
                        _ => {}
                    }
                }
            }
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut | std::io::ErrorKind::Interrupted) => {}
            Err(_) => std::thread::sleep(Duration::from_millis(40)),
        }

        // Tamamlanan örnekleri çıkar
        for (key, i) in insts.iter_mut() {
            if i.emitted {
                continue;
            }
            let (Some((target, port)), Some(txt)) = (&i.srv, &i.txt) else { continue };
            let Some(&addr) = hosts.get(target) else { continue };
            i.emitted = true;
            last_news = Instant::now();
            let label = i.name.get(..i.name.len().saturating_sub(suffix.len())).unwrap_or(i.name.as_str()).to_string();
            if skip.as_deref() == Some(&key[..key.len() - suffix.len()]) {
                continue;
            }
            found.push(Found { instance: label, addr, port: *port, txt: txt.clone() });
        }

        // Eksik kayıtlar için paralel ek sorgu (her 250 ms'de bir)
        let pending: Vec<&Inst> = insts.values().filter(|i| !i.emitted).collect();
        if !pending.is_empty() && last_follow.map_or(true, |t| t.elapsed() >= Duration::from_millis(250)) {
            let mut qs: Vec<(String, u16)> = Vec::new();
            for i in &pending {
                if i.srv.is_none() {
                    qs.push((i.name.clone(), T_SRV));
                }
                if i.txt.is_none() {
                    qs.push((i.name.clone(), T_TXT));
                }
                if let Some((target, _)) = &i.srv {
                    if !hosts.contains_key(target) {
                        qs.push((target.clone(), T_A));
                    }
                }
            }
            qs.dedup();
            for chunk in qs.chunks(8) {
                let _ = sock.send_to(&build_query(chunk), opt.target);
            }
            last_follow = Some(Instant::now());
        }

        let all_done = insts.values().all(|i| i.emitted);
        if !found.is_empty() && all_done && last_news.elapsed() >= opt.quiet && next_ptr >= 2 {
            break;
        }
    }
    Ok(found)
}

// ---------------------------------------------------------------- iptal belirteci (JNI için)

fn tokens() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    static T: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();
    T.get_or_init(|| Mutex::new(HashMap::new()))
}

/// `browse` ile aynı; ek olarak `token` ile dışarıdan `cancel(token)` çağrılarak erken bitirilebilir.
pub fn browse_cancellable(token: i64, opt: &Options) -> std::io::Result<Vec<Found>> {
    let flag = Arc::new(AtomicBool::new(false));
    tokens().lock().unwrap_or_else(|e| e.into_inner()).insert(token, flag.clone());
    let r = browse(opt, &flag);
    tokens().lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
    r
}

/// Çalışan `browse_cancellable(token, ..)` taramasını durdurur (yoksa bir şey yapmaz).
pub fn cancel(token: i64) {
    let f = tokens().lock().unwrap_or_else(|e| e.into_inner()).get(&token).cloned();
    if let Some(f) = f {
        f.store(true, Ordering::Relaxed);
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    const SVC: &str = "_qshare._tcp.local";

    fn rec(o: &mut Vec<u8>, name: &str, ty: u16, ttl: u32, rd: &[u8]) {
        push_name(o, name);
        o.extend_from_slice(&ty.to_be_bytes());
        o.extend_from_slice(&0x8001u16.to_be_bytes()); // cache-flush biti + IN
        o.extend_from_slice(&ttl.to_be_bytes());
        o.extend_from_slice(&(rd.len() as u16).to_be_bytes());
        o.extend_from_slice(rd);
    }
    fn name_bytes(n: &str) -> Vec<u8> {
        let mut v = Vec::new();
        push_name(&mut v, n);
        v
    }
    fn srv_rd(port: u16, target: &str) -> Vec<u8> {
        let mut v = vec![0, 0, 0, 0];
        v.extend_from_slice(&port.to_be_bytes());
        v.extend(name_bytes(target));
        v
    }
    fn txt_rd(kv: &[(&str, &str)]) -> Vec<u8> {
        let mut v = Vec::new();
        for (k, val) in kv {
            let s = format!("{}={}", k, val);
            v.push(s.len() as u8);
            v.extend_from_slice(s.as_bytes());
        }
        v
    }
    fn response(records: &[(&str, u16, u32, Vec<u8>)]) -> Vec<u8> {
        let mut o = vec![0, 0, 0x84, 0, 0, 0];
        o.extend_from_slice(&(records.len() as u16).to_be_bytes());
        o.extend_from_slice(&[0, 0, 0, 0]);
        for (n, t, ttl, rd) in records {
            rec(&mut o, n, *t, *ttl, rd);
        }
        o
    }

    /// Yerel sahte yanıtlayıcı: gelen sorulara göre yanıt verir. `split` true ise PTR sorusuna yalnızca PTR döner.
    fn responder(devs: Vec<(&'static str, u16, &'static str, Ipv4Addr, &'static str)>, split: bool) -> (SocketAddr, Arc<AtomicBool>) {
        let s = UdpSocket::bind("127.0.0.1:0").unwrap();
        s.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let addr = s.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let st = stop.clone();
        thread::spawn(move || {
            let mut buf = vec![0u8; 2000];
            while !st.load(Ordering::Relaxed) {
                let Ok((n, from)) = s.recv_from(&mut buf) else { continue };
                let b = &buf[..n];
                if b.len() < 12 || b[2] & 0x80 != 0 {
                    continue;
                }
                let qd = u16_at(b, 4).unwrap() as usize;
                let mut pos = 12;
                let mut recs: Vec<(String, u16, u32, Vec<u8>)> = Vec::new();
                for _ in 0..qd {
                    let (qn, e) = read_name(b, pos).unwrap();
                    let qt = u16_at(b, e).unwrap();
                    pos = e + 4;
                    for (inst, port, host, ip, id) in &devs {
                        let full = format!("{}.{}", inst, SVC);
                        let hostn = format!("{}.local", host);
                        let all = !split;
                        if qt == T_PTR && qn.eq_ignore_ascii_case(SVC) {
                            recs.push((SVC.into(), T_PTR, 120, name_bytes(&full)));
                            if all {
                                recs.push((full.clone(), T_SRV, 120, srv_rd(*port, &hostn)));
                                recs.push((full.clone(), T_TXT, 120, txt_rd(&[("n", "Cihaz"), ("i", id)])));
                                recs.push((hostn.clone(), T_A, 120, ip.octets().to_vec()));
                            }
                        }
                        if qn.eq_ignore_ascii_case(&full) && qt == T_SRV {
                            recs.push((full.clone(), T_SRV, 120, srv_rd(*port, &hostn)));
                        }
                        if qn.eq_ignore_ascii_case(&full) && qt == T_TXT {
                            recs.push((full.clone(), T_TXT, 120, txt_rd(&[("n", "Cihaz"), ("i", id)])));
                        }
                        if qn.eq_ignore_ascii_case(&hostn) && qt == T_A {
                            recs.push((hostn.clone(), T_A, 120, ip.octets().to_vec()));
                        }
                    }
                }
                if !recs.is_empty() {
                    let r: Vec<(&str, u16, u32, Vec<u8>)> = recs.iter().map(|(a, b, c, d)| (a.as_str(), *b, *c, d.clone())).collect();
                    let _ = s.send_to(&response(&r), from);
                }
            }
        });
        (addr, stop)
    }

    fn opts(target: SocketAddr, ms: u64) -> Options {
        let mut o = Options::new(SVC, Some(Ipv4Addr::LOCALHOST), Duration::from_millis(ms));
        o.target = target;
        o.quiet = Duration::from_millis(150);
        o
    }

    #[test]
    fn names_roundtrip_with_escaped_dot_and_compression() {
        let mut v = Vec::new();
        push_name(&mut v, "My\\.Phone._qshare._tcp.local");
        assert_eq!(v[0] as usize, "My.Phone".len());
        let (n, e) = read_name(&v, 0).unwrap();
        assert_eq!(n, "My\\.Phone._qshare._tcp.local");
        assert_eq!(e, v.len());
        // sıkıştırma: "x" + işaretçi → ilk adın 1. etiketinden sonrası
        let tail = 1 + "My.Phone".len();
        let mut c = v.clone();
        let start = c.len();
        c.push(1);
        c.push(b'x');
        c.extend_from_slice(&[0xC0, tail as u8]);
        let (n2, e2) = read_name(&c, start).unwrap();
        assert_eq!(n2, "x._qshare._tcp.local");
        assert_eq!(e2, c.len());
        // işaretçi döngüsü reddedilir
        assert!(read_name(&[0xC0, 0x00], 0).is_none());
    }

    #[test]
    fn bad_packets_do_not_panic() {
        for len in 0..40 {
            let junk: Vec<u8> = (0..len).map(|i| (i * 37 + 11) as u8 | 0x80).collect();
            let _ = parse_response(&junk);
        }
        let mut p = response(&[(SVC, T_PTR, 120, name_bytes("a.b"))]);
        p.truncate(p.len() - 3);
        assert!(parse_response(&p).len() <= 1);
    }

    #[test]
    fn finds_device_when_answers_come_split_across_followups() {
        let (addr, stop) = responder(vec![("QSabc", 40123, "tv-box", Ipv4Addr::new(192, 168, 1, 50), "id-1")], true);
        let f = browse(&opts(addr, 3000), &AtomicBool::new(false)).unwrap();
        stop.store(true, Ordering::Relaxed);
        assert_eq!(f.len(), 1, "{:?}", f);
        assert_eq!(f[0].instance, "QSabc");
        assert_eq!(f[0].addr, Ipv4Addr::new(192, 168, 1, 50));
        assert_eq!(f[0].port, 40123);
        assert_eq!(f[0].txt_get("i"), Some("id-1"));
        assert_eq!(f[0].txt_get("N"), Some("Cihaz"));
    }

    #[test]
    fn finds_many_devices_in_parallel_and_skips_self() {
        let devs = vec![
            ("QSone", 1111, "h1", Ipv4Addr::new(10, 0, 0, 1), "i1"),
            ("QStwo", 2222, "h2", Ipv4Addr::new(10, 0, 0, 2), "i2"),
            ("QSme", 3333, "h3", Ipv4Addr::new(10, 0, 0, 3), "i3"),
        ];
        let (addr, stop) = responder(devs, false);
        let mut o = opts(addr, 3000);
        o.skip_instance = Some("QSme".into());
        let t = Instant::now();
        let mut f = browse(&o, &AtomicBool::new(false)).unwrap();
        let took = t.elapsed();
        stop.store(true, Ordering::Relaxed);
        f.sort_by(|a, b| a.instance.cmp(&b.instance));
        assert_eq!(f.iter().map(|x| x.instance.as_str()).collect::<Vec<_>>(), vec!["QSone", "QStwo"]);
        assert!(took < Duration::from_millis(1500), "erken bitmeli: {:?}", took);
    }

    #[test]
    fn goodbye_records_are_ignored_and_timeout_is_respected() {
        let s = UdpSocket::bind("127.0.0.1:0").unwrap();
        s.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let addr = s.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let st = stop.clone();
        thread::spawn(move || {
            let mut buf = vec![0u8; 2000];
            while !st.load(Ordering::Relaxed) {
                if let Ok((_, from)) = s.recv_from(&mut buf) {
                    let r = response(&[(SVC, T_PTR, 0, name_bytes(&format!("QSgone.{}", SVC)))]);
                    let _ = s.send_to(&r, from);
                }
            }
        });
        let t = Instant::now();
        let f = browse(&opts(addr, 700), &AtomicBool::new(false)).unwrap();
        stop.store(true, Ordering::Relaxed);
        assert!(f.is_empty());
        assert!(t.elapsed() >= Duration::from_millis(650) && t.elapsed() < Duration::from_millis(1500));
    }

    #[test]
    fn stop_flag_ends_quickly() {
        let (addr, stop) = responder(vec![], false);
        let flag = Arc::new(AtomicBool::new(false));
        let f2 = flag.clone();
        thread::spawn(move || {
            thread::sleep(Duration::from_millis(100));
            f2.store(true, Ordering::Relaxed);
        });
        let t = Instant::now();
        let f = browse(&opts(addr, 5000), &flag).unwrap();
        stop.store(true, Ordering::Relaxed);
        assert!(f.is_empty());
        assert!(t.elapsed() < Duration::from_millis(600));
    }
}

