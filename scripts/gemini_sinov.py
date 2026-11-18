"""Gemini "hakam" sinovi: rasm qidiruvi + Gemini qayta tartiblash qanchalik aniq?

Xuddi model_tanlov.py dagi test: so'rov = tovarning qo'shimcha rasmi, baza = hamma tovarlarning asosiy rasmi.
1) Ilovadagidek MobileNetV3-large (butun rasm + 0.75/0.55 qirqimlar) bilan eng yaqin TOP_K ta tovar.
2) So'rov rasmi + shu TOP_K ta nomzod rasmi Gemini'ga: "qaysi biri aynan shu tovar?"
Natija: oldin 1-o'rinda nechta, Gemini'dan keyin nechta to'g'ri.

Kerak: GEMINI_API_KEY (GitHub secret). Ixtiyoriy: TOP_K (15), LIMIT (89 — so'rovlar soni), GEMINI_MODELS.
"""
import base64, csv, io, json, os, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from model_tanlov import load, fetch, center, mediapipe_embedder  # noqa: E402

KEY = os.environ.get("GEMINI_API_KEY", "").strip()
TOP_K = int(os.environ.get("TOP_K", "15"))
LIMIT = int(os.environ.get("LIMIT", "200"))
MODELS = [m.strip() for m in os.environ.get(
    "GEMINI_MODELS", "gemini-flash-latest,gemini-3.6-flash,gemini-3.5-flash-lite,gemini-3.1-flash-lite").split(",") if m.strip()]
MIN_CONF = int(os.environ.get("MIN_CONF", "60"))
PAUSE = float(os.environ.get("PAUSE", "4.5"))   # bepul limit: daqiqasiga ~15 so'rov

PROMPT = """Birinchi rasm — do'konda olingan SO'ROV rasmi (bitta tovar).
Keyingi rasmlar — do'kon katalogidagi NOMZOD tovarlar, har birining oldida raqami yozilgan (1, 2, 3 ...).
Vazifa: so'rov rasmidagi tovar AYNAN qaysi nomzod ekanini top.
- Shakl, rang, naqsh, yozuv, material, detallarni solishtir. Bir seriyadagi o'xshash tovarlarni farqla.
- Fon, yorug'lik, burchak, qadoq farqiga e'tibor berma.
- Har doim eng to'g'ri keladigan BITTA nomzodni tanla (0 emas). Ishonchingni halol baholа:
  aynan shu tovar bo'lsa 90-100, o'xshash lekin aniq emas bo'lsa 40-70, umuman o'xshamasa 0-30.
Faqat JSON qaytar: {"tanlov": <raqam>, "ishonch": <0-100>, "tavsif": "<tovarning qisqa o'zbekcha tavsifi>"}"""


def jpeg_b64(img, side=448):
    im = img.copy(); im.thumbnail((side, side))
    b = io.BytesIO(); im.save(b, "JPEG", quality=82)
    return base64.b64encode(b.getvalue()).decode()


_model_ok = None


def gemini(query_img, cand_imgs):
    """-> (tanlov 0..n, ishonch, model) ; xato bo'lsa Exception."""
    global _model_ok
    parts = [{"text": PROMPT}, {"text": "SO'ROV rasmi:"},
             {"inline_data": {"mime_type": "image/jpeg", "data": jpeg_b64(query_img, 640)}}]
    for i, im in enumerate(cand_imgs, 1):
        parts += [{"text": f"Nomzod {i}:"}, {"inline_data": {"mime_type": "image/jpeg", "data": jpeg_b64(im)}}]
    body = json.dumps({"contents": [{"parts": parts}],
                       "generationConfig": {"temperature": 0, "response_mime_type": "application/json"}}).encode()
    order = ([_model_ok] if _model_ok else []) + [m for m in MODELS if m != _model_ok]
    last = "javob yo'q"
    for m in order:
        for attempt in range(3):
            req = urllib.request.Request(
                f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent",
                data=body, method="POST", headers={"Content-Type": "application/json", "x-goog-api-key": KEY})
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    t = json.loads(r.read())
                txt = t["candidates"][0]["content"]["parts"][0]["text"].strip().strip("`")
                if txt.startswith("json"): txt = txt[4:]
                j = json.loads(txt)
                _model_ok = m
                return int(j.get("tanlov") or 0), int(j.get("ishonch") or 0), m
            except urllib.error.HTTPError as e:
                msg = e.read()[:200].decode(errors="ignore")
                last = f"{m}: HTTP {e.code} {msg}"
                if e.code in (400, 403) and "key" in msg.lower():
                    raise SystemExit("GEMINI_API_KEY noto'g'ri yoki ruxsat yo'q: " + msg)
                if e.code == 429 and attempt < 2:
                    time.sleep(30); continue
                break   # 404 / 429 tugadi / boshqa -> keyingi model
            except Exception as e:
                last = f"{m}: {type(e).__name__} {e}"
                if attempt < 2: time.sleep(5); continue
                break
    raise RuntimeError(last)


