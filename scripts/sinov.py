"""Rasm orqali qidiruv aniqligini o'lchash.

sinov/ papkasiga do'konda (sotuvchi qanday olsa shunday) olingan rasmlar qo'yiladi.
Fayl nomi tovar KODI bilan boshlanadi:  QAI-68441.jpg, QAI-68441_2.jpg, QAI-68441 yon.jpg
(kod — birinchi "_" , bo'sh joy yoki "." gacha bo'lgan qism).

Har rasm uchun ilova qanday qidirsa, shunday qidiriladi (to'liq rasm + markazdagi 75% va 55%),
to'g'ri tovar nechanchi o'rinda chiqqani hisoblanadi: 1-o'rin, top-3, top-5.

  python3 scripts/sinov.py                       # hozirgi model, baza/vectors.bin bilan
  python3 scripts/sinov.py --model large.tflite  # boshqa model: katalog rasmlari shu model bilan qayta hisoblanadi

Natija ekranga va (GitHub'da) ish xulosasiga yoziladi.
"""
import argparse, csv, io, os, re, sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(__file__))
from embed import SIDE, fetch, read_bin  # noqa: E402

EXT = (".jpg", ".jpeg", ".png", ".webp", ".heic")
CROPS = (1.0, 0.75, 0.55)   # Visual.embedQuery bilan bir xil


def embedder(path):
    import mediapipe as mp
    from mediapipe.tasks import python as mpt
    from mediapipe.tasks.python import vision
    e = vision.ImageEmbedder.create_from_options(vision.ImageEmbedderOptions(
        base_options=mpt.BaseOptions(model_asset_path=path), l2_normalize=True, quantize=False))

    def run(arr):
        v = np.array(e.embed(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(arr)))
                     .embeddings[0].embedding, dtype=np.float32)
        return v / (np.linalg.norm(v) or 1.0)
    return run


try:   # iPhone rasmlari (.heic)
    import pillow_heif
    pillow_heif.register_heif_opener()
except Exception:
    pass


def load_img(path):
    img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    img.thumbnail((SIDE, SIDE))
    return np.asarray(img, dtype=np.uint8)


def crop(arr, f):
    if f >= 1.0:
        return arr
    h, w = arr.shape[:2]
    ch, cw = max(1, round(h * f)), max(1, round(w * f))
    y, x = (h - ch) // 2, (w - cw) // 2
    return arr[y:y + ch, x:x + cw]


def catalog(model, emb):
    """-> (kodlar ro'yxati, vektorlar matritsasi). Har vektor qaysi tovar kodiga tegishli."""
    if not model:
        d, ent = read_bin("baza/vectors.bin")
        codes = [k.split("|", 1)[0].strip() for k, _, _ in ent]
        V = np.array([np.frombuffer(v, np.int8) for _, _, v in ent], np.float32) / 127
        return codes, V
    rows = list(csv.reader(open("baza/products.csv", encoding="utf-8-sig"), delimiter=";"))
    head = [h.strip().lower() for h in rows[0]]
    iK, iR = head.index("kod"), head.index("rasm")
    iX = head.index("rasmlar") if "rasmlar" in head else -1
    items = []
    for r in rows[1:]:
        urls = [r[iR].strip()] + (r[iX].split() if 0 <= iX < len(r) else [])
        for u in dict.fromkeys(u for u in urls if u.startswith("http")):
            items.append((r[iK].strip(), u))
    print(f"Katalog rasmlari yangi model bilan hisoblanmoqda: {len(items)} ta…")
    codes, vecs = [], []
    with ThreadPoolExecutor(16) as pool:
        for (code, _), (_, arr) in zip(items, pool.map(lambda it: fetch(it[1]), items)):
            if arr is not None:
                codes.append(code); vecs.append(emb(arr))
    return codes, np.array(vecs, np.float32)


def rank_of(code, qs, codes, V, use_crops):
    S = (V @ np.array(qs if use_crops else qs[:1]).T).max(axis=1)   # har katalog rasmi: eng yaxshi bo'lak
    best = {}
    for c, s in zip(codes, S):
        if s > best.get(c, -9):
            best[c] = s
    order = sorted(best, key=best.get, reverse=True)
    return (order.index(code) + 1 if code in best else None), order[:5]


def norm(c):
    return re.sub(r"[\s\-]", "", c).lower()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="", help="boshqa .tflite model (bo'sh — hozirgisi, vectors.bin)")
    ap.add_argument("--current", default=os.environ.get("EMBED_MODEL", "embedder.tflite"), help="hozirgi model fayli")
    ap.add_argument("--dir", default="sinov")
    ap.add_argument("--name", default="")
    a = ap.parse_args()

    files = sorted(f for f in os.listdir(a.dir) if f.lower().endswith(EXT)) if os.path.isdir(a.dir) else []
    if not files:
        print(f"{a.dir}/ papkasida hali rasm yo'q"); return
    emb = embedder(a.model or a.current)
    codes, V = catalog(a.model, emb)
    by_norm = {norm(c): c for c in codes}

    res = {True: [], False: []}
    fails, missing = [], []
    for f in files:
        raw = re.split(r"[_ .]", f, 1)[0]
        code = by_norm.get(norm(raw))
        if code is None:
            missing.append(f); continue
        arr = load_img(os.path.join(a.dir, f))
        qs = [emb(crop(arr, c)) for c in CROPS]
        for use in (False, True):
            r, top = rank_of(code, qs, codes, V, use)
            res[use].append(r)
            if use and (r is None or r > 1):
                fails.append((f, r, top))

    def pct(lst, k):
        return 100.0 * sum(1 for r in lst if r is not None and r <= k) / max(1, len(lst))

    title = a.name or os.path.basename(a.model or a.current)
    lines = [f"### {title}", f"Sinov rasmlari: {len(res[True])} ta" + (f" (kodi bazada topilmadi: {len(missing)})" if missing else ""), "",
             "| Usul | 1-o'rin | Top-3 | Top-5 |", "|---|---|---|---|"]
    for use, lbl in ((False, "Faqat to'liq rasm (eski)"), (True, "To'liq + markaz bo'laklari (yangi)")):
        lines.append(f"| {lbl} | {pct(res[use], 1):.0f}% | {pct(res[use], 3):.0f}% | {pct(res[use], 5):.0f}% |")
    if fails:
        lines += ["", "<details><summary>1-o'rinda chiqmaganlar</summary>", ""]
        for f, r, top in fails:
            lines.append(f"- `{f}` — {'topilmadi' if r is None else f'{r}-o‘rin'}; 1-o'rinda: {top[0]}")
        lines += ["", "</details>"]
    if missing:
        lines += ["", "Kodi bazada yo'q fayllar: " + ", ".join(f"`{m}`" for m in missing)]
    out = "\n".join(lines) + "\n"
    print(out)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8").write(out + "\n")


if __name__ == "__main__":
    main()
