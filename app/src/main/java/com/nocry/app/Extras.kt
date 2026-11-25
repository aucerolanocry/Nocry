package com.nocry.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================================
// Conversa com o servidor pros extras: aviso do dono, suporte, eventos e amigos
// =====================================================================================
object Comunidade {
    private const val ID_SUPORTE = 7101
    private const val ID_EVENTO_NOVO = 7102
    private const val ID_TOQUE = 7103

    /** aviso do dono que está no ar agora (null = nenhum) */
    @Volatile var aviso: Pair<Long, String>? = null
    /** respostas da staff que a pessoa ainda não leu */
    @Volatile var supNovas = 0
    @Volatile var evMax = 0L

    /** algo mudou: a tela inicial redesenha */
    @Volatile var versao = 0

    fun avisoParaMostrar(): Pair<Long, String>? {
        val a = aviso ?: return null
        return if (Prefs.extraLong("aviso_visto") == a.first) null else a
    }

    fun dispensaAviso() {
        aviso?.let { Prefs.extraLong("aviso_visto", it.first) }
        versao++
    }

    /** o que o ping devolveu (chamado pela Presenca a cada minuto) */
    fun doPing(c: Context, j: JSONObject) {
        val app = c.applicationContext
        val antes = "${aviso?.first}|$supNovas|$evMax"
        val av = j.optJSONObject("aviso")
        aviso = if (av != null && av.optString("texto").isNotBlank()) Pair(av.optLong("id"), av.optString("texto")) else null
        val sup = j.optInt("sup", 0)
        if (sup > supNovas && sup > 0 && !Presenca.naTela) notifica(app, ID_SUPORTE, "A staff respondeu", "Toque pra ver a resposta no NOCRY", "ajuda")
        supNovas = sup
        val ev = j.optLong("evmax", 0L)
        val visto = Prefs.extraLong("ev_visto")
        if (ev > 0 && visto == 0L) Prefs.extraLong("ev_visto", ev)            // primeira vez: só guarda, não avisa do que já existia
        else if (ev > visto) {
            Prefs.extraLong("ev_visto", ev)
            notifica(app, ID_EVENTO_NOVO, "Evento novo no NOCRY", "Toque pra ver e participar", "eventos")
        }
        evMax = ev
        val tq = j.optJSONArray("toques")
        if (tq != null) for (i in 0 until tq.length()) {
            val quem = tq.optString(i)
            if (quem.isNotBlank()) notifica(app, ID_TOQUE + i, "$quem te chamou pra jogar!", "Bora? Abra o Free Fire e chama ele.", "amigos")
        }
        if (antes != "${aviso?.first}|$supNovas|$evMax") { versao++; Bg.ui { Engine.onChange?.invoke() } }
    }

    fun notifica(c: Context, id: Int, titulo: String, texto: String, abrir: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val it = Intent(c, MainActivity::class.java).putExtra("abrir", abrir).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(c, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, Notif.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (_: Throwable) {}
    }

    /** pedido pro servidor (bloqueia: chame fora da tela). null = sem internet */
    fun x(c: Context, op: String, extra: JSONObject = JSONObject()): JSONObject? {
        val key = Prefs.licKey ?: return null
        extra.put("key", key).put("dev", License.devId(c)).put("app", License.versao(c)).put("op", op)
        return License.post("/v1/x", extra)
    }

    /** texto simples pro motivo do servidor */
    fun motivo(j: JSONObject?): String = when (j?.optString("reason")) {
        null -> "Sem internet agora. Tente de novo."
        "muitas" -> "Muitas mensagens hoje. Espere um pouco."
        "nao_achou" -> "Não achei ninguém com esse código."
        "voce" -> "Esse é o seu próprio código."
        "lotado" -> "As vagas acabaram."
        "passou" -> "Esse evento já passou."
        "espera" -> "Você acabou de chamar. Espere 1 minuto."
        "apelido" -> "O apelido precisa ter pelo menos 2 letras."
        "manutencao" -> "O app está em manutenção."
        else -> "Não deu certo (" + (j.optString("reason")) + ")."
    }
}

// =====================================================================================
// Diagnóstico com 1 toque: testa tudo e explica o que fazer
// =====================================================================================
object Diag {
    class Item(val id: String, val nome: String, val ok: Boolean, val txt: String, val dica: String)

    /** demora alguns segundos (testa o ADB de verdade): chame fora da tela */
    fun rodar(c: Context): List<Item> {
        val app = c.applicationContext
        val r = ArrayList<Item>()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = try { cm?.let { it.getNetworkCapabilities(it.activeNetwork) } } catch (_: Throwable) { null }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        r.add(Item("internet", "Internet", internet, if (internet) "ok" else "sem internet",
            "Sem internet o app não confere a key nem atualiza. Ligue o Wi-Fi ou os dados."))
        r.add(Item("wifi", "Wi-Fi", wifi, if (wifi) "conectado" else "fora do Wi-Fi",
            "A depuração sem fio só funciona conectado num Wi-Fi."))
        if (Build.VERSION.SDK_INT >= 30) {
            val dep = try { Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (_: Throwable) { false }
            r.add(Item("depuracao", "Depuração sem fio", dep, if (dep) "ligada" else "desligada",
                "Ligue em Configurações › Opções do desenvolvedor › Depuração sem fio. Na Conexão do app tem o passo a passo."))
        }
        val adb = try { Adb.ensure(app) } catch (_: Throwable) { false }
        r.add(Item("adb", "ADB", adb, if (adb) "conectado" else "desconectado",
            if (Adb.needsPairing) "Precisa parear de novo: abra a Conexão e siga o passo de parear." else "Abra a Conexão e toque em conectar."))
        val perm = Sys.hasWriteSecure()
        r.add(Item("perm", "Permissões", perm, if (perm) "liberadas" else "faltando",
            "Com o ADB conectado, abra a Conexão e toque em liberar permissões."))
        val acc = Sys.acessibilidadeLigada()
        r.add(Item("acess", "Acessibilidade", acc, if (acc) "ligada" else "desligada",
            "Ela é que percebe o volume e o Free Fire abrindo. Ligue no passo da Conexão."))
        val notif = try { app.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true } catch (_: Throwable) { false }
        r.add(Item("notif", "Notificações", notif, if (notif) "ligadas" else "bloqueadas",
            "Sem notificação você não vê os avisos. Libere em Configurações › Apps › Nocry › Notificações."))
        val bat = try { app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(Const.PKG) == true } catch (_: Throwable) { false }
        r.add(Item("bateria", "Economia de bateria", bat, if (bat) "liberado" else "o celular pode fechar o app",
            "Tire o Nocry da economia de bateria em Configurações › Apps › Nocry › Bateria › Sem restrições."))
        val atual = Update.pendenteReal(app) == null
        r.add(Item("versao", "Versão", atual, License.versao(app) + if (atual) "" else " (tem nova)",
            "Atualize o app no Sobre."))
        val erros = try { Erros.novos() } catch (_: Throwable) { 0 }
        r.add(Item("erros", "Erros", erros == 0, if (erros == 0) "nenhum novo" else "$erros novos",
            "Os erros ficam na aba Logs. A staff consegue ver pelo teste."))
        return r
    }

    /** manda o resultado pro painel da staff */
    fun enviar(c: Context, itens: List<Item>): Boolean {
        val arr = JSONArray()
        for (i in itens) arr.put(JSONObject().put("id", i.id).put("ok", i.ok).put("txt", i.txt))
        val modelo = (Build.MANUFACTURER + " " + Build.MODEL).take(40)
        val dados = JSONObject().put("itens", arr).put("aparelho", modelo).put("android", Build.VERSION.RELEASE)
        return Comunidade.x(c, "diag", JSONObject().put("dados", dados))?.optBoolean("ok") == true
    }
}

// =====================================================================================
// Relatório da partida: percebe o Free Fire abrindo/fechando (pela acessibilidade)
// =====================================================================================
object Partida {
    private const val ID_NOTIF = 7201
    private val ignorar = setOf("com.android.systemui", Const.PKG)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var jogando = false
        private set
    private var inicio = 0L
    private var batIni = -1
    private var batMin = -1
    private var tempMax = 0f
    private var memBaixa = false
    private var memMin = Long.MAX_VALUE
    private var pacote = ""

