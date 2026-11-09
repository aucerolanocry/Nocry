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
class SChat(val id: String, val hora: String, val tipo: String, val nome: String, val texto: String,
            val imagem: String? = null, val imagemGrande: String? = null)

/** um campo do formulário (modal) que a org abre: texto ou lista de opções */
class SCampo(val chave: String, val rotulo: String, val dica: String, val tipo: Int, val customId: String,
             val obrigatorio: Boolean, val inicial: String, val opcoes: List<Pair<String, String>>, val padrao: List<String>,
             val minimo: Int, val maximo: Int)

/** formulário que a org abriu depois de um clique (DUCK, FURIA, HELIPA...) */
class SForm(val nonce: String, val modalId: String, val customId: String, val appId: String, val canalId: String, val guildId: String?,
            val titulo: String, val org: String, val nome: String, val fid: String?, val salaId: String?,
            val textos: List<String>, val campos: List<SCampo>, val bruto: org.json.JSONArray,
            /** "verificação de segurança" (conta, pergunta): só você responde, o bot nunca responde nem guarda */
            val verificacao: Boolean = false) {
    val chegou = System.currentTimeMillis()
}

class SSala(val id: String, val nome: String, val org: String, val guildId: String?, val thread: Boolean) {
    val aberta = System.currentTimeMillis()
    /** a org cancelou/fechou (te tirou da sala ou não deixa mais escrever) */
    @Volatile var encerrada = false
    /** abriu só porque a org te colocou na thread (nome não parece sala): só cancela sozinho se tiver card de confirmar */
    @Volatile var incerta = false
    @Volatile var temConfirmar = false
    @Volatile var ultima = aberta
    @Volatile var respondida = false
    @Volatile var inativa = false
    @Volatile var msgEnviada = false
    @Volatile var confirmada = false
    @Volatile var naoLidas = 0
    @Volatile var ultimoId: String? = null
    @Volatile var ultimoPoll = 0L
    @Volatile var gatewayEm = 0L
    /** a sala não deixa escrever (fica no painel só pra ver) */
    @Volatile var somenteLeitura = false
    /** saiu "aposta confirmada": a sala fica no painel até a org apagar ou você tirar */
    @Volatile var apostaOk = false
    /** confirmada (aposta ou você tocou em confirmar): nada de sumir sozinha */
    val protegida: Boolean get() = apostaOk || confirmada
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
    const val TIMEOUT_RESPOSTA = 30_000L  // padrão do "tempo pra responder" (dá pra mudar em Ajustes)
    const val TIMEOUT_INATIVA = 10 * 60_000L
    const val ESQUECER_SUMIDA = 10 * 60_000L
    const val SALA_NOVA = 30 * 60_000L
    const val PAUSA_SEM_MEDIADOR = 5 * 60_000L
    const val PAUSA_BLOQUEIO = 15 * 60_000L
    const val PAUSA_LIMITE = 45_000L      // org disse "limite máximo de filas": espera isso e tenta de novo
    const val FORM_VALIDO = 12 * 60_000L  // formulário aberto vale uns 15 min no Discord
    const val MSG_PADRAO = "# FULL UMP E XM8 SEM MOBILADOR E SEM BUG DE KIT"
    val MODOS = listOf("1X1", "2X2", "3X3", "4X4")
    private val PALAVRAS_SALA = listOf("aguardando", "partida", "match", "vs", "sala", "duelo", "confronto")
    /** canal com esse nome nunca é sala de partida */
    private val NAO_SALA = listOf("ticket", "suporte", "atendimento", "denuncia", "denúncia", "saque", "pix", "duvida", "dúvida",
        "parceria", "compra", "loja", "log", "regra", "anuncio", "anúncio", "verifica", "registro", "ranking",
        "bot", "atualiz", "config", "staff", "admin", "moderac", "comando", "cargo", "aviso", "chat-geral", "geral")
    private val PALAVRAS_APOSTA = listOf("aposta confirmada", "aposta gerada", "aposta criada")
    private val CANCELADA = listOf(Regex("(partida|aposta|sala|fila) (foi )?(cancelad|encerrad|finalizad|expirad)"), Regex("foi cancelad"),
        Regex("tempo (esgotado|expirou|acabou)"), Regex("expirou"), Regex("nao confirm(ou|aram) (a tempo|em)"))
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
    @Volatile var maxFilas = 300
    @Volatile var msgAuto = MSG_PADRAO
    /** sala sem resposta de PESSOA nesse tempo: clica em sair/cancelar e volta a procurar */
    @Volatile var tempoResposta = TIMEOUT_RESPOSTA

    // ---------------- estado ----------------
    @Volatile var buscando = false
    @Volatile var inicioBusca = 0L
    @Volatile var conectado = false
    @Volatile var estado = "desligado"     // desligado | conectando | online | caiu | token
    @Volatile var nomeConta = ""
    @Volatile var usuario = ""
    @Volatile var eu = ""
    /** abriu sala: sai das outras filas e pausa a busca (volta sozinho se a sala fechar sem aposta) */
    @Volatile var sairAoAbrir = false
    /** já começa a buscar quando o bot conecta */
    @Volatile var buscarAoLigar = false
    @Volatile private var pausadoPorSala = false
    private val cliques = ConcurrentHashMap<String, Array<String>>()      // nonce -> (id da fila, org, nome)
    /** todo clique (fila ou sala): nonce -> (tipo "fila"/"sala", id, org, nome, quando) · fica uns minutos */
    private val cliqueInfo = ConcurrentHashMap<String, Array<String>>()
    /** o que a org fez com o clique: "ok", "form" (abriu formulário) ou "falha" */
    private val desfecho = ConcurrentHashMap<String, String>()
    private val travaEntrada = Object()
    /** formulários que o bot não soube responder sozinho: você responde uma vez e ele lembra */
    val formularios = CopyOnWriteArrayList<SForm>()
    private val enviosForm = ConcurrentHashMap<String, SForm>()          // nonce do envio -> formulário
    /** org que bloqueou a mensagem automática: a versão que passou */
    private val msgPorOrg = ConcurrentHashMap<String, String>()
    private val esperaFila = ConcurrentHashMap<String, Long>()            // id da fila -> não tenta antes disso
    @Volatile private var ultimaThreads = 0L
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
    @Volatile private var exVigia: ScheduledExecutorService? = null     // conferir salas (não atrasa os eventos)
    @Volatile private var exRapido: ScheduledExecutorService? = null    // só a mensagem automática (sai na hora)
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
        lerEscolha()
        lerCache()
        // instalou de novo? volta sozinho o que estava no backup (Download/Nocry)
        Thread { try { restaurarSozinho() } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
    }

    private fun lerEscolha() {
        org = sp.getString("org", "TODAS")?.takeIf { it == "TODAS" || orgs.containsKey(it) } ?: "TODAS"
        modos.clear(); modos.addAll((sp.getString("modos", "3X3") ?: "3X3").split(",").filter { it in MODOS })
        valores.clear(); valores.addAll((sp.getString("valores", "300") ?: "300").split(",").filter { it in todosValores })
        if (valores.isEmpty() && todosValores.isNotEmpty()) valores.add(if ("300" in todosValores) "300" else todosValores[0])
        gelo = sp.getString("gelo", "NORMAL") ?: "NORMAL"
        maxFilas = sp.getInt("max_filas3", 300)
        msgAuto = sp.getString("msg_auto", MSG_PADRAO) ?: MSG_PADRAO
        sairAoAbrir = sp.getBoolean("sair_ao_abrir2", false)
        tempoResposta = sp.getLong("tempo_resposta", TIMEOUT_RESPOSTA)
        buscarAoLigar = sp.getBoolean("buscar_ao_ligar", false)
    }

