import os,sys,json,urllib.request
sys.path.insert(0,'scripts'); import billz_sync as b
tok=b.req("POST","/v1/auth/login",body={"secret_token":os.environ["BILLZ_SECRET_TOKEN"].strip()})["data"]["access_token"]
p=b.req("GET","/v2/products?limit=3&page=1",tok)["products"]
for x in p:
    for k in ("product_supplier_stock","shop_measurement_values","suppliers","custom_fields"):
        print("PF",k,json.dumps(x.get(k),ensure_ascii=False)[:500])
im=b.req("GET","/v2/import?limit=1&page=1",tok)["imports"][0]; i=im["id"]; print("IMPFULL",json.dumps({k:v for k,v in im.items() if 'price' not in k},ensure_ascii=False)[:1500])
for path in [f"/v2/import?limit=1&page=1&with_items=true",f"/v2/import?limit=1&page=1&include_items=true",f"/v2/import?limit=1&page=1&import_id={i}",f"/v2/import-items/{i}",f"/v2/import/{i}/item",f"/v2/import/items/{i}",f"/v2/import-product?import_id={i}",f"/v2/import/{i}/import-items",f"/v2/stock-movement?limit=2",f"/v2/product-movements?limit=2",f"/v2/products?limit=1&page=1&sort=created_at",f"/v2/product/{p[0]['id']}",f"/v2/product-history/{p[0]['id']}"]:
    try: r=b.req("GET",path,tok); s=json.dumps(r,ensure_ascii=False); print("OK",path,s[:1200])
    except urllib.error.HTTPError as e: print(e.code,path,e.read()[:100])
