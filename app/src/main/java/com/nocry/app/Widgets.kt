package com.nocry.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Anel de progresso da tela inicial (brilho neon por tras do arco). */
class RingView(c: Context) : View(c) {
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var mostrado = 0f
    private var alvo = 0f
    private var cor = Theme.accent
    private var anim: ValueAnimator? = null

    init {
        for (p in listOf(trilho, brilho, arco)) {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
        }
    }

    /** progresso de 0 a 1 e a cor do arco */
    fun define(p: Float, c: Int) {
        cor = c
        val novo = p.coerceIn(0f, 1f)
        if (novo == alvo) { invalidate(); return }
        alvo = novo
        anim?.cancel()
        if (!Prefs.anim) { mostrado = alvo; invalidate(); return }
        val a = ValueAnimator.ofFloat(mostrado, alvo)
        a.duration = 650
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { mostrado = it.animatedValue as Float; invalidate() }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val esp = 8f * d
        val m = esp
        r.set(m, m, w - m, h - m)
        trilho.strokeWidth = esp
        trilho.color = Theme.alpha(0xFFFFFFFF.toInt(), 0x1E)
        cv.drawArc(r, -90f, 360f, false, trilho)
        val varre = 360f * mostrado
        if (varre > 0.5f) {
            if (Prefs.neon) {
                brilho.strokeWidth = esp * 2.2f
                brilho.color = Theme.alpha(cor, 0x38)
                cv.drawArc(r, -90f, varre, false, brilho)
            }
            arco.strokeWidth = esp
            arco.color = cor
            cv.drawArc(r, -90f, varre, false, arco)
        }
    }
}
