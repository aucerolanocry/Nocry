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

/**
 * Linha do tempo da entrada (ms). Tudo — som, tremida, flash, onda e o texto — sai daqui,
 * então fica sincronizado.
 */
private object Tempo {
    const val BOOM = 950L        // a explosão: flash branco, onda de choque, a foto aparece
    const val CARIMBO = 1500L    // "TOP 1" bate na tela
    const val NOME = 1950L       // o nome aparece (com um brilho subindo)
    const val FIM = 5600L        // começa a sumir
}

/** som da entrada, gerado no próprio celular (nada de música de fora): ronco subindo, explosão e o carimbo */
private object SomTop1 {
    private const val TAXA = 44100
    @Volatile private var faixa: android.media.AudioTrack? = null

    private fun gerar(): ShortArray {
        val total = (TAXA * 3.2).toInt()
        val b = FloatArray(total)
        val rnd = Random(7)
        var ruido = 0f
        fun env(t: Double, ini: Double, ataque: Double, queda: Double): Double =
            if (t < ini) 0.0 else if (t < ini + ataque) (t - ini) / ataque else Math.exp(-(t - ini - ataque) / queda)
        for (i in 0 until total) {
            val t = i / TAXA.toDouble()
            // ronco subindo até a explosão
            val sobe = (t / (Tempo.BOOM / 1000.0)).coerceIn(0.0, 1.0)
            var s = if (t < Tempo.BOOM / 1000.0) sobe * sobe * 0.35 * Math.sin(2 * Math.PI * (38 + 40 * sobe) * t) else 0.0
            ruido = 0.96f * ruido + 0.04f * (rnd.nextFloat() * 2f - 1f)
            if (t < Tempo.BOOM / 1000.0) s += sobe * sobe * sobe * 0.9 * ruido
            // explosão: grave caindo + estalo de ruído
            val tb = t - Tempo.BOOM / 1000.0
            if (tb >= 0) {
                val f = 70 * Math.exp(-tb * 3) + 32
                s += 0.95 * env(t, Tempo.BOOM / 1000.0, 0.004, 0.55) * Math.sin(2 * Math.PI * f * tb)
                s += 0.7 * env(t, Tempo.BOOM / 1000.0, 0.002, 0.09) * (rnd.nextFloat() * 2 - 1)
                s += 0.35 * env(t, Tempo.BOOM / 1000.0, 0.01, 0.6) * ruido * 3
            }
            // carimbo do TOP 1: batida seca + brilho agudo
            val tc = t - Tempo.CARIMBO / 1000.0
            if (tc >= 0) {
                s += 0.7 * env(t, Tempo.CARIMBO / 1000.0, 0.002, 0.16) * Math.sin(2 * Math.PI * (120 * Math.exp(-tc * 8) + 55) * tc)
                s += 0.12 * env(t, Tempo.CARIMBO / 1000.0, 0.01, 0.9) * (Math.sin(2 * Math.PI * 1318.5 * tc) + 0.6 * Math.sin(2 * Math.PI * 1975.5 * tc))
            }
            // nome: brilho subindo (arpejo rapidinho)
            val tn = t - Tempo.NOME / 1000.0
            if (tn >= 0) {
                val nota = doubleArrayOf(987.8, 1318.5, 1760.0, 2637.0)
                val k = (tn / 0.07).toInt()
                if (k < nota.size) s += 0.07 * Math.exp(-(tn - k * 0.07) * 18) * Math.sin(2 * Math.PI * nota[k] * tn)
                else s += 0.06 * Math.exp(-(tn - 0.28) * 2.5) * Math.sin(2 * Math.PI * 2637.0 * tn)
            }
            b[i] = s.toFloat()
        }
        // normaliza sem estourar
        var pico = 0.01f
        for (x in b) pico = maxOf(pico, kotlin.math.abs(x))
        return ShortArray(total) { (b[it] / pico * 0.92f * Short.MAX_VALUE).toInt().toShort() }
    }

    @Volatile private var cache: ShortArray? = null

    /** gera o som uma vez (fica guardado); chame fora da tela principal */
    fun preparar() { if (cache == null) cache = try { gerar() } catch (_: Throwable) { null } }

    /** toca na hora (o som já tem que estar pronto, senão fica sem som pra não atrasar) */
    fun tocar() {
        parar()
        val pcm = cache ?: return
        Bg.io {
            try {
                val at = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TAXA).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2).build()
                at.write(pcm, 0, pcm.size)
                faixa = at
                at.play()
            } catch (_: Throwable) {}
        }
    }

    fun parar() {
        val f = faixa ?: return
        faixa = null
        try { f.stop() } catch (_: Throwable) {}
        try { f.release() } catch (_: Throwable) {}
    }
}

