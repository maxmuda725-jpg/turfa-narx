"""Rasm qidiruv modellarini solishtirish (bir martalik sinov, GitHub Actions'da ishlaydi).

Test: ikkinchi rasmi bor har bir tovar. So'rov = qo'shimcha rasm, baza = hamma tovarlarning asosiy rasmi.
To'g'ri tovar 1-o'rinda / 5 talik ichida chiqdimi — har model uchun hisoblanadi.
Ilovadagidek: so'rovdan 3 ta qirqim (1.0 / 0.75 / 0.55), eng yaqini olinadi.
"""
import csv, io, os, sys, time, urllib.request
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




def diag():
    """Xatolarni ko'rish: har xato uchun [so'rov | to'g'ri tovar | 1-o'rindagi] rasmlar varag'i + matn."""
    import os, json
    from PIL import ImageDraw
    gallery, queries = load()
    rows = list(csv.reader(open("baza/products.csv", encoding="utf-8-sig"), delimiter=";"))
    h = rows[0]; iN, iK = h.index("nomi"), h.index("kod")
    iC = h.index("kategoriya") if "kategoriya" in h else None
    name = {(r[iK].strip() or r[iN].strip()): (r[iN].strip(), r[iC] if iC is not None else "") for r in rows[1:]}
    with ThreadPoolExecutor(16) as ex:
        gimgs = list(ex.map(fetch, [u for _, u in gallery]))
        qimgs = list(ex.map(fetch, [u for _, u in queries]))
    g = [(k, im) for (k, _), im in zip(gallery, gimgs) if im is not None]
    gk = [k for k, _ in g]; gidx = {k: i for i, k in enumerate(gk)}
    q = [(k, im, u) for (k, u), im in zip(queries, qimgs) if im is not None and k in gidx]
    emb = mediapipe_embedder("large.tflite")
    G = np.stack([emb(im) for _, im in g])
    os.makedirs("diag", exist_ok=True)
    out, tiles = [], []
    for k, im, u in q:
        qs = [emb(im)] + [emb(center(im, f)) for f in (0.75, 0.55)]
        sims = np.max(np.stack([G @ x for x in qs]), 0)
        order = list(np.argsort(-sims))
        rank = order.index(gidx[k]) + 1
        # so'rov rasmi va asosiy rasm bir-biriga qanchalik o'xshash (to'g'ridan-to'g'ri)
        if rank == 1: continue
        top = order[0]
        rec = {"rank": int(rank), "true": name.get(k, (k, ""))[0], "true_cat": name.get(k, ("", ""))[1],
               "true_sim": round(float(sims[gidx[k]]), 3), "top": name.get(gk[top], (gk[top], ""))[0],
               "top_cat": name.get(gk[top], ("", ""))[1], "top_sim": round(float(sims[top]), 3),
               "top3": [name.get(gk[i], (gk[i], ""))[0] for i in order[:3]], "query_url": u}
        out.append(rec)
        T = 220
        tile = Image.new("RGB", (T * 3, T + 46), "white")
        for j, img in enumerate([im, g[gidx[k]][1], g[top][1]]):
            c = img.copy(); c.thumbnail((T, T)); tile.paste(c, (j * T + (T - c.width) // 2, (T - c.height) // 2))
        d = ImageDraw.Draw(tile)
        d.text((4, T + 2), f"#{len(out)} rank={rank}  true={rec['true'][:34]} ({rec['true_sim']})", fill="black")
        d.text((4, T + 22), f"top1={rec['top'][:44]} ({rec['top_sim']})", fill="red")
        tiles.append(tile)
    for p in range(0, len(tiles), 8):
        part = tiles[p:p + 8]
        sheet = Image.new("RGB", (part[0].width, sum(t.height + 6 for t in part)), "#ddd")
        y = 0
        for t in part: sheet.paste(t, (0, y)); y += t.height + 6
        sheet.save(f"diag/varaq_{p // 8 + 1}.jpg", quality=80)
    json.dump(out, open("diag/xatolar.json", "w"), ensure_ascii=False, indent=1)
    print("XATOLAR", len(out))


# ---------------------------------------------------------------- fonni olib tashlash sinovi
def focus_tools():
    import mediapipe as mp
    from mediapipe.tasks.python import vision, BaseOptions
    from mediapipe.tasks.python.components.containers import keypoint as kp
    det = vision.ObjectDetector.create_from_options(vision.ObjectDetectorOptions(
        base_options=BaseOptions(model_asset_path="detector.tflite"), max_results=10, score_threshold=0.1))
    seg = vision.InteractiveSegmenter.create_from_options(vision.InteractiveSegmenterOptions(
        base_options=BaseOptions(model_asset_path="magic.tflite"), output_category_mask=True))
    ROI = vision.InteractiveSegmenterRegionOfInterest

    def mpimg(img): return mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(np.asarray(img, dtype=np.uint8)))

    def crop(img):
        w, h = img.size
        best, bs = None, 0
        for d in det.detect(mpimg(img)).detections:
            b = d.bounding_box
            a = b.width * b.height / (w * h)
            if a < 0.04: continue
            cx, cy = (b.origin_x + b.width / 2) / w, (b.origin_y + b.height / 2) / h
            s = d.categories[0].score * (1 - min(1, ((cx - .5) ** 2 + (cy - .5) ** 2) ** .5 * 1.4)) * (a ** .3)
            if s > bs: best, bs = b, s
        if best is None: return img
        px, py = best.width * .08, best.height * .08
        return img.crop((max(0, best.origin_x - px), max(0, best.origin_y - py),
                         min(w, best.origin_x + best.width + px), min(h, best.origin_y + best.height + py)))

    def cut(img):
        roi = ROI(format=ROI.Format.KEYPOINT, keypoint=kp.NormalizedKeypoint(0.5, 0.5))
        m = seg.segment(mpimg(img), roi).category_mask.numpy_view()
        fg = (m > 0) if m.dtype != np.float32 else (m > 0.1)
        if fg.mean() < 0.03 or fg.mean() > 0.97:
            fg = ~fg if fg.mean() > 0.97 else fg
            if fg.mean() < 0.03: return img
        a = np.asarray(img).copy(); a[~fg] = 255
        ys, xs = np.where(fg)
        out = Image.fromarray(a).crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
        return out
    return crop, cut


def evaluate_multi(name, emb, gviews, qviews, gkeys, qkeys):
    """gviews/qviews: har rasmning bir nechta ko'rinishi; o'xshashlik = eng yaqin juftlik."""
    Gs = [np.stack([emb(v[j]) for v in gviews]) for j in range(len(gviews[0]))]
    top1 = top5 = 0
    for k, vs in zip(qkeys, qviews):
        qs = []
        for v in vs: qs += [emb(v)] + [emb(center(v, f)) for f in (0.75, 0.55)]
        sims = np.max(np.stack([G @ q for G in Gs for q in qs]), 0)
        seen, ranked = set(), []
        for i in np.argsort(-sims):
            if gkeys[i] in seen: continue
            seen.add(gkeys[i]); ranked.append(gkeys[i])
            if len(ranked) == 5: break
        top1 += ranked[0] == k; top5 += k in ranked
    n = len(qkeys)
    line = f"{name}: 1-o'rinda {top1}/{n} ({100*top1/n:.0f}%), 5 talik ichida {top5}/{n} ({100*top5/n:.0f}%)"
    print("NATIJA", line, flush=True); return line


def fon():
    gallery, queries = load()
    with ThreadPoolExecutor(16) as ex:
        gimgs = list(ex.map(fetch, [u for _, u in gallery]))
        qimgs = list(ex.map(fetch, [u for _, u in queries]))
    g = [(k, im) for (k, _), im in zip(gallery, gimgs) if im is not None]
    gk = [k for k, _ in g]; gs = set(gk)
    q = [(k, im) for (k, _), im in zip(queries, qimgs) if im is not None and k in gs]
    crop, cut = focus_tools()
    def safe(f, im):
        try: return f(im)
        except Exception: return im
    G = [im for _, im in g]; Q = [im for _, im in q]; qk = [k for k, _ in q]
    Gc = [safe(crop, im) for im in G]; Qc = [safe(crop, im) for im in Q]
    Gs = [safe(cut, im) for im in G]; Qs = [safe(cut, im) for im in Q]
    os.makedirs("diag", exist_ok=True)
    for i in range(min(12, len(Q))):
        sheet = Image.new("RGB", (660, 220), "white")
        for j, im in enumerate([Q[i], Qc[i], Qs[i]]):
            c = im.copy(); c.thumbnail((220, 220)); sheet.paste(c, (j * 220, 0))
        sheet.save(f"diag/fon_{i}.jpg", quality=75)
    emb = mediapipe_embedder("large.tflite")
    res = [
        evaluate_multi("Hozirgi (butun rasm)", emb, [[x] for x in G], [[x] for x in Q], gk, qk),
        evaluate_multi("Tovarni qirqib olish", emb, [[x] for x in Gc], [[x] for x in Qc], gk, qk),
        evaluate_multi("Fonni o'chirish", emb, [[x] for x in Gs], [[x] for x in Qs], gk, qk),
        evaluate_multi("Butun + qirqim birga", emb, [[a, b] for a, b in zip(G, Gc)], [[a, b] for a, b in zip(Q, Qc)], gk, qk),
        evaluate_multi("Butun + fonsiz birga", emb, [[a, b] for a, b in zip(G, Gs)], [[a, b] for a, b in zip(Q, Qs)], gk, qk),
        evaluate_multi("Uchalasi birga", emb, [[a, b, c] for a, b, c in zip(G, Gc, Gs)], [[a, b, c] for a, b, c in zip(Q, Qc, Qs)], gk, qk),
    ]
    print("\n".join(["", "=== XULOSA ==="] + res))


if __name__ == "__main__":
    fon() if "--fon" in sys.argv else diag() if "--diag" in sys.argv else main()