    private val amostra = object : Runnable {
        override fun run() {
            if (!jogando) return
            mede()
            main.postDelayed(this, 30_000)
        }
    }
    private val confereSaida = Runnable { if (jogando && !ffNaFrente) termina() }
    @Volatile private var ffNaFrente = false

    private fun ehFf(p: String) = p.startsWith("com.dts.freefire")

    /** cada troca de janela (a acessibilidade avisa): só olha se é o Free Fire ou não. Nada é guardado */
    fun janela(pkg: String?) {
        val p = pkg ?: return
        if (ehFf(p)) {
            ffNaFrente = true
            main.removeCallbacks(confereSaida)
            if (!jogando) comeca(p)
        } else if (p !in ignorar && !p.contains("inputmethod") && !p.contains("keyboard")) {
            ffNaFrente = false
            // espera um pouco: notificação, teclado e janelinhas também trocam a janela
            if (jogando) { main.removeCallbacks(confereSaida); main.postDelayed(confereSaida, 8_000) }
        }
    }

    private fun bateria(): Pair<Int, Float> {
        return try {
            val i = Sys.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val nivel = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val esc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            Pair(if (nivel >= 0 && esc > 0) nivel * 100 / esc else -1, t)
        } catch (_: Throwable) { Pair(-1, 0f) }
    }

    private fun mede() {
        val (b, t) = bateria()
        if (b >= 0) batMin = if (batMin < 0) b else minOf(batMin, b)
        if (t > tempMax) tempMax = t
        try {
            val mi = ActivityManager.MemoryInfo()
            Sys.app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
            if (mi.lowMemory) memBaixa = true
            if (mi.availMem in 1 until memMin) memMin = mi.availMem
        } catch (_: Throwable) {}
    }

    private fun comeca(p: String) {
        jogando = true
        pacote = p
        inicio = System.currentTimeMillis()
        batIni = bateria().first
        batMin = batIni
        tempMax = 0f
        memBaixa = false
        memMin = Long.MAX_VALUE
        mede()
        main.removeCallbacks(amostra)
        main.postDelayed(amostra, 30_000)
        NLog.add("partida", "Free Fire abriu")
        Comunidade.versao++
    }

    private fun termina() {
        if (!jogando) return
        mede()
        jogando = false
        main.removeCallbacks(amostra)
        val dur = System.currentTimeMillis() - inicio
        NLog.add("partida", "Free Fire fechou (" + (dur / 60000) + " min)")
        Comunidade.versao++
        if (dur < 2 * 60_000) return            // abriu e fechou rápido: não vale relatório
        val o = JSONObject()
            .put("t", inicio).put("dur", dur).put("ff", if (pacote.endsWith("max")) "max" else "normal")
            .put("b0", batIni).put("b1", batMin).put("temp", tempMax.toDouble())
            .put("memBaixa", memBaixa).put("memMb", if (memMin == Long.MAX_VALUE) -1L else memMin / (1024 * 1024))
        val lista = lista()
        lista.put(o)
        val final = if (lista.length() > 30) JSONArray().also { n -> for (i in lista.length() - 30 until lista.length()) n.put(lista.get(i)) } else lista
        Prefs.extraTxt("partidas", final.toString())
        if (Prefs.extraLong("partida_notif", 1L) == 1L) {
            Comunidade.notifica(Sys.app, ID_NOTIF, "Partida encerrada · " + (dur / 60000) + " min", resumo(o), "partidas")
        }
    }

    fun lista(): JSONArray = try { JSONArray(Prefs.extraTxt("partidas") ?: "[]") } catch (_: Throwable) { JSONArray() }

    fun resumo(o: JSONObject): String {
        val b0 = o.optInt("b0", -1)
        val b1 = o.optInt("b1", -1)
        val p = ArrayList<String>()
        if (b0 >= 0 && b1 >= 0) p.add("Bateria $b0% → $b1% (−${b0 - b1}%)")
        val t = o.optDouble("temp", 0.0)
        if (t > 0) p.add("Calor máx. ${t.toInt()}°C" + if (t >= 43) " ⚠ alto" else "")
        p.add(if (o.optBoolean("memBaixa")) "Memória apertada" else "Memória ok")
        return p.joinToString(" · ")
    }
}

// =====================================================================================
// Caderninho das apostas: cada aposta confirmada do bot fica anotada (só no celular)
// =====================================================================================
object Caderno {
    class Item(val id: String, val t: Long, val org: String, val fila: String, val valor: Double, val res: String)

    private val trava = Any()

    private fun ler(): JSONArray = try { JSONArray(Prefs.extraTxt("caderno") ?: "[]") } catch (_: Throwable) { JSONArray() }

    fun valorDe(fila: String): Double {
        val m = Regex("R\\$\\s*([0-9]+(?:[.,][0-9]{1,2})?)").find(fila) ?: return 0.0
        return m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
    }

    /** o bot viu "aposta confirmada" numa sala */
    fun anota(id: String, org: String, fila: String) {
        synchronized(trava) {
            val a = ler()
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == id) return
            a.put(JSONObject().put("id", id).put("t", System.currentTimeMillis()).put("org", org).put("fila", fila)
                .put("v", valorDe(fila)).put("r", ""))
            val final = if (a.length() > 500) JSONArray().also { n -> for (i in a.length() - 500 until a.length()) n.put(a.get(i)) } else a
            Prefs.extraTxt("caderno", final.toString())
        }
        Comunidade.versao++
    }

