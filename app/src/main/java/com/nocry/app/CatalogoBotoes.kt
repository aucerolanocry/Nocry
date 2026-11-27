package com.nocry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Catálogo de formatos de botão — SÓ NA PRÉVIA do CI (tela "catalogo"), pro dono escolher pelo número.
 * O app de verdade não usa nada daqui até o dono escolher um formato.
 */
object Formatos {
    val nomes = listOf(
        "Pílula", "Arredondado", "Quadrado vazado", "Canto cortado", "Dois cantos cortados", "Octógono",
        "Hexágono", "Inclinado", "Sublinhado", "Mira", "Neon", "3D de jogo",
        "Sólido claro", "Degradê colorido", "Folha", "Ingresso", "Escuro sem borda", "Contorno duplo"
    )

    /** o botão grande desse formato é claro (texto escuro em cima)? */
    fun grandeClaro(f: Int) = f in listOf(1, 2, 4, 5, 6, 7, 8, 12, 13, 15, 16)
    /** o botão pequeno desse formato é claro? */
    fun pequenoClaro(f: Int) = f == 13
}

class FormatoBotao(c: Context, private val f: Int, private val grande: Boolean) : Drawable() {
    private val d = c.resources.displayMetrics.density
    private val path = Path()
    private val base = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private var r = RectF()

