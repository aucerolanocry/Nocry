SDK=$(getprop ro.build.version.sdk); SDK=${SDK:-0}
OPV=RUN_ANY_IN_BACKGROUND; [ "$SDK" -lt 28 ] && OPV=RUN_IN_BACKGROUND
AM="cmd activity"; $AM help </dev/null >/dev/null 2>&1 || AM=am
AO="cmd appops"; $AO help </dev/null >/dev/null 2>&1 || AO=appops
DI="cmd deviceidle"; $DI help </dev/null >/dev/null 2>&1 || DI="dumpsys deviceidle"
op() { $AO get "$1" "$2" </dev/null 2>/dev/null | grep -oE 'allow|ignore|deny|default|foreground' | head -1; }
bname() { case "$1" in 10) echo active;; 20) echo working_set;; 30) echo frequent;; 40) echo rare;; 45) echo restricted;; 50) echo never;; esac; }
volta() {   # operacao valor-de-antes: ignore/deny/foreground = como estava; allow, default ou desconhecido = padrao do sistema
  case "$3" in ignore|deny|foreground) m="$3" ;; *) m=default ;; esac
  $AO set "$1" "$2" "$m" </dev/null >/dev/null 2>&1
}
undo() {
  e="$1"; p=${e%%:*}; e=${e#*:}; b=${e%%:*}; e=${e#*:}; o1=${e%%:*}; o2=${e#*:}
  if [ "$SDK" -ge 26 ]; then
    volta "$p" RUN_IN_BACKGROUND "$o1"
    volta "$p" RUN_ANY_IN_BACKGROUND "$o2"
    nb=$(bname "$b"); [ "$SDK" -ge 28 ] && [ -n "$nb" ] && $AM set-standby-bucket "$p" "$nb" </dev/null >/dev/null 2>&1
  fi
}
tm inicio
pd_ini
n=0
for e in $LISTA $LIBERA; do
  undo "$e" &
  n=$((n+1)); [ $((n % 8)) -eq 0 ] && wait
done
wait
# ---- o resto volta tudo ao mesmo tempo (antes era um por um). Cada volta conferida escreve 1 (voltou) ou 0 (nao voltou)
for p in $WLIMPA; do par $DI whitelist "-$p" </dev/null >/dev/null 2>&1; done
[ "$FPERF" = 1 ] && par cmd power set-fixed-performance-mode-enabled false </dev/null >/dev/null 2>&1
[ "${REDELAT:-0}" = 1 ] && par cmd wifi force-low-latency-mode disabled </dev/null >/dev/null 2>&1
[ "${LOCAL:-0}" = 1 ] && par cmd location set-location-enabled true </dev/null >/dev/null 2>&1
[ "${HIPERF:-0}" = 1 ] && par cmd wifi force-hi-perf-mode disabled </dev/null >/dev/null 2>&1
for p in $XLIGA; do par pm enable "$p" </dev/null >/dev/null 2>&1; done
f_rede() {      # a economia de dados desliga antes de tirar o FF e o NOCRY da lista (na ordem de antes)
  [ "${DSAVER:-0}" = 1 ] && cmd netpolicy set restrict-background false </dev/null >/dev/null 2>&1
  for u in $DUIDS; do cmd netpolicy remove restrict-background-whitelist "$u" </dev/null >/dev/null 2>&1; done
}
par f_rede
f_jogo() {
  p=${1%%:*}; m=${1#*:}
  case "$m" in standard|performance|battery) ;; *) return ;; esac
  cmd game mode "$m" "$p" </dev/null >/dev/null 2>&1
  if cmd game list-modes "$p" </dev/null 2>/dev/null | grep -q "current mode: $m"; then echo 1; else echo 0; fi
}
f_anim() {
  a="$1"; k=${a%%:*}; val=${a#*:}; ns=${k%%.*}; key=${k#*.}
  case "$k" in
    global.window_animation_scale|global.transition_animation_scale|global.animator_duration_scale|system.peak_refresh_rate|system.min_refresh_rate|global.captive_portal_detection_enabled|global.sem_enhanced_cpu_responsiveness|global.adaptive_battery_management_enabled|global.low_power|global.ble_scan_always_enabled|global.wifi_scan_always_enabled|global.wifi_networks_available_notification_on|global.heads_up_notifications_enabled|global.location_background_throttle_interval_ms|system.haptic_feedback_enabled|system.show_touches|system.pointer_location|system.sound_effects_enabled|global.updatable_driver_production_opt_in_apps|system.user_refresh_rate|global.notification_bubbles|secure.spell_checker_enabled|global.network_recommendations_enabled|global.tcp_default_init_rwnd|dc.max_cached_processes|global.disable_window_blurs|global.automatic_power_save_mode|global.cached_apps_freezer|global.settings_enable_monitor_phantom_procs|dc.max_phantom_processes) ;;
    *) return ;;
  esac
  if [ "$ns" = dc ]; then      # flag do sistema (Android 12): device_config, nao settings
    case "$val" in null|"") cmd device_config delete activity_manager $key </dev/null >/dev/null 2>&1; want=null ;; *) cmd device_config put activity_manager $key $val </dev/null >/dev/null 2>&1; want=$val ;; esac
    cur=$(cmd device_config get activity_manager $key </dev/null 2>/dev/null)
  else
    if cget "$ns" "$key" && { [ "$CV" = "${val:-null}" ] || cigual "$CV" "$val"; }; then cur=ok      # ja esta como era: nao precisa mexer
    else case "$val" in null|"") if cdel "$ns" "$key"; then cur=ok; else cur=no; fi ;; *) if cput "$ns" "$key" "$val"; then cur=ok; else cur=no; fi ;; esac; fi
    want=ok
  fi
  if [ "$cur" = "$want" ]; then echo 1; else echo 0; fi
}
f_confere() {      # o app voltou a poder rodar em segundo plano?
  e="$1"; p=${e%%:*}; r=${e#*:}; b=${r%%:*}; r=${r#*:}; o1=${r%%:*}; o2=${r#*:}
  [ "$SDK" -lt 28 ] && o2=$o1
  if [ "$SDK" -lt 26 ] || [ "$o2" = ignore ] || [ "$(op "$p" $OPV)" != ignore ]; then echo 1; else echo 0; fi
}
conta() { printf '%s' "$1" | tr -cd "$2" | wc -c; }
st=0; sv=0; v=0; t=0
if [ -n "$PD" ]; then
  for g in $GAMES; do par f_jogo "$g" >> "$PD/s"; done
  rr=""      # a taxa da tela (peak, min e a da Xiaomi) volta na ordem, uma depois da outra: uma mexe na outra
  for a in $ANIMS; do case "$a" in *refresh_rate:*) rr="$rr $a" ;; *) par f_anim "$a" >> "$PD/s" ;; esac; done
  f_taxas() { for a in $rr; do f_anim "$a"; done; }
  par f_taxas >> "$PD/s"
  for e in $LISTA $LIBERA; do par f_confere "$e" >> "$PD/v"; done
  wait
  rs=$(cat "$PD/s" 2>/dev/null); rv=$(cat "$PD/v" 2>/dev/null)
else      # sem a pasta: um por vez, como antes
  wait
  rs=""; for g in $GAMES; do rs="$rs$(f_jogo "$g")"; done
  for a in $ANIMS; do rs="$rs$(f_anim "$a")"; done
  rv=""; for e in $LISTA $LIBERA; do rv="$rv$(f_confere "$e")"; done
fi
st=$(( $(conta "$rs" 01) + 0 )); sv=$(( $(conta "$rs" 1) + 0 ))
t=$(( $(conta "$rv" 01) + 0 )); v=$(( $(conta "$rv" 1) + 0 ))
pd_fim
tm conferir
echo "V|$v|$t"
echo "S|$sv|$st"
echo FIM
