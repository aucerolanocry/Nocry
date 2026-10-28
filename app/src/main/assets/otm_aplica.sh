SDK=$(getprop ro.build.version.sdk); SDK=${SDK:-0}
BK=restricted; [ "$SDK" -lt 30 ] && BK=rare
OPV=RUN_ANY_IN_BACKGROUND; [ "$SDK" -lt 28 ] && OPV=RUN_IN_BACKGROUND     # a que o aparelho tem pra conferir
AM="cmd activity"; $AM help </dev/null >/dev/null 2>&1 || AM=am
AO="cmd appops"; $AO help </dev/null >/dev/null 2>&1 || AO=appops
DI="cmd deviceidle"; $DI help </dev/null >/dev/null 2>&1 || DI="dumpsys deviceidle"
mem() { grep -E '^MemAvailable:' /proc/meminfo | tr -s ' ' | cut -d' ' -f2; }
op() { $AO get "$1" "$2" </dev/null 2>/dev/null | grep -oE 'allow|ignore|deny|default|foreground' | head -1; }
setg() {      # ns chave valor: guarda o de antes (A|), muda e CONFERE lendo de volta · R|ns.chave = o celular recusou (nao ficou)
  if ! cget "$1" "$2"; then echo "R|$1.$2"; return 1; fi
  echo "A|$1.$2|$CV"
  cigual "$CV" "$3" && return 0
  cput "$1" "$2" "$3" || { echo "R|$1.$2"; return 1; }
}
echo "MEM_ANTES=$(mem)"
work() {
  p="$1"
  if [ "$SDK" -ge 26 ]; then
    b=$($AM get-standby-bucket "$p" </dev/null 2>/dev/null | tr -dc '0-9')
    o1=$(op "$p" RUN_IN_BACKGROUND); o2=$(op "$p" RUN_ANY_IN_BACKGROUND)
    echo "E|$p|${b:--}|${o1:--}|${o2:--}"
    $AO set "$p" RUN_IN_BACKGROUND ignore </dev/null >/dev/null 2>&1
    $AO set "$p" RUN_ANY_IN_BACKGROUND ignore </dev/null >/dev/null 2>&1
    [ "$SDK" -ge 28 ] && $AM set-standby-bucket "$p" $BK </dev/null >/dev/null 2>&1
  fi
  $AM force-stop "$p" </dev/null >/dev/null 2>&1
  echo "K|$p"      # fechou (o menu mostra ao vivo)
}
libera() {   # Termux e FF: sempre liberados pra rodar em segundo plano e fora da economia de energia
  p="$1"
  if [ "$SDK" -ge 26 ]; then
    b=$($AM get-standby-bucket "$p" </dev/null 2>/dev/null | tr -dc '0-9')
    o1=$(op "$p" RUN_IN_BACKGROUND); o2=$(op "$p" RUN_ANY_IN_BACKGROUND)
    echo "L|$p|${b:--}|${o1:--}|${o2:--}"
    $AO set "$p" RUN_IN_BACKGROUND allow </dev/null >/dev/null 2>&1
    $AO set "$p" RUN_ANY_IN_BACKGROUND allow </dev/null >/dev/null 2>&1
    [ "$SDK" -ge 28 ] && $AM set-standby-bucket "$p" active </dev/null >/dev/null 2>&1
  fi
  if $DI whitelist </dev/null 2>/dev/null | grep -q ",$p,"; then echo "W|$p|1"; else echo "W|$p|0"; fi
  $DI whitelist "+$p" </dev/null >/dev/null 2>&1
}
if [ "$ANIM" = 1 ]; then
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do setg global $k 0; done
fi
if [ "${HZ:-0}" -ge 90 ] 2>/dev/null; then      # tela de 90 Hz ou mais: trava na taxa maxima (o sistema nao baixa no meio do jogo)
  setg system peak_refresh_rate $HZ; setg system min_refresh_rate $HZ
  if cget system peak_refresh_rate && cigual "$CV" "$HZ"; then echo "HZ|$HZ|1"; else echo "HZ|$HZ|0"; fi
