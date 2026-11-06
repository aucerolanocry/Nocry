package com.nocry.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Avisa o servidor que o app esta em uso, pra staff ver no painel quem esta online e o historico.
 *
 * Manda SO: a key, o codigo anonimo do aparelho (o mesmo do login), a versao do app, se o app esta na tela,
 * o que esta ligado agora (bypass e modos) e os eventos (ligou/desligou/aplicou) com horario.
 * Nada de arquivos, contatos, localizacao ou o que mais tiver no celular.
 *
 * Sem internet os eventos ficam numa fila (no maximo 100) e vao no proximo envio.
 */
object Presenca {
    private const val INTERVALO = 60_000L
    private const val MAX_FILA = 100
    private const val POR_ENVIO = 50
    private const val REVALIDA_MIN = 5 * 60_000L

    /** true enquanto alguma tela do app esta aberta (a MainActivity liga e desliga) */
    @Volatile var naTela = false
    @Volatile private var ultimo = 0L
    @Volatile private var enviando = false
    @Volatile private var ultimaRevalida = 0L
    @Volatile private var seq = 0
    private val trava = Any()

    fun pode(): Boolean = Prefs.avisoAtividade && Prefs.licKey != null && BuildConfig.API_URL.isNotBlank()

    fun gatNome(g: String): String = when (g) {
        "dnd" -> "NÃO PERTURBE"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; "volume" -> "VOLUME"; else -> g.uppercase()
    }
    fun ffNome(ff: String): String = if (ff == "max") "FF MAX" else "FF NORMAL"

    /** guarda um evento e manda na hora (se der) */
    fun evento(tipo: String, detalhe: String = "") {
        if (!pode()) return
        try {
            synchronized(trava) {
                val fila = lerFila()
                seq = (seq + 1) % 1000
                val id = System.currentTimeMillis() * 1000 + seq
                fila.put(JSONObject().put("i", id).put("t", System.currentTimeMillis()).put("k", tipo).put("d", detalhe.take(120)))
                val sobra = fila.length() - MAX_FILA
                val final = if (sobra > 0) JSONArray().also { n -> for (i in sobra until fila.length()) n.put(fila.get(i)) } else fila
                Prefs.presencaFila = final.toString()
            }
        } catch (_: Throwable) {}
        ping(Sys.app, forcar = true)
    }

    /** "estou aqui": no maximo 1 por minuto (forcar = manda agora, usado quando tem evento novo) */
    fun ping(c: Context, forcar: Boolean = false) {
        if (!pode()) return
        val agora = System.currentTimeMillis()
        if (!forcar && agora - ultimo < INTERVALO - 3_000L) return
        if (enviando) return // o evento fica na fila e vai no proximo
        enviando = true
        ultimo = agora
        val app = c.applicationContext
        Bg.io {
            try { envia(app) } catch (_: Throwable) {} finally { enviando = false }
        }
    }

    private fun lerFila(): JSONArray = try { JSONArray(Prefs.presencaFila ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun estado(): JSONObject {
        val o = JSONObject()
        val byp = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        o.put("byp", byp)
        if (byp) {
            o.put("gat", if (Prefs.bypassVolAtivo && !Prefs.bypassAtivo) "volume" else Prefs.bypassGatilho)
            o.put("ff", Prefs.bypassFf)
            o.put("tipo", Prefs.bypassTipo)
            o.put("apl", Prefs.bypassMark)
        }
        o.put("vol", Prefs.volAtivo)
        o.put("carga", Prefs.cargaAtivo)
        o.put("mc", Prefs.mcAtivo)
        o.put("comp", Prefs.compFlag)
        o.put("otm", Prefs.otmAuto)
        return o
    }

    private fun envia(c: Context) {
        val key = Prefs.licKey ?: return
        val mandar = JSONArray()
        val ids = HashSet<Long>()
        synchronized(trava) {
            val fila = lerFila()
            for (i in 0 until minOf(fila.length(), POR_ENVIO)) {
                val o = fila.optJSONObject(i) ?: continue
                mandar.put(o)
                ids.add(o.optLong("i"))
            }
        }
        val corpo = JSONObject()
            .put("key", key)
            .put("dev", License.devId(c))
            .put("app", License.versao(c))
            .put("fg", naTela)
            .put("agora", System.currentTimeMillis())
            .put("estado", estado())
            .put("ev", mandar)
        val j = License.post("/v1/ping", corpo) ?: return // sem internet: tenta de novo no proximo
        if (j.optBoolean("ok", false)) {
            if (ids.isNotEmpty()) synchronized(trava) {
                val atual = lerFila()
                val resto = JSONArray()
                for (i in 0 until atual.length()) {
                    val o = atual.optJSONObject(i) ?: continue
                    if (o.optLong("i") !in ids) resto.put(o)
                }
                Prefs.presencaFila = resto.toString()
            }
            return
        }
        when (j.optString("reason")) {
            "invalid", "revoked", "expired", "blocked" -> {
                // o servidor disse que essa key/aparelho nao vale mais: confere de verdade agora
                // (se a key foi revogada o app sai; se so soltaram o aparelho, ele entra de novo sozinho)
                val agora = System.currentTimeMillis()
                if (agora - ultimaRevalida > REVALIDA_MIN) {
                    ultimaRevalida = agora
                    val r = License.revalida(c)
                    if (r.negado()) synchronized(trava) { Prefs.presencaFila = "[]" }
                }
            }
        }
    }
}
