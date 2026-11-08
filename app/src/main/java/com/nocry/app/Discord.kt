package com.nocry.app

import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Conexão com o Discord pra o SNIPER (conta de usuário, só do dono).
 * Gateway (eventos ao vivo) + REST (ler fila, clicar botão, mandar mensagem).
 * Se apresenta como o app oficial do Discord pra Android.
 */

/** valor em texto de uma chave do JSON, ou null se não tiver (o optString do Android devolve "null") */
fun JSONObject.s(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

object Snow {
    const val EPOCA = 1420070400000L
    fun ms(id: String): Long = try { (id.toLong() shr 22) + EPOCA } catch (_: Exception) { 0L }
    fun novo(): String = ((System.currentTimeMillis() - EPOCA) shl 22).toString()
}

class DCanal(
    val id: String,
    val guildId: String?,
    var nome: String,
    val tipo: Int,
    val parentId: String?,
    val privado: Boolean,
    var membro: Boolean,
    /** você tem acesso garantido (o Discord avisou ao vivo, ou tem permissão com o seu id no canal) */
    val meuAcesso: Boolean = false
) {
    /** 10, 11, 12 = threads */
    val ehThread: Boolean get() = tipo in 10..12
    val ehTexto: Boolean get() = tipo == 0 || tipo == 5
    val criadoMs: Long get() = Snow.ms(id)
}

class DBotao(val customId: String, val label: String, val desativado: Boolean)
class DCampo(val nome: String, val valor: String)
class DEmbed(val titulo: String, val descricao: String, val campos: List<DCampo>)

class DMsg(
    val id: String,
    val canalId: String,
    val guildId: String?,
    val autorId: String,
    val autorNome: String,
    val autorBot: Boolean,
    val conteudo: String,
    val embeds: List<DEmbed>,
    val botoes: List<DBotao>,
    val mencoes: List<Pair<String, String>>,
    val flags: Int,
    val interacaoUser: String?,
    val appId: String?
) {
    val efemera: Boolean get() = flags and 64 != 0

    companion object {
        fun de(o: JSONObject, guildPadrao: String? = null): DMsg {
            val a = o.optJSONObject("author") ?: JSONObject()
            val embeds = ArrayList<DEmbed>()
            o.optJSONArray("embeds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val campos = ArrayList<DCampo>()
                    e.optJSONArray("fields")?.let { fs ->
                        for (j in 0 until fs.length()) {
                            val f = fs.optJSONObject(j) ?: continue
                            campos.add(DCampo(f.s("name") ?: "", f.s("value") ?: ""))
                        }
                    }
                    embeds.add(DEmbed(e.s("title") ?: "", e.s("description") ?: "", campos))
                }
            }
            val botoes = ArrayList<DBotao>()
            fun lerComp(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    when (c.optInt("type")) {
                        2 -> {
                            val id = c.s("custom_id")
                            val label = c.s("label")
                            if (id != null && label != null) botoes.add(DBotao(id, label, c.optBoolean("disabled", false)))
                        }
                        else -> {
                            lerComp(c.optJSONArray("components"))
                            c.optJSONObject("accessory")?.let { lerComp(JSONArray().put(it)) }
                        }
                    }
                }
            }
            lerComp(o.optJSONArray("components"))
            val mencoes = ArrayList<Pair<String, String>>()
            o.optJSONArray("mentions")?.let { ms ->
                for (i in 0 until ms.length()) {
                    val u = ms.optJSONObject(i) ?: continue
                    mencoes.add((u.s("id") ?: "") to (u.s("global_name") ?: u.s("username") ?: "alguém"))
                }
            }
            val inter = (o.optJSONObject("interaction_metadata") ?: o.optJSONObject("interaction"))
                ?.optJSONObject("user")?.s("id")
            val bot = a.optBoolean("bot", false)
            return DMsg(
                id = o.s("id") ?: "",
                canalId = o.s("channel_id") ?: "",
                guildId = o.s("guild_id") ?: guildPadrao,
                autorId = a.s("id") ?: "",
                autorNome = o.optJSONObject("member")?.s("nick") ?: a.s("global_name") ?: a.s("username") ?: "?",
                autorBot = bot,
                conteudo = o.s("content") ?: "",
                embeds = embeds,
                botoes = botoes,
                mencoes = mencoes,
                flags = o.optInt("flags", 0),
                interacaoUser = inter,
                appId = o.s("application_id") ?: o.optJSONObject("webhook")?.s("id") ?: (if (bot) a.s("id") else null)
            )
        }
    }
}