    private val BRANCO = 0xFFF2F3F7.toInt()
    private val ROXO = 0xFF8B5CF6.toInt()
    private val AZUL = 0xFF3B82F6.toInt()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        // neon precisa de espaço pro brilho; 3D precisa da base embaixo
        val folga = if (f == 11) 4f * d else 1f * d
        r = RectF(b.left + folga, b.top + folga, b.right - folga, b.bottom - folga - if (f == 12) 4f * d else 0f)
        monta(path, r)
        if (f == 12) { base.reset(); base.addRoundRect(RectF(r.left, r.top + 4f * d, r.right, r.bottom + 4f * d), 10f * d, 10f * d, Path.Direction.CW) }
    }

    private fun monta(p: Path, r: RectF) {
        p.reset()
        val h = r.height(); val l = r.left; val t = r.top; val rt = r.right; val bt = r.bottom; val cy = r.centerY()
        val k = minOf(12f * d, h / 2.6f)
        when (f) {
            1 -> p.addRoundRect(r, h / 2f, h / 2f, Path.Direction.CW)
            3 -> p.addRoundRect(r, 2f * d, 2f * d, Path.Direction.CW)
            4 -> { p.moveTo(l, t); p.lineTo(rt - k, t); p.lineTo(rt, t + k); p.lineTo(rt, bt); p.lineTo(l, bt); p.close() }
            5 -> { p.moveTo(l + k, t); p.lineTo(rt, t); p.lineTo(rt, bt - k); p.lineTo(rt - k, bt); p.lineTo(l, bt); p.lineTo(l, t + k); p.close() }
            6 -> { val q = k * 0.75f
                p.moveTo(l + q, t); p.lineTo(rt - q, t); p.lineTo(rt, t + q); p.lineTo(rt, bt - q); p.lineTo(rt - q, bt); p.lineTo(l + q, bt); p.lineTo(l, bt - q); p.lineTo(l, t + q); p.close() }
            7 -> { val q = h / 2.4f
                p.moveTo(l + q, t); p.lineTo(rt - q, t); p.lineTo(rt, cy); p.lineTo(rt - q, bt); p.lineTo(l + q, bt); p.lineTo(l, cy); p.close() }
            8 -> { val q = h * 0.32f
                p.moveTo(l + q, t); p.lineTo(rt, t); p.lineTo(rt - q, bt); p.lineTo(l, bt); p.close() }
            9, 10 -> p.addRect(r, Path.Direction.CW)
            15 -> { val a = 16f * d; val b2 = 3f * d
                p.addRoundRect(r, floatArrayOf(a, a, b2, b2, a, a, b2, b2), Path.Direction.CW) }
            16 -> {
                p.addRoundRect(r, 8f * d, 8f * d, Path.Direction.CW)
                val furo = Path()
                furo.addCircle(l, cy, h * 0.2f, Path.Direction.CW)
                furo.addCircle(rt, cy, h * 0.2f, Path.Direction.CW)
                p.op(furo, Path.Op.DIFFERENCE)
            }
            else -> p.addRoundRect(r, 12f * d, 12f * d, Path.Direction.CW)
        }
    }

    override fun draw(cv: Canvas) {
        if (path.isEmpty) return
        fill.shader = null
        line.shader = null
        val claro = if (grande) Formatos.grandeClaro(f) else Formatos.pequenoClaro(f)
        when {
            f == 12 -> {
                // botão de jogo: base escura embaixo e a "tampa" por cima
                fill.color = if (grande) 0xFF8D93A8.toInt() else 0xFF0E0F15.toInt()
                cv.drawPath(base, fill)
                fill.color = if (grande) BRANCO else 0xFF2A2D3A.toInt()
                cv.drawPath(path, fill)
            }
            f == 14 -> {
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, if (grande) ROXO else Theme.alpha(ROXO, 0x99), if (grande) AZUL else Theme.alpha(AZUL, 0x99), Shader.TileMode.CLAMP)
                cv.drawPath(path, fill)
            }
            f == 17 -> { fill.color = Theme.alpha(0xFF07080D.toInt(), 0xD0); cv.drawPath(path, fill) }
            claro -> { fill.color = BRANCO; cv.drawPath(path, fill) }
            f == 9 -> {
                fill.color = Theme.alpha(Color.WHITE, 0x0E); cv.drawPath(path, fill)
                fill.color = Color.WHITE
                cv.drawRect(r.left, r.bottom - 2.5f * d, r.right, r.bottom, fill)
            }
            f == 10 -> {
                fill.color = Theme.alpha(Color.BLACK, 0x38); cv.drawPath(path, fill)
                line.color = Color.WHITE; line.strokeWidth = 2f * d; line.strokeCap = Paint.Cap.SQUARE
                val q = minOf(10f * d, r.height() / 3f)
                cv.drawLine(r.left, r.top, r.left + q, r.top, line); cv.drawLine(r.left, r.top, r.left, r.top + q, line)
                cv.drawLine(r.right, r.top, r.right - q, r.top, line); cv.drawLine(r.right, r.top, r.right, r.top + q, line)
                cv.drawLine(r.left, r.bottom, r.left + q, r.bottom, line); cv.drawLine(r.left, r.bottom, r.left, r.bottom - q, line)
                cv.drawLine(r.right, r.bottom, r.right - q, r.bottom, line); cv.drawLine(r.right, r.bottom, r.right, r.bottom - q, line)
            }
            f == 11 -> {
                fill.color = Theme.alpha(Color.BLACK, 0x55); cv.drawPath(path, fill)
                // brilho: várias bordas, da mais larga e fraca pra mais fina e forte
                val cor = 0xFF7FE7FF.toInt()
                for ((w, a) in listOf(7f to 0x18, 4.5f to 0x30, 2.8f to 0x60)) { line.color = Theme.alpha(cor, a); line.strokeWidth = w * d; cv.drawPath(path, line) }
                line.color = cor; line.strokeWidth = 1.4f * d; cv.drawPath(path, line)
            }
            else -> {
                // vidro: claro bem transparente com borda branca (o grande vazado fica só na borda)
                val vazado = grande && f in listOf(3, 18)
                fill.color = Theme.alpha(Color.WHITE, if (vazado) 0x08 else 0x1A); cv.drawPath(path, fill)
                line.color = Theme.alpha(Color.WHITE, if (grande) 0xE0 else 0x70); line.strokeWidth = (if (grande) 1.6f else 1f) * d
                cv.drawPath(path, line)
                if (f == 18) {
                    val dentro = Path(); val ri = RectF(r); ri.inset(3.5f * d, 3.5f * d)
                    dentro.addRoundRect(ri, 9f * d, 9f * d, Path.Direction.CW)
                    line.color = Theme.alpha(Color.WHITE, if (grande) 0x80 else 0x40); line.strokeWidth = d
                    cv.drawPath(dentro, line)
                }
            }
        }
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class CatalogoBotoesScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Formatos de botão"

    private fun cartao(f: Int): View {
        val c = act
        val card = Ui.vbox(c)
        card.background = Ui.glass(c)
        card.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 11))
        // número grande + nome do formato
        val topo = Ui.hbox(c)
        topo.gravity = Gravity.CENTER_VERTICAL
        val num = Ui.text(c, "$f", 12f, 0xFF14161E.toInt(), true, mono = true)
        num.gravity = Gravity.CENTER
        num.setShadowLayer(0f, 0f, 0f, 0)
        num.background = Ui.shape(c, Color.WHITE, 99)
        topo.addView(num, LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)).also { it.rightMargin = Ui.dp(c, 8) })
        topo.addView(Ui.text(c, Formatos.nomes[f - 1], 10.5f, Ui.TEXT, true).also { Ui.umaLinha(it, 7) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(topo)
        // botão grande
        val claroG = Formatos.grandeClaro(f)
        val g = FrameLayout(c)
        g.background = FormatoBotao(c, f, true)
        val tg = Ui.text(c, "Jogar", 11.5f, if (claroG) 0xFF14161E.toInt() else Color.WHITE, true, mono = true)
        if (claroG) tg.setShadowLayer(0f, 0f, 0f, 0)
        tg.letterSpacing = 0.08f
        g.addView(tg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { if (f == 12) it.bottomMargin = Ui.dp(c, 4) })
        card.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, if (f == 11) 50 else 42)).also { it.topMargin = Ui.dp(c, 10) })
        // três botões pequenos no mesmo formato
        val linha = Ui.hbox(c)
        val claroP = Formatos.pequenoClaro(f)
        listOf(R.drawable.ic_info to "Ajuda", R.drawable.ic_trofeu to "Rank", R.drawable.ic_bolt to "Toque").forEachIndexed { i, (ico, nome) ->
            val b = Ui.vbox(c)
            b.gravity = Gravity.CENTER
            b.background = FormatoBotao(c, f, false)
            val cor = if (claroP) 0xFF14161E.toInt() else Color.WHITE
            b.addView(Ui.icone(c, ico, cor, 15), LinearLayout.LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15)))
            b.addView(Ui.text(c, nome, 7.5f, cor, true).also { if (claroP) it.setShadowLayer(0f, 0f, 0f, 0); Ui.umaLinha(it, 6) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 3) })
            if (f == 12) b.setPadding(0, 0, 0, Ui.dp(c, 4))
            linha.addView(b, LinearLayout.LayoutParams(0, Ui.dp(c, if (f == 11) 54 else 46), 1f).also { if (i > 0) it.leftMargin = Ui.dp(c, 5) })
        }
        card.addView(linha, Ui.lp(top = 8, c = c))
        return card
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.dica(c, "Cada cartão mostra o botão grande e os pequenos no mesmo formato. É só me falar o número que você gostou (pode misturar, ex.: formato 5 com a cor do 14).", Ui.Dica.INFO), 0)
        val total = Formatos.nomes.size
        var f = 1
        while (f <= total) {
            val l = Ui.hbox(c)
            l.addView(cartao(f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (f + 1 <= total) l.addView(cartao(f + 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(c, 10) })
            add(l, if (f == 1) 14 else 10)
            f += 2
        }
    }
}
