package com.nocry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Monta o contorno: retangulo arredondado ou, no estilo HUD, retangulo com 2 cantos cortados. */
object Caminho {
    fun monta(p: Path, l: Float, t: Float, r: Float, b: Float, raio: Float, hud: Boolean, corte: Float) {
        p.reset()
        if (hud) {
            val k = minOf(corte, (r - l) / 3f, (b - t) / 3f)
            p.moveTo(l, t)
            p.lineTo(r - k, t)
            p.lineTo(r, t + k)
            p.lineTo(r, b)
            p.lineTo(l + k, b)
            p.lineTo(l, b - k)
            p.close()
        } else {
            val rr = minOf(raio, (r - l) / 2f, (b - t) / 2f)
            p.addRoundRect(RectF(l, t, r, b), rr, rr, Path.Direction.CW)
        }
    }
}

/**
 * Cartao do NOCRY. Estilos: vidro (translucido com brilho), contorno (neon), solido (luxo) e hud (cantos cortados).
 * A borda e um degrade cor 1 -> cor 2; quando "ligado" ou "forte" o brilho interno acende.
 */
class Painel(
    c: Context,
    private val estilo: String = Prefs.cartao,
    private val forte: Boolean = false,
    private val ligado: Boolean = false,
    raioDp: Int = Prefs.raio,
    private val mascara: Boolean = false
) : Drawable() {
    private val d = c.resources.displayMetrics.density
    private val hud = estilo == "hud"
    private val raio = raioDp * d
    private val corte = 14f * d
    private val path = Path()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private var shBase: Shader? = null
    private var shVidro: Shader? = null
    private var shBorda: Shader? = null
    private var shBordaOn: Shader? = null
    private var shLuz: Shader? = null
    private val inset get() = maxOf(Prefs.borda * d * 0.65f, 1f)

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        val ins = inset
        Caminho.monta(path, b.left + ins, b.top + ins, b.right - ins, b.bottom - ins, raio, hud, corte)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val c1 = Theme.accent
        val c2 = Theme.accent2
        val base = Theme.base
        val k = Prefs.vidro / 62f
        fun a(x: Float) = (x.coerceIn(0f, 0.97f) * 255).toInt()
        val p = Theme.pontos(w, h)
        val x0 = b.left + p[0]; val y0 = b.top + p[1]; val x1 = b.left + p[2]; val y1 = b.top + p[3]
        val tint = if (ligado) 0.20f else if (forte) 0.15f else 0.08f
        shBase = when (estilo) {
            "contorno" -> {
                val fundo = 0xFF060812.toInt()
                LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    Theme.alpha(Theme.mix(fundo, c1, tint + 0.04f), a(0.62f * k)), Theme.alpha(fundo, a(0.62f * k)), Shader.TileMode.CLAMP)
            }
            "solido" -> {
                val al = a(0.55f + 0.42f * ((Prefs.vidro - 20) / 75f))
                LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    Theme.alpha(Theme.mix(Theme.mix(base, 0xFF1B150D.toInt(), 0.5f), c1, tint + 0.04f), al),
                    Theme.alpha(Theme.mix(base, 0xFF000000.toInt(), 0.35f), al), Shader.TileMode.CLAMP)
            }
            "hud" -> {
                val fundo = 0xFF0C0608.toInt()
                LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    Theme.alpha(Theme.mix(fundo, c1, tint + 0.02f), a(0.78f * k)), Theme.alpha(fundo, a(0.78f * k)), Shader.TileMode.CLAMP)
            }
            else -> null
        }
        shVidro = if (estilo == "vidro") LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            Theme.alpha(Color.WHITE, a(0.10f * k)), Theme.alpha(Color.WHITE, a(0.03f * k)), Shader.TileMode.CLAMP) else null
        shBorda = LinearGradient(x0, y0, x1, y1,
            intArrayOf(Theme.alpha(c1, 0xB0), Theme.alpha(Color.WHITE, 0x1C), Theme.alpha(c2, 0x90)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        shBordaOn = LinearGradient(x0, y0, x1, y1,
            intArrayOf(c1, Theme.alpha(c1, 0x66), c2), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        shLuz = LinearGradient(x0, y0, x1, y1, intArrayOf(c1, c2), null, Shader.TileMode.CLAMP)
    }

    override fun draw(cv: Canvas) {
        if (path.isEmpty) return
        val b = bounds
        if (mascara) {
            pFill.shader = null
            pFill.color = Color.WHITE
            cv.drawPath(path, pFill)
            return
        }
        val on = ligado || forte
        // 1) preenchimento
        pFill.shader = null
        if (estilo == "vidro") {
            val k = Prefs.vidro / 62f
            pFill.color = Theme.alpha(0xFF0A0814.toInt(), ((0.46f * k).coerceIn(0f, 0.95f) * 255).toInt())
            cv.drawPath(path, pFill)
            pFill.shader = shVidro
            cv.drawPath(path, pFill)
            if (on) {
                pFill.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    Theme.alpha(Theme.accent, if (ligado) 0x38 else 0x26), Theme.alpha(Theme.accent2, 0x0E), Shader.TileMode.CLAMP)
                cv.drawPath(path, pFill)
            }
        } else {
            pFill.shader = shBase
            cv.drawPath(path, pFill)
        }
        pFill.shader = null

        // 2) linhas de varredura (HUD) e brilho de vidro no topo
        if (hud) {
            cv.save()
            cv.clipPath(path)
            pFill.color = Theme.alpha(Color.WHITE, 8)
            var y = b.top + 2f * d
            while (y < b.bottom) { cv.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + d, pFill); y += 4f * d }
            cv.restore()
        } else if (estilo == "vidro") {
            pLine.shader = null
            pLine.strokeWidth = d
            pLine.color = Theme.alpha(Color.WHITE, 0x26)
            val ins = inset + 1f * d
            cv.drawLine(b.left + raio * 0.7f + ins, b.top + ins + d, b.right - raio * 0.7f - ins, b.top + ins + d, pLine)
        }

        // 3) brilho interno
        val g = Prefs.brilho / 100f
        if (g > 0f && (on || estilo == "contorno")) {
            cv.save()
            cv.clipPath(path)
            pLine.shader = shLuz
            val f = if (on) 1.0f else 0.55f
            for ((w, al) in listOf(14f to 0.07f, 8f to 0.12f, 4f to 0.20f)) {
                pLine.strokeWidth = w * d
                pLine.alpha = ((al * g * f * (if (ligado) 1.5f else 1f)).coerceAtMost(1f) * 255).toInt()
                cv.drawPath(path, pLine)
            }
            cv.restore()
        }

        // 4) borda em degrade
        pLine.shader = if (on) shBordaOn else shBorda
        pLine.alpha = 255
        pLine.strokeWidth = Prefs.borda * d * (if (estilo == "contorno") 1.25f else 1f)
        cv.drawPath(path, pLine)

        // 5) detalhes dos cantos cortados
        if (hud) {
            val ins = inset
            val k = minOf(corte, (b.width() - 2 * ins) / 3f, (b.height() - 2 * ins) / 3f)
            pLine.shader = null
            pLine.color = Theme.accent
            pLine.strokeWidth = Prefs.borda * d * 1.6f
            cv.drawLine(b.right - ins - k, b.top + ins, b.right - ins, b.top + ins + k, pLine)
            pLine.color = Theme.accent2
            cv.drawLine(b.left + ins, b.bottom - ins - k, b.left + ins + k, b.bottom - ins, pLine)
        }
        pLine.shader = null
    }

    override fun getOutline(o: Outline) {
        if (hud) {
            try { o.setConvexPath(path) } catch (_: Throwable) { o.setRect(bounds) }
        } else o.setRoundRect(bounds, raio)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Formas pequenas: botao em degrade, botao fantasma, perigo, campo de texto e caixa de icone.
 * tipos: "grad" | "ghost" | "danger" | "campo" | "ico" | "icoOn" | "trilho"
 */
class Botao(
    c: Context,
    private val tipo: String,
    raioDp: Int = Prefs.raio,
    private val mascara: Boolean = false
) : Drawable() {
    private val d = c.resources.displayMetrics.density
    private val hud = Prefs.cartao == "hud"
    private val raio = minOf(raioDp, 18) * d * (if (tipo == "ico" || tipo == "icoOn") 0.8f else 1f)
    private val corte = (if (tipo == "ico" || tipo == "icoOn") 8f else 12f) * d
    private val path = Path()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var shFill: Shader? = null
    private var shLinha: Shader? = null

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        val ins = 0.5f * d
        Caminho.monta(path, b.left + ins, b.top + ins, b.right - ins, b.bottom - ins, if (tipo == "trilho") b.height() / 2f else raio, hud && tipo != "trilho", corte)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val c1 = Theme.accent
        val c2 = Theme.accent2
        val p = Theme.pontos(w, h)
        val x0 = b.left + p[0]; val y0 = b.top + p[1]; val x1 = b.left + p[2]; val y1 = b.top + p[3]
        shFill = when (tipo) {
            "grad" -> LinearGradient(x0, y0, x1, y1, c1, c2, Shader.TileMode.CLAMP)
            "icoOn" -> LinearGradient(x0, y0, x1, y1, Theme.alpha(c1, 0x60), Theme.alpha(c2, 0x38), Shader.TileMode.CLAMP)
            "trilho" -> LinearGradient(x0, y0, x1, y1, c1, c2, Shader.TileMode.CLAMP)
            else -> null
        }
        shLinha = if (tipo == "icoOn") LinearGradient(x0, y0, x1, y1, Theme.alpha(c1, 0xA0), Theme.alpha(c2, 0x60), Shader.TileMode.CLAMP) else null
    }

    override fun draw(cv: Canvas) {
        if (path.isEmpty) return
        val b = bounds
        if (mascara) {
            pFill.shader = null
            pFill.color = Color.WHITE
            cv.drawPath(path, pFill)
            return
        }
        pFill.shader = shFill
        pFill.color = when (tipo) {
            "ghost" -> Theme.alpha(Color.WHITE, 0x12)
            "danger" -> Theme.alpha(Ui.BAD, 0x24)
            "campo" -> Theme.alpha(Color.BLACK, 0x70)
            "ico" -> Theme.alpha(Color.WHITE, 0x14)
            else -> Color.BLACK
        }
        cv.drawPath(path, pFill)
        pFill.shader = null
        if (tipo == "grad") {
            // brilho de vidro na metade de cima
            cv.save()
            cv.clipPath(path)
            pFill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.55f, Theme.alpha(Color.WHITE, 0x38), Theme.alpha(Color.WHITE, 0), Shader.TileMode.CLAMP)
            cv.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + b.height() * 0.55f, pFill)
            pFill.shader = null
            cv.restore()
        }
        val cor = when (tipo) {
            "ghost" -> Theme.alpha(Color.WHITE, 0x30)
            "danger" -> Theme.alpha(Ui.BAD, 0x88)
            "campo" -> Theme.alpha(Theme.accent, 0x99)
            "ico" -> Theme.alpha(Color.WHITE, 0x1C)
            else -> 0
        }
        if (tipo == "icoOn") {
            pLine.shader = shLinha
            pLine.strokeWidth = d
            cv.drawPath(path, pLine)
            pLine.shader = null
        } else if (cor != 0) {
            pLine.shader = null
            pLine.color = cor
            pLine.strokeWidth = d
            cv.drawPath(path, pLine)
        }
    }

    override fun getOutline(o: Outline) {
        if (hud && tipo != "trilho") {
            try { o.setConvexPath(path) } catch (_: Throwable) { o.setRect(bounds) }
        } else o.setRoundRect(bounds, if (tipo == "trilho") bounds.height() / 2f else raio)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