fun canalDe(o: JSONObject, guildPadrao: String? = null, eu: String? = null, aoVivo: Boolean = false): DCanal {
    val gid = o.s("guild_id") ?: guildPadrao
    val tipo = o.optInt("type", 0)
    var privado = tipo == 12
    var meu = aoVivo
    o.optJSONArray("permission_overwrites")?.let { ows ->
        for (i in 0 until ows.length()) {
            val ow = ows.optJSONObject(i) ?: continue
            val id = ow.s("id")
            // canal escondido de @everyone (o id do cargo @everyone é o id do servidor)
            if (gid != null && id == gid && ((ow.s("deny")?.toLongOrNull() ?: 0L) and 1024L) != 0L) privado = true
            // permissão de ver o canal dada pro seu usuário (sala privada sua)
            if (eu != null && eu.isNotEmpty() && id == eu && ((ow.s("allow")?.toLongOrNull() ?: 0L) and 1024L) != 0L) meu = true
        }
    }
    return DCanal(o.s("id") ?: "", gid, o.s("name") ?: "", tipo, o.s("parent_id"), privado, o.has("member"), meu)
}

class Resp(val codigo: Int, val corpo: String) {
    val ok: Boolean get() = codigo in 200..299
    fun obj(): JSONObject? = try { JSONObject(corpo) } catch (_: Exception) { null }
    fun arr(): JSONArray? = try { JSONArray(corpo) } catch (_: Exception) { null }
    /** código de erro do Discord (50013 = sem permissão, 10008 = mensagem não existe...) */
    fun erro(): Int = obj()?.optInt("code", 0) ?: 0
    fun captcha(): Boolean = corpo.contains("captcha_key")
}

class Discord(private val token: String, private val vendorId: String, private val ouvinte: Ouvinte) {

    interface Ouvinte {
        fun pronto(eu: String, nome: String, d: JSONObject)
        fun evento(tipo: String, d: JSONObject)
        fun caiu(motivo: String, fatal: Boolean)
    }

    companion object {
        // versão do app oficial do Discord pra Android que ele imita (dá pra atualizar aqui)
        const val VERSAO = "301.14 - rn"
        const val BUILD = 301014
        const val API = "https://discord.com/api/v9"
        const val GATEWAY = "wss://gateway.discord.gg"
        private val JSON = "application/json".toMediaType()
    }

    private val ua = "Discord-Android/$BUILD;RNA"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun props(): JSONObject = JSONObject()
        .put("os", "Android")
        .put("browser", "Discord Android")
        .put("device", Build.MODEL ?: "")
        .put("system_locale", "pt-BR")
        .put("client_version", VERSAO)
        .put("release_channel", "googleRelease")
        .put("device_vendor_id", vendorId)
        .put("browser_user_agent", "")
        .put("browser_version", "")
        .put("os_version", Build.VERSION.SDK_INT.toString())
        .put("client_build_number", BUILD)
        .put("client_event_source", JSONObject.NULL)
        .put("design_id", 0)

