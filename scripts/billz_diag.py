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
    allo = []
    for page in range(1, 9):
        s, d = req("GET", f"/v2/order-notes?limit=100&page={page}", token)
        o = (d.get("orders") or []) if isinstance(d, dict) else []
        allo += o
        if len(o) < 100: break
    print("hamma do'konlar, jami:", len(allo))
    hits = []
    for o in allo:
        det = o.get("order_detail") or {}
        c = str(det.get("created_at") or "")
        if c.startswith("2026-11-10 15:") or c.startswith("2026-11-10 14:5") or "SINOV" in str(det.get("comment") or ""):
            hits.append(o)
    print("bugun 14:50–15:59 yoki SINOV izohli:", len(hits))
    for o in hits:
        det = o.get("order_detail") or {}
        print(json.dumps({"id": str(o.get("id"))[:8], "num": o.get("order_number"), "type": o.get("order_type"),
              "park": o.get("park_status"), "future": o.get("future_time"), "del": o.get("deleted"),
              "created": det.get("created_at"), "shop": (det.get("shop") or {}).get("name") if isinstance(det.get("shop"), dict) else det.get("shop"),
              "kassa": det.get("cashbox_name"), "izoh": str(det.get("comment") or "")[:60],
              "jami_dona": det.get("total_products_measurement_value")}, ensure_ascii=False))

main()
