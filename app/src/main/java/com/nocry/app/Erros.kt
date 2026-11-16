package com.nocry.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Relatório de erros: o app guarda sozinho o que deu errado, pra dar pra ver o motivo sem PC.
 *  - crash: o app fechou por um erro (com a pilha completa)
 *  - travada: a tela ficou parada mais de 3 s (com o que a thread principal estava fazendo)
 *  - saídas antigas: no Android 11+, o próprio sistema conta por que fechou o app da última vez
 *    (travamento/ANR, falta de memória, erro nativo...)
 * Fica em files/erros, no máximo [MAX_ARQ] arquivos. Nada daqui sai do celular sozinho.
 */
object Erros {
    class Item(val arquivo: File, val tipo: String, val titulo: String, val quando: Long)

    private const val MAX_ARQ = 25
    private const val LIMITE_TRAVADA = 3000L
    private const val TAG = "NocryErro"

    /** nome da tela aberta agora (vai junto no registro) */
    @Volatile var telaAtual: String = "-"

    private var dir: File? = null
    private lateinit var app: Context
    private var anterior: Thread.UncaughtExceptionHandler? = null
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
    private val inicioProcesso = SystemClock.elapsedRealtime()

    fun instalar(c: Context) {
        app = c.applicationContext
        dir = File(app.filesDir, "erros").apply { mkdirs() }
        anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                salvar("crash", "O app fechou por um erro: ${e.javaClass.simpleName}",
                    "thread: ${t.name}\n\n${e.stackTraceToString()}")
            } catch (_: Throwable) {}
            val a = anterior
            if (a != null) a.uncaughtException(t, e) else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
        vigia()
        if (Build.VERSION.SDK_INT >= 30) Thread({
            try { conferirSaidas() } catch (t: Throwable) { Log.w(TAG, "saidas: $t") }
        }, "nocry-erros").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ registro

    private fun cabecalho(): String {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val mem = try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            "celular: ${mi.availMem / mb} MB livres de ${mi.totalMem / mb} MB${if (mi.lowMemory) " (POUCA MEMÓRIA)" else ""}"
        } catch (_: Throwable) { "celular: ?" }
        val ligado = (SystemClock.elapsedRealtime() - inicioProcesso) / 1000
        return buildString {
            append("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("tela aberta: $telaAtual\n")
            append("memória do app: ${(rt.totalMemory() - rt.freeMemory()) / mb} MB usados de ${rt.maxMemory() / mb} MB\n")
            append("$mem\n")
            append("app aberto há: ${ligado / 60} min ${ligado % 60} s\n")
        }
    }

    @Synchronized
    private fun salvar(tipo: String, titulo: String, detalhes: String, quando: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val f = File(d, "${quando}_$tipo.txt")
            f.writeText("tipo: $tipo\nquando: ${fmt.format(Date(quando))}\nresumo: $titulo\n----\n${cabecalho()}----\n$detalhes\n")
            Log.e(TAG, "$titulo\n$detalhes")
            cache = null
            novosCache = -1
            // guarda só os mais novos
            val todos = d.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: return
            todos.drop(MAX_ARQ).forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ vigia da tela

    /** se a thread principal ficar sem responder mais de 3 s, guarda o que ela estava fazendo */
    private fun vigia() {
        val main = Handler(Looper.getMainLooper())
        val th = Thread({
            var ultimo = 0L
            while (true) {
                try {
                    val ok = AtomicBoolean(false)
                    main.post { ok.set(true) }
                    Thread.sleep(LIMITE_TRAVADA)
                    if (ok.get()) continue
                    // parada na fila sem nada pra fazer não é travada (acontece quando o Android congela o app no fundo)
                    val pilha = Looper.getMainLooper().thread.stackTrace
                    if (pilha.isEmpty() || pilha[0].methodName == "nativePollOnce") continue
                    var parado = LIMITE_TRAVADA
                    while (!ok.get() && parado < 60_000) { Thread.sleep(250); parado += 250 }
                    val agora = System.currentTimeMillis()
                    if (agora - ultimo < 20_000) continue          // não enche de arquivo se travar várias vezes seguidas
                    ultimo = agora
                    val txt = pilha.joinToString("\n") { "    at $it" }
                    salvar("travada", "A tela travou por ${parado / 1000.0} s${if (!ok.get()) " (ainda travada)" else ""}",
                        "o que a tela estava fazendo quando travou:\n$txt", agora - parado)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {}
            }
        }, "nocry-vigia")
        th.isDaemon = true
        th.priority = Thread.MAX_PRIORITY
        th.start()
    }

    // ------------------------------------------------------------------ o que o Android conta

    @androidx.annotation.RequiresApi(30)
    private fun conferirSaidas() {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val sp = app.getSharedPreferences("nocry_erros", Context.MODE_PRIVATE)
        val visto = sp.getLong("saida_vista", 0L)
        val lista = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
        var maior = visto
        for (e in lista.sortedBy { it.timestamp }) {
            if (e.timestamp <= visto) continue
            maior = maxOf(maior, e.timestamp)
            val visivel = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
            val desc = e.description ?: ""
            val (tipo, titulo) = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "travada" to "O Android fechou o app porque ele travou (ANR)"
                ApplicationExitInfo.REASON_CRASH -> {
                    // o capturador já guardou esse com a pilha completa
                    if (temPerto("crash", e.timestamp)) continue
                    "crash" to "O app fechou por um erro"
                }
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash" to "O app fechou por um erro interno (nativo)"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "crash" to "O app não conseguiu abrir"
                ApplicationExitInfo.REASON_LOW_MEMORY ->
                    if (visivel) "memoria" to "O Android fechou o app por falta de memória" else continue
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                    "memoria" to "O Android fechou o app por usar demais o celular"
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER ->
                    if (visivel && !desc.contains("remove task", true) && !desc.contains("stop user", true))
                        "sistema" to "O sistema fechou o app ($desc)" else continue
                else -> continue
            }
            val detalhes = buildString {
                append("motivo do Android: ${e.reason} · $desc\n")
                append("importância: ${e.importance} · memória usada: ${e.pss / 1024} MB (pss) / ${e.rss / 1024} MB (rss)\n")
                append("processo: ${e.processName} · status ${e.status}\n")
                if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    val trace = try { e.traceInputStream?.bufferedReader()?.use { lerTrace(it) } } catch (_: Throwable) { null }
                    if (!trace.isNullOrBlank()) append("\n").append(trace)
                }
            }
            salvar(tipo, titulo, detalhes, e.timestamp)
        }
        if (maior != visto) sp.edit().putLong("saida_vista", maior).apply()
    }

