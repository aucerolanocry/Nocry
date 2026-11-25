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
    val cartao: String get() = when (Prefs.tema) { "morte", "sangue", "inferno" -> Prefs.tema; else -> "" }

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
    fun noCartao(cv: Canvas, path: Path, l: Float, t: Float, r: Float, b: Float, d: Float, semente: Int): Boolean {
        val tipo = cartao
        if (tipo.isEmpty()) return false
        val w = r - l; val h = b - t
        if (w < 30f * d || h < 24f * d) return false
        val ms = if (animado()) SystemClock.uptimeMillis() else 4000L
        cv.save()
        cv.clipPath(path)
        when (tipo) {
            "sangue" -> sangue(cv, l, t, w, h, d, semente, ms)
            "inferno" -> inferno(cv, l, b, w, h, d, semente, ms)
            "morte" -> morte(cv, l, t, w, h, d, semente, ms)
        }
        cv.restore()
        return animado()
    }

    /** sangue: uma faixa escorrendo pela borda de cima e gotas que crescem, soltam e caem */
    private fun sangue(cv: Canvas, l: Float, t: Float, w: Float, h: Float, d: Float, sem: Int, ms: Long) {
        val escuro = 0xFF5A0008.toInt(); val vivo = 0xFFC4001A.toInt(); val brilho = 0xFFFF4A5A.toInt()
        // faixa de cima com a beirada ondulada
        gota.reset()
        gota.moveTo(l, t)
        gota.lineTo(l + w, t)
        val passos = (w / (6f * d)).toInt().coerceAtLeast(6)
        for (i in passos downTo 0) {
            val x = l + w * i / passos
            val y = t + (3.5f + 2.5f * sin(i * 1.7f + sem) + 1.2f * sin(ms / 900f + i)) * d
            gota.lineTo(x, y)
        }
        gota.close()
        p.shader = LinearGradient(0f, t, 0f, t + 8f * d, vivo, escuro, Shader.TileMode.CLAMP)
        cv.drawPath(gota, p)
        // gotas
        val n = (w / (34f * d)).toInt().coerceIn(2, 12)
        for (i in 0 until n) {
            val k = sem * 31 + i * 7
            val x = l + w * (i + 0.25f + 0.5f * ruido(k)) / n
            val maxLen = (10f + 26f * ruido(k + 1)) * d * minOf(1f, h / (70f * d))
            val periodo = 2600f + 3400f * ruido(k + 2)
            val fase = ((ms + ruido(k + 3) * periodo) % periodo) / periodo
            val larg = (2.6f + 2.2f * ruido(k + 4)) * d
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
        val altMax = minOf(h * 0.55f, 34f * d)
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
        p.shader = RadialGradient(nx, t + h * 0.7f, maxOf(w, h) * 0.6f, Theme.alpha(0xFFBFC4C8.toInt(), 0x22), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(l, t, l + w, t + h, p)
        p.shader = null
        // caveira grande no canto de baixo à direita, cortada pela borda
        val tam = minOf(h * 1.05f, 120f * d)
        val brilho = 0.35f + 0.65f * (0.5f + 0.5f * sin(tt * 1.6f + sem)).let { it * it }
        caveira(cv, l + w - tam * 0.38f, t + h - tam * 0.32f, tam, 0xFFE8E2D4.toInt(), 0x1E, 0xFFFF1E2D.toInt(), brilho * 0.55f)
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
