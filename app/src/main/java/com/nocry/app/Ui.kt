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

    private var fatorTela = 0f

    /**
     * telas estreitas (aparelho pequeno ou "tamanho da tela" do Android aumentado) encolhem medidas e textos juntos,
     * pra tudo caber como numa tela normal em vez de quebrar linha e cortar. Tela normal ou larga = 1 (nao muda nada).
     */
    fun fator(c: Context): Float {
        if (fatorTela == 0f) {
            val dm = c.resources.displayMetrics
            val larguraDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
            fatorTela = (larguraDp / 384f).coerceIn(0.8f, 1f)
        }
        return fatorTela
    }

    /** estilo dos botões em teste: "" = atual, "1" compacto, "2" lista, "3" sólido */
    @JvmStatic var estiloBt = ""
    private val compacto get() = estiloBt.isNotEmpty() && pele.isEmpty() && formato == 0 && estiloBt !in listOf("T", "U", "V", "W", "X", "Y")

    /** formato de botão em prévia (só no CI), desenhado por FormatoBotao: L 3D de jogo, M neon, N hexágono, O inclinado, P mira */
    val formato: Int get() = when (estiloBt) { "L" -> 12; "M" -> 11; "N" -> 7; "O" -> 8; "P" -> 10; else -> 0 }
    /** cor do texto em cima do botão grande (principal) */
    fun corSobreGrande(): Int = if (formato > 0) (if (Formatos.grandeClaro(formato)) ESCURO else Color.WHITE) else Theme.sobreGrad()

    // ---------------- estilos de botão em prévia (só no CI; sem prévia tudo fica igual ao de sempre) ----------------
    /** "H" claro (botões brancos cheios), "I" cores (cada botão com a sua cor), "J" gamer (cantos cortados), "K" escuro limpo (sem borda) */
    val pele: String get() = estiloBt.take(1).takeIf { it == "H" || it == "I" || it == "J" || it == "K" } ?: ""
    private val ESCURO = 0xFF14161E.toInt()

    /** cor própria de cada ícone no estilo "cores" */
    fun corIcone(res: Int): Int? = when (res) {
        R.drawable.ic_info -> 0xFF4FC3F7.toInt()
        R.drawable.ic_trofeu, R.drawable.ic_crown -> 0xFFFFC94D.toInt()
        R.drawable.ic_sparkle -> 0xFFB388FF.toInt()
        R.drawable.ic_log -> 0xFF4ADE80.toInt()
        R.drawable.ic_gamepad -> 0xFFFF6B81.toInt()
        R.drawable.ic_bolt -> 0xFFFFD54F.toInt()
        R.drawable.ic_video -> 0xFF26C6DA.toInt()
        R.drawable.ic_chip -> 0xFF40C4FF.toInt()
        R.drawable.ic_sliders -> 0xFFFFA94D.toInt()
        R.drawable.ic_shield -> 0xFF9D7BFF.toInt()
        R.drawable.ic_wifi -> 0xFF4FC3F7.toInt()
        else -> null
    }

    /** fundo dos botões pequenos (atalhos, menu do topo). Sem prévia = o de sempre */
    fun fundoPeq(c: Context, raio: Int, ico: Int = 0, ativo: Boolean = false): Drawable = if (formato > 0) FormatoBotao(c, formato, false) else when (pele) {
        "H" -> shape(c, if (ativo) Theme.mix(Color.WHITE, OK, 0.35f) else Theme.alpha(Theme.mix(Color.WHITE, Theme.accent, 0.08f), 0xF2), raio)
        "I" -> { val k = corIcone(ico) ?: Theme.accent; shape(c, Theme.alpha(k, if (ativo) 0x44 else 0x22), raio, Theme.alpha(k, 0xA8), 1) }
        "J" -> Botao(c, if (ativo) "icoOn" else "ghost", raio)
        "K" -> shape(c, if (ativo) Theme.alpha(OK, 0x30) else Theme.alpha(0xFF07080D.toInt(), 0xC8), raio + 4)
        else -> shape(c, Theme.alpha(Color.WHITE, 0x1A), raio, Theme.alpha(Color.WHITE, 0x70), 1)
    }

    /** cor do ícone em cima do botão pequeno */
    fun corIconePeq(ico: Int = 0, ativo: Boolean = false): Int = when (pele) {
        "H" -> if (ativo) 0xFF0E6B3A.toInt() else ESCURO
        "I" -> corIcone(ico) ?: TEXT
        else -> if (ativo) OK else TEXT
    }

    /** texto em cima do botão pequeno: escuro (e sem sombra) no estilo claro */
    fun textoPeq(t: TextView): TextView {
        if (pele == "H") { t.setTextColor(ESCURO); t.setShadowLayer(0f, 0f, 0f, 0) }
        return t
    }
    /** fundo dos botões no estilo sólido: cor do tema clarinha, sem borda */
    fun solido(c: Context, raio: Int): Drawable = when (estiloBt) {
        "1" -> shape(c, Theme.alpha(Color.BLACK, 0x55), raio, Theme.alpha(Theme.accent, 0xF0), 2)
        "2" -> shape(c, Theme.alpha(Theme.accent, 0x50), raio)
        else -> shape(c, Theme.alpha(Theme.accent, 0x30), minOf(raio, 4), Theme.alpha(Theme.accent, 0xC0), 2)
    }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density * fator(c) + 0.5f).toInt()

    /** o texto encolhe ate caber em uma linha so (titulos grandes dos cartoes, que nao podem quebrar) */
    fun umaLinha(t: TextView, minSp: Int = 10) {
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val max = (t.textSize / t.resources.displayMetrics.scaledDensity).toInt()
        if (max > minSp) t.setAutoSizeTextTypeUniformWithConfiguration(minSp, max, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private val hud: Boolean get() = Prefs.cartao == "hud"

    /** forma simples arredondada (no estilo HUD os cantos ficam quase retos) */
    fun shape(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, if (hud) minOf(radiusDp, 3) else radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor)
        return g
    }

    /**
     * fundo das pecas pequenas (chips, abas, caixinhas de informacao): escuro nos estilos normais;
     * no estilo vidro fica um vidro claro com borda branca fina, combinando com os cartoes
     */
    fun peca(c: Context, raio: Int, alfa: Int = 0x9C, borda: Int = 0x1F): GradientDrawable =
        if (Theme.vidro) shape(c, Theme.alpha(Theme.mix(0xFF10131E.toInt(), Color.WHITE, 0.11f), Theme.op(alfa * 9 / 10)), raio, Theme.alpha(Color.WHITE, borda + 0x12), 1)
        else shape(c, Theme.alpha(0xFF0B0A12.toInt(), Theme.op(alfa)), raio, Theme.alpha(Color.WHITE, borda + (if (Prefs.vidro < 35) 0x14 else 0)), 1)

    /** canto de etiqueta/pilula: redonda de vez no tema redondo, quase reta no tema reto */
    fun raioPilula(): Int = if (Theme.redondo) 99 else raioPeq(8)

    /** cartao do estilo escolhido (vidro, contorno, solido ou HUD) */
    fun glass(c: Context, raio: Int = Prefs.raio, forte: Boolean = false, ligado: Boolean = false): Drawable =
        Painel(c, Prefs.cartao, forte, ligado, raio)

    fun ripple(c: Context, bg: Drawable, mask: Drawable? = null): Drawable =
        RippleDrawable(ColorStateList.valueOf(Theme.alpha(Color.WHITE, 0x30)), bg, mask ?: Painel(c, mascara = true))

    /** vibracao curta ao tocar (se o usuario ligou no ESTUDIO) */
    fun toca(v: View) {
        if (Prefs.vibrar) try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) {}
        if (Prefs.somClique) try {
            (v.context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f)
        } catch (_: Throwable) {}
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
        val display = bold || mono
        val esc = if (display || code) Theme.escalaTexto() else Theme.escalaCorpo()
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * esc * fator(c) * (if (code) 0.95f else 1f))
        t.setTextColor(color)
        // texto corrido (explicacoes) ganha mais entrelinha: em paragrafo, linha colada cansa a leitura
        t.setLineSpacing(0f, if (display || code) 1.12f else 1.3f)
        t.typeface = if (code) Theme.codigo(c) else Theme.fonte(c, display, bold)
        // texto claro ganha uma sombra bem leve: some no cartão escuro, mas segura a leitura em cima do fundo claro
        if (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 > 140_000) {
            t.setShadowLayer(dp(c, 3).toFloat(), 0f, dp(c, 1).toFloat() * 0.6f, Theme.alpha(Color.BLACK, 0x99))
        }
        if (!code && Prefs.maiusc) {
            t.setAllCaps(true)
            // caixa alta sem espaco entre as letras fica apertada; titulo usa o espacamento do pacote,
            // texto corrido um pouco menos (senao o paragrafo fica largo demais)
            t.letterSpacing = if (display) Theme.pack().esp else 0.03f
        }
        // com fundo de foto/video a letra ganha sombra; com cartao bem transparente a sombra fica mais forte pra continuar legivel
        if (Theme.temMidiaDeFundo()) {
            val fraco = Prefs.vidro < 40 || Prefs.bgEscuro < 25
            t.setShadowLayer(dp(c, if (fraco) 4 else 3).toFloat(), 0f, dp(c, 1).toFloat(), if (fraco) 0xE6000000.toInt() else 0x99000000.toInt())
        }
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
    /** canto das peças pequenas acompanhando o canto dos cartões do tema (tema reto não ganha pílula redonda) */
    fun raioPeq(max: Int): Int = minOf(max, Prefs.raio + 3)

    fun label(c: Context, s: String): TextView {
        val t = text(c, s, 11f, RED, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.14f else 0.06f
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        // sombra leve: a etiqueta fica fora do cartão, direto em cima do fundo
        t.setShadowLayer(dp(c, 4).toFloat(), 0f, dp(c, 1).toFloat(), Theme.alpha(Color.BLACK, 0xE0))
        return t
    }

    /**
     * nome de um item de lista: fonte do texto corrido em negrito, sem caixa alta. A fonte de titulo fica
     * so pros titulos de verdade (tela, secao, estado, botao) - lista inteira em caixa alta cansa e parece amador.
     */
    fun nome(c: Context, s: String, sp: Float = 13.5f, cor: Int = TEXT): TextView {
        val t = text(c, s, sp, cor)
        t.typeface = Theme.fonte(c, false, true)
        t.setLineSpacing(0f, 1.15f)
        return t
    }

    /** ícone que combina com o título (seção ou tela); null = não achou, usa o losango */
    fun iconeDe(titulo: String): Int? {
        val t = titulo.lowercase()
        return when {
            "menu do topo" in t -> R.drawable.ic_bolt
            "atalho" in t -> R.drawable.ic_sparkle
            "modo" in t -> R.drawable.ic_gamepad
            "sala" in t || "bot" in t -> R.drawable.ic_crown
            "partida" in t || "free fire" in t -> R.drawable.ic_gamepad
            "testar" in t || "passo" in t -> R.drawable.ic_check
            "conquista" in t || "rank" in t -> R.drawable.ic_trofeu
            "key" in t -> R.drawable.ic_key
            "erro" in t -> R.drawable.ic_warn
            "amig" in t || "esperando" in t || "pedido" in t -> R.drawable.ic_user
            "nível" in t || "nivel" in t || "tipo" in t || "ajuste" in t -> R.drawable.ic_sliders
            "link" in t -> R.drawable.ic_globe
            "staff" in t || "sobre" in t || "ajuda" in t -> R.drawable.ic_info
            "volume" in t -> R.drawable.ic_volume
            "conex" in t -> R.drawable.ic_wifi
            "otimiza" in t -> R.drawable.ic_chip
            "bypass" in t -> R.drawable.ic_shield
            "estúdio" in t || "estudio" in t || "visual" in t -> R.drawable.ic_brush
            "log" in t || "registro" in t || "caderno" in t -> R.drawable.ic_log
            "evento" in t -> R.drawable.ic_sparkle
            "recurso" in t || "atualiza" in t -> R.drawable.ic_download
            "competitivo" in t || "toque" in t -> R.drawable.ic_bolt
            "início" in t || "inicio" in t -> R.drawable.ic_home
            else -> null
        }
    }

    /** titulo de secao: ícone que combina com o assunto, o nome e uma linha fina que some pra direita */
    fun secao(c: Context, s: String, direita: View? = null): View {
        val r = hbox(c)
        r.gravity = Gravity.CENTER_VERTICAL
        val ic = iconeDe(s)
        if (ic != null) {
            r.addView(icone(c, ic, Theme.mix(Theme.accent, Color.WHITE, 0.25f), 14), LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)).also { it.rightMargin = dp(c, 8) })
        } else {
            // sem ícone que combine: losango no degrade
            val m = View(c)
            m.background = Botao(c, "grad", 1)
            m.rotation = 45f
            val mp = LinearLayout.LayoutParams(dp(c, 6), dp(c, 6))
            mp.leftMargin = dp(c, 2)
            mp.rightMargin = dp(c, 10)
            r.addView(m, mp)
        }
        r.addView(label(c, s))
        val linha = View(c)
        linha.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Theme.alpha(Color.WHITE, 0x38), Theme.alpha(Color.WHITE, 0)))
        val lpx = LinearLayout.LayoutParams(0, maxOf(1, dp(c, 1)), 1f)
        lpx.leftMargin = dp(c, 10)
        r.addView(linha, lpx)
        if (direita != null) r.addView(direita)
        return r
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
        val k = if (pele == "I") corIcone(res) else null
        val rIco = (minOf(Prefs.raio, 18) * 0.8f).toInt()
        f.background = when {
            k != null -> shape(c, Theme.alpha(k, if (ativo) 0x48 else 0x26), rIco, Theme.alpha(k, 0x9C), 1)
            pele == "H" -> shape(c, if (ativo) Theme.mix(Color.WHITE, OK, 0.35f) else Theme.alpha(Color.WHITE, 0xEE), rIco)
            else -> Botao(c, if (ativo) "icoOn" else "ico")
        }
        val tam = (tamDp * 0.5f).toInt()
        val corIc = when {
            k != null -> k
            pele == "H" -> if (ativo) 0xFF0E6B3A.toInt() else ESCURO
            else -> if (ativo || Theme.vidro) (if (ativo) Color.WHITE else 0xFFE6EAF7.toInt()) else 0xFFB9B9C8.toInt()
        }
        val ic = icone(c, res, corIc, tam)
        f.addView(ic, FrameLayout.LayoutParams(dp(c, tam), dp(c, tam), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(c, tamDp), dp(c, tamDp))
        return f
    }

    /** selo pequeno (DONO, STAFF, AUTO...) */
    fun selo(c: Context, s: String, cor: Int = RED, cheio: Boolean = true): TextView {
        val t = text(c, s.uppercase(), 10f, if (cheio) cor else MUTED, true, mono = true)
        t.letterSpacing = 0.12f
        t.background = shape(c, Theme.alpha(cor, if (cheio) 0x22 else 0x14), raioPilula(), Theme.alpha(cor, if (cheio) 0x99 else 0x55), 1)
        t.setPadding(dp(c, if (Theme.redondo) 12 else 10), dp(c, 6), dp(c, if (Theme.redondo) 12 else 10), dp(c, 6))
        return t
    }

    /** pilula de estado deitada: "● ADB  LIGADO" (bolinha colorida, nome apagado, valor na cor do estado) */
    fun pilulaStatus(c: Context, nome: String, valor: String, ok: Boolean?, clicavel: Boolean = false, ico: Int = 0): View {
        val r = hbox(c)
        r.gravity = Gravity.CENTER
        // clicável ganha borda de destaque + ripple pra ficar claro que dá pra tocar
        val cor = when (ok) { true -> OK; false -> BAD; null -> WARN }
        // clicável: fundo e borda na cor do estado (verde ligado, vermelho desligado, amarelo atenção)
        // a pílula fica neutra; só o botãozinho redondo da ponta muda de cor (verde ligado, vermelho desligado)
        r.background = if (clicavel)
            ripple(c, shape(c, Theme.alpha(Color.WHITE, 0x12), raioPilula(), Theme.alpha(Color.WHITE, 0x40), 1))
        else peca(c, raioPilula(), 0x8C)
        val dot = View(c)
        dot.background = shape(c, cor, 4)
        val dp2 = LinearLayout.LayoutParams(dp(c, 7), dp(c, 7))
        dp2.rightMargin = dp(c, 6)
        if (!(clicavel && ico != 0)) r.addView(dot, dp2)
        // maiusculo feito aqui mesmo: o "tudo maiusculo" do TextView apagava as cores do texto em Android antigo
        val n = up(nome)
        val s = android.text.SpannableString(n + " " + up(valor))
        s.setSpan(android.text.style.ForegroundColorSpan(MUTED), 0, n.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(android.text.style.ForegroundColorSpan(if (ok == true) TEXT else cor), n.length + 1, s.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val t = text(c, "", 10f, TEXT, true, mono = true)
        t.setAllCaps(false)
        t.letterSpacing = if (Prefs.maiusc) 0.06f else 0.02f
        t.text = s
        // largura do proprio texto (fica centralizado com a bolinha); se nao couber, corta com "..."
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        // o texto encolhe (com "...") e a bolinha da ponta nunca fica cortada
        if (clicavel && ico != 0) {
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            umaLinha(t, 7)
            // botãozinho redondo na ponta, com o ícone na cor de contraste do tema: deixa claro que dá pra tocar
            val bola = FrameLayout(c)
            bola.background = shape(c, cor, 99)
            bola.addView(icone(c, ico, 0xFF0D0F14.toInt(), 8), FrameLayout.LayoutParams(dp(c, 8), dp(c, 8), Gravity.CENTER))
            r.addView(bola, LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)).also { it.leftMargin = dp(c, 5) })
        } else r.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pv = if (compacto) 6 else 9
        if (compacto) r.background = if (clicavel) ripple(c, solido(c, raioPilula())) else solido(c, raioPilula())
        if (pele == "J" || pele == "K" || formato > 0) r.background = if (clicavel) ripple(c, fundoPeq(c, raioPilula())) else fundoPeq(c, raioPilula())
        r.setPadding(dp(c, if (clicavel && ico != 0) 9 else 7), dp(c, pv), dp(c, if (clicavel && ico != 0) 5 else 7), dp(c, pv))
        r.contentDescription = "$nome $valor"
        return r
    }

    /** faixa de aviso de uma linha so: icone, o aviso e a acao na direita ("VER EM LOGS ›") */
    fun faixaAviso(c: Context, texto: String, acao: String, cor: Int = WARN, onClick: () -> Unit): View {
        val r = hbox(c)
        r.background = ripple(c, android.graphics.drawable.LayerDrawable(arrayOf(
            shape(c, Theme.alpha(0xFF0B0A12.toInt(), Theme.op(0xB8, 0.3f)), raioPeq(16)),
            shape(c, Theme.alpha(cor, 0x1C), raioPeq(16), Theme.alpha(cor, 0x48), 1))),
            shape(c, Color.WHITE, raioPeq(16)))
        r.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        r.addView(icone(c, R.drawable.ic_warn, cor, 18), LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)).also { it.rightMargin = dp(c, 10) })
        val t = text(c, texto, 12.5f, Theme.mix(TEXT, cor, 0.35f), true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.05f else 0.01f
        umaLinha(t, 9)
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val a = text(c, "$acao ›", 11.5f, Theme.alpha(cor, 0xD0), true, mono = true)
        a.maxLines = 1
        r.addView(a, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(c, 8) })
        clique(r, onClick)
        return r
    }

    /** etiqueta de estado em pilula (dentro dos cartoes do inicio): bolinha + texto; verde quando ligado */
    fun pilulaEstado(c: Context, s: String, ligado: Boolean?): View {
        val r = hbox(c)
        val on = ligado == true
        r.background = if (on) shape(c, Theme.alpha(OK, 0x1E), raioPilula(), Theme.alpha(OK, 0x66), 1) else peca(c, raioPilula(), 0x70)
        val dot = View(c)
        dot.background = shape(c, if (on) OK else Theme.alpha(Color.WHITE, 0x66), 4)
        r.addView(dot, LinearLayout.LayoutParams(dp(c, 6), dp(c, 6)).also { it.rightMargin = dp(c, 6) })
        val t = text(c, s, 10.5f, if (on) OK else MUTED, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) 0.1f else 0.02f
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        r.addView(t)
        r.setPadding(dp(c, 9), dp(c, 4), dp(c, 10), dp(c, 4))
        return r
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
        col.background = peca(c, raioPeq(14))
        val cor = when (ok) { true -> OK; false -> BAD; null -> WARN }
        val dot = View(c)
        dot.background = shape(c, cor, 4)
        val dp2 = LinearLayout.LayoutParams(dp(c, 7), dp(c, 7))
        dp2.gravity = Gravity.CENTER_HORIZONTAL
        dp2.bottomMargin = dp(c, 7)
        col.addView(dot, dp2)
        val n = text(c, nome, 10f, MUTED, mono = true)
        n.gravity = Gravity.CENTER
        n.letterSpacing = 0.06f
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(n)
        val v = text(c, valor, 11.5f, cor, true, mono = true)
        v.gravity = Gravity.CENTER
        umaLinha(v, 8)
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
        col.background = peca(c, raioPeq(14))
        col.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 12))
        val topo = hbox(c)
        topo.addView(icone(c, ico, MUTED, 13))
        val r = text(c, rotulo, 9.5f, MUTED, true, mono = true)
        r.maxLines = 1
        r.ellipsize = android.text.TextUtils.TruncateAt.END
        val rp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rp.leftMargin = dp(c, 6)
        topo.addView(r, rp)
        col.addView(topo)
        val v = text(c, valor, 12f, cor, true)
        umaLinha(v, 8)
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
        val est = text(c, estado, 21f, if (ligado == true) OK else TEXT, true)
        umaLinha(est, 12)
        linha.addView(est, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    fun item(c: Context, titulo: String, sub: String = "", ico: Int = 0, valor: String? = null, onClick: (() -> Unit)? = null): View =
        itemEstado(c, titulo, sub, ico, valor, null, onClick)

    /** igual ao item(), mas com a cor do valor da direita (ex.: verde = OK, amarelo = falta liberar) */
    fun itemEstado(c: Context, titulo: String, sub: String, ico: Int, valor: String?, valorCor: Int?, onClick: (() -> Unit)? = null): View {
        val r = hbox(c)
        r.minimumHeight = dp(c, 56)
        r.setPadding(0, dp(c, 12), 0, dp(c, 12))
        if (ico != 0) {
            val ip = LinearLayout.LayoutParams(dp(c, 34), dp(c, 34))
            ip.rightMargin = dp(c, 12)
            r.addView(caixaIcone(c, ico, false, 34), ip)
        }
        val col = vbox(c)
        col.addView(nome(c, titulo))
        if (sub.isNotEmpty()) col.addView(text(c, sub, 11f, MUTED).also { it.setLineSpacing(0f, 1.18f) }, lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (valor != null) {
            val vc = valorCor ?: RED
            val v = text(c, valor, 10f, vc, true, mono = true)
            v.background = shape(c, Theme.alpha(vc, 0x1C), raioPeq(8), Theme.alpha(vc, 0x40), 1)
            v.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4))
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
    fun itemChave(c: Context, titulo: String, sub: String, ligado: Boolean, ico: Int = 0, onChange: (Boolean) -> Unit): View {
        val r = hbox(c)
        r.minimumHeight = dp(c, 56)
        r.setPadding(0, dp(c, 12), 0, dp(c, 12))
        if (ico != 0) {
            val ip = LinearLayout.LayoutParams(dp(c, 34), dp(c, 34))
            ip.rightMargin = dp(c, 12)
            r.addView(caixaIcone(c, ico, false, 34), ip)
        }
        val col = vbox(c)
        col.addView(nome(c, titulo))
        if (sub.isNotEmpty()) col.addView(text(c, sub, 11f, MUTED).also { it.setLineSpacing(0f, 1.18f) }, lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ch = interruptor(c, ligado, onChange)
        val chp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chp.leftMargin = dp(c, 10)
        r.addView(ch, chp)
        return r
    }

    /** abas em pilulas que rolam na horizontal (icone + nome); a escolhida fica em degrade */
    fun abas(c: Context, opcoes: List<Pair<Int, String>>, escolhido: Int, onPick: (Int) -> Unit): View {
        val hs = android.widget.HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        // a aba cortada na ponta some esmaecendo: dá pra ver que tem mais pro lado
        hs.isHorizontalFadingEdgeEnabled = true
        hs.setFadingEdgeLength(dp(c, 28))
        val l = hbox(c)
        for ((i, o) in opcoes.withIndex()) {
            val sel = i == escolhido
            val b = hbox(c)
            b.gravity = Gravity.CENTER
            b.background = if (sel) Botao(c, "grad", raioPeq(14)) else peca(c, if (Theme.redondo) 99 else raioPeq(14), 0xB8, 0x26)
            b.setPadding(dp(c, 14), dp(c, 9), dp(c, 15), dp(c, 9))
            val cor = if (sel) Theme.sobreGrad() else MUTED
            b.addView(icone(c, o.first, cor, 15))
            val t = text(c, o.second, 11f, cor, true, mono = true)
            t.maxLines = 1
            val tp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tp.leftMargin = dp(c, 7)
            b.addView(t, tp)
            b.contentDescription = o.second
            clique(b) { onPick(i) }
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) p.leftMargin = dp(c, 8)
            l.addView(b, p)
        }
        hs.addView(l)
        return hs
    }

    /** grupo de itens: rotulo em cima e um cartao so, com os itens separados por linhas finas */
    fun grupo(c: Context, titulo: String?, itens: List<View>): View {
        val col = vbox(c)
        if (titulo != null) col.addView(secao(c, titulo))
        val card = vbox(c)
        card.background = glass(c)
        card.setPadding(dp(c, 16), dp(c, 3), dp(c, 16), dp(c, 3))
        for ((i, v) in itens.withIndex()) {
            if (i > 0) {
                val d = View(c)
                d.setBackgroundColor(Theme.alpha(Color.WHITE, 0x12))
                card.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            card.addView(v)
        }
        col.addView(card, lp(top = if (titulo != null) 10 else 0, c = c))
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
            r.addView(text(c, s, 11.5f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(r, lp(top = if (i == 0) 0 else 8, c = c))
        }
        return col
    }

    /**
     * tela de erro de verdade: icone grande, titulo, uma linha explicando e as saidas (botoes). Antes era so
     * uma faixinha de aviso e o resto da tela vazio, com cara de app quebrado.
     */
    fun erro(c: Context, titulo: String, msg: String, acoes: List<View>): View {
        val caixa = card(c, forte = true)
        caixa.gravity = Gravity.CENTER_HORIZONTAL
        caixa.setPadding(dp(c, 20), dp(c, 26), dp(c, 20), dp(c, 20))
        caixa.addView(caixaIcone(c, R.drawable.ic_warn, false, 56))
        val t = text(c, titulo, 18f, TEXT, true)
        t.gravity = Gravity.CENTER
        caixa.addView(t, lp(top = 14, c = c))
        val m = text(c, msg, 12f, MUTED)
        m.gravity = Gravity.CENTER
        caixa.addView(m, lp(top = 6, c = c))
        for ((i, a) in acoes.withIndex()) caixa.addView(a, lp(top = if (i == 0) 18 else 10, c = c))
        return caixa
    }

    enum class Dica { INFO, AVISO, OK }

    /** caixinha de dica/aviso: fundo e borda na cor do tipo, icone e o texto */
    fun dica(c: Context, s: String, tipo: Dica = Dica.INFO): View {
        val cor = when (tipo) { Dica.INFO -> 0xFF7FC8FF.toInt(); Dica.AVISO -> WARN; Dica.OK -> OK }
        val ico = when (tipo) { Dica.INFO -> R.drawable.ic_info; Dica.AVISO -> R.drawable.ic_warn; Dica.OK -> R.drawable.ic_check }
        val r = hbox(c)
        r.gravity = Gravity.TOP
        if (tipo == Dica.INFO) {
            // informação comum fica discreta (barrinha no degrade do tema + texto): quase toda tela começa
            // com uma, e uma caixa azul em cada tela competia com o conteúdo e poluía o app
            r.background = if (Theme.vidro) peca(c, raioPeq(16), 0xA8, 0x0E) else shape(c, Theme.alpha(0xFF0B0A12.toInt(), Theme.op(0xA8, 0.25f)), raioPeq(12))
            // a barrinha da esquerda acompanha o canto arredondado em vez de sair pra fora dele
            r.clipToOutline = true
            r.setPadding(0, dp(c, 10), dp(c, 13), dp(c, 10))
            val barra = View(c)
            barra.background = Botao(c, "grad", 1)
            val bp = LinearLayout.LayoutParams(dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT)
            bp.rightMargin = dp(c, 11)
            r.addView(barra, bp)
            r.addView(text(c, s, 11.5f, Theme.mix(TEXT, MUTED, 0.3f)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return r
        }
        // base escura por baixo da cor: o balão fica legível mesmo em cima de fundo claro
        r.background = android.graphics.drawable.LayerDrawable(arrayOf(
            shape(c, Theme.alpha(0xFF0B0A12.toInt(), Theme.op(0xC8, 0.3f)), raioPeq(14)),
            shape(c, Theme.alpha(cor, 0x1A), raioPeq(14), Theme.alpha(cor, 0x50), 1)))
        r.setPadding(dp(c, 12), dp(c, 11), dp(c, 13), dp(c, 11))
        // ícone numa bolinha da mesma cor (fica com cara de balão de aviso, não de texto solto)
        val bola = FrameLayout(c)
        bola.background = shape(c, Theme.alpha(cor, 0x28), 11)
        bola.addView(icone(c, ico, cor, 13), FrameLayout.LayoutParams(dp(c, 13), dp(c, 13), Gravity.CENTER))
        val ip = LinearLayout.LayoutParams(dp(c, 22), dp(c, 22))
        ip.rightMargin = dp(c, 10)
        r.addView(bola, ip)
        val t = text(c, s, 11f, Theme.mix(TEXT, cor, 0.15f))
        t.setPadding(0, dp(c, 2), 0, 0)
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        // a bolinha fica na altura do meio da faixa do titulo (40dp)
        esq.addView(bola, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)).also { it.topMargin = dp(c, 6) })
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
        cab.minimumHeight = dp(c, 40)
        // faixa escura atras do titulo: o passo vira uma linha de verdade em vez de texto solto no fundo
        cab.background = if (Theme.vidro && estado != 1) peca(c, raioPeq(14), 0xA8, 0x0E)
            else shape(c, Theme.alpha(0xFF0B0A12.toInt(), Theme.op(if (estado == 1) 0xD8 else 0xA8, 0.25f)), raioPeq(if (Theme.vidro) 14 else 10),
                if (estado == 1) Theme.alpha(Theme.accent, 0x70) else Theme.alpha(Color.WHITE, 0x1A), 1)
        cab.setPadding(dp(c, 12), dp(c, 6), dp(c, 10), dp(c, 6))
        val t = text(c, titulo, 13f, if (estado == 2) MUTED else TEXT, true)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        cab.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val selo = text(c, when (estado) { 0 -> "feito"; 1 -> "agora"; else -> "depois" }, 9.5f, corEstado, true, mono = true)
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
        if (!ultimo) dir.setPadding(0, 0, 0, dp(c, 10))
        val dlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        dlp.leftMargin = dp(c, 12)
        r.addView(dir, dlp)
        return r
    }

    fun button(c: Context, label: String, kind: Kind = Kind.PRIMARY, ico: Int = 0, onClick: () -> Unit): View {
        val corTxt = when (kind) {
            Kind.PRIMARY -> corSobreGrande()
            Kind.DANGER -> 0xFFFF8E9E.toInt()
            Kind.GHOST -> if (estiloBt == "1") Theme.mix(Theme.accent, Color.WHITE, 0.45f) else if (pele == "H") ESCURO else if (pele == "I") (corIcone(ico) ?: TEXT) else TEXT
        }
        val tipo = when (kind) { Kind.PRIMARY -> "grad"; Kind.DANGER -> "danger"; Kind.GHOST -> "ghost" }
        val bg = Botao(c, tipo)
        val ph = dp(c, 16)
        val l = hbox(c)
        l.gravity = Gravity.CENTER
        if (ico != 0) l.addView(icone(c, ico, corTxt, 18))
        val t = text(c, label.uppercase(), if (label.length > 24) 11f else if (label.length > 17) 12f else 13f, corTxt, true, mono = true)
        t.letterSpacing = if (Prefs.maiusc) (if (label.length > 17) 0.05f else 0.09f) else 0.02f
        t.maxLines = 2
        t.gravity = Gravity.CENTER
        // letra escura em cima do botao claro nao leva sombra (a sombra preta deixava a letra borrada)
        if ((kind == Kind.PRIMARY && corTxt != Color.WHITE) || (kind == Kind.GHOST && pele == "H")) t.setShadowLayer(0f, 0f, 0f, 0)
        val tp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (ico != 0) tp.leftMargin = dp(c, 8)
        l.addView(t, tp)
        l.background = ripple(c, bg, Botao(c, tipo, mascara = true))
        if (compacto) {
            t.textSize = 11.5f
            l.setPadding(ph, dp(c, 10), ph, dp(c, 10))
            l.minimumHeight = dp(c, 42)
        } else {
            l.setPadding(ph, dp(c, 14), ph, dp(c, 14))
            l.minimumHeight = dp(c, 52)
        }
        // botão 3D: o texto fica na "tampa", acima da base
        if (formato == 12 && kind != Kind.DANGER) l.setPadding(l.paddingLeft, l.paddingTop, l.paddingRight, l.paddingBottom + dp(c, 4))
        clique(l, onClick)
        if (kind == Kind.PRIMARY && Prefs.brilho > 0 && Build.VERSION.SDK_INT >= 28 && formato == 0) {
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
        val tamI = if (compacto) 34 else 40
        r.setPadding(p, dp(c, if (compacto) 9 else 12), p, dp(c, if (compacto) 9 else 12))
        if (ico != 0) {
            val ib = caixaIcone(c, ico, tamDp = tamI)
            val ip = LinearLayout.LayoutParams(dp(c, tamI), dp(c, tamI))
            ip.rightMargin = dp(c, 12)
            r.addView(ib, ip)
        }
        val col = vbox(c)
        col.addView(nome(c, titulo, if (compacto) 13.5f else 14.5f))
        if (sub.isNotEmpty()) {
            val s = text(c, sub, 11.5f, MUTED)
            s.setPadding(0, dp(c, 2), 0, 0)
            s.tag = "sub"                       // quem quiser trocar só esse texto depois acha por aqui
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seta = icone(c, R.drawable.ic_chevron, MUTED, 18)
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
        e.hint = up(hint)
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * Theme.escalaTexto())
        e.typeface = Theme.fonte(c, true, false)
        e.letterSpacing = if (Prefs.maiusc) 0.04f else 0.01f
        e.inputType = if (numero) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.background = Botao(c, "campo", minOf(Prefs.raio, 14))
        val p = dp(c, 16)
        e.setPadding(p, dp(c, 13), p, dp(c, 13))
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

    /** quadrado da grade do inicio: icone, nome, descricao e o estado numa pilula no rodape */
    fun tile(c: Context, ico: Int, titulo: String, desc: String, estado: String, ligado: Boolean?, onClick: () -> Unit): View {
        if (compacto) return tileLinha(c, ico, titulo, estado, ligado, onClick)
        val on = ligado == true
        val col = vbox(c)
        col.background = ripple(c, glass(c, ligado = on), Painel(c, mascara = true))
        val p = dp(c, if (compacto) 12 else 14)
        col.setPadding(p, p, p, p)
        col.minimumHeight = dp(c, if (compacto) 108 else 140)
        col.addView(caixaIcone(c, ico, on, if (compacto) 32 else 38))
        // nome numa linha so (encolhe um pouco se precisar): quebrado em 2 linhas o quadrado ficava torto
        col.addView(text(c, titulo, 13f, TEXT, true).also { umaLinha(it, 10) }, lp(top = 12, c = c))
        if (desc.isNotEmpty()) {
            val d = text(c, desc, 9.5f, MUTED)
            d.setLineSpacing(0f, 1.12f)
            d.ellipsize = android.text.TextUtils.TruncateAt.END
            d.maxLines = 2
            col.addView(d, lp(top = 2, c = c))
        }
        // espaco flexivel empurra o estado pro rodape: os dois quadrados da linha ficam alinhados embaixo
        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(pilulaEstado(c, estado, ligado), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(c, 10) })
        col.contentDescription = "$titulo, $estado"
        clique(col, onClick)
        return col
    }

    /** estilo 2: o modo vira um botão baixinho (ícone, nome e estado do lado) */
    private fun tileLinha(c: Context, ico: Int, titulo: String, estado: String, ligado: Boolean?, onClick: () -> Unit): View {
        val on = ligado == true
        val r = hbox(c)
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = ripple(c, if (on) glass(c, ligado = true) else Botao(c, "ghost"), Botao(c, "ghost", mascara = true))
        r.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10))
        r.addView(caixaIcone(c, ico, on, 30), LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)).also { it.rightMargin = dp(c, 8) })
        val col = vbox(c)
        col.addView(text(c, titulo, 11.5f, TEXT, true).also { umaLinha(it, 8) })
        val e = text(c, estado, 9f, if (on) OK else MUTED, true, mono = true)
        e.maxLines = 1; e.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(e, lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.contentDescription = "$titulo, $estado"
        clique(r, onClick)
        return r
    }

    /**
     * cartao grande do primeiro modo do inicio: icone, nome, descricao, estado e um botao rapido na direita
     * (ex.: LIGAR). Tocar no cartao abre a tela do modo; tocar no botao faz a acao sem sair do inicio.
     */
    fun destaque(c: Context, ico: Int, titulo: String, desc: String, estado: String, ligado: Boolean?,
                 botao: String?, primario: Boolean, onBotao: (() -> Unit)?, onClick: () -> Unit): View {
        val on = ligado == true
        val r = hbox(c)
        r.background = ripple(c, glass(c, forte = true, ligado = on), Painel(c, mascara = true))
        val ti = if (compacto) 40 else 48
        r.setPadding(dp(c, 16), dp(c, if (compacto) 12 else 16), dp(c, 14), dp(c, if (compacto) 12 else 16))
        r.addView(caixaIcone(c, ico, on, ti), LinearLayout.LayoutParams(dp(c, ti), dp(c, ti)).also { it.rightMargin = dp(c, 14) })
        val col = vbox(c)
        col.addView(text(c, titulo, if (compacto) 15f else 17f, TEXT, true).also { umaLinha(it, 11) })
        if (desc.isNotEmpty()) col.addView(text(c, desc, 10.5f, MUTED).also { it.maxLines = 2; it.ellipsize = android.text.TextUtils.TruncateAt.END }, lp(top = 2, c = c))
        col.addView(pilulaEstado(c, estado, ligado), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(c, 8) })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (botao != null && onBotao != null) {
            val tipo = if (primario) "grad" else "ghost"
            val b = text(c, botao.uppercase(), 12.5f, if (primario) corSobreGrande() else TEXT, true, mono = true)
            b.gravity = Gravity.CENTER
            b.maxLines = 1
            b.letterSpacing = if (Prefs.maiusc) 0.1f else 0.02f
            if (primario && corSobreGrande() != Color.WHITE) b.setShadowLayer(0f, 0f, 0f, 0)
            b.background = ripple(c, Botao(c, tipo), Botao(c, tipo, mascara = true))
            b.setPadding(dp(c, 16), dp(c, if (compacto) 8 else 10), dp(c, 16), dp(c, (if (compacto) 8 else 10) + (if (formato == 12) 4 else 0)))
            b.minimumHeight = dp(c, if (compacto) 36 else 42)
            b.minimumWidth = dp(c, 76)
            clique(b, onBotao)
            r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(c, 10) })
        }
        r.contentDescription = "$titulo, $estado"
        clique(r, onClick)
        return r
    }

    /** maiusculo o texto quando o modo "tudo maiusculo" esta ligado; usado onde o texto nao passa pelo text() */
    private fun up(s: String): String = if (Prefs.maiusc) s.uppercase() else s

    fun toast(c: Context, msg: String) { Toast.makeText(c, up(msg), Toast.LENGTH_SHORT).show() }

    // ---------------- caixas de dialogo ----------------
    private fun builder(c: Context) = AlertDialog.Builder(c, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

    /**
     * Janela do NOCRY: cartão no visual do tema (não a caixa cinza do Android), com título, conteúdo e botões.
     * botões: (texto, primário?, ação). Devolve o Dialog pra quem quiser fechar.
     */
    fun janela(c: Context, titulo: String, corpo: View?, botoes: List<Triple<String, Boolean, () -> Unit>>, cancelavel: Boolean = true, icone: Int = 0): android.app.Dialog {
        val dlg = android.app.Dialog(c)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val raiz = FrameLayout(c)
        val card = vbox(c)
        card.background = Painel(c, forte = true, ligado = true)
        card.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 16))
        val topo = hbox(c)
        topo.gravity = Gravity.CENTER_VERTICAL
        if (icone != 0) topo.addView(caixaIcone(c, icone, true, 34), LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)).also { it.rightMargin = dp(c, 12) })
        topo.addView(gradTexto(c, titulo, 19f).also { it.maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(topo)
        if (corpo != null) {
            val sc = android.widget.ScrollView(c)
            sc.isVerticalScrollBarEnabled = false
            sc.addView(corpo)
            card.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.topMargin = dp(c, 14) })
        }
        if (botoes.isNotEmpty()) {
            val linha = hbox(c)
            botoes.forEachIndexed { k, (txt, prim, acao) ->
                val b = button(c, txt, if (prim) Kind.PRIMARY else Kind.GHOST) { dlg.dismiss(); acao() }
                linha.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { if (k > 0) it.leftMargin = dp(c, 10) })
            }
            card.addView(linha, lp(top = 18, c = c))
        }
        val m = dp(c, 18)
        raiz.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { it.leftMargin = m; it.rightMargin = m })
        dlg.setContentView(raiz)
        dlg.setCancelable(cancelavel)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.72f)
            val alt = (c.resources.displayMetrics.heightPixels * 0.82f).toInt()
            card.maxHeightCompat(alt)
        }
        dlg.show()
        // entra subindo de leve (com as animações do Android zeradas ele só aparece)
        card.alpha = 0f; card.translationY = dp(c, 24).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        return dlg
    }

    private fun LinearLayout.maxHeightCompat(max: Int) {
        viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (height > max) { layoutParams = layoutParams.also { it.height = max }; requestLayout() }
                viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun corpoTexto(c: Context, msg: String): View =
        text(c, msg, 13f, TEXT).also { it.setLineSpacing(0f, 1.15f) }

    fun dialogNumero(c: Context, titulo: String, dica: String, min: Int, max: Int, atual: Int, onOk: (Int) -> Unit) {
        val e = field(c, dica, true)
        e.setText(atual.toString())
        e.setSelection(e.text.length)
        val box = vbox(c)
        box.addView(text(c, "de $min a $max", 12f, MUTED))
        box.addView(e, lp(top = 8, c = c))
        janela(c, titulo, box, listOf(Triple("cancelar", false) {}, Triple("ok", true) {
            val v = e.text.toString().trim().toIntOrNull()
            if (v == null || v < min || v > max) toast(c, "Use um numero de $min a $max") else onOk(v)
        }))
    }

    fun dialogTexto(c: Context, titulo: String, dica: String, onOk: (String) -> Unit) {
        val e = field(c, dica, false)
        janela(c, titulo, e, listOf(Triple("cancelar", false) {}, Triple("ok", true) { onOk(e.text.toString()) }))
    }

    /** lista de opções: cada uma vira um botão do tema */
    fun dialogEscolha(c: Context, titulo: String, opcoes: List<String>, onPick: (Int) -> Unit) {
        val box = vbox(c)
        var dlg: android.app.Dialog? = null
        opcoes.forEachIndexed { i, o ->
            val partes = o.split(" · ", limit = 2)
            val r = vbox(c)
            r.background = ripple(c, Botao(c, "ghost"), Botao(c, "ghost", mascara = true))
            r.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11))
            r.addView(text(c, partes[0], 13.5f, TEXT, true))
            if (partes.size > 1) r.addView(text(c, partes[1], 10.5f, MUTED), lp(top = 2, c = c))
            clique(r) { dlg?.dismiss(); onPick(i) }
            box.addView(r, lp(top = if (i == 0) 0 else 8, c = c))
        }
        dlg = janela(c, titulo, box, listOf(Triple("cancelar", false) {}))
    }

    /** aviso com um botao so (nao fecha tocando fora) */
    fun dialogAviso(c: Context, titulo: String, msg: String, ok: String, onOk: () -> Unit) {
        janela(c, titulo, corpoTexto(c, msg), listOf(Triple(ok, true, onOk)), cancelavel = false)
    }

    fun dialogConfirma(c: Context, titulo: String, msg: String, ok: String = "SIM", onOk: () -> Unit) {
        janela(c, titulo, corpoTexto(c, msg), listOf(Triple("cancelar", false) {}, Triple(ok, true, onOk)))
    }

    /** novidades da versão: lista com marcadores no visual do app */
    fun novidades(c: Context, versao: String, itens: List<String>, rodape: String, onOk: () -> Unit) {
        val box = vbox(c)
        box.addView(text(c, "Você está na v$versao", 12f, MUTED, true, mono = true))
        for (it in itens) {
            val l = hbox(c)
            l.addView(View(c).also { v -> v.background = Botao(c, "grad", 99) }, LinearLayout.LayoutParams(dp(c, 7), dp(c, 7)).also { p -> p.topMargin = dp(c, 7); p.rightMargin = dp(c, 10) })
            l.addView(text(c, it, 13f, TEXT).also { t -> t.setLineSpacing(0f, 1.12f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(l, lp(top = 12, c = c))
        }
        if (rodape.isNotBlank()) box.addView(text(c, rodape, 11f, MUTED), lp(top = 16, c = c))
        janela(c, "Novidades", box, listOf(Triple("entendi", true, onOk)), cancelavel = false, icone = R.drawable.ic_sparkle)
    }
}