    private val superProps: String by lazy {
        Base64.encodeToString(props().toString().toByteArray(), Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ REST
    private val trava = Object()
    private val travaClique = Object()
    @Volatile private var ultimoPedido = 0L
    @Volatile private var ultimoClique = 0L

    /** segura os pedidos em fila: no máximo ~10 por segundo, mesmo com todas as orgs buscando juntas */
    private fun esperaVez(caminho: String) {
        if (caminho == "/interactions") {
            // o Discord limita os cliques em botão por conta (dava 429): um a cada 1,3 s
            synchronized(travaClique) {
                val falta = ultimoClique + 1300 - System.currentTimeMillis()
                if (falta > 0) Thread.sleep(falta)
                ultimoClique = System.currentTimeMillis()
            }
        }
        synchronized(trava) {
            val falta = ultimoPedido + 100 - System.currentTimeMillis()
            if (falta > 0) Thread.sleep(falta)
            ultimoPedido = System.currentTimeMillis()
        }
    }

    fun req(metodo: String, caminho: String, corpo: JSONObject? = null, antes: (() -> Boolean)? = null): Resp {
        var tentativa = 0
        while (true) {
            esperaVez(caminho)
            // esperou a vez de clicar: confere se ainda pode (ex.: a busca foi pausada enquanto esperava)
            if (antes != null && !antes()) return Resp(-2, "cancelado")
            val b = Request.Builder().url(API + caminho)
                .header("Authorization", token)
                .header("User-Agent", ua)
                .header("X-Super-Properties", superProps)
                .header("X-Discord-Locale", "pt-BR")
                .header("X-Discord-Timezone", "America/Sao_Paulo")
                .header("Accept-Language", "pt-BR")
            val rb = corpo?.toString()?.toRequestBody(JSON)
            when (metodo) {
                "GET" -> b.get()
                "DELETE" -> b.delete(rb)
                "PUT" -> b.put(rb ?: ByteArray(0).toRequestBody(null))
                else -> b.method(metodo, rb ?: "{}".toRequestBody(JSON))
            }
            val r: Resp = try {
                http.newCall(b.build()).execute().use { x: Response -> Resp(x.code, x.body?.string() ?: "") }
            } catch (t: Throwable) {
                Resp(-1, t.toString())
            }
            if (r.codigo == 429 && tentativa < 3) {
                // limite de velocidade: espera o que o Discord mandou e tenta de novo
                val s = r.obj()?.optDouble("retry_after", 2.0) ?: 2.0
                Thread.sleep((s * 1000).toLong().coerceIn(500, 20_000) + 150)
                tentativa++
                continue
            }
            return r
        }
    }

    fun eu(): Resp = req("GET", "/users/@me")
    fun servidores(): Resp = req("GET", "/users/@me/guilds")
    fun canais(gid: String): Resp = req("GET", "/guilds/$gid/channels")
    fun canal(cid: String): Resp = req("GET", "/channels/$cid")
    fun threadsAtivas(gid: String): Resp = req("GET", "/guilds/$gid/threads/active")

    /** busca uma mensagem pelo id (conta de usuário usa o "around"). null se não estiver nesse canal */
    fun mensagem(cid: String, mid: String, gid: String? = null): Pair<DMsg?, Resp> {
        val r = req("GET", "/channels/$cid/messages?limit=1&around=$mid")
        if (!r.ok) return null to r
        val a = r.arr() ?: return null to r
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.s("id") == mid) return DMsg.de(o, gid) to r
        }
        return null to r
    }

