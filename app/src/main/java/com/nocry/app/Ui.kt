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
        val display = bold || mono
        // texto corrido (explicacoes) ganha mais entrelinha: em paragrafo, linha colada cansa a leitura
        t.setLineSpacing(0f, if (display || code) 1.12f else 1.3f)
        t.typeface = if (code) Theme.codigo(c) else Theme.fonte(c, display, bold)
        if (!code && Prefs.maiusc) {
            t.setAllCaps(true)
            // caixa alta sem espaco entre as letras fica apertada; titulo usa o espacamento do pacote,
            // texto corrido um pouco menos (senao o paragrafo fica largo demais)
            t.letterSpacing = if (display) Theme.pack().esp else 0.03f
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
        val p = dp(c, 18)
        l.setPadding(p, p, p, p)
        return l
    }

    /** etiqueta pequena na cor 1 em cima de cada bloco */
    fun label(c: Context, s: String): TextView {
        val t = text(c, s, 11f, RED, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.2f else 0.06f
        return t
    }

    /** etiqueta com um iconezinho do lado, no mesmo padrao usado no Bypass e na Conexao */
    fun labelIcone(c: Context, iconRes: Int, s: String): View {
        val r = hbox(c)
        val caixa = caixaIcone(c, iconRes, false, 26)
        val lp = LinearLayout.LayoutParams(dp(c, 26), dp(c, 26))
        lp.rightMargin = dp(c, 8)
        r.addView(caixa, lp)
        r.addView(label(c, s))
        return r
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

    /** bolinha (com um halo suave) + texto de estado: "ADB · CONECTADO" */
    fun status(c: Context, nome: String, valor: String, ok: Boolean?): View {
        val r = hbox(c)
        val cor = when (ok) { true -> OK; false -> BAD; null -> WARN }
        // "LED" com halo: um aro translucido por baixo do ponto solido, da sensacao de luz acesa
        // em vez de so uma bolinha chapada - detalhe pequeno, mas usado em todo app profissional.
        val ledBox = FrameLayout(c)
        val halo = View(c)
        halo.background = shape(c, Theme.alpha(cor, 0x33), 7)
        ledBox.addView(halo, FrameLayout.LayoutParams(dp(c, 14), dp(c, 14), Gravity.CENTER))
        val dot = View(c)
        dot.background = shape(c, cor, 4)
        ledBox.addView(dot, FrameLayout.LayoutParams(dp(c, 8), dp(c, 8), Gravity.CENTER))
        r.addView(ledBox, LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)))
        val n = text(c, nome, 12.5f, MUTED, mono = true)
        val np = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        np.leftMargin = dp(c, 9)
        r.addView(n, np)
        r.addView(text(c, valor, 12.5f, cor, true, mono = true))
        r.setPadding(0, dp(c, 5), 0, dp(c, 5))
        return r
    }

    /** chip curto de status, em grade horizontal (ex.: 3 lado a lado no cartao principal) */
    fun chip(c: Context, nome: String, valor: String, ok: Boolean?): View {
        val col = vbox(c)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.background = shape(c, Theme.alpha(Color.WHITE, 0x0A), 14, Theme.alpha(Color.WHITE, 0x1C), 1)
        val cor = when (ok) { true -> OK; false -> BAD; null -> WARN }
        val dot = View(c)
        dot.background = shape(c, cor, 4)
        val dp2 = LinearLayout.LayoutParams(dp(c, 7), dp(c, 7))
        dp2.gravity = Gravity.CENTER_HORIZONTAL
        dp2.bottomMargin = dp(c, 7)
        col.addView(dot, dp2)
        val n = text(c, nome, 9f, MUTED, mono = true)
        n.gravity = Gravity.CENTER
        n.letterSpacing = 0.06f
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(n)
        val v = text(c, valor, 11.5f, cor, true, mono = true)
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(v, lp(top = 2, c = c))
        col.setPadding(dp(c, 6), dp(c, 11), dp(c, 6), dp(c, 11))
        return col
    }

    /** linha com varios chips repartidos em partes iguais, com espaco entre eles */
    fun chips(c: Context, vararg itens: View): View {
        val r = hbox(c)
        for ((i, v) in itens.withIndex()) {
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(c, 8)
            r.addView(v, p)
        }
        return r
    }

    /** caixinha de informacao: icone + rotulo pequeno em cima, valor embaixo (usada em grade 2x2) */
    fun infoBox(c: Context, ico: Int, rotulo: String, valor: String, cor: Int = TEXT): View {
        val col = vbox(c)
        col.background = shape(c, Theme.alpha(Color.WHITE, 0x0A), 14, Theme.alpha(Color.WHITE, 0x1C), 1)
        col.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 12))
        val topo = hbox(c)
        topo.addView(icone(c, ico, MUTED, 13))
        val r = text(c, rotulo, 8.5f, MUTED, true, mono = true)
        r.maxLines = 1
        r.ellipsize = android.text.TextUtils.TruncateAt.END
        val rp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rp.leftMargin = dp(c, 6)
        topo.addView(r, rp)
        col.addView(topo)
        val v = text(c, valor, 12f, cor, true)
        v.maxLines = 1
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(v, lp(top = 7, c = c))
        return col
    }

    // ------------------------------------------------------------------ pecas de tela (padrao novo)
    /**
     * cartao principal de cada modo: icone grande, rotulo, estado grande (verde quando ligado) e uma linha
     * explicando. Devolve o cartao pra tela poder pendurar chips/infos embaixo.
     */
    fun hero(c: Context, ico: Int, rotulo: String, estado: String, ligado: Boolean?, sub: String): LinearLayout {
        val card = card(c, forte = true, ligado = ligado == true)
        val topo = hbox(c)
        topo.addView(caixaIcone(c, ico, ligado == true, 58), LinearLayout.LayoutParams(dp(c, 58), dp(c, 58)))
        val col = vbox(c)
        col.addView(label(c, rotulo))
        val linha = hbox(c)
        val cor = when (ligado) { true -> OK; false -> Theme.alpha(Color.WHITE, 0x55); null -> WARN }
        val dot = View(c)
        dot.background = shape(c, cor, 5)
        val dlp = LinearLayout.LayoutParams(dp(c, 9), dp(c, 9))
        dlp.rightMargin = dp(c, 9)
        linha.addView(dot, dlp)
        linha.addView(text(c, estado, 21f, if (ligado == true) OK else TEXT, true))
        col.addView(linha, lp(top = 3, c = c))
        if (sub.isNotEmpty()) col.addView(text(c, sub, 10.5f, MUTED), lp(top = 3, c = c))
        val cp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cp.leftMargin = dp(c, 14)
        topo.addView(col, cp)
        card.addView(topo)
        return card
    }

    /** linha "quando isso → acontece aquilo" (como o modo funciona) */
    fun regra(c: Context, quando: String, entao: String): View {
        val r = hbox(c)
        r.setPadding(0, dp(c, 10), 0, dp(c, 10))
        val q = text(c, quando, 10.5f, TEXT, true, mono = true)
        q.background = shape(c, Theme.alpha(RED, 0x1A), 8, Theme.alpha(RED, 0x55), 1)
        q.setPadding(dp(c, 9), dp(c, 5), dp(c, 9), dp(c, 5))
        q.maxLines = 1
        r.addView(q)
        val sp = LinearLayout.LayoutParams(dp(c, 14), dp(c, 14))
        sp.leftMargin = dp(c, 8); sp.rightMargin = dp(c, 8)
        r.addView(icone(c, R.drawable.ic_chevron, MUTED, 14), sp)
        r.addView(text(c, entao, 10.5f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    /** fundo de toque sem cor (so o brilho do dedo), pra itens que ficam dentro de um cartao */
    private fun toqueLimpo(): Drawable =
        RippleDrawable(ColorStateList.valueOf(Theme.alpha(Color.WHITE, 0x22)), null, android.graphics.drawable.ColorDrawable(Color.WHITE))

    /** item de lista sem fundo proprio (vai dentro de grupo()): icone, titulo, descricao, valor em pilula e seta */
    fun item(c: Context, titulo: String, sub: String = "", ico: Int = 0, valor: String? = null, onClick: (() -> Unit)? = null): View {
        val r = hbox(c)
        r.minimumHeight = dp(c, 52)
        r.setPadding(0, dp(c, 10), 0, dp(c, 10))
        if (ico != 0) {
            val ip = LinearLayout.LayoutParams(dp(c, 34), dp(c, 34))
            ip.rightMargin = dp(c, 12)
            r.addView(caixaIcone(c, ico, false, 34), ip)
        }
        val col = vbox(c)
        col.addView(text(c, titulo, 13f, TEXT, true))
        if (sub.isNotEmpty()) col.addView(text(c, sub, 10.5f, MUTED), lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (valor != null) {
            val v = text(c, valor, 11f, RED, true, mono = true)
            v.background = shape(c, Theme.alpha(RED, 0x1A), 8)
            v.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5))
            v.maxLines = 1
            val vp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            vp.leftMargin = dp(c, 8)
            r.addView(v, vp)
        }
        if (onClick != null) {
            val sp = LinearLayout.LayoutParams(dp(c, 16), dp(c, 16))
            sp.leftMargin = dp(c, 8)
            r.addView(icone(c, R.drawable.ic_chevron, MUTED, 16), sp)
            r.background = toqueLimpo()
            clique(r, onClick)
        }
        return r
    }

    /** chave liga/desliga sem fundo proprio (vai dentro de grupo()) */
    fun itemChave(c: Context, titulo: String, sub: String, ligado: Boolean, onChange: (Boolean) -> Unit): View {
        val r = hbox(c)
        r.minimumHeight = dp(c, 52)
        r.setPadding(0, dp(c, 10), 0, dp(c, 10))
        val col = vbox(c)
        col.addView(text(c, titulo, 13f, TEXT, true))
        if (sub.isNotEmpty()) col.addView(text(c, sub, 10.5f, MUTED), lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ch = interruptor(c, ligado, onChange)
        val chp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chp.leftMargin = dp(c, 10)
        r.addView(ch, chp)
        return r
    }

    /** grupo de itens: rotulo em cima e um cartao so, com os itens separados por linhas finas */
    fun grupo(c: Context, titulo: String?, itens: List<View>): View {
        val col = vbox(c)
        if (titulo != null) col.addView(label(c, titulo))
        val card = vbox(c)
        card.background = glass(c)
        card.setPadding(dp(c, 16), dp(c, 2), dp(c, 16), dp(c, 2))
        for ((i, v) in itens.withIndex()) {
            if (i > 0) {
                val d = View(c)
                d.setBackgroundColor(Theme.alpha(Color.WHITE, 0x14))
                card.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            card.addView(v)
        }
        col.addView(card, lp(top = if (titulo != null) 8 else 0, c = c))
        return col
    }

    /** instrucao em passinhos numerados (quadradinho com o numero + texto), em vez de um paragrafo corrido */
    fun lista(c: Context, itens: List<String>): View {
        val col = vbox(c)
        for ((i, s) in itens.withIndex()) {
            val r = hbox(c)
            r.gravity = Gravity.TOP
            val n = text(c, (i + 1).toString(), 9.5f, Theme.sobreGrad(), true, mono = true)
            n.gravity = Gravity.CENTER
            n.background = Botao(c, "grad", 5)
            val np = LinearLayout.LayoutParams(dp(c, 17), dp(c, 17))
            np.rightMargin = dp(c, 10)
            r.addView(n, np)
            r.addView(text(c, s, 10.5f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(r, lp(top = if (i == 0) 0 else 8, c = c))
        }
        return col
    }

    enum class Dica { INFO, AVISO, OK }

    /** caixinha de dica/aviso: fundo e borda na cor do tipo, icone e o texto */
    fun dica(c: Context, s: String, tipo: Dica = Dica.INFO): View {
        val cor = when (tipo) { Dica.INFO -> 0xFF7FC8FF.toInt(); Dica.AVISO -> WARN; Dica.OK -> OK }
        val ico = when (tipo) { Dica.INFO -> R.drawable.ic_info; Dica.AVISO -> R.drawable.ic_warn; Dica.OK -> R.drawable.ic_check }
        val r = hbox(c)
        r.gravity = Gravity.TOP
        r.background = shape(c, Theme.alpha(cor, 0x18), 12, Theme.alpha(cor, 0x4D), 1)
        r.setPadding(dp(c, 11), dp(c, 10), dp(c, 12), dp(c, 10))
        val ip = LinearLayout.LayoutParams(dp(c, 15), dp(c, 15))
        ip.rightMargin = dp(c, 9)
        r.addView(icone(c, ico, cor, 15), ip)
        r.addView(text(c, s, 10.5f, Theme.mix(TEXT, cor, 0.18f)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    /**
     * item da linha do tempo (passo a passo): bolinha numerada (vira um check quando feito) com uma
     * linha ligando ao proximo passo, titulo + etiqueta de estado e, quando aberto, o conteudo num cartao.
     * estado: 0 = feito, 1 = atual (o proximo a fazer), 2 = depois.
     * onToque != null deixa o cabecalho tocavel (abre/fecha o passo).
     */
    fun passo(c: Context, numero: Int, titulo: String, estado: Int, conteudo: View?, ultimo: Boolean, onToque: (() -> Unit)? = null): View {
        val r = LinearLayout(c)
        r.orientation = LinearLayout.HORIZONTAL
        val corEstado = when (estado) { 0 -> OK; 1 -> RED; else -> MUTED }

        // coluna da esquerda: bolinha + linha ate o proximo passo
        val esq = vbox(c)
        esq.gravity = Gravity.CENTER_HORIZONTAL
        val bola = FrameLayout(c)
        bola.background = when (estado) {
            0 -> shape(c, Theme.alpha(OK, 0x24), 14, Theme.alpha(OK, 0x99), 1)
            1 -> Botao(c, "grad", 14)
            else -> shape(c, Theme.alpha(Color.WHITE, 0x08), 14, LINE, 1)
        }
        if (estado == 0) {
            bola.addView(icone(c, R.drawable.ic_check, OK, 14), FrameLayout.LayoutParams(dp(c, 14), dp(c, 14), Gravity.CENTER))
        } else {
            val n = text(c, numero.toString(), 11.5f, if (estado == 1) Theme.sobreGrad() else MUTED, true, mono = true)
            n.gravity = Gravity.CENTER
            bola.addView(n, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        esq.addView(bola, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)))
        if (!ultimo) {
            val linha = View(c)
            linha.background = shape(c, if (estado == 0) Theme.alpha(OK, 0x55) else LINE, 1)
            val lpl = LinearLayout.LayoutParams(dp(c, 2), 0, 1f)
            lpl.topMargin = dp(c, 4)
            esq.addView(linha, lpl)
        }
        // altura MATCH_PARENT: a linha estica ate o fim do conteudo do passo (o LinearLayout iguala a altura)
        r.addView(esq, LinearLayout.LayoutParams(dp(c, 28), ViewGroup.LayoutParams.MATCH_PARENT))

        // coluna da direita: titulo + estado (+ seta) e o conteudo
        val dir = vbox(c)
        val cab = hbox(c)
        cab.minimumHeight = dp(c, 28)
        val t = text(c, titulo, 13f, if (estado == 2) MUTED else TEXT, true)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        cab.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val selo = text(c, when (estado) { 0 -> "feito"; 1 -> "agora"; else -> "depois" }, 8.5f, corEstado, true, mono = true)
        selo.background = shape(c, Theme.alpha(corEstado, 0x1E), 7)
        selo.setPadding(dp(c, 7), dp(c, 3), dp(c, 7), dp(c, 3))
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.leftMargin = dp(c, 8)
        cab.addView(selo, slp)
        if (onToque != null) {
            val seta = icone(c, R.drawable.ic_chevron, MUTED, 16)
            seta.rotation = if (conteudo != null) 90f else 0f
            val sl = LinearLayout.LayoutParams(dp(c, 16), dp(c, 16))
            sl.leftMargin = dp(c, 6)
            cab.addView(seta, sl)
            clique(cab, onToque)
        }
        dir.addView(cab)
        if (conteudo != null) {
            val cartao = card(c, forte = estado == 1)
            cartao.addView(conteudo)
            dir.addView(cartao, lp(top = 8, c = c))
        }
        if (!ultimo) dir.setPadding(0, 0, 0, dp(c, 18))
        val dlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        dlp.leftMargin = dp(c, 12)
        r.addView(dir, dlp)
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

    /** quadrado da tela inicial: icone, nome, descricao e um selo de estado no rodape */
    fun tile(c: Context, ico: Int, titulo: String, desc: String, estado: String, ligado: Boolean?, onClick: () -> Unit): View {
        val col = vbox(c)
        col.background = ripple(c, glass(c, ligado = ligado == true), Painel(c, mascara = true))
        val p = dp(c, 14)
        col.setPadding(p, p, p, dp(c, 12))
        col.minimumHeight = dp(c, 134)
        col.addView(caixaIcone(c, ico, ligado == true))
        col.addView(text(c, titulo, 13.5f, TEXT, true), lp(top = 11, c = c))
        if (desc.isNotEmpty()) {
            val d = text(c, desc, 10.5f, MUTED)
            d.maxLines = 2
            col.addView(d, lp(top = 3, c = c))
        }
        // espaco flexivel empurra o selo pro rodape - assim ele nunca fica em cima do icone
        // ou do titulo, por mais longo que o texto do estado seja ou por mais estreito que o tile fique.
        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        val cor = if (ligado == true) RED else MUTED
        val selo = text(c, estado, 9f, cor, true, mono = true)
        selo.letterSpacing = if (Prefs.maiusc) 0.08f else 0.02f
        selo.maxLines = 1
        selo.ellipsize = android.text.TextUtils.TruncateAt.END
        selo.background = shape(c, Theme.alpha(cor, if (ligado == true) 0x26 else 0x16), 7)
        selo.setPadding(dp(c, 7), dp(c, 3), dp(c, 7), dp(c, 3))
        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.topMargin = dp(c, 9)
        col.addView(selo, sp)
        clique(col, onClick)
        return col
    }

    /** maiusculo o texto quando o modo "tudo maiusculo" esta ligado; usado onde o texto nao passa pelo text() */
    private fun up(s: String): String = if (Prefs.maiusc) s.uppercase() else s

    fun toast(c: Context, msg: String) { Toast.makeText(c, up(msg), Toast.LENGTH_SHORT).show() }

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
        builder(c).setTitle(up(titulo)).setView(box)
            .setPositiveButton(up("OK")) { _, _ ->
                val v = e.text.toString().trim().toIntOrNull()
                if (v == null || v < min || v > max) toast(c, "Use um numero de $min a $max") else onOk(v)
            }
            .setNegativeButton(up("CANCELAR"), null)
            .show()
    }

    fun dialogTexto(c: Context, titulo: String, dica: String, onOk: (String) -> Unit) {
        val e = field(c, dica, false)
        val box = vbox(c)
        val p = dp(c, 20)
        box.setPadding(p, dp(c, 8), p, 0)
        box.addView(e)
        builder(c).setTitle(up(titulo)).setView(box)
            .setPositiveButton(up("OK")) { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton(up("CANCELAR"), null)
            .show()
    }

    fun dialogEscolha(c: Context, titulo: String, opcoes: List<String>, onPick: (Int) -> Unit) {
        builder(c).setTitle(up(titulo))
            .setItems(opcoes.map { up(it) }.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton(up("CANCELAR"), null)
            .show()
    }

    fun dialogConfirma(c: Context, titulo: String, msg: String, ok: String = "SIM", onOk: () -> Unit) {
        builder(c).setTitle(up(titulo)).setMessage(up(msg))
            .setPositiveButton(up(ok)) { _, _ -> onOk() }
            .setNegativeButton(up("CANCELAR"), null)
            .show()
    }
}
