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
    for q in ("limit=50&page=1", f"limit=50&page=1&shop_id={sid}"):
        s, d = req("GET", f"/v2/order-notes?{q}", token)
        print(f"\n=== /v2/order-notes {'+shop' if 'shop' in q else ''}: HTTP {s} count={d.get('count') if isinstance(d, dict) else '-'}")
        orders = d.get("orders") if isinstance(d, dict) else None
        if not orders: continue
        print("order kalitlari:", sorted(orders[0].keys())[:60])
        for o in orders[:30]:
            prods = o.get("order_products") or o.get("products") or []
            names = [str(p.get("product_name") or p.get("name") or (p.get("product") or {}).get("name") or "?")[:30] for p in prods][:4]
            def g(*ks):
                for k in ks:
                    v = o.get(k)
                    if isinstance(v, dict): v = v.get("name")
                    if v not in (None, ""): return str(v)[:40]
                return "-"
            print(" |", g("created_at"), "| shop:", g("shop", "shop_name"), "| kassa:", g("cashbox", "cashbox_name"),
                  "| xodim:", g("user", "user_name", "seller", "created_by"), "| turi:", g("order_type", "type", "status"),
                  "| izoh:", g("comment"), "| tovar:", names)

main()
