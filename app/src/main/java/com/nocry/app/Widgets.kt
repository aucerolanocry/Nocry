package com.nocry.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/** Anel de progresso da tela inicial: arco em degrade com brilho que pulsa. */
class RingView(c: Context) : View(c) {
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var mostrado = 0f
    private var alvo = 0f
    private var anim: ValueAnimator? = null
    private var pulso: ValueAnimator? = null
    private var luz = 1f

    init {
        for (p in listOf(trilho, brilho, arco)) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
        }
    }

    /** progresso de 0 a 1 */
    fun define(p: Float) {
        val novo = p.coerceIn(0f, 1f)
        if (novo == alvo) { invalidate(); return }
        alvo = novo
        anim?.cancel()
        if (!Prefs.anim) { mostrado = alvo; invalidate(); return }
        val a = ValueAnimator.ofFloat(mostrado, alvo)
        a.duration = 750
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { mostrado = it.animatedValue as Float; invalidate() }
        anim = a
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Prefs.pulso && Prefs.anim && Prefs.brilho > 0) {
            val p = ValueAnimator.ofFloat(0.55f, 1f)
            p.duration = 1700
            p.repeatMode = ValueAnimator.REVERSE
            p.repeatCount = ValueAnimator.INFINITE
            p.addUpdateListener { luz = it.animatedValue as Float; invalidate() }
            pulso = p
            p.start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        pulso?.cancel()
        pulso = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val esp = 9f * d
        val m = esp * 1.6f
        r.set(m, m, w - m, h - m)
        trilho.strokeWidth = esp
        trilho.color = Theme.alpha(Color.WHITE, 0x18)
        cv.drawArc(r, -90f, 360f, false, trilho)
        val varre = 360f * mostrado
        if (varre < 0.5f) return
        val sh = SweepGradient(w / 2f, h / 2f, intArrayOf(Theme.accent, Theme.accent2, Theme.accent), floatArrayOf(0f, 0.7f, 1f))
        val mt = Matrix()
        mt.postRotate(-90f, w / 2f, h / 2f)
        sh.setLocalMatrix(mt)
        val g = Prefs.brilho / 100f
        if (g > 0f) {
            brilho.shader = sh
            brilho.strokeWidth = esp * 2.4f
            brilho.alpha = (0x40 * g * luz).toInt().coerceIn(0, 255)
            cv.drawArc(r, -90f, varre, false, brilho)
            brilho.strokeWidth = esp * 1.6f
            brilho.alpha = (0x60 * g * luz).toInt().coerceIn(0, 255)
            cv.drawArc(r, -90f, varre, false, brilho)
        }
        arco.shader = sh
        arco.strokeWidth = esp
        cv.drawArc(r, -90f, varre, false, arco)
    }
}

/** Interruptor em degrade (liga/desliga com animacao). */
class Chave(c: Context, ligado: Boolean, private val aoMudar: (Boolean) -> Unit) : View(c) {
    var ligado = ligado
        private set
    private var t = if (ligado) 1f else 0f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener {
            Ui.toca(this)
            muda(!this.ligado, true)
        }
    }

    fun muda(v: Boolean, avisa: Boolean) {
        if (v == ligado) return
        ligado = v
        anim?.cancel()
        if (Prefs.anim) {
            val a = ValueAnimator.ofFloat(t, if (v) 1f else 0f)
            a.duration = 160
            a.addUpdateListener { t = it.animatedValue as Float; invalidate() }
            anim = a
            a.start()
        } else { t = if (v) 1f else 0f; invalidate() }
        if (avisa) aoMudar(v)
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension((46 * d).toInt(), (28 * d).toInt()) }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rr.set(0f, 0f, w, h)
        p.shader = null
        p.color = Theme.alpha(Color.WHITE, 0x26)
        cv.drawRoundRect(rr, h / 2f, h / 2f, p)
        if (t > 0f) {
            p.shader = Theme.grad(w, h)
            p.alpha = (255 * t).toInt()
            cv.drawRoundRect(rr, h / 2f, h / 2f, p)
            p.shader = null
        }
        val raio = h / 2f - 4f * d
        val x = h / 2f + (w - h) * t
        if (t > 0.3f && Prefs.brilho > 0) {
            p.color = Theme.alpha(Theme.accent, (0x55 * t * Prefs.brilho / 100f).toInt())
            cv.drawCircle(x, h / 2f, raio + 4f * d, p)
        }
        p.color = Theme.mix(0xFFC9C9D4.toInt(), Color.WHITE, t)
        cv.drawCircle(x, h / 2f, raio, p)
    }
}

