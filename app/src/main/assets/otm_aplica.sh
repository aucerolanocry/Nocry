SDK=$(getprop ro.build.version.sdk); SDK=${SDK:-0}
BK=restricted; [ "$SDK" -lt 30 ] && BK=rare
OPV=RUN_ANY_IN_BACKGROUND; [ "$SDK" -lt 28 ] && OPV=RUN_IN_BACKGROUND     # a que o aparelho tem pra conferir
AM="cmd activity"; $AM help </dev/null >/dev/null 2>&1 || AM=am
AO="cmd appops"; $AO help </dev/null >/dev/null 2>&1 || AO=appops
DI="cmd deviceidle"; $DI help </dev/null >/dev/null 2>&1 || DI="dumpsys deviceidle"
mem() { grep -E '^MemAvailable:' /proc/meminfo | tr -s ' ' | cut -d' ' -f2; }
op() { $AO get "$1" "$2" </dev/null 2>/dev/null | grep -oE 'allow|ignore|deny|default|foreground' | head -1; }
setg() {      # ns chave valor: guarda o de antes (A|), muda e CONFERE lendo de volta - R|ns.chave = o celular recusou (nao ficou)
  if ! cget "$1" "$2"; then echo "R|$1.$2"; return 1; fi
  echo "A|$1.$2|$CV"
  cigual "$CV" "$3" && return 0
  cput "$1" "$2" "$3" || { echo "R|$1.$2"; return 1; }
}
tm inicio
echo "MEM_ANTES=$(mem)"
pd_ini
work() {      # $2 = 0: o app esta parado, so congela (sem force-stop)
  p="$1"
  if [ "$SDK" -ge 26 ]; then
    b=$($AM get-standby-bucket "$p" </dev/null 2>/dev/null | tr -dc '0-9')
    o1=$(op "$p" RUN_IN_BACKGROUND); o2=$(op "$p" RUN_ANY_IN_BACKGROUND)
    echo "E|$p|${b:--}|${o1:--}|${o2:--}"
    $AO set "$p" RUN_IN_BACKGROUND ignore </dev/null >/dev/null 2>&1
    $AO set "$p" RUN_ANY_IN_BACKGROUND ignore </dev/null >/dev/null 2>&1
    [ "$SDK" -ge 28 ] && $AM set-standby-bucket "$p" $BK </dev/null >/dev/null 2>&1
    [ -n "$PD" ] && [ "$(op "$p" $OPV)" = ignore ] && echo 1 >> "$PD/ok"      # ja confere aqui, junto (antes era um por um no final)
  fi
  [ "${2:-1}" = 0 ] && return
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
# ---- 1) ajustes do sistema, liberar o NOCRY/Termux/FF e modo jogo: tudo ao mesmo tempo (antes era um por um)
XIAOMI=""; [ -n "$(getprop ro.miui.ui.version.name)$(getprop ro.mi.os.version.name)" ] && XIAOMI=1
f_hz() {      # tela de 90 Hz ou mais: trava na taxa maxima (o sistema nao baixa no meio do jogo). Na ordem: uma depende da outra
  setg system peak_refresh_rate $HZ; setg system min_refresh_rate $HZ
  if cget system peak_refresh_rate && cigual "$CV" "$HZ"; then echo "HZ|$HZ|1"; else echo "HZ|$HZ|0"; fi
  [ "${FULL:-0}" = 1 ] && [ -n "$XIAOMI" ] && setg system user_refresh_rate $HZ      # Xiaomi/HyperOS: a taxa que o sistema escolhe (senao ele baixa sozinho)
}
f_sem() { setg global sem_enhanced_cpu_responsiveness 1; echo "SEM|1"; }
f_ph() {
  if [ "$SDK" -ge 32 ]; then      # Android 12L+: o sistema mata processos "filhos" (e o Termux com o monitor) com sinal 9 quando passa de 32 -- desliga esse limite
    setg global settings_enable_monitor_phantom_procs false
    if cget global settings_enable_monitor_phantom_procs && [ "$CV" = false ]; then echo "PH|1"; else echo "PH|0"; fi
  elif [ "$SDK" -eq 31 ]; then      # Android 12: o limite e uma flag do sistema
    echo "A|dc.max_phantom_processes|$(cmd device_config get activity_manager max_phantom_processes </dev/null 2>/dev/null)"
    cmd device_config put activity_manager max_phantom_processes 2147483647 </dev/null >/dev/null 2>&1
    if [ "$(cmd device_config get activity_manager max_phantom_processes </dev/null 2>/dev/null)" = 2147483647 ]; then echo "PH|1"; else echo "PH|0"; fi
  fi
}
f_trim() {      # tela inicial e servicos do Google largam o que guardam na memoria
  h=$(cmd role get-role-holders android.app.role.HOME </dev/null 2>/dev/null | cut -d';' -f1)
  [ -n "$h" ] && am send-trim-memory "$h" MODERATE </dev/null >/dev/null 2>&1
  am send-trim-memory com.google.android.gms.persistent RUNNING_LOW </dev/null >/dev/null 2>&1
}
f_driver() {      # driver de video atualizado (so em celular que tem): o FF usa na proxima vez que abrir
  if [ -n "$(getprop ro.gfx.driver.0)" ] && [ -n "$GAMES" ]; then
    cget global updatable_driver_production_opt_in_apps; v="$CV"; [ "$v" = null ] && v=""
    n="$v"; for g in $GAMES; do case ",$n," in *",$g,"*) ;; *) n="${n:+$n,}$g" ;; esac; done
    [ "$n" != "$v" ] && setg global updatable_driver_production_opt_in_apps "$n"
  fi
}
f_mc() {      # menos apps parados guardados na memoria
  echo "A|dc.max_cached_processes|$(cmd device_config get activity_manager max_cached_processes </dev/null 2>/dev/null)"
  cmd device_config put activity_manager max_cached_processes 8 </dev/null >/dev/null 2>&1
  [ "$(cmd device_config get activity_manager max_cached_processes </dev/null 2>/dev/null)" = 8 ] && echo "MC|1"
}
f_loc() {      # localizacao (o FF nao usa)
  if [ "$(cmd location is-location-enabled </dev/null 2>/dev/null)" = true ]; then
    cmd location set-location-enabled false </dev/null >/dev/null 2>&1
    [ "$(cmd location is-location-enabled </dev/null 2>/dev/null)" = false ] && echo "Q|1"
  fi
}
f_hi() {      # Wi-Fi em alto desempenho (so se o celular aceitar)
  r=$(cmd wifi force-hi-perf-mode enabled </dev/null 2>&1)
  case "$r" in *nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ermission*|*ailure*|*"not allowed"*|*"does not have"*|*rivileg*|*"Failed transaction"*) ;; *) echo "H|1" ;; esac
}
f_xads() {      # propaganda e coleta de dados da Xiaomi: desligadas ate desativar
  dis=$(pm list packages -d </dev/null 2>/dev/null)
  for p in com.miui.msa.global com.miui.systemAdSolution com.miui.analytics; do
    pm path "$p" </dev/null >/dev/null 2>&1 || continue
    echo "$dis" | grep -q "^package:$p\$" && continue
    pm disable-user --user 0 "$p" </dev/null >/dev/null 2>&1
    pm list packages -d </dev/null 2>/dev/null | grep -q "^package:$p\$" && echo "X|$p"
  done
}
f_net() {      # economia de dados: em 2o plano so o FF e o NOCRY
  case "$(cmd netpolicy get restrict-background </dev/null 2>/dev/null)" in
    *disabled*)
      wl=$(cmd netpolicy list restrict-background-whitelist </dev/null 2>/dev/null)
      for p in $LIB; do
        u=$(cmd package list packages -U "$p" </dev/null 2>/dev/null | grep -E "^package:$p " | grep -oE 'uid:[0-9]+' | head -1 | cut -d: -f2)
        [ -n "$u" ] || continue
        echo "$wl" | grep -qw "$u" && continue
        cmd netpolicy add restrict-background-whitelist "$u" </dev/null >/dev/null 2>&1 && echo "D|$u"
      done
      cmd netpolicy set restrict-background true </dev/null >/dev/null 2>&1
      case "$(cmd netpolicy get restrict-background </dev/null 2>/dev/null)" in *enabled*) echo "D|ON" ;; esac ;;
  esac
}
f_lat() {
  r=$(cmd wifi force-low-latency-mode enabled </dev/null 2>&1)
  case "$r" in *nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ermission*|*ailure*|*"not allowed"*|*"does not have"*|*rivileg*|*"Failed transaction"*) ;; *) echo "N|1" ;; esac
}
f_perf() {      # desempenho fixo da CPU/GPU (comando oficial do Android 11+; so vale se o aparelho aceitar). OPCIONAL: trava num nivel estavel, nao no maximo
  r=$(cmd power set-fixed-performance-mode-enabled true </dev/null 2>&1)
  case "$r" in *nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ailure*|*"Failed transaction"*) ;; *) echo "F|1" ;; esac
}
gm() { r=$(cmd game list-modes "$1" </dev/null 2>/dev/null | tr -d '\r' | head -1); case "$r" in *"current mode"*) echo "$r" ;; esac; }     # so se o celular entende "list-modes"
f_jogo() {      # modo jogo "desempenho" no Free Fire (Android 12+): so se o jogo oferece esse modo e o celular aceita
  p="$1"
  l=$(gm "$p")
  prev=$(echo "$l" | sed -n 's/.*current mode: *\([A-Za-z0-9_]*\).*/\1/p')
  av=$(echo "$l" | sed -n 's/.*available game modes: *\[\(.*\)\].*/\1/p')
  case "$prev" in 1|standard) prev=standard ;; 2|performance) prev=performance ;; 3|battery) prev=battery ;; *) prev="" ;; esac
  [ "$prev" = performance ] && return
  if [ -n "$av" ]; then echo "$av" | grep -qE 'performance|(^|[^0-9])2([^0-9]|$)' || return; fi
  r=$(cmd game mode performance "$p" </dev/null 2>&1)
  case "$r" in *"not supported"*|*nknown*|*xception*|*rror*|*nvalid*|*"Can't find"*|*ermission*|*ailure*|*"Failed transaction"*) return ;; esac
  if [ -n "$l" ]; then case "$(gm "$p")" in *"current mode: performance"*|*"current mode: 2"*) ;; *) return ;; esac; fi
  echo "G|$p|${prev:-standard}"
}
if [ "$ANIM" = 1 ]; then
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do par setg global $k 0; done
fi
if [ "${HZ:-0}" -ge 90 ] 2>/dev/null; then par f_hz; fi
[ "$REDE" = 1 ] && par setg global captive_portal_detection_enabled 0      # para de testar a internet a toda hora
if [ "$(getprop ro.product.manufacturer </dev/null | tr 'A-Z' 'a-z')" = samsung ]; then par f_sem; fi
if [ "$EXTRA" = 1 ]; then      # ajustes de sistema (so os seguros): nada de aviso por cima do jogo, sem scan em segundo plano, sem bateria "inteligente" limitando o FF
  for kv in adaptive_battery_management_enabled:0 low_power:0 ble_scan_always_enabled:0 wifi_scan_always_enabled:0 wifi_networks_available_notification_on:0 heads_up_notifications_enabled:0 location_background_throttle_interval_ms:99999999; do
    par setg global "${kv%%:*}" "${kv#*:}"
  done
  par setg system haptic_feedback_enabled 0
  par setg global disable_window_blurs 1                  # sem desfoque de janela (Android 12+): menos trabalho pra GPU na barra de notificacoes e no menu
  par setg global automatic_power_save_mode 0             # o sistema nao liga a economia de bateria sozinho no meio do jogo
  [ "$SDK" -ge 31 ] && par setg global cached_apps_freezer enabled      # congelador do Android: apps em cache param de gastar CPU (so vale se o kernel tiver)
  par f_ph
