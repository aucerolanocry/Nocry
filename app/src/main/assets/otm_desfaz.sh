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
n=0
for e in $LISTA $LIBERA; do
  undo "$e" &
  n=$((n+1)); [ $((n % 8)) -eq 0 ] && wait
done
wait
for p in $WLIMPA; do $DI whitelist "-$p" </dev/null >/dev/null 2>&1; done
st=0; sv=0
[ "$FPERF" = 1 ] && cmd power set-fixed-performance-mode-enabled false </dev/null >/dev/null 2>&1
for g in $GAMES; do
  p=${g%%:*}; m=${g#*:}
  case "$m" in standard|performance|battery) ;; *) continue ;; esac
  cmd game mode "$m" "$p" </dev/null >/dev/null 2>&1
  st=$((st+1)); cmd game list-modes "$p" </dev/null 2>/dev/null | grep -q "current mode: $m" && sv=$((sv+1))
done
for a in $ANIMS; do
  k=${a%%:*}; val=${a#*:}; ns=${k%%.*}; key=${k#*.}
  case "$k" in
    global.window_animation_scale|global.transition_animation_scale|global.animator_duration_scale|system.peak_refresh_rate|system.min_refresh_rate|global.captive_portal_detection_enabled|global.sem_enhanced_cpu_responsiveness|global.adaptive_battery_management_enabled|global.low_power|global.ble_scan_always_enabled|global.wifi_scan_always_enabled|global.wifi_networks_available_notification_on|global.heads_up_notifications_enabled|global.location_background_throttle_interval_ms|system.haptic_feedback_enabled|global.disable_window_blurs|global.automatic_power_save_mode|global.cached_apps_freezer|global.settings_enable_monitor_phantom_procs|dc.max_phantom_processes) ;;
    *) continue ;;
  esac
  if [ "$ns" = dc ]; then      # flag do sistema (Android 12): device_config, nao settings
    case "$val" in null|"") cmd device_config delete activity_manager $key </dev/null >/dev/null 2>&1; want=null ;; *) cmd device_config put activity_manager $key $val </dev/null >/dev/null 2>&1; want=$val ;; esac
    cur=$(cmd device_config get activity_manager $key </dev/null 2>/dev/null)
  else
    if cget "$ns" "$key" && { [ "$CV" = "${val:-null}" ] || cigual "$CV" "$val"; }; then cur=ok      # ja esta como era: nao precisa mexer
    else case "$val" in null|"") if cdel "$ns" "$key"; then cur=ok; else cur=no; fi ;; *) if cput "$ns" "$key" "$val"; then cur=ok; else cur=no; fi ;; esac; fi
    want=ok
  fi
  st=$((st+1)); [ "$cur" = "$want" ] && sv=$((sv+1))
done
v=0; t=0
for e in $LISTA $LIBERA; do
  p=${e%%:*}; r=${e#*:}; b=${r%%:*}; r=${r#*:}; o1=${r%%:*}; o2=${r#*:}; t=$((t+1))
  [ "$SDK" -lt 28 ] && o2=$o1
  if [ "$SDK" -lt 26 ] || [ "$o2" = ignore ] || [ "$(op "$p" $OPV)" != ignore ]; then v=$((v+1)); fi
done
echo "V|$v|$t"
echo "S|$sv|$st"
echo FIM
