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
    from collections import Counter
    allo = []
    for page in range(1, 9):
        s, d = req("GET", f"/v2/order-notes?limit=100&page={page}&shop_id={sid}", token)
        o = (d.get("orders") or []) if isinstance(d, dict) else []
        allo += o
        if len(o) < 100: break
    print("jami:", len(allo))
    print("holatlar:", Counter((str(o.get("order_status")), str(o.get("park_status")), bool(o.get("deleted"))) for o in allo).most_common(15))
    od = next((o.get("order_detail") for o in allo if isinstance(o.get("order_detail"), dict)), {})
    print("order_detail kalitlari:", sorted(od.keys())[:70] if od else od)
    def brief(o):
        det = o.get("order_detail") or {}
        prods = det.get("products") or det.get("order_products") or []
        names = []
        for p in prods[:3]:
            if isinstance(p, dict): names.append(str(p.get("name") or p.get("product_name") or (p.get("product") or {}).get("name") or "?")[:25])
        keep = {k: det.get(k) for k in ("created_at", "user_name", "seller_name", "cashbox_name", "shop_name", "comment", "future_time") if det.get(k)}
        return {"id": str(o.get("id"))[:8], "num": o.get("order_number"), "status": o.get("order_status"), "park": o.get("park_status"),
                "future": o.get("future_time"), "created": o.get("created_at") or o.get("created_at_utc"), "upd": o.get("updated_at"),
                "del": o.get("deleted"), "det": keep, "tovar": names}
    parked = [o for o in allo if o.get("park_status") not in (None, "", 0, "0", False) or o.get("future_time")]
    print("\nkechiktirilgan/belgili:", len(parked))
    for o in parked[:25]: print(json.dumps(brief(o), ensure_ascii=False)[:600])
    print("\neng yangi 8 ta (updated_at bo'yicha):")
    for o in sorted(allo, key=lambda o: str(o.get("updated_at") or ""), reverse=True)[:8]: print(json.dumps(brief(o), ensure_ascii=False)[:600])

main()