fi
if [ "${FULL:-0}" = 1 ]; then      # FULL: tudo que e real e volta ao desativar (do bypass antigo, sem os que estragam o celular)
  par setg system show_touches 0
  par setg system pointer_location 0
  par setg system sound_effects_enabled 0                # som de toque na tela
  par setg global notification_bubbles 0                 # baloes de conversa por cima do jogo
  par setg secure spell_checker_enabled 0
  par setg global network_recommendations_enabled 0
  par f_trim
  par f_driver
  par setg global tcp_default_init_rwnd 60               # conexoes comecam mais rapido (atualizacao/download do jogo)
  if [ "$SDK" -ge 30 ]; then par f_mc; par f_loc; fi
  par f_hi
  [ -n "$XIAOMI" ] && par f_xads
  par f_net
  [ "$SDK" -ge 29 ] && par f_lat
fi
[ "$PERF" = 1 ] && [ "$SDK" -ge 30 ] && par f_perf
if [ "$SDK" -ge 31 ]; then for p in $GAMES; do par f_jogo "$p"; done; fi
for p in $LIB; do par libera "$p"; done
wait
tm ajustes
# ---- 2) fechar os apps (8 de cada vez, como antes)
n=0
for p in $PKGS; do
  work "$p" &
  n=$((n+1)); [ $((n % 8)) -eq 0 ] && wait
