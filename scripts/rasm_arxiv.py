"""Ilovadan yuklangan rasmlarni Lovable Storage'dan GitHub Releases'ga ko'chiradi (bepul, cheksiz).

Har soat (baza.yml): 1) navbatni olish  2) har rasmni "rasmlar-N" relizga yuklash
3) baza/rasmlar.csv ga yozish (nom;havola;asset_id)  4) serverga "ko'chirildi" deyish (Storage'dan o'chadi).
O'chirilgan rasmlar (x belgisi) GitHub'dan ham o'chiriladi.
Kerak: RASM_KEY (GitHub secret), GITHUB_TOKEN, GITHUB_REPOSITORY.
"""
import json, os, subprocess, sys, urllib.parse, urllib.request, urllib.error

SERVER = os.environ.get("KASSA_API", "https://do-kon-savdo-yordamchisi.lovable.app")
REPO = os.environ.get("GITHUB_REPOSITORY", "maxmuda725-jpg/turfa-narx")
GH = "https://api.github.com"
CSV = "baza/rasmlar.csv"
PER_RELEASE = 900


def http(method, url, body=None, headers=None, raw=None, timeout=60):
    h = {"User-Agent": "turfa-narx", "Accept": "application/json", **(headers or {})}
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if body is not None:
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(url, method=method, data=data, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read()
            return resp.status, (json.loads(t) if t and t[:1] in b"[{" else t)
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]


def gh(method, path, body=None, raw=None, ctype=None, base=GH):
    h = {"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"], "Accept": "application/vnd.github+json"}
    if ctype:
        h["Content-Type"] = ctype
    return http(method, base + path, body=body, headers=h, raw=raw)


def read_csv():
    rows = {}
    if os.path.exists(CSV):
        for line in open(CSV, encoding="utf-8"):
            p = line.strip().split(";")
            if len(p) >= 3 and p[0] != "nom":
                rows[p[0]] = (p[1], p[2])
    return rows


def write_csv(rows):
    with open(CSV, "w", encoding="utf-8") as f:
        f.write("nom;havola;asset_id\n")
        for n in sorted(rows):
            f.write(f"{n};{rows[n][0]};{rows[n][1]}\n")


def target_release():
    """Bo'sh joyi bor 'rasmlar-N' reliz (APK relizlariga tegmaydi, 'latest' bo'lmaydi)."""
    st, rels = gh("GET", f"/repos/{REPO}/releases?per_page=100")
    mine = sorted([r for r in rels if str(r.get("tag_name", "")).startswith("rasmlar-")],
                  key=lambda r: int(r["tag_name"].split("-")[1]))
    for r in mine:
        st, assets = gh("GET", f"/repos/{REPO}/releases/{r['id']}/assets?per_page=100&page=10")
        if len(assets) == 0:   # 900 dan kam (10-sahifa bo'sh)
            return r
    n = int(mine[-1]["tag_name"].split("-")[1]) + 1 if mine else 1
    st, r = gh("POST", f"/repos/{REPO}/releases", {
        "tag_name": f"rasmlar-{n}", "name": f"Tovar rasmlari {n}",
        "body": "Ilovadan yuklangan tovar rasmlari (avtomatik). O'chirmang.",
        "make_latest": "false", "prerelease": False})
    if st >= 300:
        sys.exit(f"Reliz yaratilmadi: {st} {r}")
    return r


def server(op, name, key):
    st, r = http("POST", f"{SERVER}/api/kassa/rasm-arxiv", {"op": op, "name": name}, {"x-rasm-key": key})
    return st == 200


