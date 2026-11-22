//! Unicode NFD / NFC (kanonik) ve Mn denetimi — macOS'taki `decomposedStringWithCanonicalMapping` /
//! `precomposedStringWithCanonicalMapping` karşılığı. Tablolar `unicode_tables.rs` (üretilmiş).

use crate::unicode_tables::*;

const SBASE: u32 = 0xAC00;
const LBASE: u32 = 0x1100;
const VBASE: u32 = 0x1161;
const TBASE: u32 = 0x11A7;
const LCOUNT: u32 = 19;
const VCOUNT: u32 = 21;
const TCOUNT: u32 = 28;
const NCOUNT: u32 = VCOUNT * TCOUNT;
const SCOUNT: u32 = LCOUNT * NCOUNT;

fn ccc(c: u32) -> u8 {
    CCC.binary_search_by_key(&c, |e| e.0).map(|i| CCC[i].1).unwrap_or(0)
}

pub fn is_nonspacing_mark(c: char) -> bool {
    let c = c as u32;
    MN.binary_search_by(|&(a, b)| {
        if c < a { std::cmp::Ordering::Greater } else if c > b { std::cmp::Ordering::Less } else { std::cmp::Ordering::Equal }
    })
    .is_ok()
}

fn push_decomposed(c: u32, out: &mut Vec<u32>) {
    if (SBASE..SBASE + SCOUNT).contains(&c) {
        let s = c - SBASE;
        out.push(LBASE + s / NCOUNT);
        out.push(VBASE + (s % NCOUNT) / TCOUNT);
        if s % TCOUNT != 0 {
            out.push(TBASE + s % TCOUNT);
        }
    } else if let Ok(i) = DECOMP.binary_search_by_key(&c, |e| e.0) {
        let (_, off, len) = DECOMP[i];
        out.extend_from_slice(&DECOMP_DATA[off as usize..off as usize + len as usize]);
    } else {
        out.push(c);
    }
}

/// Kanonik ayrışım + kanonik sıralama; skalar dizisi döndürür.
fn nfd_scalars(s: &str) -> Vec<u32> {
    let mut v = Vec::with_capacity(s.len());
    for ch in s.chars() {
        push_decomposed(ch as u32, &mut v);
    }
    // aynı ccc'li olmayan (ccc>0) ardışık işaretleri ccc'ye göre kararlı sırala
    let mut i = 0;
    while i < v.len() {
        if ccc(v[i]) == 0 {
            i += 1;
            continue;
        }
        let mut j = i;
        while j < v.len() && ccc(v[j]) != 0 {
            j += 1;
        }
        v[i..j].sort_by_key(|&c| ccc(c));
        i = j;
    }
    v
}

pub fn nfd(s: &str) -> String {
    nfd_scalars(s).into_iter().filter_map(char::from_u32).collect()
}

fn compose_pair(a: u32, b: u32) -> Option<u32> {
    if (LBASE..LBASE + LCOUNT).contains(&a) && (VBASE..VBASE + VCOUNT).contains(&b) {
        return Some(SBASE + ((a - LBASE) * VCOUNT + (b - VBASE)) * TCOUNT);
    }
    if (SBASE..SBASE + SCOUNT).contains(&a) && (a - SBASE) % TCOUNT == 0 && (TBASE + 1..TBASE + TCOUNT).contains(&b) {
        return Some(a + (b - TBASE));
    }
    COMPOSE.binary_search_by(|e| (e.0, e.1).cmp(&(a, b))).ok().map(|i| COMPOSE[i].2)
}

/// Kanonik birleşik biçim (NFC).
pub fn nfc(s: &str) -> String {
    if s.is_ascii() {
        return s.to_string();
    }
    let d = nfd_scalars(s);
    let mut out: Vec<u32> = Vec::with_capacity(d.len());
    let mut starter: Option<usize> = None; // out içindeki son başlatıcının yeri
    let mut last_ccc: i32 = -1; // başlatıcıdan sonra eklenen son işaretin ccc'si
    for &c in &d {
        let cc = ccc(c) as i32;
        if let Some(si) = starter {
            let blocked = last_ccc != -1 && (last_ccc >= cc || last_ccc == 0);
            if !blocked || (out.len() - 1 == si) {
                if let Some(p) = compose_pair(out[si], c) {
                    out[si] = p;
                    continue;
                }
            }
        }
        if cc == 0 {
            starter = Some(out.len());
            last_ccc = -1;
        } else {
            last_ccc = cc;
        }
        out.push(c);
    }
    out.into_iter().filter_map(char::from_u32).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nfd_nfc_roundtrip() {
        assert_eq!(nfd("é"), "e\u{0301}");
        assert_eq!(nfc("e\u{0301}"), "é");
        assert_eq!(nfc(&nfd("Çalışma Şğüö İ ệ")), "Çalışma Şğüö İ ệ");
        assert_eq!(nfc("Cafe\u{0301}"), nfc("Café"));
        assert_eq!(nfd("한"), "\u{1112}\u{1161}\u{11AB}");
        assert_eq!(nfc("\u{1112}\u{1161}\u{11AB}"), "한");
        assert_eq!(nfc("abc"), "abc");
    }

    #[test]
    fn reorders_combining_marks() {
        // a + ogonek(202) + acute(230) ve ters sıra aynı sonucu verir
        assert_eq!(nfc("a\u{0301}\u{0328}"), nfc("a\u{0328}\u{0301}"));
    }

    #[test]
    fn mn_detection() {
        assert!(is_nonspacing_mark('\u{0301}'));
        assert!(!is_nonspacing_mark('e'));
    }
}
