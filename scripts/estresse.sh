#!/usr/bin/env bash
# Teste de estresse no emulador (build de PRÉVIA): troca de telas rápida, segundo plano e volta, bot em
# demonstração aberto, toques aleatórios. No fim junta crashes, travadas (ANR), avisos do StrictMode
# (trabalho pesado na thread principal), o relatório de erros do próprio app, memória e quadros travados.
# uso: bash scripts/estresse.sh <pasta_saida>
set -u
OUT="$1"
mkdir -p "$OUT"
PKG=com.whatsapp.web
ACT=com.nocry.app.MainActivity
R="$OUT/relatorio_estresse.txt"
adb logcat -G 16M >/dev/null 2>&1 || true
# o que o app registrou durante os prints (antes do estresse), pra não perder com a limpeza do logcat
adb logcat -d -v brief 2>/dev/null | grep -E "NocryErro|FATAL EXCEPTION|ANR in" > "$OUT/.antes.txt" || true
adb logcat -c
abrir() { adb shell am start -n $PKG/$ACT --es tela "$1" --ei aba "${2:-0}" --ez demo "${3:-false}" >/dev/null 2>&1; sleep "${4:-2}"; }
INI=$(date +%s)

echo "1) troca de telas"
for volta in 1 2 3; do
  for t in home conexao bypass recursos comp vol carga mc ajustes otm estudio organizar log sobre; do abrir $t 0 false 1.2; done
  abrir bot 0 true 3; abrir bot 1 true 2; abrir bot 2 true 2
done

echo "2) segundo plano e volta"
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_HOME; sleep 2; abrir home 0 false 2; done

echo "3) bot em demonstração aberto, rolando o chat"
adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
abrir bot 0 true 5
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); X=$(( ${W%x*} / 2 )); A=${W#*x}
for i in $(seq 1 30); do adb shell input swipe $X $((A*70/100)) $X $((A*35/100)) 250; sleep 1; adb shell input swipe $X $((A*35/100)) $X $((A*70/100)) 250; sleep 1; done
FRAMES_BOT=$(adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|50th|90th|99th" )

echo "4) toques aleatórios"
adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
abrir home 0 false 3
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --ignore-crashes --ignore-timeouts --ignore-security-exceptions \
  --throttle 120 -s 7 -v 2500 > "$OUT/monkey.txt" 2>&1
sleep 8
FRAMES_MONKEY=$(adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|50th|90th|99th" )

adb logcat -d -v brief > "$OUT/logcat_tudo.txt" 2>/dev/null
{
  echo "TESTE DE ESTRESSE · $(( $(date +%s) - INI ))s"
  echo
  echo "== CRASHES (o app fechou) =="
  adb logcat -d -b crash -v brief | tail -n 400
  echo
  echo "== TRAVADAS (ANR) =="
  grep -E "ANR in|Application Not Responding|Input dispatching timed out" "$OUT/logcat_tudo.txt" | head -40
  echo
  echo "== RELATÓRIO DE ERROS DO APP (tag NocryErro) · durante os prints =="
  head -300 "$OUT/.antes.txt"
  echo
  echo "== RELATÓRIO DE ERROS DO APP (tag NocryErro) · durante o estresse =="
  grep -E "NocryErro" "$OUT/logcat_tudo.txt" | head -300
  echo
  echo "== STRICTMODE: trabalho pesado na thread principal, agrupado pelo ponto do app =="
  python3 - "$OUT/logcat_tudo.txt" <<'PY'
import sys, re, collections
linhas = open(sys.argv[1], errors='replace').read().split('\n')
blocos, atual = [], None
for l in linhas:
    if 'StrictMode' not in l: continue
    msg = l.split(':', 1)[1].strip() if ':' in l else l
    if 'policy violation' in msg or msg.startswith('StrictMode policy'):
        atual = [msg]; blocos.append(atual)
    elif atual is not None:
        atual.append(msg)
cont = collections.Counter(); exemplo = {}
for b in blocos:
    tipo = next((m.group(0) for x in b for m in [re.search(r'android\.os\.strictmode\.\w+', x)] if m), b[0][:80])
    app = next((x for x in b if 'com.nocry.app' in x), '(sem linha do app)')
    k = (tipo, app.strip())
    cont[k] += 1
    exemplo.setdefault(k, b[:22])
print(f"{len(blocos)} avisos, {len(cont)} lugares diferentes\n")
for (tipo, app), n in cont.most_common(40):
    print(f"[{n}x] {tipo}\n    {app}")
    for x in exemplo[(tipo, app)][1:22]:
        if 'com.nocry.app' in x: print('      ' + x.strip())
    print()
PY
  echo
  echo "== POR QUE O ANDROID FECHOU O APP (exit-info) =="
  adb shell dumpsys activity exit-info $PKG | grep -E "reason|description|importance|timestamp|pss" | head -80
  echo
  echo "== MEMÓRIA =="
  adb shell dumpsys meminfo $PKG | head -45
  echo
  echo "== QUADROS (bot rolando o chat) =="
  echo "$FRAMES_BOT"
  echo
  echo "== QUADROS (toques aleatórios) =="
  echo "$FRAMES_MONKEY"
  echo
  echo "== MONKEY (fim) =="
  tail -n 30 "$OUT/monkey.txt"
} > "$R" 2>&1
rm -f "$OUT/logcat_tudo.txt" "$OUT/.antes.txt"
echo "relatorio em $R"