fi
[ "$REDE" = 1 ] && setg global captive_portal_detection_enabled 0      # para de testar a internet a toda hora
if [ "$(getprop ro.product.manufacturer </dev/null | tr 'A-Z' 'a-z')" = samsung ]; then setg global sem_enhanced_cpu_responsiveness 1; echo "SEM|1"; fi
if [ "$EXTRA" = 1 ]; then      # ajustes de sistema (so os seguros): nada de aviso por cima do jogo, sem scan em segundo plano, sem bateria "inteligente" limitando o FF
  for kv in adaptive_battery_management_enabled:0 low_power:0 ble_scan_always_enabled:0 wifi_scan_always_enabled:0 wifi_networks_available_notification_on:0 heads_up_notifications_enabled:0 location_background_throttle_interval_ms:99999999; do
    setg global "${kv%%:*}" "${kv#*:}"
  done
  setg system haptic_feedback_enabled 0
  setg global disable_window_blurs 1                  # sem desfoque de janela (Android 12+): menos trabalho pra GPU na barra de notificacoes e no menu
  setg global automatic_power_save_mode 0             # o sistema nao liga a economia de bateria sozinho no meio do jogo
  [ "$SDK" -ge 31 ] && setg global cached_apps_freezer enabled      # congelador do Android: apps em cache param de gastar CPU (so vale se o kernel tiver)
  if [ "$SDK" -ge 32 ]; then      # Android 12L+: o sistema mata processos "filhos" (e o Termux com o monitor) com sinal 9 quando passa de 32 -- desliga esse limite
    setg global settings_enable_monitor_phantom_procs false
    if cget global settings_enable_monitor_phantom_procs && [ "$CV" = false ]; then echo "PH|1"; else echo "PH|0"; fi
  elif [ "$SDK" -eq 31 ]; then      # Android 12: o limite e uma flag do sistema
    echo "A|dc.max_phantom_processes|$(cmd device_config get activity_manager max_phantom_processes </dev/null 2>/dev/null)"
    cmd device_config put activity_manager max_phantom_processes 2147483647 </dev/null >/dev/null 2>&1
    if [ "$(cmd device_config get activity_manager max_phantom_processes </dev/null 2>/dev/null)" = 2147483647 ]; then echo "PH|1"; else echo "PH|0"; fi
  fi
fi
if [ "$PERF" = 1 ] && [ "$SDK" -ge 30 ]; then      # desempenho fixo da CPU/GPU (comando oficial do Android 11+; so vale se o aparelho aceitar). OPCIONAL: trava num nivel estavel, nao no maximo
  r=$(cmd power set-fixed-performance-mode-enabled true </dev/null 2>&1)
  case "$r" in *nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ailure*|*"Failed transaction"*) ;; *) echo "F|1" ;; esac
fi
gm() { r=$(cmd game list-modes "$1" </dev/null 2>/dev/null | tr -d '\r' | head -1); case "$r" in *"current mode"*) echo "$r" ;; esac; }     # so se o celular entende "list-modes"
if [ "$SDK" -ge 31 ]; then      # modo jogo "desempenho" no Free Fire (Android 12+): so se o jogo oferece esse modo e o celular aceita
  for p in $GAMES; do
    l=$(gm "$p")
    prev=$(echo "$l" | sed -n 's/.*current mode: *\([A-Za-z0-9_]*\).*/\1/p')
    av=$(echo "$l" | sed -n 's/.*available game modes: *\[\(.*\)\].*/\1/p')
    case "$prev" in 1|standard) prev=standard ;; 2|performance) prev=performance ;; 3|battery) prev=battery ;; *) prev="" ;; esac
    [ "$prev" = performance ] && continue
    if [ -n "$av" ]; then echo "$av" | grep -qE 'performance|(^|[^0-9])2([^0-9]|$)' || continue; fi
    r=$(cmd game mode performance "$p" </dev/null 2>&1)
    case "$r" in *"not supported"*|*nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ermission*|*ailure*|*"Failed transaction"*) continue ;; esac
    if [ -n "$l" ]; then case "$(gm "$p")" in *"current mode: performance"*|*"current mode: 2"*) ;; *) continue ;; esac; fi
    echo "G|$p|${prev:-standard}"
  done
fi
for p in $LIB; do libera "$p"; done
n=0
for p in $PKGS; do
  work "$p" &
  n=$((n+1)); [ $((n % 8)) -eq 0 ] && wait
done
wait
$AM kill-all </dev/null >/dev/null 2>&1      # mata o que sobrou em cache (so o que o sistema considera seguro)
sleep 1
psl=$(ps -A -o NAME </dev/null 2>/dev/null || ps -A </dev/null 2>/dev/null || ps </dev/null)
v=0; t=0; vivos=0
for p in $PKGS; do
  t=$((t+1))
  [ "$SDK" -ge 26 ] && [ "$(op "$p" $OPV)" = ignore ] && v=$((v+1))
  echo "$psl" | grep -qE "(^|[[:space:]])$p(:[A-Za-z0-9_.]+)?\$" && vivos=$((vivos+1))
done
echo "V|$v|$t|$vivos"
echo "MEM_DEPOIS=$(mem)"
echo FIM