    fun itens(): List<Item> {
        val a = ler()
        val r = ArrayList<Item>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            r.add(Item(o.optString("id"), o.optLong("t"), o.optString("org"), o.optString("fila"), o.optDouble("v", 0.0), o.optString("r")))
        }
        return r.sortedByDescending { it.t }
    }

    /** "g" ganhou, "p" perdeu, "" sem resultado; "x" apaga */
    fun marca(id: String, res: String) {
        synchronized(trava) {
            val a = ler()
            val n = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("id") == id) { if (res == "x") continue; o.put("r", res) }
                n.put(o)
            }
            Prefs.extraTxt("caderno", n.toString())
        }
        Comunidade.versao++
    }

    class Resumo(val salas: Int, val ganhou: Int, val perdeu: Int, val saldo: Double, val maisJogada: String)

    fun resumo(desde: Long): Resumo {
        val l = itens().filter { it.t >= desde }
        val g = l.filter { it.res == "g" }
        val p = l.filter { it.res == "p" }
        val saldo = g.sumOf { it.valor } - p.sumOf { it.valor }
        val mais = l.groupingBy { it.org }.eachCount().maxByOrNull { it.value }?.let { "${it.key} (${it.value}x)" } ?: "—"
        return Resumo(l.size, g.size, p.size, saldo, mais)
    }

    fun inicioDoDia(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun inicioDaSemana(): Long = Calendar.getInstance().apply { timeInMillis = inicioDoDia(); set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis
    fun inicioDoMes(): Long = Calendar.getInstance().apply { timeInMillis = inicioDoDia(); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    fun dinheiro(v: Double): String = (if (v < 0) "−" else if (v > 0) "+" else "") + "R$ " + String.format(Locale("pt", "BR"), "%.2f", kotlin.math.abs(v))
}

// =====================================================================================
// Conquistas: medalhas tiradas do que a pessoa já fez (tudo calculado no celular)
// =====================================================================================
object Conquistas {
    class C(val id: String, val nome: String, val desc: String, val tem: Boolean)

    /** marca o dia de hoje como "usou o app" (pra conta dos dias seguidos) */
    fun marcaDia() {
        val hoje = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val dias = (Prefs.extraTxt("dias") ?: "").split(',').filter { it.length == 8 }.toMutableList()
        if (dias.lastOrNull() == hoje) return
        dias.add(hoje)
        Prefs.extraTxt("dias", dias.takeLast(60).joinToString(","))
    }

    fun diasSeguidos(): Int {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dias = (Prefs.extraTxt("dias") ?: "").split(',').filter { it.length == 8 }.toSet()
        val cal = Calendar.getInstance()
        var n = 0
        while (f.format(cal.time) in dias) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    fun todas(): List<C> {
        val cad = Caderno.itens()
        val ganhas = cad.count { it.res == "g" }
        val partidas = Partida.lista().length()
        val seguidos = diasSeguidos()
        val toque = Prefs.extraLong("toque_melhor_media", 0L)
        return listOf(
            C("aposta1", "Primeira aposta", "O bot pegou sua primeira aposta", cad.isNotEmpty()),
            C("ganhou10", "10 vitórias", "Marcou 10 salas como ganhas", ganhas >= 10),
            C("salas50", "50 salas", "Jogou 50 salas pelo bot", cad.size >= 50),
            C("partida1", "Primeira partida", "Fechou o Free Fire com relatório", partidas >= 1),
            C("partidas20", "Viciado", "20 partidas com relatório", partidas >= 20),
            C("dias7", "7 dias seguidos", "Abriu o app 7 dias seguidos", seguidos >= 7),
            C("evento1", "Primeiro evento", "Se inscreveu num evento", Prefs.extraLong("ev_inscreveu") == 1L),
            C("amigo1", "Tem parceiro", "Fez a primeira amizade no app", Prefs.extraLong("amigos_n") >= 1),
            C("reflexo", "Reflexo rápido", "Média abaixo de 300 ms no teste de toque", toque in 1..299)
        )
    }

    /** ids das que já tem (vai pro perfil dos amigos) */
    fun codigo(): String = todas().filter { it.tem }.joinToString(",") { it.id }
}

// =====================================================================================
// Lembrete de evento (alarme do Android dispara mesmo com o app fechado)
// =====================================================================================
class EventoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Prefs.init(context)
            Notif.init(context)
            val nome = intent.getStringExtra("nome") ?: "Evento"
            val falta = intent.getIntExtra("falta", 0)
            Comunidade.notifica(context.applicationContext, 7300 + intent.getIntExtra("id", 0) % 500,
                if (falta > 0) "$nome começa em $falta min" else "$nome começou!", "Toque pra abrir o NOCRY", "eventos")
        } catch (_: Throwable) {}
    }

    companion object {
        fun agenda(c: Context, id: Int, nome: String, quando: Long, ligar: Boolean) {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            for ((i, falta) in listOf(15, 0).withIndex()) {
                val it = Intent(c, EventoReceiver::class.java).putExtra("id", id).putExtra("nome", nome).putExtra("falta", falta)
                val pi = PendingIntent.getBroadcast(c, 50_000 + id * 2 + i, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val hora = quando - falta * 60_000L
                if (!ligar || hora < System.currentTimeMillis()) { am.cancel(pi); continue }
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, hora, pi) } catch (_: Throwable) {}
            }
        }
    }
}

// =====================================================================================
// Bloco "NOCRY" no menu rápido do Android: liga/desliga o modo competitivo
// =====================================================================================
class CompTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        atualiza()
    }

    private fun atualiza() {
        val t = qsTile ?: return
        val on = try { Comp.ligado() } catch (_: Throwable) { false }
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "NOCRY"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Competitivo" else "Desligado"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun abreAntigo(it: Intent) = startActivityAndCollapse(it)

    override fun onClick() {
        super.onClick()
        try { Prefs.init(this); Sys.init(this) } catch (_: Throwable) {}
        if (!License.valid() || !Toque.podeMexer()) {
            // sem key ou sem conexão feita: abre o app pra resolver
            val it = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE))
                else abreAntigo(it)
            } catch (_: Throwable) {}
            return
        }
        val ligar = !Comp.ligado()
        qsTile?.let { it.state = Tile.STATE_UNAVAILABLE; it.updateTile() }
        Bg.io {
            val ok = try { if (ligar) Comp.ativar() else Comp.desativar(true) } catch (_: Throwable) { false }
            Bg.ui {
                atualiza()
                Notif.evento(this, if (ligar) "Modo competitivo" else "Modo competitivo", if (!ok) "O aparelho não aceitou a mudança" else if (ligar) "Ligado pelo menu rápido" else "Desligado: tudo voltou ao normal",
                    if (!ok) Notif.Efeito.FALHOU else if (ligar) Notif.Efeito.LIGOU else Notif.Efeito.DESLIGOU)
            }
        }
    }
}