    /** últimas mensagens (mais nova primeiro, igual o Discord devolve). depois = só as mais novas que esse id */
    fun historico(cid: String, limite: Int, depois: String? = null, gid: String? = null): Pair<List<DMsg>, Resp> {
        val q = "/channels/$cid/messages?limit=$limite" + (if (depois != null) "&after=$depois" else "")
        val r = req("GET", q)
        if (!r.ok) return emptyList<DMsg>() to r
        val a = r.arr() ?: return emptyList<DMsg>() to r
        val out = ArrayList<DMsg>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(DMsg.de(it, gid)) }
        return out to r
    }

    fun enviar(cid: String, texto: String): Resp = req("POST", "/channels/$cid/messages", JSONObject()
        .put("content", texto)
        .put("nonce", Snow.novo())
        .put("enforce_nonce", true)
        .put("tts", false)
        .put("flags", 0))

    fun entrarThread(tid: String): Resp = req("PUT", "/channels/$tid/thread-members/@me")

    /** clica num botão de mensagem (o mesmo que tocar no botão no app) */
    fun clicar(m: DMsg, b: DBotao, nonce: String = Snow.novo(), antes: (() -> Boolean)? = null): Resp {
        val corpo = JSONObject()
            .put("type", 3)
            .put("nonce", nonce)
            .put("channel_id", m.canalId)
            .put("message_flags", m.flags)
            .put("message_id", m.id)
            .put("application_id", m.appId ?: m.autorId)
            .put("session_id", sessao ?: "")
            .put("data", JSONObject().put("component_type", 2).put("custom_id", b.customId))
        if (m.guildId != null) corpo.put("guild_id", m.guildId)
        return req("POST", "/interactions", corpo, antes)
    }

    // ------------------------------------------------------------------ gateway
    @Volatile private var ws: WebSocket? = null
    @Volatile var sessao: String? = null
        private set
    @Volatile private var seq: Long = -1
    @Volatile private var urlRetomar: String? = null
    @Volatile private var ligado = false
    @Volatile private var ack = true
    @Volatile private var batida: Thread? = null
    @Volatile private var tentativas = 0

    fun conectar() {
        ligado = true
        abre(false)
    }

    fun desligar() {
        ligado = false
        batida?.interrupt()
        batida = null
        try { ws?.close(1000, "tchau") } catch (_: Throwable) {}
        ws = null
    }

    fun mandar(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    /** pede pro Discord mandar os eventos de thread desse servidor (o que o app faz quando você abre o servidor) */
    fun assinar(gid: String) {
        mandar(JSONObject().put("op", 14).put("d", JSONObject()
            .put("guild_id", gid)
            .put("typing", true)
            .put("threads", true)
            .put("activities", true)
            .put("members", JSONArray())
            .put("channels", JSONObject())
            .put("thread_member_lists", JSONArray())))
    }

    private fun abre(retomar: Boolean) {
        if (!ligado) return
        val base = if (retomar && urlRetomar != null) urlRetomar!! else GATEWAY
        val req = Request.Builder().url("$base/?v=9&encoding=json").header("User-Agent", ua).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                try { trata(webSocket, JSONObject(text), retomar) } catch (t: Throwable) { NLog.add("sniper", "gateway: $t") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Throwable) {}
                if (webSocket === ws) caiu(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) caiu(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) caiu(-1, t.message ?: t.toString())
            }
        })
    }

    @Synchronized
    private fun caiu(code: Int, motivo: String) {
        if (ws == null && code != 4004) return     // já tratou essa queda (onClosing e onClosed chegam os dois)
        batida?.interrupt()
        batida = null
        ws = null
        if (!ligado) return
        when (code) {
            4004 -> { ligado = false; ouvinte.caiu("o Discord recusou o token", true); return }
            4010, 4011, 4012, 4013, 4014 -> { ligado = false; ouvinte.caiu("o Discord fechou a conexão ($code)", true); return }
            4003, 4007, 4009 -> { sessao = null; seq = -1 }
        }
        tentativas++
        ouvinte.caiu("conexão caiu ($code), voltando...", false)
        val espera = (2000L * tentativas).coerceAtMost(30_000L)
        Thread {
            try { Thread.sleep(espera) } catch (_: InterruptedException) { return@Thread }
            abre(sessao != null)
        }.start()
    }

    private fun trata(w: WebSocket, m: JSONObject, retomar: Boolean) {
        when (m.optInt("op", -1)) {
            10 -> {
                val intervalo = m.optJSONObject("d")?.optLong("heartbeat_interval", 41250L) ?: 41250L
                iniciaBatida(w, intervalo)
                if (retomar && sessao != null) {
                    w.send(JSONObject().put("op", 6).put("d", JSONObject()
                        .put("token", token).put("session_id", sessao).put("seq", seq)).toString())
                } else identifica(w)
            }
            11 -> ack = true
            1 -> w.send(JSONObject().put("op", 1).put("d", if (seq >= 0) seq else JSONObject.NULL).toString())
            7 -> w.close(4000, "reconectar")
            9 -> {
                val volta = m.optBoolean("d", false)
                if (!volta) { sessao = null; seq = -1 }
                Thread {
                    try { Thread.sleep(2500) } catch (_: InterruptedException) {}
                    if (volta && sessao != null) w.close(4000, "retomar") else identifica(w)
                }.start()
            }
            0 -> {
                if (m.has("s") && !m.isNull("s")) seq = m.optLong("s", seq)
                val t = m.s("t") ?: return
                val d = m.optJSONObject("d") ?: JSONObject()
                when (t) {
                    "READY" -> {
                        sessao = d.s("session_id")
                        urlRetomar = d.s("resume_gateway_url")
                        tentativas = 0
                        val u = d.optJSONObject("user") ?: JSONObject()
                        ouvinte.pronto(u.s("id") ?: "", u.s("global_name") ?: u.s("username") ?: "?", d)
                    }
                    "RESUMED" -> { tentativas = 0; ouvinte.evento(t, d) }
                    else -> ouvinte.evento(t, d)
                }
            }
        }
    }

    private fun identifica(w: WebSocket) {
        w.send(JSONObject().put("op", 2).put("d", JSONObject()
            .put("token", token)
            .put("capabilities", 0)
            .put("properties", props())
            .put("presence", JSONObject().put("status", "online").put("since", 0).put("activities", JSONArray()).put("afk", false))
            .put("compress", false)
            .put("client_state", JSONObject().put("guild_versions", JSONObject()))).toString())
    }

    private fun iniciaBatida(w: WebSocket, intervalo: Long) {
        batida?.interrupt()
        ack = true
        val th = Thread {
            try {
                Thread.sleep((intervalo * Math.random()).toLong())
                while (!Thread.currentThread().isInterrupted && ws === w) {
                    if (!ack) { w.close(4000, "sem resposta"); return@Thread }
                    ack = false
                    w.send(JSONObject().put("op", 1).put("d", if (seq >= 0) seq else JSONObject.NULL).toString())
                    Thread.sleep(intervalo)
                }
            } catch (_: InterruptedException) {
            }
        }
        th.isDaemon = true
        th.name = "sniper-batida"
        batida = th
        th.start()
    }
}