    /** do trace gigante do ANR, guarda o começo e a thread principal (é ela que trava a tela) */
    private fun lerTrace(r: java.io.BufferedReader): String {
        val sb = StringBuilder()
        var linhas = 0
        var naMain = false
        var cabeca = true
        while (true) {
            val l = r.readLine() ?: break
            if (++linhas > 40_000) break
            if (cabeca) {
                if (l.startsWith("\"")) cabeca = false else { if (sb.length < 3000) sb.append(l).append('\n'); continue }
            }
            if (l.startsWith("\"main\"")) naMain = true
            else if (naMain && l.isBlank()) { naMain = false; sb.append('\n') }
            if (naMain) sb.append(l).append('\n')
            if (sb.length > 60_000) break
        }
        return sb.toString()
    }

    private fun temPerto(tipo: String, ts: Long): Boolean =
        itens().any { it.tipo == tipo && kotlin.math.abs(it.quando - ts) < 15_000 }

    // ------------------------------------------------------------------ pra tela

    @Volatile private var cache: List<Item>? = null

    /** a lista já lida (sem tocar no disco); null = ainda não leu */
    fun itensProntos(): List<Item>? = cache

    fun itens(): List<Item> {
        cache?.let { return it }
        val d = dir ?: return emptyList()
        val fs = d.listFiles()?.filter { it.name.endsWith(".txt") } ?: return emptyList()
        return fs.mapNotNull { f ->
            try {
                val quando = f.name.substringBefore('_').toLongOrNull() ?: f.lastModified()
                val tipo = f.name.substringAfter('_').removeSuffix(".txt")
                val titulo = f.bufferedReader().use { r ->
                    var t = ""
                    for (i in 0 until 3) { val l = r.readLine() ?: break; if (l.startsWith("resumo: ")) t = l.removePrefix("resumo: ") }
                    t
                }
                Item(f, tipo, titulo, quando)
            } catch (_: Throwable) { null }
        }.sortedByDescending { it.quando }.also { cache = it }
    }

    /** quantos registros chegaram depois da última vez que você abriu o relatório */
    @Volatile private var novosCache = -1

    fun novos(): Int {
        if (novosCache >= 0) return novosCache
        if (!::app.isInitialized) return 0
        val visto = app.getSharedPreferences("nocry_erros", Context.MODE_PRIVATE).getLong("visto_ate", 0L)
        val nomes = dir?.list() ?: return 0
        return nomes.count { (it.substringBefore('_').toLongOrNull() ?: 0L) > visto }.also { novosCache = it }
    }

    fun marcarVisto() {
        app.getSharedPreferences("nocry_erros", Context.MODE_PRIVATE).edit()
            .putLong("visto_ate", System.currentTimeMillis()).apply()
        novosCache = 0
    }

    /** junta tudo num arquivo só, pronto pra compartilhar */
    fun montarRelatorio(): File? {
        val l = itens()
        if (l.isEmpty()) return null
        val out = File(app.filesDir, "relatorio_erros_nocry.txt")
        out.bufferedWriter().use { w ->
            w.write("RELATÓRIO DE ERROS DO NOCRY · ${fmt.format(Date())}\n")
            w.write("${l.size} registro(s), do mais novo pro mais antigo\n\n")
            for (it in l) {
                w.write("==================================================================\n")
                try { it.arquivo.bufferedReader().use { r -> r.copyTo(w) } } catch (_: Throwable) {}
                w.write("\n")
            }
        }
        return out
    }

    fun apagar() {
        dir?.listFiles()?.forEach { it.delete() }
        cache = null
        marcarVisto()
    }
}