// =====================================================================================
// TELAS
// =====================================================================================

/** linha de valor simples (rótulo à esquerda, valor à direita) */
private fun linhaValor(c: Context, rotulo: String, valor: String, cor: Int = Ui.TEXT): View {
    val r = Ui.hbox(c)
    r.gravity = Gravity.CENTER_VERTICAL
    r.addView(Ui.text(c, rotulo, 12f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    r.addView(Ui.text(c, valor, 13f, cor, true))
    return r
}

private fun dataCurta(t: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(t))

// ---------------------------------------------------------------- AJUDA (diagnóstico + suporte)
class AjudaScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Ajuda"
    private var testando = false
    private var resultado: List<Diag.Item>? = null
    private var enviado = false
    private var msgs: JSONArray? = null
    private var enviando = false
    private var carregou = false
    private var ultimaBusca = 0L

    override fun key(): String = "$testando|${resultado?.size}|$enviado|${msgs?.length()}|$enviando|${Comunidade.supNovas}"

    override fun aoMostrar() { super.aoMostrar(); busca() }

    private fun busca() {
        ultimaBusca = SystemClock.elapsedRealtime()
        Bg.io {
            val j = Comunidade.x(act, "sup_ler")
            Bg.ui {
                carregou = true
                if (j?.optBoolean("ok") == true) { msgs = j.optJSONArray("msgs"); Comunidade.supNovas = 0 }
                redesenha()
            }
        }
    }

    override fun leve() {
        if (SystemClock.elapsedRealtime() - ultimaBusca > 12_000) busca()
    }

    override fun render() {
        limpa()
        val c = act
        cabecalho(titulo, "Teste o app com 1 toque ou fale direto com a staff. O resultado do teste vai junto, assim ninguém precisa ficar perguntando o que aconteceu.")

        // ---- testar
        add(Ui.secao(c, "testar meu app"), 18)
        val r = resultado
        if (r == null) {
            add(Ui.button(c, if (testando) "testando..." else "testar meu app", Ui.Kind.PRIMARY, R.drawable.ic_check) { testar() }, 10)
        } else {
            val ruins = r.filter { !it.ok }
            add(Ui.dica(c, if (ruins.isEmpty()) "Tudo certo! O app está pronto pra usar." else "Achei ${ruins.size} coisa(s) pra arrumar. Veja abaixo o que fazer.",
                if (ruins.isEmpty()) Ui.Dica.OK else Ui.Dica.AVISO), 10)
            val card = Ui.card(c)
            for ((i, it) in r.withIndex()) {
                card.addView(linhaValor(c, it.nome, (if (it.ok) "✓ " else "✗ ") + it.txt, if (it.ok) Ui.OK else Ui.BAD), Ui.lp(top = if (i == 0) 0 else 8, c = c))
                if (!it.ok) card.addView(Ui.text(c, it.dica, 11f, Ui.MUTED), Ui.lp(top = 3, c = c))
            }
            add(card, 10)
            add(Ui.text(c, if (enviado) "O resultado foi pra staff." else "Não consegui mandar o resultado pra staff (sem internet).", 11f, Ui.MUTED), 8)
            add(Ui.button(c, if (testando) "testando..." else "testar de novo", Ui.Kind.GHOST, R.drawable.ic_check) { testar() }, 8)
        }

        // ---- falar com a staff
        add(Ui.secao(c, "falar com a staff"), 24)
        val m = msgs
        if (!carregou) add(Ui.text(c, "Carregando...", 12f, Ui.MUTED), 10)
        else if (m == null) add(Ui.dica(c, "Sem internet agora. As mensagens aparecem quando voltar.", Ui.Dica.AVISO), 10)
        else if (m.length() == 0) add(Ui.dica(c, "Escreva o que está acontecendo. A resposta aparece aqui e chega uma notificação.", Ui.Dica.INFO), 10)
        else for (i in 0 until m.length()) {
            val o = m.optJSONObject(i) ?: continue
            val staff = o.optString("de") == "staff"
            val b = Ui.card(c, forte = staff)
            b.addView(Ui.text(c, (if (staff) "Staff" + o.optString("autor").let { if (it.isNotBlank()) " · $it" else "" } else "Você") + " · " + dataCurta(o.optLong("t")), 10.5f, Ui.MUTED, mono = true))
            b.addView(Ui.text(c, o.optString("texto"), 13.5f, Ui.TEXT), Ui.lp(top = 4, c = c))
            val lp = Ui.lp(top = 8, c = c)
            if (staff) lp.rightMargin = Ui.dp(c, 36) else lp.leftMargin = Ui.dp(c, 36)
            root.addView(b, lp)
        }
        val campo = Ui.field(c, "escreva sua mensagem", false)
        campo.maxLines = 5
        add(campo, 12)
        add(Ui.button(c, if (enviando) "enviando..." else "enviar", Ui.Kind.PRIMARY, R.drawable.ic_up) {
            val t = campo.text.toString().trim()
            if (t.isEmpty() || enviando) return@button
            enviando = true
            redesenha()
            Bg.io {
                val j = Comunidade.x(act, "sup_enviar", JSONObject().put("texto", t))
                // se ainda não testou, manda o teste junto (a staff já vê o que está errado)
                if (j?.optBoolean("ok") == true && resultado == null) try { Diag.enviar(act, Diag.rodar(act)) } catch (_: Throwable) {}
                Bg.ui {
                    enviando = false
                    if (j?.optBoolean("ok") == true) { Ui.toast(act, "MENSAGEM ENVIADA"); busca() } else Ui.toast(act, Comunidade.motivo(j))
                    redesenha()
                }
            }
        }, 8)
    }

    private fun testar() {
        if (testando) return
        testando = true
        redesenha()
        Bg.io {
            val r = try { Diag.rodar(act) } catch (_: Throwable) { emptyList() }
            val ok = try { Diag.enviar(act, r) } catch (_: Throwable) { false }
            Bg.ui { testando = false; resultado = r; enviado = ok; redesenha() }
        }
    }
}

// ---------------------------------------------------------------- CADERNINHO
class CadernoScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Caderninho"
    private var periodo = 0

    override fun key(): String = "$periodo|${Comunidade.versao}"

    override fun render() {
        limpa()
        val c = act
        cabecalho(titulo, "Toda aposta confirmada pelo bot fica anotada aqui sozinha. Marque se ganhou ou perdeu pra ver seu saldo. Fica só no seu celular.")
        add(Ui.segmento(c, listOf("Hoje", "Semana", "Mês", "Tudo"), periodo) { periodo = it; redesenha() }, 14)
        val desde = when (periodo) { 0 -> Caderno.inicioDoDia(); 1 -> Caderno.inicioDaSemana(); 2 -> Caderno.inicioDoMes(); else -> 0L }
        val r = Caderno.resumo(desde)
        val card = Ui.card(c, forte = true)
        card.addView(Ui.text(c, "SALDO", 10.5f, Ui.MUTED, true, mono = true))
        card.addView(Ui.text(c, Caderno.dinheiro(r.saldo), 30f, if (r.saldo > 0) Ui.OK else if (r.saldo < 0) Ui.BAD else Ui.TEXT, true), Ui.lp(top = 2, c = c))
        card.addView(linhaValor(c, "Salas", "${r.salas}"), Ui.lp(top = 10, c = c))
        card.addView(linhaValor(c, "Ganhou", "${r.ganhou}", Ui.OK), Ui.lp(top = 6, c = c))
        card.addView(linhaValor(c, "Perdeu", "${r.perdeu}", Ui.BAD), Ui.lp(top = 6, c = c))
        card.addView(linhaValor(c, "Sem resultado", "${r.salas - r.ganhou - r.perdeu}"), Ui.lp(top = 6, c = c))
        card.addView(linhaValor(c, "Org mais jogada", r.maisJogada), Ui.lp(top = 6, c = c))
        add(card, 12)
        add(Ui.text(c, "O saldo conta o valor da aposta: ganhou soma, perdeu tira.", 10.5f, Ui.MUTED), 6)

        val itens = Caderno.itens().filter { it.t >= desde }
        add(Ui.secao(c, "salas"), 20)
        if (itens.isEmpty()) {
            add(Ui.dica(c, "Nenhuma aposta nesse período. Quando o bot pegar uma sala e sair \"aposta confirmada\", ela aparece aqui.", Ui.Dica.INFO), 10)
            return
        }
        for (s in itens.take(100)) {
            val k = Ui.card(c)
            val topo = Ui.hbox(c)
            topo.gravity = Gravity.CENTER_VERTICAL
            val col = Ui.vbox(c)
            col.addView(Ui.text(c, s.org.ifBlank { "Sala" }, 14f, Ui.TEXT, true))
            col.addView(Ui.text(c, listOf(s.fila, dataCurta(s.t)).filter { x -> x.isNotBlank() }.joinToString(" · "), 11f, Ui.MUTED), Ui.lp(top = 2, c = c))
            topo.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val est = when (s.res) { "g" -> "GANHOU"; "p" -> "PERDEU"; else -> "?" }
            topo.addView(Ui.pilulaEstado(c, est, when (s.res) { "g" -> true; "p" -> false; else -> null }))
            k.addView(topo)
            val bt = Ui.hbox(c)
            val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.leftMargin = Ui.dp(c, 8) }
            bt.addView(Ui.button(c, "ganhei", if (s.res == "g") Ui.Kind.PRIMARY else Ui.Kind.GHOST) { Caderno.marca(s.id, if (s.res == "g") "" else "g") }, l1)
            bt.addView(Ui.button(c, "perdi", if (s.res == "p") Ui.Kind.DANGER else Ui.Kind.GHOST) { Caderno.marca(s.id, if (s.res == "p") "" else "p") }, l2)
            k.addView(bt, Ui.lp(top = 10, c = c))
            k.setOnLongClickListener {
                Ui.dialogConfirma(act, "APAGAR ESSA SALA?", "Ela sai do caderninho.", "APAGAR") { Caderno.marca(s.id, "x") }
                true
            }
            add(k, 8)
        }
        add(Ui.text(c, "Segure numa sala pra apagar.", 10.5f, Ui.MUTED), 8)
    }
}