/**
 * Fundo da entrada: até a explosão, escuro com faíscas sendo puxadas pro meio; na explosão,
 * flash branco, onda de choque, estilhaços voando, depois raios girando e confete dourado.
 */
private class FundoTop1(c: Context, private val inicio: Long) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val pRaio = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLuz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pPart = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pOnda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val raio = Path()

    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, val giro: Float, val tam: Float, val cor: Int, var ang: Float)
    private val puxadas = ArrayList<P>()
    private val estilhacos = ArrayList<P>()
    private val confetes = ArrayList<P>()
    private var ultimo = 0L
    private var explodiu = false
    private val cores = intArrayOf(Ouro.MEIO, Ouro.CLARO, Ouro.FUNDO, Color.WHITE)

    private fun confete(w: Float, h: Float, topo: Boolean) = P(Random.nextFloat() * w, if (topo) -Random.nextFloat() * 200f * d else Random.nextFloat() * h * 0.3f - h * 0.3f,
        (Random.nextFloat() - 0.5f) * 60f * d, (120f + Random.nextFloat() * 220f) * d, (Random.nextFloat() - 0.5f) * 540f,
        (3f + Random.nextFloat() * 4f) * d, cores[Random.nextInt(cores.size)], Random.nextFloat() * 360f)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val agora = SystemClock.uptimeMillis()
        val ms = agora - inicio
        val dt = if (ultimo == 0L) 0f else ((agora - ultimo) / 1000f).coerceAtMost(0.05f)
        ultimo = agora
        val cx = w / 2f; val cy = h * 0.4f
        cv.drawColor(Theme.alpha(Color.BLACK, (min(1f, ms / 300f) * 0xEE).toInt()))

        if (ms < Tempo.BOOM) {
            // antes da explosão: faíscas sendo sugadas pro centro, cada vez mais rápido
            val forca = ms / Tempo.BOOM.toFloat()
            if (puxadas.isEmpty()) repeat(90) {
                val a = Random.nextFloat() * 6.283f
                val r = (0.45f + Random.nextFloat() * 0.6f) * maxOf(w, h)
                puxadas.add(P(cx + r * cos(a), cy + r * sin(a), 0f, 0f, 0f, (1.5f + Random.nextFloat() * 2f) * d, cores[Random.nextInt(cores.size)], 0f))
            }
            for (p in puxadas) {
                val dx = cx - p.x; val dy = cy - p.y
                val k = (0.6f + forca * forca * 5f) * dt
                p.x += dx * k; p.y += dy * k
                pPart.color = p.cor
                pPart.alpha = (120 + 135 * forca).toInt().coerceAtMost(255)
                cv.drawLine(p.x, p.y, p.x - dx * 0.06f, p.y - dy * 0.06f, pPart.also { it.strokeWidth = p.tam })
            }
            // núcleo de luz crescendo
            val rn = (12f + 60f * forca * forca) * d
            pLuz.shader = RadialGradient(cx, cy, rn, intArrayOf(Color.WHITE, Theme.alpha(Ouro.MEIO, 0xA0), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, rn, pLuz)
        } else {
            val tb = ms - Tempo.BOOM
            if (!explodiu) {
                explodiu = true
                repeat(80) {
                    val a = Random.nextFloat() * 6.283f
                    val v = (300f + Random.nextFloat() * 900f) * d
                    estilhacos.add(P(cx, cy, v * cos(a), v * sin(a), (Random.nextFloat() - 0.5f) * 900f, (2f + Random.nextFloat() * 5f) * d, cores[Random.nextInt(cores.size)], Random.nextFloat() * 360f))
                }
                repeat(70) { confetes.add(confete(w, h, false)) }
            }
            // raios girando (entram junto com a explosão)
            val giro = ms / 26f
            val alcance = maxOf(w, h)
            val forcaRaio = min(1f, tb / 400f)
            for (i in 0 until 16) {
                val a0 = Math.toRadians((giro + i * 360f / 16).toDouble())
                val a1 = Math.toRadians((giro + i * 360f / 16 + 8f).toDouble())
                raio.reset()
                raio.moveTo(cx, cy)
                raio.lineTo(cx + (alcance * cos(a0)).toFloat(), cy + (alcance * sin(a0)).toFloat())
                raio.lineTo(cx + (alcance * cos(a1)).toFloat(), cy + (alcance * sin(a1)).toFloat())
                raio.close()
                pRaio.shader = RadialGradient(cx, cy, alcance * 0.75f, intArrayOf(Theme.alpha(Ouro.MEIO, (0x50 * forcaRaio).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                cv.drawPath(raio, pRaio)
            }
            // brilho no meio pulsando
            val pulso = 1f + 0.08f * sin(ms / 240.0).toFloat()
            pLuz.shader = RadialGradient(cx, cy, 175f * d * pulso, intArrayOf(Theme.alpha(Ouro.MEIO, 0x78), Theme.alpha(Ouro.FUNDO, 0x22), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, 175f * d * pulso, pLuz)
            // ondas de choque (a da explosão e a do carimbo)
            fun onda(desde: Long, dur: Float, maxR: Float, larg: Float) {
                val t = (ms - desde) / dur
                if (t < 0f || t > 1f) return
                val e = 1f - (1f - t) * (1f - t)
                pOnda.strokeWidth = larg * d * (1f - t) + 1f
                pOnda.color = Color.WHITE
                pOnda.alpha = ((1f - t) * 255).toInt()
                cv.drawCircle(cx, cy, maxR * e, pOnda)
                pOnda.color = Ouro.MEIO
                pOnda.alpha = ((1f - t) * 140).toInt()
                pOnda.strokeWidth = larg * 3f * d * (1f - t) + 1f
                cv.drawCircle(cx, cy, maxR * e * 0.94f, pOnda)
            }
            onda(Tempo.BOOM, 700f, maxOf(w, h), 10f)
            onda(Tempo.BOOM + 120, 900f, maxOf(w, h) * 0.8f, 5f)
            onda(Tempo.CARIMBO, 520f, w * 0.75f, 6f)
            // estilhaços voando pra fora
            for (p in estilhacos) {
                p.x += p.vx * dt; p.y += p.vy * dt
                p.vx *= 0.985f; p.vy = p.vy * 0.985f + 300f * d * dt
                p.ang += p.giro * dt
                if (p.y > h + 40f * d) continue
                pPart.color = p.cor
                pPart.alpha = (255 * (1f - (tb / 2200f)).coerceIn(0f, 1f)).toInt()
                cv.save(); cv.rotate(p.ang, p.x, p.y)
                cv.drawRect(p.x - p.tam, p.y - p.tam / 2.5f, p.x + p.tam, p.y + p.tam / 2.5f, pPart)
                cv.restore()
            }
            // confete caindo
            for (k in confetes.indices) {
                val f = confetes[k]
                f.y += f.vy * dt; f.x += f.vx * dt; f.ang += f.giro * dt
                if (f.y > h + 20f * d) confetes[k] = confete(w, h, true)
                pPart.color = f.cor
                pPart.alpha = 255
                cv.save(); cv.rotate(f.ang, f.x, f.y)
                cv.drawRect(f.x - f.tam / 2f, f.y - f.tam, f.x + f.tam / 2f, f.y + f.tam, pPart)
                cv.restore()
            }
            // flash branco da explosão e um menor no carimbo
            fun flash(desde: Long, dur: Float, forca: Int) {
                val t = (ms - desde) / dur
                if (t in 0f..1f) cv.drawColor(Theme.alpha(Color.WHITE, ((1f - t) * (1f - t) * forca).toInt()))
            }
            flash(Tempo.BOOM, 420f, 255)
            flash(Tempo.CARIMBO, 220f, 120)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/**
 * Entrada do top 1 (o dono): tela escurece e puxa faíscas pro meio (som subindo) → EXPLOSÃO
 * (flash, onda de choque, tremida, vibração) com a foto aparecendo → "TOP 1" carimba na tela →
 * o nome sobe brilhando. Tudo na mesma linha do tempo do som. Toque pra pular.
 */
object IntroTop1 {
    @Volatile private var abrindo = false

    /** deixa o som pronto antes (chamado quando o rank abre) */
    fun preparar() = SomTop1.preparar()

    /** prepara o som e só então começa: assim som, explosão e texto saem juntos */
    fun mostrar(act: Activity, foto: Bitmap?, nome: String, frase: String) {
        if (abrindo) return
        abrindo = true
        Bg.io {
            SomTop1.preparar()
            Bg.ui {
                abrindo = false
                if (!act.isFinishing) try { comecar(act, foto, nome, frase) } catch (_: Throwable) {}
            }
        }
    }

    private fun comecar(act: Activity, foto: Bitmap?, nome: String, frase: String) {
        val raiz = act.window?.decorView as? ViewGroup ?: return
        val c = act
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val inicio = SystemClock.uptimeMillis()

        val camada = FrameLayout(c)
        camada.isClickable = true
        camada.addView(FundoTop1(c, inicio), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val caixa = FrameLayout(c)
        val anel = AnelGiratorio(c).also { it.espessura = 4f; it.periodo = 1500L }
        caixa.addView(anel, FrameLayout.LayoutParams(dp(220), dp(220)))
        val fv = FotoPerfil(c)
        fv.fixa = foto
        caixa.addView(fv, FrameLayout.LayoutParams(dp(168), dp(168), Gravity.CENTER))
        col.addView(caixa, LinearLayout.LayoutParams(dp(220), dp(220)))

        val linha = LinearLayout(c)
        linha.gravity = Gravity.CENTER
        linha.addView(Ui.icone(c, R.drawable.ic_crown, Ouro.MEIO, 34), LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(10) })
        val top = TextoBrilho(c)
        top.text = "TOP 1"
        top.textSize = 48f
        top.typeface = Ui.text(c, "x", 48f, Color.WHITE, true).typeface
        top.letterSpacing = 0.12f
        top.setShadowLayer(22f, 0f, 0f, Theme.alpha(Ouro.FUNDO, 0xD0))
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
        col.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })

        val pular = Ui.text(c, "toque pra continuar", 10f, Theme.alpha(Color.WHITE, 0x99), true, mono = true)
        col.addView(pular, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(28) })

        camada.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { it.bottomMargin = dp(60) })
        raiz.addView(camada, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // tudo escondido até a explosão
        val textos = listOf(nm, selo, fr, pular)
        for (v in listOf(caixa, linha) + textos) v.alpha = 0f

        // som (gerado aqui) começa junto com a linha do tempo
        SomTop1.tocar()

        fun vibra(forte: Boolean) {
            try { camada.performHapticFeedback(if (forte) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) } catch (_: Throwable) {}
        }
        fun cl(x: Float) = x.coerceIn(0f, 1f)
        fun desacelera(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        fun passa(t: Float, s: Float = 1.6f): Float { val u = t - 1f; return 1f + (s + 1f) * u * u * u + s * u * u }

        // relógio próprio (cada quadro da tela): funciona mesmo com as animações do Android zeradas pela otimização
        var saiu = false
        var fimEm = 0L
        var vBoom = false; var vCarimbo = false; var vNome = false
        val quadro = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(n: Long) {
                if (camada.parent == null) return
                val ms = SystemClock.uptimeMillis() - inicio
                // tremida: cresce antes da explosão, forte na explosão, menor no carimbo
                val amp = when {
                    ms < Tempo.BOOM -> 3f * (ms / Tempo.BOOM.toFloat()).let { x -> x * x }
                    ms < Tempo.BOOM + 600 -> 16f * (1f - (ms - Tempo.BOOM) / 600f)
                    ms in Tempo.CARIMBO..(Tempo.CARIMBO + 350) -> 8f * (1f - (ms - Tempo.CARIMBO) / 350f)
                    else -> 0f
                } * d
                col.translationX = (Random.nextFloat() * 2f - 1f) * amp
                col.translationY = (Random.nextFloat() * 2f - 1f) * amp
                // BOOM: a foto aparece grande e encaixa girando
                if (ms >= Tempo.BOOM) {
                    if (!vBoom) { vBoom = true; vibra(true) }
                    val t = cl((ms - Tempo.BOOM) / 520f)
                    val e = passa(t)
                    caixa.alpha = 1f
                    caixa.scaleX = 1.9f - 0.9f * e; caixa.scaleY = caixa.scaleX
                    caixa.rotation = 25f * (1f - e)
                }
                // carimbo do TOP 1
                if (ms >= Tempo.CARIMBO) {
                    if (!vCarimbo) { vCarimbo = true; vibra(true) }
                    val t = desacelera(cl((ms - Tempo.CARIMBO) / 260f))
                    linha.alpha = 1f
                    linha.scaleX = 3.2f - 2.2f * t; linha.scaleY = linha.scaleX
                }
                // nome, selo, frase e o "toque pra continuar" sobem um por um
                if (ms >= Tempo.NOME && !vNome) { vNome = true; vibra(false) }
                textos.forEachIndexed { i, v ->
                    val t = desacelera(cl((ms - Tempo.NOME - i * 160L) / 480f))
                    v.alpha = t * (if (v === pular) 0.8f else 1f)
                    v.translationY = 36f * d * (1f - t)
                }
                // saída
                if (ms >= Tempo.FIM && !saiu) { saiu = true; fimEm = ms; SomTop1.parar() }
                if (saiu) {
                    if (fimEm == 0L) fimEm = ms
                    val t = cl((ms - fimEm) / 450f)
                    camada.alpha = 1f - t
                    if (t >= 1f) { try { raiz.removeView(camada) } catch (_: Throwable) {}; return }
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(quadro)
        camada.setOnClickListener {
            if (!saiu) { saiu = true; fimEm = 0L; SomTop1.parar() }
        }
    }
}

/** o pulsar suave do cartão do top 1 (relógio próprio: funciona com as animações do Android zeradas) */
fun pulsar(v: View) {
    val passo = object : Runnable {
        override fun run() {
            if (!v.isAttachedToWindow) return
            val e = 1f + 0.015f * (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 1400.0 * Math.PI).toFloat())
            v.scaleX = e; v.scaleY = e
            v.postOnAnimation(this)
        }
    }
    v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(x: View) { v.postOnAnimation(passo) }
        override fun onViewDetachedFromWindow(x: View) { v.removeCallbacks(passo) }
    })
}
