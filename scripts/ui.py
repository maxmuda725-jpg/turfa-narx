"""Virtual telefonda ilovani bosib ko'rish uchun yordamchi (adb + uiautomator).

  python3 scripts/ui.py shot NOM            — skrinshot: ui-shots/NOM.png
  python3 scripts/ui.py tap "matn"          — matni yoki tavsifi (content-desc) shu bilan boshlangan elementni bosadi
  python3 scripts/ui.py has "matn"          — ekranda bormi (yo'q bo'lsa xato bilan chiqadi)
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

OUT = "ui-shots"


def sh(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=False, timeout=60).stdout


def dump():
    for _ in range(3):
        sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = sh("shell", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
        if "<hierarchy" in x:
            return ET.fromstring(x[x.index("<hierarchy"):])
        time.sleep(1)
    raise SystemExit("uiautomator dump ishlamadi")


def find(root, want):
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if t.startswith(want) or d.startswith(want) or (want in t and len(want) > 3):
            yield n


def center(n):
    a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
    return (a + c) // 2, (b + d) // 2


def main():
    cmd, arg = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""
    os.makedirs(OUT, exist_ok=True)
    if cmd == "shot":
        time.sleep(1.5)
        open(f"{OUT}/{arg}.png", "wb").write(sh("exec-out", "screencap", "-p"))
        open(f"{OUT}/{arg}.txt", "w").write("\n".join(
            f"{n.get('text') or ''} | {n.get('content-desc') or ''}".strip(" |") for n in dump().iter("node")
            if (n.get("text") or n.get("content-desc"))))
        print("📸", arg)
    elif cmd in ("tap", "has"):
        hits = list(find(dump(), arg))
        if not hits:
            raise SystemExit(f"❌ topilmadi: {arg}")
        if cmd == "tap":
            x, y = center(hits[0])
            sh("shell", "input", "tap", str(x), str(y))
            print("👆", arg, x, y)
            time.sleep(1.5)
        else:
            print("✅ bor:", arg)


if __name__ == "__main__":
    main()
