"""Billz API -> baza/products.csv (faqat Turfa do'koni).
Tannarx (supply_price) HECH QACHON yozilmaydi.
Kalit GitHub secret'dan olinadi: BILLZ_SECRET_TOKEN."""
import csv, io, json, os, re, sys, urllib.request

API = "https://api-admin.billz.ai"
# Ilovadan yuklangan tovar rasmlari (Do'kon Yordamchisi serveri, faqat o'qish)
RASM_URL = os.environ.get("RASM_URL", "https://do-kon-savdo-yordamchisi.lovable.app/api/public/rasmlar")

def uploaded_photos():
    try:
        r = urllib.request.Request(RASM_URL, headers={"Accept": "application/json", "User-Agent": "turfa-narx"})
        with urllib.request.urlopen(r, timeout=30) as resp:
            items = json.load(resp).get("items") or []
        out = {}
        for it in items:
            u = str(it.get("url") or "")
            if u.startswith("http") and it.get("sku"):
                out.setdefault(str(it["sku"]).strip(), []).append(u)
        print(f"Ilovadan yuklangan rasmlar: {sum(map(len, out.values()))} ta, {len(out)} ta tovar")
        return out
    except Exception as e:
        print("Ilova rasmlari olinmadi:", type(e).__name__)
        return {}
SHOP = os.environ.get("BILLZ_SHOP", "turfa").lower()

def req(method, path, token=None, body=None):
    r = urllib.request.Request(API + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Accept": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(r, timeout=60) as resp:
        return json.load(resp)

def num(v):
    try: return int(round(float(v or 0)))
    except (TypeError, ValueError): return 0

def main():
    secret = os.environ["BILLZ_SECRET_TOKEN"].strip()
    token = req("POST", "/v1/auth/login", body={"secret_token": secret})["data"]["access_token"]
    products, page = [], 1
    while True:
        d = req("GET", f"/v2/products?limit=500&page={page}&include_empty_stocks=true", token)
        batch = d.get("products") or []
        products += batch
        print(f"page {page}: {len(batch)} (jami {d.get('count')})")
        if not batch or len(products) >= (d.get("count") or 0): break
        page += 1

    # Turfa do'konining id si (qoldiq faqat shu do'kon bo'yicha)
    turfa_id = next((s.get("shop_id") for p in products for s in p.get("shop_prices") or []
                     if SHOP in (s.get("shop_name") or "").lower() and s.get("shop_id")), None)
    print("Turfa shop_id:", "topildi" if turfa_id else "TOPILMADI (qoldiq yozilmaydi)")

    # Kategoriyalar (diagnostika): Billz'da qanday kategoriyalar borligini ko'rish
    import collections
    ckeys = sorted({k for p in products for k in p if "categor" in k.lower()})
    print("Kategoriya maydonlari:", ckeys)
    def cat(p):
        for k in ckeys:
            v = p.get(k)
            if isinstance(v, list) and v:
                x = v[0]
                return (x.get("name") if isinstance(x, dict) else str(x)) or ""
            if isinstance(v, dict) and v.get("name"): return v["name"]
            if isinstance(v, str) and v: return v
        return ""
    cc = collections.Counter(cat(p) for p in products)
    print(f"Kategoriyasiz: {cc.get('', 0)} / {len(products)}, kategoriyalar soni: {len(cc) - (1 if '' in cc else 0)}")
    for n, c in cc.most_common(40): print(f"  {c:5d}  {n or '(yo`q)'}")

    upl = uploaded_photos()
    rows = []
    for p in products:
        prices = p.get("shop_prices") or []
        # Asosiy narx — Turfa. Turfada narx bo'lmasa, boshqa do'kondagi narx (tovar tushib qolmasin).
        sp = next((s for s in prices if SHOP in (s.get("shop_name") or "").lower() and num(s.get("retail_price")) > 0), None) \
            or next((s for s in prices if num(s.get("retail_price")) > 0), None) or {}
        sid = sp.get("shop_id")
        opt = [num(x.get("wholesale_price")) for x in p.get("product_supplier_stock") or []
               if x.get("shop_id") == sid and num(x.get("wholesale_price")) > 0]
        if not opt:
            opt = [num(x.get("wholesale_price")) for x in p.get("product_supplier_stock") or []
                   if num(x.get("wholesale_price")) > 0]
        ph = sorted(p.get("photos") or [], key=lambda x: (not x.get("is_main"), x.get("sequence") or 0))
        cands = [p.get("main_image_url_full"), *[x.get("photo_url") for x in ph], p.get("main_image_url")]
        urls = list(dict.fromkeys(str(c) for c in cands if c and str(c).startswith("http")))[:5]
        urls += [u for u in upl.get((p.get("sku") or "").strip(), []) if u not in urls][-6:]   # ilovadan yuklanganlar
        photo = urls[0] if urls else ""
        extra = " ".join(urls[1:11])   # qo'shimcha rasmlar (rasm orqali qidirish uchun)
        if not photo and any(cands) and not globals().get("_shown"):
            globals()["_shown"] = True
            print("Rasm namunasi (to'liq havola emas):", [c for c in cands if c][:3])
        qty = ""
        if turfa_id:
            qty = str(num(sum(float(m.get("active_measurement_value") or 0)
                              for m in p.get("shop_measurement_values") or [] if m.get("shop_id") == turfa_id)))
        name = re.sub(r"\s+", " ", p.get("name") or "").strip()
        if not name: continue
        rows.append([name, (p.get("sku") or "").strip(), (p.get("barcode") or "").strip(),
                     str(num(sp.get("retail_price"))),
                     "-".join(map(str, sorted(set(opt)))), photo, qty, extra])

    if len(rows) < 100:
        sys.exit(f"Juda kam mahsulot ({len(rows)}), baza o'zgartirilmadi")
    nat = lambda s: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s.lower())]
    rows.sort(key=lambda r: nat(r[0]))
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\n")
    w.writerow(["nomi", "kod", "shtrix", "dona narx", "optom narx", "rasm", "qoldiq", "rasmlar"])
    w.writerows(rows)
    open("baza/products.csv", "w", encoding="utf-8").write("﻿" + buf.getvalue())
    print(f"Yozildi: {len(rows)} ta (Billz'da {len(products)} ta), rasmli: {sum(1 for r in rows if r[5])} (qo'shimcha rasm: {sum(len(r[7].split()) for r in rows)}), narxsiz: {sum(1 for r in rows if r[3] == '0')}, "
          f"Turfada bor: {sum(1 for r in rows if r[6] not in ('', '0'))}")

if __name__ == "__main__":
    main()
