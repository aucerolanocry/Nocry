package com.nocry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Visual HUD (painel futurista de IA). Por enquanto só existe na PRÉVIA do CI: liga quando o tema tem o cartão "hudx".
 * Nada daqui aparece no app de verdade antes do dono escolher.
 */
object Hud {
    /** o visual HUD está ligado (só na build de prévia) */
    val on: Boolean get() = BuildConfig.PREVIEW && Prefs.cartao == "hudx"

    /** "abrir conexão" -> "Abrir conexão" (só a primeira letra) */
    fun frase(s: String): String = if (s.isNotEmpty() && s[0].isLowerCase()) s.replaceFirstChar { it.uppercase() } else s

    private fun ruido(i: Int): Float { val x = sin(i * 12.9898 + 78.233) * 43758.5453; return (x - Math.floor(x)).toFloat() }

    /** colchetes dos cantos (a marca do painel HUD): [quatro] = os 4 cantos; senão só em cima-esquerda e embaixo-direita */
    fun cantos(cv: Canvas, l: Float, t: Float, r: Float, b: Float, tam: Float, p: Paint, quatro: Boolean = true) {
        val k = min(tam, min((r - l) / 3f, (b - t) / 3f))
        cv.drawLine(l, t, l + k, t, p); cv.drawLine(l, t, l, t + k, p)
        cv.drawLine(r, b, r - k, b, p); cv.drawLine(r, b, r, b - k, p)
        if (!quatro) return
        cv.drawLine(r, t, r - k, t, p); cv.drawLine(r, t, r, t + k, p)
        cv.drawLine(l, b, l + k, b, p); cv.drawLine(l, b, l, b - k, p)
    }

    // ------------------------------------------------------------------ fundos animados novos
    val FUNDOS = listOf("holo", "reator", "radar", "plasma")
    fun ehFundo(id: String) = id in FUNDOS

    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hexPath = Path()
    private var hexW = -1f
    private var hexH = -1f
    /** a colmeia da tela inteira desenhada uma vez só (só a transparência; a cor vem na hora de desenhar) */
    private var hexBmp: android.graphics.Bitmap? = null
    private val pb = Paint(Paint.FILTER_BITMAP_FLAG)
    private val arco = RectF()

    /** desenha o fundo [id] com o tempo [t] (segundos). Serve pro fundo do app e pra miniatura do Estúdio */
    fun desenha(cv: Canvas, id: String, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int) {
        when (id) {
            "holo" -> holo(cv, w, h, t, d, c1, c2)
            "reator" -> reator(cv, w, h, t, d, c1, c2)
            "radar" -> radar(cv, w, h, t, d, c1, c2)
            "plasma" -> plasma(cv, w, h, t, d, c1, c2)
        }
    }

