package com.nocry.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Pecas visuais do NOCRY. Cores, degrade, formato dos cartoes e fonte vem do ESTUDIO. */
object Ui {
    val BG: Int get() = Theme.base
    val LINE: Int get() = Theme.alpha(Color.WHITE, 0x26)
    /** cor 1 do degrade */
    val RED: Int get() = Theme.accent
    /** cor 2 do degrade */
    val ACC2: Int get() = Theme.accent2
    /** vermelho fixo pra "desligado / problema" (nao muda com o tema) */
    val BAD = 0xFFFF5470.toInt()
    val RED_DARK: Int get() = Theme.alpha(0xFFFF5470.toInt(), 0x2E)
    val TEXT = 0xFFF4F4F7.toInt()
    val MUTED = 0xFFA9A9B8.toInt()
    val OK = 0xFF3DDC84.toInt()
    val WARN = 0xFFF2B84B.toInt()

    enum class Kind { PRIMARY, DANGER, GHOST }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    private val hud: Boolean get() = Prefs.cartao == "hud"

    /** forma simples arredondada (no estilo HUD os cantos ficam quase retos) */
    fun shape(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, if (hud) minOf(radiusDp, 3) else radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor)
        return g
    }

    /** cartao do estilo escolhido (vidro, contorno, solido ou HUD) */
    fun glass(c: Context, raio: Int = Prefs.raio, forte: Boolean = false, ligado: Boolean = false): Drawable =
        Painel(c, Prefs.cartao, forte, ligado, raio)

    fun ripple(c: Context, bg: Drawable, mask: Drawable? = null): Drawable =
        RippleDrawable(ColorStateList.valueOf(Theme.alpha(Color.WHITE, 0x30)), bg, mask ?: Painel(c, mascara = true))

    /** vibracao curta ao tocar (se o usuario ligou no ESTUDIO) */
    fun toca(v: View) {
        if (Prefs.vibrar) try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) {}
    }

    fun clique(v: View, acao: () -> Unit) {
        v.isClickable = true
        v.setOnClickListener { toca(v); acao() }
    }

    // ------------------------------------------------------------------ texto
    /**
     * bold ou mono = titulo/rotulo (fonte "display" do tema); o resto e texto corrido.
     * code = fonte de largura fixa e sem maiusculas (usado nos logs).
     */
    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false, code: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Theme.escalaTexto() * (if (code) 0.95f else 1f))
        t.setTextColor(color)
        t.setLineSpacing(0f, 1.12f)
        val display = bold || mono
        t.typeface = if (code) Theme.codigo(c) else Theme.fonte(c, display, bold)
        if (!code && Prefs.maiusc) {
            t.setAllCaps(true)
            if (display) t.letterSpacing = Theme.pack().esp
        }
        if (Theme.temMidiaDeFundo()) t.setShadowLayer(dp(c, 3).toFloat(), 0f, dp(c, 1).toFloat(), 0x99000000.toInt())
        return t
    }

    /** texto com degrade cor 1 -> cor 2 */
    fun gradTexto(c: Context, s: String, sp: Float, bold: Boolean = true): TextView {
        val t = text(c, s, sp, TEXT, bold, mono = true)
        val str = if (Prefs.maiusc) s.uppercase() else s
        val w = t.paint.measureText(str)
        t.paint.shader = android.graphics.LinearGradient(0f, 0f, maxOf(w, 1f), 0f, Theme.accent, Theme.accent2, android.graphics.Shader.TileMode.CLAMP)
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

    /** cartao do tema onde ficam os itens */
    fun card(c: Context, forte: Boolean = false, ligado: Boolean = false): LinearLayout {
        val l = vbox(c)
        l.background = glass(c, forte = forte, ligado = ligado)
        val p = dp(c, 16)
        l.setPadding(p, p, p, p)
        return l
    }

    /** etiqueta pequena na cor 1 em cima de cada bloco */
    fun label(c: Context, s: String): TextView {
        val t = text(c, s, 11f, RED, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.2f else 0.06f
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

    /** quadradinho com um icone dentro; aceso fica em degrade com brilho */
    fun caixaIcone(c: Context, res: Int, ativo: Boolean = false, tamDp: Int = 40): View {
        val f = FrameLayout(c)
        f.background = Botao(c, if (ativo) "icoOn" else "ico")
        val tam = (tamDp * 0.5f).toInt()
        val ic = icone(c, res, if (ativo) Color.WHITE else 0xFFB9B9C8.toInt(), tam)
        f.addView(ic, FrameLayout.LayoutParams(dp(c, tam), dp(c, tam), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(c, tamDp), dp(c, tamDp))
        return f
    }

    /** selo pequeno (DONO, STAFF, AUTO...) */
    fun selo(c: Context, s: String, cor: Int = RED, cheio: Boolean = true): TextView {
        val t = text(c, s.uppercase(), 10f, if (cheio) cor else MUTED, true, mono = true)
        t.letterSpacing = 0.12f
        t.background = shape(c, Theme.alpha(cor, if (cheio) 0x22 else 0x14), 8, Theme.alpha(cor, if (cheio) 0x99 else 0x55), 1)
        t.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6))
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
        val corTxt = when (kind) {
            Kind.PRIMARY -> Theme.sobreGrad()
            Kind.DANGER -> 0xFFFF8E9E.toInt()
            Kind.GHOST -> TEXT
        }
        val tipo = when (kind) { Kind.PRIMARY -> "grad"; Kind.DANGER -> "danger"; Kind.GHOST -> "ghost" }
        val bg = Botao(c, tipo)
        val ph = dp(c, 16)
        val l = hbox(c)
        l.gravity = Gravity.CENTER
        if (ico != 0) l.addView(icone(c, ico, corTxt, 18))
        val t = text(c, label.uppercase(), 13f, corTxt, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.1f else 0.02f
        t.gravity = Gravity.CENTER
        val tp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (ico != 0) tp.leftMargin = dp(c, 8)
        l.addView(t, tp)
        l.background = ripple(c, bg, Botao(c, tipo, mascara = true))
        l.setPadding(ph, dp(c, 14), ph, dp(c, 14))
        l.minimumHeight = dp(c, 52)
        clique(l, onClick)
        if (kind == Kind.PRIMARY && Prefs.brilho > 0 && Build.VERSION.SDK_INT >= 28) {
            l.elevation = dp(c, 4 + Prefs.brilho / 12).toFloat()
            l.outlineSpotShadowColor = RED
            l.outlineAmbientShadowColor = RED
        }
        return l
    }

    /** linha de menu: icone, titulo, descricao curta e setinha */
    fun row(c: Context, titulo: String, sub: String, ico: Int = 0, onClick: () -> Unit): View {
        val r = hbox(c)
        r.background = ripple(c, glass(c), Painel(c, mascara = true))
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
        clique(r, onClick)
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

    /** interruptor sozinho em degrade */
    fun interruptor(c: Context, ligado: Boolean, onChange: (Boolean) -> Unit): View = Chave(c, ligado, onChange)

    /** opcoes lado a lado (ex.: TIROU | COLOCOU) com a escolhida em degrade */
    fun segmento(c: Context, opcoes: List<String>, escolhido: Int, onPick: (Int) -> Unit): View {
        val r = hbox(c)
        r.background = Botao(c, "ghost")
        r.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        for ((i, o) in opcoes.withIndex()) {
            val sel = i == escolhido
            val t = text(c, o.uppercase(), 12f, if (sel) Theme.sobreGrad() else MUTED, true, mono = true)
            t.gravity = Gravity.CENTER
            t.letterSpacing = if (Prefs.maiusc) 0.06f else 0.01f
            if (sel) t.background = Botao(c, "grad", minOf(Prefs.raio, 14))
            t.setPadding(dp(c, 6), dp(c, 11), dp(c, 6), dp(c, 11))
            clique(t) { onPick(i) }
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            r.addView(t, p)
        }
        return r
    }

    fun field(c: Context, hint: String, numero: Boolean): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Theme.escalaTexto())
        e.typeface = Theme.fonte(c, true, false)
        e.inputType = if (numero) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.background = Botao(c, "campo", minOf(Prefs.raio, 14))
        val p = dp(c, 14)
        e.setPadding(p, dp(c, 12), p, dp(c, 12))
        e.setSingleLine(true)
        return e
    }

    /** barra de valor: titulo, valor na cor 1 e o controle em degrade. onMove acontece enquanto arrasta; onFim ao soltar */
    fun slider(c: Context, titulo: String, valor: Int, min: Int, max: Int, sufixo: String, onMove: (Int) -> Unit = {}, onFim: (Int) -> Unit): View {
        val box = vbox(c)
        val topo = hbox(c)
        topo.addView(text(c, titulo, 13.5f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vt = text(c, "$valor$sufixo", 12.5f, RED, true, mono = true)
        topo.addView(vt)
        box.addView(topo)
        val sb = SeekBar(c)
        sb.max = max - min
        sb.progress = (valor - min).coerceIn(0, max - min)
        val alt = dp(c, 6)
        val trilho = GradientDrawable()
        trilho.setColor(Theme.alpha(Color.WHITE, 0x1E))
        trilho.cornerRadius = alt / 2f
        trilho.setSize(1, alt)
        val cheio = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.accent, Theme.accent2))
        cheio.cornerRadius = alt / 2f
        cheio.setSize(1, alt)
        val camadas = LayerDrawable(arrayOf<Drawable>(trilho, ClipDrawable(cheio, Gravity.START, ClipDrawable.HORIZONTAL)))
        camadas.setId(0, android.R.id.background)
        camadas.setId(1, android.R.id.progress)
        sb.progressDrawable = camadas
        val bola = GradientDrawable()
        bola.shape = GradientDrawable.OVAL
        bola.setColor(Color.WHITE)
        bola.setStroke(dp(c, 4), Theme.alpha(Theme.accent, 0x70))
        bola.setSize(dp(c, 22), dp(c, 22))
        sb.thumb = bola
        sb.splitTrack = false
        sb.setPadding(dp(c, 11), 0, dp(c, 11), 0)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                vt.text = "${p + min}$sufixo"
                if (fromUser) onMove(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onFim(s.progress + min) }
        })
        box.addView(sb, lp(top = 6, h = dp(c, 34), c = c))
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
            g.setStroke(dp(c, if (sel) 3 else 1), if (sel) Color.WHITE else Theme.alpha(Color.WHITE, 0x44))
            v.background = g
            v.contentDescription = cor.nome
            clique(v) { onPick(cor.valor) }
            itens.add(v)
        }
        val p = View(c)
        val gp = GradientDrawable()
        gp.shape = GradientDrawable.OVAL
        gp.setColor(if (ehPalheta) Theme.alpha(Color.WHITE, 0x10) else atual)
        gp.setStroke(dp(c, if (ehPalheta) 1 else 3), if (ehPalheta) Theme.alpha(Color.WHITE, 0x66) else Color.WHITE)
        p.background = gp
        p.contentDescription = "Outra cor"
        clique(p) { onPropria() }
        itens.add(p)

        val porLinha = 6
        var i = 0
        while (i < itens.size) {
            val linha = hbox(c)
            for (k in i until minOf(i + porLinha, itens.size)) {
                val lpv = LinearLayout.LayoutParams(dp(c, 36), dp(c, 36))
                lpv.rightMargin = dp(c, 12)
                linha.addView(itens[k], lpv)
            }
            col.addView(linha, lp(top = if (i == 0) 0 else 12, c = c))
            i += porLinha
        }
        return col
    }

    /** quadrado da tela inicial: icone, nome, descricao e estado */
    fun tile(c: Context, ico: Int, titulo: String, desc: String, estado: String, ligado: Boolean?, onClick: () -> Unit): View {
        val col = vbox(c)
        col.background = ripple(c, glass(c, ligado = ligado == true), Painel(c, mascara = true))
        val p = dp(c, 14)
        col.setPadding(p, p, p, dp(c, 12))
        col.minimumHeight = dp(c, 140)
        col.addView(caixaIcone(c, ico, ligado == true))
        col.addView(text(c, titulo, 14f, TEXT, true), lp(top = 11, c = c))
        if (desc.isNotEmpty()) {
            val d = text(c, desc, 11f, MUTED)
            d.maxLines = 2
            col.addView(d, lp(top = 3, c = c))
        }
        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        val st = hbox(c)
        val dot = View(c)
        dot.background = shape(c, if (ligado == true) RED else Theme.alpha(Color.WHITE, 0x40), 8)
        st.addView(dot, LinearLayout.LayoutParams(dp(c, 7), dp(c, 7)))
        val tx = text(c, estado, 10f, if (ligado == true) RED else MUTED, true, mono = true)
        tx.letterSpacing = if (Prefs.maiusc) 0.1f else 0.02f
        tx.maxLines = 1
        tx.ellipsize = android.text.TextUtils.TruncateAt.END
        val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp.leftMargin = dp(c, 7)
        st.addView(tx, tp)
        col.addView(st, lp(top = 9, c = c))
        clique(col, onClick)
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
