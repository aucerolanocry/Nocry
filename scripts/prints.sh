#!/usr/bin/env bash
# Tira print de cada tela do app no emulador do CI (build de PRÉVIA, sem key).
# uso: bash scripts/prints.sh <apk> <pasta_saida>
set -u
APK="$1"; OUT="$2"
mkdir -p "$OUT/cru"
PKG=com.whatsapp.web
ACT=com.nocry.app.MainActivity

adb install -r -g "$APK" || { echo "falhou instalar"; exit 1; }
adb logcat -G 16M >/dev/null 2>&1 || true
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

FONTE=bigs   # a fonte que o dono usa (condensada): o caso mais apertado
INICIO=""; ESTILO=""; COR=""; TEMA=""
shot() { # nome tela [aba] [demo]
  local nome=$1 tela=$2 aba=${3:-0} demo=${4:-false}
  # TELAS="01_inicio 15_estudio": tira só essas (prévia rápida)
  if [ -n "${TELAS:-}" ] && ! echo " $TELAS " | grep -q " $nome "; then return; fi
  adb shell am force-stop $PKG
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am start -n $PKG/$ACT --es tela "$tela" --ei aba "$aba" --ez demo "$demo" --es fonte "$FONTE" ${INICIO:+--es inicio $INICIO} ${ESTILO:+--es estilo $ESTILO} ${COR:+--es cor $COR} ${TEMA:+--es tema $TEMA} ${PERFIL:+--es perfil $PERFIL} >/dev/null
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

# vídeo curto de uma tela (prévia de como vai ficar): grava a tela enquanto rola pra baixo e volta
anim() { for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k $1; done; }
video() { # nome estilo
  local nome=$1 est=$2
  if [ -n "${TELAS:-}" ] && ! echo " $TELAS " | grep -q " $nome "; then return; fi
  anim 1
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/$ACT --es tela home --es fonte "$FONTE" ${est:+--es estilo $est} >/dev/null
  sleep 7
  adb shell rm -f /sdcard/v.mp4
  adb shell screenrecord --time-limit 26 --bit-rate 5000000 /sdcard/v.mp4 &
  local rec=$!
  sleep 2
  if [ "${est:0:1}" = "G" ]; then
    sleep 5; adb shell input swipe $X $Y1 $X $Y2 900; sleep 3; adb shell input swipe $X $Y2 $X $Y1 700; sleep 13
  else
    for i in 1 2 3 4; do adb shell input swipe $X $Y1 $X $Y2 1100; sleep 2; done
    for i in 1 2 3 4; do adb shell input swipe $X $Y2 $X $Y1 700; sleep 1.2; done
    sleep 3
  fi
  wait $rec 2>/dev/null
  sleep 1
  adb pull /sdcard/v.mp4 "/tmp/v_$nome.mp4" >/dev/null 2>&1
  if [ -s "/tmp/v_$nome.mp4" ] && [ $(stat -c%s "/tmp/v_$nome.mp4") -gt 60000 ]; then
    ffmpeg -y -loglevel error -i "/tmp/v_$nome.mp4" -vf "scale=540:-2" -c:v libx264 -crf 25 -preset veryfast -pix_fmt yuv420p -an "$OUT/$nome.mp4" \
      || cp "/tmp/v_$nome.mp4" "$OUT/$nome.mp4"
    echo "ok video $nome"
  else
    # o gravador do emulador falhou: monta o vídeo com prints seguidos
    mkdir -p "/tmp/q_$nome"; local n=0
    for i in 1 2 3 4 5 6; do adb exec-out screencap -p > "/tmp/q_$nome/$(printf %03d $n).png"; n=$((n+1)); adb shell input swipe $X $Y1 $X $Y2 900; sleep 1; done
    ffmpeg -y -loglevel error -framerate 1.5 -i "/tmp/q_$nome/%03d.png" -vf "scale=540:-2" -c:v libx264 -pix_fmt yuv420p "$OUT/$nome.mp4" && echo "ok video (prints) $nome"
  fi
  anim 0
}

# 1ª passada: tela ESTREITA (320dp de largura, como celular pequeno ou com "tamanho da tela" aumentado)
adb shell wm density 540
sleep 3
adb shell am start -n $PKG/$ACT >/dev/null; sleep 12
shot 01_inicio home
shot 02_conexao conexao
shot 03_bypass bypass
shot 04_recursos recursos
shot 05_comp comp
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
shot 23_ajuda ajuda
shot 24_caderno caderno
shot 25_partidas partidas
shot 27_eventos eventos
shot 28_amigos amigos
for e in ouro sangue brasa raio toxico gelo glitch morte vazio aurora; do PERFIL=$e; shot 38_perfil_${e} rank; done; PERFIL=""
for t in t_inferno t_sangue t_tempestade t_toxico t_gelo t_vazio t_diamante t_neon; do TEMA=$t; shot 39_${t} home; done; TEMA=""
shot 40_estudio_temas estudio 0
# rank: a entrada do top 1 é animada, então tira várias fotos seguidas
if [ -z "${TELAS:-}" ] || echo " $TELAS " | grep -q " 36_rank "; then
  # entrada 6x mais lenta (só no teste) e uma foto por segundo
  adb shell am force-stop $PKG
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am start -n $PKG/$ACT --es tela rank --es fonte "$FONTE" --es lento 6 >/dev/null
  for i in $(seq -w 1 24); do adb exec-out screencap -p > "$OUT/cru/36_rank_$i.png"; sleep 0.4; done
  montage "$OUT"/cru/36_rank_*.png -tile 8x3 -geometry 432x960+6+6 -background '#111111' "$OUT/36_rank.jpg" 2>/dev/null
  echo "ok 36_rank"
fi
shot 29_atalhos_grade home 0
COR=8b5cf6; ESTILO=""; shot 40_estilo_inicio home; shot 40_estilo_estudio estudio; shot 40_estilo_logs log; shot 40_estilo_sobre sobre; shot 40_estilo_bypass bypass
COR=8b5cf6; ESTILO=""; shot 50_jeito_inicio home; shot 50_jeito_sobre sobre
for e in A B C; do ESTILO=$e; shot 5${e}_jeito_inicio home; shot 5${e}_jeito_sobre sobre; done; ESTILO=""
for e in 1 2 3; do ESTILO=$e; shot 4${e}_estilo_inicio home; shot 4${e}_estilo_estudio estudio; shot 4${e}_estilo_logs log; shot 4${e}_estilo_sobre sobre; shot 4${e}_estilo_bypass bypass; done; ESTILO=""; COR=""
INICIO=D; shot 30_inicio_d home; INICIO=""
# 2ª passada: tela LARGA (411dp), só as principais, pra conferir que nada ficou pequeno demais
shot 41_estilos estilos
adb shell wm density reset
sleep 3
shot 31_largo_inicio home
INICIO=D; shot 35_largo_inicio_d home; INICIO=""
shot 32_largo_conexao conexao
shot 33_largo_bypass bypass
shot 34_largo_bot bot 2 true
# ideias novas de organização e botões (só na prévia): E lista organizada, F blocos, G abas
for e in E F G; do ESTILO=$e; shot 7${e}_inicio home; shot 7${e}_bypass bypass; shot 7${e}_estudio estudio; shot 7${e}_conexao conexao; done
ESTILO=G1; shot 7G1_inicio home; ESTILO=G2; shot 7G2_inicio home; ESTILO=""
shot 70_atual_inicio home
video 8_video_atual ""; video 8E_video E; video 8F_video F; video 8G_video Gv
# estilos de botão em cima do início de sempre (só prévia): H claro, I cores, J gamer, K escuro limpo
for e in H I J K; do ESTILO=$e; shot 9${e}_inicio home; shot 9${e}_bypass bypass; shot 9${e}_conexao conexao; done; ESTILO=""
video 9H_video H; video 9I_video I; video 9J_video J; video 9K_video K
rm -rf "$OUT/cru"
ls -la "$OUT"
