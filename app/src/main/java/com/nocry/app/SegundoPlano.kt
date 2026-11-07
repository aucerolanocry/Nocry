package com.nocry.app

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View

/**
 * Tudo o que faz o NOCRY continuar funcionando com o app minimizado ou fechado nos recentes:
 * - libera o app da economia de bateria (o Android para de fechar ele)
 * - no Xiaomi/Redmi/POCO: "inicio automatico" (deixa ele voltar sozinho)
 * - se mesmo assim for fechado com algo ativado, agenda pra religar o servico na hora
 */
object SegundoPlano {
    private const val OP_AUTO_START_MIUI = 10008
    @Volatile private var jaLiberou = false

    fun bateriaLivre(c: Context): Boolean = try {
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) ?: true
    } catch (_: Throwable) { true }

    fun xiaomi(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** so no Xiaomi: true/false = deu pra ler; null = nao deu pra saber */
    fun autoInicio(c: Context): Boolean? {
        if (!xiaomi()) return null
        return try {
            val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            (m.invoke(ao, OP_AUTO_START_MIUI, android.os.Process.myUid(), c.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { null }
    }

    /** assinatura do estado (a tela redesenha quando muda, ex.: voltou das configuracoes) */
    fun estado(c: Context): String = "${bateriaLivre(c)}|${autoInicio(c)}|${Adb.connected}"

    /**
     * Pelo ADB (sem o usuario precisar mexer em nada): tira da economia de bateria, deixa rodar em segundo
     * plano, libera o alarme que religa o servico e, no Xiaomi, o inicio automatico. Roda uma vez por abertura.
     */
    fun garantePeloAdb(forcar: Boolean = false): Boolean {
        if (!Adb.connected) return false
        if (jaLiberou && !forcar) return true
        val p = Const.PKG
        val cmds = listOf(
            "cmd deviceidle whitelist +$p",
            "cmd appops set $p RUN_IN_BACKGROUND allow",
            "cmd appops set $p RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $p SCHEDULE_EXACT_ALARM allow",
            "am set-standby-bucket $p active",
            "cmd appops set $p $OP_AUTO_START_MIUI allow"
        )
        // cada um separado: se um nao existir nesse Android, os outros continuam
        Adb.exec(cmds.joinToString("; ") { "$it </dev/null >/dev/null 2>&1" }, 15000)
        jaLiberou = true
        NLog.add("segundo-plano", "liberado pelo ADB · bateria livre=" + bateriaLivre(Sys.app) + " · auto inicio=" + autoInicio(Sys.app))
        return true
    }

    /** botao "liberar": pelo ADB se estiver conectado, senao abre o pedido do proprio Android */
    fun liberarBateria(act: MainActivity, depois: () -> Unit) {
        if (Adb.connected) {
            Bg.run({ garantePeloAdb(forcar = true) }) { _ ->
                if (bateriaLivre(act)) depois() else abrePedidoBateria(act)
            }
        } else abrePedidoBateria(act)
    }

    private fun abrePedidoBateria(act: MainActivity) {
        try {
            act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
        } catch (_: Throwable) {
            try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    /** abre a tela de "inicio automatico" do Xiaomi (ou os detalhes do app, se nao achar) */
    fun abrirAutoInicio(act: MainActivity) {
        val tentativas = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName))
        )
        for (i in tentativas) {
            try { act.startActivity(i); return } catch (_: Throwable) {}
        }
    }

    /**
     * O app foi fechado nos recentes com algo ativado: alguns Androids (Xiaomi principalmente) matam tudo junto.
     * Agenda um alarme que religa o servico ~1,5 s depois.
     */
    fun agendaVolta(c: Context, ms: Long = 1500L, servico: Class<*> = NocryService::class.java) {
        try {
            val ctx = c.applicationContext
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getForegroundService(
                ctx, if (servico == NocryService::class.java) 77 else 78, Intent(ctx, servico), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val quando = SystemClock.elapsedRealtime() + ms
            val exato = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exato) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pi)
            NLog.add("segundo-plano", "app fechado nos recentes: religa ${servico.simpleName} em ${ms} ms (alarme exato=$exato)")
        } catch (t: Throwable) {
            NLog.add("segundo-plano", "nao agendei a volta: $t")
        }
    }

    /** grupo pras telas do bypass e da instalacao */
    fun grupo(act: MainActivity, depois: () -> Unit): View {
        val c = act
        val bat = bateriaLivre(c)
        val itens = ArrayList<View>()
        itens.add(Ui.itemEstado(c, "Bateria sem restrição",
            if (bat) "o Android não fecha o NOCRY em segundo plano" else "toque pra liberar: senão o Android pode fechar o NOCRY",
            R.drawable.ic_bolt, if (bat) "OK" else "LIBERAR", if (bat) Ui.OK else Ui.WARN,
            if (bat) null else ({ liberarBateria(act, depois) })))
        if (xiaomi()) {
            val ai = autoInicio(c)
            itens.add(Ui.itemEstado(c, "Início automático",
                if (ai == true) "deixa o NOCRY voltar sozinho se for fechado" else "no Xiaomi: ache o NOCRY e ligue",
                R.drawable.ic_power, when (ai) { true -> "OK"; false -> "LIGAR"; null -> "ABRIR" },
                when (ai) { true -> Ui.OK; false -> Ui.WARN; null -> null }) { abrirAutoInicio(act) })
        }
        itens.add(Ui.item(c, "Trave nos recentes", "abra os apps recentes, segure o NOCRY e toque no cadeado", R.drawable.ic_lock))
        return Ui.grupo(c, "funcionar com o app fechado", itens)
    }
}
