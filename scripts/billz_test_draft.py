"""Billz'da 1 ta sinov qoralamasi (SHAR CHOP, 1 dona, Turfa) — har qadam javobi bilan.
ACTION=create  -> yaratadi va kechiktiradi (o'chirmaydi, foydalanuvchi kassada tekshiradi)
ACTION=delete ORDER_ID=... -> o'sha chekni o'chiradi."""
import json, os, re, time, urllib.request, urllib.error

API = "https://api-admin.billz.ai"
PID = "7d4a4c38-dd84-4902-b744-0488b80a4c01"
HIDE = re.compile(r"supply|cost|profit|margin|token|secret|phone|password", re.I)

def clean(v, d=0):
    if d > 5: return "…"
    if isinstance(v, list): return [clean(x, d + 1) for x in v[:8]]
    if isinstance(v, dict): return {k: clean(x, d + 1) for k, x in v.items() if not HIDE.search(k)}
    return v

def req(method, path, token=None, body=None, channel=True):
    h = {"Content-Type": "application/json", "platform-id": PID}
    if channel: h["Billz-Response-Channel"] = "HTTP"
    if token: h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(API + path, method=method, headers=h,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            t = resp.read().decode()
            return resp.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]

def show(step, s, d, n=900):
    print(f"\n### {step}: HTTP {s}")
    print(json.dumps(clean(d), ensure_ascii=False)[:n] if not isinstance(d, str) else d)

tok = req("POST", "/v1/auth/login", body={"secret_token": os.environ["BILLZ_SECRET_TOKEN"].strip()})[1]["data"]["access_token"]
action = os.environ.get("ACTION", "create")

def remove(oid):
    show("cancel_postpone " + oid[:8], *req("PUT", "/v2/order/cancel_postpone", tok, {"order_id": oid}))
    show("DELETE " + oid[:8], *req("DELETE", f"/v2/order/{oid}", tok))

for old in filter(None, os.environ.get("ORDER_ID", "").split(",")):
    remove(old.strip())
if action == "delete":
    raise SystemExit

s, shops = req("GET", "/v1/shop?limit=50&page=1", tok)
shop = next(x for x in shops["shops"] if "turfa" in x["name"].lower())
s, cb = req("GET", f"/v1/cash-box?shop_id={shop['id']}&limit=100", tok)
boxes = cb.get("cash_boxes") or []
print("Kassalar:", [(b.get("name"), b.get("is_active")) for b in boxes])
box = next((b for b in boxes if b.get("is_active") is not False), boxes[0])
prod = None
for q in ("search=WKX-54801&limit=20&page=1", "search=2000000009742&limit=20&page=1", "search=SHAR%20CHOP&limit=20&page=1"):
    s, pr = req("GET", "/v2/products?" + q, tok, channel=False)
    lst = (pr.get("products") or []) if isinstance(pr, dict) else []
    print("qidiruv", q.split("&")[0], "HTTP", s, "topildi", len(lst))
    prod = next((p for p in lst if p.get("sku") == "WKX-54801" or p.get("barcode") == "2000000009742"), None)
    if prod: break
if not prod: raise SystemExit("SHAR CHOP topilmadi")
print("Tovar:", prod["name"], prod["id"][:8])

s, d = req("POST", "/v2/order", tok, {"shop_id": shop["id"], "cashbox_id": box["id"]})
show("1) POST /v2/order", s, d)
oid = (d.get("data") or {}).get("id") if isinstance(d, dict) else None
print("ORDER_ID:", oid)
if not oid: raise SystemExit

s, d = req("POST", f"/v2/order-product/{oid}", tok,
           {"product_id": prod["id"], "sold_measurement_value": 1, "used_wholesale_price": False, "is_manual": False,
            "response_type": "HTTP"})
show("2) POST /v2/order-product", s, d)
time.sleep(2)
show("2b) GET /v2/order/{id} (tovar qo'shilgandan keyin)", *req("GET", f"/v2/order/{oid}", tok), n=1500)

fut = time.strftime("%Y-%m-%d 23:59:59", time.gmtime(time.time() + 5 * 3600))
s, d = req("POST", "/v2/order/create_postpone", tok, {"order_id": oid, "time": fut, "comment": "SINOV - TUR-FA narxlar (o'chirish mumkin)"})
show("3) POST /v2/order/create_postpone", s, d)
time.sleep(3)
show("4) GET /v2/order/{id} (kechiktirilgandan keyin)", *req("GET", f"/v2/order/{oid}", tok), n=1500)
s, d = req("GET", f"/v2/order-notes?limit=20&page=1&shop_id={shop['id']}", tok)
mine = [o for o in (d.get("orders") or []) if o.get("id") == oid] if isinstance(d, dict) else []
print("\n### 5) order-notes ichida bormi:", bool(mine), "| park:", mine[0].get("park_status") if mine else None, "| future:", mine[0].get("future_time") if mine else None)
s, d = req("GET", f"/v1/postponed-orders?limit=20&page=1&shop_ids={shop['id']}", tok, channel=False)
lst = (d.get("orders") or d.get("postponed_orders") or d.get("data") or []) if isinstance(d, dict) else []
print("### 6) /v1/postponed-orders: HTTP", s, "| kalitlar:", list(d.keys())[:8] if isinstance(d, dict) else d[:200], "| soni:", len(lst) if isinstance(lst, list) else lst)
if isinstance(lst, list):
    for o in lst[:10]:
        o2 = clean(o)
        print("  -", json.dumps({k: o2.get(k) for k in ("id", "order_id", "order_number", "comment", "time", "user_name", "user", "cashbox_name", "total_price") if k in o2}, ensure_ascii=False)[:300])
