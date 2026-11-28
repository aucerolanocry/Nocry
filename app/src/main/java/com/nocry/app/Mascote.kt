package com.nocry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** a carinha dela (arte original do NOCRY): troca de expressão conforme o que ela está fazendo */
object Mascote {
    enum class Cara { OUVINDO, PENSANDO, FALANDO }

    private val cache = HashMap<Cara, Bitmap>()

    /** a expressão de agora: falando, pensando ou atenta (ouvindo / esperando) */
    fun cara(): Cara = when {
        SessaoIA.estado == SessaoIA.Estado.FALANDO -> Cara.FALANDO
        Assistente.pensando || SessaoIA.estado == SessaoIA.Estado.PENSANDO -> Cara.PENSANDO
        else -> Cara.OUVINDO
    }

    fun bmp(c: Context, cara: Cara): Bitmap? = synchronized(cache) {
        cache[cara] ?: try {
            val id = when (cara) {
                Cara.OUVINDO -> R.drawable.ia_ouvindo
                Cara.PENSANDO -> R.drawable.ia_pensando
                Cara.FALANDO -> R.drawable.ia_falando
            }
            BitmapFactory.decodeResource(c.resources, id)?.also { cache[cara] = it }
        } catch (_: Throwable) { null }
    }
}

/**
 * a carinha redonda dela, com um anel que mostra o que ela está fazendo:
 * ouvindo = brilho que pulsa devagar, pensando = arco girando, falando = pulsa rápido.
 * A foto troca de expressão sozinha (suave) e ela "respira". Só desenha mais rápido enquanto está fazendo algo.
 */
class MascoteView(c: Context, private val comAnel: Boolean = true) : View(c) {
    private val foto = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND }
    private val recorte = Path()
    private val area = RectF()
    private val arco = RectF()
    private val giro = Matrix()
    private var varredura: SweepGradient? = null
    private val escalas = HashMap<Mascote.Cara, Bitmap>()
    private var atual = Mascote.cara()
    private var anterior: Mascote.Cara? = null
    private var tTroca = 0L

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        escalas.clear()
        varredura = null
    }

    /** a foto já no tamanho da tela (diminui aos poucos pra não ficar serrilhada) */
    private fun fotoNoTamanho(cara: Mascote.Cara): Bitmap? {
        escalas[cara]?.let { return it }
        val lado = (minOf(width, height) * 0.92f).toInt().coerceAtLeast(16)
        var b = Mascote.bmp(context, cara) ?: return null
        try {
            while (b.width / 2 >= lado) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b.width != lado) b = Bitmap.createScaledBitmap(b, lado, lado, true)
        } catch (_: Throwable) { }
        escalas[cara] = b
        return b
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 8f || h < 8f) return
        val agora = SystemClock.uptimeMillis()
        val nova = Mascote.cara()
        if (nova != atual) { anterior = atual; atual = nova; tTroca = agora }
        val f = ((agora - tTroca) / 280f).coerceIn(0f, 1f)

        val falando = nova == Mascote.Cara.FALANDO
        val pensando = nova == Mascote.Cara.PENSANDO
        val ouvindo = SessaoIA.ligada && !falando && !pensando
        val animando = falando || pensando || ouvindo
        val t = agora / 1000f
        val cor = Theme.accent
        val cor2 = Theme.accent2
        val lado = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f

        // ela "respira": mais forte quando fala
        val respira = when {
            falando -> 0.020f * sin(t * 11f)
            ouvindo -> 0.012f * sin(t * 2.4f)
            else -> 0.007f * sin(t * 1.5f)
        }
        val margem = if (comAnel) lado * 0.085f else 0f
        val r = (lado / 2f - margem) * (1f + respira)

        if (comAnel) {
            val larg = (lado * 0.032f).coerceAtLeast(2f)
            traco.strokeWidth = larg
            val rAnel = lado / 2f - margem * 0.45f
            when {
                pensando -> {
                    // arco girando
                    if (varredura == null) {
                        varredura = SweepGradient(cx, cy, intArrayOf(Theme.alpha(cor2, 0), Theme.alpha(cor2, 0), cor2, cor), floatArrayOf(0f, 0.35f, 0.8f, 1f))
                    }
                    giro.setRotate((agora % 1100L) / 1100f * 360f, cx, cy)
                    varredura?.setLocalMatrix(giro)
                    traco.shader = varredura
                    traco.alpha = 255
                    arco.set(cx - rAnel, cy - rAnel, cx + rAnel, cy + rAnel)
                    cv.drawArc(arco, 0f, 360f, false, traco)
                    traco.shader = null
                }
                else -> {
                    val forca = if (falando) 0.5f + 0.5f * sin(t * 11f) else if (ouvindo) 0.5f + 0.5f * sin(t * 2.4f) else 0f
                    // brilho em volta (3 anéis mais fracos por fora)
                    if (animando) {
                        for (i in 3 downTo 1) {
                            traco.color = Theme.alpha(if (falando) cor2 else cor, ((0x34 * forca) / i).toInt())
                            traco.strokeWidth = larg * (1f + i * 1.7f)
                            cv.drawCircle(cx, cy, rAnel, traco)
                        }
                    }
                    traco.strokeWidth = larg
                    traco.color = if (animando) Theme.alpha(if (falando) cor2 else cor, (150 + 105 * forca).toInt()) else Theme.alpha(cor, 0x66)
                    cv.drawCircle(cx, cy, rAnel, traco)
                }
            }
        }

        // a foto, redonda, trocando de expressão com uma suavizada
        recorte.reset()
        recorte.addCircle(cx, cy, r, Path.Direction.CW)
        area.set(cx - r, cy - r, cx + r, cy + r)
        cv.save()
        cv.clipPath(recorte)
        val ant = anterior
        if (f < 1f && ant != null) {
            fotoNoTamanho(ant)?.let { foto.alpha = 255; cv.drawBitmap(it, null, area, foto) }
        }
        fotoNoTamanho(atual)?.let { foto.alpha = (255 * f).toInt().coerceIn(0, 255); cv.drawBitmap(it, null, area, foto) }
        foto.alpha = 255
        cv.restore()

        // só redesenha rápido quando está fazendo algo; parada, só confere de vez em quando
        if (isAttachedToWindow) postInvalidateDelayed(if (animando || f < 1f) 33L else 300L)
    }
}