done
wait
for p in $FRIO; do      # FULL: instalados que estao parados
  work "$p" 0 &
  n=$((n+1)); [ $((n % 8)) -eq 0 ] && wait
done
wait
tm apps
$AM kill-all </dev/null >/dev/null 2>&1      # mata o que sobrou em cache (so o que o sistema considera seguro)
sleep 1
psl=$(ps -A -o NAME </dev/null 2>/dev/null || ps -A </dev/null 2>/dev/null || ps </dev/null)
v=0; t=0; vivos=0
if [ -n "$PD" ]; then      # cada app ja conferiu na hora de fechar
  for p in $PKGS $FRIO; do t=$((t+1)); done
  v=$(grep -c 1 "$PD/ok" 2>/dev/null); v=$((${v:-0}+0))
else
  for p in $PKGS $FRIO; do
    t=$((t+1))
    [ "$SDK" -ge 26 ] && [ "$(op "$p" $OPV)" = ignore ] && v=$((v+1))
  done
fi
for p in $PKGS; do
  echo "$psl" | grep -qE "(^|[[:space:]])$p(:[A-Za-z0-9_.]+)?\$" && vivos=$((vivos+1))
done
echo "V|$v|$t|$vivos"
echo "MEM_DEPOIS=$(mem)"
pd_fim
tm conferir
echo FIM
