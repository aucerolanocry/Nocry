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
    /** FULL: serviços do sistema que o bypass antigo fechava e que são seguros de fechar durante o jogo */
    private const val FULL_EXTRAS = "com.miui.cloudservice com.miui.cloudbackup com.miui.backup com.miui.micloudsync com.xiaomi.ota com.android.updater " +
        "com.miui.personalassistant com.miui.voiceassist com.xiaomi.aiasst.service com.xiaomi.aiasst.vision com.miui.yellowpage com.miui.readassistant " +
        "com.milink.service com.milink.crossdeviceservice com.xiaomi.mi_connect_service com.microsoft.appmanager com.microsoftsdk.crossdeviceservicebroker " +
        "com.xiaomi.barrage com.miui.screenrecorder com.miui.fm com.xiaomi.scanner com.miui.cleanmaster com.miui.android.fashiongallery com.miui.phrase " +
        "com.google.android.projection.gearhead com.facebook.services com.facebook.appmanager com.facebook.system com.google.android.googlequicksearchbox " +
        "com.google.android.tts com.google.android.apps.assistant com.miui.gallery com.miui.compass com.miui.calculator com.android.soundrecorder " +
        // mais da tela "Acelerar velocidade" da Xiaomi e outros que ficam na memória sem precisar
        "com.android.htmlviewer com.android.externalstorage com.mi.globalminusscreen com.miui.miservice com.miui.bugreport com.miui.huanji " +
        "com.xiaomi.discover com.miui.cleaner com.miui.virtualsim com.mi.android.globalFileexplorer com.android.fileexplorer com.miui.mediaviewer " +
        "com.xiaomi.mircs com.xiaomi.smarthome com.duokan.phone.remotecontroller com.miui.newmidrive com.xiaomi.midrop " +
        "com.google.android.apps.wellbeing com.google.android.apps.turbo com.google.android.feedback com.google.android.partnersetup " +
        "com.google.android.apps.restore com.google.android.apps.subscriptions.red com.google.android.apps.googleassistant com.google.android.apps.bard " +
        "com.google.android.apps.youtube.kids com.google.android.apps.magazines com.google.android.apps.podcasts " +
        "com.netflix.partner.activation com.amazon.appmanager com.facebook.katana com.instagram.android com.zhiliaoapp.musically com.ss.android.ugc.trill"
    private const val MIUI = "com.miui.analytics com.miui.msa.global com.miui.systemAdSolution com.miui.hybrid com.miui.videoplayer com.miui.player com.xiaomi.mipicks com.mi.globalbrowser com.miui.weather2 com.miui.notes com.miui.contentextension com.miui.vipaccount com.xiaomi.glgm com.miui.thirdappassistant"

    private val CRIT = Regex("^(android|com[.]android[.](settings|systemui|phone|dialer|contacts|mms|vending|stk|launcher|incallui|packageinstaller|permissioncontroller|documentsui|providers|server|bluetooth|nfc|shell|location|wifi|keychain|certinstaller|carrierconfig|storagemanager|webview|cellbroadcast|emergency|inputmethod)|com[.]google[.]android[.](gms|gsf|dialer|contacts|apps[.]messaging|packageinstaller|permissioncontroller|inputmethod|apps[.]nexuslauncher|setupwizard|webview|ext|networkstack|modulemetadata|captiveportallogin|safetycore)|com[.]miui[.](home|securitycenter|powerkeeper|system|core|phone|daemon|rom|face)|com[.]xiaomi[.](xmsf|account|finddevice|market)|com[.]samsung[.]android[.](incallui|dialer|messaging|app[.](contacts|telephony|spage|launcher)|knox|rubin|bixby|scs|fmm|security)|com[.]sec[.]android[.]|com[.](qualcomm|qti|mediatek)[.]|org[.]codeaurora[.])")
    private val EU = Regex.escape(Const.PKG)
    private val HARD = Regex("^(com[.]termux|com[.]dts[.]freefire(th|max)\$|moe[.]shizuku|com[.]draco[.]ladb|com[.]nocry[.]app|$EU\$)")
    private val LIB = Regex("^(com[.]termux([.][a-z0-9_]+)?|com[.]dts[.]freefire(th|max)|com[.]nocry[.]app|$EU)\$")
    private val PKG_OK = Regex("[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+")
    /** nunca entram sozinhos no FULL: fechar à força cancela o alarme do despertador, lembretes, pagamento por aproximação, contagem de passos */
    private val NUNCA_NO_FULL = Regex("(clock|alarm|calendar|agenda|wallet|[.]pay|pay[.]|health|fitness|[.]fit\\b|tasks|reminder|[.]keep\\b)", RegexOption.IGNORE_CASE)
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
        "system.haptic_feedback_enabled", "system.show_touches", "system.pointer_location", "global.disable_window_blurs", "global.automatic_power_save_mode",
        "global.cached_apps_freezer", "global.settings_enable_monitor_phantom_procs", "dc.max_phantom_processes",
        "system.sound_effects_enabled", "system.user_refresh_rate", "global.updatable_driver_production_opt_in_apps", "global.notification_bubbles", "secure.spell_checker_enabled",
        "global.network_recommendations_enabled", "global.tcp_default_init_rwnd", "dc.max_cached_processes"
    )

    // ---------------- arquivos ----------------
    private fun f(n: String) = File(Sys.app.filesDir, n)

    // a tela pergunta o estado a cada 1,2 s: só lê o arquivo de novo quando ele mudou
    private val lidos = HashMap<String, Triple<Long, Long, List<String>>>()

    private fun lerLinhas(n: String): List<String> = try {
        val arq = f(n)
        val mt = arq.lastModified()
        val tam = arq.length()
        synchronized(lidos) {
            val c = lidos[n]
            if (c != null && c.first == mt && c.second == tam) c.third
            else arq.readLines().map { it.trim() }.filter { it.isNotEmpty() }.also { lidos[n] = Triple(mt, tam, it) }
        }
    } catch (_: Exception) { emptyList() }

    private fun gravar(n: String, l: List<String>) {
        if (n == "otm_estado") statusCache = null
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
        val hz: Int, val setFail: Boolean, val svc: Set<String>,
        val libs: List<String>,
        val c: List<Cand>, val x: List<Prot>, val s: List<Sis>, val n: List<Nat>,
        /** FULL: apps instalados que NÃO estão rodando agora: congelam sem abrir (não ligam sozinhos em segundo plano) */
        val frio: List<String> = emptyList()
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

    /** por que a última leitura falhou (aparece no registro) */
    @Volatile var motivoLeitura = ""

    fun lerPlano(): Plano? {
        if (!Adb.connected) return null
        // celular lento ou com muitos apps: tempo maior e uma segunda tentativa antes de desistir
        for (tentativa in 0 until 2) {
            val r = Adb.execScript(asset("otm_cfg.sh") + "\n" + asset("otm_leitura.sh"), 75000)
            if (r.out.lines().any { it.trim() == "@FIM" }) { motivoLeitura = ""; return parse(r.out) }
            val fim = r.out.trim().lines().takeLast(2).joinToString(" | ").take(140)
            motivoLeitura = when {
                !Adb.connected -> "o ADB desconectou"
                r.out.isBlank() -> "o celular não respondeu" + (if (Adb.lastError.isNotEmpty()) " (${Adb.lastError})" else "")
                !r.ok -> "demorou demais (${r.out.lines().size} linhas lidas)"
                else -> "leitura cortada: $fim"
            }
            NLog.add("otimizacao", "leitura falhou (tentativa ${tentativa + 1}): $motivoLeitura")
            if (tentativa == 0) Thread.sleep(1500)
        }
        return null
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
        val svc = HashSet<String>()
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
                "SVC" -> {
                    val p = line.trim().substringBefore('/')
                    if (NOME_OK.matches(p) && p.contains('.')) svc.add(p)
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
        val uex = emptySet<String>()      // "fechar mais apps" saiu: no FULL já fecha todos os do sistema que podem
        val mg = MSG_APPS.split(' ').toSet()
        val ex = HashSet<String>(EXTRAS.split(' '))
        if (props["ro.miui.ui.version.name"].orEmpty().isNotEmpty() || props["ro.mi.os.version.name"].orEmpty().isNotEmpty()) ex.addAll(MIUI.split(' '))
        if (Prefs.otmNivel == "full") ex.addAll(FULL_EXTRAS.split(' '))
        val full = Prefs.otmNivel == "full"
        val msgFecha = full      // NORMAL: mensagens continuam avisando · FULL: fecham também

        fun razao(pk: String): String {
            if (HARD.containsMatchIn(pk)) return "NOCRY/FF"
            // relógio, alarme, agenda, carteira, saúde, tarefas e Keep nunca fecham (nem no FULL): antes a regra só
            // valia pros apps do sistema, e o Keep/Agenda/relógio instalados pela loja fechavam e perdiam os avisos
            if (NUNCA_NO_FULL.containsMatchIn(pk)) return "ALARMES/AGENDA"
            if (pk in up) return "SUA LISTA"
            if (pk in mg && pk !in ua && !msgFecha) return "MENSAGENS"
            if (pk in fg) return "EM USO AGORA"
            if (pk in home) return "LAUNCHER"
            val sp = setp[pk]
            if (sp != null) return ROT[sp] ?: sp
            // não deu pra ler as configurações: protege todo teclado, acessibilidade, leitor de notificação, SMS e telefone
            if (setfail && pk in svc) return "TECLADO/ACESSIBILIDADE"
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
            if (sel && (pk in uex || (full && !NUNCA_NO_FULL.containsMatchIn(pk)))) {
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
        // FULL: também os instalados que estão parados (antes só mexia no que estava rodando na hora)
        val sdk = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0
        val frio = if (!full || sdk < 26) emptyList() else {
            val rodando = prc.keys
            val todosApps = third + launch.filter { it in pkgall && !CRIT.containsMatchIn(it) } +
                ex.filter { it in pkgall && !CRIT.containsMatchIn(it) }
            todosApps.filter { pk ->
                pk !in rodando && NOME_OK.matches(pk) && pk.contains('.') && !HARD.containsMatchIn(pk) &&
                    !NUNCA_NO_FULL.containsMatchIn(pk) && razao(pk).isEmpty()
            }.distinct().sorted()
        }
        return Plano(props, mt, ma, mf, hz, setfail, svc, libs, c, x, s, n, frio)
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
    @Volatile private var statusCache: Pair<Long, Pair<Boolean, Int>>? = null

    /** a tela pergunta isso o tempo todo: confere o arquivo no máximo a cada 2 s */
    fun status(): Pair<Boolean, Int> {
        val agora = android.os.SystemClock.elapsedRealtime()
        statusCache?.let { if (agora - it.first < 2000) return it.second }
        return lerStatus().also { statusCache = agora to it }
    }

    private fun lerStatus(): Pair<Boolean, Int> {
        val e = estado()
        val n = e.count { it.startsWith("E|") }
        val t = e.count { it.length > 1 && it[1] == '|' && it[0] in "EALWFGNQDHX" }
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
                "N" -> if (l == "N|1" && !tem("N|")) e.add(l)
                "Q" -> if (l == "Q|1" && !tem("Q|")) e.add(l)
                "H" -> if (l == "H|1" && !tem("H|")) e.add(l)
                "X" -> if (PKG_OK.matches(p[1]) && !e.contains(l)) e.add(l)
                "D" -> if ((p[1] == "ON" || p[1].all { it.isDigit() }) && !e.contains(l)) e.add(l)
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
            if (motivoLeitura.isNotEmpty()) log("    motivo: $motivoLeitura")
            return false
        }
        if (plano.setFail) {
            // antes cancelava tudo aqui (era o "não funciona" em alguns celulares). Agora protege pelo plano B e continua
            if (plano.svc.isEmpty()) {
                log("[x] O CELULAR NAO DEIXOU LER AS CONFIGURACOES — NADA FOI MUDADO")
                log("    Reinicie o celular e tente de novo.")
                return false
            }
            log("⚠ O celular não deixou ler as configurações: protegendo todos os teclados, acessibilidade e notificações")
        }
        val full = Prefs.otmNivel == "full"
        // voltou pro NORMAL: a vigia (só do FULL) para
        if (!full && (Prefs.otmVigia || Prefs.otmComp)) desfazFull(log)
        val cand = plano.c.map { it.pk }
        val kbDe = plano.c.associate { it.pk to it.kb }
        val nC = cand.size
        val tot = plano.c.sumOf { it.kb }
        var hz = plano.hz
        if (hz < 90 || hz > 240) hz = 0
        val libs = plano.libs
        val games = libs.filter { it.startsWith("com.dts.freefire") }
        val perf = 0
        val msgs = cand.filter { it in MSG_APPS.split(' ') }.joinToString(", ") { nome(it) }

        val frio = if (full) plano.frio else emptyList()
        val script = "PKGS=\"" + cand.joinToString(" ") + "\"\n" +
            "FRIO=\"" + frio.joinToString(" ") + "\"\n" +
            "LIB=\"" + libs.joinToString(" ") + "\"\n" +
            "GAMES=\"" + games.joinToString(" ") + "\"\n" +
            "ANIM=1\nREDE=1\nEXTRA=1\nPERF=$perf\nHZ=$hz\nFULL=${if (full) 1 else 0}\n" +
            asset("otm_cfg.sh") + "\n" + asset("otm_aplica.sh")

        log(if (full) "OTIMIZANDO NO NÍVEL FULL..." else "OTIMIZANDO...")
        log("• CELULAR: " + plano.perfil())
        if (nC == 0) log("• APPS EM 2O PLANO: nenhum — ja estava limpo")
        else log("• FECHANDO $nC APPS (~${mb(tot)} MB):")
        if (frio.isNotEmpty()) log("• CONGELANDO ${frio.size} APPS INSTALADOS QUE ESTÃO PARADOS (não ligam sozinhos)")

        var kn = 0
        val res = Adb.execScriptLines(script, 240000) { l ->
            // cada mudança fica registrada na hora: se o NOCRY morrer no meio (limpeza da Xiaomi, travou),
            // o DESATIVAR ainda sabe o que desfazer. Antes só gravava quando o script inteiro acabava
            if (l.length > 1 && l[1] == '|' && l[0] in "ELWFNQHXDGA") try { guarda(l) } catch (_: Throwable) {}
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
        if (frio.isNotEmpty()) log("• APPS PARADOS CONGELADOS: ${frio.size} (abrem normal quando você tocar neles)")
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
        if ((nC > 0 || frio.isNotEmpty()) && resV < resT && !nivel.startsWith("SO FECHA")) {
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
        if (cfgOk(out, "system.sound_effects_enabled")) log("• SOM DE TOQUE NA TELA: DESLIGADO")
        if (cfgOk(out, "global.notification_bubbles")) log("• BALÕES DE CONVERSA: DESLIGADOS")
        if (cfgOk(out, "global.updatable_driver_production_opt_in_apps")) log("• DRIVER DE VÍDEO ATUALIZADO NO FF: vale quando abrir o FF de novo")
        if (lines.any { it == "MC|1" }) log("• MEMÓRIA: NO MÁXIMO 8 APPS PARADOS GUARDADOS")
        if (lines.any { it == "Q|1" }) log("• LOCALIZAÇÃO: DESLIGADA ATÉ DESATIVAR (o FF não usa)")
        if (lines.any { it == "D|ON" }) log("• ECONOMIA DE DADOS: em 2º plano só o FF e o NOCRY usam os dados móveis")
        if (lines.any { it == "N|1" }) log("• WI-FI: BAIXA LATÊNCIA LIGADA")
        if (lines.any { it == "H|1" }) log("• WI-FI: ALTO DESEMPENHO LIGADO")
        if (cfgOk(out, "system.user_refresh_rate")) log("• XIAOMI: TAXA DA TELA FIXA NO MÁXIMO")
        val xs = lines.filter { it.startsWith("X|") }
        if (xs.isNotEmpty()) log("• XIAOMI: PROPAGANDA E COLETA DE DADOS DESLIGADAS (${xs.size})")
        else if (full && plano.sdk >= 29) log("• WI-FI: o celular não deixa ligar a baixa latência pelo ADB (normal na maioria)")
        if (full) {
            Prefs.otmVigia = true
            NocryService.sync(Sys.app)
            log("• VIGIA: com o FF aberto, fecha de novo o que tentar voltar")
            if (games.isNotEmpty()) {
                log("• FREE FIRE: compilando em segundo plano (só se precisar)")
                Bg.io {
                    for (g in games) {
                        val r = Adb.exec("cmd package compile -m speed $g </dev/null 2>&1", 240000)
                        NLog.add("otimizacao", "compilar $g: " + (if (r.out.contains("Success")) "ok" else r.out.trim().take(80)))
                    }
                }
            }
        }
        log("• Apps fechados nao avisam nada ate voce abrir · PROTEGER APP e OPCOES mudam isso")
        NLog.add("otimizacao", "ativada${if (full) " (FULL)" else ""}: $nC apps fechados")
        return true
    }

    // ---------------- DESATIVAR ----------------
    fun desativar(log: (String) -> Unit): Boolean = synchronized(opLock) { desativarSeguro(log) }

    /** o que o FULL ligou fora do script: a vigia (o otmComp é só de rascunho antigo; o FULL não liga o modo competitivo) */
    private fun desfazFull(log: (String) -> Unit) {
        if (Prefs.otmVigia) {
            Prefs.otmVigia = false
            NocryService.sync(Sys.app)
        }
        if (Prefs.otmComp) {
            if (Comp.desativar(false)) { Prefs.otmComp = false; log("• Toque voltou ao normal") }
            else log("⚠ Não consegui devolver o toque: desative o Modo competitivo")
        }
    }

    private fun desativarSeguro(log: (String) -> Unit): Boolean {
        val ctx: Context = Sys.app
        if (!Adb.ensure(ctx)) {
            log("[x] ADB NAO CONECTADO — NADA FOI MUDADO")
            return false
        }
        val e = estado()
        if (e.isEmpty()) {
            desfazFull(log)
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
        var redeLat = 0
        var local = 0
        var hiPerf = 0
        val xLiga = ArrayList<String>()
        var dSaver = 0
        val dUids = ArrayList<String>()
        for (l in e) {
            val p = l.split('|')
            when (p[0]) {
                "E", "L" -> if (p.size >= 5 && NOME_OK.matches(p[1]) && reNum.matches(p[2]) && reAl.matches(p[3]) && reAl.matches(p[4])) {
                    (if (p[0] == "E") tokens else libera).add("${p[1]}:${p[2]}:${p[3]}:${p[4]}")
                }
                "W" -> if (p.size >= 3 && NOME_OK.matches(p[1]) && p[2] == "0") wlimpa.add(p[1])
                "A" -> if (p.size >= 3 && Regex("[A-Za-z0-9._,]*").matches(p[2])) {
                    var k = p[1]
                    if (Regex("[a-z_]+").matches(k)) k = "global.$k"
                    if (k in CHAVES_OK) anims.add("$k:${p[2]}")
                }
                "G" -> if (p.size >= 3 && NOME_OK.matches(p[1]) && (p[2] == "standard" || p[2] == "performance" || p[2] == "battery")) gms.add("${p[1]}:${p[2]}")
                "F" -> if (l == "F|1") fperf = 1
                "N" -> if (l == "N|1") redeLat = 1
                "Q" -> if (l == "Q|1") local = 1
                "H" -> if (l == "H|1") hiPerf = 1
                "X" -> if (p.size >= 2 && PKG_OK.matches(p[1])) xLiga.add(p[1])
                "D" -> if (p.size >= 2) { if (p[1] == "ON") dSaver = 1 else if (p[1].all { it.isDigit() }) dUids.add(p[1]) }
            }
        }
        val script = "LISTA=\"" + tokens.joinToString(" ") + "\"\n" +
            "LIBERA=\"" + libera.joinToString(" ") + "\"\n" +
            "ANIMS=\"" + anims.joinToString(" ") + "\"\n" +
            "WLIMPA=\"" + wlimpa.joinToString(" ") + "\"\n" +
            "GAMES=\"" + gms.joinToString(" ") + "\"\n" +
            "FPERF=$fperf\nREDELAT=$redeLat\nLOCAL=$local\nHIPERF=$hiPerf\nXLIGA=\"" + xLiga.joinToString(" ") + "\"\n" +
            "DSAVER=$dSaver\nDUIDS=\"" + dUids.joinToString(" ") + "\"\n" +
            asset("otm_cfg.sh") + "\n" + asset("otm_desfaz.sh")
        log("DESFAZENDO...")
        desfazFull(log)
        val r = Adb.execScript(script, 180000)
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
            statusCache = null
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

    // ---------------- LIMPAR CACHE / COMPILAR TODOS ----------------
    /** apaga o cache de todos os apps (miniaturas, temporários). Nada importante: cada app refaz o seu */
    fun limparCache(log: (String) -> Unit): Boolean {
        if (!Adb.ensure(Sys.app)) { log("[x] ADB NAO CONECTADO"); return false }
        val livre = "df /data </dev/null 2>/dev/null | tail -1 | tr -s ' ' | cut -d' ' -f4"
        log("LIMPANDO O CACHE DE TODOS OS APPS...")
        val r = Adb.exec("a=\$($livre); pm trim-caches 999999999999 </dev/null 2>&1; sleep 1; b=\$($livre); echo \"L|\$a|\$b\"", 120000)
        val l = r.out.lines().firstOrNull { it.startsWith("L|") }?.split('|')
        val a = l?.getOrNull(1)?.toLongOrNull()
        val b = l?.getOrNull(2)?.toLongOrNull()
        if (a == null || b == null) { log("[x] O CELULAR NAO RESPONDEU"); return false }
        log("[✓] CACHE LIMPO!")
        log("• LIBEROU ~${maxOf(0L, (b - a) / 1024)} MB de armazenamento")
        log("• Celular com armazenamento cheio fica lento: vale fazer de vez em quando")
        return true
    }

    /** compila todos os apps como o Android faz de madrugada (abre e roda mais rápido). Demora */
    fun compilarTudo(log: (String) -> Unit): Boolean {
        if (!Adb.ensure(Sys.app)) { log("[x] ADB NAO CONECTADO"); return false }
        log("COMPILANDO TODOS OS APPS...")
        log("• Pode levar de 2 a 15 minutos. Deixe o celular parado e na tomada se der.")
        val r = Adb.exec("cmd package bg-dexopt-job </dev/null 2>&1", 1_200_000)
        return if (r.ok && !r.out.contains("Failure") && !r.out.contains("xception")) {
            log("[✓] APPS COMPILADOS!")
            log("• Os apps abrem e rodam mais rápido")
            true
        } else {
            log("[x] O CELULAR NAO TERMINOU (${r.out.trim().take(80)})")
            log("• Tente de novo com o celular na tomada")
            false
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

    /** echo FG (FF na tela), BG (aberto atrás) ou OFF */
    const val SONDA =
        "p=\$(pidof com.dts.freefireth com.dts.freefiremax 2>/dev/null); if [ -n \"\$p\" ]; then " +
            "if dumpsys activity activities </dev/null 2>/dev/null | grep -E \"ResumedActivity\" | head -3 | grep -q \"com.dts.freefire\"; " +
            "then echo FG; else echo BG; fi; else echo OFF; fi"

    private fun log(s: String) = NLog.add("otm-auto", s)

    private fun desliga() {
        if (Prefs.otmAutoMarca) {
            var ok = true
            if (Otm.estado().isNotEmpty()) {
                log("desativando")
                ok = Otm.desativar { log(it) }
            }
            // se falhou (ex.: o ADB caiu na hora), a marca fica: na próxima volta ele tenta desfazer de novo.
            // Antes a marca sumia e, no FF seguinte, a otimização parecia "ligada na mão" e nunca mais desligava
            if (ok) Prefs.otmAutoMarca = false
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
        Presenca.evento("modo_on", "OTIMIZAÇÃO AUTOMÁTICA")
    }

    /** desliga o automatico e desfaz o que ele ligou */
    fun desligar() {
        val estava = Prefs.otmAuto
        Prefs.otmAuto = false
        desliga()
        sessao = false
        fora = 0
        NLog.add("otm-auto", "desligado")
        NocryService.sync(Sys.app)
        if (estava) Presenca.evento("modo_off", "OTIMIZAÇÃO AUTOMÁTICA")
    }
}

/**
 * Vigia do FULL: com a otimização FULL ativa e o FF aberto na tela, a cada ~20 s fecha de novo os apps
 * congelados que voltaram sozinhos (notificação, alarme do próprio app...). Chamado pelo serviço a cada 3 s.
 */
object OtmVigia {
    private var ciclo = 0
    private val PKG = Regex("[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+")

    fun tick() {
        if (!Prefs.otmVigia) return
        if (++ciclo % 7 != 0) return
        if (!Adb.connected) return
        val pkgs = Otm.estado().filter { it.startsWith("E|") }.mapNotNull { it.split('|').getOrNull(1) }.filter { PKG.matches(it) }.distinct()
        if (pkgs.isEmpty()) return
        val script = "fg=\$(" + OtmAuto.SONDA + "); [ \"\$fg\" = FG ] || { echo FORA; exit 0; }; " +
            "psl=\$(ps -A -o NAME </dev/null 2>/dev/null); " +
            "for p in " + pkgs.joinToString(" ") + "; do echo \"\$psl\" | grep -qE \"^\$p(:.*)?\\\$\" && " +
            "{ am force-stop \"\$p\" </dev/null >/dev/null 2>&1; echo \"K|\$p\"; }; done; echo FIM"
        val r = Adb.exec(script, 15000)
        val fechou = r.out.lines().filter { it.startsWith("K|") }.map { Otm.nome(it.substring(2)) }
        if (fechou.isNotEmpty()) NLog.add("otm-vigia", "voltaram e fechei de novo: " + fechou.joinToString(", "))
    }
}
