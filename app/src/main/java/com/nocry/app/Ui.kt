package com.nocry.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pecas visuais do NOCRY: vidro fosco + neon. As cores e o formato vem da tela PERSONALIZAR. */
object Ui {
    val BG: Int get() = Theme.base
    /** fundo de um cartao de vidro (so a cor de base; o desenho completo esta em glass()) */
    val CARD: Int get() = Theme.alpha(0xFF181821.toInt(), Prefs.vidro * 255 / 100)
    val CARD2: Int get() = Theme.alpha(0xFFFFFFFF.toInt(), 0x14)
    val LINE: Int get() = Theme.alpha(0xFFFFFFFF.toInt(), 0x26)
    /** cor de destaque escolhida pelo usuario */
    val RED: Int get() = Theme.accent
    /** vermelho fixo pra "desligado / problema" (nao muda com o destaque) */
    val BAD = 0xFFFF5470.toInt()
    val RED_DARK: Int get() = Theme.alpha(0xFFFF5470.toInt(), 0x2E)
    val TEXT = 0xFFF4F4F7.toInt()
    val MUTED = 0xFFA9A9B8.toInt()
    val OK = 0xFF3DDC84.toInt()
    val WARN = 0xFFF2B84B.toInt()

    enum class Kind { PRIMARY, DANGER, GHOST }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor)
        return g
    }

    /** cartao de vidro: fundo translucido com brilho leve e borda fina (neon se ligado) */
    fun glass(c: Context, raio: Int = Prefs.raio, forte: Boolean = false): GradientDrawable {
        val a = Prefs.vidro * 255 / 100
        val topo = if (forte) Theme.mix(0xFF1F1F2B.toInt(), Theme.accent, 0.24f) else 0xFF23232F.toInt()
        val baixo = if (forte) Theme.mix(0xFF111118.toInt(), Theme.accent, 0.10f) else 0xFF13131A.toInt()
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.alpha(topo, a), Theme.alpha(baixo, a)))
        g.cornerRadius = dp(c, raio).toFloat()
        val borda = when {
            forte -> Theme.alpha(Theme.accent, if (Prefs.neon) 0xB0 else 0x70)
            Prefs.neon -> Theme.alpha(Theme.accent, 0x46)
            else -> Theme.alpha(0xFFFFFFFF.toInt(), 0x26)
        }
        g.setStroke(dp(c, 1), borda)
        return g
    }

    private fun ripple(bg: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(Theme.alpha(0xFFFFFFFF.toInt(), 0x33)), bg, null)

    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Prefs.escala / 100f)
        t.setTextColor(color)
        t.setLineSpacing(0f, 1.15f)
        t.typeface = Theme.fonte(c, mono, bold)
        if (Theme.temMidiaDeFundo()) t.setShadowLayer(dp(c, 3).toFloat(), 0f, dp(c, 1).toFloat(), 0x99000000.toInt())
        return t
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, c: Context? = null): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        if (c != null && top > 0) p.topMargin = dp(c, top)
        return p
    }

    fun vbox(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun hbox(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    /** caixa de vidro onde ficam os itens */
    fun card(c: Context, forte: Boolean = false): LinearLayout {
        val l = vbox(c)
        l.background = glass(c, forte = forte)
        val p = dp(c, 16)
        l.setPadding(p, p, p, p)
        return l
    }

    /** etiqueta pequena na cor de destaque em cima de cada bloco */
    fun label(c: Context, s: String): TextView {
        val t = text(c, s.uppercase(), 11f, RED, true, mono = true)
        t.letterSpacing = 0.14f
        return t
    }

    fun spacer(c: Context, h: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
        return v
    }

    // ------------------------------------------------------------------ icones
    fun icone(c: Context, res: Int, cor: Int = MUTED, tamDp: Int = 20): ImageView {
        val i = ImageView(c)
        i.setImageResource(res)
        i.setColorFilter(cor, PorterDuff.Mode.SRC_IN)
        i.layoutParams = LinearLayout.LayoutParams(dp(c, tamDp), dp(c, tamDp))
        i.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return i
    }

    /** quadradinho arredondado com um icone dentro */
    fun caixaIcone(c: Context, res: Int, ativo: Boolean = false, tamDp: Int = 40): View {
        val f = FrameLayout(c)
        val cor = if (ativo) RED else MUTED
        f.background = shape(c, if (ativo) Theme.alpha(RED, 0x2A) else Theme.alpha(0xFFFFFFFF.toInt(), 0x14), minOf(Prefs.raio, 14))
        val ic = icone(c, res, cor, (tamDp * 0.5f).toInt())
        f.addView(ic, FrameLayout.LayoutParams(dp(c, (tamDp * 0.5f).toInt()), dp(c, (tamDp * 0.5f).toInt()), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(c, tamDp), dp(c, tamDp))
        return f
    }

    /** selo pequeno (DONO, STAFF, AUTO...) */
    fun selo(c: Context, s: String, cor: Int = RED, cheio: Boolean = true): TextView {
        val t = text(c, s.uppercase(), 10f, if (cheio) Theme.sobre(cor) else cor, true, mono = true)
        t.letterSpacing = 0.1f
        t.background = if (cheio) shape(c, cor, 7) else shape(c, Theme.alpha(cor, 0x22), 7, Theme.alpha(cor, 0x88), 1)
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4))
        return t
    }

    /** bolinha + texto de estado: "ADB · CONECTADO" */
    fun status(c: Context, nome: String, valor: String, ok: Boolean?): View {
        val r = hbox(c)
        val cor = when (ok) { true -> OK; false -> BAD; null -> WARN }
        val dot = View(c)
        dot.background = shape(c, cor, 8)
        r.addView(dot, LinearLayout.LayoutParams(dp(c, 8), dp(c, 8)))
        val n = text(c, nome, 12.5f, MUTED, mono = true)
        val np = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        np.leftMargin = dp(c, 10)
        r.addView(n, np)
        r.addView(text(c, valor, 12.5f, cor, true, mono = true))
        r.setPadding(0, dp(c, 5), 0, dp(c, 5))
        return r
    }

    fun button(c: Context, label: String, kind: Kind = Kind.PRIMARY, ico: Int = 0, onClick: () -> Unit): View {
        val fill: Int
        val stroke: Int
        val corTxt: Int
        when (kind) {
            Kind.PRIMARY -> { fill = RED; stroke = RED; corTxt = Theme.sobre(RED) }
            Kind.DANGER -> { fill = RED_DARK; stroke = Theme.alpha(BAD, 0x77); corTxt = 0xFFFF8E9E.toInt() }
            Kind.GHOST -> { fill = Theme.alpha(0xFFFFFFFF.toInt(), 0x14); stroke = Theme.alpha(0xFFFFFFFF.toInt(), 0x2C); corTxt = TEXT }
        }
        val r = minOf(Prefs.raio, 18)
        val bg: Drawable = if (kind == Kind.PRIMARY) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(RED, Theme.mix(RED, 0xFF000000.toInt(), 0.22f)))
            g.cornerRadius = dp(c, r).toFloat()
            g
        } else shape(c, fill, r, stroke, 1)

        val v: View
        val ph = dp(c, 16)
        if (ico != 0) {
            val l = hbox(c)
            l.gravity = Gravity.CENTER
            l.addView(icone(c, ico, corTxt, 18))
            val t = text(c, label.uppercase(), 13f, corTxt, true)
            t.letterSpacing = 0.07f
            val tp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tp.leftMargin = dp(c, 8)
            l.addView(t, tp)
            v = l
        } else {
            val t = text(c, label.uppercase(), 13f, corTxt, true)
            t.gravity = Gravity.CENTER
            t.letterSpacing = 0.07f
            v = t
        }
        v.background = ripple(bg)
        v.setPadding(ph, dp(c, 15), ph, dp(c, 15))
        v.isClickable = true
        v.minimumHeight = dp(c, 52)
        v.setOnClickListener { onClick() }
        if (kind == Kind.PRIMARY && Prefs.neon && Build.VERSION.SDK_INT >= 28) {
            v.elevation = dp(c, 6).toFloat()
            v.outlineSpotShadowColor = RED
            v.outlineAmbientShadowColor = RED
        }
        return v
    }

    /** linha de menu: icone, titulo, descricao curta e setinha */
    fun row(c: Context, titulo: String, sub: String, ico: Int = 0, onClick: () -> Unit): View {
        val r = hbox(c)
        r.background = ripple(glass(c))
        val p = dp(c, 14)
        r.setPadding(p, dp(c, 12), p, dp(c, 12))
        if (ico != 0) {
            val ib = caixaIcone(c, ico)
            val ip = LinearLayout.LayoutParams(dp(c, 40), dp(c, 40))
            ip.rightMargin = dp(c, 12)
            r.addView(ib, ip)
        }
        val col = vbox(c)
        col.addView(text(c, titulo, 15f, TEXT, true))
        if (sub.isNotEmpty()) {
            val s = text(c, sub, 12f, MUTED)
            s.setPadding(0, dp(c, 2), 0, 0)
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seta = icone(c, R.drawable.ic_chevron, RED, 20)
        val sp = LinearLayout.LayoutParams(dp(c, 20), dp(c, 20))
        sp.leftMargin = dp(c, 8)
        r.addView(seta, sp)
        r.isClickable = true
        r.setOnClickListener { onClick() }
        return r
    }

    fun toggle(c: Context, titulo: String, sub: String, ligado: Boolean, onChange: (Boolean) -> Unit): View {
        val r = hbox(c)
        r.background = glass(c)
        val p = dp(c, 14)
        r.setPadding(p, dp(c, 11), p, dp(c, 11))
        val col = vbox(c)
        col.addView(text(c, titulo, 15f, TEXT, true))
        if (sub.isNotEmpty()) {
            val s = text(c, sub, 12f, MUTED)
            s.setPadding(0, dp(c, 2), 0, 0)
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(interruptor(c, ligado, onChange))
        return r
    }

    /** interruptor sozinho (usa a cor de destaque) */
    fun interruptor(c: Context, ligado: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(c)
        val est = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(est, intArrayOf(Theme.sobre(RED), 0xFFC9C9D4.toInt()))
        sw.trackTintList = ColorStateList(est, intArrayOf(RED, Theme.alpha(0xFFFFFFFF.toInt(), 0x30)))
        sw.isChecked = ligado
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return sw
    }

    /** par de botoes lado a lado (ex.: TIROU | COLOCOU) com o escolhido em destaque */
    fun segmento(c: Context, opcoes: List<String>, escolhido: Int, onPick: (Int) -> Unit): View {
        val r = hbox(c)
        for ((i, o) in opcoes.withIndex()) {
            val sel = i == escolhido
            val t = text(c, o.uppercase(), 12f, if (sel) Theme.sobre(RED) else MUTED, true)
            t.gravity = Gravity.CENTER
            t.letterSpacing = 0.04f
            t.background = ripple(
                if (sel) shape(c, RED, minOf(Prefs.raio, 14), RED, 1)
                else shape(c, Theme.alpha(0xFFFFFFFF.toInt(), 0x12), minOf(Prefs.raio, 14), Theme.alpha(0xFFFFFFFF.toInt(), 0x26), 1)
            )
            t.setPadding(dp(c, 6), dp(c, 13), dp(c, 6), dp(c, 13))
            t.isClickable = true
            t.setOnClickListener { onPick(i) }
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(c, 8)
            r.addView(t, p)
        }
        return r
    }

    fun field(c: Context, hint: String, numero: Boolean): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.typeface = Theme.fonte(c, true, false)
        e.inputType = if (numero) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.background = shape(c, Theme.alpha(0xFF000000.toInt(), 0x66), minOf(Prefs.raio, 12), Theme.alpha(0xFFFFFFFF.toInt(), 0x30), 1)
        val p = dp(c, 14)
        e.setPadding(p, dp(c, 12), p, dp(c, 12))
        e.setSingleLine(true)
        return e
    }

    /** barra de valor: titulo, valor na cor de destaque e o controle. onMove acontece enquanto arrasta; onFim ao soltar */
    fun slider(c: Context, titulo: String, valor: Int, min: Int, max: Int, sufixo: String, onMove: (Int) -> Unit = {}, onFim: (Int) -> Unit): View {
        val box = vbox(c)
        val topo = hbox(c)
        topo.addView(text(c, titulo, 14f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vt = text(c, "$valor$sufixo", 13f, RED, true, mono = true)
        topo.addView(vt)
        box.addView(topo)
        val sb = SeekBar(c)
        sb.max = max - min
        sb.progress = (valor - min).coerceIn(0, max - min)
        sb.progressTintList = ColorStateList.valueOf(RED)
        sb.thumbTintList = ColorStateList.valueOf(RED)
        sb.progressBackgroundTintList = ColorStateList.valueOf(Theme.alpha(0xFFFFFFFF.toInt(), 0x30))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                vt.text = "${p + min}$sufixo"
                if (fromUser) onMove(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onFim(s.progress + min) }
        })
        box.addView(sb, lp(top = 4, h = dp(c, 36), c = c))
        return box
    }

    /** bolinhas de cor (a ultima abre a escolha de uma cor propria) */
    fun cores(c: Context, lista: List<Theme.Cor>, atual: Int, onPick: (Int) -> Unit, onPropria: () -> Unit): View {
        val col = vbox(c)
        val itens = ArrayList<View>()
        var ehPalheta = false
        for (cor in lista) {
            val sel = (cor.valor and 0xFFFFFF) == (atual and 0xFFFFFF)
            if (sel) ehPalheta = true
            val v = View(c)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(cor.valor)
            g.setStroke(dp(c, if (sel) 3 else 1), if (sel) 0xFFFFFFFF.toInt() else Theme.alpha(0xFFFFFFFF.toInt(), 0x44))
            v.background = g
            v.contentDescription = cor.nome
            v.setOnClickListener { onPick(cor.valor) }
            itens.add(v)
        }
        val p = View(c)
        val gp = GradientDrawable()
        gp.shape = GradientDrawable.OVAL
        gp.setColor(if (ehPalheta) Theme.alpha(0xFFFFFFFF.toInt(), 0x10) else atual)
        gp.setStroke(dp(c, if (ehPalheta) 1 else 3), if (ehPalheta) Theme.alpha(0xFFFFFFFF.toInt(), 0x66) else 0xFFFFFFFF.toInt())
        p.background = gp
        p.contentDescription = "Outra cor"
        p.setOnClickListener { onPropria() }
        itens.add(p)

        val porLinha = 5
        var i = 0
        while (i < itens.size) {
            val linha = hbox(c)
            for (k in i until minOf(i + porLinha, itens.size)) {
                val lpv = LinearLayout.LayoutParams(dp(c, 40), dp(c, 40))
                lpv.rightMargin = dp(c, 12)
                linha.addView(itens[k], lpv)
            }
            col.addView(linha, lp(top = if (i == 0) 0 else 12, c = c))
            i += porLinha
        }
        return col
    }

    /** quadrado da tela inicial: icone, nome e estado */
    fun tile(c: Context, ico: Int, titulo: String, estado: String, ligado: Boolean?, onClick: () -> Unit): View {
        val col = vbox(c)
        col.background = ripple(glass(c, forte = ligado == true))
        val p = dp(c, 14)
        col.setPadding(p, p, p, p)
        col.minimumHeight = dp(c, 118)
        val topo = hbox(c)
        topo.addView(caixaIcone(c, ico, ligado == true))
        topo.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        if (ligado != null) {
            val dot = View(c)
            dot.background = shape(c, if (ligado) OK else Theme.alpha(0xFFFFFFFF.toInt(), 0x40), 8)
            topo.addView(dot, LinearLayout.LayoutParams(dp(c, 8), dp(c, 8)))
        }
        col.addView(topo)
        col.addView(text(c, titulo, 15f, TEXT, true), lp(top = 12, c = c))
        col.addView(text(c, estado, 11.5f, if (ligado == true) RED else MUTED, ligado == true, mono = true), lp(top = 3, c = c))
        col.isClickable = true
        col.setOnClickListener { onClick() }
        return col
    }

    fun toast(c: Context, msg: String) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    // ---------------- caixas de dialogo ----------------
    private fun builder(c: Context) = AlertDialog.Builder(c, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

    fun dialogNumero(c: Context, titulo: String, dica: String, min: Int, max: Int, atual: Int, onOk: (Int) -> Unit) {
        val e = field(c, dica, true)
        e.setText(atual.toString())
        e.setSelection(e.text.length)
        val box = vbox(c)
        val p = dp(c, 20)
        box.setPadding(p, dp(c, 8), p, 0)
        box.addView(text(c, "de $min a $max", 12f, MUTED))
        box.addView(e, lp(top = 8, c = c))
        builder(c).setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = e.text.toString().trim().toIntOrNull()
                if (v == null || v < min || v > max) toast(c, "Use um numero de $min a $max") else onOk(v)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogTexto(c: Context, titulo: String, dica: String, onOk: (String) -> Unit) {
        val e = field(c, dica, false)
        val box = vbox(c)
        val p = dp(c, 20)
        box.setPadding(p, dp(c, 8), p, 0)
        box.addView(e)
        builder(c).setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogEscolha(c: Context, titulo: String, opcoes: List<String>, onPick: (Int) -> Unit) {
        builder(c).setTitle(titulo)
            .setItems(opcoes.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogConfirma(c: Context, titulo: String, msg: String, ok: String = "SIM", onOk: () -> Unit) {
        builder(c).setTitle(titulo).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("CANCELAR", null)
            .show()
    }
}