// ---------------------------------------------------------------- PARTIDAS (relatórios)
class PartidasScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Partidas"
    override fun key(): String = "${Comunidade.versao}|${Partida.jogando}|${Sys.acessibilidadeLigada()}|${Prefs.extraLong("partida_notif", 1L)}"

    override fun render() {
        limpa()
        val c = act
        cabecalho(titulo, "Quando você fecha o Free Fire, o app mostra quanto tempo jogou, quanto a bateria caiu, o calor e a memória. Partidas com menos de 2 minutos não contam.")
        if (!Sys.acessibilidadeLigada()) {
            add(Ui.dica(c, "Ligue a acessibilidade do NOCRY (passo na Conexão). É ela que percebe quando o Free Fire abre e fecha.", Ui.Dica.AVISO), 12)
        }
        if (Partida.jogando) add(Ui.dica(c, "Free Fire aberto agora: o relatório sai quando você fechar.", Ui.Dica.OK), 12)
        add(Ui.toggle(c, "Avisar no fim da partida", "Notificação com o resumo quando fechar o jogo", Prefs.extraLong("partida_notif", 1L) == 1L) {
            Prefs.extraLong("partida_notif", if (it) 1L else 0L); redesenha()
        }, 12)
        val l = Partida.lista()
        add(Ui.secao(c, "últimas partidas"), 20)
        if (l.length() == 0) { add(Ui.dica(c, "Nenhuma partida ainda. Jogue um pouco e feche o Free Fire.", Ui.Dica.INFO), 10); return }
        for (i in l.length() - 1 downTo 0) {
            val o = l.optJSONObject(i) ?: continue
            val k = Ui.card(c)
            k.addView(Ui.text(c, (if (o.optString("ff") == "max") "FF MAX" else "FF NORMAL") + " · " + dataCurta(o.optLong("t")), 10.5f, Ui.MUTED, mono = true))
            k.addView(Ui.text(c, "${o.optLong("dur") / 60000} min de jogo", 16f, Ui.TEXT, true), Ui.lp(top = 4, c = c))
            val b0 = o.optInt("b0", -1); val b1 = o.optInt("b1", -1)
            if (b0 >= 0 && b1 >= 0) k.addView(linhaValor(c, "Bateria", "$b0% → $b1% (−${b0 - b1}%)", if (b0 - b1 >= 25) Ui.WARN else Ui.TEXT), Ui.lp(top = 8, c = c))
            val t = o.optDouble("temp", 0.0)
            if (t > 0) k.addView(linhaValor(c, "Calor máximo", "${t.toInt()}°C" + if (t >= 43) " · alto" else "", if (t >= 43) Ui.BAD else Ui.TEXT), Ui.lp(top = 6, c = c))
            k.addView(linhaValor(c, "Memória", if (o.optBoolean("memBaixa")) "apertada" else "ok" + o.optLong("memMb", -1).let { m -> if (m > 0) " (mín. $m MB livres)" else "" },
                if (o.optBoolean("memBaixa")) Ui.WARN else Ui.TEXT), Ui.lp(top = 6, c = c))
            add(k, 8)
        }
    }
}

