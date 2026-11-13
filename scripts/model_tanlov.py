"""Rasm qidiruv modellarini solishtirish (bir martalik sinov, GitHub Actions'da ishlaydi).

Test: ikkinchi rasmi bor har bir tovar. So'rov = qo'shimcha rasm, baza = hamma tovarlarning asosiy rasmi.
To'g'ri tovar 1-o'rinda / 5 talik ichida chiqdimi — har model uchun hisoblanadi.
Ilovadagidek: so'rovdan 3 ta qirqim (1.0 / 0.75 / 0.55), eng yaqini olinadi.
"""
import csv, io, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from PIL import Image, ImageOps

SIDE = 512


def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "turfa-narx"})
        with urllib.request.urlopen(req, timeout=30) as r:
            img = ImageOps.exif_transpose(Image.open(io.BytesIO(r.read()))).convert("RGB")
        img.thumbnail((SIDE, SIDE))
        return img
    except Exception:
        return None


def center(img, f):
    w, h = img.size
    cw, ch = int(w * f), int(h * f)
    l, t = (w - cw) // 2, (h - ch) // 2
    return img.crop((l, t, l + cw, t + ch))


def load():
    rows = list(csv.reader(open("baza/products.csv", encoding="utf-8-sig"), delimiter=";"))
    h = rows[0]; iN, iK, iR, iX = h.index("nomi"), h.index("kod"), h.index("rasm"), h.index("rasmlar")
    gallery, queries = [], []
    for r in rows[1:]:
        main = r[iR].strip()
        if not main.startswith("http"): continue
        key = r[iK].strip() or r[iN].strip()
        gallery.append((key, main))
        for u in r[iX].split(","):
            u = u.strip()
            if u.startswith("http") and u != main:
                queries.append((key, u)); break
    return gallery, queries


def mediapipe_embedder(path):
    import mediapipe as mp
    from mediapipe.tasks.python import vision, BaseOptions
    e = vision.ImageEmbedder.create_from_options(vision.ImageEmbedderOptions(
        base_options=BaseOptions(model_asset_path=path), l2_normalize=True, quantize=False))
    def f(img):
        m = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(np.asarray(img, dtype=np.uint8)))
        return np.array(e.embed(m).embeddings[0].embedding, dtype=np.float32)
    return f


def dino_embedder(name):
    import torch
    from transformers import AutoImageProcessor, AutoModel
    proc = AutoImageProcessor.from_pretrained(name)
    model = AutoModel.from_pretrained(name).eval()
    torch.set_num_threads(4)
    def f(img):
        with torch.no_grad():
            out = model(**proc(images=img, return_tensors="pt"))
        cls = out.last_hidden_state[:, 0]
        patch = out.last_hidden_state[:, 1:].mean(1)
        v = torch.cat([cls, patch], 1)[0].numpy().astype(np.float32)
        return v / (np.linalg.norm(v) + 1e-9)
    return f


def evaluate(name, emb, gimgs, qimgs, gkeys, qkeys):
    t = time.time()
    G = np.stack([emb(im) for im in gimgs])
    per = (time.time() - t) / len(gimgs)
    top1 = top5 = 0
    for k, im in zip(qkeys, qimgs):
        qs = [emb(im)] + [emb(center(im, f)) for f in (0.75, 0.55)]
        sims = np.max(np.stack([G @ q for q in qs]), 0)
        order = np.argsort(-sims)
        # bir tovarning takror kalitlari bo'lmasin
        seen, ranked = set(), []
        for i in order:
            if gkeys[i] in seen: continue
            seen.add(gkeys[i]); ranked.append(gkeys[i])
            if len(ranked) == 5: break
        top1 += ranked[0] == k
        top5 += k in ranked
    n = len(qkeys)
    line = f"{name}: 1-o'rinda {top1}/{n} ({100*top1/n:.0f}%), 5 talik ichida {top5}/{n} ({100*top5/n:.0f}%), bitta rasm {per*1000:.0f} ms (server CPU)"
    print("NATIJA", line, flush=True)
    return line


def main():
    gallery, queries = load()
    print(f"Baza: {len(gallery)} rasm, test: {len(queries)} ta so'rov", flush=True)
    with ThreadPoolExecutor(16) as ex:
        gimgs = list(ex.map(fetch, [u for _, u in gallery]))
        qimgs = list(ex.map(fetch, [u for _, u in queries]))
    g = [(k, im) for (k, _), im in zip(gallery, gimgs) if im is not None]
    q = [(k, im) for (k, _), im in zip(queries, qimgs) if im is not None]
    gk = [k for k, _ in g]; gk_set = set(gk)
    q = [(k, im) for k, im in q if k in gk_set]
    print(f"Yuklandi: baza {len(g)}, test {len(q)}", flush=True)
    gi = [im for _, im in g]; qi = [im for _, im in q]; qk = [k for k, _ in q]
    res = []
    for name, mk in [
        ("MobileNetV3-small (eski)", lambda: mediapipe_embedder("small.tflite")),
        ("MobileNetV3-large (hozirgi)", lambda: mediapipe_embedder("large.tflite")),
        ("DINOv2-small", lambda: dino_embedder("facebook/dinov2-small")),
        ("DINOv2-base", lambda: dino_embedder("facebook/dinov2-base")),
    ]:
        try: res.append(evaluate(name, mk(), gi, qi, gk, qk))
        except Exception as e: print("NATIJA", name, "xato:", e, flush=True)
    print("\n".join(["", "=== XULOSA ==="] + res))


if __name__ == "__main__":
    main()
