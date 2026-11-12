#!/bin/bash
# Virtual telefonda ilovani sinash: o'rnatish, ishga tushirish, asosiy ekranlarni bosib ko'rish, skrinshotlar.
# Internet o'chiriladi: ilova o'z ichidagi baza bilan ishlaydi, haqiqiy serverga hech narsa yozilmaydi.
set -u
PKG=uz.turfa.narx
U="python3 scripts/ui.py"
FAIL=0
step() { echo "::group::$1"; }
end() { echo "::endgroup::"; }
check() { "$@" || { echo "::error::QADAM XATO: $*"; FAIL=1; }; }

adb wait-for-device
# Tizim to'liq yuklanguncha kutish (aks holda "System UI isn't responding" oynasi chiqadi)
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 25
dismiss() { for i in 1 2 3; do $U tap "Wait" >/dev/null 2>&1 || break; sleep 2; done; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1

# Sinov uchun kirish kodi (internet yo'q — server tekshirmaydi), egasi huquqi bilan
NOW=$(date +%s000)
cat > /tmp/xodim.xml <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="code">12345678</string>
    <string name="name">Sinov</string>
    <boolean name="owner" value="true" />
    <long name="ok" value="$NOW" />
</map>
X
adb push /tmp/xodim.xml /data/local/tmp/xodim.xml
adb shell chmod 644 /data/local/tmp/xodim.xml
adb shell run-as $PKG mkdir -p shared_prefs
adb shell run-as $PKG cp /data/local/tmp/xodim.xml shared_prefs/xodim.xml
adb shell svc wifi disable; adb shell svc data disable
adb logcat -c

step "1. Bosh ekran"
dismiss
adb shell am start -n $PKG/.MainActivity; sleep 8
dismiss
$U shot 01-bosh-ekran
check $U has "Tez kirish"
check $U has "Qidiruv"
check $U has "Rasm bilan topish"
end

step "2. Qidiruv: vaza"
check $U tap "Kod yoki nom"
adb shell input text "shisha%svaza"; sleep 2
adb shell input keyevent 111   # klaviaturani yopish
$U shot 02-qidiruv-natija
check $U has "Boshqa amallar"
end

step "3. Tovar amallari (⋯)"
check $U tap "Boshqa amallar"
$U shot 03-tovar-amallari
check $U has "📍 Joyini"
adb shell input keyevent 4; sleep 1
end

step "4. Kassaga qo'shish"
check $U tap "＋ Kassaga"
$U shot 04-kassaga-qoshildi
check $U has "🧾 Kassa:"
end

step "5. Kassa ekrani"
check $U tap "🧾 Kassa:"
$U shot 05-kassa
check $U has "Billz'ga qoralama"
check $U tap "Qidiruv"
end

step "6. Kategoriya chipi"
check $U tap "🏺 Vazalar"
$U shot 06-kategoriya-chip
check $U has "🏺 Vazalar"
end

step "7. Kategoriya menyusi (pastki menyu)"
check $U tap "Kategoriya"
$U shot 07-kategoriya-menyu
adb shell input keyevent 4; sleep 1
end

step "8. Polka menyusi"
check $U tap "Polka"
$U shot 08-polka-menyu
check $U has "📍 Polkaga tovar joylash"
check $U tap "📍 Polkaga tovar joylash"
check $U tap "Masalan: 7-2"
adb shell input text "7-2"; sleep 1
check $U tap "OK"
$U shot 09-polkaga-joylash
check $U has "📍 7-polka, 2-etaj"
adb shell input text "vaza"; sleep 2
adb shell input keyevent 111
$U shot 10-joylash-qidiruv
check $U has "ga qo'yish"
end

step "9. Joylashni tugatish"
check $U tap "📍 7-polka, 2-etaj ga joylash"
$U shot 11-joylash-menyu
check $U tap "✅ Tugatish"
end

step "10. Yana menyusi"
check $U tap "Yana"
$U shot 12-yana-menyu
check $U has "⭐ Sevimlilar"
adb shell input keyevent 4; sleep 1
end

step "11. Sevimlilar va rasmi yo'qlar"
check $U tap "Qidiruv"
adb shell input swipe 540 1800 540 500 300; sleep 1   # pastki plitkalar ekrandan pastda
adb shell input swipe 540 1800 540 500 300; sleep 1
$U shot 13a-bosh-ekran-past
check $U tap "Rasmi yo'qlar"
$U shot 13-rasmi-yoqlar
check $U has "🚫 Rasmi yo'q"
check $U tap "Qidiruv"
$U shot 14-orqaga-bosh-ekran
check $U has "Tez kirish"
end

step "11b. Narx bo'yicha va yangi kelganlar"
check $U tap "Qidiruv"
$U shot 17-bosh-ekran-yangi
check $U tap "Narx bo'yicha"
$U shot 18-narx-menyu
check $U tap "💰 100–200 ming"
$U shot 19-narx-natija
check $U has "💰 100–200 ming"
check $U tap "Qidiruv"
check $U tap "Yangi kelgan"
$U shot 20-yangi-kelgan
check $U tap "Qidiruv"
check $U tap "Kod yoki nom"
adb shell input text "tugagan"; sleep 1
adb shell input keyevent 111
end

step "12. Oddiy xodim: kategoriya/polka o'zgartirish yashirin"
sed -i 's/<boolean name="owner" value="true" \/>/<boolean name="owner" value="false" \/>/' /tmp/xodim.xml
adb shell am force-stop $PKG
adb push /tmp/xodim.xml /data/local/tmp/xodim.xml; adb shell chmod 644 /data/local/tmp/xodim.xml
adb shell run-as $PKG cp /data/local/tmp/xodim.xml shared_prefs/xodim.xml
adb shell am start -n $PKG/.MainActivity; sleep 6; dismiss
check $U tap "Kod yoki nom"
adb shell input text "vaza"; sleep 2; adb shell input keyevent 111
check $U tap "Boshqa amallar"
$U shot 15-xodim-tovar-amallari
check $U has "📸 Rasmini"
if $U has "🗂 Kategoriyasini" >/dev/null 2>&1; then echo "::error::Xodimda kategoriya o'zgartirish ko'rinyapti"; FAIL=1; fi
if $U has "📍 Joyini" >/dev/null 2>&1; then echo "::error::Xodimda polka o'zgartirish ko'rinyapti"; FAIL=1; fi
adb shell input keyevent 4; sleep 1
check $U tap "Polka"
$U shot 16-xodim-polka-menyu
if $U has "📍 Polkaga tovar joylash" >/dev/null 2>&1; then echo "::error::Xodimda polkaga joylash ko'rinyapti"; FAIL=1; fi
adb shell input keyevent 4; sleep 1
end

step "13. Ilova yiqilmadimi (crash)"
adb logcat -d > ui-shots/logcat.txt
if grep -E "FATAL EXCEPTION|AndroidRuntime: (java|kotlin)" ui-shots/logcat.txt; then
  echo "::error::Ilova yiqildi (crash) — logcat.txt ga qarang"; FAIL=1
else
  echo "✅ Crash yo'q"
fi
adb shell pidof $PKG >/dev/null && echo "✅ Ilova ishlab turibdi" || { echo "::error::Ilova yopilib qolgan"; FAIL=1; }
end
exit $FAIL
