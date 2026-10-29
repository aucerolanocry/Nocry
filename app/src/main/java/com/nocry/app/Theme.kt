package com.nocry.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/** Cores e fontes do visual (vidro fosco + neon). Tudo vem das preferencias, entao a tela PERSONALIZAR muda tudo. */
object Theme {
    class Cor(val nome: String, val valor: Int)

    /** cores prontas pra escolher (o usuario ainda pode digitar a dele) */
    val PALETA = listOf(
        Cor("Carmesim", 0xFFFF2D55.toInt()),
        Cor("Ciano", 0xFF22E4FF.toInt()),
        Cor("Violeta", 0xFFA66BFF.toInt()),
        Cor("Verde", 0xFF3DDC84.toInt()),
        Cor("Âmbar", 0xFFFFB020.toInt()),
        Cor("Rosa", 0xFFFF5CCB.toInt()),
        Cor("Azul", 0xFF4D7CFF.toInt()),
        Cor("Branco", 0xFFF2F2F6.toInt())
    )

    val accent: Int get() = Prefs.accent

    fun alpha(cor: Int, a: Int): Int = (cor and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    /** mistura b em a (t = 0 fica a, t = 1 fica b), sempre opaco */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        return Color.rgb(r, g, bl)
    }

    /** gira o tom da cor (usado nas manchas da aurora) */
    fun girar(cor: Int, graus: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cor, hsv)
        hsv[0] = (hsv[0] + graus + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    /** fundo base: preto com um fio da cor de destaque */
    val base: Int get() = mix(0xFF060608.toInt(), accent, 0.06f)

    /** cor do texto em cima do botao de destaque (preto se a cor for clara) */
    fun sobre(cor: Int): Int {
        val lum = 0.299 * Color.red(cor) + 0.587 * Color.green(cor) + 0.114 * Color.blue(cor)
        return if (lum > 170) 0xFF0A0A0E.toInt() else 0xFFFFFFFF.toInt()
    }

    // ------------------------------------------------------------------ fontes
    private val cache = HashMap<String, Typeface>()

    /** Space Grotesk (padrao), JetBrains Mono ou a fonte do sistema */
    fun fonte(c: Context, mono: Boolean, negrito: Boolean): Typeface {
        val modo = Prefs.fonte
        if (modo == "sistema") {
            return if (mono) Typeface.create(Typeface.MONOSPACE, if (negrito) Typeface.BOLD else Typeface.NORMAL)
            else if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val arq = if (mono || modo == "mono") "fonts/JetBrainsMono.ttf" else "fonts/SpaceGrotesk.ttf"
        val peso = if (negrito) 700 else 450
        val k = "$arq|$peso"
        synchronized(cache) {
            cache[k]?.let { return it }
            val t = try {
                Typeface.Builder(c.applicationContext.assets, arq).setFontVariationSettings("'wght' $peso").build()
            } catch (_: Throwable) { null }
            val ok = t ?: (if (mono || modo == "mono") Typeface.create(Typeface.MONOSPACE, if (negrito) Typeface.BOLD else Typeface.NORMAL)
                           else if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            cache[k] = ok
            return ok
        }
    }

    fun temMidiaDeFundo(): Boolean = (Prefs.bgTipo == "video" || Prefs.bgTipo == "imagem") && Prefs.bgArquivo != null
}