// ---------------------------------------------------------------- TESTE DE TOQUE
class TesteToqueScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Teste de toque"
    private var rodando = false

    override fun key(): String = "$rodando|${Prefs.extraTxt("toque_res")}"

    private inner class Alvo(c: Context) : View(c) {
        var total = 20
        var feitos = 0
        val tempos = ArrayList<Long>()
        private var x = 0f
        private var y = 0f
        private var mostrouEm = 0L
        private var visivel = false
        private val raio = Ui.dp(c, 30).toFloat()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fim = Handler(Looper.getMainLooper())
        var aoTerminar: ((List<Long>) -> Unit)? = null

        fun comeca() { feitos = 0; tempos.clear(); proximo() }

        private fun proximo() {
            visivel = false
            invalidate()
            // espera um tempinho aleatório pra ninguém tocar antes de aparecer
            fim.postDelayed({
                if (width <= 0) return@postDelayed
                x = raio + Math.random().toFloat() * (width - 2 * raio)
                y = raio + Math.random().toFloat() * (height - 2 * raio)
                visivel = true
                invalidate()
                // o tempo conta do quadro em que o alvo apareceu na tela
                Choreographer.getInstance().postFrameCallback { t -> mostrouEm = t / 1_000_000L }
            }, 400L + (Math.random() * 900).toLong())
        }

        override fun onDraw(cv: Canvas) {
            super.onDraw(cv)
            if (!visivel) return
            p.shader = RadialGradient(x, y, raio, Theme.accent, Theme.alpha(Theme.accent, 0x30), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, raio, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            if (!visivel || mostrouEm == 0L) return true
            val dx = e.x - x; val dy = e.y - y
            if (dx * dx + dy * dy > raio * raio * 2.2f) return true
            tempos.add((e.eventTime - mostrouEm).coerceAtLeast(0))
            mostrouEm = 0L
            feitos++
            if (feitos >= total) { visivel = false; invalidate(); aoTerminar?.invoke(tempos.toList()) } else proximo()
            return true
        }
    }

    override fun render() {
        limpa()
        val c = act
        cabecalho(titulo, "Toque nos 20 alvos o mais rápido que conseguir. O app mede o tempo entre o alvo aparecer e o seu toque chegar. Faça com o competitivo ligado e desligado e compare.")
        val comp = try { Comp.ligado() } catch (_: Throwable) { false }
        add(Ui.dica(c, "Modo competitivo agora: " + if (comp) "LIGADO" else "DESLIGADO", if (comp) Ui.Dica.OK else Ui.Dica.INFO), 12)
        if (rodando) {
            val alvo = Alvo(c)
            alvo.background = Ui.glass(c)
            alvo.aoTerminar = { t -> salva(t, comp); rodando = false; redesenha() }
            root.addView(alvo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 420)).also { it.topMargin = Ui.dp(c, 12) })
            alvo.post { alvo.comeca() }
            add(Ui.button(c, "cancelar", Ui.Kind.GHOST) { rodando = false; redesenha() }, 10)
            return
        }
        add(Ui.button(c, "começar", Ui.Kind.PRIMARY, R.drawable.ic_play) { rodando = true; redesenha() }, 12)
        val res = try { JSONArray(Prefs.extraTxt("toque_res") ?: "[]") } catch (_: Throwable) { JSONArray() }
        if (res.length() == 0) return
        val com = ArrayList<Long>(); val sem = ArrayList<Long>()
        for (i in 0 until res.length()) res.optJSONObject(i)?.let { if (it.optBoolean("comp")) com.add(it.optLong("media")) else sem.add(it.optLong("media")) }
        add(Ui.secao(c, "comparação"), 20)
        val k = Ui.card(c, forte = true)
        k.addView(linhaValor(c, "Com competitivo", if (com.isEmpty()) "faça um teste" else "${com.average().toLong()} ms (média)", Ui.OK))
        k.addView(linhaValor(c, "Sem competitivo", if (sem.isEmpty()) "faça um teste" else "${sem.average().toLong()} ms (média)"), Ui.lp(top = 8, c = c))
        add(k, 10)
        add(Ui.secao(c, "últimos testes"), 20)
        for (i in res.length() - 1 downTo 0) {
            val o = res.optJSONObject(i) ?: continue
            add(linhaValor(c, dataCurta(o.optLong("t")) + if (o.optBoolean("comp")) " · competitivo" else "", "média ${o.optLong("media")} ms · melhor ${o.optLong("melhor")} ms"), 8)
        }
    }

    private fun salva(t: List<Long>, comp: Boolean) {
        if (t.isEmpty()) return
        val media = t.average().toLong()
        val res = try { JSONArray(Prefs.extraTxt("toque_res") ?: "[]") } catch (_: Throwable) { JSONArray() }
        res.put(JSONObject().put("t", System.currentTimeMillis()).put("comp", comp).put("media", media).put("melhor", t.minOrNull() ?: 0L))
        val final = if (res.length() > 10) JSONArray().also { n -> for (i in res.length() - 10 until res.length()) n.put(res.get(i)) } else res
        Prefs.extraTxt("toque_res", final.toString())
        val melhor = Prefs.extraLong("toque_melhor_media", 0L)
        if (melhor == 0L || media < melhor) Prefs.extraLong("toque_melhor_media", media)
        Ui.toast(act, "MÉDIA: $media ms")
    }
}

