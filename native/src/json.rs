//! Küçük, bağımlılıksız JSON ayrıştırıcı (Wallhaven arama yanıtı için). Yalnızca okuma.

#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Value>),
    Obj(Vec<(String, Value)>),
}

impl Value {
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Obj(v) => v.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }
    pub fn as_arr(&self) -> Option<&[Value]> {
        match self {
            Value::Arr(v) => Some(v),
            _ => None,
        }
    }
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::Str(s) => Some(s),
            _ => None,
        }
    }
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Num(n) => Some(*n),
            _ => None,
        }
    }
}

/// İç içe en çok bu kadar dizi/nesne (yığın taşmasını önler).
const MAX_DEPTH: usize = 64;

pub fn parse(s: &str) -> Option<Value> {
    let mut p = P { b: s.as_bytes(), i: 0 };
    p.ws();
    let v = p.value(0)?;
    p.ws();
    if p.i == p.b.len() {
        Some(v)
    } else {
        None
    }
}

struct P<'a> {
    b: &'a [u8],
    i: usize,
}

impl P<'_> {
    fn ws(&mut self) {
        while matches!(self.b.get(self.i), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.i += 1;
        }
    }

    fn eat(&mut self, c: u8) -> bool {
        if self.b.get(self.i) == Some(&c) {
            self.i += 1;
            true
        } else {
            false
        }
    }

    fn lit(&mut self, word: &str, v: Value) -> Option<Value> {
        if self.b[self.i..].starts_with(word.as_bytes()) {
            self.i += word.len();
            Some(v)
        } else {
            None
        }
    }

    fn value(&mut self, depth: usize) -> Option<Value> {
        if depth > MAX_DEPTH {
            return None;
        }
        match *self.b.get(self.i)? {
            b'{' => {
                self.i += 1;
                let mut out = Vec::new();
                self.ws();
                if self.eat(b'}') {
                    return Some(Value::Obj(out));
                }
                loop {
                    self.ws();
                    let k = self.string()?;
                    self.ws();
                    if !self.eat(b':') {
                        return None;
                    }
                    self.ws();
                    let v = self.value(depth + 1)?;
                    out.push((k, v));
                    self.ws();
                    if self.eat(b',') {
                        continue;
                    }
                    return if self.eat(b'}') { Some(Value::Obj(out)) } else { None };
                }
            }
            b'[' => {
                self.i += 1;
                let mut out = Vec::new();
                self.ws();
                if self.eat(b']') {
                    return Some(Value::Arr(out));
                }
                loop {
                    self.ws();
                    out.push(self.value(depth + 1)?);
                    self.ws();
                    if self.eat(b',') {
                        continue;
                    }
                    return if self.eat(b']') { Some(Value::Arr(out)) } else { None };
                }
            }
            b'"' => self.string().map(Value::Str),
            b't' => self.lit("true", Value::Bool(true)),
            b'f' => self.lit("false", Value::Bool(false)),
            b'n' => self.lit("null", Value::Null),
            b'-' | b'0'..=b'9' => self.number(),
            _ => None,
        }
    }

    fn number(&mut self) -> Option<Value> {
        let start = self.i;
        while matches!(self.b.get(self.i), Some(b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9')) {
            self.i += 1;
        }
        std::str::from_utf8(&self.b[start..self.i]).ok()?.parse::<f64>().ok().map(Value::Num)
    }

    fn hex4(&mut self) -> Option<u32> {
        let h = self.b.get(self.i..self.i + 4)?;
        let s = std::str::from_utf8(h).ok()?;
        let v = u32::from_str_radix(s, 16).ok()?;
        self.i += 4;
        Some(v)
    }

    fn string(&mut self) -> Option<String> {
        if !self.eat(b'"') {
            return None;
        }
        let mut out: Vec<u8> = Vec::new();
        loop {
            let c = *self.b.get(self.i)?;
            self.i += 1;
            match c {
                b'"' => return String::from_utf8(out).ok(),
                b'\\' => {
                    let e = *self.b.get(self.i)?;
                    self.i += 1;
                    let ch = match e {
                        b'"' => '"',
                        b'\\' => '\\',
                        b'/' => '/',
                        b'b' => '\u{8}',
                        b'f' => '\u{c}',
                        b'n' => '\n',
                        b'r' => '\r',
                        b't' => '\t',
                        b'u' => {
                            let hi = self.hex4()?;
                            let cp = if (0xD800..0xDC00).contains(&hi) {
                                // yüksek vekil: ardından \uDC00..DFFF gelmeli
                                if self.b.get(self.i) == Some(&b'\\') && self.b.get(self.i + 1) == Some(&b'u') {
                                    self.i += 2;
                                    let lo = self.hex4()?;
                                    if !(0xDC00..0xE000).contains(&lo) {
                                        return None;
                                    }
                                    0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
                                } else {
                                    return None;
                                }
                            } else {
                                hi
                            };
                            char::from_u32(cp)?
                        }
                        _ => return None,
                    };
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                }
                0..=0x1f => return None,
                _ => out.push(c),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nested_values() {
        let v = parse(r#" {"a":[1,2.5,-3e2,true,false,null],"b":{"c":"x"},"d":""} "#).unwrap();
        let a = v.get("a").unwrap().as_arr().unwrap();
        assert_eq!(a[0].as_f64(), Some(1.0));
        assert_eq!(a[1].as_f64(), Some(2.5));
        assert_eq!(a[2].as_f64(), Some(-300.0));
        assert_eq!(a[3], Value::Bool(true));
        assert_eq!(a[5], Value::Null);
        assert_eq!(v.get("b").unwrap().get("c").unwrap().as_str(), Some("x"));
        assert_eq!(v.get("d").unwrap().as_str(), Some(""));
        assert!(v.get("zzz").is_none());
    }

    #[test]
    fn decodes_escapes_and_unicode() {
        let v = parse(r#""a\/b\"c\\d\n\u00e7\u0131 \ud83d\ude00 ğ""#).unwrap();
        assert_eq!(v.as_str(), Some("a/b\"c\\d\nçı 😀 ğ"));
    }

    #[test]
    fn rejects_malformed() {
        for bad in ["", "{", "[1,]", "{\"a\"}", "{\"a\":1,}", "tru", "\"abc", "[1] x", "\"\\ud800\"", "\"\\q\"", "{'a':1}", "\"a\nb\""] {
            assert!(parse(bad).is_none(), "kabul edildi: {bad:?}");
        }
    }

    #[test]
    fn depth_is_limited() {
        let deep = "[".repeat(10_000) + &"]".repeat(10_000);
        assert!(parse(&deep).is_none());
        let ok = "[".repeat(50) + &"]".repeat(50);
        assert!(parse(&ok).is_some());
    }
}
