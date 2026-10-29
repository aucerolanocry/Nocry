package com.nocry.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.BufferedReader
import java.io.InputStreamReader

// =====================================================================================
// Escuta do volume pela ACESSIBILIDADE (nao consome a tecla: o volume continua mudando)
// =====================================================================================
class KeyService : AccessibilityService() {
    companion object {
        @Volatile var ativo = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val i = serviceInfo
        if (i != null) {
            i.flags = i.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = i
        }
        ativo = true
        NLog.add("acessibilidade", "conectado")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> Engine.volKey(true)
                KeyEvent.KEYCODE_VOLUME_DOWN -> Engine.volKey(false)
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        ativo = false
        NLog.add("acessibilidade", "desconectado")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ativo = false
        super.onDestroy()
    }
}

// =====================================================================================
// Escuta do volume pelo ADB (getevent), usada so quando a acessibilidade nao esta ligada
// =====================================================================================
class KeyWatcher {
    @Volatile private var go = false
    @Volatile private var stream: AdbStream? = null
    private var th: Thread? = null

    val vivo: Boolean get() = go && (th?.isAlive == true)

    private fun achaTeclas(): List<String> {
        val r = Adb.exec("getevent -lp </dev/null 2>/dev/null", 10000)
        if (!r.ok) return emptyList()
        val nodes = LinkedHashSet<String>()
        var dev = ""
        for (l in r.out.lines()) {
            if (l.startsWith("add device")) {
                dev = l.substringAfter(": ", "").trim()
            } else if (dev.isNotEmpty() && (l.contains("KEY_VOLUMEUP") || l.contains("KEY_VOLUMEDOWN"))) {
                nodes.add(dev)
            }
        }
        return nodes.filter { Regex("/dev/input/[A-Za-z0-9_]+").matches(it) }
    }

    fun start() {
        if (go) return
        go = true
        val t = Thread {
            while (go) {
                try {
                    val nodes = achaTeclas()
                    if (nodes.isEmpty()) {
                        NLog.add("volume", "nao achei as teclas de volume no getevent deste aparelho")
                        sleep(5000)
                        continue
                    }
                    NLog.add("volume", "ouvindo as teclas de volume pelo ADB (" + nodes.joinToString(" ") + ")")
                    val s = Adb.stream("getevent -l " + nodes.joinToString(" ")) ?: run { sleep(3000); null }
                    if (s == null) continue
                    stream = s
                    val r = BufferedReader(InputStreamReader(s.openInputStream(), Charsets.UTF_8))
                    while (go) {
                        val l = r.readLine() ?: break
                        val t2 = l.trim()
                        if (t2.endsWith("DOWN")) {
                            if (t2.contains("KEY_VOLUMEUP")) Engine.volKey(true)
                            else if (t2.contains("KEY_VOLUMEDOWN")) Engine.volKey(false)
                        }
                    }
                } catch (_: Throwable) {
                }
                try { stream?.close() } catch (_: Throwable) {}
                stream = null
                if (go) {
                    NLog.add("volume", "a escuta das teclas caiu, tentando de novo")
                    sleep(2000)
                }
            }
        }
        t.name = "nocry-getevent"
        t.isDaemon = true
        th = t
        t.start()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    fun stop() {
        go = false
        try { stream?.close() } catch (_: Throwable) {}
        th?.interrupt()
    }
}

// =====================================================================================
// Servico em primeiro plano: mantem os monitores vivos (volume, carregador, modo carregador, otimizacao automatica)
// =====================================================================================
class NocryService : Service() {
    companion object {
        @Volatile var rodando = false

        /** liga o servico se algum monitor esta ligado; desliga se nenhum esta */
        fun sync(c: Context) {
            val ctx = c.applicationContext
            try {
                if (Prefs.precisaServico()) ctx.startForegroundService(Intent(ctx, NocryService::class.java))
                else ctx.stopService(Intent(ctx, NocryService::class.java))
            } catch (t: Throwable) {
                NLog.add("servico", "nao consegui acertar o servico: $t")
            }
        }
    }

    @Volatile private var go = false
    private var loop: Thread? = null
    private var adbTh: Thread? = null
    private var rx: BroadcastReceiver? = null
    private var watcher: KeyWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        primeiroPlano(Engine.resumo())
        if (!Prefs.precisaServico() || !License.valid()) {
            stopSelf()
            return START_NOT_STICKY
        }
        rodando = true
        registraReceptor()
        inicia()
        return START_STICKY
    }

