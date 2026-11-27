"""Billz API kaliti rasm qo'yishga ruxsat beradimi — diagnostika.

Tovarlarga HECH NARSA yozmaydi. Faqat:
  - kalit bilan kirib, tokendagi rol/huquq maydonlarini (nomlarini) ko'rsatadi;
  - bitta tovarni o'qiydi;
  - 1x1 piksel rasmni Billz omboriga yuklab ko'radi (tovarga biriktirilmaydi).
Har xil manzil va sarlavha bilan sinaydi: qaysi biri ishlasa, avtomat shu yo'ldan yuradi.
"""
import base64, json, os, uuid, urllib.request, urllib.error

PID = "7d4a4c38-dd84-4902-b744-0488b80a4c01"
HOSTS = ["https://api-admin.billz.ai", "https://turfasovgalar.billz.ai/api"]
SKU = "EUK-32438"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def req(url, method="GET", token=None, body=None, raw=None, ctype="application/json", extra=None):
    h = {"Accept": "application/json", "Content-Type": ctype, "User-Agent": "Mozilla/5.0"}
    if token: h["Authorization"] = "Bearer " + token
    h.update(extra or {})
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method=method, headers=h, data=data), timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]
    except Exception as e:  # noqa
        return 0, str(e)[:200]


s, t = req(HOSTS[0] + "/v1/auth/login", "POST", body={"secret_token": os.environ["BILLZ_SECRET_TOKEN"].strip()},
           extra={"platform-id": PID})
tok = json.loads(t)["data"]["access_token"]
print("login:", s)

# Token ichidagi maydonlar (qiymatlar emas, faqat rol/huquqqa oid narsalar)
try:
    payload = json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "=="))
    print("token maydonlari:", sorted(payload.keys()))
    for k, v in payload.items():
        if any(w in k.lower() for w in ("role", "perm", "scope", "type", "access", "admin", "platform")):
            print(f"  {k} = {json.dumps(v, ensure_ascii=False)[:300]}")
except Exception as e:  # noqa
    print("token o'qilmadi:", e)

variants = {
    "platform-id + channel": {"platform-id": PID, "Billz-Response-Channel": "HTTP"},
    "platform-id": {"platform-id": PID},
    "hech narsa": {},
}
for host in HOSTS:
    for name, ex in variants.items():
        s, d = req(f"{host}/v2/products?search={SKU}&limit=5&page=1", token=tok, extra=ex)
        pid = None
        try: pid = next(p["id"] for p in json.loads(d)["products"] if p.get("sku") == SKU)
        except Exception: pass  # noqa
        s2, _ = req(f"{host}/v2/product/{pid}", token=tok, extra=ex) if pid else (None, "")
        b = "----r" + uuid.uuid4().hex
        body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.png\"\r\n"
                f"Content-Type: image/png\r\n\r\n").encode() + PNG + f"\r\n--{b}--\r\n".encode()
        s3, d3 = req(f"{host}/v1/upload", "POST", tok, raw=body, ctype=f"multipart/form-data; boundary={b}", extra=ex)
        print(f"\n[{host} | {name}] qidiruv {s} | tovar o'qish {s2} | rasm yuklash {s3} {d3[:120]}")
