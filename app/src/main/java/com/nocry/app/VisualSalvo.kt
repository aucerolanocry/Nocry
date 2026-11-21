package com.nocry.app

import android.util.Base64
import org.json.JSONObject

/**
 * O visual inteiro do ESTUDIO como texto: usado nos 3 "meus temas" e no codigo que a pessoa passa pros amigos.
 * So leva cores, formato, fonte e efeitos; foto/video do fundo e a musica da intro ficam no celular de cada um.
 */
object VisualSalvo {
    private const val PREFIXO = "NOCRY1:"

    fun atual(): String {
        val j = JSONObject()
        j.put("a1", Prefs.accent)
        j.put("a2", Prefs.accent2)
        j.put("base", Prefs.baseCor)
        j.put("ang", Prefs.angulo)
        j.put("cartao", Prefs.cartao)
        j.put("fonte", Prefs.fonte)
        j.put("fonteTexto", Prefs.fonteTexto)
        j.put("raio", Prefs.raio)
        j.put("borda", Prefs.borda)
        j.put("brilho", Prefs.brilho)
        j.put("vidro", Prefs.vidro)
        j.put("preset", Prefs.bgPreset)
        j.put("escuro", Prefs.bgEscuro)
        j.put("blur", Prefs.bgBlur)
        j.put("scanner", Prefs.scanner)
        j.put("particulas", Prefs.particulas)
        j.put("pulso", Prefs.pulso)
        j.put("maiusc", Prefs.maiusc)
        j.put("escala", Prefs.escala)
        return j.toString()
    }

    /** aplica um visual guardado; false = texto estragado (nada muda) */
    fun aplica(s: String): Boolean {
        val j = try { JSONObject(s) } catch (_: Throwable) { return false }
        // o que faltar no texto continua como esta; os valores fora do lugar sao corrigidos pelas proprias Prefs
        Prefs.accent = j.optInt("a1", Prefs.accent)
        Prefs.accent2 = j.optInt("a2", Prefs.accent2)
        Prefs.baseCor = j.optInt("base", Prefs.baseCor)
        Prefs.angulo = j.optInt("ang", Prefs.angulo)
        Prefs.cartao = j.optString("cartao", Prefs.cartao)
        Prefs.fonte = j.optString("fonte", Prefs.fonte)
        Prefs.fonteTexto = j.optString("fonteTexto", Prefs.fonteTexto)
        Prefs.raio = j.optInt("raio", Prefs.raio)
        Prefs.borda = j.optInt("borda", Prefs.borda)
        Prefs.brilho = j.optInt("brilho", Prefs.brilho)
        Prefs.vidro = j.optInt("vidro", Prefs.vidro)
        Prefs.bgPreset = j.optString("preset", Prefs.bgPreset)
        Prefs.bgEscuro = j.optInt("escuro", Prefs.bgEscuro)
        Prefs.bgBlur = j.optInt("blur", Prefs.bgBlur)
        Prefs.scanner = j.optBoolean("scanner", Prefs.scanner)
        Prefs.particulas = j.optBoolean("particulas", Prefs.particulas)
        Prefs.pulso = j.optBoolean("pulso", Prefs.pulso)
        Prefs.maiusc = j.optBoolean("maiusc", Prefs.maiusc)
        Prefs.escala = j.optInt("escala", Prefs.escala)
        Prefs.tema = "custom"
        return true
    }

    /** codigo curto pra copiar e mandar pra alguem */
    fun codigo(): String = PREFIXO + Base64.encodeToString(atual().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** o texto do visual dentro de um codigo colado (aceita espacos e quebras de linha em volta); null = nao e um codigo */
    fun deCodigo(s: String): String? {
        val t = s.trim().replace("\\s".toRegex(), "")
        if (!t.startsWith(PREFIXO)) return null
        return try { String(Base64.decode(t.removePrefix(PREFIXO), Base64.NO_WRAP), Charsets.UTF_8) } catch (_: Throwable) { null }
    }
}
