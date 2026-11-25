package com.nocry.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sin

/**
 * Efeitos dos temas Morte, Sangue e Inferno: desenhados por cima de cada cartão (Painel) e no fundo
 * animado (AnimBg). Tudo feito à mão no app, sem imagem de fora.
 */
object Efeitos {
    /** qual efeito o tema escolhido põe nos cartões: "morte", "sangue", "inferno" ou "" */
    /** efeito nos cartões do app (escolhido no Estúdio ou pelo tema); "" = nenhum */
    val cartao: String get() = Prefs.extraTxt("efeito_cartao") ?: ""
    /** força escolhida no Estúdio: 1 fraco · 2 médio · 3 forte */
    val forcaApp: Float get() = when (Prefs.lerNum("efeito_forca", 2L)) { 1L -> 0.8f; 3L -> 1.5f; else -> 1.1f }

    val TODOS = listOf("" to "Nenhum", "sangue" to "Sangue escorrendo", "inferno" to "Fogo", "raio" to "Raios",
        "toxico" to "Gosma tóxica", "gelo" to "Gelo e neve", "vazio" to "Buraco negro", "aurora" to "Aurora", "glitch" to "Glitch", "morte" to "Caveira", "diamante" to "Diamante", "neon" to "Neon city")

    fun animado(): Boolean = Prefs.anim

    private fun ruido(i: Int): Float { val x = sin(i * 12.9898 + 78.233) * 43758.5453; return (x - Math.floor(x)).toFloat() }

    // ---------------------------------------------------------------- caveira desenhada à mão
    private val pCav = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cranio = Path()

    /** caveira com o centro em (x, y), "t" = altura total; olhos podem brilhar */
    fun caveira(cv: Canvas, x: Float, y: Float, t: Float, cor: Int, alfa: Int, olho: Int = 0, brilhoOlho: Float = 0f) {
        val r = t * 0.42f
        val topo = y - t * 0.5f
        pCav.shader = null
        pCav.style = Paint.Style.FILL
        pCav.color = Theme.alpha(cor, alfa)
        // cranio + maxilar
        cranio.reset()
        cranio.addOval(RectF(x - r, topo, x + r, topo + r * 1.9f), Path.Direction.CW)
        cranio.addRoundRect(RectF(x - r * 0.62f, topo + r * 1.45f, x + r * 0.62f, topo + t), r * 0.18f, r * 0.18f, Path.Direction.CW)
        cv.drawPath(cranio, pCav)
        // olhos, nariz e dentes "furados" (cor do fundo)
        val buraco = Theme.alpha(0xFF050505.toInt(), (alfa * 0.95f).toInt())
        pCav.color = buraco
        val oy = topo + r * 1.05f
        cv.drawOval(RectF(x - r * 0.72f, oy - r * 0.32f, x - r * 0.14f, oy + r * 0.3f), pCav)
        cv.drawOval(RectF(x + r * 0.14f, oy - r * 0.32f, x + r * 0.72f, oy + r * 0.3f), pCav)
        val nariz = Path()
        nariz.moveTo(x, oy + r * 0.35f); nariz.lineTo(x - r * 0.13f, oy + r * 0.62f); nariz.lineTo(x + r * 0.13f, oy + r * 0.62f); nariz.close()
        cv.drawPath(nariz, pCav)
        pCav.strokeWidth = maxOf(1f, r * 0.07f)
        for (k in -2..2) { val dx = k * r * 0.2f; cv.drawLine(x + dx, topo + r * 1.62f, x + dx, topo + t - r * 0.08f, pCav) }
        // olho aceso
        if (olho != 0 && brilhoOlho > 0f) {
            for (lado in listOf(-1f, 1f)) {
                val ex = x + lado * r * 0.43f
                pCav.shader = RadialGradient(ex, oy, r * 0.55f, intArrayOf(Theme.alpha(olho, (255 * brilhoOlho).toInt()), Theme.alpha(olho, 0)), null, Shader.TileMode.CLAMP)
                cv.drawCircle(ex, oy, r * 0.55f, pCav)
            }
            pCav.shader = null
        }
    }

    // ---------------------------------------------------------------- nos cartões
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gota = Path()

    /** desenha o efeito do tema dentro do cartão (o "path" já está como recorte). true = precisa de outro quadro */
    /** força dos efeitos (1 = cartões comuns; o perfil do rank usa mais) */
    private var forca = 1f

