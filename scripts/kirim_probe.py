import os,sys,json,urllib.request
sys.path.insert(0,'scripts'); import billz_sync as b
tok=b.req("POST","/v1/auth/login",body={"secret_token":os.environ["BILLZ_SECRET_TOKEN"].strip()})["data"]["access_token"]
d=b.req("GET","/v2/import?limit=100&page=1",tok)
im=d["imports"]; print("COUNT",d.get("count"),len(im), "KEYS",[k for k in d if k!="imports"])
for x in im[:40]: print("IMP",x["created_at"],x.get("finished_at"),x["shop_name"],x["name"],x["total_arrived_measurement_value"],x.get("import_status_id")[:6])
i=im[0]["id"]
for path in [f"/v2/import/{i}",f"/v2/import/{i}/items",f"/v2/import-items?import_id={i}",f"/v2/import/items?import_id={i}",f"/v2/import/{i}/products",f"/v2/import-item?import_id={i}",f"/v1/import/{i}"]:
    try: r=b.req("GET",path,tok); s=json.dumps(r,ensure_ascii=False); print("OK",path,s[:1500])
    except urllib.error.HTTPError as e: print(e.code,path,e.read()[:100])
