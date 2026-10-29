package com.nocry.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Constantes do app. Troque REPO se o repositorio publico tiver outro nome. */
object Const {
    const val PKG = "com.nocry.app"
    const val REPO = "aucerolanocry/Nocry"          // repositorio PUBLICO onde o APK fica em Releases
    const val FF_MAX = "com.dts.freefiremax"
    const val FF_NORMAL = "com.dts.freefireth"
}

/** Trabalho em segundo plano / volta pra tela. */
object Bg {
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "nocry-bg").apply { isDaemon = true }
    })

    fun io(block: () -> Unit) {
        pool.execute {
            try { block() } catch (t: Throwable) { NLog.add("erro", t.toString()) }
        }
    }

    fun ui(block: () -> Unit) {
        main.post {
            try { block() } catch (t: Throwable) { NLog.add("erro-ui", t.toString()) }
        }
    }

    fun <T> run(work: () -> T, done: (T) -> Unit) {
        io {
            val r = work()
            ui { done(r) }
        }
    }

    fun later(ms: Long, block: () -> Unit) {
        main.postDelayed({ block() }, ms)
    }
}

/** Registro de tudo que o app faz (aparece em LOGS). */
object NLog {
    private const val MAX = 400
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    @Synchronized
    fun init(c: Context) {
        val f = File(c.filesDir, "nocry.log")
        file = f
        lines.clear()
        try {
            if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + tag + "] " + msg.replace('\n', ' ')
        lines.addLast(line)
        var rewrite = false
        while (lines.size > MAX) {
            lines.removeFirst()
            rewrite = true
        }
        val f = file ?: return
        try {
            if (rewrite) f.writeText(lines.joinToString("\n") + "\n") else f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun text(tag: String? = null): String {
        val l = if (tag == null) lines.toList() else lines.filter { it.contains("[$tag]") }
        return if (l.isEmpty()) "(sem registros ainda)" else l.takeLast(120).joinToString("\n")
    }

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}

/** Preferencias do app. Valores sempre validados (nunca devolve algo fora do limite). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("nocry", Context.MODE_PRIVATE)
    }

    private fun gi(k: String, d: Int, lo: Int, hi: Int): Int {
        val v = sp.getInt(k, d)
        return if (v in lo..hi) v else d
    }

    private fun pi(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    private fun gb(k: String) = sp.getBoolean(k, false)
    private fun pb(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    private fun gs(k: String): String? = sp.getString(k, null)
    private fun ps(k: String, v: String?) {
        if (v == null) sp.edit().remove(k).apply() else sp.edit().putString(k, v).apply()
    }

    // ---- valores do toque (1 a 600) ----
    var longOn: Int
        get() = gi("long_on", 1, 1, 600)
        set(v) = pi("long_on", v.coerceIn(1, 600))
    var multiOn: Int
        get() = gi("multi_on", 1, 1, 600)
        set(v) = pi("multi_on", v.coerceIn(1, 600))
    var normal: Int
        get() = gi("normal", 100, 1, 600)
        set(v) = pi("normal", v.coerceIn(1, 600))

    // ---- quantos apertos de volume (2 a 9) ----
    var clicksUp: Int
        get() = gi("clicks_up", 2, 2, 9)
        set(v) = pi("clicks_up", v.coerceIn(2, 9))
    var clicksDown: Int
        get() = gi("clicks_down", 2, 2, 9)
        set(v) = pi("clicks_down", v.coerceIn(2, 9))

    // ---- toque via volume ----
    var volAtivo: Boolean
        get() = gb("vol_ativo")
        set(v) = pb("vol_ativo", v)
    var markVol: Boolean
        get() = gb("mark_vol")
        set(v) = pb("mark_vol", v)

    // ---- toque via carregador ----
    var cargaAtivo: Boolean
        get() = gb("carga_ativo")
        set(v) = pb("carga_ativo", v)
    var markCarga: Boolean
        get() = gb("mark_carga")
        set(v) = pb("mark_carga", v)
    /** "tirou" = tirar o carregador liga o toque rapido; "colocou" = o contrario */
    var cargaGatilho: String
        get() = if (gs("carga_gatilho") == "colocou") "colocou" else "tirou"
        set(v) = ps("carga_gatilho", if (v == "colocou") "colocou" else "tirou")
    var cargaRepTotal: Int
        get() = gi("carga_rep_total", 1, 1, 3)
        set(v) = pi("carga_rep_total", v.coerceIn(1, 3))
    var cargaRep: Int
        get() = gi("carga_rep", 0, 0, 3)
        set(v) = pi("carga_rep", v.coerceIn(0, 3))

    // ---- modo competitivo (toque rapido simples) ----
    var compFlag: Boolean
        get() = gb("comp_flag")
        set(v) = pb("comp_flag", v)
    /** valores de ANTES: "long|multi" (cada um pode ser "null") */
    var compOrig: String?
        get() = gs("comp_orig")
        set(v) = ps("comp_orig", v)

    // ---- modo carregador (density + abre o FF) ----
    var mcAtivo: Boolean
        get() = gb("mc_ativo")
        set(v) = pb("mc_ativo", v)
    var mcFf: String
        get() = if (gs("mc_ff") == "normal") "normal" else "max"
        set(v) = ps("mc_ff", if (v == "normal") "normal" else "max")
    var mcDens: Int
        get() = gi("mc_dens", 3500, 200, 9999)
        set(v) = pi("mc_dens", v.coerceIn(200, 9999))
    var mcCycles: Int
        get() = gi("mc_cycles", 0, 0, 3)
        set(v) = pi("mc_cycles", v.coerceIn(0, 3))
    var mcCyclesTotal: Int
        get() = gi("mc_cycles_total", 1, 1, 3)
        set(v) = pi("mc_cycles_total", v.coerceIn(1, 3))
    /** density de ANTES do modo carregador: numero ou "reset". null = o modo nao esta aplicado */
    var densOrig: String?
        get() = gs("dens_orig")
        set(v) = ps("dens_orig", v)

    // ---- otimizacao ----
    var otmAuto: Boolean
        get() = gb("otm_auto")
        set(v) = pb("otm_auto", v)
    var otmAutoMarca: Boolean
        get() = gb("otm_auto_marca")
        set(v) = pb("otm_auto_marca", v)
    /** "fechar" (padrao) ou "proteger" */
    var otmMsg: String
        get() = if (gs("otm_msg") == "proteger") "proteger" else "fechar"
        set(v) = ps("otm_msg", if (v == "proteger") "proteger" else "fechar")
    var otmPerf: Boolean
        get() = gb("otm_perf")
        set(v) = pb("otm_perf", v)

    // ---- geral ----
    var onboardingFeito: Boolean
        get() = gb("onboarding")
        set(v) = pb("onboarding", v)

    // ---- visual (tela PERSONALIZAR) ----
    /** "animado" (desenho do app) | "solido" | "imagem" | "video" */
    var bgTipo: String
        get() = gs("bg_tipo").let { if (it == "solido" || it == "imagem" || it == "video") it else "animado" }
        set(v) = ps("bg_tipo", v)
    /** "brasas" | "aurora" | "grade" | "noite" */
    var bgPreset: String
        get() = gs("bg_preset").let { if (it == "aurora" || it == "grade" || it == "noite") it else "brasas" }
        set(v) = ps("bg_preset", v)
    /** nome do arquivo copiado pra dentro do app (imagem ou video escolhidos da galeria) */
    var bgArquivo: String?
        get() = gs("bg_arquivo")
        set(v) = ps("bg_arquivo", v)
    var bgEscuro: Int
        get() = gi("bg_escuro", 55, 0, 90)
        set(v) = pi("bg_escuro", v.coerceIn(0, 90))
    var bgBlur: Int
        get() = gi("bg_blur", 0, 0, 25)
        set(v) = pi("bg_blur", v.coerceIn(0, 25))
    var accent: Int
        get() = sp.getInt("accent", 0xFFFF2D55.toInt())
        set(v) = pi("accent", v or 0xFF000000.toInt())
    /** quanto o cartao de vidro e opaco (20 a 95 %) */
    var vidro: Int
        get() = gi("vidro", 62, 20, 95)
        set(v) = pi("vidro", v.coerceIn(20, 95))
    var raio: Int
        get() = gi("raio", 18, 6, 30)
        set(v) = pi("raio", v.coerceIn(6, 30))
    /** "grotesk" | "mono" | "sistema" */
    var fonte: String
        get() = gs("fonte").let { if (it == "mono" || it == "sistema") it else "grotesk" }
        set(v) = ps("fonte", v)
    var escala: Int
        get() = gi("escala", 100, 85, 130)
        set(v) = pi("escala", v.coerceIn(85, 130))
    var neon: Boolean
        get() = sp.getBoolean("neon", true)
        set(v) = pb("neon", v)
    var anim: Boolean
        get() = sp.getBoolean("anim", true)
        set(v) = pb("anim", v)

    // ---- Key (licenca) ----
    var consentiu: Boolean
        get() = gb("consent")
        set(v) = pb("consent", v)
    var licKey: String?
        get() = gs("lic_key")
        set(v) = ps("lic_key", v)
    var licLabel: String
        get() = gs("lic_label") ?: ""
        set(v) = ps("lic_label", v)
    var licVisto: Long
        get() = sp.getLong("lic_visto", 0L)
        set(v) { sp.edit().putLong("lic_visto", v).apply() }
    var licP: String?
        get() = gs("lic_p")
        set(v) = ps("lic_p", v)
    var licSig: String?
        get() = gs("lic_sig")
        set(v) = ps("lic_sig", v)

    /** precisa do servico em primeiro plano? */
    fun precisaServico(): Boolean = volAtivo || cargaAtivo || mcAtivo || otmAuto
}

/** Estado do carregador (sem ADB). */
object Power {
    fun plugged(c: Context): Boolean {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}

/** Notificacoes. */
object Notif {
    const val CH_SVC = "nocry_svc"
    const val CH_ALERT = "nocry_alert"
    const val CH_PAIR = "nocry_pair"
    const val ID_SVC = 1001
    const val ID_ALERT = 2001
    const val ID_PAIR = 3001

    fun init(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "NOCRY em segundo plano", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Avisos do NOCRY", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_PAIR, "Pareamento", NotificationManager.IMPORTANCE_HIGH))
    }

    /** aviso rapido na tela (some sozinho) */
    fun say(c: Context, text: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val n = Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("NOCRY")
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(6000)
                .build()
            nm.notify(ID_ALERT, n)
        } catch (_: Exception) {
        }
    }
}
