"""Billz'ga rasm qo'yish sinovi (1 ta tovar).

Billz'da rasmi YO'Q, lekin ilovadan o'rgatilgan rasmi bor tovarni tanlaydi va o'sha rasmni
Billz'ga qo'yadi (Billz sayti ishlatadigan yo'l: POST /v1/upload -> PUT /v2/products/bulk/photo).
Rasmi bor tovarga TEGMAYDI. Natijani GET bilan tekshiradi.
SKU=... berilsa — aynan o'sha tovar (baribir rasmi yo'q bo'lishi shart).
"""
import csv, json, os, re, time, uuid, urllib.request, urllib.error

API = "https://api-admin.billz.ai"
PID = "7d4a4c38-dd84-4902-b744-0488b80a4c01"
HIDE = re.compile(r"supply|cost|profit|margin|token|secret|phone|password", re.I)


def clean(v, d=0):
    if d > 5: return "…"
    if isinstance(v, list): return [clean(x, d + 1) for x in v[:8]]
    if isinstance(v, dict): return {k: clean(x, d + 1) for k, x in v.items() if not HIDE.search(k)}
    return v


def req(method, path, token=None, body=None, raw=None, ctype="application/json", channel=True):
    h = {"Content-Type": ctype, "platform-id": PID, "Accept": "application/json"}
    if channel: h["Billz-Response-Channel"] = "HTTP"
    if token: h["Authorization"] = "Bearer " + token
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(API + path, method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            t = resp.read().decode()
            try: return resp.status, (json.loads(t) if t.strip() else {})
            except ValueError: return resp.status, t[:400]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:600]


def show(step, s, d, n=1200):
    print(f"\n### {step}: HTTP {s}")
    print(json.dumps(clean(d), ensure_ascii=False)[:n] if not isinstance(d, str) else d)


def photos_of(p):
    return [x for x in (p.get("photos") or p.get("images") or []) if x]


def find_product(tok, sku):
    s, d = req("GET", f"/v2/products?search={urllib.parse.quote(sku)}&limit=20&page=1", tok, channel=False)
    for p in (d.get("products") or []) if isinstance(d, dict) else []:
        if (p.get("sku") or "").strip() == sku:
            return p
    return None


import urllib.parse

tok = req("POST", "/v1/auth/login", body={"secret_token": os.environ["BILLZ_SECRET_TOKEN"].strip()})[1]["data"]["access_token"]

# Nomzodlar: bazada rasmi faqat ilovadan (GitHub) bo'lgan tovarlar
rows = list(csv.reader(open("baza/products.csv", encoding="utf-8-sig"), delimiter=";"))
h = rows[0]; iN, iK, iP, iR = h.index("nomi"), h.index("kod"), h.index("rasm"), h.index("rasmlar")
want = os.environ.get("SKU", "").strip()
cands = []
for r in rows[1:]:
    urls = [r[iP]] + r[iR].split()
    urls = [u for u in urls if u.startswith("http")]
    if urls and all("github.com" in u for u in urls) and (not want or r[iK].strip() == want):
        cands.append((r[iK].strip(), r[iN].strip(), urls[0]))
print("Nomzodlar (Billz rasmi yo'q, ilova rasmi bor):", len(cands))

prod = None
for sku, name, url in cands[:15]:
    p = find_product(tok, sku)
    if not p:
        print("  Billz'da topilmadi:", sku); continue
    if photos_of(p) or p.get("main_image_url"):
        print("  Rasmi bor, tegmaymiz:", sku, name); continue
    prod, photo_url = p, url
    break
if not prod:
    raise SystemExit("Mos tovar topilmadi")
print(f"\nTANLANDI: {prod.get('sku')} | {prod.get('name')} | id {prod['id']}")
show("0) Tovar (oldin) — rasm maydonlari", 200, {k: prod.get(k) for k in ("photos", "images", "main_image_url", "main_image_url_full")})

# 1) Rasmni yuklab olish (GitHub)
img = urllib.request.urlopen(urllib.request.Request(photo_url, headers={"User-Agent": "turfa-narx"}), timeout=60).read()
print("\nRasm:", photo_url, len(img), "bayt")

# 2) Billz'ga yuklash (multipart, maydon nomi "file" — Billz sayti shunday yuboradi)
b = "----turfa" + uuid.uuid4().hex
body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{prod.get('sku')}.jpg\"\r\n"
        f"Content-Type: image/jpeg\r\n\r\n").encode() + img + f"\r\n--{b}--\r\n".encode()
s, up = req("POST", "/v1/upload", tok, raw=body, ctype=f"multipart/form-data; boundary={b}")
show("1) POST /v1/upload", s, up)
data = up.get("data", up) if isinstance(up, dict) else {}
if isinstance(data, list): data = data[0] if data else {}
fname = (data or {}).get("filename") or (data or {}).get("file_name") or (data or {}).get("name")
if not fname and os.environ.get("URL_SINOV") == "1":
    # Yuklash yopiq bo'lsa: rasmni to'g'ridan-to'g'ri havola bilan berib ko'ramiz (o'sha tovarning o'z rasmi)
    fname = photo_url
    print("\nYuklash yopiq — havola bilan urinib ko'ramiz:", fname)
if not fname:
    raise SystemExit("Yuklangan rasm nomi olinmadi — to'xtadik (tovarga hech narsa yozilmadi)")

# 3) Tovarga biriktirish
s, d = req("PUT", "/v2/products/bulk/photo", tok, {"photos": [{"image": fname, "is_main": True}], "product_ids": [prod["id"]]})
show("2) PUT /v2/products/bulk/photo", s, d)

# 4) Tekshirish
for i in range(6):
    time.sleep(5)
    s, d = req("GET", f"/v2/product/{prod['id']}", tok, channel=False)
    p = d.get("data", d) if isinstance(d, dict) else {}
    ph = photos_of(p) if isinstance(p, dict) else []
    if ph or (isinstance(p, dict) and p.get("main_image_url")):
        show("3) GET tovar (keyin) — rasm maydonlari", s, {k: p.get(k) for k in ("photos", "images", "main_image_url")})
        print("\nNATIJA: ✅ rasm Billz'ga qo'yildi")
        break
else:
    show("3) GET tovar (keyin)", s, d, 2000)
    print("\nNATIJA: ❌ rasm ko'rinmadi")
