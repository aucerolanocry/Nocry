package com.nocry.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Token do Discord guardado cifrado com a chave do próprio Android (não sai do celular). */
object Cofre {
    private const val ALIAS = "nocry_sniper"
    private fun sp(c: Context) = c.getSharedPreferences("sniper_cofre", Context.MODE_PRIVATE)

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return g.generateKey()
    }

    fun guarda(c: Context, texto: String) {
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, chave())
        val enc = ci.doFinal(texto.toByteArray())
        sp(c).edit().putString("tk", Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun le(c: Context): String? = try {
        val v = sp(c).getString("tk", null)
        if (v == null) null else {
            val (a, b) = v.split(":", limit = 2)
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(a, Base64.NO_WRAP)))
            String(ci.doFinal(Base64.decode(b, Base64.NO_WRAP)))
        }
    } catch (_: Throwable) { null }

    fun tem(c: Context): Boolean = sp(c).contains("tk")
    fun apaga(c: Context) { sp(c).edit().remove("tk").apply() }
}

class SBotoes(val normal: List<String>, val infinito: List<String>, val outros: List<String>)
class SOrg(val nome: String, val rotulos: Map<String, String>, val botoes: SBotoes?, val filas: Map<String, Map<String, String>>, val pendentes: Int)
class SLinha(val hora: String, val tipo: String, val texto: String)
class SChat(val id: String, val hora: String, val tipo: String, val nome: String, val texto: String)

class SSala(val id: String, val nome: String, val org: String, val guildId: String?, val thread: Boolean) {
    val aberta = System.currentTimeMillis()
    @Volatile var ultima = aberta
    @Volatile var respondida = false
    @Volatile var inativa = false
    @Volatile var msgEnviada = false
    @Volatile var confirmada = false
    @Volatile var naoLidas = 0
    @Volatile var ultimoId: String? = null
    @Volatile var ultimoPoll = 0L
    @Volatile var gatewayEm = 0L
    val vistos: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val chat = ArrayList<SChat>()
}

/**
 * SNIPER: entra sozinho nas filas de aposta das orgs, abre as salas, manda a mensagem automática,
 * mostra o chat e deixa confirmar/cancelar. Mesma lógica do bot do Termux (motor.py), só que dentro do app.
 * Só funciona com a key de DONO.
 */
object Sniper {
    // ---------------- ajustes ----------------
    const val MAX_SALAS = 8
    const val COOLDOWN = 3_000L           // espera depois de entrar numa fila
    const val PAUSA_ENTRE_FILAS = 600L    // dentro da mesma org (as orgs rodam todas ao mesmo tempo)
    const val TIMEOUT_RESPOSTA = 30_000L  // sala sem resposta: sai
    const val TIMEOUT_INATIVA = 10 * 60_000L
    const val ESQUECER_SUMIDA = 10 * 60_000L
    const val SALA_NOVA = 30 * 60_000L
    const val PAUSA_SEM_MEDIADOR = 5 * 60_000L
    const val PAUSA_BLOQUEIO = 15 * 60_000L
    const val MSG_PADRAO = "# FULL UMP E XM8 SEM MOBILADOR E SEM BUG DE KIT"
    val MODOS = listOf("1X1", "2X2", "3X3", "4X4")
    private val PALAVRAS_SALA = listOf("aguardando", "partida", "match", "vs", "sala", "duelo", "confronto")
    private val PALAVRAS_APOSTA = listOf("aposta confirmada", "aposta gerada", "aposta criada")
    private val PALAVRAS_SAIR = listOf("SAIR", "CANCEL")
    private val NUNCA_CLICAR = listOf("TICKET", "SUPORTE", "ATENDIMENTO", "REGRA", "DENUN", "AJUDA", "SAQUE", "SACAR",
        "DEPOSIT", "PIX", "PERFIL", "CADASTR", "VERIFIC", "ABRIR")
    private val DINHEIRO = Regex("R\\$\\s*(\\d{1,5})(?:[.,](\\d{1,2}))?", RegexOption.IGNORE_CASE)

    // ---------------- dados ----------------
    private lateinit var app: Context
    private lateinit var sp: SharedPreferences
    var orgs: LinkedHashMap<String, SOrg> = LinkedHashMap()
        private set
    var semValor: List<String> = emptyList()
        private set
    /** filas sem valor ainda: org -> modo -> ids */
    var pendentes: Map<String, Map<String, List<String>>> = emptyMap()
        private set
    @Volatile var descobrindo = false
    @Volatile var progresso = ""
    fun qtdPendentes(): Int = pendentes.values.sumOf { m -> m.values.sumOf { it.size } }
    val listaOrgs: List<String> get() = listOf("TODAS") + orgs.keys
    var todosValores: List<String> = emptyList()
        private set

    // ---------------- escolha ----------------
    @Volatile var org = "TODAS"
    val modos: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val valores: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var gelo = "NORMAL"
    @Volatile var maxFilas = 20
    @Volatile var msgAuto = MSG_PADRAO

    // ---------------- estado ----------------
    @Volatile var buscando = false
    @Volatile var inicioBusca = 0L
    @Volatile var conectado = false
    @Volatile var estado = "desligado"     // desligado | conectando | online | caiu | token
    @Volatile var nomeConta = ""
    @Volatile var eu = ""
    @Volatile var apostas = 0
    @Volatile var entradas = 0
    val versao = AtomicLong()
    val salas = ConcurrentHashMap<String, SSala>()
    val ordem = CopyOnWriteArrayList<String>()
    @Volatile var salaAtiva: String? = null
    val filasEntradas: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** org -> (texto, tipo) do que está acontecendo com ela (ok, info, erro, pausa) */
    val statusOrg = ConcurrentHashMap<String, Pair<String, String>>()
    private val registro = ArrayList<SLinha>()

    private val largadas: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val canalDaFila = ConcurrentHashMap<String, String>()
    private val canaisDaOrg = ConcurrentHashMap<String, MutableSet<String>>()
    private val sumida = ConcurrentHashMap<String, Long>()
    private val orgSumida = ConcurrentHashMap<String, Long>()
    private val orgDoServidor = ConcurrentHashMap<String, String>()
    private val falhasOrg = ConcurrentHashMap<String, Int>()
    private val jaAvisou: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val entrouEm = ConcurrentHashMap<String, Long>()
    private val ultimaFila = ConcurrentHashMap<String, Pair<String, String>>()
    private val orgPausada = ConcurrentHashMap<String, Long>()

    // Discord: servidores e canais conhecidos
    private val guildas = ConcurrentHashMap<String, String>()
    private val canais = ConcurrentHashMap<String, DCanal>()
    private val canaisPorGuilda = ConcurrentHashMap<String, List<String>>()