    private fun primeiroPlano(texto: String) {
        val abre = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, Notif.CH_SVC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NOCRY ativo")
            .setContentText(texto)
            .setContentIntent(abre)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notif.ID_SVC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notif.ID_SVC, n)
    }

    private fun atualizaNotificacao(texto: String) {
        try {
            val abre = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, Notif.CH_SVC)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("NOCRY ativo")
                .setContentText(texto)
                .setContentIntent(abre)
                .setOngoing(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(Notif.ID_SVC, n)
        } catch (_: Exception) {
        }
    }

    private fun registraReceptor() {
        if (rx != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Engine.carregador()
            }
        }
        val f = IntentFilter()
        f.addAction(Intent.ACTION_POWER_CONNECTED)
        f.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        rx = r
    }

    private fun inicia() {
        if (go) return
        go = true
        NLog.add("servico", "ligado: " + Engine.resumo())

        // ciclo rapido: confere o carregador a cada 1 s (so le a bateria, nao usa ADB)
        val l = Thread {
            var n = 0
            var ultimo = ""
            while (go) {
                try {
                    Engine.tick()
                    if (n % 5 == 0) {
                        val r = Engine.resumo()
                        if (r != ultimo) { ultimo = r; atualizaNotificacao(r) }
                    }
                } catch (t: Throwable) {
                    NLog.add("servico", "ciclo: $t")
                }
                n++
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        l.name = "nocry-loop"
        l.isDaemon = true
        loop = l
        l.start()

        // ciclo do ADB: mantem a conexao viva, cuida da escuta do volume por getevent e da otimizacao automatica
        val a = Thread {
            var n = 0
            while (go) {
                try {
                    if (n % 20 == 0) Bg.io { License.tick(applicationContext) }
                    if (!License.valid()) { stopSelf(); break }
                    cuidaAdb()
                    if (Prefs.otmAuto) OtmAuto.tick(this)
                } catch (t: Throwable) {
                    NLog.add("servico", "adb: $t")
                }
                n++
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }
        a.name = "nocry-adb"
        a.isDaemon = true
        adbTh = a
        a.start()
    }

    private fun precisaAdb(): Boolean =
        Prefs.mcAtivo || Prefs.otmAuto || (Engine.precisaTeclas() && !KeyService.ativo)

    private fun cuidaAdb() {
        if (precisaAdb()) {
            if (!Adb.alive()) {
                watcher?.stop()
                watcher = null
                if (!Adb.ensure(applicationContext)) NLog.add("servico", "ADB sem resposta: " + Adb.lastError)
            }
        }
        val querGetevent = Engine.precisaTeclas() && !KeyService.ativo && Adb.connected
        val w = watcher
        if (querGetevent) {
            if (w == null || !w.vivo) {
                w?.stop()
                val novo = KeyWatcher()
                watcher = novo
                novo.start()
            }
        } else if (w != null) {
            w.stop()
            watcher = null
        }
    }

    override fun onDestroy() {
        go = false
        rodando = false
        loop?.interrupt()
        adbTh?.interrupt()
        watcher?.stop()
        watcher = null
        try { rx?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        rx = null
        NLog.add("servico", "desligado")
        super.onDestroy()
    }
}

// =====================================================================================
// Reinicia os monitores quando o celular liga / o app e atualizado
// =====================================================================================
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Prefs.init(context)
            Sys.init(context)
            NLog.init(context)
            Notif.init(context)
            NLog.add("boot", "recebi " + intent.action)
            if (Prefs.precisaServico() && License.valid()) NocryService.sync(context)
        } catch (t: Throwable) {
            NLog.add("boot", "erro: $t")
        }
    }
}

// =====================================================================================
// Pareamento pela notificacao: voce digita o codigo SEM sair da tela "Parear com codigo" do Android
// =====================================================================================
object Pairing {
    const val KEY = "codigo"
    @Volatile var porta: Int = -1
    private var mdns: AdbMdns? = null

    fun iniciar(ctx: Context) {
        val app = ctx.applicationContext
        parar()
        porta = -1
        val m = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING, AdbMdns.OnAdbDaemonDiscoveredListener { _, port ->
            if (port > 0) {
                porta = port
                NLog.add("pareamento", "porta de pareamento encontrada: $port")
                posta(app, "Digite o codigo de 6 digitos que aparece em \"Parear com codigo\"")
            }
        })
        mdns = m
        m.start()
        posta(app, "Abra Opcoes do desenvolvedor > Depuracao sem fio > Parear com codigo")
    }

    fun parar() {
        try { mdns?.stop() } catch (_: Throwable) {}
        mdns = null
    }

    fun posta(app: Context, msg: String, comCampo: Boolean = true) {
        try {
            val nb = Notification.Builder(app, Notif.CH_PAIR)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("NOCRY · PAREAMENTO")
                .setContentText(msg)
                .setOnlyAlertOnce(true)
            if (comCampo && porta > 0) {
                val ri = RemoteInput.Builder(KEY).setLabel("Codigo de pareamento").build()
                val pi = PendingIntent.getBroadcast(
                    app, 1, Intent(app, PairReceiver::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                val ac = Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_stat), "DIGITAR CODIGO", pi)
                    .addRemoteInput(ri)
                    .build()
                nb.addAction(ac)
            }
            app.getSystemService(NotificationManager::class.java)?.notify(Notif.ID_PAIR, nb.build())
        } catch (_: Exception) {
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val res = RemoteInput.getResultsFromIntent(intent)
        val code = res?.getCharSequence(Pairing.KEY)?.toString()?.filter { it.isDigit() } ?: ""
        val app = context.applicationContext
        val pr = goAsync()
        Bg.io {
            try {
                if (code.length != 6 || Pairing.porta <= 0) {
                    Pairing.posta(app, "Codigo precisa ter 6 numeros. Toque de novo em DIGITAR CODIGO.")
                } else {
                    val ok = Adb.pair(app, Pairing.porta, code)
                    NLog.add("pareamento", "pareou=$ok ${Adb.lastError}")
                    if (ok) {
                        Pairing.parar()
                        Adb.connect(app, 8000)
                        Sys.darPermissoes()
                        Sys.ligarAcessibilidade()
                        app.getSystemService(NotificationManager::class.java)?.cancel(Notif.ID_PAIR)
                        Notif.say(app, "PAREADO! Abra o NOCRY e escolha o que ligar")
                    } else {
                        Pairing.posta(app, "Nao pareou (" + Adb.lastError + "). Veja o codigo novo e tente de novo.")
                    }
                }
            } finally {
                pr.finish()
            }
        }
    }
}