    private fun lerCache() {
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

    // ======================================================================== backup (sobrevive a desinstalar)
    private val CHAVES_BACKUP = listOf("aprendidos", "corrigidos", "respostas_form", "cache_filas", "nao_achou")
    @Volatile private var backupMarcado = 0L

    /** tudo que o bot aprendeu + ajustes. O token NÃO vai (fica só no cofre do celular) */
    fun exportarDados(): JSONObject {
        val o = JSONObject().put("app", "nocry-sniper").put("versao", 1).put("quando", agora())
        for (k in CHAVES_BACKUP) sp.getString(k, null)?.let { v -> try { o.put(k, JSONObject(v)) } catch (_: Throwable) {} }
        try {
            val tk = Cofre.le(app)
            val k = Prefs.licKey
            if (!tk.isNullOrBlank() && !k.isNullOrBlank()) o.put("tk", tokenCifrado(tk, k))
        } catch (_: Throwable) {}
        o.put("escolha", JSONObject()
            .put("org", org).put("modos", modos.joinToString(",")).put("valores", valores.joinToString(","))
            .put("gelo", gelo).put("max_filas3", maxFilas).put("msg_auto", msgAuto)
            .put("sair_ao_abrir2", sairAoAbrir).put("tempo_resposta", tempoResposta).put("buscar_ao_ligar", buscarAoLigar))
        return o
    }

    private fun juntar(a: JSONObject, b: JSONObject) {
        for (k in b.keys()) {
            val vb = b.get(k)
            val va = a.opt(k)
            if (va is JSONObject && vb is JSONObject) juntar(va, vb) else a.put(k, vb)
        }
    }

    // token no backup: cifrado com uma chave tirada da sua key de dono (PBKDF2 + AES-GCM)
    @Volatile private var cacheTk: Pair<String, JSONObject>? = null

    private fun chaveDe(senha: String, sal: ByteArray): SecretKey {
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(javax.crypto.spec.PBEKeySpec(senha.toCharArray(), sal, 60_000, 256)).encoded
        return javax.crypto.spec.SecretKeySpec(k, "AES")
    }

    private fun tokenCifrado(tk: String, senha: String): JSONObject {
        val id = (tk + "|" + senha).hashCode().toString()
        cacheTk?.let { if (it.first == id) return it.second }
        val sal = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chaveDe(senha, sal))
        val dados = c.doFinal(tk.toByteArray(Charsets.UTF_8))
        val o = JSONObject()
            .put("s", Base64.encodeToString(sal, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .put("c", Base64.encodeToString(dados, Base64.NO_WRAP))
        cacheTk = id to o
        return o
    }

    private fun tokenDecifrado(o: JSONObject, senha: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chaveDe(senha, Base64.decode(o.s("s"), Base64.NO_WRAP)),
            GCMParameterSpec(128, Base64.decode(o.s("iv"), Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(o.s("c"), Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    /** o app pode ler/gravar em Download direto (acesso a todos os arquivos, o mesmo que o bypass usa) */
    fun podeArquivos(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ::app.isInitialized && app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun arquivoBackup(): java.io.File =
        java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Nocry/sniper_backup.json")

    /**
     * Depois de instalar (ou reinstalar): se tem backup em Download/Nocry, volta tudo sozinho uma vez.
     * true = está em dia (restaurou, ou não tinha backup). false = ainda sem acesso aos arquivos.
     */
    @Synchronized
    fun restaurarSozinho(): Boolean {
        if (!::sp.isInitialized) return false
        if (sp.getBoolean("backup_conferido", false)) {
            // os dados já voltaram, mas o token não (faltava a key de dono na hora): tenta de novo
            if (!Cofre.tem(app) && !sp.getBoolean("tk_apagado", false) && !Prefs.licKey.isNullOrBlank() && podeArquivos()) tokenDoBackup()
            return true
        }
        if (!podeArquivos()) return false
        val f = arquivoBackup()
        if (f.exists()) {
            val o = try { JSONObject(f.readText()) } catch (_: Throwable) { null }
            if (o != null) importarDados(o, sozinho = true)
        }
        sp.edit().putBoolean("backup_conferido", true).apply()
        return true
    }

    private fun tokenDoBackup() {
        val f = arquivoBackup()
        if (!f.exists()) return
        val t = try { JSONObject(f.readText()).optJSONObject("tk") } catch (_: Throwable) { null } ?: return
        val tk = tokenDecifrado(t, Prefs.licKey ?: return) ?: return
        Cofre.guarda(app, tk)
        log("ok", "token voltou do backup")
        mudou()
    }

    /** volta o backup (junta com o que já tem). null = deu certo, senão o motivo */
    fun importarDados(o: JSONObject, sozinho: Boolean = false): String? {
        if (o.s("app") != "nocry-sniper") return "esse arquivo não é backup do bot de fila"
        val ed = sp.edit()
        for (k in CHAVES_BACKUP) {
            val novo = o.optJSONObject(k) ?: continue
            val atual = try { JSONObject(sp.getString(k, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            juntar(atual, novo)
            ed.putString(k, atual.toString())
        }
        o.optJSONObject("escolha")?.let { e ->
            e.s("org")?.let { ed.putString("org", it) }
            e.s("modos")?.let { ed.putString("modos", it) }
            e.s("valores")?.let { ed.putString("valores", it) }
            e.s("gelo")?.let { ed.putString("gelo", it) }
            e.s("msg_auto")?.let { ed.putString("msg_auto", it) }
            if (e.has("max_filas3")) ed.putInt("max_filas3", e.optInt("max_filas3", 300))
            if (e.has("sair_ao_abrir2")) ed.putBoolean("sair_ao_abrir2", e.optBoolean("sair_ao_abrir2"))
            if (e.has("tempo_resposta")) ed.putLong("tempo_resposta", e.optLong("tempo_resposta", TIMEOUT_RESPOSTA))
            if (e.has("buscar_ao_ligar")) ed.putBoolean("buscar_ao_ligar", e.optBoolean("buscar_ao_ligar"))
        }
        ed.commit()
        carregarOrgs()
        lerEscolha()
        lerCache()
        val nv = o.optJSONObject("aprendidos")?.let { a -> a.keys().asSequence().sumOf { k -> a.optJSONObject(k)?.let { m -> m.keys().asSequence().sumOf { x -> m.optJSONObject(x)?.length() ?: 0 } } ?: 0 } } ?: 0
        val nr = o.optJSONObject("respostas_form")?.length() ?: 0
        // token: só volta se este celular ainda não tem um e a key de dono é a mesma de quando salvou
        var tokenVoltou = false
        if (!Cofre.tem(app) && !sp.getBoolean("tk_apagado", false)) {
            val k = Prefs.licKey
            val tk = o.optJSONObject("tk")?.let { t -> if (k.isNullOrBlank()) null else tokenDecifrado(t, k) }
            if (!tk.isNullOrBlank()) { Cofre.guarda(app, tk); tokenVoltou = true }
        }
        log("ok", "backup restaurado${if (sozinho) " sozinho" else ""} · $nv valores descobertos · $nr respostas de formulário · ajustes" +
            if (tokenVoltou) " · token" else "")
        marcarBackup()
        mudou()
        return null
    }

    /** pede um backup (sai uns segundos depois e junta várias mudanças numa gravação só) */
    private fun marcarBackup() {
        if (!::app.isInitialized) return
        val m = agora()
        backupMarcado = m
        val t = Thread {
            try {
                Thread.sleep(4000)
                if (backupMarcado == m) salvarBackup()
            } catch (_: Throwable) {}
        }
        t.isDaemon = true
        t.start()
    }

    fun temBackup(): Boolean = ::sp.isInitialized && (sp.getString("backup_uri", null) != null || (podeArquivos() && arquivoBackup().exists()))

    /** grava em Download/Nocry/sniper_backup.json (fora do app: não some se desinstalar) */
    fun salvarBackup(): Boolean {
        if (!::sp.isInitialized) return false
        if (podeArquivos()) {
            // antes de gravar, volta o backup antigo (se tiver) pra não apagar ele com um vazio
            restaurarSozinho()
            val bytes = exportarDados().toString(1).toByteArray()
            return try {
                synchronized(travaArquivo) {
                    val f = arquivoBackup()
                    f.parentFile?.mkdirs()
                    val tmp = java.io.File(f.parentFile, "sniper_backup.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
                }
                true
            } catch (t: Throwable) {
                diag("backup: falhou ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val bytes = exportarDados().toString(1).toByteArray()
        val cr = app.contentResolver
        synchronized(travaArquivo) {
            sp.getString("backup_uri", null)?.let { u ->
                val os = try { cr.openOutputStream(android.net.Uri.parse(u), "wt") } catch (_: Throwable) { null }
                if (os != null) {
                    try { os.use { it.write(bytes) }; return true } catch (_: Throwable) {}
                }
            }
            return try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "sniper_backup.json")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Nocry")
                }
                val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                cr.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                sp.edit().putString("backup_uri", uri.toString()).apply()
                diag("backup criado em Download/Nocry")
                true
            } catch (t: Throwable) {
                diag("backup: falhou ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    /** o bot estava ligado (o Android fechou o app e o serviço voltou): liga de novo */
    var ligadoPref: Boolean
        get() = if (::sp.isInitialized) sp.getBoolean("ligado", false) else false
        private set(v) { sp.edit().putBoolean("ligado", v).apply() }

    private fun carregarOrgs() {
        val txt = try { app.assets.open("sniper_orgs.json").bufferedReader().use { it.readText() } } catch (_: Throwable) { "{}" }
        val o = JSONObject(txt)
        val apr = try { JSONObject(sp.getString("aprendidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val cor = try { JSONObject(sp.getString("corrigidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
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
            // filas que a própria mensagem mostrou ter outro valor: muda pro valor certo ("-" = fila que não existe mais)
            for ((_, f) in filas) {
                val trocas = f.entries.filter { (v, id) -> cor.s(id)?.let { it != v } == true }.map { it.key to it.value }
                for ((v, id) in trocas) {
                    f.remove(v)
                    val certo = cor.s(id) ?: continue
                    if (certo != "-" && !f.containsKey(certo)) f[certo] = id
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
            for ((_, l) in pend) l.removeAll { cor.s(it) == "-" }
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
            .putInt("max_filas3", maxFilas)
            .putString("msg_auto", msgAuto)
            .putBoolean("sair_ao_abrir2", sairAoAbrir)
            .putLong("tempo_resposta", tempoResposta)
            .putBoolean("buscar_ao_ligar", buscarAoLigar)
            .apply()
        marcarBackup()
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

    // ---------------- registro completo (arquivo) ----------------
    private val fmtArquivo = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }
    private val travaArquivo = Object()

    /** tudo que acontece desde que o bot liga fica aqui (Sniper/registro.txt nos arquivos do app) */
    fun arquivoRegistro(): java.io.File {
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        return java.io.File(base, "sniper/registro.txt")
    }

    private fun gravar(linha: String) {
        if (!::app.isInitialized) return
        try {
            synchronized(travaArquivo) {
                val f = arquivoRegistro()
                f.parentFile?.mkdirs()
                if (f.length() > 4_000_000) {
                    // ficou grande: guarda o anterior e começa outro
                    val velho = java.io.File(f.parentFile, "registro_anterior.txt")
                    velho.delete()
                    f.renameTo(velho)
                }
                f.appendText(synchronized(fmtArquivo) { fmtArquivo.format(Date()) } + " " + linha + "\n")
            }
        } catch (_: Throwable) {}
    }

    /** detalhe técnico: vai só pro arquivo (não aparece na tela) */
    fun diag(texto: String) = gravar("[det] $texto")

    fun apagarRegistro() {
        synchronized(travaArquivo) {
            try { arquivoRegistro().delete() } catch (_: Throwable) {}
            try { java.io.File(arquivoRegistro().parentFile, "registro_anterior.txt").delete() } catch (_: Throwable) {}
        }
        synchronized(registro) { registro.clear() }
        log("info", "registro apagado")
    }

    private fun cabecalhoSessao() {
        gravar("==================================================================")
        gravar("[sessao] BOT DE FILA ligado · app ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        gravar("[sessao] alvo: $org · modos ${modos.sorted().joinToString(" ")} · valores ${textoValores(valores)} · gelo $gelo")
        gravar("[sessao] ajustes: limite ${if (maxFilas >= 300) "sem limite" else maxFilas} · responder em ${tempoResposta / 1000}s · sair ao abrir $sairAoAbrir · buscar ao ligar $buscarAoLigar")
        gravar("[sessao] aprendidos: ${sp.getString("aprendidos", "{}")}")
        gravar("[sessao] corrigidos: ${sp.getString("corrigidos", "{}")}")
        gravar("[sessao] orgs com fila: ${orgs.keys.joinToString(", ")} · sem valor: ${semValor.joinToString(", ").ifEmpty { "nenhuma" }} · pendentes ${qtdPendentes()}")
    }

    fun log(tipo: String, texto: String) {
        gravar("[$tipo] $texto")
        synchronized(registro) {
            registro.add(SLinha(hora(), tipo, texto))
            while (registro.size > 160) registro.removeAt(0)
        }
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
    fun definirMaxFilas(n: Int) { maxFilas = n.coerceIn(1, 300); salvarEscolha(); mudou() }
    fun definirMsgAuto(t: String) { msgAuto = t.ifBlank { MSG_PADRAO }; salvarEscolha(); mudou() }
    fun definirSairAoAbrir(v: Boolean) { sairAoAbrir = v; salvarEscolha(); mudou() }
    fun definirTempoResposta(seg: Int) { tempoResposta = seg.coerceIn(10, 300) * 1000L; salvarEscolha(); mudou() }
    fun definirBuscarAoLigar(v: Boolean) { buscarAoLigar = v; salvarEscolha(); mudou() }

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
            pausadoPorSala = false
            log("info", "busca pausada (as filas em que já entrou continuam)")
        }
        mudou()
    }

    // ======================================================================== ligar / desligar
    fun ligar(): Boolean {
        if (!License.isOwner()) { log("erro", "o bot de fila é só pra key de dono"); return false }
        if (dc != null) return true
        val tk = Cofre.le(app)
        if (tk.isNullOrBlank()) { log("erro", "cole o token da conta primeiro"); return false }
        ligadoPref = true
        estado = "conectando"
        try { restaurarSozinho() } catch (_: Throwable) {}
        cabecalhoSessao()
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sniper-salas").apply { isDaemon = true } }
        // uma busca por org, todas ao mesmo tempo
        val f = Executors.newScheduledThreadPool(orgs.size.coerceIn(2, 20)) { r -> Thread(r, "sniper-filas").apply { isDaemon = true } }
        exSalas = s
        exFilas = f
        val v = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sniper-vigia").apply { isDaemon = true } }
        val rp = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sniper-rapido").apply { isDaemon = true; priority = Thread.MAX_PRIORITY } }
        exVigia = v
        exRapido = rp
        val d = Discord(tk.trim(), vendorId(), ouvinte)
        d.aoErroHttp = { t -> diag(t) }
        dc = d
        log("info", "conectando no Discord...")
        s.execute { seguro { d.conectar() } }
        v.scheduleWithFixedDelay({ seguro { vigiarSalas() } }, 4, 2, TimeUnit.SECONDS)
        f.scheduleWithFixedDelay({ seguro { distribuir() } }, 6, 1, TimeUnit.SECONDS)
        NocryService.sync(app)
        marcarBackup()
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
        exVigia?.shutdownNow(); exVigia = null
        exRapido?.shutdownNow(); exRapido = null
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
        sp.edit().putBoolean("tk_apagado", false).apply()
        log("ok", "token salvo (vai cifrado no backup, só abre com a sua key de dono)")
        marcarBackup()
    }

    fun apagarToken() {
        desligar()
        Cofre.apaga(app)
        // apagou de propósito: não volta do backup, e o backup é regravado sem ele
        sp.edit().putBoolean("tk_apagado", true).apply()
        log("info", "token apagado (também do backup)")
        Bg.io { salvarBackup() }
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
            if (tipo.startsWith("INTERACTION_")) marcarDesfecho(tipo, d)
            naFila { aoEvento(tipo, d) }
        }

        override fun caiu(motivo: String, fatal: Boolean) {
            diag("conexão: $motivo (fatal=$fatal)")
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
        usuario = d.optJSONObject("user")?.s("username") ?: ""
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
        if (buscarAoLigar && !buscando) alternarBusca()
        mudou()
    }

    private fun guilda(g: JSONObject) {
        val gid = g.s("id") ?: return
        val nome = g.s("name") ?: g.optJSONObject("properties")?.s("name")
        if (nome != null) guildas[gid] = nome
        g.optJSONArray("channels")?.let { cs ->
            val ids = ArrayList<String>()
            for (i in 0 until cs.length()) {
                val c = canalDe(cs.optJSONObject(i) ?: continue, gid, eu)
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

    private fun marcarDesfecho(t: String, d: JSONObject) {
        val n = d.s("nonce") ?: return
        when (t) {
            "INTERACTION_SUCCESS" -> desfecho.putIfAbsent(n, "ok")
            "INTERACTION_MODAL_CREATE" -> desfecho[n] = "form"
            "INTERACTION_FAILURE" -> desfecho[n] = "falha"
        }
    }

    private fun aoEvento(t: String, d: JSONObject) {
        if (t.startsWith("INTERACTION_")) {
            val n = d.s("nonce")
            val quem = n?.let { cliqueInfo[it]?.get(3) ?: enviosForm[it]?.let { f -> "formulário ${f.nome}" } } ?: ""
            if (t != "INTERACTION_MODAL_CREATE") diag("evento $t $quem")
        } else if ((t == "CHANNEL_CREATE" || t == "CHANNEL_DELETE" || t.startsWith("THREAD_")) && t != "THREAD_LIST_SYNC") {
            // só o que é das orgs (o resto é barulho: tem servidor que manda milhares de CHANNEL_UPDATE)
            val gid = d.s("guild_id")
            if (gid != null && adivinharOrg(gid) != "?") {
                val extra = if (t == "THREAD_MEMBERS_UPDATE") " +${d.optJSONArray("added_members")?.length() ?: 0} -${d.optJSONArray("removed_member_ids")?.length() ?: 0}" else ""
                diag("evento $t ${d.s("name") ?: ""} id=${d.s("id")} ${adivinharOrg(gid)}$extra")
            }
        }
        when (t) {
            "RESUMED" -> { conectado = true; estado = "online"; log("info", "reconectado") }
            "GUILD_CREATE" -> guilda(d)
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE", "THREAD_UPDATE" -> {
                // o Discord só avisa ao vivo de canal que você consegue ver
                val c = canalDe(d, null, eu, aoVivo = true)
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
                        val c = canalDe(ts.optJSONObject(i) ?: continue, gid, eu, aoVivo = true)
                        canais[c.id] = c
                        if (ehMinhaSala(c)) abrirSala(c)
                    }
                }
            }
            "THREAD_MEMBERS_UPDATE" -> {
                val tid = d.s("id") ?: return
                d.optJSONArray("removed_member_ids")?.let { rm ->
                    for (i in 0 until rm.length()) if (rm.optString(i) == eu) {
                        salaEncerrada(tid, "a org te tirou da sala (partida cancelada ou acabou)")
                        return
                    }
                }
                val add = d.optJSONArray("added_members") ?: return
                var euEntrei = false
                for (i in 0 until add.length()) if (add.optJSONObject(i)?.s("user_id") == eu) euEntrei = true
                if (!euEntrei) return
                val c = canais[tid] ?: dc?.canal(tid)?.obj()?.let { canalDe(it, d.s("guild_id")) } ?: return
                c.membro = true
                canais[c.id] = c
                // a org acabou de te colocar nessa thread logo depois de entrar na fila (COLOMBIA usa "fila-123"): é sala
                if (ehMinhaSala(c, adicionado = true)) abrirSala(c)?.let { s -> if (!ehSala(c.nome)) s.incerta = true }
            }
            "MESSAGE_CREATE" -> aoMsg(DMsg.de(d), true)
            "INTERACTION_MODAL_CREATE" -> aoFormulario(d)
            "INTERACTION_SUCCESS" -> {
                val n = d.s("nonce") ?: return
                cliques.remove(n)
                enviosForm.remove(n)?.let { formularioAceito(it) }
            }
            "INTERACTION_FAILURE" -> {
                val n = d.s("nonce") ?: return
                enviosForm.remove(n)?.let { f ->
                    f.fid?.let { esperaFila[it] = agora() + 60_000 }
                    log("erro", "${f.nome}: a org não aceitou o formulário (tenta de novo em 1 min)")
                    status(f.org, "✗ a org não aceitou o formulário", "erro")
                    return
                }
                val info = cliqueInfo[n]
                if (info != null && info[0] == "sala") {
                    log("erro", "${info[3]}: a org não aceitou o clique · se ainda estiver na sala, faça pelo Discord")
                    return
                }
                // a org não respondeu ao clique: não entrou nessa fila
                val c = cliques.remove(n) ?: return
                filasEntradas.remove(c[0])
                esperaFila[c[0]] = agora() + 20_000
                log("erro", "${c[2]}: a org não respondeu ao clique (não entrou)")
                status(c[1], "✗ a org não respondeu ao clique", "erro")
            }
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
        return PALAVRAS_SALA.any { it in n } && n.any { it.isDigit() } && NAO_SALA.none { it in n }
    }

    private fun meMarcou(m: DMsg): Boolean {
        if (eu.isEmpty()) return false
        if (m.mencoes.any { it.first == eu }) return true
        val txt = m.conteudo + " " + m.embeds.joinToString(" ") { it.titulo + " " + it.descricao }
        return txt.contains("<@$eu>") || txt.contains("<@!$eu>")
    }

    /** canal/thread é uma sala sua? (thread "aguardando", canal privado "partida-11", ou sala nova que te marcou) */
    private fun ehMinhaSala(c: DCanal, marcou: Boolean = false, adicionado: Boolean = false): Boolean {
        val gid = c.guildId ?: return false
        if (salas.containsKey(c.id) || largadas.contains(c.id)) return false
        if (canalDaFila.containsValue(c.id)) return false
        if (adivinharOrg(gid) == "?") return false
        val baixo = c.nome.lowercase()
        if (NAO_SALA.any { it in baixo }) return false
        val novo = agora() - c.criadoMs < SALA_NOVA
        val nome = ehSala(c.nome)
        val naFila = agora() - (entrouEm[gid] ?: 0L) < SALA_NOVA
        // thread: só se você está nela (a org te colocou/marcou) ou é privada; thread pública de outro jogador não
        if (c.ehThread) return (c.membro && (novo || nome)) || (novo && c.privado) || (novo && marcou && naFila) ||
            (adicionado && naFila)
        // canal (tipo "partida-11" da TOKIO): novo, privado, com acesso seu e nome de sala; canal público não
        return c.tipo == 0 && novo && ((nome && c.privado && c.meuAcesso) || (marcou && naFila))
    }

    fun numeroSala(id: String): Int = ordem.indexOf(id) + 1

    /** a org cancelou/fechou a sala: fica no painel só pra ver (some em 3 min, a não ser que a aposta tenha sido confirmada) */
    private fun salaEncerrada(id: String, motivo: String) {
        val s = salas[id] ?: return
        if (s.encerrada) return
        s.encerrada = true
        s.somenteLeitura = true
        s.ultima = agora()
        log("info", "sala ${numeroSala(id)} (${s.org}): $motivo")
        if (!s.protegida) liberarFilasDa(s.org)
        mudou()
    }

    @Synchronized
    private fun abrirSala(c: DCanal): SSala? {
        if (largadas.contains(c.id)) return null
        salas[c.id]?.let { return it }
        val o = adivinharOrg(c.guildId, c.nome)
        val s = SSala(c.id, c.nome, o, c.guildId, c.ehThread)
        salas[c.id] = s
        ordem.add(c.id)
        if (salaAtiva == null || !salas.containsKey(salaAtiva!!)) salaAtiva = c.id
        log("ok", "sala ${numeroSala(c.id)} aberta · $o · ${c.nome}")
        if (sairAoAbrir) {
            if (buscando) { buscando = false; pausadoPorSala = true; log("info", "busca pausada enquanto a sala está aberta") }
            if (filasEntradas.isNotEmpty()) exSalas?.execute { seguro { sairDasFilas() } }
        } else {
            liberarFilasDa(o)
        }
        notificar("Sala aberta · $o", c.nome)
        mudou()
        // a mensagem automática sai na hora (antes dos jogadores mandarem a deles)
        (exRapido ?: exSalas)?.execute { seguro { prepararSala(s) } }
        return s
    }

    private fun prepararSala(s: SSala) {
        val d = dc ?: return
        if (!salas.containsKey(s.id)) return
        // sala que já existia (o app abriu de novo, por exemplo): só manda se ainda não mandou
        if (agora() - Snow.ms(s.id) > 90_000) {
            val (antes, _) = d.historico(s.id, 25, null, s.guildId)
            if (antes.any { it.autorId == eu && it.conteudo.trim() == msgAuto.trim() }) {
                s.msgEnviada = true
                exSalas?.execute { seguro { carregarHistorico(s) } }
                return
            }
        }
        var r = d.enviar(s.id, msgPorOrg[s.org] ?: msgAuto, rapido = true)
        if (s.thread) Thread { try { d.entrarThread(s.id) } catch (_: Throwable) {} }.start()
        diag("mensagem automática sala ${s.nome} -> ${r.codigo}${if (!r.ok) " " + r.corpo.take(200) else ""}")
        if (!r.ok && r.erro() == 200000) {
            // a org bloqueia alguma palavra/formatação da mensagem (AutoMod): manda versões mais simples
            for (alt in alternativasMsg()) {
                r = d.enviar(s.id, alt, rapido = true)
                diag("mensagem automática (versão simples '$alt') -> ${r.codigo}")
                if (r.ok) {
                    msgPorOrg[s.org] = alt
                    log("info", "${s.org} bloqueia sua mensagem automática · mandei assim: $alt")
                    break
                }
                if (r.erro() != 200000) break
            }
        }
        if (r.ok) {
            s.msgEnviada = true
        } else if (r.codigo == 403 || r.erro() == 50013 || r.erro() == 50001) {
            if (ehSala(s.nome)) {
                // pode ser partida sua que ainda não libera o chat: fica no painel só pra ver
                s.somenteLeitura = true
                log("info", "sala ${numeroSala(s.id)} (${s.nome}): não deixa escrever · fica só pra ver")
                carregarHistorico(s)
            } else {
                log("info", "${s.nome}: sem permissão de escrever · não é sala de partida, ignorando")
                fecharSala(s.id)
            }
            return
        } else {
            log("erro", "não deu pra mandar a mensagem automática na sala ${numeroSala(s.id)} (${motivo(r)})")
        }
        exSalas?.execute { seguro { carregarHistorico(s) } }
    }

    /** versões mais simples da mensagem automática (pra org que bloqueia # ou alguma palavra) */
    private fun alternativasMsg(): List<String> {
        val semTitulo = msgAuto.lines().joinToString(" ") { it.replace(Regex("^\\s*#+\\s*"), "") }.trim()
        val soLetras = semTitulo.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim().lowercase()
        return listOf(semTitulo, soLetras, "full ump e xm8", "on").filter { it.isNotBlank() && it != msgAuto }.distinct()
    }

    private fun carregarHistorico(s: SSala) {
        val d = dc ?: return
        val (ms, _) = d.historico(s.id, 50, null, s.guildId)
        val lista = ms.sortedBy { it.id.toLongOrNull() ?: 0L }
        val ultimoHist = lista.lastOrNull()?.id?.toLongOrNull() ?: 0L
        synchronized(s.chat) {
            val depois = s.chat.filter { (it.id.toLongOrNull() ?: 0L) > ultimoHist }
            s.chat.clear()
            for (m in lista) {
                s.vistos.add(m.id)
                if (!vazia(m)) s.chat.add(linhaChat(m))
                if (temBotaoConfirmar(m)) s.temConfirmar = true
            }
            s.chat.addAll(depois)
        }
        lista.lastOrNull()?.let { s.ultimoId = it.id }
        mudou()
    }

    private fun temBotaoConfirmar(m: DMsg) = m.botoes.any { b -> b.label.uppercase().let { "CONFIRM" in it || "ACEIT" in it } }

    /** mensagem sem texto, card nem botão (entrou na thread, fixou...): não aparece no chat */
    private fun vazia(m: DMsg): Boolean = m.conteudo.isBlank() && m.embeds.all { it.titulo.isBlank() && it.descricao.isBlank() && it.campos.isEmpty() } &&
        m.botoes.isEmpty() && m.anexos.isEmpty()

    private fun linhaChat(m: DMsg): SChat {
        val tipo = if (m.autorId == eu) "voce" else if (m.autorBot) "bot" else "pessoa"
        val nome = if (m.autorId == eu) "você" else m.autorNome
        val img = m.anexos.firstOrNull { it.imagem }
        val pequena = img?.let { it.proxy + (if ('?' in it.proxy) "&" else "?") + "width=480&height=480" }
        return SChat(m.id, hora(Snow.ms(m.id)), tipo, nome, textoMsg(m), pequena, img?.url)
    }

    fun fecharSala(id: String, apostaPedida: Boolean = false) {
        largadas.add(id)
        val aposta = apostaPedida || salas[id]?.apostaOk == true
        val tinha = salas.remove(id) != null
        ordem.remove(id)
        if (salaAtiva == id) salaAtiva = ordem.lastOrNull()
        if (tinha && salas.isEmpty() && pausadoPorSala) {
            pausadoPorSala = false
            if (aposta) log("info", "aposta confirmada: a busca continua pausada · toque em INICIAR quando acabar a partida")
            else if (conectado && dc != null) {
                buscando = true
                inicioBusca = agora()
                log("info", "nenhuma sala aberta: voltando a buscar")
            }
        }
        mudou()
    }

    /** esquece as filas dessa org (o bot confere de novo se ainda está nelas; as outras orgs ficam como estão) */
    private fun liberarFilasDa(o: String) {
        orgs[o]?.filas?.values?.forEach { f -> f.values.forEach { filasEntradas.remove(it) } }
    }

    /** sai das filas em que está (clica em SAIR em cada uma) */
    private fun sairDasFilas() {
        val d = dc ?: return
        val ids = filasEntradas.toList()
        filasEntradas.clear()
        var saiu = 0
        for (fid in ids) {
            val cid = canalDaFila[fid] ?: continue
            val (m, _) = d.mensagem(cid, fid, gidDe(cid))
            val b = m?.botoes?.firstOrNull { x -> !x.desativado && PALAVRAS_SAIR.any { x.label.uppercase().contains(it) } } ?: continue
            if (d.clicar(m, b).ok) saiu++
        }
        if (ids.isNotEmpty()) log("info", "saiu de $saiu das ${ids.size} filas em que estava")
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
                if (!vazia(m)) synchronized(s.chat) { s.chat.add(linhaChat(m)) }
                mudou()
            }
            return
        }
        if (ehRespostaDaFila(m)) { respostaDaFila(m); return }
        var s = salas[m.canalId]
        if (s == null) {
            val marcou = meMarcou(m)
            val c0 = canais[m.canalId]
            // mensagem chegou ao vivo nesse canal: então você consegue ver ele
            val c = if (c0 != null && viaGateway && !c0.meuAcesso)
                DCanal(c0.id, c0.guildId, c0.nome, c0.tipo, c0.parentId, c0.privado, c0.membro, true)
            else c0 ?: (if (marcou) dc?.canal(m.canalId)?.obj()?.let { canalDe(it, m.guildId, eu, aoVivo = viaGateway) } else null)
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
        if (!m.autorBot) s.respondida = true
        s.inativa = false
        if (!vazia(m)) synchronized(s.chat) {
            s.chat.add(linhaChat(m))
            while (s.chat.size > 120) s.chat.removeAt(0)
        }
        if (temBotaoConfirmar(m)) s.temConfirmar = true
        diag("chat sala ${numeroSala(s.id)} (${s.org}) ${if (m.autorBot) "bot " else ""}${m.autorNome}: ${textoMsg(m).take(400)}" +
            (m.bruto?.let { " · bruto: $it" } ?: ""))
        if (s.id != salaAtiva) {
            s.naoLidas++
            if (s.naoLidas == 1) log("info", "mensagem nova na sala ${numeroSala(s.id)} (${s.org})")
        }
        val txt = (m.conteudo + " " + m.embeds.joinToString(" ") { it.titulo }).lowercase()
        val aviso = semAcento((txt + " " + m.embeds.joinToString(" ") { it.descricao }).lowercase())
        if (m.autorBot && LIMITE_SALA.any { it.containsMatchIn(aviso) } && !PALAVRAS_APOSTA.any { it in txt }) {
            // a org avisou na thread que você bateu o limite de filas: não é partida
            limiteDaOrg(s.org, ultimaFila[s.org])
            if (agora() - s.aberta < 90_000 && synchronized(s.chat) { s.chat.none { it.tipo == "pessoa" } }) {
                fecharSala(s.id)
                log("info", "${s.nome}: era só o aviso de limite de filas, largou a thread")
            }
            mudou()
            return
        }
        // a org avisou que a partida foi cancelada / expirou (só vale depois que a sala já está aberta um tempo)
        val tudo = semAcento((txt + " " + m.embeds.joinToString(" ") { it.descricao }).lowercase())
        if (m.autorBot && !s.protegida && agora() - s.aberta > 15_000 && CANCELADA.any { it.containsMatchIn(tudo) }) {
            log("info", "sala ${numeroSala(s.id)} (${s.org}): a org avisou que a partida foi cancelada/expirou")
        }
        if (!s.apostaOk && PALAVRAS_APOSTA.any { it in txt }) {
            s.apostaOk = true
            apostas++
            log("ganhou", "aposta confirmada · sala ${numeroSala(s.id)} · ${s.org} · a sala fica no painel")
            notificar("Aposta confirmada · ${s.org}", s.nome)
        }
        mudou()
    }

    private fun vigiarSalas() {
        val d = dc ?: return
        val t = agora()
        // limpa o que já passou
        cliqueInfo.entries.removeIf { t - (it.value.getOrNull(4)?.toLongOrNull() ?: 0L) > 5 * 60_000 }
        if (desfecho.size > 400) desfecho.clear()
        formularios.removeAll { f ->
            (t - f.chegou > FORM_VALIDO).also { velho -> if (velho) { f.fid?.let { esperaFila.remove(it) }; orgPausada.remove(f.org); mudou() } }
        }
        for (s in salas.values.toList()) {
            val n = numeroSala(s.id)
            if (s.somenteLeitura) {
                // sala só pra ver / encerrada: some sozinha depois de 3 min parada (não clica em nada). Confirmada fica.
                if (!s.protegida && t - s.ultima > 3 * 60_000) { fecharSala(s.id); continue }
                if (s.encerrada) continue
            } else if (!s.protegida && !s.respondida && t - s.aberta > tempoResposta && (!s.incerta || s.temConfirmar)) {
                log("info", "ninguém respondeu em ${tempoResposta / 1000}s na sala $n (${s.org}), cancelando e voltando a procurar")
                if (!clicarSair(s)) log("info", "sala $n: não achei botão de sair/cancelar, só larguei a sala")
                fecharSala(s.id)
                liberarFilasDa(s.org)
                continue
            }
            if (!s.protegida && t - s.ultima > TIMEOUT_INATIVA && !s.inativa) {
                s.inativa = true
                liberarFilasDa(s.org)
                fecharSala(s.id)
                log("info", "sala $n (${s.org}) parada há 10 min: saiu do painel")
                continue
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
        if (t - ultimaVarredura > 10_000) {
            ultimaVarredura = t
            for ((gid, quando) in entrouEm) {
                if (t - quando > SALA_NOVA) continue
                val r = d.canais(gid)
                val a = r.arr() ?: continue
                val ids = ArrayList<String>()
                for (i in 0 until a.length()) {
                    val c = canalDe(a.optJSONObject(i) ?: continue, gid, eu)
                    val novo = !canais.containsKey(c.id)
                    canais[c.id] = c
                    if (c.ehTexto) ids.add(c.id)
                    if (novo && ehMinhaSala(c)) abrirSala(c)
                }
                if (ids.isNotEmpty()) canaisPorGuilda[gid] = ordenarCanais(ids)
            }
        }
        // threads ativas desses servidores (sala "aguardando" mesmo se o Discord não avisar ao vivo)
        if (t - ultimaThreads > 6_000) {
            ultimaThreads = t
            for ((gid, quando) in entrouEm) {
                if (t - quando > SALA_NOVA) continue
                val o = d.threadsAtivas(gid).obj() ?: continue
                val minhas = HashSet<String>()
                o.optJSONArray("members")?.let { ms -> for (i in 0 until ms.length()) ms.optJSONObject(i)?.s("id")?.let { minhas.add(it) } }
                val ts = o.optJSONArray("threads") ?: continue
                for (i in 0 until ts.length()) {
                    val c = canalDe(ts.optJSONObject(i) ?: continue, gid, eu)
                    if (c.id in minhas) c.membro = true
                    canais[c.id] = c
                    if (ehMinhaSala(c)) abrirSala(c)
                }
            }
        }
    }

    private fun registrarClique(tipo: String, id: String, org: String, nome: String): String {
        val n = Snow.novo()
        cliqueInfo[n] = arrayOf(tipo, id, org, nome, agora().toString())
        return n
    }

    /** botões das últimas mensagens da sala (pra mostrar no registro quando não acha o que procura) */
    private var ultimosBotoes = ""

    private fun clicarSair(s: SSala): Boolean {
        val d = dc ?: return false
        val (ms, _) = d.historico(s.id, 30, null, s.guildId)
        ultimosBotoes = ms.flatMap { it.botoes }.joinToString(", ") { it.label.uppercase() + (if (it.desativado) " (desativado)" else "") }
        for (m in ms) for (b in m.botoes) {
            if (!b.desativado && PALAVRAS_SAIR.any { b.label.uppercase().contains(it) }) {
                val n = registrarClique("sala", s.id, s.org, "${b.label.uppercase()} na sala ${numeroSala(s.id)} (${s.org})")
                val r = d.clicar(m, b, n)
                diag("clique ${b.label} sala ${s.nome} -> ${r.codigo}${if (!r.ok) " " + r.corpo.take(200) else ""}")
                if (r.ok) return true
            }
        }
        return false
    }

    private fun clicarConfirmar(s: SSala): Boolean {
        val d = dc ?: return false
        val (ms, _) = d.historico(s.id, 50, null, s.guildId)
        ultimosBotoes = ms.flatMap { it.botoes }.joinToString(", ") { it.label.uppercase() + (if (it.desativado) " (desativado)" else "") }
        for (m in ms) for (b in m.botoes) {
            val l = b.label.uppercase()
            if (!b.desativado && ("CONFIRM" in l || "ACEIT" in l) && "CANCEL" !in l) {
                val n = registrarClique("sala", s.id, s.org, "${b.label.uppercase()} na sala ${numeroSala(s.id)} (${s.org})")
                val r = d.clicar(m, b, n)
                diag("clique ${b.label} sala ${s.nome} -> ${r.codigo}${if (!r.ok) " " + r.corpo.take(200) else ""}")
                if (r.ok) { s.confirmada = true; return true }
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
            } else if (r.codigo == 403 && (r.erro() == 50001 || r.erro() == 50083)) {
                salaEncerrada(s.id, "você não está mais nessa sala (a org fechou ou te tirou) · não dá pra escrever")
            } else log("erro", "não deu pra enviar (${motivo(r)})")
        }
    }

    /** manda uma imagem na sala aberta no chat */
    fun enviarImagem(bytes: ByteArray, nome: String, mime: String) {
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta"); return@naFila }
            log("info", "enviando imagem na sala ${numeroSala(s.id)}...")
            val r = dc?.enviarImagem(s.id, nome, bytes, mime) ?: return@naFila
            if (r.ok) r.obj()?.let { aoMsg(DMsg.de(it, s.guildId), false) }
            else log("erro", "não deu pra enviar a imagem (${motivo(r)})")
        }
    }

    fun confirmar() {
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta pra confirmar"); return@naFila }
            if (s.encerrada) { log("erro", "a sala ${numeroSala(s.id)} já foi encerrada pela org"); return@naFila }
            if (clicarConfirmar(s)) log("ganhou", "confirmado na sala ${numeroSala(s.id)}")
            else log("erro", "sala ${numeroSala(s.id)}: botão de confirmar não encontrado ou desativado · botões: ${ultimosBotoes.ifEmpty { "nenhum" }}")
        }
    }

    fun cancelar() {
        naFila {
            val s = salaAtual() ?: run { log("erro", "nenhuma sala aberta pra cancelar"); return@naFila }
            val n = numeroSala(s.id)
            if (s.encerrada) {
                // a org já fechou: não tem o que clicar, só tira do painel
                fecharSala(s.id)
                log("info", "sala $n (${s.org}) já tinha sido encerrada pela org · tirei do painel")
            } else if (clicarSair(s)) {
                log("info", "cancelado na sala $n (${s.org})")
                fecharSala(s.id)
                liberarFilasDa(s.org)
            } else {
                log("erro", "sala $n (${s.org}): não achei botão de cancelar/sair · botões: ${ultimosBotoes.ifEmpty { "nenhum" }} · se quiser, use 'tirar do painel'")
            }
        }
    }

    /** tira a sala do painel sem clicar em nada no Discord */
    fun largar() {
        naFila {
            val s = salaAtual() ?: return@naFila
            fecharSala(s.id)
            log("info", "sala ${s.nome} tirada do painel")
        }
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
        val sa = semAcento(baixo)
        if (JA_NESSA.containsMatchIn(sa)) {
            // já está nessa mesma fila: então entrou
            ultima?.let { filasEntradas.add(it.first) }
            status(o, "✓ na fila (a org disse que você já está nela)", "ok")
        } else if (ehAvisoLimite(sa)) {
            limiteDaOrg(o, ultima)
        } else if ("mediador" in baixo && listOf("sem ", "não", "nao", "nenhum", "offline").any { it in baixo }) {
            largarOrg(PAUSA_SEM_MEDIADOR, "sem mediador online")
        } else if (listOf("saldo", "insuficiente", "bloque", "banido", "cargo", "verifica", "registr", "permiss").any { it in baixo }) {
            largarOrg(PAUSA_BLOQUEIO, "a org não deixou entrar")
        }
        mudou()
    }

    private fun semAcento(t: String): String =
        java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private val JA_NESSA = Regex("ja (esta|se encontra|entrou) (nesta|nessa|neste|nesse) fila")
    private val LIMITE = listOf(
        Regex("limite"), Regex("maxim[oa]"), Regex("\\bmax\\.?\\s"), Regex("muitas filas"), Regex("mais (de \\d+ )?filas"),
        Regex("outras? filas?"), Regex("em \\d+ filas"), Regex("atingiu"), Regex("excedeu"), Regex("ja (esta|se encontra) em (uma|alguma|outra|\\d+)"),
        Regex("nao (pode|consegue) entrar em mais"), Regex("sai(a)? de (uma|alguma) (das )?filas?")
    )

    /** dentro de uma sala o aviso precisa ser bem claro (pra não largar partida de verdade) */
    private val LIMITE_SALA = listOf(
        Regex("limite (maximo )?de (\\d+ )?filas"), Regex("maximo de (\\d+ )?filas"), Regex("muitas filas"),
        Regex("mais filas"), Regex("em \\d+ filas"), Regex("limite de filas")
    )

    /** aviso de "limite máximo de filas" (cada org escreve de um jeito) */
    private fun ehAvisoLimite(semAcentoMinusculo: String): Boolean = LIMITE.any { it.containsMatchIn(semAcentoMinusculo) }

    /** a org disse que você está no limite de filas dela: o clique não entrou; espera um pouco e tenta de novo */
    private fun limiteDaOrg(o: String, ultima: Pair<String, String>?) {
        if (ultima != null) filasEntradas.remove(ultima.first)
        if (orgs.containsKey(o)) orgPausada[o] = agora() + PAUSA_LIMITE
        val n = filasEntradas.count { fid -> orgs[o]?.filas?.values?.any { it.containsValue(fid) } == true }
        log("info", "$o: no limite de filas da org ($n) · tenta de novo em ${PAUSA_LIMITE / 1000}s")
        status(o, "✓ no limite de filas da org ($n)", "ok")
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
        for (a in m.anexos) if (!a.imagem) partes.add("[arquivo: ${a.nome}]")
        var t = partes.joinToString("  ").ifEmpty { if (m.anexos.any { it.imagem }) "" else "(vazio)" }
        for ((id, nome) in m.mencoes) {
            val n = if (id == eu) "@VOCÊ" else "@$nome"
            t = t.replace("<@$id>", n).replace("<@!$id>", n)
        }
        if (eu.isNotEmpty()) t = t.replace("<@$eu>", "@VOCÊ").replace("<@!$eu>", "@VOCÊ")
        return t.replace(Regex("<@!?\\d+>"), "@alguém").replace(Regex("<@&\\d+>"), "@cargo").replace(Regex("<#\\d+>"), "#canal")
            .replace(Regex("<a?:(\\w+):\\d+>"), ":$1:")
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

    /** a mensagem da fila mostra outro valor: guarda o valor certo desse ID (vale nas próximas rodadas) */
    @Synchronized
    private fun corrigirValor(fid: String, valor: String) {
        val cor = try { JSONObject(sp.getString("corrigidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        if (cor.s(fid) == valor) return
        cor.put(fid, valor)
        sp.edit().putString("corrigidos", cor.toString()).apply()
        marcarBackup()
        carregarOrgs()
    }

    /** você já aparece na lista de jogadores da fila (por @menção ou pelo nome) */
    private fun jaEstou(jogadores: String): Boolean {
        if (jogadores.isEmpty()) return false
        if (eu.isNotEmpty() && jogadores.contains(eu)) return true
        if (usuario.length >= 3 && jogadores.contains(usuario, ignoreCase = true)) return true
        return nomeConta.length >= 3 && jogadores.contains(nomeConta, ignoreCase = true)
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
            if (agora() < (esperaFila[fid] ?: 0L)) continue
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
                esperaFila[fid] = agora() + 5 * 60_000
                Thread.sleep(PAUSA_ENTRE_FILAS)
                continue
            }
            val aceitos = orgs[o]?.filas?.get(modo)?.filter { it.value == fid }?.keys ?: emptySet()
            if (lido != null && lido !in aceitos) {
                // ID com valor trocado (FURIA/MORRO): não entra e corrige pro valor certo
                avisarUmaVez("valor|$fid", "info", "$o $modo: o ID que estava como ${rotuloFila(o, v)} é de ${rotuloValor(lido)} · corrigido, não entrei")
                corrigirValor(fid, lido)
                Thread.sleep(PAUSA_ENTRE_FILAS)
                continue
            }
            val botao = acharBotao(m, o, modo)
            if (botao == null) {
                val labels = m.botoes.joinToString(", ") { it.label.uppercase() }.ifEmpty { "nenhum" }
                avisarUmaVez("botao|$fid|$gelo", "erro", "fila $nome: não achei o botão de entrar · botões: $labels")
                status(o, "✗ botão diferente: $labels".take(70), "erro")
                esperaFila[fid] = agora() + 60_000
            } else if (jaEstou(jogadores)) {
                // você já está nessa fila (entrou antes ou pelo Discord)
                filasEntradas.add(fid)
                status(o, "✓ na fila $modo ${rotuloFila(o, v)}", "ok")
            } else if (botao.desativado) {
                status(o, "fila $modo ${rotuloFila(o, v)} fechada agora", "info")
            } else {
                if (!buscando) return
                val nonce = registrarClique("fila", fid, o, nome)
                cliques[nonce] = arrayOf(fid, o, nome)
                val r = d.clicar(m, botao, nonce) { buscando && filasEntradas.size < maxFilas }
                diag("clique $nome botão '${botao.label}' -> ${r.codigo}${if (!r.ok) " " + r.corpo.take(200) else ""}")
                if (r.codigo == -2) { cliques.remove(nonce); return }
                if (r.ok) {
                    // espera a org responder: se abrir formulário (DUCK, FURIA, HELIPA...) ainda NÃO entrou
                    val ate = agora() + 2_500
                    var okEm = 0L
                    while (agora() < ate) {
                        val x = desfecho[nonce]
                        if (x == "form" || x == "falha") break
                        if (x == "ok") { if (okEm == 0L) okEm = agora() else if (agora() - okEm > 400) break }
                        Thread.sleep(60)
                    }
                    val x = synchronized(travaEntrada) {
                        val dx = desfecho[nonce]
                        if (dx != "form" && dx != "falha") filasEntradas.add(fid)
                        dx
                    }
                    if (x == "form" || x == "falha") { Thread.sleep(PAUSA_ENTRE_FILAS); continue }
                    ultimaFila[o] = fid to nome
                    m.guildId?.let { entrouEm[it] = agora() }
                    entradas++
                    log("ok", "entrou na fila $nome")
                    status(o, "✓ na fila $modo ${rotuloFila(o, v)}", "ok")
                    Thread.sleep(COOLDOWN)
                } else {
                    cliques.remove(nonce)
                    // não tenta essa fila de novo na hora (antes repetia a cada segundo)
                    esperaFila[fid] = agora() + 30_000
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

    // ======================================================================== formulários (modal)
    private fun chaveCampo(rotulo: String, customId: String): String {
        val k = semAcento(rotulo).lowercase().filter { it.isLetterOrDigit() }.take(60)
        return k.ifEmpty { "id:" + customId.take(60) }
    }

    /** lê o formulário que veio no INTERACTION_MODAL_CREATE */
    private fun lerFormulario(d: JSONObject, nonce: String, org: String, nome: String, fid: String?, salaId: String?,
                              canalId: String, gid: String?): SForm? {
        val modalId = d.s("id") ?: return null
        val custom = d.s("custom_id") ?: return null
        val appId = d.optJSONObject("application")?.s("id") ?: d.s("application_id") ?: return null
        val bruto = d.optJSONArray("components") ?: JSONArray()
        val campos = ArrayList<SCampo>()
        val textos = ArrayList<String>()
        fun campo(x: JSONObject?, rotulo: String?, descricao: String?) {
            if (x == null) return
            val tipo = x.optInt("type")
            val cid = x.s("custom_id") ?: return
            val rot = (rotulo ?: x.s("label") ?: x.s("placeholder") ?: cid).trim()
            val dica = (descricao ?: x.s("placeholder") ?: "").trim()
            if (tipo == 4) {
                campos.add(SCampo(chaveCampo(rot, cid), rot, dica, 4, cid, x.optBoolean("required", true), x.s("value") ?: "",
                    emptyList(), emptyList(), x.optInt("min_length", 0), x.optInt("max_length", 4000)))
            } else if (tipo == 23) {
                // uma caixinha só (marcar ou não)
                campos.add(SCampo(chaveCampo(rot, cid), rot, dica, 23, cid, x.optBoolean("required", false), "",
                    listOf("marcar" to "true"), if (x.optBoolean("default", false)) listOf("true") else emptyList(), 0, 1))
            } else {
                val ops = ArrayList<Pair<String, String>>()
                val pad = ArrayList<String>()
                x.optJSONArray("options")?.let { a ->
                    for (i in 0 until a.length()) {
                        val op = a.optJSONObject(i) ?: continue
                        val v = op.s("value") ?: continue
                        ops.add((op.s("label") ?: v) to v)
                        if (op.optBoolean("default", false)) pad.add(v)
                    }
                }
                val obrig = x.optBoolean("required", true) && x.optInt("min_values", 1) > 0
                val max = when (tipo) { 21 -> 1; 22 -> x.optInt("max_values", ops.size.coerceAtLeast(1)); else -> x.optInt("max_values", 1) }
                campos.add(SCampo(chaveCampo(rot, cid), rot, dica, tipo, cid, obrig, "", ops, pad,
                    x.optInt("min_values", 1), max))
            }
        }
        for (i in 0 until bruto.length()) {
            val c = bruto.optJSONObject(i) ?: continue
            when (c.optInt("type")) {
                1 -> c.optJSONArray("components")?.let { a -> for (j in 0 until a.length()) campo(a.optJSONObject(j), null, null) }
                18 -> campo(c.optJSONObject("component"), c.s("label"), c.s("description"))
                10 -> c.s("content")?.let { textos.add(it) }
            }
        }
        val titulo = d.s("title") ?: "formulário"
        val tudo = semAcento((titulo + " " + textos.joinToString(" ") + " " + campos.joinToString(" ") { it.rotulo + " " + it.dica }).lowercase())
        val verif = VERIFICACAO.any { it in tudo }
        return SForm(nonce, modalId, custom, appId, canalId, gid, titulo, org, nome, fid, salaId, textos, campos, bruto, verif)
    }

    /** formulário anti-robô das orgs: quem responde é você (o bot não tenta adivinhar) */
    private val VERIFICACAO = listOf("verificacao", "seguranca", "captcha", "resposta correta", "quanto e", "conte os", "conte as",
        "robo", "humano", "selecione uma comida")

    private fun respostasSalvas(): JSONObject = try { JSONObject(sp.getString("respostas_form", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    /** o que o bot já sabe pôr nesse campo (resposta que você deu antes, valor que já vem preenchido, opção padrão) */
    fun respostaConhecida(f: SCampo): List<String>? {
        val salvo = respostasSalvas().optJSONArray(f.chave)?.let { a -> (0 until a.length()).map { a.optString(it) } }
        if (f.tipo == 4) {
            salvo?.firstOrNull()?.let { return listOf(it) }
            if (f.inicial.isNotEmpty()) return listOf(f.inicial)
            return if (!f.obrigatorio) listOf("") else null
        }
        if (f.tipo == 3 || f.tipo == 21 || f.tipo == 22 || f.tipo == 23) {
            val validos = f.opcoes.map { it.second }
            salvo?.filter { it in validos }?.takeIf { it.isNotEmpty() }?.let { return it }
            if (f.padrao.isNotEmpty()) return f.padrao
            // opção de gelo igual à que você escolheu na BUSCA
            val g = if (gelo == "INFINITO") "INFIN" else "NORMAL"
            f.opcoes.firstOrNull { g in semAcento(it.first).uppercase() }?.let { return listOf(it.second) }
            return if (!f.obrigatorio) emptyList() else null
        }
        return if (!f.obrigatorio) emptyList() else null
    }

    private fun respostasAuto(f: SForm): Map<String, List<String>>? {
        if (f.verificacao) return null
        val out = HashMap<String, List<String>>()
        for (c in f.campos) out[c.customId] = respostaConhecida(c) ?: return null
        return out
    }

    private fun montarRespostas(f: SForm, valores: Map<String, List<String>>): JSONArray {
        fun resp(x: JSONObject?): JSONObject? {
            if (x == null) return null
            val tipo = x.optInt("type")
            val cid = x.s("custom_id") ?: return null
            val o = JSONObject().put("type", tipo).put("custom_id", cid)
            if (x.has("id")) o.put("id", x.optInt("id"))
            val v = valores[cid] ?: emptyList()
            when (tipo) {
                4 -> o.put("value", v.firstOrNull() ?: "")
                21 -> o.put("value", v.firstOrNull() ?: JSONObject.NULL)          // radio: uma opção
                23 -> o.put("value", v.firstOrNull() == "true")                   // caixinha: marcou ou não
                else -> o.put("values", JSONArray(v))                              // lista / várias caixas
            }
            return o
        }
        val out = JSONArray()
        for (i in 0 until f.bruto.length()) {
            val c = f.bruto.optJSONObject(i) ?: continue
            when (c.optInt("type")) {
                1 -> {
                    val linha = JSONArray()
                    c.optJSONArray("components")?.let { a -> for (j in 0 until a.length()) resp(a.optJSONObject(j))?.let { linha.put(it) } }
                    if (linha.length() > 0) out.put(JSONObject().put("type", 1).put("components", linha).also { o -> if (c.has("id")) o.put("id", c.optInt("id")) })
                }
                18 -> resp(c.optJSONObject("component"))?.let { r ->
                    out.put(JSONObject().put("type", 18).put("component", r).also { o -> if (c.has("id")) o.put("id", c.optInt("id")) })
                }
                10 -> if (c.has("id")) out.put(JSONObject().put("type", 10).put("id", c.optInt("id")))
            }
        }
        return out
    }

    private fun aoFormulario(d: JSONObject) {
        diag("formulário: ${d.toString().take(3000)}")
        val nonce = d.s("nonce") ?: ""
        val info = cliqueInfo[nonce]
        val tipo = info?.get(0) ?: "?"
        val canalId = d.s("channel_id") ?: ""
        val gid = d.s("guild_id") ?: canais[canalId]?.guildId ?: gidDe(canalId)
        val o = info?.get(2) ?: adivinharOrg(gid)
        val nome = info?.get(3) ?: o
        val fid = if (tipo == "fila") info?.get(1) else null
        val f = lerFormulario(d, nonce, o, nome, fid, if (tipo == "sala") info?.get(1) else null, canalId, gid) ?: run {
            log("erro", "$nome: a org abriu um formulário que o app não entendeu (detalhes no registro completo)")
            return
        }
        if (fid != null) {
            synchronized(travaEntrada) {
                if (filasEntradas.remove(fid) && entradas > 0) entradas--
            }
            // não clica de novo nessa fila enquanto o formulário estiver valendo
            esperaFila[fid] = agora() + FORM_VALIDO
        }
        val auto = respostasAuto(f)
        if (auto != null) {
            exRapido?.execute { seguro { responderFormulario(f, auto, false) } }
            return
        }
        formularios.removeAll { it.fid != null && it.fid == fid }
        while (formularios.size >= 12) formularios.removeAt(0).fid?.let { esperaFila.remove(it) }
        formularios.add(f)
        // não clica nas outras filas dessa org até você responder (senão cada valor abre outro formulário)
        orgPausada[o] = agora() + FORM_VALIDO
        val campos = f.campos.joinToString(" · ") { it.rotulo }
        if (f.verificacao) {
            val pergunta = f.campos.firstOrNull()?.dica?.ifEmpty { null } ?: f.textos.firstOrNull() ?: f.titulo
            log("info", "${f.nome}: a org pediu verificação de segurança ('$pergunta') · responda lá em cima pra entrar")
            status(o, "🛡 verificação: responda no app", "pausa")
            notificar("$o: verificação pra entrar na fila", pergunta)
        } else {
            avisarUmaVez("form|$o|${f.titulo}", "erro", "$o pediu um formulário ('${f.titulo}': $campos) · responda uma vez lá em cima que o bot lembra e responde sozinho nas próximas")
            status(o, "📝 pediu formulário: responda no app", "pausa")
            notificar("$o pediu um formulário", "${f.titulo}: $campos")
        }
        mudou()
    }

    /** manda o formulário. doUsuario = você respondeu agora (o bot guarda as respostas pras próximas) */
    private fun responderFormulario(f: SForm, valores: Map<String, List<String>>, doUsuario: Boolean) {
        val d = dc ?: return
        formularios.remove(f)
        if (doUsuario) orgPausada.remove(f.org)
        if (agora() - f.chegou > FORM_VALIDO) {
            f.fid?.let { esperaFila.remove(it) }
            log("info", "${f.nome}: esse formulário expirou · o bot clica de novo na fila")
            mudou()
            return
        }
        if (doUsuario && !f.verificacao) {
            val sv = respostasSalvas()
            for (c in f.campos) {
                val v = valores[c.customId] ?: continue
                if (v.isEmpty() || v.all { it.isBlank() }) continue
                sv.put(c.chave, JSONArray(v))
            }
            sp.edit().putString("respostas_form", sv.toString()).apply()
            marcarBackup()
        }
        val n = Snow.novo()
        enviosForm[n] = f
        if (f.fid != null) ultimaFila[f.org] = f.fid to f.nome
        val r = d.enviarFormulario(f.appId, f.canalId, f.guildId, f.modalId, f.customId, montarRespostas(f, valores), n)
        diag("formulário ${f.nome} enviado -> ${r.codigo}${if (!r.ok) " " + r.corpo.take(300) else ""}")
        if (!r.ok) {
            enviosForm.remove(n)
            f.fid?.let { esperaFila[it] = agora() + 30_000 }
            log("erro", "${f.nome}: não deu pra mandar o formulário (${motivo(r)})")
        } else if (doUsuario && f.verificacao) {
            log("ok", "${f.nome}: verificação respondida")
        } else if (doUsuario) {
            log("ok", "${f.nome}: formulário enviado · as próximas a org responde sozinha")
            // os outros formulários parados que agora o bot sabe responder
            for (g in formularios.toList()) respostasAuto(g)?.let { responderFormulario(g, it, false) }
        }
        mudou()
    }

    private fun formularioAceito(f: SForm) {
        val fid = f.fid
        if (fid != null) {
            filasEntradas.add(fid)
            esperaFila.remove(fid)
            entradas++
            f.guildId?.let { entrouEm[it] = agora() }
            log("ok", "entrou na fila ${f.nome} (respondeu o formulário)")
            status(f.org, "✓ na fila (formulário respondido)", "ok")
        } else log("ok", "${f.nome}: formulário aceito")
        mudou()
    }

    // ---------------- formulário: tela ----------------
    fun responderPeloApp(f: SForm, valores: Map<String, List<String>>) {
        exRapido?.execute { seguro { responderFormulario(f, valores, true) } } ?: log("erro", "ligue o bot primeiro")
    }

    fun ignorarFormulario(f: SForm) {
        formularios.remove(f)
        if (formularios.none { it.org == f.org }) orgPausada.remove(f.org)
        f.fid?.let { esperaFila[it] = agora() + 5 * 60_000 }
        log("info", "${f.nome}: formulário ignorado (não tenta essa fila por 5 min)")
        mudou()
    }

    fun qtdRespostasSalvas(): Int = respostasSalvas().length()

    fun apagarRespostas() {
        sp.edit().remove("respostas_form").apply()
        marcarBackup()
        log("info", "respostas dos formulários apagadas")
        mudou()
    }

    // ======================================================================== descobrir valores
    /** abre cada fila sem valor (GOLD, FARM...), lê o "Valor: R$ ..." dela e guarda. Só lê, não entra em fila. */
    fun descobrir(conferirTudo: Boolean = false) {
        if (descobrindo) return
        if (!conectado || dc == null) { log("erro", "ligue o bot primeiro"); return }
        val lista = ArrayList<Triple<String, String, String>>()
        for ((o, ms) in pendentes) for ((m, ids) in ms) for (id in ids) lista.add(Triple(o, m, id))
        val conferir = ArrayList<Triple<String, String, String>>()
        if (conferirTudo) {
            val vistos = HashSet<String>()
            for ((o, od) in orgs) for ((m, f) in od.filas) for ((v, id) in f) if (vistos.add(id)) conferir.add(Triple(o, m, id))
        }
        if (lista.isEmpty() && conferir.isEmpty()) { log("info", "nenhuma fila sem valor"); return }
        descobrindo = true
        log("info", "lendo ${lista.size + conferir.size} filas (só lê, não entra em nenhuma)...")
        val th = Thread {
            var achou = 0
            val faltou = ArrayList<String>()
            val apr = try { JSONObject(sp.getString("aprendidos", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            val semServidor = HashSet<String>()
            val semAchar = HashMap<String, Int>()
            var apagadas = 0
            val naoAchou = try { JSONObject(sp.getString("nao_achou", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            try {
                // confere as que já têm valor: ID com valor trocado é corrigido
                var corrigidas = 0
                for ((i, t) in conferir.withIndex()) {
                    if (dc == null) break
                    val (o, m, id) = t
                    if (o in semServidor) continue
                    progresso = "conferindo $o $m (${i + 1}/${conferir.size})"
                    mudou()
                    val msg = acharFila(id, o, forcar = true) ?: continue
                    val lido = lerValor(msg) ?: continue
                    val aceitos = orgs[o]?.filas?.get(m)?.filter { it.value == id }?.keys ?: emptySet()
                    if (lido !in aceitos) {
                        corrigirValor(id, lido)
                        corrigidas++
                        log("info", "$o $m: ID corrigido pra ${rotuloValor(lido)}")
                    }
                    Thread.sleep(250)
                }
                if (conferir.isNotEmpty()) log("ok", "conferência: $corrigidas filas estavam com valor trocado e foram corrigidas")
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
                        } else {
                            // não achou em nenhum canal da org: na 2ª vez seguida conta como apagada (a org postou o painel de novo)
                            val k = naoAchou.optInt(id, 0) + 1
                            naoAchou.put(id, k)
                            diag("descobrir: $o $m fila $id não encontrada (${k}ª vez)")
                            if (k >= 2) { corrigirValor(id, "-"); apagadas++ }
                            else faltou.add("$o $m: não achei a fila $id (se faltar de novo na próxima, sai da lista)")
                        }
                        continue
                    }
                    val v = lerValor(msg)
                    if (v == null) {
                        faltou.add("$o $m: não deu pra ler o valor da fila $id")
                        diag("descobrir: $o $m $id sem valor · texto: ${textoCard(msg).take(400)} · botões: ${msg.botoes.joinToString { it.label }}" +
                            (msg.bruto?.let { " · bruto: $it" } ?: ""))
                        continue
                    }
                    val jaTem = orgs[o]?.filas?.get(m)?.get(v) ?: apr.optJSONObject(o)?.optJSONObject(m)?.s(v)
                    if (jaTem != null && jaTem != id) {
                        // já tinha outro ID nesse valor: abre ele também e vê quem está certo
                        val outra = acharFila(jaTem, o, forcar = true)
                        val lidoOutra = outra?.let { lerValor(it) }
                        if (lidoOutra == v) { faltou.add("$o $m: duas filas de ${rotuloValor(v)} ($jaTem e $id), fiquei com a primeira"); continue }
                        corrigirValor(jaTem, lidoOutra ?: "-")
                        log("info", "$o $m: o ID que estava como ${rotuloValor(v)} era " +
                            (lidoOutra?.let { "de ${rotuloValor(it)}" } ?: "de fila apagada") + " · corrigido")
                    }
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
                sp.edit().putString("aprendidos", apr.toString()).putString("nao_achou", naoAchou.toString()).apply()
                marcarBackup()
                carregarOrgs()
                descobrindo = false
                progresso = ""
                log("ok", "pronto · $achou filas descobertas" + (if (apagadas > 0) " · $apagadas apagadas pela org saíram da lista" else "") +
                    (if (faltou.isNotEmpty()) " · ${faltou.size} ficaram sem valor" else ""))
                // um aviso por org (antes era uma linha por fila)
                for ((org, qts) in faltou.groupBy { it.substringBefore(' ').substringBefore(':') }) {
                    if (qts.size == 1) log("erro", qts[0])
                    else log("erro", "$org: ${qts.size} filas sem valor (${qts[0].substringAfter(": ").substringBefore(" da fila").substringBefore(" a fila")}) · detalhes no registro completo")
                    for (q in qts) diag("descobrir: $q")
                }
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
        buscando -> "BOT DE FILA buscando · ${filasEntradas.size} filas · ${salas.size} salas"
        else -> "BOT DE FILA ${estado.uppercase()}"
    }
}