// ---------------------------------------------------------------- EVENTOS
class EventosScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Eventos"
    private var lista: JSONArray? = null
    private var carregou = false
    private var ocupado = false

    override fun key(): String = "$carregou|${lista?.length()}|$ocupado"
    override fun aoMostrar() { super.aoMostrar(); busca() }

    private fun busca() {
        Bg.io {
            val j = Comunidade.x(act, "ev_lista")
            Bg.ui { carregou = true; lista = if (j?.optBoolean("ok") == true) j.optJSONArray("eventos") else null; redesenha() }
        }
    }

    override fun render() {
        limpa()
        val c = act
        cabecalho(titulo, "Eventos e campeonatos criados pelo dono. Toque em participar e o app te avisa 15 minutos antes e na hora.")
        val l = lista
        if (!carregou) { add(Ui.text(c, "Carregando...", 12f, Ui.MUTED), 12); return }
        if (l == null) { add(Ui.dica(c, "Sem internet agora. Tente de novo.", Ui.Dica.AVISO), 12); add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carregou = false; redesenha(); busca() }, 8); return }
        if (l.length() == 0) { add(Ui.dica(c, "Nenhum evento marcado agora. Quando tiver, chega uma notificação.", Ui.Dica.INFO), 12); return }
        for (i in 0 until l.length()) {
            val e = l.optJSONObject(i) ?: continue
            val id = e.optInt("id")
            val quando = e.optLong("quando")
            val eu = e.optBoolean("eu")
            val vagas = e.optInt("vagas")
            val n = e.optInt("inscritos")
            val k = Ui.card(c, forte = eu)
            k.addView(Ui.text(c, e.optString("nome"), 16f, Ui.TEXT, true))
            k.addView(Ui.text(c, dataCurta(quando) + e.optString("valor").let { if (it.isNotBlank()) " · $it" else "" }, 12f, Ui.MUTED), Ui.lp(top = 3, c = c))
            k.addView(Ui.text(c, "$n inscrito(s)" + if (vagas > 0) " de $vagas vagas" else "", 11f, Ui.MUTED, mono = true), Ui.lp(top = 3, c = c))
            if (e.optString("info").isNotBlank()) k.addView(Ui.text(c, e.optString("info"), 12.5f, Ui.TEXT), Ui.lp(top = 8, c = c))
            val passou = quando < System.currentTimeMillis()
            if (!passou) k.addView(Ui.button(c, if (ocupado) "aguarde..." else if (eu) "sair do evento" else "participar", if (eu) Ui.Kind.GHOST else Ui.Kind.PRIMARY) {
                if (ocupado) return@button
                ocupado = true
                redesenha()
                Bg.io {
                    val j = Comunidade.x(act, if (eu) "ev_sair" else "ev_entrar", JSONObject().put("id", id).put("apelido", Prefs.extraTxt("am_apelido") ?: Prefs.apelido))
                    val ok = j?.optBoolean("ok") == true
                    if (ok) {
                        EventoReceiver.agenda(act, id, e.optString("nome"), quando, !eu)
                        if (!eu) Prefs.extraLong("ev_inscreveu", 1L)
                    }
                    Bg.ui { ocupado = false; Ui.toast(act, if (ok) (if (eu) "VOCÊ SAIU DO EVENTO" else "INSCRITO! TE AVISO ANTES") else Comunidade.motivo(j)); busca() }
                }
            }, Ui.lp(top = 12, c = c))
            else k.addView(Ui.pilulaEstado(c, "JÁ PASSOU", null), Ui.lp(top = 10, c = c))
            add(k, 10)
        }
    }
}

