"""Billz API -> baza/products.csv (faqat Turfa do'koni).
Tannarx (supply_price) HECH QACHON yozilmaydi.
Kalit GitHub secret'dan olinadi: BILLZ_SECRET_TOKEN."""
import csv, io, json, os, re, sys, urllib.request

API = "https://api-admin.billz.ai"
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

    rows = []
    for p in products:
        sp = next((s for s in p.get("shop_prices") or [] if SHOP in (s.get("shop_name") or "").lower()), None)
        if not sp or num(sp.get("retail_price")) <= 0: continue
        sid = sp.get("shop_id")
        qty = next((num(m.get("active_measurement_value")) for m in p.get("shop_measurement_values") or []
                    if m.get("shop_id") == sid), 0)
        opt = [num(x.get("wholesale_price")) for x in p.get("product_supplier_stock") or []
               if x.get("shop_id") == sid and num(x.get("wholesale_price")) > 0]
        if not opt:
            opt = [num(x.get("wholesale_price")) for x in p.get("product_supplier_stock") or []
                   if num(x.get("wholesale_price")) > 0]
        photo = p.get("main_image_url") or p.get("main_image_url_full") or ""
        if not photo:
            ph = sorted(p.get("photos") or [], key=lambda x: (not x.get("is_main"), x.get("sequence") or 0))
            photo = ph[0]["photo_url"] if ph else ""
        name = re.sub(r"\s+", " ", p.get("name") or "").strip()
        if not name: continue
        rows.append([name, (p.get("sku") or "").strip(), (p.get("barcode") or "").strip(),
                     str(num(sp.get("retail_price"))),
                     "-".join(map(str, sorted(set(opt)))), str(qty), photo])

    if len(rows) < 100:
        sys.exit(f"Juda kam mahsulot ({len(rows)}), baza o'zgartirilmadi")
    nat = lambda s: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s.lower())]
    rows.sort(key=lambda r: nat(r[0]))
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\n")
    w.writerow(["nomi", "kod", "shtrix", "dona narx", "optom narx", "qoldiq", "rasm"])
    w.writerows(rows)
    open("baza/products.csv", "w", encoding="utf-8").write("﻿" + buf.getvalue())
    print(f"Yozildi: {len(rows)} ta, rasmli: {sum(1 for r in rows if r[6])}")

if __name__ == "__main__":
    main()
