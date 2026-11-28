T=""; command -v timeout >/dev/null 2>&1 && T="timeout 25"      # celular lento: o dumpsys nao trava a leitura inteira
# cada parte da leitura e uma funcao. Rodam todas ao mesmo tempo (antes era uma por uma) e saem na mesma ordem de sempre
s_PROP() { for k in ro.product.manufacturer ro.product.model ro.product.marketname ro.build.version.release ro.build.version.sdk ro.miui.ui.version.name ro.mi.os.version.name ro.build.version.emui; do echo "$k=$(getprop $k)"; done; }
s_MEM() { grep -E '^(MemTotal|MemAvailable|MemFree):' /proc/meminfo; }
s_PKG3() { pm list packages -3 </dev/null; }
s_PS() { ps -A -o RSS,NAME </dev/null 2>/dev/null || ps -A </dev/null 2>/dev/null || ps </dev/null; }
s_FG() { $T dumpsys activity activities </dev/null 2>/dev/null | grep ResumedActivity | head -3; }
s_HOME() {
  h=$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null 2>/dev/null | tail -1)
  [ -n "$h" ] || h=$(pm resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null 2>/dev/null | tail -1)
  echo "$h"
}
s_SET() { for k in default_input_method enabled_accessibility_services enabled_notification_listeners sms_default_application dialer_default_application; do if cget secure $k; then echo "$k=$CV"; else echo "$k=!"; fi; done; }      # "!" = o celular nao deixou ler (o menu NAO fecha nada nesse caso)
s_FGS() { $T dumpsys activity services </dev/null 2>/dev/null | grep -E 'ServiceRecord\{|isForeground=true'; }
s_HZ() { $T dumpsys display </dev/null 2>/dev/null | grep -oE '(fps|refreshRate)=[0-9]+' | cut -d= -f2 | sort -n | tail -1; }
s_PKGALL() { pm list packages </dev/null 2>/dev/null; }
s_LAUNCH() {
  l=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER </dev/null 2>/dev/null | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+')
  [ -n "$l" ] || l=$(pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER </dev/null 2>/dev/null | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+')
  echo "$l" | cut -d/ -f1 | sort -u
}
s_SVC() {      # plano B quando o celular nao deixa ler as configuracoes: todo app que oferece teclado, acessibilidade, leitor de notificacao, SMS ou telefone
  for a in android.view.InputMethod android.accessibilityservice.AccessibilityService android.service.notification.NotificationListenerService; do
    s=$(cmd package query-services --brief -a $a </dev/null 2>/dev/null); [ -n "$s" ] || s=$(pm query-services --brief -a $a </dev/null 2>/dev/null)
    echo "$s" | grep -oE '^ *[A-Za-z0-9_.]+/' | tr -d ' /'
  done
  for r in android.app.role.SMS android.app.role.DIALER android.app.role.HOME; do cmd role get-role-holders $r </dev/null 2>/dev/null | tr ';' '\n'; done
}
PARTES="PROP MEM PKG3 PS FG HOME SET FGS HZ PKGALL LAUNCH SVC"
LD=/data/local/tmp/.nocry_l$$
rm -rf "$LD" 2>/dev/null
if mkdir -p "$LD" 2>/dev/null && : > "$LD/.ok" 2>/dev/null; then
  for x in $PARTES; do s_$x > "$LD/$x" 2>&1 & done
  wait
  for x in $PARTES; do echo "@$x"; cat "$LD/$x"; done
  rm -rf "$LD" 2>/dev/null
else      # nao deu pra criar a pasta: le uma por uma, como antes
  for x in $PARTES; do echo "@$x"; s_$x; done
fi
echo @FIM
