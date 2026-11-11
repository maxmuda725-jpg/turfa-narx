"""Har tovarga "aqlli" kategoriya: baza/products.csv ga "kategoriya" ustuni ("Kategoriya|Bo'lim").

Tartib (birinchi topilgani):
 1. Kalit so'z (baza/kategoriyalar.txt) — bo'lim aniq bo'lsa
 2. Seriya: bir seriyadagi (43-39 -> "43") boshqa tovarlarning ko'pchiligi qaysi bo'limda bo'lsa (sinovda ~97%)
 3. Rasm: eng o'xshash rasmli tovarning bo'limi (o'xshashlik >= 0.70, sinovda ~97%)
 4. Kategoriyasiz tovar ham seriyasi/rasmi orqali kategoriya oladi
Qo'lda almashtirilganlar ilovada ustun (server), bu yerda hisobga olinmaydi.
"""
import collections, csv, io, re, struct

OTHER, OTHER_SUB = "📦 Boshqalar", "Boshqalar"
APOS = re.compile(r"['`ʻʼ‘’]")
WORD = re.compile(r"[a-zа-яёé]+")
SEP = re.compile(r"[\s,;/|()\[\]{}\"'«»]+")
SIM = 0.70


def load_cats():
    cats = []
    for line in open("baza/kategoriyalar.txt", encoding="utf-8-sig"):
        t = line.strip()
        if not t or t.startswith("#") or "|" not in t:
            continue
        name, kws = t.split("|", 1)
        kws = [APOS.sub("", k.strip().lower()) for k in kws.split(",") if k.strip()]
        if line[:1] in (" ", "\t"):
            if cats: cats[-1][2].append((name.strip(), kws))
        else:
            cats.append((name.strip(), kws, []))
    return cats


def hit(ws, kws):
    return any(w == k or (len(k) >= 4 and w.startswith(k)) for w in ws for k in kws)


def auto(cats, name):
    ws = WORD.findall(APOS.sub("", name.lower()))
    for c, kws, subs in cats:
        if hit(ws, kws):
            if not subs:
                return c, None, True
            sub = next((sn for sn, sk in subs if hit(ws, sk)), None)
            return c, (sub or OTHER_SUB), sub is not None
    return OTHER, None, False


def series(name):
    """'gul 25-4-47-9' -> '25-4-47';  '01-04' -> '1'. Ilovadagi seriesKey bilan bir xil."""
    toks = [t for t in SEP.split(name.lower()) if "-" in t and any(ch.isdigit() for ch in t)]
    if not toks:
        return None
    parts = toks[-1].split("-")
    if len(parts) < 2:
        return None
    return "-".join(str(int(p)) if p.isdigit() else p for p in parts[:-1])


def load_vectors():
    try:
        import numpy as np
        b = open("baza/vectors.bin", "rb").read()
    except Exception:
        return None, None
    if b[:4] != b"TFV1":
        return None, None
    n, d = struct.unpack_from("<ii", b, 4)
    p, keys, vecs = 12, [], []
    for _ in range(n):
        (l,) = struct.unpack_from("<H", b, p); p += 2
        keys.append(b[p:p + l].decode()); p += l
        (l,) = struct.unpack_from("<H", b, p); p += 2 + l
        vecs.append(np.frombuffer(b[p:p + d], np.int8)); p += d
    return keys, (np.array(vecs, np.float32) / 127 if vecs else None)


def main():
    raw = open("baza/products.csv", encoding="utf-8-sig").read()
    rows = list(csv.reader(io.StringIO(raw), delimiter=";"))
    head = rows[0]
    if "kategoriya" in head:
        iC = head.index("kategoriya")
    else:
        head.append("kategoriya"); iC = len(head) - 1
    iN, iK = head.index("nomi"), head.index("kod")
    body = rows[1:]
    for r in body:
        while len(r) < len(head):
            r.append("")

    cats = load_cats()
    cat, sub, sure = [], [], []
    for r in body:
        c, s, ok = auto(cats, r[iN])
        cat.append(c); sub.append(s); sure.append(ok)
    stats = collections.Counter()

    # 2) seriya: bo'lim (shu kategoriya ichida) va kategoriya (kategoriyasizlar uchun)
    ser = [series(r[iN]) for r in body]
    by_cs = collections.defaultdict(list)
    by_s = collections.defaultdict(list)
    for i, s in enumerate(ser):
        if s:
            by_cs[(cat[i], s)].append(i); by_s[s].append(i)
    for i, s in enumerate(ser):
        if not s:
            continue
        if cat[i] == OTHER:
            mates = [cat[j] for j in by_s[s] if j != i and cat[j] != OTHER]
            if len(mates) >= 3:
                top, n = collections.Counter(mates).most_common(1)[0]
                if n / len(mates) >= 0.8:
                    cat[i] = top; stats["seriya: kategoriya"] += 1
                    c, s2, ok = next(((c, OTHER_SUB if subs else None, False) for c, kws, subs in cats if c == top), (top, None, False))
                    sub[i] = s2
        if sub[i] == OTHER_SUB:
            mates = [sub[j] for j in by_cs[(cat[i], s)] if j != i and sure[j] and sub[j] not in (None, OTHER_SUB)]
            if len(mates) >= 2:
                top, n = collections.Counter(mates).most_common(1)[0]
                if n / len(mates) >= 0.6:
                    sub[i] = top; stats["seriya: bo'lim"] += 1

    # 3) rasm: eng o'xshash "aniq" tovar
    keys, V = load_vectors()
    if V is not None and len(V):
        import numpy as np
        key2i = {f"{r[iK].strip()}|{r[iN].strip()}": i for i, r in enumerate(body)}
        vi = [key2i.get(k) for k in keys]
        S = V @ V.T
        np.fill_diagonal(S, -1)
        for a, i in enumerate(vi):
            if i is None or not (cat[i] == OTHER or sub[i] == OTHER_SUB):
                continue
            for b in np.argsort(-S[a])[:5]:
                j = vi[b]
                if S[a, b] < SIM:
                    break
                if j is None or j == i:
                    continue
                if cat[i] == OTHER and cat[j] != OTHER:
                    cat[i], sub[i] = cat[j], (sub[j] if sure[j] else (OTHER_SUB if sub[j] else None))
                    stats["rasm: kategoriya"] += 1
                    break
                if cat[i] == cat[j] and sure[j] and sub[j] not in (None, OTHER_SUB) and sub[i] == OTHER_SUB:
                    sub[i] = sub[j]; stats["rasm: bo'lim"] += 1
                    break

    for i, r in enumerate(body):
        r[iC] = cat[i] + (f"|{sub[i]}" if sub[i] else "")
    out = io.StringIO()
    w = csv.writer(out, delimiter=";", lineterminator="\n")
    w.writerow(head); w.writerows(body)
    open("baza/products.csv", "w", encoding="utf-8").write("﻿" + out.getvalue())
    left = sum(1 for i in range(len(body)) if cat[i] == OTHER)
    left_sub = sum(1 for i in range(len(body)) if sub[i] == OTHER_SUB)
    print("Aqlli kategoriya:", dict(stats), f"| kategoriyasiz qoldi: {left}, 'Boshqalar' bo'limda: {left_sub}")


if __name__ == "__main__":
    main()