    /** fundo da tela (antes dos desenhos): escuro, com a cor do tema bem de leve */
    fun ceu(id: String, w: Float, h: Float, base: Int, c1: Int): Shader = when (id) {
        "reator" -> RadialGradient(w * 0.5f, h * 0.34f, max(w, h) * 0.75f, intArrayOf(Theme.mix(base, c1, 0.14f), base, Theme.mix(base, Color.BLACK, 0.5f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        "radar" -> RadialGradient(w * 0.5f, h * 0.55f, max(w, h) * 0.7f, intArrayOf(Theme.mix(base, c1, 0.10f), base, Theme.mix(base, Color.BLACK, 0.55f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        "plasma" -> LinearGradient(0f, 0f, w, h, intArrayOf(Theme.mix(base, c1, 0.08f), base, Theme.mix(base, Color.BLACK, 0.4f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        else -> LinearGradient(0f, 0f, 0f, h, intArrayOf(Theme.mix(base, Color.BLACK, 0.45f), base, Theme.mix(base, c1, 0.10f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    /** Holograma: colmeia fininha, uma faixa de luz passando devagar, anéis girando no alto e pontinhos de dados subindo */
    private fun holo(cv: Canvas, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int) {
        val s = max(18f * d, w / 9f)
        val grande = w * h > 300_000f
        if (grande) {
            // tela inteira: usa a colmeia já desenhada (antes refazia o desenho todo a cada quadro)
            var bmp = hexBmp
            if (bmp == null || bmp.width != w.toInt() || bmp.height != h.toInt()) {
                bmp?.recycle()
                bmp = android.graphics.Bitmap.createBitmap(max(1, w.toInt()), max(1, h.toInt()), android.graphics.Bitmap.Config.ALPHA_8)
                val c2v = Canvas(bmp)
                val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, 0.6f * d); color = Color.WHITE }
                c2v.drawPath(montaHex(w, h, s), pp)
                hexBmp = bmp
            }
            pb.color = Theme.alpha(c1, 0x16)
            cv.drawBitmap(bmp, 0f, 0f, pb)
            val ciclo = 7f
            val y0 = ((t % ciclo) / ciclo) * (h + h * 0.4f) - h * 0.2f
            cv.save()
            cv.clipRect(0f, y0 - h * 0.08f, w, y0 + h * 0.08f)
            pb.color = Theme.alpha(c1, 0x40)
            cv.drawBitmap(bmp, 0f, 0f, pb)
            cv.restore()
            holoResto(cv, w, h, t, d, c1, c2, y0)
            return
        }
        if (hexW != w || hexH != h) {
            hexW = w; hexH = h
            hexPath.set(montaHex(w, h, s))
        }
        pl.shader = null
        pl.strokeWidth = max(1f, 0.6f * d)
        pl.color = Theme.alpha(c1, 0x16)
        cv.drawPath(hexPath, pl)
        // faixa de luz descendo
        val ciclo = 7f
        val y0 = ((t % ciclo) / ciclo) * (h + h * 0.4f) - h * 0.2f
        cv.save()
        cv.clipRect(0f, y0 - h * 0.08f, w, y0 + h * 0.08f)
        pl.color = Theme.alpha(c1, 0x40)
        cv.drawPath(hexPath, pl)
        cv.restore()
        holoResto(cv, w, h, t, d, c1, c2, y0)
    }

    /** a colmeia (só o desenho das linhas) */
    private fun montaHex(w: Float, h: Float, s: Float): Path {
        val path = Path()
        val hw = s * 0.866f
        var row = 0
        var y = -s
        while (y < h + s) {
            var x = if (row % 2 == 0) 0f else hw
            while (x < w + hw * 2) {
                for (k in 0 until 6) {
                    val a1 = Math.toRadians((60 * k - 30).toDouble()); val a2 = Math.toRadians((60 * (k + 1) - 30).toDouble())
                    path.moveTo(x + (s * cos(a1)).toFloat(), y + (s * sin(a1)).toFloat())
                    path.lineTo(x + (s * cos(a2)).toFloat(), y + (s * sin(a2)).toFloat())
                }
                x += hw * 2
            }
            y += s * 1.5f; row++
        }
        return path
    }

    /** faixa de luz, anéis e dados subindo do fundo Holograma */
    private fun holoResto(cv: Canvas, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int, y0: Float) {
        pf.shader = LinearGradient(0f, y0 - h * 0.1f, 0f, y0 + h * 0.1f, intArrayOf(Theme.alpha(c1, 0), Theme.alpha(c1, 0x1C), Theme.alpha(c1, 0)), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, y0 - h * 0.1f, w, y0 + h * 0.1f, pf)
        pf.shader = null
        // anéis no canto de cima
        val cx = w * 0.86f; val cy = h * 0.10f; val r = w * 0.42f
        aneis(cv, cx, cy, r, t * 0.6f, d, c1, c2, 0x30)
        // dados subindo
        for (i in 0 until 26) {
            val px = ruido(i) * w
            val v = 0.03f + ruido(i + 50) * 0.05f
            val prog = (t * v + ruido(i + 99)) % 1f
            val py = h * (1f - prog)
            val a = (sin(prog * Math.PI).toFloat() * (0.4f + 0.6f * ruido(i + 7)))
            pf.color = Theme.alpha(if (i % 3 == 0) c2 else c1, (a * 150).toInt())
            val q = (1.2f + ruido(i + 3) * 1.8f) * d
            cv.drawRect(px, py, px + q, py + q, pf)
        }
    }

    /** Reator: núcleo de energia enorme e apagadinho no meio, girando, com fagulhas douradas subindo */
    private fun reator(cv: Canvas, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int) {
        val cx = w * 0.5f; val cy = h * 0.33f; val r = w * 0.62f
        pf.shader = RadialGradient(cx, cy, r * 0.5f, intArrayOf(Theme.alpha(c2, 0x30), Theme.alpha(c1, 0x10), Theme.alpha(c1, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.5f, pf)
        pf.shader = null
        aneis(cv, cx, cy, r, t * 0.35f, d, c1, c2, 0x34)
        // 10 travas do reator, devagar
        pl.shader = null
        pl.strokeWidth = 5f * d
        for (k in 0 until 10) {
            val a = k * 36f + t * 6f
            arco.set(cx - r * 0.36f, cy - r * 0.36f, cx + r * 0.36f, cy + r * 0.36f)
            pl.color = Theme.alpha(c1, 0x26)
            cv.drawArc(arco, a, 20f, false, pl)
        }
        for (i in 0 until 34) {
            val v = 0.04f + ruido(i + 20) * 0.06f
            val prog = (t * v + ruido(i + 40)) % 1f
            val px = ruido(i) * w + sin(t * 0.8f + i) * 10f * d
            val py = h * (1.05f - prog * 1.1f)
            val a = (1f - prog) * (0.5f + 0.5f * abs(sin(t * 1.7f + i)))
            pf.color = Theme.alpha(c2, (a * 170).toInt())
            cv.drawCircle(px, py, (0.8f + ruido(i + 5) * 1.4f) * d, pf)
        }
    }

    /** Comando: radar varrendo, grade de pontos e alvos que piscam quando a varredura passa */
    private fun radar(cv: Canvas, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int) {
        val passo = 26f * d
        pf.shader = null
        pf.color = Theme.alpha(c1, 0x1A)
        var y = passo / 2f
        while (y < h) { var x = passo / 2f; while (x < w) { cv.drawCircle(x, y, 0.9f * d, pf); x += passo }; y += passo }
        val cx = w * 0.5f; val cy = h * 0.56f; val r = w * 0.78f
        pl.shader = null
        pl.strokeWidth = max(1f, 0.8f * d)
        for (k in 1..4) { pl.color = Theme.alpha(c1, 0x22); cv.drawCircle(cx, cy, r * k / 4f, pl) }
        pl.color = Theme.alpha(c1, 0x18)
        cv.drawLine(cx - r, cy, cx + r, cy, pl); cv.drawLine(cx, cy - r, cx, cy + r, pl)
        val ang = (t * 40f) % 360f
        cv.save()
        cv.rotate(ang, cx, cy)
        pf.shader = SweepGradient(cx, cy, intArrayOf(Theme.alpha(c1, 0), Theme.alpha(c1, 0), Theme.alpha(c1, 0x46)), floatArrayOf(0f, 0.8f, 1f))
        cv.drawCircle(cx, cy, r, pf)
        pf.shader = null
        cv.restore()
        // alvos: acendem quando a varredura passa por eles e vão apagando
        for (i in 0 until 9) {
            val a = ruido(i) * 360f
            val dist = (0.2f + ruido(i + 30) * 0.75f) * r
            val bx = cx + dist * cos(Math.toRadians(a.toDouble())).toFloat()
            val by = cy + dist * sin(Math.toRadians(a.toDouble())).toFloat()
            var dif = (ang - a) % 360f; if (dif < 0) dif += 360f
            val luz = max(0f, 1f - dif / 220f)
            if (luz <= 0f) continue
            pf.color = Theme.alpha(if (i % 3 == 0) c2 else c1, (luz * 210).toInt())
            cv.drawCircle(bx, by, 2.4f * d, pf)
            pl.color = Theme.alpha(c1, (luz * 120).toInt())
            cv.drawCircle(bx, by, 2.4f * d + (1f - luz) * 9f * d, pl)
        }
    }

    /** Plasma: faixas de energia ondulando devagar e estrelinhas */
    private fun plasma(cv: Canvas, w: Float, h: Float, t: Float, d: Float, c1: Int, c2: Int) {
        pf.shader = null
        for (i in 0 until 40) {
            val a = 0.25f + 0.75f * abs(sin(t * (0.3f + ruido(i) * 0.6f) + i))
            pf.color = Theme.alpha(Color.WHITE, (a * 70).toInt())
            cv.drawCircle(ruido(i + 11) * w, ruido(i + 23) * h, (0.5f + ruido(i + 3)) * d, pf)
        }
        val path = Path()
        pl.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 5) {
            path.reset()
            val base = h * (0.22f + k * 0.15f)
            val amp = h * (0.05f + 0.025f * k)
            val fase = t * (0.25f + k * 0.07f) + k * 1.7f
            var x = -10f
            path.moveTo(x, base + amp * sin(fase))
            while (x <= w + 10f) {
                x += w / 36f
                val u = x / w
                path.lineTo(x, base + amp * sin(u * 5.5f + fase) + amp * 0.4f * sin(u * 11f - fase * 1.4f))
            }
            val cor = if (k % 2 == 0) c1 else c2
            pl.shader = null
            pl.strokeWidth = 10f * d
            pl.color = Theme.alpha(cor, 0x10)
            cv.drawPath(path, pl)
            pl.strokeWidth = 1.6f * d
            pl.color = Theme.alpha(cor, 0x5A)
            cv.drawPath(path, pl)
        }
        pl.strokeCap = Paint.Cap.BUTT
    }

    /** anéis de painel de IA: traços em volta, arcos girando e um anel tracejado */
    fun aneis(cv: Canvas, cx: Float, cy: Float, r: Float, giro: Float, d: Float, c1: Int, c2: Int, alfa: Int) {
        pl.shader = null
        pl.pathEffect = null
        pl.strokeWidth = max(1f, 0.9f * d)
        val ang0 = giro * 30f
        for (i in 0 until 72) {
            val a = Math.toRadians((i * 5f + ang0).toDouble())
            val longo = i % 6 == 0
            val r1 = r; val r2 = r - (if (longo) 9f else 4f) * d
            pl.color = Theme.alpha(c1, if (longo) alfa * 2 else alfa)
            cv.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), pl)
        }
        pl.strokeWidth = 3f * d
        for (k in 0 until 3) {
            val rr = r * 0.82f
            arco.set(cx - rr, cy - rr, cx + rr, cy + rr)
            pl.color = Theme.alpha(if (k == 1) c2 else c1, alfa * 2)
            cv.drawArc(arco, -giro * 45f + k * 120f, 70f, false, pl)
        }
        pl.strokeWidth = max(1f, 1f * d)
        pl.pathEffect = DashPathEffect(floatArrayOf(6f * d, 7f * d), giro * 20f * d)
        pl.color = Theme.alpha(c1, alfa)
        cv.drawCircle(cx, cy, r * 0.64f, pl)
        pl.pathEffect = null
    }
}

/**
 * Anel de IA animado (o "reator"): traços girando, arcos em volta, o progresso (quanto está pronto) e um brilho no meio.
 * [seguirIA] = lê sozinho o que a IA está fazendo (parada, ouvindo, pensando, falando) e muda a velocidade e o brilho.
 */
class HudReator(c: Context) : View(c) {
    var progresso = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var seguirIA = false
    var modo = 0
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r0 = RectF()
    private val t0 = SystemClock.uptimeMillis()
    private var giro = 0f
    private var ultimo = 0L

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f - 2f * d
        val agora = SystemClock.uptimeMillis()
        val t = (agora - t0) / 1000f
        if (seguirIA) modo = when {
            Assistente.pensando || SessaoIA.estado == SessaoIA.Estado.PENSANDO -> 2
            SessaoIA.estado == SessaoIA.Estado.FALANDO -> 3
            SessaoIA.ligada -> 1
            else -> 0
        }
        val vel = when (modo) { 2 -> 3.4f; 1 -> 1.3f; 3 -> 1.8f; else -> 0.45f }
        val dt = if (ultimo == 0L) 0f else min(0.1f, (agora - ultimo) / 1000f)
        ultimo = agora
        giro += dt * vel
        val pulso = when (modo) {
            1 -> 0.5f + 0.5f * sin(t * 4f)
            3 -> 0.5f + 0.5f * abs(sin(t * 7f) * sin(t * 2.3f + 1f))
            2 -> 0.6f + 0.4f * sin(t * 9f)
            else -> 0.5f + 0.5f * sin(t * 1.4f)
        }
        val c1 = Theme.accent
        val c2 = Theme.accent2
        // brilho no meio
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, intArrayOf(Theme.alpha(c1, (0x2C + pulso * 0x30).toInt()), Theme.alpha(c1, 0x0A), Theme.alpha(c1, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p)
        p.shader = null
        Hud.aneis(cv, cx, cy, r, giro, d, c1, c2, 0x50)
        // progresso (quanto está pronto): trilho apagado + arco cheio
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 4f * d
        val rp = r * 0.5f
        r0.set(cx - rp, cy - rp, cx + rp, cy + rp)
        p.color = Theme.alpha(c1, 0x22)
        cv.drawArc(r0, 0f, 360f, false, p)
        p.shader = SweepGradient(cx, cy, intArrayOf(c2, c1, c2), null)
        cv.save(); cv.rotate(-90f, cx, cy)
        cv.drawArc(r0, 0f, 360f * progresso, false, p)
        cv.restore()
        p.shader = null
        // varredura rápida quando está pensando
        if (modo == 2) {
            val rv = r * 0.9f
            r0.set(cx - rv, cy - rv, cx + rv, cy + rv)
            p.strokeWidth = 2f * d
            p.color = Theme.alpha(Color.WHITE, 0xC0)
            cv.drawArc(r0, (t * 400f) % 360f, 36f, false, p)
        }
        p.strokeCap = Paint.Cap.BUTT
        if (isShown) postInvalidateDelayed(if (Prefs.anim) 33L else 250L)
    }
}

/** botão rápido do HUD (atalho de pedido pra IA, filtros): retângulo de canto cortado com colchetes */
class HudChip(c: Context, private val ligado: Boolean = false) : android.graphics.drawable.Drawable() {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(cv: Canvas) {
        val b = bounds
        val l = b.left + d; val t = b.top + d; val r = b.right - d; val bb = b.bottom - d
        val k = 7f * d
        path.reset()
        path.moveTo(l, t); path.lineTo(r - k, t); path.lineTo(r, t + k); path.lineTo(r, bb); path.lineTo(l + k, bb); path.lineTo(l, bb - k); path.close()
        p.style = Paint.Style.FILL
        p.color = if (ligado) Theme.alpha(Theme.accent, 0x2E) else Theme.alpha(Theme.mix(Theme.base, Theme.accent, 0.08f), 0xC8)
        cv.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, d)
        p.color = Theme.alpha(Theme.accent, if (ligado) 0xC0 else 0x5A)
        cv.drawPath(path, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