    fun noCartao(cv: Canvas, path: Path, l: Float, t: Float, r: Float, b: Float, d: Float, semente: Int, tipo: String = cartao, forte: Float = 1f): Boolean {
        forca = forte
        if (tipo.isEmpty()) return false
        val w = r - l; val h = b - t
        if (w < 30f * d || h < 24f * d) return false
        val ms = if (animado()) SystemClock.uptimeMillis() else 4000L
        cv.save()
        cv.clipPath(path)
        when (tipo) {
            "sangue" -> sangue(cv, l, t, w, h, d, semente, ms)
            "toxico" -> { sangue(cv, l, t, w, h, d, semente, ms, 0xFF0E5A10.toInt(), 0xFF3DFF3A.toInt(), 0xFFB8FF9A.toInt()); bolhas(cv, l, t, w, h, d, semente, ms) }
            "raio" -> raio(cv, l, t, w, h, d, semente, ms)
            "gelo" -> gelo(cv, l, t, w, h, d, semente, ms)
            "glitch" -> glitch(cv, l, t, w, h, d, semente, ms)
            "vazio" -> vazio(cv, l, t, w, h, d, semente, ms)
            "aurora" -> aurora(cv, l, t, w, h, d, semente, ms)
            "diamante" -> diamante(cv, l, t, w, h, d, semente, ms)
            "neon" -> neon(cv, l, t, w, h, d, semente, ms)
            "inferno" -> inferno(cv, l, b, w, h, d, semente, ms)
            "morte" -> morte(cv, l, t, w, h, d, semente, ms)
        }
        cv.restore()
        return animado()
    }

    /** sangue: uma faixa escorrendo pela borda de cima e gotas que crescem, soltam e caem */
    private fun sangue(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long,
                       escuro: Int = 0xFF5A0008.toInt(), vivo: Int = 0xFFC4001A.toInt(), brilho: Int = 0xFFFF4A5A.toInt()) {
        // faixa de cima com a beirada ondulada
        gota.reset()
        gota.moveTo(l, t)
        gota.lineTo(l + w, t)
        val passos = (w / (6f * d)).toInt().coerceAtLeast(6)
        for (i in passos downTo 0) {
            val x = l + w * i / passos
            val y = t + (3.5f + 2.5f * sin(i * 1.7f + sem) + 1.2f * sin(ms / 900f + i)) * d * forca
            gota.lineTo(x, y)
        }
        gota.close()
        p.shader = LinearGradient(0f, t, 0f, t + 8f * d * forca, vivo, escuro, Shader.TileMode.CLAMP)
        cv.drawPath(gota, p)
        // gotas
        val n = (w / (34f * d / forca)).toInt().coerceIn(2, 16)
        for (i in 0 until n) {
            val k = sem * 31 + i * 7
            val x = l + w * (i + 0.25f + 0.5f * ruido(k)) / n
            val maxLen = (10f + 26f * ruido(k + 1)) * d * minOf(1f, h / (70f * d)) * forca * forca
            val periodo = 2600f + 3400f * ruido(k + 2)
            val fase = ((ms + ruido(k + 3) * periodo) % periodo) / periodo
            val larg = (2.6f + 2.2f * ruido(k + 4)) * d * (0.5f + 0.5f * forca)
            // 0..0.7 cresce devagar · 0.7..1 a gota solta e cai
            val cresce = minOf(1f, fase / 0.7f)
            val len = maxLen * (cresce * cresce * (3f - 2f * cresce))
            p.shader = LinearGradient(0f, t, 0f, t + len + larg, intArrayOf(vivo, vivo, escuro), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(x - larg / 2f, t, x + larg / 2f, t + len + larg * 0.3f), larg / 2f, larg / 2f, p)
            // ponta mais gorda
            p.shader = null
            p.color = vivo
            cv.drawCircle(x, t + len, larg * 0.75f, p)
            p.color = Theme.alpha(brilho, 0x90)
            cv.drawCircle(x - larg * 0.22f, t + len - larg * 0.25f, larg * 0.22f, p)
            if (fase > 0.7f) {
                val q = (fase - 0.7f) / 0.3f
                val yq = t + maxLen + q * q * (h - maxLen + 10f * d)
                p.color = Theme.alpha(vivo, ((1f - q) * 255).toInt())
                cv.drawOval(RectF(x - larg * 0.6f, yq - larg, x + larg * 0.6f, yq + larg * 0.6f), p)
            }
        }
        p.shader = null
    }

