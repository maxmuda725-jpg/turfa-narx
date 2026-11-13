"""baza/products.csv dagi tovar rasmlari -> baza/vectors.bin (rasm orqali qidirish uchun).

Har bir rasm MediaPipe ImageEmbedder (mobilenet_v3_small) bilan vektorga aylantiriladi.
Ilova ham AYNAN shu modelni ishlatadi, shuning uchun vektorlar solishtiriladi.
Oldingi vectors.bin dagi rasmlar qayta hisoblanmaydi (faqat yangi rasmlar).

Fayl formati (little-endian):
  "TFV1", int32 N, int32 D, keyin N marta:
  uint16 len + key (UTF-8, "kod|nomi"), uint16 len + rasm url, D bayt int8 (vektor * 127)
"""
import csv, io, os, struct, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image, ImageOps
import mediapipe as mp
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision

MODEL = os.environ.get("EMBED_MODEL", "embedder.tflite")
CSV = "baza/products.csv"
OUT = os.environ.get("EMBED_OUT", "baza/vectors.bin")   # large model: baza/vectors-l.bin
SIDE = 640      # ilovada ham shu o'lcham
CROP = os.environ.get("EMBED_CROP") == "1"   # har rasmdan yana "tovarning o'zi" qirqimi (#q) — fon kamroq ta'sir qiladi
DETECTOR = os.environ.get("EMBED_DETECTOR", "detector.tflite")
CHUNK = 64


def read_bin(path):
    """-> (dim, [(key, url, bytes)])"""
    if not os.path.exists(path):
        return 0, []
    b = open(path, "rb").read()
    if b[:4] != b"TFV1":
        return 0, []
    n, d = struct.unpack_from("<ii", b, 4)
    pos, out = 12, []
    for _ in range(n):
        (kl,) = struct.unpack_from("<H", b, pos); pos += 2
        key = b[pos:pos + kl].decode(); pos += kl
        (ul,) = struct.unpack_from("<H", b, pos); pos += 2
        url = b[pos:pos + ul].decode(); pos += ul
        out.append((key, url, b[pos:pos + d])); pos += d
    return d, out


def write_bin(dim, entries):
    buf = io.BytesIO()
    buf.write(b"TFV1"); buf.write(struct.pack("<ii", len(entries), dim))
    for key, url, vec in entries:
        k, u = key.encode()[:65000], url.encode()[:65000]
        buf.write(struct.pack("<H", len(k))); buf.write(k)
        buf.write(struct.pack("<H", len(u))); buf.write(u)
        buf.write(vec)
    return buf.getvalue()


def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "turfa-narx"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
        img.thumbnail((SIDE, SIDE))
        return url, np.ascontiguousarray(np.asarray(img, dtype=np.uint8))
    except Exception as e:
        print("  rasm yuklanmadi:", url, type(e).__name__)
        return url, None


def main_crop(arr, det):
    """Rasmdagi asosiy buyumni (markazga yaqin, katta) qirqib olish. Ilovadagi Shelf.mainCrop bilan bir xil."""
    h, w = arr.shape[:2]
    best, bs = None, 0.0
    for d in det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=arr)).detections:
        b = d.bounding_box
        a = b.width * b.height / (w * h)
        if a < 0.04: continue
        cx, cy = (b.origin_x + b.width / 2) / w, (b.origin_y + b.height / 2) / h
        s = d.categories[0].score * (1 - min(1.0, ((cx - .5) ** 2 + (cy - .5) ** 2) ** .5 * 1.4)) * (a ** .3)
        if s > bs: best, bs = b, s
    if best is None: return None
    px, py = best.width * .08, best.height * .08
    l, t = int(max(0, best.origin_x - px)), int(max(0, best.origin_y - py))
    r, bt = int(min(w, best.origin_x + best.width + px)), int(min(h, best.origin_y + best.height + py))
    if r - l < 8 or bt - t < 8: return None
    return np.ascontiguousarray(arr[t:bt, l:r])


def main():
    rows = list(csv.reader(open(CSV, encoding="utf-8-sig"), delimiter=";"))
    head = [h.strip().lower() for h in rows[0]]
    iN, iK, iR = head.index("nomi"), head.index("kod"), head.index("rasm")
    iX = head.index("rasmlar") if "rasmlar" in head else -1
    items = []
    for r in rows[1:]:
        urls = [r[iR].strip()] if len(r) > iR else []
        if 0 <= iX < len(r):
            urls += r[iX].split()
        key = f"{r[iK].strip()}|{r[iN].strip()}"
        for u in dict.fromkeys(u for u in urls if u.startswith("http")):
            items.append((key, u))

    old_dim, old = read_bin(OUT)
    known = {url: vec for _, url, vec in old}
    todo = sorted({u for _, u in items if u not in known or (CROP and u + "#q" not in known)})
    print(f"Rasmli tovar: {len({k for k, _ in items})}, rasm: {len(items)}, oldin hisoblangan: {len(known)}, yangi: {len(todo)}")

    dim = old_dim
    if todo:
        emb = vision.ImageEmbedder.create_from_options(vision.ImageEmbedderOptions(
            base_options=mpt.BaseOptions(model_asset_path=MODEL), l2_normalize=True, quantize=False))
        det = vision.ObjectDetector.create_from_options(vision.ObjectDetectorOptions(
            base_options=mpt.BaseOptions(model_asset_path=DETECTOR), max_results=10, score_threshold=0.1)) if CROP else None

        def vec(a):
            v = np.array(emb.embed(mp.Image(image_format=mp.ImageFormat.SRGB, data=a)).embeddings[0].embedding, dtype=np.float32)
            v /= (np.linalg.norm(v) or 1.0)
            return v
        done = 0
        with ThreadPoolExecutor(16) as pool:
            for i in range(0, len(todo), CHUNK):
                for url, arr in pool.map(fetch, todo[i:i + CHUNK]):
                    if arr is None:
                        continue
                    v = vec(arr)
                    if dim and dim != len(v):
                        sys.exit(f"Vektor o'lchami o'zgardi ({dim} -> {len(v)}). vectors.bin ni o'chirib qayta ishga tushiring.")
                    dim = len(v)
                    known[url] = np.clip(np.round(v * 127), -127, 127).astype(np.int8).tobytes()
                    if CROP:
                        c = main_crop(arr, det)
                        # buyum topilmasa — qirqim o'rniga butun rasm (keyingi safar qayta urinilmasin)
                        vq = vec(c) if c is not None else v
                        known[url + "#q"] = np.clip(np.round(vq * 127), -127, 127).astype(np.int8).tobytes()
                    done += 1
                print(f"  {min(i + CHUNK, len(todo))}/{len(todo)}")
        print(f"Yangi hisoblandi: {done}")

    entries = [(k, u, known[u]) for k, u in items if u in known]
    if CROP:
        entries += [(k, u + "#q", known[u + "#q"]) for k, u in items if u + "#q" in known]
    if not entries:
        sys.exit("Birorta ham vektor yo'q")
    data = write_bin(dim, entries)
    if os.path.exists(OUT) and open(OUT, "rb").read() == data:
        print("O'zgarish yo'q")
        return
    open(OUT, "wb").write(data)
    print(f"Yozildi: {len(entries)} ta vektor, {dim} o'lchamli, {len(data) // 1024} KB")


if __name__ == "__main__":
    main()
