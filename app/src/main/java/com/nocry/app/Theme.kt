package com.nocry.app

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Cores, degrade e fontes do visual. Tudo vem das preferencias, entao o ESTUDIO muda tudo. */
object Theme {
    class Cor(val nome: String, val valor: Int)

    /** cores prontas pra escolher (o usuario ainda pode digitar a dele) */
    val PALETA = listOf(
        Cor("Carmesim", 0xFFFF2D55.toInt()),
        Cor("Laranja", 0xFFFF8A1F.toInt()),
        Cor("Âmbar", 0xFFFFB020.toInt()),
        Cor("Ouro", 0xFFF6D27A.toInt()),
        Cor("Verde", 0xFF3DDC84.toInt()),
        Cor("Matrix", 0xFF3DFF7A.toInt()),
        Cor("Ciano", 0xFF22E4FF.toInt()),
        Cor("Azul", 0xFF4D7CFF.toInt()),
        Cor("Violeta", 0xFFA66BFF.toInt()),
        Cor("Rosa", 0xFFFF3CAC.toInt()),
        Cor("Sakura", 0xFFFF8FC7.toInt()),
        Cor("Branco", 0xFFF2F2F6.toInt())
    )

    /** cor 1 do degrade (a principal) */
    val accent: Int get() = Prefs.accent
    /** cor 2 do degrade */
    val accent2: Int get() = Prefs.accent2

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

    /** fundo base do app */
    val base: Int get() = Prefs.baseCor

    /** cor do texto em cima de uma cor de destaque (preto se a cor for clara) */
    fun sobre(cor: Int): Int {
        val lum = 0.299 * Color.red(cor) + 0.587 * Color.green(cor) + 0.114 * Color.blue(cor)
        return if (lum > 165) 0xFF0A0A0E.toInt() else 0xFFFFFFFF.toInt()
    }

    /** cor do texto em cima do degrade inteiro (botao principal) */
    fun sobreGrad(): Int = sobre(mix(accent, accent2, 0.5f))

    /** pontos (x0,y0,x1,y1) de um degrade com o angulo escolhido, cobrindo a area w x h */
    fun pontos(w: Float, h: Float, graus: Int = Prefs.angulo): FloatArray {
        val rad = Math.toRadians(graus.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val meia = (abs(w * dx) + abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        return floatArrayOf(cx - dx * meia, cy - dy * meia, cx + dx * meia, cy + dy * meia)
    }

    /** degrade cor 1 -> cor 2 na area w x h */
    fun grad(w: Float, h: Float, c1: Int = accent, c2: Int = accent2): LinearGradient {
        val p = pontos(maxOf(w, 1f), maxOf(h, 1f))
        return LinearGradient(p[0], p[1], p[2], p[3], c1, c2, Shader.TileMode.CLAMP)
    }

    // ------------------------------------------------------------------ fontes
    private val cache = HashMap<String, Typeface>()

    fun pack(): Pack = Packs.get(Prefs.fonte)

    /** multiplicador de tamanho do texto: escolha do usuario x correcao da fonte */
    fun escalaTexto(): Float = Prefs.escala / 100f * pack().escala

    /** display = titulos, rotulos e botoes; false = texto corrido */
    fun fonte(c: Context, display: Boolean, negrito: Boolean): Typeface = fonteDe(c, pack(), display, negrito)

    /** a mesma coisa, mas de um pacote qualquer (usado pra mostrar cada fonte no ESTUDIO) */
    fun fonteDe(c: Context, p: Pack, display: Boolean, negrito: Boolean): Typeface {
        if (p.sistema) {
            return if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val arq = if (display) (if (negrito) p.dispB else p.disp) else (if (negrito) p.bodyB else p.body)
        val k = "$arq|$negrito"
        synchronized(cache) {
            cache[k]?.let { return it }
            val t = try {
                if (p.variavel) {
                    Typeface.Builder(c.applicationContext.assets, "fonts/$arq").setFontVariationSettings("'wght' ${if (negrito) 700 else 450}").build()
                } else {
                    Typeface.createFromAsset(c.applicationContext.assets, "fonts/$arq")
                }
            } catch (_: Throwable) { null }
            val ok = t ?: (if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            cache[k] = ok
            return ok
        }
    }

    /** fonte de largura fixa (logs): sempre JetBrains Mono */
    fun codigo(c: Context): Typeface {
        synchronized(cache) {
            cache["code"]?.let { return it }
            val t = try {
                Typeface.Builder(c.applicationContext.assets, "fonts/JetBrainsMono.ttf").setFontVariationSettings("'wght' 450").build()
            } catch (_: Throwable) { null }
            val ok = t ?: Typeface.MONOSPACE
            cache["code"] = ok
            return ok
        }
    }

    fun temMidiaDeFundo(): Boolean = (Prefs.bgTipo == "video" || Prefs.bgTipo == "imagem") && Prefs.bgArquivo != null
}