// ---------------------------------------------------------------- AMIGOS (+ conquistas)
class AmigosScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Amigos"
    private var dados: JSONObject? = null
    private var carregou = false
    private var ocupado = false
    private var ultimaBusca = 0L

    override fun key(): String = "$carregou|${dados?.toString()?.hashCode()}|$ocupado|${Prefs.extraTxt("am_codigo")}"
    override fun aoMostrar() { super.aoMostrar(); busca() }

    override fun leve() { if (Prefs.extraTxt("am_codigo") != null && SystemClock.elapsedRealtime() - ultimaBusca > 20_000) busca() }

    private fun busca() {
        ultimaBusca = SystemClock.elapsedRealtime()
        if (Prefs.extraTxt("am_codigo") == null) { carregou = true; redesenha(); return }
        Bg.io {
            val j = Comunidade.x(act, "am_lista")
            Bg.ui {
                carregou = true
                if (j?.optBoolean("ok") == true) {
                    dados = j
                    val n = j.optJSONArray("amigos")?.let { a -> (0 until a.length()).count { a.optJSONObject(it)?.optString("estado") == "amigo" } } ?: 0
                    Prefs.extraLong("amigos_n", n.toLong())
                } else if (j?.optString("reason") == "sem_perfil") Prefs.extraTxt("am_codigo", null)
                redesenha()
            }
        }
    }

    private fun acao(op: String, extra: JSONObject, ok: String) {
        if (ocupado) return
        ocupado = true
        redesenha()
        Bg.io {
            val j = Comunidade.x(act, op, extra)
            Bg.ui { ocupado = false; Ui.toast(act, if (j?.optBoolean("ok") == true) ok else Comunidade.motivo(j)); busca() }
        }
    }

    private fun ativ(o: JSONObject): String {
        if (o.optBoolean("online")) return when (o.optString("ativ")) {
            "jogo" -> "jogando Free Fire"; "bot" -> "no bot de fila"; "app" -> "no app"; else -> "online"
        }
        val v = o.optLong("visto")
        if (v <= 0) return "offline"
        val min = (System.currentTimeMillis() - v) / 60000
        return if (min < 60) "visto há $min min" else if (min < 1440) "visto há ${min / 60} h" else "visto há ${min / 1440} dia(s)"
    }

    override fun render() {
        limpa()
        val c = act
        val codigo = Prefs.extraTxt("am_codigo")
        if (codigo == null) {
            cabecalho(titulo, "Veja quem dos seus amigos está online no NOCRY e chame pra jogar. Vem desligado: só aparece pra quem você aceitar, e só o apelido e o que está fazendo. Nada de nome, número ou localização.")
            val ap = Ui.field(c, "seu apelido (aparece pros amigos)", false)
            ap.setText(Prefs.extraTxt("am_apelido") ?: Prefs.apelido)
            add(ap, 14)
            add(Ui.button(c, if (ocupado) "ligando..." else "ligar amigos", Ui.Kind.PRIMARY, R.drawable.ic_user) {
                val nome = ap.text.toString().trim()
                if (nome.length < 2) { Ui.toast(act, "Escolha um apelido"); return@button }
                ocupado = true
                redesenha()
                Bg.io {
                    val j = Comunidade.x(act, "am_perfil", JSONObject().put("apelido", nome).put("ligado", true).put("conq", Conquistas.codigo()))
                    val p = j?.optJSONObject("perfil")
                    Bg.ui {
                        ocupado = false
                        if (p != null) { Prefs.extraTxt("am_codigo", p.optString("codigo")); Prefs.extraTxt("am_apelido", nome); busca() } else Ui.toast(act, Comunidade.motivo(j))
                        redesenha()
                    }
                }
            }, 8)
            conquistas()
            return
        }
        cabecalho(titulo, "Passe seu código pros amigos. Quando os dois se adicionarem, aparece quem está online.")
        val k = Ui.card(c, forte = true)
        k.addView(Ui.text(c, "SEU CÓDIGO", 10.5f, Ui.MUTED, true, mono = true))
        k.addView(Ui.gradTexto(c, codigo, 30f), Ui.lp(top = 2, c = c))
        k.addView(Ui.text(c, "Apelido: " + (Prefs.extraTxt("am_apelido") ?: ""), 12f, Ui.MUTED), Ui.lp(top = 4, c = c))
        val bts = Ui.hbox(c)
        bts.addView(Ui.button(c, "copiar", Ui.Kind.GHOST, R.drawable.ic_copy) {
            try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("NOCRY", codigo)) } catch (_: Throwable) {}
            Ui.toast(act, "CÓDIGO COPIADO")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bts.addView(Ui.button(c, "desligar", Ui.Kind.GHOST, R.drawable.ic_power) {
            Ui.dialogConfirma(act, "DESLIGAR AMIGOS?", "Você some da lista dos seus amigos até ligar de novo.", "DESLIGAR") {
                Bg.io {
                    val j = Comunidade.x(act, "am_perfil", JSONObject().put("ligado", false))
                    Bg.ui { if (j?.optBoolean("ok") == true) { Prefs.extraTxt("am_codigo", null); dados = null }; Ui.toast(act, if (j?.optBoolean("ok") == true) "AMIGOS DESLIGADO" else Comunidade.motivo(j)); redesenha() }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(c, 8) })
        k.addView(bts, Ui.lp(top = 12, c = c))
        add(k, 12)

        val cod = Ui.field(c, "código do amigo (6 letras)", false)
        cod.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        add(cod, 14)
        add(Ui.button(c, "adicionar amigo", Ui.Kind.PRIMARY, R.drawable.ic_user) {
            val t = cod.text.toString().trim().uppercase()
            if (t.length != 6) { Ui.toast(act, "O código tem 6 letras"); return@button }
            acao("am_add", JSONObject().put("codigo", t), "PEDIDO ENVIADO")
        }, 8)

        val d = dados
        if (!carregou || d == null) { add(Ui.text(c, if (!carregou) "Carregando..." else "Sem internet agora.", 12f, Ui.MUTED), 14); conquistas(); return }
        val arr = d.optJSONArray("amigos") ?: JSONArray()
        val todos = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val recebidos = todos.filter { it.optString("estado") == "recebido" }
        val amigos = todos.filter { it.optString("estado") == "amigo" }.sortedByDescending { if (it.optBoolean("online")) Long.MAX_VALUE else it.optLong("visto") }
        val enviados = todos.filter { it.optString("estado") == "enviado" }

        if (recebidos.isNotEmpty()) {
            add(Ui.secao(c, "pedidos de amizade"), 20)
            for (o in recebidos) {
                val kk = Ui.card(c)
                kk.addView(Ui.text(c, o.optString("apelido") + " quer ser seu amigo", 14f, Ui.TEXT, true))
                val b = Ui.hbox(c)
                b.addView(Ui.button(c, "aceitar", Ui.Kind.PRIMARY) { acao("am_aceitar", JSONObject().put("codigo", o.optString("codigo")), "AGORA VOCÊS SÃO AMIGOS") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                b.addView(Ui.button(c, "recusar", Ui.Kind.GHOST) { acao("am_remover", JSONObject().put("codigo", o.optString("codigo")), "PEDIDO RECUSADO") },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(c, 8) })
                kk.addView(b, Ui.lp(top = 10, c = c))
                add(kk, 8)
            }
        }
        val on = amigos.count { it.optBoolean("online") }
        add(Ui.secao(c, "amigos · $on online"), 20)
        if (amigos.isEmpty()) add(Ui.dica(c, "Nenhum amigo ainda. Adicione pelo código.", Ui.Dica.INFO), 8)
        val nomes = Conquistas.todas().associate { it.id to it.nome }
        for (o in amigos) {
            val kk = Ui.card(c, ligado = o.optBoolean("online"))
            val topo = Ui.hbox(c)
            topo.gravity = Gravity.CENTER_VERTICAL
            val col = Ui.vbox(c)
            col.addView(Ui.text(c, o.optString("apelido"), 15f, Ui.TEXT, true))
            col.addView(Ui.text(c, ativ(o), 11.5f, if (o.optBoolean("online")) Ui.OK else Ui.MUTED), Ui.lp(top = 2, c = c))
            val med = o.optString("conq").split(',').filter { it.isNotBlank() }
            if (med.isNotEmpty()) col.addView(Ui.text(c, "🏅 ${med.size} · " + med.take(3).mapNotNull { nomes[it] }.joinToString(", "), 10.5f, Ui.MUTED), Ui.lp(top = 3, c = c))
            topo.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topo.addView(Ui.pilulaEstado(c, if (o.optBoolean("online")) "ON" else "OFF", o.optBoolean("online")))
            kk.addView(topo)
            kk.addView(Ui.button(c, "bora jogar?", Ui.Kind.GHOST, R.drawable.ic_gamepad) {
                acao("am_bora", JSONObject().put("codigo", o.optString("codigo")), "CHAMEI ${o.optString("apelido").uppercase()}!")
            }, Ui.lp(top = 10, c = c))
            kk.setOnLongClickListener {
                Ui.dialogConfirma(act, "DESFAZER AMIZADE?", o.optString("apelido") + " sai da sua lista.", "DESFAZER") {
                    acao("am_remover", JSONObject().put("codigo", o.optString("codigo")), "AMIZADE DESFEITA")
                }
                true
            }
            add(kk, 8)
        }
        if (amigos.isNotEmpty()) add(Ui.text(c, "Segure num amigo pra desfazer a amizade.", 10.5f, Ui.MUTED), 6)
        if (enviados.isNotEmpty()) {
            add(Ui.secao(c, "esperando aceitar"), 20)
            for (o in enviados) add(Ui.row(c, o.optString("apelido"), "toque pra cancelar o pedido", R.drawable.ic_user) {
                acao("am_remover", JSONObject().put("codigo", o.optString("codigo")), "PEDIDO CANCELADO")
            }, 8)
        }
        conquistas()
    }

    private fun conquistas() {
        val c = act
        val l = Conquistas.todas()
        add(Ui.secao(c, "suas conquistas · ${l.count { it.tem }}/${l.size}"), 24)
        var i = 0
        while (i < l.size) {
            val linha = Ui.hbox(c)
            for (j in 0..1) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (j == 1) lp.leftMargin = Ui.dp(c, 8)
                if (i + j < l.size) {
                    val q = l[i + j]
                    val k = Ui.card(c, ligado = q.tem)
                    k.addView(Ui.text(c, (if (q.tem) "🏅 " else "🔒 ") + q.nome, 13f, if (q.tem) Ui.TEXT else Ui.MUTED, true))
                    k.addView(Ui.text(c, q.desc, 10.5f, Ui.MUTED), Ui.lp(top = 3, c = c))
                    linha.addView(k, lp)
                } else linha.addView(View(c), lp)
            }
            add(linha, 8)
            i += 2
        }
    }
}
