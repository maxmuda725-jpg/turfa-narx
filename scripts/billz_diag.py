"""FAQAT O'QISH: Billz'dagi kechiktirilgan/qoralama cheklarni ko'rish (diagnostika).
Loglarga faqat qisqa maydonlar chiqadi; narx-tannarx, mijoz telefoni chiqmaydi."""
import json, os, urllib.request, urllib.error

API = "https://api-admin.billz.ai"
PID = "7d4a4c38-dd84-4902-b744-0488b80a4c01"

def req(method, path, token=None, body=None):
    h = {"Content-Type": "application/json", "platform-id": PID}
    if token: h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(API + path, method=method, headers=h,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]

KEEP = {"id", "status", "order_type", "type", "created_at", "updated_at", "future_time", "time", "comment",
        "shop_name", "cashbox_name", "user_name", "seller_name", "name", "first_name", "is_postponed",
        "postponed", "order_number", "number", "products_count", "sold_measurement_value", "product_name",
        "deleted_at", "is_draft", "state", "sale_type", "user", "seller", "shop", "cashbox", "products", "order_products"}

def slim(v, d=0):
    if d > 4: return "…"
    if isinstance(v, list): return [slim(x, d + 1) for x in v[:15]]
    if isinstance(v, dict): return {k: slim(x, d + 1) for k, x in v.items() if k in KEEP}
    return v

def main():
    s, d = req("POST", "/v1/auth/login", body={"secret_token": os.environ["BILLZ_SECRET_TOKEN"].strip()})
    token = d["data"]["access_token"]
    s, shops = req("GET", "/v1/shop?limit=50&page=1", token)
    turfa = next((x for x in (shops.get("shops") or []) if "turfa" in str(x.get("name", "")).lower()), {}) if isinstance(shops, dict) else {}
    print("Turfa shop:", turfa.get("name"), "| bor:", bool(turfa))
    sid = turfa.get("id", "")
    cands = ["/v2/order-notes", "/v2/orders/notes", "/v2/notes", "/v2/order-note",
             "/v2/order/postpones", "/v2/orders/postpone", "/v2/orders", "/v1/orders", "/v2/order/drafts",
             "/v1/order/notes", "/v1/order-notes"]
    for path in cands:
        for q in ("limit=10&page=1", f"limit=10&page=1&shop_id={sid}"):
            s, d = req("GET", f"{path}?{q}", token)
            short = (json.dumps(slim(d.get("data", d)), ensure_ascii=False)[:1500] if isinstance(d, dict) else str(d)[:200])
            keys = list(d.keys())[:8] if isinstance(d, dict) else "-"
            print(f"\n=== GET {path}?{q.split('&shop')[0]}{' +shop' if 'shop' in q else ''}: HTTP {s} keys={keys}")
            if s == 200: print(short)
            else: print(short[:160])
            if s != 200: break

main()