def main():
    if not KEY:
        print("NATIJA GEMINI_API_KEY qo'shilmagan: GitHub -> Settings -> Secrets and variables -> Actions -> New secret")
        return
    gallery, queries = load()
    with ThreadPoolExecutor(16) as ex:
        gimgs = list(ex.map(fetch, [u for _, u in gallery]))
        qimgs = list(ex.map(fetch, [u for _, u in queries]))
    g = [(k, im) for (k, _), im in zip(gallery, gimgs) if im is not None]
    gk = [k for k, _ in g]; gset = set(gk)
    q = [(k, im) for (k, _), im in zip(queries, qimgs) if im is not None and k in gset][:LIMIT]
    print(f"Baza {len(g)} rasm, test {len(q)} so'rov, TOP_K={TOP_K}", flush=True)

    emb = mediapipe_embedder("large.tflite")
    G = np.stack([emb(im) for _, im in g])
    first = {}
    for i, k in enumerate(gk): first.setdefault(k, i)

    base1 = inK = gem1 = gem_none = errs = 0
    conf_ok, conf_bad = [], []
    for n, (k, im) in enumerate(q, 1):
        qs = [emb(im)] + [emb(center(im, f)) for f in (0.75, 0.55)]
        sims = np.max(np.stack([G @ v for v in qs]), 0)
        seen, ranked = set(), []
        for i in np.argsort(-sims):
            if gk[i] in seen: continue
            seen.add(gk[i]); ranked.append(gk[i])
            if len(ranked) == TOP_K: break
        base1 += ranked[0] == k
        inK += k in ranked
        try:
            pick, conf, model = gemini(im, [g[first[c]][1] for c in ranked])
        except RuntimeError as e:
            errs += 1; print(f"  {n}: xato {e}", flush=True)
            final = ranked[0]; pick = -1; conf = 0
        else:
            gpick = ranked[pick - 1] if 1 <= pick <= len(ranked) else None
            if gpick is None: gem_none += 1
            (conf_ok if gpick == k else conf_bad).append(conf)
            final = gpick if (gpick is not None and conf >= MIN_CONF) else ranked[0]
        gem1 += final == k
        print(f"  {n}/{len(q)} {k}: oldin {'✓' if ranked[0]==k else '✗'}, top{TOP_K} {'bor' if k in ranked else 'YO`Q'}, "
              f"gemini {pick} ({conf}%) {'✓' if final==k else '✗'}", flush=True)
        time.sleep(PAUSE)

    N = len(q)
    p = lambda a: f"{a}/{N} ({100*a/N:.0f}%)"
    lines = [
        f"Hozirgi ilova (1-o'rinda): {p(base1)}",
        f"To'g'ri tovar {TOP_K} talik ichida: {p(inK)}  <- Gemini uchun yuqori chegara",
        f"Ilova + Gemini birga, ishonch>={MIN_CONF}% bo'lsa (1-o'rinda): {p(gem1)}",
        f"Gemini 'hech biri emas' dedi: {gem_none}, xatolar: {errs}, model: {_model_ok}",
    ]
    if conf_ok: lines.append(f"O'rtacha ishonch: to'g'rilarda {np.mean(conf_ok):.0f}%, noto'g'rilarda {np.mean(conf_bad) if conf_bad else 0:.0f}%")
    print("\n=== XULOSA ===")
    for l in lines: print("NATIJA", l)
    s = os.environ.get("GITHUB_STEP_SUMMARY")
    if s: open(s, "a").write("### Gemini hakam sinovi\n\n" + "\n".join("- " + l for l in lines) + "\n")


if __name__ == "__main__":
    main()
