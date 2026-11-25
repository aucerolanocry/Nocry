package com.nocry.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** cores do top 1 */
object Ouro {
    const val CLARO = 0xFFFFE9A6.toInt()
    const val MEIO = 0xFFFFD54A.toInt()
    const val FUNDO = 0xFFFFA000.toInt()
}

/** anel dourado girando em volta da foto, com 3 brilhos orbitando */
class AnelGiratorio @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = c.resources.displayMetrics.density
    private val pAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.alpha(Ouro.MEIO, 0x50) }
    private val pBrilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var sh: SweepGradient? = null
    var espessura = 3f
    var periodo = 2200L

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sh = SweepGradient(w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Theme.alpha(Ouro.FUNDO, 0x80), Ouro.MEIO, Color.WHITE, Ouro.MEIO, Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.18f, 0.32f, 0.4f, 0.48f, 0.62f, 1f))
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f - espessura * d * 2.2f
        val t = (SystemClock.uptimeMillis() % periodo) / periodo.toFloat()
        val ang = t * 360f
        pBase.strokeWidth = espessura * d * 0.5f
        cv.drawCircle(cx, cy, r, pBase)
        m.setRotate(ang, cx, cy)
        sh?.setLocalMatrix(m)
        pAnel.shader = sh
        // brilho largo por baixo + linha firme por cima
        pAnel.strokeWidth = espessura * d * 2.6f
        pAnel.alpha = 0x55
        cv.drawCircle(cx, cy, r, pAnel)
        pAnel.strokeWidth = espessura * d
        pAnel.alpha = 0xFF
        cv.drawCircle(cx, cy, r, pAnel)
        // 3 brilhos orbitando no sentido contrário
        for (i in 0 until 3) {
            val a = Math.toRadians((-ang * 1.4f + i * 120f).toDouble())
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            pBrilho.shader = RadialGradient(x, y, 7f * d, intArrayOf(Color.WHITE, Theme.alpha(Ouro.MEIO, 0x90), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, 7f * d, pBrilho)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** uma luz dourada correndo pela borda do cartão */
class BordaViva @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = c.resources.displayMetrics.density
    private val path = Path()
    private val seg = Path()
    private var pm: PathMeasure? = null
    private var tam = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pLinha = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.alpha(Ouro.MEIO, 0x40) }
    var raioDp = Prefs.raio.toFloat()

    init { isClickable = false; isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ins = 1.5f * d
        path.reset()
        val r = min(raioDp * d, min(w, h) / 2f)
        path.addRoundRect(RectF(ins, ins, w - ins, h - ins), r, r, Path.Direction.CW)
        pm = PathMeasure(path, true).also { tam = it.length }
    }

    private fun trecho(ini: Float, fim: Float, larg: Float, cor: Int, alfa: Int) {
        val m = pm ?: return
        p.strokeWidth = larg
        p.color = cor
        p.alpha = alfa
        var a = ini
        var b = fim
        if (a < 0) { seg.reset(); m.getSegment(tam + a, tam, seg, true); canvas?.drawPath(seg, p); a = 0f }
        if (b > tam) { seg.reset(); m.getSegment(0f, b - tam, seg, true); canvas?.drawPath(seg, p); b = tam }
        seg.reset(); m.getSegment(a, b, seg, true); canvas?.drawPath(seg, p)
    }

    private var canvas: Canvas? = null

    override fun onDraw(cv: Canvas) {
        if (tam <= 0f) return
        canvas = cv
        pLinha.strokeWidth = 1.2f * d
        cv.drawPath(path, pLinha)
        val t = (SystemClock.uptimeMillis() % 3200L) / 3200f
        // duas luzes, uma de cada lado
        for (k in 0 until 2) {
            val pos = ((t + k * 0.5f) % 1f) * tam
            val comp = tam * 0.16f
            trecho(pos - comp, pos, 9f * d, Ouro.FUNDO, 0x30)
            trecho(pos - comp * 0.8f, pos, 4.5f * d, Ouro.MEIO, 0x90)
            trecho(pos - comp * 0.45f, pos, 2f * d, Color.WHITE, 0xFF)
        }
        canvas = null
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** texto com um brilho passando de um lado pro outro (o nome do top 1) */
class TextoBrilho(c: Context) : TextView(c) {
    private val m = Matrix()
    private var sh: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sh = LinearGradient(0f, 0f, w.toFloat().coerceAtLeast(1f), 0f,
            intArrayOf(Ouro.MEIO, Ouro.MEIO, Color.WHITE, Ouro.CLARO, Ouro.MEIO, Ouro.MEIO),
            floatArrayOf(0f, 0.35f, 0.48f, 0.52f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val t = (SystemClock.uptimeMillis() % 2600L) / 2600f
        m.setTranslate((t * 2.2f - 1.1f) * w, 0f)
        sh?.setLocalMatrix(m)
        paint.shader = sh
        super.onDraw(cv)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** fundo da entrada do top 1: escurece, raios girando, brilho no meio e confete dourado caindo */
private class FundoTop1(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val inicio = SystemClock.uptimeMillis()
    private val pRaio = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLuz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pConf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val raio = Path()

    private class Conf(var x: Float, var y: Float, val vy: Float, val vx: Float, val giro: Float, val tam: Float, val cor: Int, var ang: Float)
    private val confetes = ArrayList<Conf>()
    private var ultimo = 0L

    private fun nasce(w: Float, topo: Boolean): Conf {
        val cores = intArrayOf(Ouro.MEIO, Ouro.CLARO, Ouro.FUNDO, Color.WHITE)
        return Conf(Random.nextFloat() * w, if (topo) -Random.nextFloat() * 200f * d else Random.nextFloat() * height,
            (120f + Random.nextFloat() * 220f) * d, (Random.nextFloat() - 0.5f) * 60f * d, (Random.nextFloat() - 0.5f) * 540f,
            (3f + Random.nextFloat() * 4f) * d, cores[Random.nextInt(cores.size)], Random.nextFloat() * 360f)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val agora = SystemClock.uptimeMillis()
        val ms = agora - inicio
        val dt = if (ultimo == 0L) 0f else (agora - ultimo) / 1000f
        ultimo = agora
        val cx = w / 2f; val cy = h * 0.4f
        // fundo escuro
        cv.drawColor(Theme.alpha(Color.BLACK, (min(1f, ms / 350f) * 0xE6).toInt()))
        // raios girando
        val giro = ms / 30f
        val alcance = maxOf(w, h)
        for (i in 0 until 14) {
            val a0 = Math.toRadians((giro + i * 360f / 14).toDouble())
            val a1 = Math.toRadians((giro + i * 360f / 14 + 9f).toDouble())
            raio.reset()
            raio.moveTo(cx, cy)
            raio.lineTo(cx + (alcance * cos(a0)).toFloat(), cy + (alcance * sin(a0)).toFloat())
            raio.lineTo(cx + (alcance * cos(a1)).toFloat(), cy + (alcance * sin(a1)).toFloat())
            raio.close()
            pRaio.shader = RadialGradient(cx, cy, alcance * 0.75f, intArrayOf(Theme.alpha(Ouro.MEIO, 0x46), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            cv.drawPath(raio, pRaio)
        }
        // brilho no meio, pulsando
        val pulso = 1f + 0.08f * sin(ms / 260.0).toFloat()
        pLuz.shader = RadialGradient(cx, cy, 170f * d * pulso, intArrayOf(Theme.alpha(Ouro.MEIO, 0x70), Theme.alpha(Ouro.FUNDO, 0x20), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, 170f * d * pulso, pLuz)
        // confete
        if (confetes.isEmpty()) repeat(70) { confetes.add(nasce(w, false)) }
        for (k in confetes.indices) {
            val f = confetes[k]
            f.y += f.vy * dt; f.x += f.vx * dt; f.ang += f.giro * dt
            if (f.y > h + 20f * d) confetes[k] = nasce(w, true)
            pConf.color = f.cor
            cv.save()
            cv.rotate(f.ang, f.x, f.y)
            cv.drawRect(f.x - f.tam / 2f, f.y - f.tam, f.x + f.tam / 2f, f.y + f.tam, pConf)
            cv.restore()
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/**
 * Entrada do top 1 (o dono): ao abrir o rank, a tela escurece, raios e confete dourado,
 * a foto entra girando com o anel, "TOP 1" e o nome brilhando. Toque pra pular; some sozinha.
 */
object IntroTop1 {
    fun mostrar(act: Activity, foto: Bitmap?, nome: String, frase: String) {
        val raiz = act.window?.decorView as? ViewGroup ?: return
        val c = act
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val camada = FrameLayout(c)
        camada.isClickable = true
        camada.addView(FundoTop1(c), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        // foto + anel
        val caixa = FrameLayout(c)
        val anel = AnelGiratorio(c).also { it.espessura = 4f; it.periodo = 1600L }
        caixa.addView(anel, FrameLayout.LayoutParams(dp(220), dp(220)))
        val fv = FotoPerfil(c)
        fv.fixa = foto
        caixa.addView(fv, FrameLayout.LayoutParams(dp(168), dp(168), Gravity.CENTER))
        col.addView(caixa, LinearLayout.LayoutParams(dp(220), dp(220)))

        // coroa + TOP 1
        val linha = LinearLayout(c)
        linha.gravity = Gravity.CENTER
        linha.addView(Ui.icone(c, R.drawable.ic_crown, Ouro.MEIO, 34), LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(10) })
        val top = TextoBrilho(c)
        top.text = "TOP 1"
        top.textSize = 46f
        top.typeface = Ui.text(c, "x", 46f, Color.WHITE, true).typeface
        top.letterSpacing = 0.12f
        top.setShadowLayer(18f, 0f, 0f, Theme.alpha(Ouro.FUNDO, 0xC0))
        linha.addView(top)
        col.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) })

        val nm = TextoBrilho(c)
        nm.text = nome.uppercase()
        nm.textSize = 30f
        nm.typeface = top.typeface
        nm.letterSpacing = 0.06f
        nm.gravity = Gravity.CENTER
        nm.setShadowLayer(14f, 0f, 0f, Theme.alpha(Color.BLACK, 0xC0))
        col.addView(nm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) })

        val selo = Ui.selo(c, "DONO DO NOCRY", Ouro.MEIO, false)
        col.addView(selo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })

        val fr = Ui.text(c, frase.ifBlank { "criador do NOCRY" }, 13f, Color.WHITE, false)
        fr.gravity = Gravity.CENTER
        fr.alpha = 0.85f
        col.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })

        val pular = Ui.text(c, "toque pra continuar", 10f, Theme.alpha(Color.WHITE, 0x99), true, mono = true)
        col.addView(pular, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(28) })

        camada.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { it.bottomMargin = dp(60) })
        raiz.addView(camada, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // entrada: a foto cresce girando, depois o texto sobe um por um
        caixa.scaleX = 0.15f; caixa.scaleY = 0.15f; caixa.alpha = 0f; caixa.rotation = -200f
        caixa.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(900).setInterpolator(OvershootInterpolator(1.4f)).start()
        listOf(linha, nm, selo, fr, pular).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = 40f * d
            v.animate().alpha(if (v === pular) 0.8f else 1f).translationY(0f).setStartDelay(550L + i * 170L).setDuration(520).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        linha.scaleX = 2.2f; linha.scaleY = 2.2f
        linha.animate().scaleX(1f).scaleY(1f).setStartDelay(550).setDuration(520).setInterpolator(OvershootInterpolator(2f)).start()
        camada.postDelayed({ try { camada.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {} }, 600)

        var saiu = false
        val sair = {
            if (!saiu) {
                saiu = true
                camada.animate().alpha(0f).setStartDelay(0).setDuration(450).withEndAction { try { raiz.removeView(camada) } catch (_: Throwable) {} }.start()
            }
        }
        camada.setOnClickListener { sair() }
        camada.postDelayed({ sair() }, 4300)
    }
}

/** o pulsar suave do cartão do top 1 */
fun pulsar(v: View) {
    val a = ValueAnimator.ofFloat(1f, 1.015f)
    a.duration = 1400
    a.repeatMode = ValueAnimator.REVERSE
    a.repeatCount = ValueAnimator.INFINITE
    a.addUpdateListener { v.scaleX = it.animatedValue as Float; v.scaleY = it.animatedValue as Float }
    v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(x: View) { if (!a.isStarted) a.start() }
        override fun onViewDetachedFromWindow(x: View) { a.cancel() }
    })
}
