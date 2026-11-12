import os,sys,json,urllib.request
sys.path.insert(0,'scripts'); import billz_sync as b
tok=b.req("POST","/v1/auth/login",body={"secret_token":os.environ["BILLZ_SECRET_TOKEN"].strip()})["data"]["access_token"]
p=b.req("GET","/v2/products?limit=1&page=1",tok)
pr=(p.get("products") or [{}])[0]; print("PRODUCT KEYS:",sorted(pr.keys()))
for k in pr:
    if any(x in k for x in ("date","at","time","import")): print("  ",k,"=",pr[k])
paths=["/v2/imports","/v2/import","/v1/import","/v1/imports","/v2/import/list","/v1/import/list","/v2/stock-imports","/v2/stock-import","/v1/stock-import","/v2/stock/import","/v2/supplier-orders","/v1/supplier-order","/v2/orders?limit=2","/v2/transfers","/v1/transfer","/v2/documents","/v2/inventory","/v2/product-history","/v2/arrivals","/v2/income","/v2/import-products","/v1/import-products","/v2/purchase","/v2/purchases"]
for path in paths:
    sep='&' if '?' in path else '?'
    try:
        d=b.req("GET",path+sep+"limit=2&page=1",tok); s=json.dumps(d,ensure_ascii=False)
        print("OK",path,s[:900])
    except urllib.error.HTTPError as e: print(e.code,path,e.read()[:120])
    except Exception as e: print("ERR",path,e)
