"""FAQAT O'QISH (GET): Billz'da transfer (ko'chirish) API manzilini topish.
Logga faqat holat kodi, kalit nomlari va bir nechta qisqa maydon chiqadi; narx/tannarx chiqmaydi."""
import json, os, urllib.request, urllib.error

API = "https://api-admin.billz.ai"
PID = "7d4a4c38-dd84-4902-b744-0488b80a4c01"
HIDE = ("price", "cost", "supply", "sum", "amount", "phone", "token")

def req(method, path, token=None, body=None):
    h = {"Content-Type": "application/json", "platform-id": PID}
    if token: h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(API + path, method=method, headers=h,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]
    except Exception as e:
        return 0, str(e)[:200]

def shape(v, d=0):
    if d > 3: return "…"
    if isinstance(v, list): return [shape(v[0], d + 1), f"(+{len(v)-1})"] if v else []
    if isinstance(v, dict):
        return {k: ("<yashirin>" if any(x in k.lower() for x in HIDE) else shape(x, d + 1)) for k, x in v.items()}
    if isinstance(v, str): return v[:60]
    return v

def first_list(d):
    if isinstance(d, list): return d
    if isinstance(d, dict):
        for k, v in d.items():
            if isinstance(v, list) and v and isinstance(v[0], dict): return v
    return []

def main():
    s, d = req("POST", "/v1/auth/login", body={"secret_token": os.environ["BILLZ_SECRET_TOKEN"].strip()})
    token = d["data"]["access_token"]
    s, d = req("GET", "/v2/transfer?limit=1&page=1", token)
    tid = d["transfer"][0]["id"]
    s, d2 = req("GET", "/v2/import?limit=1&page=1", token)
    iid = d2["imports"][0]["id"]
    subs = ["/v2/transfer-items/{id}", "/v2/transfer_items?transfer_id={id}", "/v2/transfer-item/list?transfer_id={id}",
            "/v3/transfer/{id}", "/v3/transfer-item?transfer_id={id}", "/v2/transfer/{id}/transfer_items",
            "/v2/transfer/item/{id}", "/v2/transfer-products/{id}", "/v1/transfer/{id}", "/v2/transfer/{id}/products-list",
            "/v2/transfer-product-list?transfer_id={id}", "/v2/transfer-item-search?transfer_id={id}",
            "/v2/transfer/{id}/item", "/v2/transfer/{id}/goods", "/v2/transfer/products/{id}", "/v2/transfer/items/{id}",
            "IMP/v2/import-item?import_id={iid}", "IMP/v2/import-items?import_id={iid}", "IMP/v2/import/{iid}/items",
            "IMP/v2/import/{iid}/products", "IMP/v2/import/products/{iid}", "IMP/v2/import/items/{iid}", "IMP/v2/import-products?import_id={iid}"]
    for sub in subs:
        path = sub.replace("IMP", "").replace("{id}", tid).replace("{iid}", iid)
        s, d = req("GET", path + ("&" if "?" in path else "?") + "limit=3&page=1", token)
        print(f"\n=== {sub} -> {s}")
        print(json.dumps(shape(d), ensure_ascii=False)[:3000] if s == 200 else str(d)[:150])
    return
    cands = ["/v1/transfer", "/v2/transfer", "/v1/transfers", "/v2/transfers", "/v1/stock-transfer",
             "/v2/stock-transfer", "/v1/transfer-list", "/v2/transfer-list", "/v1/product-transfer",
             "/v2/product-transfer", "/v1/transfer/list", "/v2/transfer/list", "/v1/movement", "/v2/movement",
             "/v1/transfer-report", "/v1/transfer-table", "/v1/import", "/v2/import"]
    found = []
    for p in cands:
        s, d = req("GET", p + "?limit=3&page=1", token)
        print(f"\n=== {p} -> {s}")
        if s == 200:
            print(json.dumps(shape(d), ensure_ascii=False)[:2500])
            lst = first_list(d)
            if lst and p not in ("/v1/import", "/v2/import"): found.append((p, lst[0]))
        else:
            print(str(d)[:150])
    for p, item in found[:3]:
        tid = item.get("id")
        if not tid: continue
        for sub in ["/{id}", "/{id}/products", "/{id}/items", "-product?transfer_id={id}", "/products?transfer_id={id}"]:
            path = p + sub.replace("{id}", str(tid))
            s, d = req("GET", path + ("&" if "?" in path else "?") + "limit=3&page=1", token)
            print(f"\n=== {p}{sub} -> {s}")
            print(json.dumps(shape(d), ensure_ascii=False)[:2500] if s == 200 else str(d)[:150])

main()
