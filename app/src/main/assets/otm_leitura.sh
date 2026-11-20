T=""; command -v timeout >/dev/null 2>&1 && T="timeout 25"      # celular lento: o dumpsys não trava a leitura inteira
echo @PROP
for k in ro.product.manufacturer ro.product.model ro.product.marketname ro.build.version.release ro.build.version.sdk ro.miui.ui.version.name ro.mi.os.version.name ro.build.version.emui; do echo "$k=$(getprop $k)"; done
echo @MEM
grep -E '^(MemTotal|MemAvailable|MemFree):' /proc/meminfo
echo @PKG3
pm list packages -3 </dev/null
echo @PS
ps -A -o RSS,NAME </dev/null 2>/dev/null || ps -A </dev/null 2>/dev/null || ps </dev/null
echo @FG
$T dumpsys activity activities </dev/null 2>/dev/null | grep ResumedActivity | head -3
echo @HOME
h=$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null 2>/dev/null | tail -1)
[ -n "$h" ] || h=$(pm resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null 2>/dev/null | tail -1)
echo "$h"
echo @SET
for k in default_input_method enabled_accessibility_services enabled_notification_listeners sms_default_application dialer_default_application; do if cget secure $k; then echo "$k=$CV"; else echo "$k=!"; fi; done      # "!" = o celular nao deixou ler (o menu NAO fecha nada nesse caso)
echo @FGS
$T dumpsys activity services </dev/null 2>/dev/null | grep -E 'ServiceRecord\{|isForeground=true'
echo @HZ
$T dumpsys display </dev/null 2>/dev/null | grep -oE '(fps|refreshRate)=[0-9]+' | cut -d= -f2 | sort -n | tail -1
echo @PKGALL
pm list packages </dev/null 2>/dev/null
echo @LAUNCH
l=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER </dev/null 2>/dev/null | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+')
[ -n "$l" ] || l=$(pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER </dev/null 2>/dev/null | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+')
echo "$l" | cut -d/ -f1 | sort -u
echo @SVC      # plano B quando o celular não deixa ler as configurações: todo app que oferece teclado, acessibilidade, leitor de notificação, SMS ou telefone
for a in android.view.InputMethod android.accessibilityservice.AccessibilityService android.service.notification.NotificationListenerService; do
  s=$(cmd package query-services --brief -a $a </dev/null 2>/dev/null); [ -n "$s" ] || s=$(pm query-services --brief -a $a </dev/null 2>/dev/null)
  echo "$s" | grep -oE '^ *[A-Za-z0-9_.]+/' | tr -d ' /'
done
for r in android.app.role.SMS android.app.role.DIALER android.app.role.HOME; do cmd role get-role-holders $r </dev/null 2>/dev/null | tr ';' '\n'; done
echo @FIM
