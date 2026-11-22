#!/usr/bin/env python3
"""src/unicode_tables.rs dosyasını üretir (NFC/NFD için kanonik ayrışım, birleşim ve ccc tabloları).
Kullanım: python3 tools/gen_unicode.py > src/unicode_tables.rs"""
import sys, unicodedata as u

HS, HE = 0xAC00, 0xD7A4
decomp, ccc, mn, comp = {}, {}, [], []
for cp in range(0x110000):
    if 0xD800 <= cp <= 0xDFFF or HS <= cp < HE:
        continue
    c = chr(cp)
    d = u.normalize("NFD", c)
    if d != c:
        decomp[cp] = [ord(x) for x in d]
    k = u.combining(c)
    if k:
        ccc[cp] = k
    if u.category(c) == "Mn":
        mn.append(cp)
    raw = u.decomposition(c)
    if raw and not raw.startswith("<"):
        parts = [int(x, 16) for x in raw.split()]
        if len(parts) == 2 and u.normalize("NFC", chr(parts[0]) + chr(parts[1])) == c:
            comp.append((parts[0], parts[1], cp))

def ranges(xs):
    out, s, p = [], None, None
    for x in xs:
        if s is None: s = p = x
        elif x == p + 1: p = x
        else: out.append((s, p)); s = p = x
    if s is not None: out.append((s, p))
    return out

o = sys.stdout
o.write(f"// OTOMATİK ÜRETİLDİ: tools/gen_unicode.py (Unicode {u.unidata_version}). Elle düzenleme.\n")
o.write("pub static DECOMP_DATA: &[u32] = &[" + ",".join(str(x) for cp in sorted(decomp) for x in decomp[cp]) + "];\n")
off = 0; rows = []
for cp in sorted(decomp):
    rows.append((cp, off, len(decomp[cp]))); off += len(decomp[cp])
o.write("pub static DECOMP: &[(u32, u32, u8)] = &[" + ",".join(f"({a},{b},{c})" for a, b, c in rows) + "];\n")
o.write("pub static CCC: &[(u32, u8)] = &[" + ",".join(f"({k},{v})" for k, v in sorted(ccc.items())) + "];\n")
o.write("pub static MN: &[(u32, u32)] = &[" + ",".join(f"({a},{b})" for a, b in ranges(mn)) + "];\n")
comp.sort()
o.write("pub static COMPOSE: &[(u32, u32, u32)] = &[" + ",".join(f"({a},{b},{c})" for a, b, c in comp) + "];\n")
