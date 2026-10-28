package com.nocry.app

import android.content.Context
import java.io.File

/**
 * OTIMIZACAO DE SEGUNDO PLANO (mesma logica do install do Termux, agora dentro do app).
 *   fecha   = force-stop
 *   congela = appops RUN_IN_BACKGROUND e RUN_ANY_IN_BACKGROUND = ignore + standby bucket "restricted" (Android 9+)
 *   protege = o proprio NOCRY, Termux, os dois Free Fire, teclado, launcher, SMS/telefone, acessibilidade, leitores de notificacao,
 *             app em uso, apps com servico em primeiro plano e a lista que voce escolher
 *   desfaz  = o estado de ANTES de cada app fica guardado; DESATIVAR devolve exatamente isso
 * So mexe em apps instalados por voce (pm list packages -3) + uma lista curta de apps do Google seguros de fechar + o que voce escolher.
 */
object Otm {
    private val opLock = Any()

    private const val MSG_APPS = "com.whatsapp com.whatsapp.w4b org.telegram.messenger org.thoughtcrime.securesms com.facebook.orca com.discord"
    private const val EXTRAS = "com.android.chrome com.google.android.youtube com.google.android.apps.youtube.music com.google.android.apps.photos com.google.android.gm com.google.android.apps.maps com.google.android.apps.docs com.google.android.videos com.google.android.apps.tachyon"
    private const val MIUI = "com.miui.analytics com.miui.msa.global com.miui.systemAdSolution com.miui.hybrid com.miui.videoplayer com.miui.player com.xiaomi.mipicks com.mi.globalbrowser com.miui.weather2 com.miui.notes com.miui.contentextension com.miui.vipaccount com.xiaomi.glgm com.miui.thirdappassistant"

    private val CRIT = Regex("^(android|com[.]android[.](settings|systemui|phone|dialer|contacts|mms|vending|stk|launcher|incallui|packageinstaller|permissioncontroller|documentsui|providers|server|bluetooth|nfc|shell|location|wifi|keychain|certinstaller|carrierconfig|storagemanager|webview|cellbroadcast|emergency|inputmethod)|com[.]google[.]android[.](gms|gsf|dialer|contacts|apps[.]messaging|packageinstaller|permissioncontroller|inputmethod|apps[.]nexuslauncher|setupwizard|webview|ext|networkstack|modulemetadata|captiveportallogin|safetycore)|com[.]miui[.](home|securitycenter|powerkeeper|system|core|phone|daemon|rom|face)|com[.]xiaomi[.](xmsf|account|finddevice|market)|com[.]samsung[.]android[.](incallui|dialer|messaging|app[.](contacts|telephony|spage|launcher)|knox|rubin|bixby|scs|fmm|security)|com[.]sec[.]android[.]|com[.](qualcomm|qti|mediatek)[.]|org[.]codeaurora[.])")
    private val HARD = Regex("^(com[.]termux|com[.]dts[.]freefire(th|max)\$|moe[.]shizuku|com[.]draco[.]ladb|com[.]nocry[.]app)")
    private val LIB = Regex("^(com[.]termux([.][a-z0-9_]+)?|com[.]dts[.]freefire(th|max)|com[.]nocry[.]app)\$")
    private val PKG_OK = Regex("[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+")
    private val NOME_OK = Regex("[A-Za-z0-9_.]+")

    private val ROT = mapOf(
        "default_input_method" to "TECLADO",
        "enabled_accessibility_services" to "ACESSIBILIDADE",
        "enabled_notification_listeners" to "NOTIFICACOES",
        "sms_default_application" to "SMS",
        "dialer_default_application" to "TELEFONE"
    )

    private val CHAVES_OK = setOf(
        "global.window_animation_scale", "global.transition_animation_scale", "global.animator_duration_scale",
        "system.peak_refresh_rate", "system.min_refresh_rate", "global.captive_portal_detection_enabled",
        "global.sem_enhanced_cpu_responsiveness", "global.adaptive_battery_management_enabled", "global.low_power",
        "global.ble_scan_always_enabled", "global.wifi_scan_always_enabled", "global.wifi_networks_available_notification_on",
        "global.heads_up_notifications_enabled", "global.location_background_throttle_interval_ms",
        "system.haptic_feedback_enabled", "global.disable_window_blurs", "global.automatic_power_save_mode",
        "global.cached_apps_freezer", "global.settings_enable_monitor_phantom_procs", "dc.max_phantom_processes"
    )