def main():
    key = os.environ.get("RASM_KEY", "").strip()
    if not key:
        print("RASM_KEY qo'shilmagan — rasmlar ko'chirilmadi")
        return
    st, q = http("GET", f"{SERVER}/api/public/rasmlar?arxiv=1")
    if st != 200 or not isinstance(q, dict) or not q.get("ok"):
        print("Navbat olinmadi:", st)
        return
    # Server kalitini tekshirish: 400 = kalit to'g'ri, 401 = noto'g'ri, 503 = serverda RASM_KEY yo'q
    st, _ = http("POST", f"{SERVER}/api/kassa/rasm-arxiv", {"op": "forget", "name": "tekshiruv"}, {"x-rasm-key": key})
    print("Server kaliti:", {400: "to'g'ri ✅", 401: "NOTO'G'RI ❌", 503: "serverda RASM_KEY yo'q ❌"}.get(st, f"javob {st}"))
    # Ilova kaliti (APK ichidagi) serverda qabul qilinadimi: 400 = ha, 401 = yo'q
    import hashlib
    app_key = hashlib.sha256(("turfa-app:" + key).encode()).hexdigest()
    st, _ = http("POST", f"{SERVER}/api/kassa/rasm-belgi", {"name": "tekshiruv", "done": True}, {"x-app-key": app_key})
    print("Ilova kaliti:", {400: "to'g'ri ✅", 401: "NOTO'G'RI ❌"}.get(st, f"javob {st}"))
    rows = read_csv()
    moved = forgot = 0

    forget = q.get("forget") or []
    for name in forget:
        if name in rows:
            gh("DELETE", f"/repos/{REPO}/releases/assets/{rows[name][1]}")
            del rows[name]
    if forget:
        write_csv(rows)
        if commit():
            forgot = sum(1 for n in forget if server("forget", n, key))

    todo = q.get("archive") or []
    rel = target_release() if any(it["name"] not in rows for it in todo) else None
    for it in todo:
        name = it["name"]
        if name in rows:
            continue
        st, img = http("GET", it["url"], timeout=60)
        if st != 200 or not isinstance(img, bytes) or len(img) < 100:
            print("  yuklab bo'lmadi:", name, st)
            continue
        up = rel["upload_url"].split("{")[0] + "?" + urllib.parse.urlencode({"name": name})
        ctype = "image/jpeg" if name.endswith(".jpg") else "image/" + name.rsplit(".", 1)[1]
        st, a = gh("POST", "", raw=img, ctype=ctype, base=up)
        if st == 422:
            a = find_asset(name)       # oldingi ish uzilib qolgan: fayl allaqachon bor
            if a is None:              # reliz to'lgan
                rel = target_release()
                st, a = gh("POST", "", raw=img, ctype=ctype, base=rel["upload_url"].split("{")[0] + "?" + urllib.parse.urlencode({"name": name}))
                if st >= 300: a = None
        elif st >= 300:
            a = None
        if not a:
            print("  GitHub'ga yuklanmadi:", name, st)
            continue
        rows[name] = (a["browser_download_url"], str(a["id"]))

    # Avval GitHub'ga saqlaymiz, keyin serverga "ko'chirildi" deymiz — ish uzilsa ham rasm yo'qolmaydi
    write_csv(rows)
    if not commit():
        print("rasmlar.csv saqlanmadi — server o'zgartirilmadi")
        return
    for it in todo:
        if it["name"] in rows and server("archived", it["name"], key):
            moved += 1
    print(f"Ko'chirildi: {moved} ta, o'chirildi: {forgot} ta, GitHub'da jami: {len(rows)} ta")


def find_asset(name):
    st, rels = gh("GET", f"/repos/{REPO}/releases?per_page=100")
    for r in rels:
        if not str(r.get("tag_name", "")).startswith("rasmlar-"):
            continue
        for page in range(1, 11):
            st, assets = gh("GET", f"/repos/{REPO}/releases/{r['id']}/assets?per_page=100&page={page}")
            for x in assets:
                if x["name"] == name:
                    return x
            if len(assets) < 100:
                break
    return None


def commit():
    run = lambda *a: subprocess.run(["git", *a], capture_output=True, text=True)
    run("config", "user.name", "baza-bot"); run("config", "user.email", "baza-bot@users.noreply.github.com")
    run("add", CSV)
    if run("diff", "--cached", "--quiet").returncode == 0:
        return True
    run("commit", "-m", "Rasmlar GitHub'ga ko'chirildi")
    for _ in range(3):
        if run("push").returncode == 0:
            return True
        run("pull", "--rebase", "-q")
    return False


if __name__ == "__main__":
    main()