    /** inferno: chamas lambendo a borda de baixo e brasas subindo */
    private fun inferno(cv: Canvas, l: Float, b: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        val altMax = minOf(h * 0.55f, 34f * d * forca)
        // brilho quente embaixo
        p.shader = LinearGradient(0f, b - altMax * 1.6f, 0f, b, Theme.alpha(0xFFFF5A00.toInt(), 0), Theme.alpha(0xFFFF3A00.toInt(), 0x70), Shader.TileMode.CLAMP)
        cv.drawRect(l, b - altMax * 1.6f, l + w, b, p)
        // línguas de fogo
        val n = (w / (16f * d)).toInt().coerceIn(5, 26)
        for (camada in 0 until 2) {
            val cores = if (camada == 0) intArrayOf(Theme.alpha(0xFFFF2A00.toInt(), 0xD0), Theme.alpha(0xFFFF7A00.toInt(), 0x60), Color.TRANSPARENT)
                else intArrayOf(Theme.alpha(0xFFFFE070.toInt(), 0xE0), Theme.alpha(0xFFFFA000.toInt(), 0x70), Color.TRANSPARENT)
            val esc = if (camada == 0) 1f else 0.55f
            for (i in 0..n) {
                val k = sem * 17 + i * 5 + camada * 101
                val x = l + w * (i + 0.5f * ruido(k)) / n
                val lg = w / n * (1.1f + 0.5f * ruido(k + 1)) * esc
                val alt = altMax * esc * (0.45f + 0.55f * abs(sin(tt * (2.2f + ruido(k + 2) * 2.5f) + i)))
                val ginga = sin(tt * 3.1f + i * 0.9f) * lg * 0.35f
                gota.reset()
                gota.moveTo(x - lg / 2f, b)
                gota.quadTo(x - lg * 0.5f, b - alt * 0.55f, x + ginga, b - alt)
                gota.quadTo(x + lg * 0.5f, b - alt * 0.55f, x + lg / 2f, b)
                gota.close()
                p.shader = LinearGradient(0f, b, 0f, b - alt, cores, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                cv.drawPath(gota, p)
            }
        }
        p.shader = null
        // brasas
        for (i in 0 until 7) {
            val k = sem * 13 + i * 3
            val per = 1.6f + 1.4f * ruido(k)
            val f = ((tt + ruido(k + 1) * per) % per) / per
            val x = l + w * ruido(k + 2) + sin(tt * 2f + i) * 6f * d
            val y = b - f * h * 0.9f
            p.color = Theme.alpha(if (i % 2 == 0) 0xFFFFC040.toInt() else 0xFFFF5A00.toInt(), ((1f - f) * 230).toInt())
            cv.drawCircle(x, y, (1.2f + 1.3f * (1f - f)) * d, p)
        }
    }

    /** morte: caveira apagada no canto com os olhos acendendo em vermelho, e uma névoa passando */
    private fun morte(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        // névoa cinza atravessando o cartão
        val nx = l + ((tt * 18f * d + sem * 40f) % (w * 2f)) - w * 0.5f
        p.shader = RadialGradient(nx, t + h * 0.7f, maxOf(w, h) * 0.6f, Theme.alpha(0xFFBFC4C8.toInt(), (0x22 * forca).toInt().coerceAtMost(0x60)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(l, t, l + w, t + h, p)
        val nx2 = l + w - ((tt * 11f * d + sem * 70f) % (w * 2f)) + w * 0.5f
        p.shader = RadialGradient(nx2, t + h * 0.3f, maxOf(w, h) * 0.5f, Theme.alpha(0xFF8A8F96.toInt(), (0x18 * forca).toInt().coerceAtMost(0x50)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(l, t, l + w, t + h, p)
        p.shader = null
        // caveira grande no canto de baixo à direita, cortada pela borda
        val tam = minOf(h * 1.05f, 120f * d * forca)
        val brilho = 0.35f + 0.65f * (0.5f + 0.5f * sin(tt * 1.6f + sem)).let { it * it }
        caveira(cv, l + w - tam * 0.38f, t + h - tam * 0.32f, tam, 0xFFE8E2D4.toInt(), (0x1E * forca * forca).toInt().coerceAtMost(0x70), 0xFFFF1E2D.toInt(), brilho * (0.55f + 0.3f * (forca - 1f)))
        // no perfil: caveirinhas subindo pelo cartão
        if (forca > 1.2f) for (i in 0 until 5) {
            val per = 5f + 3f * ruido(sem + i)
            val f = ((tt + ruido(sem + i + 9) * per) % per) / per
            val x = l + w * (0.1f + 0.6f * ruido(sem + i + 20)) + sin(tt + i) * 8f * d
            val y = t + h * (1.1f - f * 1.25f)
            cv.save(); cv.rotate(sin(tt * 0.8f + i) * 15f, x, y)
            caveira(cv, x, y, (14f + 10f * ruido(i + 3)) * d, 0xFFE8E2D4.toInt(), (sin(f * Math.PI).toFloat() * 0x70).toInt(), 0xFFFF1E2D.toInt(), 0.6f)
            cv.restore()
        }
    }

    /** tóxico: bolhas verdes subindo e estourando */
    private fun bolhas(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        p.style = Paint.Style.STROKE
        for (i in 0 until (8 * forca).toInt()) {
            val per = 2.2f + 2.5f * ruido(sem + i)
            val f = ((tt + ruido(sem + i + 5) * per) % per) / per
            val x = l + w * ruido(sem + i + 11) + sin(tt * 2f + i) * 5f * d
            val y = t + h * (1.02f - f * 0.9f)
            val r = (2f + 4f * ruido(i + 2)) * d * (0.6f + 0.4f * f)
            val estoura = f > 0.9f
            p.strokeWidth = 1.3f * d
            p.color = Theme.alpha(0xFF6DFF5A.toInt(), if (estoura) ((1f - f) * 10f * 200).toInt() else 0xC0)
            cv.drawCircle(x, y, if (estoura) r * (1f + (f - 0.9f) * 12f) else r, p)
            if (!estoura) {
                p.style = Paint.Style.FILL
                p.color = Theme.alpha(0xFFDFFFD0.toInt(), 0x90)
                cv.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.25f, p)
                p.style = Paint.Style.STROKE
            }
        }
        p.style = Paint.Style.FILL
        // brilho verde de baixo
        p.shader = LinearGradient(0f, t + h * 0.6f, 0f, t + h, Color.TRANSPARENT, Theme.alpha(0xFF3DFF3A.toInt(), 0x30), Shader.TileMode.CLAMP)
        cv.drawRect(l, t + h * 0.6f, l + w, t + h, p)
        p.shader = null
    }

    /** raio: relâmpagos estalando no cartão, com brilho roxo/azul e faíscas na borda */
    private fun raio(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val ciclo = 1400L
        val n = ms / ciclo
        val fase = (ms % ciclo) / ciclo.toFloat()
        // clarão no fundo quando o raio cai
        if (fase < 0.18f) {
            p.color = Theme.alpha(0xFFB8A4FF.toInt(), ((1f - fase / 0.18f) * 0x40).toInt())
            cv.drawRect(l, t, l + w, t + h, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        for (k in 0 until 2) {
            val semente = (n * 7 + k * 13 + sem).toInt()
            val vida = if (k == 0) fase else (fase + 0.5f) % 1f
            if (vida > 0.32f) continue
            val alfa = (1f - vida / 0.32f) * (if ((ms / 45) % 2 == 0L) 1f else 0.6f)   // pisca
            // caminho em zigue-zague, de cima pra baixo
            gota.reset()
            var x = l + w * (0.15f + 0.7f * ruido(semente))
            var y = t
            gota.moveTo(x, y)
            val passos = 9
            val ramos = ArrayList<FloatArray>()
            for (i in 1..passos) {
                y = t + h * i / passos
                x += (ruido(semente + i * 3) - 0.5f) * w * 0.16f
                gota.lineTo(x, y)
                if (i == 3 || i == 6) ramos.add(floatArrayOf(x, y))
            }
            for ((lw, al, cor) in listOf(Triple(10f, 0x30, 0xFF7A5CFF.toInt()), Triple(5f, 0x80, 0xFF5AC8FF.toInt()), Triple(1.8f, 0xFF, Color.WHITE))) {
                p.strokeWidth = lw * d
                p.color = Theme.alpha(cor, (al * alfa).toInt())
                cv.drawPath(gota, p)
            }
            // galhos
            p.strokeWidth = 1.4f * d
            p.color = Theme.alpha(0xFFCFE8FF.toInt(), (0xC0 * alfa).toInt())
            for ((i, rr) in ramos.withIndex()) {
                val dir = if (i % 2 == 0) 1f else -1f
                cv.drawLine(rr[0], rr[1], rr[0] + dir * w * 0.12f, rr[1] + h * 0.12f, p)
            }
        }
        p.style = Paint.Style.FILL
        // faíscas correndo na borda
        val tt = ms / 1000f
        for (i in 0 until 6) {
            val pos = ((tt * 0.35f + i / 6f) % 1f)
            val per = 2f * (w + h)
            var q = pos * per
            val (x, y) = when {
                q < w -> l + q to t
                q < w + h -> { q -= w; l + w to t + q }
                q < 2 * w + h -> { q -= w + h; l + w - q to t + h }
                else -> { q -= 2 * w + h; l to t + h - q }
            }
            p.shader = RadialGradient(x, y, 6f * d, intArrayOf(Color.WHITE, Theme.alpha(0xFF5AC8FF.toInt(), 0x90), Color.TRANSPARENT), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, 6f * d, p)
        }
        p.shader = null
    }

    /** gelo: pingentes pendurados no topo brilhando, cantos congelados e neve caindo */
    private fun gelo(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        // cantos congelados
        for ((cx, cy) in listOf(l to t, l + w to t, l to t + h, l + w to t + h)) {
            p.shader = RadialGradient(cx, cy, 60f * d, Theme.alpha(0xFFDDF4FF.toInt(), 0x50), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, 60f * d, p)
        }
        // pingentes
        val n = (w / (14f * d)).toInt().coerceIn(6, 40)
        for (i in 0 until n) {
            val x = l + w * (i + 0.5f) / n
            val comp = (6f + 22f * ruido(sem + i)) * d * forca * 0.8f
            val lg = (w / n) * (0.6f + 0.3f * ruido(sem + i + 3))
            gota.reset()
            gota.moveTo(x - lg / 2f, t); gota.lineTo(x + lg / 2f, t); gota.lineTo(x, t + comp); gota.close()
            p.shader = LinearGradient(0f, t, 0f, t + comp, Theme.alpha(0xFFFFFFFF.toInt(), 0xD0), Theme.alpha(0xFF8FD8FF.toInt(), 0x50), Shader.TileMode.CLAMP)
            cv.drawPath(gota, p)
            // brilho correndo pelos pingentes
            val luz = ((tt * 0.5f) % 1.4f) * w
            if (abs(x - l - luz) < lg) {
                p.shader = null
                p.color = Theme.alpha(Color.WHITE, 0xE0)
                cv.drawCircle(x, t + comp * 0.35f, 1.6f * d, p)
            }
        }
        p.shader = null
        // neve
        for (i in 0 until (14 * forca).toInt()) {
            val per = 4f + 4f * ruido(sem + i + 30)
            val f = ((tt + ruido(sem + i + 50) * per) % per) / per
            val x = l + w * ruido(sem + i + 70) + sin(tt * 1.3f + i) * 8f * d
            val y = t + f * h
            p.color = Theme.alpha(Color.WHITE, (0xD0 * sin(f * Math.PI).toFloat()).toInt())
            cv.drawCircle(x, y, (1f + 1.6f * ruido(i + 4)) * d, p)
        }
    }

    /** glitch: faixas do cartão "pulando" com cor separada (vermelho/ciano) e linhas de tv */
    private fun glitch(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val bloco = ms / 90
        val ativo = ruido((bloco / 6).toInt() + sem) > 0.45f
        // linhas de tv
        p.color = Theme.alpha(Color.WHITE, 0x0C)
        var y = t
        while (y < t + h) { cv.drawRect(l, y, l + w, y + 1f * d, p); y += 3f * d }
        // faixa de luz descendo
        val yy = t + ((ms / 2200f) % 1f) * h
        p.shader = LinearGradient(0f, yy - 16f * d, 0f, yy + 16f * d, intArrayOf(Color.TRANSPARENT, Theme.alpha(0xFF00F0FF.toInt(), 0x22), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        cv.drawRect(l, yy - 16f * d, l + w, yy + 16f * d, p)
        p.shader = null
        if (!ativo) return
        for (i in 0 until 6) {
            val k = (bloco * 3 + i).toInt()
            if (ruido(k + sem) < 0.35f) continue
            val y0 = t + h * ruido(k + 1)
            val alt = (2f + 12f * ruido(k + 2)) * d
            val dx = (ruido(k + 3) - 0.5f) * 24f * d
            p.color = Theme.alpha(0xFFFF1E4E.toInt(), 0x50)
            cv.drawRect(l + dx - 3f * d, y0, l + w * (0.3f + 0.7f * ruido(k + 4)) + dx - 3f * d, y0 + alt, p)
            p.color = Theme.alpha(0xFF00F0FF.toInt(), 0x50)
            cv.drawRect(l + dx + 3f * d, y0 + 1f * d, l + w * (0.2f + 0.8f * ruido(k + 5)) + dx + 3f * d, y0 + alt + 1f * d, p)
        }
    }

    /** buraco negro: um redemoinho roxo puxando partículas pro centro, com anel de luz */
    private fun vazio(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        val cx = l + w * 0.78f; val cy = t + h * 0.45f
        val R = minOf(w, h) * 0.55f * forca
        // disco de acreção girando
        cv.save()
        cv.rotate(tt * 40f, cx, cy)
        p.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Theme.alpha(0xFFB46BFF.toInt(), 0x70), Theme.alpha(0xFF5A2BFF.toInt(), 0x30), Color.TRANSPARENT, Theme.alpha(0xFFFF6BD8.toInt(), 0x50), Color.TRANSPARENT), null)
        cv.drawCircle(cx, cy, R, p)
        cv.restore()
        // miolo preto com borda de luz
        p.shader = RadialGradient(cx, cy, R * 0.42f, intArrayOf(Color.BLACK, Color.BLACK, Theme.alpha(0xFFE0B8FF.toInt(), 0xC0), Color.TRANSPARENT), floatArrayOf(0f, 0.62f, 0.72f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, R * 0.42f, p)
        p.shader = null
        // partículas em espiral caindo no centro
        for (i in 0 until (26 * forca).toInt()) {
            val per = 2.5f + 2f * ruido(sem + i)
            val f = ((tt + ruido(sem + i + 7) * per) % per) / per
            val ang = ruido(sem + i + 3) * 6.283f + f * 7f
            val rr = R * 1.6f * (1f - f)
            val x = cx + rr * kotlin.math.cos(ang); val y = cy + rr * kotlin.math.sin(ang) * 0.75f
            p.color = Theme.alpha(if (i % 3 == 0) 0xFFFFFFFF.toInt() else 0xFFC9A0FF.toInt(), (sin(f * Math.PI).toFloat() * 230).toInt())
            cv.drawCircle(x, y, (0.8f + 1.4f * (1f - f)) * d, p)
        }
    }

    /** aurora: faixas de luz verde/roxa ondulando devagar pelo cartão */
    private fun aurora(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        val cores = intArrayOf(0xFF3DFFB4.toInt(), 0xFF7A5CFF.toInt(), 0xFF3AC8FF.toInt())
        for (k in 0 until 3) {
            gota.reset()
            val base = t + h * (0.25f + 0.22f * k)
            gota.moveTo(l, base)
            val passos = 16
            for (i in 0..passos) {
                val x = l + w * i / passos
                val y = base + sin(i * 0.55f + tt * (0.6f + 0.2f * k) + k * 2f) * 12f * d * forca + sin(i * 1.3f + tt * 1.4f) * 4f * d
                gota.lineTo(x, y)
            }
            gota.lineTo(l + w, base + 40f * d * forca); gota.lineTo(l, base + 40f * d * forca); gota.close()
            p.shader = LinearGradient(0f, base - 14f * d, 0f, base + 40f * d * forca, intArrayOf(Theme.alpha(cores[k], 0x70), Theme.alpha(cores[k], 0x18), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cv.drawPath(gota, p)
        }
        p.shader = null
        // estrelinhas
        for (i in 0 until 10) {
            val a = 0.4f + 0.6f * abs(sin(tt * (1f + ruido(i)) + i))
            p.color = Theme.alpha(Color.WHITE, (a * 200).toInt())
            cv.drawCircle(l + w * ruido(sem + i * 2), t + h * ruido(sem + i * 2 + 1) * 0.5f, 1.1f * d, p)
        }
    }

    private val estrela = Path()

    /** estrela de 4 pontas (brilho de diamante) */
    private fun brilho4(cv: Canvas, x: Float, y: Float, r: Float, cor: Int, alfa: Int) {
        estrela.reset()
        estrela.moveTo(x, y - r); estrela.quadTo(x, y, x + r, y); estrela.quadTo(x, y, x, y + r)
        estrela.quadTo(x, y, x - r, y); estrela.quadTo(x, y, x, y - r); estrela.close()
        p.shader = null
        p.color = Theme.alpha(cor, alfa)
        cv.drawPath(estrela, p)
        p.color = Theme.alpha(Color.WHITE, alfa)
        cv.drawCircle(x, y, r * 0.14f, p)
    }

    /** diamante: faixa de luz arco-íris passando na diagonal e brilhos de 4 pontas piscando */
    private fun diamante(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        val pos = ((tt * 0.35f) % 1.6f - 0.3f) * (w + h)
        cv.save()
        cv.rotate(-25f, l + w / 2f, t + h / 2f)
        val faixa = 70f * d * forca
        p.shader = LinearGradient(l + pos - faixa, 0f, l + pos + faixa, 0f,
            intArrayOf(Color.TRANSPARENT, Theme.alpha(0xFF7DF9FF.toInt(), 0x40), Theme.alpha(Color.WHITE, 0x70), Theme.alpha(0xFFFF8AF0.toInt(), 0x40), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(l - h, t - w, l + w + h, t + h + w, p)
        cv.restore()
        p.shader = null
        for (i in 0 until (9 * forca).toInt()) {
            val per = 1.6f + 1.8f * ruido(sem + i)
            val f = ((tt + ruido(sem + i + 4) * per) % per) / per
            val a = sin(f * Math.PI).toFloat()
            val x = l + w * ruido(sem + i * 3 + 1); val y = t + h * ruido(sem + i * 3 + 2)
            brilho4(cv, x, y, (5f + 6f * ruido(i + 8)) * d * a, if (i % 2 == 0) 0xFF9EF6FF.toInt() else 0xFFFFB8F4.toInt(), (a * 255).toInt())
        }
    }

    /** neon city: chão quadriculado andando embaixo e um sol de linhas atrás, com brilho rosa/ciano */
    private fun neon(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val tt = ms / 1000f
        val y0 = t + h * 0.62f
        // sol listrado
        val cx = l + w * 0.78f; val rs = minOf(w, h) * 0.28f
        p.shader = LinearGradient(0f, y0 - rs * 2f, 0f, y0, 0xFFFFD06B.toInt(), 0xFFFF3CAC.toInt(), Shader.TileMode.CLAMP)
        cv.save()
        cv.clipRect(l, t, l + w, y0)
        cv.drawCircle(cx, y0 - rs * 0.4f, rs, p)
        cv.restore()
        p.shader = null
        p.color = 0xFF07040F.toInt()
        for (k in 0 until 5) { val yy = y0 - rs * 0.4f + k * rs * 0.18f; cv.drawRect(cx - rs, yy, cx + rs, yy + (1.5f + k) * d, p) }
        // chão
        p.color = Theme.alpha(0xFF07040F.toInt(), 0xC0)
        cv.drawRect(l, y0, l + w, t + h, p)
        p.strokeWidth = 1.2f * d
        val n = 10
        for (i in -n..n) {
            val a = 1f - abs(i) / (n + 1f)
            p.color = Theme.alpha(0xFF22E4FF.toInt(), (0x30 + 0x80 * a).toInt())
            cv.drawLine(l + w / 2f + i * w * 0.04f, y0, l + w / 2f + i * w * 0.22f, t + h, p)
        }
        val sc = (tt * 0.5f) % 1f
        for (j in 0 until 6) {
            val f = (j + sc) / 6f
            val y = y0 + (t + h - y0) * f * f
            p.color = Theme.alpha(0xFFFF3CAC.toInt(), (0x40 + 0xA0 * f).toInt())
            cv.drawLine(l, y, l + w, y, p)
        }
        // horizonte brilhando
        p.shader = LinearGradient(0f, y0 - 6f * d, 0f, y0 + 6f * d, intArrayOf(Color.TRANSPARENT, Theme.alpha(0xFFFF3CAC.toInt(), 0xA0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        cv.drawRect(l, y0 - 6f * d, l + w, y0 + 6f * d, p)
        p.shader = null
    }

    /** fundo Tempestade: chuva caindo de lado e relâmpago iluminando a tela */
    fun fundoTempestade(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        val ms = (t * 1000).toLong()
        if (Prefs.particulas) {
            pb.strokeWidth = 1.2f * d
            for (i in 0 until 70) {
                val per = 0.7f + 0.5f * ruido(i)
                val f = ((t + ruido(i + 30) * per) % per) / per
                val x = w * ruido(i + 60) + f * 40f * d
                val y = -40f * d + f * (h + 80f * d)
                pb.color = Theme.alpha(0xFFB8D4FF.toInt(), 0x50)
                cv.drawLine(x, y, x - 6f * d, y - 22f * d, pb)
            }
        }
        // relâmpago grande a cada ~5 s
        val ciclo = 5200L
        val fase = (ms % ciclo) / ciclo.toFloat()
        if (fase < 0.06f) {
            val a = (1f - fase / 0.06f) * (if ((ms / 50) % 2 == 0L) 1f else 0.5f)
            cv.drawColor(Theme.alpha(0xFFC8C0FF.toInt(), (a * 0x50).toInt()))
            val sem = (ms / ciclo).toInt()
            gota.reset()
            var x = w * (0.2f + 0.6f * ruido(sem)); gota.moveTo(x, 0f)
            for (i in 1..12) { x += (ruido(sem + i) - 0.5f) * w * 0.12f; gota.lineTo(x, h * 0.65f * i / 12) }
            pb.style = Paint.Style.STROKE
            for ((lw, al, cor) in listOf(Triple(14f, 0x40, 0xFF7A5CFF.toInt()), Triple(3f, 0xFF, Color.WHITE))) {
                pb.strokeWidth = lw * d; pb.color = Theme.alpha(cor, (al * a).toInt()); cv.drawPath(gota, pb)
            }
            pb.style = Paint.Style.FILL
        }
    }

    /** fundo Tóxico: névoa verde e bolhas subindo */
    fun fundoToxico(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        pb.shader = LinearGradient(0f, h * 0.5f, 0f, h, Color.TRANSPARENT, Theme.alpha(0xFF2BFF4A.toInt(), 0x40), Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.5f, w, h, pb)
        pb.shader = null
        if (!Prefs.particulas) return
        pb.style = Paint.Style.STROKE
        for (i in 0 until 30) {
            val per = 4f + 5f * ruido(i)
            val f = ((t + ruido(i + 40) * per) % per) / per
            val x = w * ruido(i + 80) + sin(t * 1.2f + i) * 14f * d
            val y = h * (1.05f - f * 1.1f)
            pb.strokeWidth = 1.4f * d
            pb.color = Theme.alpha(0xFF7DFF6A.toInt(), (sin(f * Math.PI).toFloat() * 170).toInt())
            cv.drawCircle(x, y, (3f + 7f * ruido(i + 5)) * d, pb)
        }
        pb.style = Paint.Style.FILL
    }

    /** fundo Vazio: buraco negro girando no alto da tela, sugando estrelas */
    fun fundoVazio(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        val cx = w * 0.5f; val cy = h * 0.3f
        val R = w * 0.75f
        cv.save(); cv.rotate(t * 12f, cx, cy)
        pb.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Theme.alpha(0xFFB46BFF.toInt(), 0x45), Color.TRANSPARENT, Theme.alpha(0xFFFF6BD8.toInt(), 0x35), Color.TRANSPARENT), null)
        cv.drawCircle(cx, cy, R, pb)
        cv.restore()
        pb.shader = RadialGradient(cx, cy, R * 0.25f, intArrayOf(Color.BLACK, Color.BLACK, Theme.alpha(0xFFE0B8FF.toInt(), 0x90), Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, R * 0.25f, pb)
        pb.shader = null
        if (!Prefs.particulas) return
        for (i in 0 until 60) {
            val per = 5f + 4f * ruido(i)
            val f = ((t + ruido(i + 7) * per) % per) / per
            val ang = ruido(i + 3) * 6.283f + f * 6f
            val rr = maxOf(w, h) * 0.8f * (1f - f)
            pb.color = Theme.alpha(Color.WHITE, (sin(f * Math.PI).toFloat() * 200).toInt())
            cv.drawCircle(cx + rr * kotlin.math.cos(ang), cy + rr * kotlin.math.sin(ang) * 0.8f, (0.8f + 1.2f * (1f - f)) * d, pb)
        }
    }

    // ---------------------------------------------------------------- fundos animados
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG)

    /** fundo Caveiras: névoa e caveiras subindo devagar, girando e sumindo */
    fun fundoCaveiras(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        // névoa
        for (i in 0 until 3) {
            val x = w * (0.5f + 0.45f * sin(t * (0.05f + i * 0.02f) + i * 2f))
            val y = h * (0.35f + 0.3f * i)
            pb.shader = RadialGradient(x, y, w * 0.8f, Theme.alpha(0xFF9AA0A6.toInt(), 0x1C), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, pb)
        }
        pb.shader = null
        if (!Prefs.particulas) return
        for (i in 0 until 11) {
            val per = 22f + 16f * ruido(i)
            val f = ((t + ruido(i + 40) * per) % per) / per
            val x = w * ruido(i + 80) + sin(t * 0.4f + i) * 18f * d
            val y = h * (1.1f - f * 1.25f)
            val tam = (28f + 40f * ruido(i + 120)) * d
            val alfa = (sin(f * Math.PI).toFloat() * 0x46).toInt()
            cv.save()
            cv.rotate(sin(t * 0.3f + i) * 14f, x, y)
            caveira(cv, x, y, tam, 0xFFE8E2D4.toInt(), alfa, 0xFFFF1E2D.toInt(), (sin(t * 1.3f + i) * 0.5f + 0.5f) * alfa / 0x46 * 0.7f)
            cv.restore()
        }
    }

    /** fundo Sangue: sangue escorrendo do topo da tela e pingando */
    fun fundoSangue(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        // vinheta vermelha
        pb.shader = RadialGradient(w / 2f, h * 0.45f, maxOf(w, h) * 0.75f, Color.TRANSPARENT, Theme.alpha(0xFF3A0006.toInt(), 0xB0), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, pb)
        pb.shader = null
        val escuro = 0xFF4A0006.toInt(); val vivo = 0xFFB0001A.toInt()
        // borda de cima
        gota.reset()
        gota.moveTo(0f, 0f); gota.lineTo(w, 0f)
        val passos = 24
        for (i in passos downTo 0) gota.lineTo(w * i / passos, (14f + 8f * sin(i * 1.3f) + 3f * sin(t * 0.8f + i)) * d)
        gota.close()
        pb.color = Theme.alpha(escuro, 0xE0)
        cv.drawPath(gota, pb)
        if (!Prefs.particulas) return
        for (i in 0 until 9) {
            val per = 6f + 6f * ruido(i + 7)
            val f = ((t + ruido(i + 3) * per) % per) / per
            val x = w * (i + 0.2f + 0.6f * ruido(i + 11)) / 9f
            val maxLen = h * (0.12f + 0.28f * ruido(i + 19))
            val larg = (4f + 4f * ruido(i + 23)) * d
            val cresce = minOf(1f, f / 0.75f)
            val len = maxLen * cresce * cresce
            pb.shader = LinearGradient(0f, 0f, 0f, len + 14f * d, intArrayOf(escuro, vivo, vivo), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(x - larg / 2f, 0f, x + larg / 2f, 14f * d + len), larg / 2f, larg / 2f, pb)
            pb.shader = null
            pb.color = vivo
            cv.drawCircle(x, 14f * d + len, larg * 0.8f, pb)
            if (f > 0.75f) {
                val q = (f - 0.75f) / 0.25f
                pb.color = Theme.alpha(vivo, ((1f - q) * 255).toInt())
                cv.drawCircle(x, 14f * d + maxLen + q * q * h, larg * 0.55f, pb)
            }
        }
    }

    /** fundo Inferno: fogo subindo da parte de baixo e muita brasa */
    fun fundoInferno(cv: Canvas, w: Float, h: Float, t: Float, d: Float) {
        pb.shader = LinearGradient(0f, h * 0.45f, 0f, h, Color.TRANSPARENT, Theme.alpha(0xFFFF3A00.toInt(), 0x80), Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.45f, w, h, pb)
        val n = 14
        for (camada in 0 until 3) {
            val altMax = h * (0.26f - camada * 0.07f)
            val cores = when (camada) {
                0 -> intArrayOf(Theme.alpha(0xFFB01000.toInt(), 0xB0), Theme.alpha(0xFFFF3A00.toInt(), 0x50), Color.TRANSPARENT)
                1 -> intArrayOf(Theme.alpha(0xFFFF5A00.toInt(), 0xC0), Theme.alpha(0xFFFF9A00.toInt(), 0x50), Color.TRANSPARENT)
                else -> intArrayOf(Theme.alpha(0xFFFFE070.toInt(), 0xD0), Theme.alpha(0xFFFFB000.toInt(), 0x50), Color.TRANSPARENT)
            }
            for (i in 0..n) {
                val k = i * 5 + camada * 50
                val x = w * (i + 0.5f * ruido(k)) / n
                val lg = w / n * (1.5f + 0.6f * ruido(k + 1))
                val alt = altMax * (0.5f + 0.5f * abs(sin(t * (1.6f + ruido(k + 2) * 2f) + i)))
                val ginga = sin(t * 2.3f + i * 0.8f + camada) * lg * 0.4f
                gota.reset()
                gota.moveTo(x - lg / 2f, h)
                gota.quadTo(x - lg * 0.5f, h - alt * 0.55f, x + ginga, h - alt)
                gota.quadTo(x + lg * 0.5f, h - alt * 0.55f, x + lg / 2f, h)
                gota.close()
                pb.shader = LinearGradient(0f, h, 0f, h - alt, cores, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                cv.drawPath(gota, pb)
            }
        }
        pb.shader = null
        if (!Prefs.particulas) return
        for (i in 0 until 40) {
            val per = 3f + 4f * ruido(i)
            val f = ((t + ruido(i + 60) * per) % per) / per
            val x = w * ruido(i + 90) + sin(t * 1.4f + i) * 22f * d
            val y = h * (1.02f - f * 1.05f)
            pb.color = Theme.alpha(if (i % 3 == 0) 0xFFFFD060.toInt() else 0xFFFF6A00.toInt(), ((1f - f) * 220).toInt())
            cv.drawCircle(x, y, (1.4f + 2f * ruido(i + 5)) * d * (1f - f * 0.5f), pb)
        }
    }
}
