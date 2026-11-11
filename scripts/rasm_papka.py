"""Tovar rasmlarini bitta ZIP papka qilib beradi: har rasm tovar nomi va kodi bilan nomlanadi.

Manba: ilovadan yuklangan rasmlar (server ro'yxati). BILLZ=1 bo'lsa, Billz'dagi rasmlar ham qo'shiladi.
Natija: rasmlar-papka.zip  (ichida: <Kategoriya yo'q>/<nomi> [<kod>] 1.jpg)
"""
import csv, io, json, os, re, sys, urllib.request, zipfile
from concurrent.futures import ThreadPoolExecutor

SERVER = os.environ.get("KASSA_API", "https://do-kon-savdo-yordamchisi.lovable.app")
WITH_BILLZ = os.environ.get("BILLZ", "") in ("1", "true", "yes")
OUT = "rasmlar-papka.zip"


def get(url, timeout=60):
    r = urllib.request.Request(url, headers={"User-Agent": "turfa-narx"})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return resp.read()


def safe(s):
    s = re.sub(r'[\\/:*?"<>|\r\n\t]+', " ", s)
    return re.sub(r"\s+", " ", s).strip()[:120] or "nomsiz"


def main():
    rows = list(csv.reader(open("baza/products.csv", encoding="utf-8-sig"), delimiter=";"))
    head = [h.strip().lower() for h in rows[0]]
    iN, iK, iR = head.index("nomi"), head.index("kod"), head.index("rasm")
    iX = head.index("rasmlar") if "rasmlar" in head else -1
    name_of = {r[iK].strip(): r[iN].strip() for r in rows[1:] if len(r) > iK}

    jobs = []   # (kod, url, manba)
    try:
        items = json.loads(get(SERVER + "/api/public/rasmlar")).get("items") or []
    except Exception as e:
        sys.exit(f"Server ro'yxati olinmadi: {e}")
    for it in sorted(items, key=lambda x: x.get("at", 0)):
        jobs.append((str(it["sku"]), it["url"], "ilova"))
    print(f"Ilovadan yuklangan rasmlar: {len(items)} ta")

    if WITH_BILLZ:
        seen = {u for _, u, _ in jobs}
        n = 0
        for r in rows[1:]:
            urls = [r[iR]] if len(r) > iR else []
            if 0 <= iX < len(r):
                urls += r[iX].split()
            for u in urls:
                if u.startswith("http") and u not in seen and "digitaloceanspaces" in u:
                    jobs.append((r[iK].strip(), u, "billz")); seen.add(u); n += 1
        print(f"Billz rasmlari: {n} ta")

    if not jobs:
        print("Rasm yo'q — bo'sh papka")

    def fetch(j):
        try:
            return j, get(j[1])
        except Exception:
            return j, None

    count, missed = {}, 0
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_STORED) as z, ThreadPoolExecutor(16) as pool:
        for (kod, url, src), data in pool.map(fetch, jobs):
            if not data:
                missed += 1
                continue
            base = safe(f"{name_of.get(kod, 'Bazada yoq')} [{kod}]")
            count[base] = count.get(base, 0) + 1
            ext = url.rsplit(".", 1)[-1].split("?")[0].lower()
            ext = ext if ext in ("jpg", "jpeg", "png", "webp") else "jpg"
            folder = "Ilovadan" if src == "ilova" else "Billz"
            z.writestr(f"{folder}/{base} {count[base]}.{ext}", data)
        z.writestr("OQING.txt", "Har rasm nomi: <tovar nomi> [<kod>] <tartib raqami>.\n"
                   "Ilovadan — telefondan yuklangan rasmlar. Billz — Billz'dagi rasmlar.\n")
    print(f"Papkaga qo'shildi: {sum(count.values())} ta rasm, {len(count)} ta tovar; yuklanmadi: {missed}")
    print(f"Hajmi: {os.path.getsize(OUT) // 1024 // 1024} MB")


if __name__ == "__main__":
    main()