    @Volatile private var dc: Discord? = null
    @Volatile private var exSalas: ScheduledExecutorService? = null
    @Volatile private var exFilas: ScheduledExecutorService? = null
    private val giroOrg = ConcurrentHashMap<String, Int>()
    private val orgRodando: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var ultimaVarredura = 0L

    private val fmtHora = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }
    private fun agora() = System.currentTimeMillis()
    private fun hora(ms: Long = agora()): String = synchronized(fmtHora) { fmtHora.format(Date(ms)) }

    // ======================================================================== início
    fun init(c: Context) {
        app = c.applicationContext
        sp = app.getSharedPreferences("sniper", Context.MODE_PRIVATE)
        carregarOrgs()
        org = sp.getString("org", "TODAS")?.takeIf { it == "TODAS" || orgs.containsKey(it) } ?: "TODAS"
        modos.clear(); modos.addAll((sp.getString("modos", "3X3") ?: "3X3").split(",").filter { it in MODOS })
        valores.clear(); valores.addAll((sp.getString("valores", "300") ?: "300").split(",").filter { it in todosValores })
        if (valores.isEmpty() && todosValores.isNotEmpty()) valores.add(if ("300" in todosValores) "300" else todosValores[0])
        gelo = sp.getString("gelo", "NORMAL") ?: "NORMAL"
        maxFilas = sp.getInt("max_filas", 20)
        msgAuto = sp.getString("msg_auto", MSG_PADRAO) ?: MSG_PADRAO
        try {
            val o = JSONObject(sp.getString("cache_filas", "{}") ?: "{}")
            o.optJSONObject("filas")?.let { f -> f.keys().forEach { k -> f.s(k)?.let { canalDaFila[k] = it } } }
            o.optJSONObject("servidores")?.let { f -> f.keys().forEach { k -> f.s(k)?.let { orgDoServidor[k] = it } } }
            o.optJSONObject("canais_org")?.let { f ->
                f.keys().forEach { k ->
                    val a = f.optJSONArray(k) ?: return@forEach
                    val set: MutableSet<String> = ConcurrentHashMap.newKeySet()
                    for (i in 0 until a.length()) set.add(a.optString(i))
                    canaisDaOrg[k] = set
                }
            }
        } catch (_: Throwable) {}
    }

    /** o bot estava ligado (o Android fechou o app e o serviço voltou): liga de novo */
    var ligadoPref: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean("ligado", false) else false
        private set(v) { sp.edit().putBoolean("ligado", v).apply() }

    private fun carregarOrgs() {
        val txt = try { app.assets.open("sniper_orgs.json").bufferedReader().use { it.readText() } } catch (_: Throwable) { "{}" }
        val o = JSONObject(txt)
        val apr = try { JSONObject(sp.getString("aprendidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val out = LinkedHashMap<String, SOrg>()
        val sem = ArrayList<String>()
        val pendAll = LinkedHashMap<String, Map<String, List<String>>>()
        for (nome in o.keys()) {
            val d = o.optJSONObject(nome) ?: continue
            val rot = HashMap<String, String>()
            d.optJSONObject("rotulos")?.let { r -> r.keys().forEach { k -> r.s(k)?.let { rot[k] = it } } }
            val b = d.optJSONObject("botoes")
            val botoes = if (b == null) null else {
                fun lista(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).uppercase() }
                val um = b.optJSONObject("1X1")
                SBotoes(lista(um?.optJSONArray("NORMAL")), lista(um?.optJSONArray("INFINITO")), lista(b.optJSONArray("outros_modos")))
            }
            val filas = LinkedHashMap<String, LinkedHashMap<String, String>>()
            d.optJSONObject("filas")?.let { f ->
                for (modo in f.keys()) {
                    val m = f.optJSONObject(modo) ?: continue
                    val mm = LinkedHashMap<String, String>()
                    m.keys().forEach { v -> m.s(v)?.let { mm[v] = it } }
                    if (mm.isNotEmpty()) filas[modo] = mm
                }
            }
            val pend = LinkedHashMap<String, MutableList<String>>()
            d.optJSONObject("pendentes")?.let { p ->
                for (modo in p.keys()) {
                    val a = p.optJSONArray(modo) ?: continue
                    pend[modo] = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toMutableList()
                }
            }
            // valores que o "descobrir valores" já leu das filas (ficam salvos no celular)
            apr.optJSONObject(nome)?.let { a ->
                for (modo in a.keys()) {
                    val mm = a.optJSONObject(modo) ?: continue
                    for (v in mm.keys()) {
                        val id = mm.s(v) ?: continue
                        val f = filas.getOrPut(modo) { LinkedHashMap() }
                        if (!f.containsKey(v)) f[v] = id
                        pend[modo]?.remove(id)
                    }
                }
            }
            val pendLimpo = pend.filterValues { it.isNotEmpty() }
            if (pendLimpo.isNotEmpty()) pendAll[nome] = pendLimpo
            if (filas.isEmpty()) { if (pendLimpo.isNotEmpty()) sem.add(nome); continue }
            out[nome] = SOrg(nome, rot, botoes, filas, pendLimpo.values.sumOf { it.size })
        }
        orgs = out
        semValor = sem
        pendentes = pendAll
        todosValores = out.values.flatMap { it.filas.values.flatMap { f -> f.keys } }.toSet().sortedBy { it.toIntOrNull() ?: 0 }
    }

    private fun salvarEscolha() {
        sp.edit()
            .putString("org", org)
            .putString("modos", modos.joinToString(","))
            .putString("valores", valores.joinToString(","))
            .putString("gelo", gelo)
            .putInt("max_filas", maxFilas)
            .putString("msg_auto", msgAuto)
            .apply()
    }

    private fun salvarCache() {
        val o = JSONObject()
        val f = JSONObject(); canalDaFila.forEach { (k, v) -> f.put(k, v) }
        val g = JSONObject(); orgDoServidor.forEach { (k, v) -> g.put(k, v) }
        o.put("filas", f)
        o.put("servidores", g)
        val co = JSONObject()
        canaisDaOrg.forEach { (k, v) -> co.put(k, JSONArray(v.toList())) }
        o.put("canais_org", co)
        sp.edit().putString("cache_filas", o.toString()).apply()
    }

    private fun vendorId(): String {
        val v = sp.getString("vendor", null)
        if (v != null) return v
        val n = UUID.randomUUID().toString()
        sp.edit().putString("vendor", n).apply()
        return n
    }

    // ======================================================================== textos
    fun reais(c: String): String {
        val n = c.toIntOrNull() ?: return c
        return if (n % 100 == 0) "${n / 100}" else "${n / 100},${(n % 100).toString().padStart(2, '0')}"
    }
    fun rotuloValor(v: String) = "R$ " + reais(v)
    fun textoValores(vs: Collection<String>): String {
        val l = vs.sortedBy { it.toIntOrNull() ?: 0 }
        return if (l.isEmpty()) "sem valor" else "R$ " + l.joinToString(" · ") { reais(it) }
    }
    fun rotuloFila(org: String, v: String) = orgs[org]?.rotulos?.get(v) ?: rotuloValor(v)

    private fun mudou() { versao.incrementAndGet() }

    fun log(tipo: String, texto: String) {
        synchronized(registro) {
            registro.add(SLinha(hora(), tipo, texto))
            while (registro.size > 160) registro.removeAt(0)
        }
        NLog.add("sniper", texto)
        mudou()
    }

    fun logs(n: Int): List<SLinha> = synchronized(registro) { registro.takeLast(n) }

    private fun avisarUmaVez(chave: String, tipo: String, texto: String) {
        if (jaAvisou.add(chave)) log(tipo, texto)
    }

    private fun status(org: String, texto: String, tipo: String) {
        statusOrg[org] = texto to tipo
        mudou()
    }

    // ======================================================================== escolha
    fun orgsAlvo(): List<String> = if (org == "TODAS") orgs.keys.toList() else listOf(org)

    fun valoresDisponiveis(): Set<String> {
        val tem = HashSet<String>()
        for (o in orgsAlvo()) for (m in (if (modos.isEmpty()) MODOS else modos.toList())) orgs[o]?.filas?.get(m)?.keys?.let { tem.addAll(it) }
        return tem
    }

    /** (org, modo, valor, id) de cada fila que bate com a escolha. Fila dupla (10/20) entra uma vez só. */
    fun filasAlvo(): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        val vistos = HashSet<String>()
        for (o in orgsAlvo()) {
            val od = orgs[o] ?: continue
            for (m in modos.sorted()) {
                val f = od.filas[m] ?: continue
                for (v in valores.sortedBy { it.toIntOrNull() ?: 0 }) {
                    val fid = f[v] ?: continue
                    if (vistos.add(fid)) out.add(arrayOf(o, m, v, fid))
                }
            }
        }
        return out
    }

    fun trocarOrg(nome: String) {
        org = nome
        log("info", "org: $nome")
        val falta = valores - valoresDisponiveis()
        if (falta.isNotEmpty() && falta.size == valores.size) log("erro", "nenhum valor marcado existe em $nome nesses modos")
        else if (falta.isNotEmpty()) log("info", "${textoValores(falta)} não tem em $nome · procura só os outros")
        salvarEscolha(); mudou()
    }

    fun alternarModo(m: String) { if (!modos.remove(m)) modos.add(m); salvarEscolha(); mudou() }

    fun alternarValor(v: String) {
        if (valores.contains(v)) valores.remove(v)
        else if (v !in valoresDisponiveis()) { log("erro", "${rotuloValor(v)} não existe em $org nesses modos"); return }
        else valores.add(v)
        salvarEscolha(); mudou()
    }

    fun escolherGelo(g: String) { gelo = g; salvarEscolha(); mudou() }
    fun definirMaxFilas(n: Int) { maxFilas = n.coerceIn(1, 40); salvarEscolha(); mudou() }
    fun definirMsgAuto(t: String) { msgAuto = t.ifBlank { MSG_PADRAO }; salvarEscolha(); mudou() }

    fun alternarBusca() {
        if (!buscando) {
            if (!conectado) { log("erro", "ligue o bot primeiro (conta não conectada)"); return }
            if (modos.isEmpty()) { log("erro", "escolha pelo menos um modo"); return }
            if (valores.isEmpty()) { log("erro", "escolha pelo menos um valor"); return }
            val fs = filasAlvo()
            if (fs.isEmpty()) { log("erro", "nenhuma fila com ${textoValores(valores)} em $org nesses modos"); return }
            buscando = true
            inicioBusca = agora()
            statusOrg.clear()
            log("info", "busca iniciada · $org · ${modos.sorted().joinToString(" ")} · ${textoValores(valores)} · ${fs.size} filas")
        } else {
            buscando = false
            log("info", "busca pausada (as filas em que já entrou continuam)")
        }
        mudou()
    }

    // ======================================================================== ligar / desligar
    fun ligar(): Boolean {
        if (!License.isOwner()) { log("erro", "o sniper é só pra key de dono"); return false }
        if (dc != null) return true
        val tk = Cofre.le(app)
        if (tk.isNullOrBlank()) { log("erro", "cole o token da conta primeiro"); return false }
        ligadoPref = true
        estado = "conectando"
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sniper-salas").apply { isDaemon = true } }
        // uma busca por org, todas ao mesmo tempo
        val f = Executors.newScheduledThreadPool(orgs.size.coerceIn(2, 20)) { r -> Thread(r, "sniper-filas").apply { isDaemon = true } }
        exSalas = s
        exFilas = f
        val d = Discord(tk.trim(), vendorId(), ouvinte)
        dc = d
        log("info", "conectando no Discord...")
        s.execute { seguro { d.conectar() } }
        s.scheduleWithFixedDelay({ seguro { vigiarSalas() } }, 4, 2, TimeUnit.SECONDS)
        f.scheduleWithFixedDelay({ seguro { distribuir() } }, 6, 1, TimeUnit.SECONDS)
        NocryService.sync(app)
        mudou()
        return true
    }

    fun desligar(lembrar: Boolean = false) {
        buscando = false
        conectado = false
        try { dc?.desligar() } catch (_: Throwable) {}
        dc = null
        exSalas?.shutdownNow(); exSalas = null
        exFilas?.shutdownNow(); exFilas = null
        orgRodando.clear()
        if (estado != "token") estado = "desligado"
        if (!lembrar) ligadoPref = false
        salas.clear(); ordem.clear(); salaAtiva = null
        filasEntradas.clear()
        log("info", "bot desligado")
        try { NocryService.sync(app) } catch (_: Throwable) {}
    }

    fun ligado(): Boolean = dc != null

    fun salvarToken(t: String) {
        val limpo = t.trim().removePrefix("\"").removeSuffix("\"")
        Cofre.guarda(app, limpo)
        log("ok", "token salvo (fica só neste celular)")
    }

    fun apagarToken() {
        desligar()
        Cofre.apaga(app)
        log("info", "token apagado")
    }

    private inline fun seguro(f: () -> Unit) {
        try { f() } catch (_: InterruptedException) { Thread.currentThread().interrupt() } catch (t: Throwable) {
            log("erro", "erro interno: ${t.javaClass.simpleName}: ${t.message ?: ""}".take(180))
        }
    }

    private fun naFila(r: Runnable) { exSalas?.execute { seguro { r.run() } } }

    private val ouvinte = object : Discord.Ouvinte {
        override fun pronto(eu: String, nome: String, d: JSONObject) {
            naFila { aoPronto(eu, nome, d) }
        }

        override fun evento(tipo: String, d: JSONObject) {
            naFila { aoEvento(tipo, d) }
        }

        override fun caiu(motivo: String, fatal: Boolean) {
            conectado = false
            log(if (fatal) "erro" else "info", motivo)
            if (fatal) {
                estado = if ("token" in motivo) "token" else "caiu"
                Bg.io { desligar() }
            } else estado = "caiu"
            mudou()
        }
    }

    // ======================================================================== eventos
    private fun aoPronto(id: String, nome: String, d: JSONObject) {
        eu = id
        nomeConta = nome
        conectado = true
        estado = "online"
        log("ok", "conectado como $nome")
        d.optJSONArray("guilds")?.let { gs ->
            for (i in 0 until gs.length()) gs.optJSONObject(i)?.let { guilda(it) }
        }
        // nome dos servidores pela API (o READY às vezes vem sem nome)
        val r = dc?.servidores()
        r?.arr()?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                val gid = g.s("id") ?: continue
                guildas[gid] = g.s("name") ?: guildas[gid] ?: ""
            }
        }
        // pede os eventos de thread dos servidores das orgs (salas "aguardando")
        val dosOrgs = guildas.keys.filter { adivinharOrg(it) != "?" }
        for (gid in dosOrgs) {
            dc?.assinar(gid)
            Thread.sleep(350)
        }
        log("info", "${guildas.size} servidores · ${dosOrgs.size} de org · escolha org, modo e valor e toque em INICIAR BUSCA")
        mudou()
    }

    private fun guilda(g: JSONObject) {
        val gid = g.s("id") ?: return
        val nome = g.s("name") ?: g.optJSONObject("properties")?.s("name")
        if (nome != null) guildas[gid] = nome
        g.optJSONArray("channels")?.let { cs ->
            val ids = ArrayList<String>()
            for (i in 0 until cs.length()) {
                val c = canalDe(cs.optJSONObject(i) ?: continue, gid)
                canais[c.id] = c
                if (c.ehTexto) ids.add(c.id)
            }
            if (ids.isNotEmpty()) canaisPorGuilda[gid] = ordenarCanais(ids)
        }
        g.optJSONArray("threads")?.let { ts ->
            for (i in 0 until ts.length()) {
                val c = canalDe(ts.optJSONObject(i) ?: continue, gid)
                canais[c.id] = c
            }
        }
    }

    private fun aoEvento(t: String, d: JSONObject) {
        when (t) {
            "RESUMED" -> { conectado = true; estado = "online"; log("info", "reconectado") }
            "GUILD_CREATE" -> guilda(d)
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE", "THREAD_UPDATE" -> {
                val c = canalDe(d)
                if (c.id.isEmpty()) return
                val antes = canais[c.id]
                if (antes != null && antes.membro) c.membro = true
                canais[c.id] = c
                if (c.ehTexto && c.guildId != null) canaisPorGuilda[c.guildId]?.let { l -> if (c.id !in l) canaisPorGuilda[c.guildId] = l + c.id }
                if ((t == "CHANNEL_CREATE" || t.startsWith("THREAD")) && ehMinhaSala(c)) abrirSala(c)
            }
            "CHANNEL_DELETE", "THREAD_DELETE" -> {
                val id = d.s("id") ?: return
                canais.remove(id)
                if (salas.containsKey(id)) fecharSala(id)
            }
            "THREAD_LIST_SYNC" -> {
                val gid = d.s("guild_id")
                d.optJSONArray("threads")?.let { ts ->
                    for (i in 0 until ts.length()) {
                        val c = canalDe(ts.optJSONObject(i) ?: continue, gid)
                        canais[c.id] = c
                        if (ehMinhaSala(c)) abrirSala(c)
                    }
                }
            }
            "THREAD_MEMBERS_UPDATE" -> {
                val tid = d.s("id") ?: return
                val add = d.optJSONArray("added_members") ?: return
                var euEntrei = false
                for (i in 0 until add.length()) if (add.optJSONObject(i)?.s("user_id") == eu) euEntrei = true
                if (!euEntrei) return
                val c = canais[tid] ?: dc?.canal(tid)?.obj()?.let { canalDe(it, d.s("guild_id")) } ?: return
                c.membro = true
                canais[c.id] = c
                if (ehMinhaSala(c)) abrirSala(c)
            }
            "MESSAGE_CREATE" -> aoMsg(DMsg.de(d), true)
        }
    }

    // ======================================================================== salas
    private fun normal(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun adivinharOrg(gid: String?, nomeSala: String = ""): String {
        if (gid != null) orgDoServidor[gid]?.let { return it }
        val texto = normal((if (gid != null) guildas[gid] ?: "" else "") + " " + nomeSala)
        for (o in orgs.keys) if (normal(o) in texto) return o
        return "?"
    }

    private fun ehSala(nome: String): Boolean {
        val n = nome.lowercase()
        return PALAVRAS_SALA.any { it in n }
    }

    private fun meMarcou(m: DMsg): Boolean {
        if (eu.isEmpty()) return false
        if (m.mencoes.any { it.first == eu }) return true
        val txt = m.conteudo + " " + m.embeds.joinToString(" ") { it.titulo + " " + it.descricao }
        return txt.contains("<@$eu>") || txt.contains("<@!$eu>")
    }

    /** canal/thread é uma sala sua? (thread "aguardando", canal privado "partida-11", ou sala nova que te marcou) */
    private fun ehMinhaSala(c: DCanal, marcou: Boolean = false): Boolean {
        val gid = c.guildId ?: return false
        if (salas.containsKey(c.id) || largadas.contains(c.id)) return false
        if (canalDaFila.containsValue(c.id)) return false
        if (adivinharOrg(gid) == "?") return false
        val novo = agora() - c.criadoMs < SALA_NOVA
        val nome = ehSala(c.nome)
        val naFila = agora() - (entrouEm[gid] ?: 0L) < SALA_NOVA
        if (c.ehThread) return (nome && (c.membro || novo || naFila)) || (naFila && novo && (marcou || c.privado))
        return c.tipo == 0 && novo && (nome || (naFila && (marcou || c.privado)))
    }

    fun numeroSala(id: String): Int = ordem.indexOf(id) + 1

    private fun abrirSala(c: DCanal): SSala? {
        if (largadas.contains(c.id)) return null
        salas[c.id]?.let { return it }
        val o = adivinharOrg(c.guildId, c.nome)
        val s = SSala(c.id, c.nome, o, c.guildId, c.ehThread)
        salas[c.id] = s
        ordem.add(c.id)
        filasEntradas.clear()
        if (salaAtiva == null || !salas.containsKey(salaAtiva!!)) salaAtiva = c.id
        log("ok", "sala ${numeroSala(c.id)} aberta · $o · ${c.nome}")
        notificar("Sala aberta · $o", c.nome)
        mudou()
        exSalas?.schedule(Runnable { seguro { prepararSala(s) } }, 2, TimeUnit.SECONDS)
        return s
    }

    private fun prepararSala(s: SSala) {
        val d = dc ?: return
        if (!salas.containsKey(s.id)) return
        if (s.thread) d.entrarThread(s.id)
        val r = d.enviar(s.id, msgAuto)
        if (r.ok) {
            s.msgEnviada = true
        } else if (r.codigo == 403 || r.erro() == 50013 || r.erro() == 50001) {
            log("info", "${s.nome}: sem permissão de escrever · não é sala de partida, ignorando")
            fecharSala(s.id)
            return
        } else {
            log("erro", "não deu pra mandar a mensagem automática na sala ${numeroSala(s.id)} (${motivo(r)})")
        }
        carregarHistorico(s)
    }

    private fun carregarHistorico(s: SSala) {
        val d = dc ?: return
        val (ms, _) = d.historico(s.id, 50, null, s.guildId)
        val lista = ms.sortedBy { it.id.toLongOrNull() ?: 0L }
        synchronized(s.chat) {
            s.chat.clear()
            for (m in lista) {
                s.vistos.add(m.id)
                s.chat.add(linhaChat(m))
            }
        }
        lista.lastOrNull()?.let { s.ultimoId = it.id }
        mudou()
    }

    private fun linhaChat(m: DMsg): SChat {
        val tipo = if (m.autorId == eu) "voce" else if (m.autorBot) "bot" else "pessoa"
        val nome = if (m.autorId == eu) "você" else m.autorNome
        return SChat(m.id, hora(Snow.ms(m.id)), tipo, nome, textoMsg(m))
    }

    fun fecharSala(id: String) {
        largadas.add(id)
        salas.remove(id)
        ordem.remove(id)
        if (salaAtiva == id) salaAtiva = ordem.lastOrNull()
        mudou()
    }

    private fun maior(a: String?, b: String): String {
        val x = a?.toLongOrNull() ?: return b
        val y = b.toLongOrNull() ?: return a
        return if (y > x) b else a
    }

    private fun aoMsg(m: DMsg, viaGateway: Boolean) {
        if (m.guildId == null && salas[m.canalId] == null) return
        if (m.autorId == eu) {
            val s = salas[m.canalId] ?: return
            if (s.vistos.add(m.id)) {
                s.ultimoId = maior(s.ultimoId, m.id)
                synchronized(s.chat) { s.chat.add(linhaChat(m)) }
                mudou()
            }
            return
        }
        if (ehRespostaDaFila(m)) { respostaDaFila(m); return }
        var s = salas[m.canalId]
        if (s == null) {
            val marcou = meMarcou(m)
            val c = canais[m.canalId] ?: (if (marcou) dc?.canal(m.canalId)?.obj()?.let { canalDe(it, m.guildId) } else null)
            if (c != null) {
                canais[c.id] = c
                if (ehMinhaSala(c, marcou)) s = abrirSala(c)
            }
        }
        if (s == null) return
        if (!s.vistos.add(m.id)) return
        if (viaGateway) s.gatewayEm = agora()
        s.ultimoId = maior(s.ultimoId, m.id)
        s.ultima = agora()
        s.respondida = true
        s.inativa = false
        synchronized(s.chat) {
            s.chat.add(linhaChat(m))
            while (s.chat.size > 120) s.chat.removeAt(0)
        }
        if (s.id != salaAtiva) {
            s.naoLidas++
            if (s.naoLidas == 1) log("info", "mensagem nova na sala ${numeroSala(s.id)} (${s.org})")
        }
        val txt = (m.conteudo + " " + m.embeds.joinToString(" ") { it.titulo }).lowercase()
        if (PALAVRAS_APOSTA.any { it in txt }) {
            apostas++
            log("ganhou", "aposta confirmada · sala ${numeroSala(s.id)} · ${s.org}")
            notificar("Aposta confirmada · ${s.org}", s.nome)
            fecharSala(s.id)
        }
        mudou()
    }

    private fun vigiarSalas() {
        val d = dc ?: return
        val t = agora()
        for (s in salas.values.toList()) {
            val n = numeroSala(s.id)
            if (!s.respondida && t - s.aberta > TIMEOUT_RESPOSTA) {
                log("info", "ninguém respondeu em 30s na sala $n (${s.org}), saindo")
                clicarSair(s)
                fecharSala(s.id)
                filasEntradas.clear()
                continue
            }
            if (t - s.ultima > TIMEOUT_INATIVA && !s.inativa) {
                s.inativa = true
                filasEntradas.clear()
                log("info", "sala $n parada há 10 min, liberando a vaga")
            }
            // o Discord não mandou nada dessa sala há um tempo: busca as mensagens novas direto
            if (s.ultimoId != null && t - s.gatewayEm > 15_000 && t - s.ultimoPoll > 3_000) {
                s.ultimoPoll = t
                val (ms, r) = d.historico(s.id, 50, s.ultimoId, s.guildId)
                if (r.codigo == 404 || r.erro() == 10003) { fecharSala(s.id); continue }
                for (m in ms.sortedBy { it.id.toLongOrNull() ?: 0L }) aoMsg(m, false)
            }
        }
        // servidor em que acabou de entrar numa fila: confere se apareceu canal novo (sala tipo "partida-11")
        if (t - ultimaVarredura > 20_000) {
            ultimaVarredura = t
            for ((gid, quando) in entrouEm) {
                if (t - quando > SALA_NOVA) continue
                val r = d.canais(gid)
                val a = r.arr() ?: continue
                val ids = ArrayList<String>()
                for (i in 0 until a.length()) {
                    val c = canalDe(a.optJSONObject(i) ?: continue, gid)
                    val novo = !canais.containsKey(c.id)
                    canais[c.id] = c
                    if (c.ehTexto) ids.add(c.id)
                    if (novo && ehMinhaSala(c)) abrirSala(c)
                }
                if (ids.isNotEmpty()) canaisPorGuilda[gid] = ordenarCanais(ids)
            }
        }
    }

    private fun clicarSair(s: SSala): Boolean {
        val d = dc ?: return false
        val (ms, _) = d.historico(s.id, 20, null, s.guildId)
        for (m in ms) for (b in m.botoes) {
            if (!b.desativado && PALAVRAS_SAIR.any { b.label.uppercase().contains(it) }) {
                if (d.clicar(m, b).ok) return true
            }
        }
        return false
    }

    private fun clicarConfirmar(s: SSala): Boolean {
        val d = dc ?: return false
        val (ms, _) = d.historico(s.id, 50, null, s.guildId)
        for (m in ms) for (b in m.botoes) {
            val l = b.label.uppercase()
            if (!b.desativado && "CONFIRM" in l && "CANCEL" !in l) {
                if (d.clicar(m, b).ok) { s.confirmada = true; return true }
            }
        }
        return false
    }

    private fun salaAtual(): SSala? = salaAtiva?.let { salas[it] } ?: ordem.lastOrNull()?.let { salas[it] }

    // ---------------- ações da tela ----------------
    fun ativarSala(i: Int) {
        val id = ordem.getOrNull(i) ?: run { log("erro", "a sala ${i + 1} não está aberta"); return }
        salaAtiva = id
        salas[id]?.naoLidas = 0
        mudou()
    }

    fun enviar(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        if (t.all { it.isDigit() } && t.length == 1 && t.toInt() in 1..MAX_SALAS) { ativarSala(t.toInt() - 1); return }
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta"); return@naFila }
            val r = dc?.enviar(s.id, t) ?: return@naFila
            if (r.ok) {
                r.obj()?.let { aoMsg(DMsg.de(it, s.guildId), false) }
            } else log("erro", "não deu pra enviar (${motivo(r)})")
        }
    }

    fun confirmar() {
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta pra confirmar"); return@naFila }
            if (clicarConfirmar(s)) log("ganhou", "confirmado na sala ${numeroSala(s.id)}")
            else log("erro", "botão de confirmar não encontrado ou desativado")
        }
    }

    fun cancelar() {
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta pra cancelar"); return@naFila }
            val n = numeroSala(s.id)
            if (clicarSair(s)) {
                log("info", "cancelado na sala $n (${s.org})")
                fecharSala(s.id)
                filasEntradas.clear()
            } else log("erro", "botão de cancelar/sair não encontrado nessa sala")
        }
    }

    /** larga a sala sem clicar em nada (some do painel) */
    fun largar() {
        val s = salaAtual() ?: return
        fecharSala(s.id)
        log("info", "sala largada: ${s.nome}")
    }

    // ======================================================================== mensagens da fila
    private fun ehRespostaDaFila(m: DMsg): Boolean {
        if (salas.containsKey(m.canalId)) return false
        if (m.efemera) return true
        if (m.interacaoUser != null && m.interacaoUser == eu) return true
        return canalDaFila.containsValue(m.canalId) && meMarcou(m)
    }

    private fun respostaDaFila(m: DMsg) {
        val o = adivinharOrg(m.guildId)
        val texto = textoCard(m).ifEmpty { "(mensagem vazia)" }
        val baixo = texto.lowercase()
        val ultima = ultimaFila[o]
        val nome = ultima?.second ?: o
        fun largarOrg(pausa: Long, motivo: String) {
            if (ultima != null) filasEntradas.remove(ultima.first)
            if (orgs.containsKey(o)) orgPausada[o] = agora() + pausa
            log("erro", "$nome: $motivo · pulando a $o por ${pausa / 60_000} min")
            status(o, "⏸ $motivo (${pausa / 60_000} min)", "pausa")
        }
        log("info", "$o diz: ${texto.take(160)}")
        if ("mediador" in baixo && listOf("sem ", "não", "nao", "nenhum", "offline").any { it in baixo }) {
            largarOrg(PAUSA_SEM_MEDIADOR, "sem mediador online")
        } else if (listOf("saldo", "insuficiente", "bloque", "banido", "cargo", "verifica", "registr", "permiss").any { it in baixo }) {
            largarOrg(PAUSA_BLOQUEIO, "a org não deixou entrar")
        } else if (listOf("já está", "ja esta", "já se encontra", "ja se encontra", "outra fila").any { it in baixo }) {
            log("info", "$nome: a org diz que você já está em fila")
        }
        mudou()
    }

    private fun textoCard(m: DMsg): String {
        val partes = ArrayList<String>()
        partes.add(m.conteudo)
        for (e in m.embeds) {
            partes.add(e.titulo); partes.add(e.descricao)
            for (f in e.campos) partes.add(f.nome + " " + f.valor)
        }
        return partes.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("<@!?\\d+>"), "@você")
            .trim()
    }

    /** texto de uma mensagem do chat: troca <@123> pelo nome (você vira @VOCÊ) */
    private fun textoMsg(m: DMsg): String {
        val partes = ArrayList<String>()
        if (m.conteudo.isNotEmpty()) partes.add(m.conteudo)
        m.embeds.firstOrNull()?.let { e ->
            val t = (e.titulo.ifEmpty { e.descricao }).trim().lineSequence().firstOrNull() ?: ""
            partes.add(("[CARD] $t").trim())
        }
        if (m.botoes.isNotEmpty()) partes.add("[" + m.botoes.joinToString(" | ") { it.label.uppercase() } + "]")
        var t = partes.joinToString("  ").ifEmpty { "(vazio)" }
        for ((id, nome) in m.mencoes) {
            val n = if (id == eu) "@VOCÊ" else "@$nome"
            t = t.replace("<@$id>", n).replace("<@!$id>", n)
        }
        if (eu.isNotEmpty()) t = t.replace("<@$eu>", "@VOCÊ").replace("<@!$eu>", "@VOCÊ")
        return t.replace(Regex("<@!?\\d+>"), "@alguém").replace(Regex("<@&\\d+>"), "@cargo").replace(Regex("<#\\d+>"), "#canal")
    }

    /** valor escrito na mensagem da fila (em centavos), ou null */
    fun lerValor(m: DMsg): String? {
        fun achar(t: String): List<String> = DINHEIRO.findAll(t).map { r ->
            val a = r.groupValues[1].toInt()
            val b = r.groupValues[2]
            (a * 100 + (if (b.isEmpty()) 0 else b.padEnd(2, '0').toInt())).toString()
        }.toList()
        val tudo = StringBuilder(m.conteudo)
        for (e in m.embeds) {
            for (f in e.campos) {
                val n = f.nome.uppercase()
                if (listOf("VALOR", "APOSTA", "ENTRADA").any { it in n }) {
                    val v = achar(f.valor).ifEmpty { achar(f.nome) }
                    if (v.isNotEmpty()) return v[0]
                }
                tudo.append(' ').append(f.nome).append(' ').append(f.valor)
            }
            val t = achar(e.titulo)
            if (t.toSet().size == 1) return t[0]
            tudo.append(' ').append(e.titulo).append(' ').append(e.descricao)
        }
        val vs = achar(tudo.toString()).toSet()
        return if (vs.size == 1) vs.first() else null
    }

    private fun motivo(r: Resp): String = when {
        r.captcha() -> "o Discord pediu captcha"
        r.codigo == -1 -> "sem internet"
        r.codigo == 401 -> "token inválido"
        r.codigo == 403 -> "sem permissão"
        r.codigo == 404 -> "não existe mais"
        else -> "código ${r.codigo}" + (r.obj()?.s("message")?.let { ": " + it.take(80) } ?: "")
    }

    // ======================================================================== filas
    /** servidor de um canal: o clique no botão precisa dele (a API não manda junto da mensagem) */
    private fun gidDe(cid: String): String? {
        canais[cid]?.guildId?.let { return it }
        val c = dc?.canal(cid)?.obj()?.let { canalDe(it) } ?: return null
        if (c.id.isNotEmpty()) canais[c.id] = c
        return c.guildId
    }

    private fun ordenarCanais(ids: List<String>): List<String> {
        val chaves = listOf("fila", "x1", "1v1", "2v2", "3v3", "4v4", "mob", "emu", "apost", "x2", "x3", "x4")
        return ids.sortedBy { id -> val n = (canais[id]?.nome ?: "").lowercase(); if (chaves.any { it in n }) 0 else 1 }
    }

    private fun textosDe(gid: String): List<String> {
        canaisPorGuilda[gid]?.let { return it }
        val a = dc?.canais(gid)?.arr() ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until a.length()) {
            val c = canalDe(a.optJSONObject(i) ?: continue, gid)
            canais[c.id] = c
            if (c.ehTexto) ids.add(c.id)
        }
        val l = ordenarCanais(ids)
        canaisPorGuilda[gid] = l
        return l
    }

    /** acha a mensagem da fila. Depois da 1ª vez já sabe o canal e vai direto (fica salvo). */
    private fun acharFila(fid: String, o: String, forcar: Boolean = false): DMsg? {
        val d = dc ?: return null
        val t = agora()
        if (!forcar && (t - (sumida[fid] ?: 0L) < ESQUECER_SUMIDA || t - (orgSumida[o] ?: 0L) < ESQUECER_SUMIDA)) return null
        canalDaFila[fid]?.let { cid ->
            val (m, r) = d.mensagem(cid, fid, gidDe(cid))
            if (m != null) return m
            if (r.ok || r.codigo == 403 || r.codigo == 404) canalDaFila.remove(fid) else return null
        }
        if (!forcar) status(o, "procurando as filas...", "info")
        val conhecidos = canaisDaOrg[o]?.toList() ?: emptyList()
        val candidatos = LinkedHashSet<String>()
        if (conhecidos.isNotEmpty()) {
            candidatos.addAll(conhecidos)
            conhecidos.mapNotNull { canais[it]?.guildId }.toSet().forEach { candidatos.addAll(textosDe(it)) }
        } else {
            // primeiro os servidores com o nome da org, depois o resto
            val gs = guildas.keys.sortedBy { if (normal(o) in normal(guildas[it] ?: "")) 0 else 1 }
            for (gid in gs) candidatos.addAll(textosDe(gid))
        }
        for (cid in candidatos) {
            if (!forcar && !buscando) return null
            if (dc == null) return null
            val (m, r) = d.mensagem(cid, fid, canais[cid]?.guildId)
            if (m != null) {
                canalDaFila[fid] = cid
                canaisDaOrg.getOrPut(o) { ConcurrentHashMap.newKeySet() }.add(cid)
                canais[cid]?.guildId?.let { orgDoServidor[it] = o }
                falhasOrg[o] = 0
                salvarCache()
                return m
            }
            if (r.codigo == 401) return null
            Thread.sleep(120)
        }
        if (forcar) return null
        sumida[fid] = t
        if (conhecidos.isEmpty()) {
            val f = (falhasOrg[o] ?: 0) + 1
            falhasOrg[o] = f
            if (f >= 2) {
                falhasOrg[o] = 0
                orgSumida[o] = t
                log("erro", "não achei as filas da $o (você está no servidor?) · tento de novo em 10 min")
                status(o, "✗ não achei as filas (está no servidor?)", "erro")
            }
        } else status(o, "✗ uma fila dela não existe mais (ID velho)", "erro")
        return null
    }

    private fun acharBotao(m: DMsg, o: String, modo: String): DBotao? {
        val botoes = m.botoes.filter { b -> NUNCA_CLICAR.none { b.label.uppercase().contains(it) } }
        val cfg = orgs[o]?.botoes
        if (cfg != null) {
            val alvos = if (modo == "1X1") (if (gelo == "INFINITO") cfg.infinito else cfg.normal) else cfg.outros
            for (a in alvos) botoes.firstOrNull { a in it.label.uppercase() }?.let { return it }
            return null
        }
        val entrar = botoes.filter { b -> PALAVRAS_SAIR.none { b.label.uppercase().contains(it) } }
        val chave = if (gelo == "INFINITO") "INF" else "NORMAL"
        var b = entrar.firstOrNull { chave in it.label.uppercase() }
        if (b == null && modo == "1X1") {
            if (gelo == "NORMAL" && entrar.size == 1 && "INF" !in entrar[0].label.uppercase()) b = entrar[0]
        } else if (b == null) {
            b = entrar.firstOrNull { e -> listOf("ENTRAR", "JOGAR", "FULL").any { it in e.label.uppercase() } } ?: entrar.firstOrNull()
        }
        if (b != null) avisarUmaVez("auto|$o|$modo|$gelo", "info", "$o $modo: entrando pelo botão '${b.label.uppercase()}'")
        return b
    }

    /** a cada 1 s: toda org do alvo que não está rodando ganha uma rodada própria (todas ao mesmo tempo) */
    private fun distribuir() {
        val ex = exFilas ?: return
        if (!buscando || !conectado || filasEntradas.size >= maxFilas) return
        val porOrg = filasAlvo().groupBy { it[0] }
        for ((o, lista) in porOrg) {
            if (agora() < (orgPausada[o] ?: 0L)) continue
            if (!orgRodando.add(o)) continue
            try {
                ex.execute {
                    try { seguro { rodadaOrg(o, lista) } } finally { orgRodando.remove(o) }
                }
            } catch (_: Throwable) { orgRodando.remove(o) }
        }
    }

    private fun rodadaOrg(org: String, alvo: List<Array<String>>) {
        val d = dc ?: return
        if (!buscando || !conectado || filasEntradas.size >= maxFilas) return
        if (alvo.isEmpty()) return
        val n = alvo.size
        val inicio = (giroOrg[org] ?: 0) % n
        giroOrg[org] = (inicio + 1) % n
        for (k in 0 until n) {
            if (!buscando || filasEntradas.size >= maxFilas) return
            val (o, modo, v, fid) = alvo[(inicio + k) % n]
            val nome = "$o $modo ${rotuloFila(o, v)}"
            if (fid in filasEntradas) continue
            val pausa = orgPausada[o] ?: 0L
            if (agora() < pausa) continue
            val m = acharFila(fid, o)
            if (m == null) { Thread.sleep(PAUSA_ENTRE_FILAS); continue }
            var jogadores = ""
            for (e in m.embeds) for (f in e.campos) if ("JOGADOR" in f.nome.uppercase()) jogadores = f.valor
            val lido = lerValor(m)
            val texto = textoCard(m).uppercase()
            val botaoConhecido = orgs[o]?.botoes != null && acharBotao(m, o, modo) != null
            if (lido == null && "JOGADOR" !in texto && "FILA" !in texto && !botaoConhecido) {
                avisarUmaVez("naofila|$fid", "erro", "$nome: esse ID não parece uma fila (sem valor/jogadores) · não cliquei")
                status(o, "✗ ID de $modo ${rotuloFila(o, v)} não é fila", "erro")
                Thread.sleep(PAUSA_ENTRE_FILAS)
                continue
            }
            val aceitos = orgs[o]?.filas?.get(modo)?.filter { it.value == fid }?.keys ?: emptySet()
            if (lido != null && lido !in aceitos) {
                avisarUmaVez("valor|$fid", "erro", "fila $nome: a mensagem diz ${rotuloValor(lido)} · confira esse ID")
            }
            val botao = acharBotao(m, o, modo)
            if (botao == null) {
                val labels = m.botoes.joinToString(", ") { it.label.uppercase() }.ifEmpty { "nenhum" }
                avisarUmaVez("botao|$fid|$gelo", "erro", "fila $nome: não achei o botão de entrar · botões: $labels")
                status(o, "✗ botão diferente: $labels".take(70), "erro")
            } else if (eu.isNotEmpty() && jogadores.contains(eu)) {
                // você já está nessa fila (entrou antes ou pelo Discord)
                filasEntradas.add(fid)
                status(o, "✓ na fila $modo ${rotuloFila(o, v)}", "ok")
            } else if (botao.desativado) {
                status(o, "fila $modo ${rotuloFila(o, v)} fechada agora", "info")
            } else {
                val r = d.clicar(m, botao)
                if (r.ok) {
                    filasEntradas.add(fid)
                    ultimaFila[o] = fid to nome
                    m.guildId?.let { entrouEm[it] = agora() }
                    entradas++
                    log("ok", "entrou na fila $nome")
                    status(o, "✓ na fila $modo ${rotuloFila(o, v)}", "ok")
                    Thread.sleep(COOLDOWN)
                } else {
                    log("erro", "não deu pra entrar na fila $nome (${motivo(r)})")
                    status(o, "✗ ${motivo(r)}", "erro")
                    if (r.captcha()) {
                        buscando = false
                        log("erro", "busca pausada: o Discord pediu captcha. Abra o Discord, faça alguma coisa normal e tente de novo depois")
                        return
                    }
                }
            }
            Thread.sleep(PAUSA_ENTRE_FILAS)
        }
    }

    // ======================================================================== descobrir valores
    /** abre cada fila sem valor (GOLD, FARM...), lê o "Valor: R$ ..." dela e guarda. Só lê, não entra em fila. */
    fun descobrir() {
        if (descobrindo) return
        if (!conectado || dc == null) { log("erro", "ligue o bot primeiro"); return }
        val lista = ArrayList<Triple<String, String, String>>()
        for ((o, ms) in pendentes) for ((m, ids) in ms) for (id in ids) lista.add(Triple(o, m, id))
        if (lista.isEmpty()) { log("info", "nenhuma fila sem valor"); return }
        descobrindo = true
        log("info", "descobrindo o valor de ${lista.size} filas (só lê, não entra em nenhuma)...")
        val th = Thread {
            var achou = 0
            val faltou = ArrayList<String>()
            val apr = try { JSONObject(sp.getString("aprendidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            val semServidor = HashSet<String>()
            val semAchar = HashMap<String, Int>()
            try {
                for ((i, t) in lista.withIndex()) {
                    if (dc == null) break
                    val (o, m, id) = t
                    if (o in semServidor) continue
                    progresso = "lendo $o $m (${i + 1}/${lista.size})"
                    mudou()
                    val msg = acharFila(id, o, forcar = true)
                    if (msg == null) {
                        if (canaisDaOrg[o].isNullOrEmpty()) {
                            val n = (semAchar[o] ?: 0) + 1
                            semAchar[o] = n
                            if (n >= 2) { semServidor.add(o); faltou.add("$o: não achei nenhuma fila (você está no servidor?)") }
                        } else faltou.add("$o $m: não achei a fila $id")
                        continue
                    }
                    val v = lerValor(msg)
                    if (v == null) { faltou.add("$o $m: não deu pra ler o valor da fila $id"); continue }
                    val jaTem = orgs[o]?.filas?.get(m)?.get(v) ?: apr.optJSONObject(o)?.optJSONObject(m)?.s(v)
                    if (jaTem != null && jaTem != id) { faltou.add("$o $m: a fila $id diz ${rotuloValor(v)}, mas já tem outra com esse valor"); continue }
                    val jo = apr.optJSONObject(o) ?: JSONObject().also { apr.put(o, it) }
                    val jm = jo.optJSONObject(m) ?: JSONObject().also { jo.put(m, it) }
                    jm.put(v, id)
                    achou++
                    log("ok", "$o $m: fila de ${rotuloValor(v)} descoberta")
                    Thread.sleep(300)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                log("erro", "descobrir: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                sp.edit().putString("aprendidos", apr.toString()).apply()
                carregarOrgs()
                descobrindo = false
                progresso = ""
                log("ok", "pronto · $achou filas descobertas" + (if (faltou.isNotEmpty()) " · ${faltou.size} ficaram sem valor" else ""))
                for (f in faltou.take(12)) log("erro", f)
                mudou()
            }
        }
        th.isDaemon = true
        th.name = "sniper-descobrir"
        th.start()
    }

    // ======================================================================== aviso no celular
    private fun notificar(titulo: String, texto: String) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            val abre = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java).putExtra("abrir", "sniper"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, Notif.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(abre)
                .setAutoCancel(true)
                .build()
            nm.notify(5001, n)
        } catch (_: Throwable) {}
    }

    fun resumo(): String = when {
        !ligado() -> ""
        buscando -> "SNIPER buscando · ${filasEntradas.size} filas · ${salas.size} salas"
        else -> "SNIPER ${estado.uppercase()}"
    }
}