    // ---------------- arquivos ----------------
    private fun f(n: String) = File(Sys.app.filesDir, n)

    private fun lerLinhas(n: String): List<String> = try {
        f(n).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    private fun gravar(n: String, l: List<String>) {
        try {
            if (l.isEmpty()) f(n).delete() else f(n).writeText(l.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    private val assets = HashMap<String, String>()
    private fun asset(n: String): String = synchronized(assets) {
        assets.getOrPut(n) { Sys.app.assets.open(n).bufferedReader().use { it.readText() } }
    }

    fun protegidos(): List<String> = lerLinhas("otm_prot").filter { !it.startsWith("!") }.map { it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } }.filter { it.isNotEmpty() }
    private fun liberadosMsg(): Set<String> = lerLinhas("otm_prot").filter { it.startsWith("!") }.map { it.substring(1) }.toSet()
    fun extras(): List<String> = lerLinhas("otm_extra").filter { NOME_OK.matches(it) }

    // ---------------- plano ----------------
    class Cand(val pk: String, val kb: Long, val escolhido: Boolean)
    class Prot(val pk: String, val kb: Long, val why: String)
    class Sis(val pk: String, val kb: Long, val pode: Boolean, val why: String)
    class Nat(val pk: String, val kb: Long)

    class Plano(
        val props: Map<String, String>,
        val memTotal: Long, val memAvail: Long, val memFree: Long,
        val hz: Int, val setFail: Boolean,
        val libs: List<String>,
        val c: List<Cand>, val x: List<Prot>, val s: List<Sis>, val n: List<Nat>
    ) {
        fun p(k: String): String = props[k] ?: ""
        val sdk: Int get() = p("ro.build.version.sdk").toIntOrNull() ?: 0

        /** "XIAOMI POCO X5 · ANDROID 13 · 6 GB · MIUI/HYPEROS" */
        fun perfil(): String {
            val mf = p("ro.product.manufacturer")
            var mo = p("ro.product.marketname")
            if (mo.isEmpty()) mo = p("ro.product.model")
            val an = p("ro.build.version.release").ifEmpty { "?" }
            val gb = (memTotal + 1048575) / 1048576
            var skin = ""
            if (p("ro.miui.ui.version.name").isNotEmpty() || p("ro.mi.os.version.name").isNotEmpty()) skin = " · MIUI/HYPEROS"
            if (p("ro.build.version.emui").isNotEmpty()) skin = " · EMUI"
            return (mf + " " + mo).uppercase() + " · ANDROID " + an + " · " + gb + " GB" + skin
        }

        /** o que o aparelho aguenta */
        fun nivel(): String = when {
            sdk >= 28 -> "FECHA + BLOQUEIA SEGUNDO PLANO + STANDBY"
            sdk >= 26 -> "FECHA + BLOQUEIA SEGUNDO PLANO"
            else -> "SO FECHA (ANDROID ANTIGO NAO CONGELA)"
        }
    }

    fun lerPlano(): Plano? {
        if (!Adb.connected) return null
        val r = Adb.exec(asset("otm_cfg.sh") + "\n" + asset("otm_leitura.sh"), 40000)
        if (!r.out.lines().any { it.trim() == "@FIM" }) return null
        return parse(r.out)
    }

    private val reFg = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
    private val reHome = Regex("[A-Za-z0-9_.]+/")
    private val reFgsCur = Regex(" u[0-9]+ [A-Za-z0-9_.]+/")
    private val ws = Regex("\\s+")

    private fun parse(raw: String): Plano {
        val props = HashMap<String, String>()
        var mt = 0L
        var ma = 0L
        var mf = 0L
        val third = HashSet<String>()
        val pkgall = HashSet<String>()
        val launch = HashSet<String>()
        val prc = HashMap<String, Long>()
        val fg = HashSet<String>()
        val home = HashSet<String>()
        val setp = HashMap<String, String>()
        val fgs = HashSet<String>()
        var hz = 0
        var setfail = false
        var cur = ""
        var sec = ""
        for (line in raw.lines()) {
            if (line.startsWith("@")) { sec = line.substring(1).trim(); continue }
            when (sec) {
                "PROP" -> {
                    val i = line.indexOf('=')
                    if (i > 0) props[line.substring(0, i)] = line.substring(i + 1)
                }
                "MEM" -> {
                    val t = line.trim().split(ws)
                    if (t.size >= 2) {
                        val v = t[1].toLongOrNull() ?: 0L
                        when (t[0]) { "MemTotal:" -> mt = v; "MemAvailable:" -> ma = v; "MemFree:" -> mf = v }
                    }
                }
                "PKG3" -> {
                    val p = line.trim().removePrefix("package:")
                    if (p.isNotEmpty()) third.add(p)
                }
                "PKGALL" -> {
                    val p = line.trim().removePrefix("package:").substringBefore(' ').substringBefore('\t')
                    if (p.isNotEmpty()) pkgall.add(p)
                }
                "LAUNCH" -> {
                    val p = line.trim()
                    if (NOME_OK.matches(p)) launch.add(p)
                }
                "PS" -> {
                    val t = line.trim().split(ws).filter { it.isNotEmpty() }
                    if (t.isNotEmpty() && t[0] != "RSS" && t[0] != "USER") {
                        var rss = -1L
                        var nm = ""
                        if (t.size == 2 && t[0].all { it.isDigit() }) { rss = t[0].toLong(); nm = t[1] }
                        else if (t.size >= 8 && t[4].all { it.isDigit() }) { rss = t[4].toLong(); nm = t[t.size - 1] }
                        if (rss >= 0) {
                            nm = nm.substringBefore(':')
                            if (NOME_OK.matches(nm) && nm.contains('.')) prc[nm] = (prc[nm] ?: 0L) + rss
                        }
                    }
                }
                "FG" -> {
                    val m = reFg.find(line)
                    if (m != null) fg.add(m.value.substringBefore('/'))
                }
                "HOME" -> {
                    val m = reHome.find(line)
                    if (m != null) home.add(m.value.removeSuffix("/"))
                }
                "SET" -> {
                    val i = line.indexOf('=')
                    if (i > 0) {
                        val k = line.substring(0, i)
                        val v = line.substring(i + 1)
                        if (v == "!") setfail = true
                        else if (v != "null" && v.isNotEmpty()) {
                            for (part in v.split(':')) {
                                val s = part.substringBefore('/')
                                if (s.isNotEmpty()) setp[s] = k
                            }
                        }
                    }
                }
                "HZ" -> {
                    val t = line.trim()
                    if (t.isNotEmpty() && t.all { it.isDigit() }) hz = t.toIntOrNull() ?: 0
                }
                "FGS" -> {
                    val m = reFgsCur.find(line)
                    if (m != null) {
                        cur = m.value.trim().substringAfter(' ').removeSuffix("/")
                    } else if (line.contains("isForeground=true") && cur.isNotEmpty()) fgs.add(cur)
                }
            }
        }

        val up = protegidos().toSet()
        val ua = liberadosMsg()
        val uex = extras().toSet()
        val mg = MSG_APPS.split(' ').toSet()
        val ex = HashSet<String>(EXTRAS.split(' '))
        if (props["ro.miui.ui.version.name"].orEmpty().isNotEmpty() || props["ro.mi.os.version.name"].orEmpty().isNotEmpty()) ex.addAll(MIUI.split(' '))
        val msgFecha = Prefs.otmMsg == "fechar"

        fun razao(pk: String): String {
            if (HARD.containsMatchIn(pk)) return "NOCRY/FF"
            if (pk in up) return "SUA LISTA"
            if (pk in mg && pk !in ua && !msgFecha) return "MENSAGENS"
            if (pk in fg) return "EM USO AGORA"
            if (pk in home) return "LAUNCHER"
            val sp = setp[pk]
            if (sp != null) return ROT[sp] ?: sp
            if (pk in fgs) return "SERVICO ATIVO"
            return ""
        }

        val libs = third.filter { LIB.containsMatchIn(it) }.sorted()
        val c = ArrayList<Cand>()
        val x = ArrayList<Prot>()
        val s = ArrayList<Sis>()
        val n = ArrayList<Nat>()
        for ((pk, kb) in prc) {
            val why = razao(pk)
            if (pk in third || pk in ex) {                       // apps instalados por voce + lista segura do Google/Xiaomi
                if (why.isEmpty()) c.add(Cand(pk, kb, false)) else x.add(Prot(pk, kb, why))
                continue
            }
            val sel = pk in launch && !CRIT.containsMatchIn(pk) && !HARD.containsMatchIn(pk)    // app do sistema com icone, nao essencial
            if (sel && pk in uex) {
                if (why.isEmpty()) c.add(Cand(pk, kb, true)) else x.add(Prot(pk, kb, why))
                continue
            }
            if (pk !in pkgall) n.add(Nat(pk, kb))
            else if (sel && why.isEmpty()) s.add(Sis(pk, kb, true, ""))
            else s.add(Sis(pk, kb, false, why))
        }
        c.sortByDescending { it.kb }
        x.sortByDescending { it.kb }
        s.sortByDescending { it.kb }
        return Plano(props, mt, ma, mf, hz, setfail, libs, c, x, s, n)
    }

    // ---------------- textos ----------------
    fun mb(kb: Long): Long = kb / 1024
    fun gb(kb: Long): String = String.format(java.util.Locale.US, "%.1f", kb / 1048576.0)
    fun curto(pk: String): String = pk.removePrefix("com.google.android.").removePrefix("com.android.").removePrefix("com.")
    fun nome(pk: String): String = when (pk) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "org.telegram.messenger" -> "Telegram"
        "org.thoughtcrime.securesms" -> "Signal"
        "com.facebook.orca" -> "Messenger"
        "com.discord" -> "Discord"
        else -> pk.removePrefix("com.")
    }

    // ---------------- estado (o que cada app tinha ANTES) ----------------
    fun estado(): List<String> = lerLinhas("otm_estado")

    /** "ligada" e quantos apps ficaram congelados */
    fun status(): Pair<Boolean, Int> {
        val e = estado()
        val n = e.count { it.startsWith("E|") }
        val t = e.count { it.length > 1 && it[1] == '|' && it[0] in "EALWFG" }
        return Pair(t > 0, n)
    }

    private fun guarda(out: String) {
        val e = estado().toMutableList()
        fun tem(prefixo: String) = e.any { it.startsWith(prefixo) }
        for (l in out.lines()) {
            val p = l.split('|')
            if (p.size < 2) continue
            when (p[0]) {
                "E", "L" -> if (NOME_OK.matches(p[1]) && !tem("${p[0]}|${p[1]}|")) e.add(l)
                "W" -> if (p.size == 3 && NOME_OK.matches(p[1]) && (p[2] == "0" || p[2] == "1") && !tem("W|${p[1]}|")) e.add(l)
                "F" -> if (l == "F|1" && !tem("F|")) e.add(l)
                "G" -> if (p.size == 3 && NOME_OK.matches(p[1]) && (p[2] == "standard" || p[2] == "performance" || p[2] == "battery") && !tem("G|${p[1]}|")) e.add(l)
                "A" -> if (p.size >= 3 && p[1] in CHAVES_OK && !tem("A|${p[1]}|")) e.add(l)
            }
        }
        gravar("otm_estado", e)
    }

    private fun cfgOk(out: String, chave: String): Boolean =
        out.lines().any { it.startsWith("A|$chave|") } && out.lines().none { it == "R|$chave" }

    // ---------------- ATIVAR ----------------
    /** fecha e congela. Cada linha de log aparece na tela na hora (os apps fechando ao vivo). true = feito */
    fun ativar(log: (String) -> Unit): Boolean = synchronized(opLock) { ativarSeguro(log) }

    private fun ativarSeguro(log: (String) -> Unit): Boolean {
        val ctx: Context = Sys.app
        log("LENDO O CELULAR...")
        if (!Adb.ensure(ctx)) {
            log("[x] ADB NAO CONECTADO — NADA FOI MUDADO")
            log("    Abra CONEXAO e conecte a depuracao sem fio.")
            return false
        }
        var plano = lerPlano()
        if (plano != null && plano.setFail) {
            log("TENTANDO DE NOVO...")
            Thread.sleep(2000)
            plano = lerPlano()
        }
        if (plano == null) {
            log("[x] NAO CONSEGUI LER O CELULAR — NADA FOI MUDADO")
            return false
        }
        if (plano.setFail) {
            log("[x] O CELULAR NAO DEIXOU LER AS CONFIGURACOES — NADA FOI MUDADO")
            log("    Reinicie o celular e tente de novo.")
            return false
        }
        val cand = plano.c.map { it.pk }
        val kbDe = plano.c.associate { it.pk to it.kb }
        val nC = cand.size
        val tot = plano.c.sumOf { it.kb }
        var hz = plano.hz
        if (hz < 90 || hz > 240) hz = 0
        val libs = plano.libs
        val games = libs.filter { it.startsWith("com.dts.freefire") }
        val perf = if (Prefs.otmPerf) 1 else 0
        val msgs = cand.filter { it in MSG_APPS.split(' ') }.joinToString(", ") { nome(it) }

        val script = "PKGS=\"" + cand.joinToString(" ") + "\"\n" +
            "LIB=\"" + libs.joinToString(" ") + "\"\n" +
            "GAMES=\"" + games.joinToString(" ") + "\"\n" +
            "ANIM=1\nREDE=1\nEXTRA=1\nPERF=$perf\nHZ=$hz\n" +
            asset("otm_cfg.sh") + "\n" + asset("otm_aplica.sh")

        log("OTIMIZANDO...")
        log("• CELULAR: " + plano.perfil())
        if (nC == 0) log("• APPS EM 2O PLANO: nenhum — ja estava limpo")
        else log("• FECHANDO $nC APPS (~${mb(tot)} MB):")

        var kn = 0
        val res = Adb.execLines(script, 90000) { l ->
            if (l.startsWith("K|")) {
                val p = l.substring(2)
                val kb = kbDe[p]
                if (kb != null && NOME_OK.matches(p)) {
                    kn += 1
                    val pct = minOf(100, kn * 100 / maxOf(nC, 1))
                    log("   × " + nome(p).take(22) + " · ${mb(kb)} MB  ($kn/$nC) $pct%")
                }
            }
        }
        val out = res.out
        guarda(out)
        if (!out.lines().any { it.trim() == "FIM" }) {
            log("[x] O CELULAR PAROU DE RESPONDER NO MEIO")
            log("    Use DESATIVAR para desfazer o que ja foi feito.")
            return false
        }
        val lines = out.lines()
        val antes = lines.firstOrNull { it.startsWith("MEM_ANTES=") }?.substringAfter('=')?.trim()?.toLongOrNull()
        val depois = lines.firstOrNull { it.startsWith("MEM_DEPOIS=") }?.substringAfter('=')?.trim()?.toLongOrNull()
        val v = lines.firstOrNull { it.startsWith("V|") }?.split('|')
        val resV = v?.getOrNull(1)?.toIntOrNull() ?: 0
        val resT = v?.getOrNull(2)?.toIntOrNull() ?: 0
        val resVivos = v?.getOrNull(3)?.toIntOrNull() ?: 0
        val nivel = plano.nivel()

        log("")
        log("[✓] OTIMIZACAO ATIVA!")
        if (!nivel.contains("STANDBY")) log("• MODO: $nivel")
        if (nC > 0) log("• APPS FECHADOS: $nC  (~${mb(tot)} MB)")
        if (msgs.isNotEmpty()) log("• MENSAGENS FECHADAS: $msgs")
        if (antes != null && depois != null) {
            val delta = (depois - antes) / 1024
            log("• RAM LIVRE: ${gb(antes)} GB → ${gb(depois)} GB  (${if (delta >= 0) "+" else ""}$delta MB)")
        }
        log("• PROTEGIDOS: ${plano.x.size} apps")
        if (cfgOk(out, "global.window_animation_scale") && cfgOk(out, "global.transition_animation_scale") && cfgOk(out, "global.animator_duration_scale")) {
            log(if (cfgOk(out, "global.disable_window_blurs")) "• ANIMACOES: ZERADAS + SEM DESFOQUE" else "• ANIMACOES: ZERADAS")
        } else log("⚠ ANIMACOES: o celular nao aceitou zerar (Opcoes do desenvolvedor → escalas de animacao)")
        val hzok = lines.firstOrNull { it.startsWith("HZ|") }?.split('|')?.getOrNull(2)
        if (hzok == "1") log("• TELA: $hz Hz TRAVADO")
        if (hz >= 90 && hzok != "1") log("⚠ TELA: o celular nao aceitou travar em $hz Hz (use as configuracoes de tela)")
        if (cfgOk(out, "global.captive_portal_detection_enabled")) log("• WI-FI: SEM TESTE DE LOGIN")
        if (libs.isNotEmpty()) log("• NOCRY, TERMUX E FF: FORA DA ECONOMIA DE BATERIA")
        if (lines.any { it == "PH|1" }) log("• ANDROID: NAO MATA MAIS OS PROCESSOS EM SEGUNDO PLANO")
        if (lines.any { it == "PH|0" }) log("⚠ ANDROID: o celular nao aceitou tirar o limite de processos (Opcoes do desenvolvedor → Desativar restricoes de processos filhos)")
        if (cfgOk(out, "global.low_power")) log("• SISTEMA: BATERIA INTELIGENTE E ECONOMIA OFF")
        if (cfgOk(out, "global.cached_apps_freezer")) log("• CONGELADOR DO ANDROID: LIGADO")
        if (lines.any { it.startsWith("G|") }) log("• FREE FIRE: MODO JOGO = DESEMPENHO")
        if (lines.any { it == "F|1" }) log("• CPU/GPU: NIVEL FIXO (estavel, nao mais forte)")
        if (lines.any { it == "SEM|1" }) log("• SAMSUNG: RESPOSTA DA CPU NO MAXIMO")
        if (nC > 0 && resV < resT && !nivel.startsWith("SO FECHA")) {
            log("⚠ $resV de $resT: o celular so aceitou o bloqueio em $resV (os outros so foram fechados)")
            val pf = plano.perfil()
            if (pf.startsWith("XIAOMI") || pf.startsWith("REDMI") || pf.startsWith("POCO"))
                log("    Xiaomi/POCO: ligue \"Depuracao USB (Configuracoes de seguranca)\" nas Opcoes do desenvolvedor")
        }
        if (resVivos > 0) log("⚠ $resVivos app(s) o sistema reabriu na hora")
        val ignorar = setOf("system.peak_refresh_rate", "system.min_refresh_rate", "global.settings_enable_monitor_phantom_procs")
        val nTot = lines.filter { Regex("^[AR]\\|(global|system|secure)[.].*").matches(it) }.map { it.split('|')[1] }.filter { it !in ignorar }.toSet().size
        val nRec = lines.filter { it.startsWith("R|") }.map { it.split('|').getOrElse(1) { "" } }.filter { it !in ignorar }.toSet().size
        if (nRec > 0) {
            if (nRec >= nTot) {
                log("⚠ AJUSTES DE SISTEMA: o servico de configuracoes do celular nao respondeu — so os apps foram fechados")
                log("    Reinicie o celular e rode de novo para aplicar animacoes, tela e bateria.")
            } else log("⚠ AJUSTES DE SISTEMA: o celular recusou $nRec de $nTot (o resto foi aplicado e conferido)")
        }
        log("• Apps fechados nao avisam nada ate voce abrir · PROTEGER APP e OPCOES mudam isso")
        NLog.add("otimizacao", "ativada: $nC apps fechados")
        return true
    }

    // ---------------- DESATIVAR ----------------
    fun desativar(log: (String) -> Unit): Boolean = synchronized(opLock) { desativarSeguro(log) }

    private fun desativarSeguro(log: (String) -> Unit): Boolean {
        val ctx: Context = Sys.app
        if (!Adb.ensure(ctx)) {
            log("[x] ADB NAO CONECTADO — NADA FOI MUDADO")
            return false
        }
        val e = estado()
        if (e.isEmpty()) {
            log("[!] NADA PRA DESFAZER")
            log("• A otimizacao nao esta ativa")
            return true
        }
        val reNum = Regex("[0-9-]+")
        val reAl = Regex("[a-z-]+")
        val tokens = ArrayList<String>()
        val libera = ArrayList<String>()
        val wlimpa = ArrayList<String>()
        val anims = ArrayList<String>()
        val gms = ArrayList<String>()
        var fperf = 0
        for (l in e) {
            val p = l.split('|')
            when (p[0]) {
                "E", "L" -> if (p.size >= 5 && NOME_OK.matches(p[1]) && reNum.matches(p[2]) && reAl.matches(p[3]) && reAl.matches(p[4])) {
                    (if (p[0] == "E") tokens else libera).add("${p[1]}:${p[2]}:${p[3]}:${p[4]}")
                }
                "W" -> if (p.size >= 3 && NOME_OK.matches(p[1]) && p[2] == "0") wlimpa.add(p[1])
                "A" -> if (p.size >= 3 && Regex("[A-Za-z0-9.]*").matches(p[2])) {
                    var k = p[1]
                    if (Regex("[a-z_]+").matches(k)) k = "global.$k"
                    if (k in CHAVES_OK) anims.add("$k:${p[2]}")
                }
                "G" -> if (p.size >= 3 && NOME_OK.matches(p[1]) && (p[2] == "standard" || p[2] == "performance" || p[2] == "battery")) gms.add("${p[1]}:${p[2]}")
                "F" -> if (l == "F|1") fperf = 1
            }
        }
        val script = "LISTA=\"" + tokens.joinToString(" ") + "\"\n" +
            "LIBERA=\"" + libera.joinToString(" ") + "\"\n" +
            "ANIMS=\"" + anims.joinToString(" ") + "\"\n" +
            "WLIMPA=\"" + wlimpa.joinToString(" ") + "\"\n" +
            "GAMES=\"" + gms.joinToString(" ") + "\"\n" +
            "FPERF=$fperf\n" +
            asset("otm_cfg.sh") + "\n" + asset("otm_desfaz.sh")
        log("DESFAZENDO...")
        val r = Adb.exec(script, 90000)
        if (!r.out.lines().any { it.trim() == "FIM" }) {
            log("[x] O CELULAR NAO RESPONDEU — NADA FOI DESFEITO")
            log("• Tente de novo (o registro do que mudou continua guardado)")
            return false
        }
        val lines = r.out.lines()
        val v = lines.firstOrNull { it.startsWith("V|") }?.split('|')
        val s = lines.firstOrNull { it.startsWith("S|") }?.split('|')
        val ok = (v?.getOrNull(1)?.toIntOrNull() ?: 0) + (s?.getOrNull(1)?.toIntOrNull() ?: 0)
        val t = (v?.getOrNull(2)?.toIntOrNull() ?: 0) + (s?.getOrNull(2)?.toIntOrNull() ?: 0)
        if (ok >= t) {
            f("otm_estado").delete()
            log("[✓] OTIMIZACAO DESATIVADA!")
            log("• Apps liberados para rodar em segundo plano de novo")
            log("• Animacoes, tela, wi-fi e standby voltaram ao que eram")
            NLog.add("otimizacao", "desativada")
            return true
        }
        log("[!] VOLTOU $ok DE $t")
        log("• O celular nao aceitou todos · rode DESATIVAR de novo")
        return false
    }

    // ---------------- PROTEGER APP / FECHAR MAIS APPS ----------------
    /** alterna a protecao de UM app. why = motivo atual (vazio = vai fechar). Devolve a mensagem */
    fun alternaProtecao(p: String, why: String): String {
        if (!NOME_OK.matches(p)) return "NOME INVALIDO"
        val prot = lerLinhas("otm_prot").toMutableList()
        when (why) {
            "SUA LISTA", "" -> {
                return if (prot.contains(p)) {
                    prot.removeAll { it == p }
                    gravar("otm_prot", prot)
                    "$p VAI FECHAR NA OTIMIZACAO"
                } else {
                    prot.removeAll { it == "!$p" }
                    prot.add(p)
                    gravar("otm_prot", prot)
                    "$p PROTEGIDO — NUNCA FECHA"
                }
            }
            "MENSAGENS" -> {
                return if (prot.contains("!$p")) {
                    prot.removeAll { it == "!$p" }
                    gravar("otm_prot", prot)
                    "$p PROTEGIDO — NUNCA FECHA"
                } else {
                    prot.add("!$p")
                    gravar("otm_prot", prot)
                    "$p VAI FECHAR (nao recebe aviso enquanto congelado)"
                }
            }
            else -> return "$p ja fica protegido sozinho: $why"
        }
    }

    /** adiciona pelo nome do pacote. false = nome invalido */
    fun protegerPorNome(p: String): Boolean {
        val n = p.trim()
        if (!PKG_OK.matches(n)) return false
        val prot = lerLinhas("otm_prot").toMutableList()
        if (!prot.contains(n)) {
            prot.removeAll { it == "!$n" }
            prot.add(n)
            gravar("otm_prot", prot)
        }
        return true
    }

    /** liga/desliga "fechar junto" de um app do sistema. Devolve true se agora esta escolhido */
    fun alternaExtra(p: String): Boolean {
        if (!PKG_OK.matches(p)) return false
        val l = lerLinhas("otm_extra").toMutableList()
        return if (l.contains(p)) {
            l.removeAll { it == p }
            gravar("otm_extra", l)
            false
        } else {
            l.add(p)
            gravar("otm_extra", l)
            true
        }
    }

    // ---------------- PREPARAR O FF ----------------
    fun prepararFf(pkg: String, log: (String) -> Unit): Boolean {
        if (pkg != Const.FF_NORMAL && pkg != Const.FF_MAX) return false
        if (!Adb.ensure(Sys.app)) { log("[x] ADB NAO CONECTADO"); return false }
        if (Adb.exec("pm path $pkg </dev/null 2>/dev/null", 8000).out.trim().isEmpty()) {
            log("[x] ESSE FREE FIRE NAO ESTA INSTALADO")
            return false
        }
        log("COMPILANDO... (demora um pouco)")
        val r = Adb.exec("cmd package compile -m speed -f $pkg </dev/null 2>&1; cmd deviceidle whitelist +$pkg </dev/null 2>&1", 150000)
        return if (r.out.contains("Success")) {
            log("[✓] FF PREPARADO!")
            log("• Compilado para abrir e rodar mais rapido")
            log("• Fora da economia de energia do sistema")
            true
        } else {
            log("[x] O CELULAR NAO ACEITOU")
            log("• Confira se o ADB esta autorizado e tente de novo")
            false
        }
    }
}

/**
 * LIGAR SOZINHO AO ABRIR O FF: FF abriu = ativa a otimizacao; saiu do FF por um tempo = desativa (so desfaz o que ELE ativou).
 * Chamado pelo servico a cada 3 s.
 */
object OtmAuto {
    private const val POLL = 3
    private const val GRACA = 45
    private var sessao = false
    private var fora = 0

    private const val SONDA =
        "p=\$(pidof com.dts.freefireth com.dts.freefiremax 2>/dev/null); if [ -n \"\$p\" ]; then " +
            "if dumpsys activity activities </dev/null 2>/dev/null | grep -E \"ResumedActivity\" | head -3 | grep -q \"com.dts.freefire\"; " +
            "then echo FG; else echo BG; fi; else echo OFF; fi"

    private fun log(s: String) = NLog.add("otm-auto", s)

    private fun desliga() {
        if (Prefs.otmAutoMarca) {
            if (Otm.estado().isNotEmpty()) {
                log("desativando")
                Otm.desativar { log(it) }
            }
            Prefs.otmAutoMarca = false
        }
    }

    fun tick(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        if (!Prefs.otmAuto) return
        if (!Adb.connected) return
        val r = Adb.exec(SONDA, 8000)
        when (r.out.trim()) {
            "FG" -> {
                fora = 0
                if (!sessao) {
                    sessao = true
                    if (Otm.estado().isEmpty()) {                 // se voce ja tinha ligado na mao, nao mexe (e nao desliga depois)
                        Prefs.otmAutoMarca = true                // a marca vem ANTES: se parar no meio, sabe que foi ele e desfaz
                        log("FF abriu: ativando")
                        Otm.ativar { log(it) }
                        if (Otm.estado().isEmpty()) Prefs.otmAutoMarca = false
                    }
                }
            }
            "BG", "OFF" -> {
                if (sessao) {
                    fora += POLL
                    if (fora >= GRACA) {
                        log("saiu do FF")
                        desliga()
                        sessao = false
                        fora = 0
                    }
                }
            }
        }
    }

    fun ligar() {
        Prefs.otmAuto = true
        sessao = false
        fora = 0
        NLog.add("otm-auto", "ligado")
        NocryService.sync(Sys.app)
    }

    /** desliga o automatico e desfaz o que ele ligou */
    fun desligar() {
        Prefs.otmAuto = false
        desliga()
        sessao = false
        fora = 0
        NLog.add("otm-auto", "desligado")
        NocryService.sync(Sys.app)
    }
}