/** Miniatura de um tema ou de um fundo animado (so desenho, serve de botao de escolha). */
class Miniatura private constructor(c: Context, private val tema: Tema?, private val fundo: String?, private val sel: Boolean) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    companion object {
        fun tema(c: Context, t: Tema, sel: Boolean) = Miniatura(c, t, null, sel)
        fun fundo(c: Context, id: String, sel: Boolean) = Miniatura(c, null, id, sel)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val raio = 12f * d
        val ac = tema?.c1 ?: Theme.accent
        val ac2 = tema?.c2 ?: Theme.accent2
        val base = tema?.base ?: Theme.base
        cv.save()
        path.reset()
        path.addRoundRect(RectF(0f, 0f, w, h), raio, raio, Path.Direction.CW)
        cv.clipPath(path)
        p.shader = null
        p.color = base
        cv.drawRect(0f, 0f, w, h, p)
        val tipo = tema?.fundo ?: fundo ?: "noite"
        pinta(cv, tipo, w, h, ac, ac2, base)
        if (tema != null) {
            p.shader = LinearGradient(0f, 0f, w, h, ac, ac2, Shader.TileMode.CLAMP)
            cv.drawCircle(w * 0.3f, h * 0.30f, w * 0.17f, p)
            val rb = RectF(w * 0.12f, h * 0.72f, w * 0.88f, h * 0.88f)
            cv.drawRoundRect(rb, 7f * d, 7f * d, p)
            p.shader = null
        }
        cv.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (sel) 2.5f else 1f) * d
        p.color = if (sel) ac else Theme.alpha(Color.WHITE, 0x30)
        val i = p.strokeWidth / 2f
        cv.drawRoundRect(RectF(i, i, w - i, h - i), raio, raio, p)
        p.style = Paint.Style.FILL
        if (sel && tema != null) {
            p.color = ac
            cv.drawCircle(w - 14f * d, 14f * d, 8f * d, p)
            p.color = Theme.sobre(ac)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.8f * d
            p.strokeCap = Paint.Cap.ROUND
            val ck = Path()
            ck.moveTo(w - 17.5f * d, 14.2f * d)
            ck.lineTo(w - 14.8f * d, 16.8f * d)
            ck.lineTo(w - 10.5f * d, 11.5f * d)
            cv.drawPath(ck, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun pinta(cv: Canvas, tipo: String, w: Float, h: Float, ac: Int, ac2: Int, base: Int) {
        when (tipo) {
            "brasas" -> {
                p.shader = RadialGradient(w / 2f, h * 1.1f, max(w, h) * 0.8f, Theme.alpha(ac, 0xCC), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(ac2, 0xDD)
                for (i in 0 until 7) cv.drawCircle(w * (0.15f + 0.12f * i), h * (0.9f - 0.11f * ((i * 5) % 7)), 1.4f * d, p)
            }
            "grade" -> {
                p.shader = LinearGradient(0f, h * 0.4f, 0f, h, Theme.alpha(ac, 0), Theme.alpha(ac, 0x50), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(ac, 0x90)
                p.strokeWidth = d
                for (i in 0..6) cv.drawLine(w / 2f + (i - 3) * w * 0.28f, h * 0.55f, w / 2f + (i - 3) * w * 0.7f, h, p)
                for (i in 0..3) { val y = h * (0.55f + 0.45f * (i / 3f) * (i / 3f)); cv.drawLine(0f, y, w, y, p) }
            }
            "aurora" -> {
                p.shader = RadialGradient(w * 0.25f, h * 0.30f, w * 0.7f, Theme.alpha(ac, 0xA0), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = RadialGradient(w * 0.8f, h * 0.7f, w * 0.7f, Theme.alpha(ac2, 0xA0), Theme.alpha(ac2, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
            }
            "sol" -> {
                p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, Theme.alpha(ac, 0x10), Theme.alpha(ac, 0xD0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h * 0.62f, p)
                p.shader = LinearGradient(0f, h * 0.2f, 0f, h * 0.62f, ac2, ac, Shader.TileMode.CLAMP)
                cv.drawCircle(w / 2f, h * 0.42f, w * 0.22f, p)
                p.shader = null
                p.color = base
                cv.drawRect(0f, h * 0.62f, w, h, p)
                p.color = Theme.alpha(ac, 0xA0)
                p.strokeWidth = d
                for (i in 0..4) cv.drawLine(w / 2f + (i - 2) * w * 0.12f, h * 0.62f, w / 2f + (i - 2) * w * 0.5f, h, p)
            }
            "chuva" -> {
                p.color = Theme.alpha(ac, 0xB0)
                p.strokeWidth = 1.6f * d
                for (i in 0..8) { val x = w * (0.08f + 0.105f * i); val a = h * (0.2f + 0.5f * (((i * 37) % 10) / 10f)); cv.drawLine(x, a, x, a + h * 0.3f, p) }
            }
            "neve" -> {
                p.shader = RadialGradient(w / 2f, 0f, w, Theme.alpha(ac2, 0x80), Theme.alpha(ac2, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(Color.WHITE, 0xD0)
                for (i in 0 until 12) cv.drawCircle(w * (((i * 53) % 100) / 100f), h * (((i * 31) % 100) / 100f), 1.5f * d, p)
            }
            "petalas" -> {
                p.shader = RadialGradient(w / 2f, h, w, Theme.alpha(ac, 0xA0), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(ac, 0xE0)
                for (i in 0 until 7) cv.drawOval(RectF(w * (0.1f + 0.12f * i), h * (0.1f + 0.1f * ((i * 3) % 7)), w * (0.1f + 0.12f * i) + 6f * d, h * (0.1f + 0.1f * ((i * 3) % 7)) + 4f * d), p)
            }
            "ouro" -> {
                p.shader = RadialGradient(w * 0.9f, 0f, w, Theme.alpha(ac, 0x90), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(ac, 0xE0)
                for (i in 0 until 8) cv.drawCircle(w * (((i * 47) % 100) / 100f), h * (0.3f + 0.6f * (((i * 29) % 100) / 100f)), 1.4f * d, p)
            }
            else -> {
                p.shader = RadialGradient(w * 0.8f, h * 0.1f, w, Theme.alpha(ac, 0x60), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                p.color = Theme.alpha(Color.WHITE, 0xB0)
                for (i in 0 until 10) cv.drawCircle(w * (((i * 61) % 100) / 100f), h * (((i * 37) % 100) / 100f), 1.1f * d, p)
            }
        }
        p.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e)
}
