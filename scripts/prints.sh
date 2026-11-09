#!/usr/bin/env bash
# Tira print de cada tela do app no emulador do CI (build de PRÉVIA, sem key).
# uso: bash scripts/prints.sh <apk> <pasta_saida>
set -u
APK="$1"; OUT="$2"
mkdir -p "$OUT/cru"
PKG=com.whatsapp.web
ACT=com.nocry.app.MainActivity

adb install -r -g "$APK" || { echo "falhou instalar"; exit 1; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# o emulador às vezes mostra "Pixel Launcher isn't responding" por cima de tudo: esconde esses avisos do sistema
adb shell settings put global hide_error_dialogs 1
adb shell settings put global anr_show_background 0
# notificações liberadas e sem a tela de economia de bateria no caminho
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); LARG=${W%x*}; ALT=${W#*x}
X=$((LARG/2)); Y1=$((ALT*78/100)); Y2=$((ALT*28/100))

shot() { # nome tela [aba] [demo]
  local nome=$1 tela=$2 aba=${3:-0} demo=${4:-false}
  adb shell am force-stop $PKG
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am start -n $PKG/$ACT --es tela "$tela" --ei aba "$aba" --ez demo "$demo" >/dev/null
  sleep 7
  adb exec-out screencap -p > "$OUT/cru/${nome}_1.png"
  for i in 2 3 4 5; do
    adb shell input swipe $X $Y1 $X $Y2 350
    sleep 1.6
    adb exec-out screencap -p > "$OUT/cru/${nome}_$i.png"
  done
  # uma imagem por tela, com as rolagens lado a lado
  montage "$OUT"/cru/${nome}_[1-5].png -tile 5x1 -geometry 432x960+6+0 -background '#111111' "$OUT/${nome}.jpg" 2>/dev/null \
    || cp "$OUT/cru/${nome}_1.png" "$OUT/${nome}.png"
  echo "ok $nome"
}

shot 01_inicio home
shot 02_conexao conexao
shot 03_bypass bypass
shot 04_recursos recursos
shot 05_comp comp
shot 06_vol vol
shot 07_carga carga
shot 08_mc mc
shot 09_ajustes ajustes
shot 10_testar testar
shot 11_otm otm
shot 12_otmver otmver
shot 13_otmproteger otmproteger
shot 14_otmmais otmmais
shot 15_estudio estudio
shot 16_organizar organizar
shot 17_log log
shot 18_sobre sobre
shot 19_bot_partidas bot 0 true
shot 20_bot_busca bot 1 true
shot 21_bot_conta bot 2 true
shot 22_bot_vazio bot 0 false
rm -rf "$OUT/cru"
ls -la "$OUT"
