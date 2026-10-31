package com.nocry.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** Faixa que mostra o degrade atual (cor 1 -> cor 2, no angulo escolhido). */
class FaixaGrad(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.shader = Theme.grad(w, h)
        cv.drawRoundRect(r, h / 2.2f, h / 2.2f, p)
    }
}

// =====================================================================================
// TELA DA KEY (entrada) com aviso do que e enviado
// =====================================================================================
class KeyGate(private val act: MainActivity) {
    private val dono = FrameLayout(act)
    private var ocupado = false
    private var erro = ""
    private var keyTxt = Prefs.licKey ?: ""
    private var concordo = Prefs.consentiu
    private var campo: EditText? = null

    fun criar(): View {
        monta()
        // key guardada que deixou de valer (ex.: passou do prazo sem internet): tenta confirmar sozinho
        val salva = Prefs.licKey
        if (salva != null && Prefs.consentiu) {
            Bg.later(300) { tenta(salva) }
        }
        return dono
    }

    private fun item(c: Context, texto: String): View {
        val l = Ui.hbox(c)
        l.gravity = Gravity.TOP
        val ic = Ui.icone(c, R.drawable.ic_check, Ui.OK, 14)
        val ip = LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14))
        ip.topMargin = Ui.dp(c, 3)
        ip.rightMargin = Ui.dp(c, 10)
        l.addView(ic, ip)
        l.addView(Ui.text(c, texto, 12.5f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return l
    }

    private fun monta() {
        campo?.let { keyTxt = it.text.toString() }
        dono.removeAllViews()
        val c = act
        val sv = ScrollView(c)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        val col = Ui.vbox(c)
        col.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(c, 20)
        col.setPadding(p, Ui.dp(c, 28), p, Ui.dp(c, 28))

        // ---------------- marca ----------------
        val logo = FrameLayout(c)
        logo.background = Botao(c, "icoOn", 22)
        val n = Ui.text(c, "N", 40f, Theme.sobreGrad(), true, mono = true)
        n.gravity = Gravity.CENTER
        logo.addView(n, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(logo, LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76)))
        col.addView(Ui.gradTexto(c, "NOCRY", 46f), Ui.lp(top = 16, c = c))
        col.addView(Ui.text(c, "Acesso com key", 14f, Ui.TEXT, true, mono = true), Ui.lp(top = 0, c = c))
        col.addView(Ui.text(c, "Modo competitivo, toque e otimização direto no celular. Para entrar, use a sua key.", 13f, Ui.MUTED), Ui.lp(top = 6, c = c))

        // ---------------- cartao ----------------
        val card = Ui.card(c, forte = true)
        card.addView(Ui.label(c, "sua key"))
        val e = Ui.field(c, "XXXX-XXXX-XXXX-XXXX", false)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.setText(keyTxt)
        e.setSelection(e.text.length)
        campo = e
        card.addView(e, Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "o que o NOCRY envia"), Ui.lp(top = 18, c = c))
        val lista = listOf(
            "seu IP (o servidor vê sozinho)",
            "marca e modelo do celular",
            "versão do Android e do app",
            "um código anônimo do aparelho (não é o IMEI nem o número do telefone)"
        )
        for (l in lista) card.addView(item(c, l), Ui.lp(top = 7, c = c))
        card.addView(Ui.text(c, "Não envia telefone, localização, contatos, mensagens nem a lista de apps. Serve para saber quem usa cada key e poder desativar uma key.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))

        val linha = Ui.hbox(c)
        linha.addView(Ui.text(c, "Li e concordo", 14f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        linha.addView(Ui.interruptor(c, concordo) { v -> concordo = v })
        card.addView(linha, Ui.lp(top = 16, c = c))

        if (erro.isNotEmpty()) card.addView(Ui.text(c, erro, 13f, Ui.WARN, true), Ui.lp(top = 12, c = c))
        card.addView(Ui.button(c, if (ocupado) "confirmando..." else "entrar", Ui.Kind.PRIMARY, R.drawable.ic_key) {
            tenta(e.text.toString())
        }, Ui.lp(top = 14, c = c))
        col.addView(card, Ui.lp(top = 22, c = c))

        val dev = License.devId(c).take(8)
        val rod = Ui.hbox(c)
        rod.addView(Ui.selo(c, "1 key = 1 aparelho", Ui.MUTED, false))
        col.addView(rod, Ui.lp(top = 18, c = c))
        col.addView(Ui.text(c, "versão ${License.versao(c)} · aparelho $dev", 11f, Ui.MUTED, mono = true), Ui.lp(top = 10, c = c))
        col.addView(Ui.text(c, "Sem key? Peça a quem te passou o app.", 12f, Ui.MUTED), Ui.lp(top = 4, c = c))

        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dono.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun tenta(key: String) {
        if (ocupado) return
        val k = key.trim()
        if (k.length < 4) { erro = "Digite a sua key."; monta(); return }
        if (!concordo) { erro = "Marque \"Li e concordo\" para continuar."; monta(); return }
        ocupado = true
        erro = ""
        keyTxt = k
        monta()
        Prefs.consentiu = true
        Bg.run({ License.login(act.applicationContext, k) }) { r ->
            ocupado = false
            if (r.ok) act.entrou()
            else {
                erro = r.texto()
                // se a key salva foi recusada de vez, esquece ela
                if (r.negado()) Prefs.licKey = null
                monta()
            }
        }
    }
}

// =====================================================================================
// ORGANIZAR O INICIO (ordem e quais modos aparecem)
// =====================================================================================
class OrganizarScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Organizar início"

    override fun key(): String = Prefs.ordem + "|" + Prefs.ocultos

    private fun mini(c: Context, ico: Int, ativo: Boolean, desc: String, acao: () -> Unit): View {
        val f = FrameLayout(c)
        f.background = Botao(c, if (ativo) "icoOn" else "ico")
        f.addView(Ui.icone(c, ico, if (ativo) Color.WHITE else Ui.MUTED, 16), FrameLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16), Gravity.CENTER))
        f.contentDescription = desc
        Ui.clique(f, acao)
        return f
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, "Escolha a ordem dos modos na tela inicial. O olho mostra ou esconde cada um.", 13f, Ui.MUTED), 0)

        val lista = Atalhos.ordem()
        val ocultos = lista.filter { Atalhos.escondido(it.id) }.map { it.id }
        for ((i, at) in lista.withIndex()) {
            val oculto = at.id in ocultos
            val r = Ui.hbox(c)
            r.background = Ui.glass(c, ligado = !oculto)
            val pd = Ui.dp(c, 12)
            r.setPadding(pd, Ui.dp(c, 10), pd, Ui.dp(c, 10))
            val ib = Ui.caixaIcone(c, at.ico, !oculto, 40)
            val ip = LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40))
            ip.rightMargin = Ui.dp(c, 12)
            r.addView(ib, ip)
            val tx = Ui.vbox(c)
            tx.addView(Ui.text(c, at.nome, 14f, if (oculto) Ui.MUTED else Ui.TEXT, true))
            tx.addView(Ui.text(c, if (oculto) "escondido" else at.desc, 11f, Ui.MUTED), Ui.lp(top = 1, c = c))
            r.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            fun botao(v: View) {
                val lp = LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34))
                lp.leftMargin = Ui.dp(c, 6)
                r.addView(v, lp)
            }
            botao(mini(c, R.drawable.ic_up, false, "Subir") { mover(i, -1) })
            botao(mini(c, R.drawable.ic_down, false, "Descer") { mover(i, 1) })
            botao(mini(c, R.drawable.ic_eye, !oculto, if (oculto) "Mostrar" else "Esconder") { alterna(at.id) })
            add(r, if (i == 0) 14 else 8)
        }
        add(Ui.button(c, "voltar à ordem padrão", Ui.Kind.GHOST, R.drawable.ic_sparkle) { Atalhos.padrao(); redesenha() }, 18)
    }

    private fun mover(i: Int, dir: Int) {
        val l = Atalhos.ordem().toMutableList()
        val j = i + dir
        if (j < 0 || j >= l.size) return
        val t = l[i]
        l[i] = l[j]
        l[j] = t
        Atalhos.salva(l, l.filter { Atalhos.escondido(it.id) }.map { it.id })
        redesenha()
    }

    private fun alterna(id: String) {
        val l = Atalhos.ordem()
        val oc = l.filter { Atalhos.escondido(it.id) }.map { it.id }.toMutableList()
        if (id in oc) oc.remove(id) else oc.add(id)
        Atalhos.salva(l, oc)
        redesenha()
    }
}

// =====================================================================================
// ESTUDIO (temas, cores, cartoes, fundo, fontes e efeitos)
// =====================================================================================
class EstudioScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Estúdio"
    override val aba = 2

    private var ocupado = false
    private var msg = ""
    private var ocupadoMus = false
    private var msgMus = ""

    companion object {
        private const val REQ_MIDIA = 4101
        private const val REQ_MUSICA = 4102
        private const val LIMITE_MB = 400
        private const val LIMITE_MUS_MB = 40

        /** fundos escuros pra escolher (o texto do app e claro, entao a base precisa ser escura) */
        private val BASES = listOf(
            Theme.Cor("Carvão", 0xFF0A0A0E.toInt()),
            Theme.Cor("Vinho", 0xFF0A0506.toInt()),
            Theme.Cor("Noite", 0xFF05060F.toInt()),
            Theme.Cor("Floresta", 0xFF020A04.toInt()),
            Theme.Cor("Roxo", 0xFF0D0221.toInt()),
            Theme.Cor("Âmbar", 0xFF0B0805.toInt()),
            Theme.Cor("Ameixa", 0xFF110A18.toInt()),
            Theme.Cor("Grafite", 0xFF0E0F13.toInt())
        )
    }

    override fun key(): String = listOf(
        Prefs.bgTipo, Prefs.bgPreset, Prefs.bgArquivo, Prefs.bgEscuro, Prefs.bgBlur, Prefs.accent, Prefs.accent2, Prefs.baseCor,
        Prefs.angulo, Prefs.tema, Prefs.cartao, Prefs.vidro, Prefs.raio, Prefs.brilho, Prefs.borda, Prefs.fonte, Prefs.escala,
        Prefs.maiusc, Prefs.anim, Prefs.particulas, Prefs.scanner, Prefs.pulso, Prefs.vibrar, ocupado, msg,
        Prefs.introModo, Prefs.introSom, Prefs.introMusica, Prefs.introInicio, ocupadoMus, msgMus
    ).joinToString("|")

    private fun arquivo(): File? = Prefs.bgArquivo?.let { File(act.filesDir, it) }?.takeIf { it.isFile }

    private fun mb(f: File): String {
        val m = f.length() / 1048576.0
        return if (m >= 10) "${m.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", m)
    }

    /** mudou algo que afeta todas as telas; a partir daqui o visual e "personalizado" */
    private fun muda() { Prefs.tema = "custom"; act.reaplica() }

    /** mudou so o fundo */
    private fun fundo() { act.refazFundo(); redesenha() }

    private fun secao(c: Context, titulo: String, forte: Boolean = false): LinearLayout {
        val k = Ui.card(c, forte = forte)
        k.addView(Ui.label(c, titulo))
        return k
    }

    private fun linhaRolavel(c: Context, itens: List<View>): HorizontalScrollView {
        val hs = HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val l = Ui.hbox(c)
        l.gravity = Gravity.TOP
        for (v in itens) l.addView(v)
        hs.addView(l, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return hs
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, "Deixe o NOCRY do seu jeito. Tudo muda na hora e fica salvo.", 13f, Ui.MUTED), 0)

        // ---------------- temas prontos ----------------
        val tm = secao(c, "temas prontos", forte = true)
        val cartas = ArrayList<View>()
        for (t in Temas.lista) {
            val sel = Prefs.tema == t.id
            val box = Ui.vbox(c)
            val mini = Miniatura.tema(c, t, sel)
            box.addView(mini, LinearLayout.LayoutParams(Ui.dp(c, 104), Ui.dp(c, 132)))
            val nome = Ui.text(c, t.nome, 12.5f, if (sel) Ui.RED else Ui.TEXT, true, mono = true)
            box.addView(nome, Ui.lp(top = 7, c = c))
            box.addView(Ui.text(c, t.tag, 10f, Ui.MUTED).also { it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(top = 1, c = c))
            Ui.clique(box) { Temas.aplica(t); act.reaplica() }
            val lp = LinearLayout.LayoutParams(Ui.dp(c, 104), ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = Ui.dp(c, 12)
            box.layoutParams = lp
            cartas.add(box)
        }
        tm.addView(linhaRolavel(c, cartas), Ui.lp(top = 12, c = c))
        if (Prefs.tema == "custom") tm.addView(Ui.text(c, "Visual personalizado por você.", 11.5f, Ui.MUTED, true, mono = true), Ui.lp(top = 4, c = c))
        add(tm, 12)

        // ---------------- cores ----------------
        val k = secao(c, "cores")
        val faixa = FaixaGrad(c)
        k.addView(faixa, Ui.lp(top = 12, h = Ui.dp(c, 18), c = c))

        k.addView(Ui.text(c, "Degrades prontos", 12.5f, Ui.TEXT, true), Ui.lp(top = 14, c = c))
        k.addView(gradientes(c), Ui.lp(top = 10, c = c))

        k.addView(Ui.text(c, "Cor 1", 12.5f, Ui.TEXT, true), Ui.lp(top = 16, c = c))
        k.addView(Ui.cores(c, Theme.PALETA, Prefs.accent, { v -> Prefs.accent = v; muda() }, { corPropria(1) }), Ui.lp(top = 10, c = c))
        k.addView(Ui.text(c, "Cor 2", 12.5f, Ui.TEXT, true), Ui.lp(top = 16, c = c))
        k.addView(Ui.cores(c, Theme.PALETA, Prefs.accent2, { v -> Prefs.accent2 = v; muda() }, { corPropria(2) }), Ui.lp(top = 10, c = c))
        k.addView(Ui.slider(c, "Ângulo do degradê", Prefs.angulo, 0, 360, "°") { x -> Prefs.angulo = x; muda() }, Ui.lp(top = 16, c = c))
        k.addView(Ui.text(c, "Cor do fundo do app", 12.5f, Ui.TEXT, true), Ui.lp(top = 16, c = c))
        k.addView(Ui.cores(c, BASES, Prefs.baseCor, { v -> Prefs.baseCor = v; muda() }, { corPropria(3) }), Ui.lp(top = 10, c = c))
        add(k, 12)

        // ---------------- cartoes ----------------
        val v = secao(c, "cartões e botões")
        val estilos = listOf("vidro", "contorno", "solido", "hud")
        v.addView(Ui.segmento(c, listOf("Vidro", "Contorno", "Sólido", "HUD"), estilos.indexOf(Prefs.cartao).coerceAtLeast(0)) { i ->
            Prefs.cartao = estilos[i]
            muda()
        }, Ui.lp(top = 12, c = c))
        v.addView(Ui.text(c, when (Prefs.cartao) {
            "vidro" -> "Translúcido, com brilho no topo."
            "contorno" -> "Só a borda neon, bem leve."
            "solido" -> "Cor cheia, visual de luxo."
            else -> "Cantos cortados, estilo painel de jogo."
        }, 12f, Ui.MUTED), Ui.lp(top = 8, c = c))
        v.addView(Ui.slider(c, "Brilho neon", Prefs.brilho, 0, 100, "%") { x -> Prefs.brilho = x; muda() }, Ui.lp(top = 14, c = c))
        v.addView(Ui.slider(c, "Opacidade do vidro", Prefs.vidro, 20, 95, "%") { x -> Prefs.vidro = x; muda() }, Ui.lp(top = 12, c = c))
        v.addView(Ui.slider(c, "Arredondamento", Prefs.raio, 2, 30, " dp") { x -> Prefs.raio = x; muda() }, Ui.lp(top = 12, c = c))
        v.addView(Ui.slider(c, "Espessura da borda", Prefs.borda, 1, 4, " dp") { x -> Prefs.borda = x; muda() }, Ui.lp(top = 12, c = c))
        add(v, 12)

        // ---------------- fundo ----------------
        add(secaoFundo(c), 12)

        // ---------------- fontes ----------------
        val t = secao(c, "fonte")
        t.addView(fontes(c), Ui.lp(top = 12, c = c))
        t.addView(Ui.toggle(c, "Tudo em maiúsculo", "títulos, botões e textos em CAIXA ALTA", Prefs.maiusc) { x -> Prefs.maiusc = x; muda() }, Ui.lp(top = 14, c = c))
        t.addView(Ui.slider(c, "Tamanho do texto", Prefs.escala, 85, 130, "%") { x -> Prefs.escala = x; muda() }, Ui.lp(top = 14, c = c))
        add(t, 12)

        // ---------------- efeitos ----------------
        val e = secao(c, "efeitos")
        e.addView(Ui.toggle(c, "Animações", "fundo animado, anel e transições", Prefs.anim) { x -> Prefs.anim = x; muda() }, Ui.lp(top = 12, c = c))
        e.addView(Ui.toggle(c, "Partículas", "brasas, neve, pétalas, chuva de código", Prefs.particulas) { x -> Prefs.particulas = x; muda() }, Ui.lp(top = 8, c = c))
        e.addView(Ui.toggle(c, "Linhas de scanner", "listras e uma faixa de luz que varre a tela", Prefs.scanner) { x -> Prefs.scanner = x; muda() }, Ui.lp(top = 8, c = c))
        e.addView(Ui.toggle(c, "Brilho pulsando", "o anel do início respira", Prefs.pulso) { x -> Prefs.pulso = x; muda() }, Ui.lp(top = 8, c = c))
        e.addView(Ui.toggle(c, "Vibrar ao tocar", "vibração curta nos botões", Prefs.vibrar) { x -> Prefs.vibrar = x; muda() }, Ui.lp(top = 8, c = c))
        add(e, 12)

        // ---------------- intro de abertura ----------------
        add(secaoIntro(c), 12)

        // ---------------- inicio ----------------
        add(Ui.row(c, "Organizar o início", "ordem e visibilidade dos modos", R.drawable.ic_sliders) { act.abre(OrganizarScreen(act)) }, 12)

        add(Ui.button(c, "voltar ao tema Brasa", Ui.Kind.GHOST, R.drawable.ic_sparkle) {
            Ui.dialogConfirma(act, "TEMA PADRÃO", "Cores, fonte, cartões, fundo e efeitos voltam ao tema Brasa. O vídeo ou a imagem que você escolheu continua guardado.", "VOLTAR") {
                restaura()
            }
        }, 16)
    }

    // ---------------- intro de abertura ----------------
    private fun secaoIntro(c: Context): View {
        val s = secao(c, "intro de abertura")
        s.addView(Ui.text(c, "Animação HACKER com “app desenvolvido por aucerola”. Toque na tela para pular.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))

        val modos = listOf("sempre", "novo", "off")
        s.addView(Ui.segmento(c, listOf("Sempre", "1ª vez", "Desligada"), modos.indexOf(Prefs.introModo).coerceAtLeast(0)) { i ->
            Prefs.introModo = modos[i]
            redesenha()
        }, Ui.lp(top = 12, c = c))
        s.addView(Ui.text(c, when (Prefs.introModo) {
            "novo" -> "Só na primeira vez que o app abre depois de instalar ou atualizar."
            "off" -> "Não toca ao abrir. Você ainda pode assistir pelo botão lá embaixo."
            else -> "Toca sempre que o app abre do zero. Voltar de outro app não repete."
        }, 12f, Ui.MUTED), Ui.lp(top = 8, c = c))

        s.addView(Ui.toggle(c, "Som da intro", "música e efeitos (segue o volume de mídia)", Prefs.introSom) { x ->
            Prefs.introSom = x
            redesenha()
        }, Ui.lp(top = 14, c = c))

        val mus = Intro.musica(c)
        if (mus != null) {
            s.addView(Ui.text(c, "música: " + (Prefs.introMusicaNome ?: "escolhida") + " · " + mb(mus), 12.5f, Ui.OK, true, mono = true), Ui.lp(top = 14, c = c))
            s.addView(Ui.row(c, "Começar em: ${Prefs.introInicio} s", "o trecho da música que toca na intro (0 a 600)") {
                Ui.dialogNumero(act, "COMEÇAR EM", "segundos", 0, 600, Prefs.introInicio) { v -> Prefs.introInicio = v; redesenha() }
            }, Ui.lp(top = 8, c = c))
            s.addView(Ui.button(c, if (ocupadoMus) "copiando..." else "trocar a música", Ui.Kind.PRIMARY, R.drawable.ic_volume) {
                if (!ocupadoMus) escolherMusica()
            }, Ui.lp(top = 8, c = c))
            s.addView(Ui.button(c, "voltar à música original", Ui.Kind.GHOST, R.drawable.ic_trash) { removeMusica() }, Ui.lp(top = 8, c = c))
        } else {
            s.addView(Ui.text(c, "música: a original da intro", 12.5f, Ui.TEXT, true, mono = true), Ui.lp(top = 14, c = c))
            s.addView(Ui.button(c, if (ocupadoMus) "copiando..." else "escolher uma música do celular", Ui.Kind.PRIMARY, R.drawable.ic_volume) {
                if (!ocupadoMus) escolherMusica()
            }, Ui.lp(top = 10, c = c))
        }
        if (msgMus.isNotEmpty()) s.addView(Ui.text(c, msgMus, 12.5f, Ui.WARN, true), Ui.lp(top = 8, c = c))
        s.addView(Ui.text(c, "A música é copiada para dentro do app e nada é enviado pela internet. Só toca o tempo da intro (uns 8 segundos) e vai baixando no final.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))

        s.addView(Ui.button(c, "assistir agora", Ui.Kind.PRIMARY, R.drawable.ic_play) { act.tocaIntro() }, Ui.lp(top = 14, c = c))
        return s
    }

    @Suppress("DEPRECATION")
    private fun escolherMusica() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_MUSICA && res == Activity.RESULT_OK && u != null) copiaMusica(u)
        }
        try {
            act.startActivityForResult(i, REQ_MUSICA)
        } catch (_: Exception) {
            Ui.toast(act, "Não consegui abrir os arquivos")
        }
    }

    private class CopiaMus(val arquivo: String?, val nome: String, val erro: String)

    private fun copiaMusica(uri: Uri) {
        ocupadoMus = true
        msgMus = ""
        redesenha()
        val ctx = act.applicationContext
        Bg.run({ copiarMusica(ctx, uri) }) { r ->
            ocupadoMus = false
            if (r.arquivo != null) {
                val velho = Prefs.introMusica
                Prefs.introMusica = r.arquivo
                Prefs.introMusicaNome = r.nome
                Prefs.introInicio = 0
                if (velho != null && velho != r.arquivo) try { File(ctx.filesDir, velho).delete() } catch (_: Exception) {}
                msgMus = ""
                NLog.add("intro", "musica nova: ${r.nome}")
            } else msgMus = r.erro
            redesenha()
        }
    }

    private fun copiarMusica(c: Context, uri: Uri): CopiaMus {
        val dest = File(c.filesDir, "intro_musica_" + System.currentTimeMillis() + ".snd")
        return try {
            var nome = "música escolhida"
            try {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
                    if (q.moveToFirst()) q.getString(0)?.let { nome = it }
                }
            } catch (_: Throwable) {}
            val ins = c.contentResolver.openInputStream(uri) ?: return CopiaMus(null, "", "Não consegui abrir esse arquivo.")
            ins.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            if (dest.length() == 0L) {
                dest.delete()
                return CopiaMus(null, "", "O arquivo veio vazio.")
            }
            if (dest.length() > LIMITE_MUS_MB * 1048576L) {
                dest.delete()
                return CopiaMus(null, "", "Arquivo grande demais (mais de $LIMITE_MUS_MB MB). Escolha uma música menor.")
            }
            val mr = MediaMetadataRetriever()
            val ok = try {
                mr.setDataSource(dest.absolutePath)
                mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            } catch (_: Throwable) {
                false
            } finally {
                try { mr.release() } catch (_: Throwable) {}
            }
            if (!ok) {
                dest.delete()
                return CopiaMus(null, "", "Esse arquivo não parece ser um áudio que o celular consegue tocar.")
            }
            CopiaMus(dest.name, nome, "")
        } catch (t: Throwable) {
            try { dest.delete() } catch (_: Exception) {}
            CopiaMus(null, "", "Não consegui copiar: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun removeMusica() {
        Prefs.introMusica?.let { try { File(act.filesDir, it).delete() } catch (_: Exception) {} }
        Prefs.introMusica = null
        Prefs.introMusicaNome = null
        Prefs.introInicio = 0
        msgMus = ""
        redesenha()
    }

    // ---------------- degrades prontos ----------------
    private fun gradientes(c: Context): View {
        val col = Ui.vbox(c)
        val lista = Temas.GRADIENTES
        var i = 0
        while (i < lista.size) {
            val linha = Ui.hbox(c)
            for (k in i until minOf(i + 6, lista.size)) {
                val par = lista[k]
                val sel = (par.first and 0xFFFFFF) == (Prefs.accent and 0xFFFFFF) && (par.second and 0xFFFFFF) == (Prefs.accent2 and 0xFFFFFF)
                val b = View(c)
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(par.first, par.second))
                g.shape = GradientDrawable.OVAL
                g.setStroke(Ui.dp(c, if (sel) 3 else 1), if (sel) Color.WHITE else Theme.alpha(Color.WHITE, 0x44))
                b.background = g
                Ui.clique(b) { Prefs.accent = par.first; Prefs.accent2 = par.second; muda() }
                val lp = LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36))
                lp.rightMargin = Ui.dp(c, 12)
                linha.addView(b, lp)
            }
            col.addView(linha, Ui.lp(top = if (i == 0) 0 else 12, c = c))
            i += 6
        }
        return col
    }

    // ---------------- fontes ----------------
    private fun fontes(c: Context): View {
        val col = Ui.vbox(c)
        val lista = Packs.lista
        var i = 0
        while (i < lista.size) {
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.TOP
            for (k in i until i + 2) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (k % 2 == 1) lp.leftMargin = Ui.dp(c, 10)
                if (k < lista.size) linha.addView(celulaFonte(c, lista[k]), lp) else linha.addView(View(c), lp)
            }
            col.addView(linha, Ui.lp(top = if (i == 0) 0 else 10, c = c))
            i += 2
        }
        return col
    }

    private fun celulaFonte(c: Context, p: Pack): View {
        val sel = Prefs.fonte == p.id
        val b = Ui.vbox(c)
        b.background = Ui.ripple(c, Ui.glass(c, 14, forte = false, ligado = sel), Painel(c, raioDp = 14, mascara = true))
        val pd = Ui.dp(c, 12)
        b.setPadding(pd, Ui.dp(c, 10), pd, Ui.dp(c, 10))
        val amostra = android.widget.TextView(c)
        amostra.text = "Aa"
        amostra.setTextColor(if (sel) Ui.RED else Ui.TEXT)
        amostra.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f * p.escala)
        amostra.typeface = Theme.fonteDe(c, p, true, true)
        amostra.includeFontPadding = false
        b.addView(amostra)
        val nome = Ui.text(c, p.nome, 11f, if (sel) Ui.RED else Ui.MUTED, true, mono = true)
        nome.maxLines = 1
        b.addView(nome, Ui.lp(top = 6, c = c))
        Ui.clique(b) { Prefs.fonte = p.id; muda() }
        return b
    }

    // ---------------- fundo ----------------
    private fun secaoFundo(c: Context): View {
        val f = secao(c, "fundo")
        val tipos = listOf("animado", "imagem", "vídeo", "liso")
        val atual = when (Prefs.bgTipo) { "imagem" -> 1; "video" -> 2; "solido" -> 3; else -> 0 }
        f.addView(Ui.segmento(c, tipos, atual) { i ->
            Prefs.bgTipo = when (i) { 1 -> "imagem"; 2 -> "video"; 3 -> "solido"; else -> "animado" }
            fundo()
        }, Ui.lp(top = 12, c = c))

        when (Prefs.bgTipo) {
            "animado" -> {
                f.addView(Ui.text(c, "Desenhos do próprio app. Eles seguem as cores 1 e 2.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
                val cartas = ArrayList<View>()
                for (id in Temas.FUNDOS) {
                    val sel = Prefs.bgPreset == id
                    val box = Ui.vbox(c)
                    box.addView(Miniatura.fundo(c, id, sel), LinearLayout.LayoutParams(Ui.dp(c, 82), Ui.dp(c, 108)))
                    val tn = Ui.text(c, Temas.nomeFundo(id), 11f, if (sel) Ui.RED else Ui.MUTED, true, mono = true)
                    tn.gravity = Gravity.CENTER
                    tn.maxLines = 1
                    box.addView(tn, Ui.lp(top = 6, c = c))
                    Ui.clique(box) { Prefs.bgPreset = id; Prefs.tema = "custom"; fundo() }
                    val lp = LinearLayout.LayoutParams(Ui.dp(c, 82), ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.rightMargin = Ui.dp(c, 10)
                    box.layoutParams = lp
                    cartas.add(box)
                }
                f.addView(linhaRolavel(c, cartas), Ui.lp(top = 10, c = c))
            }
            "imagem", "video" -> {
                val video = Prefs.bgTipo == "video"
                val tem = arquivo() != null
                val atualTxt = arquivo()?.let { (if (video) "vídeo escolhido" else "imagem escolhida") + " · " + mb(it) }
                    ?: "nada escolhido ainda, por isso o fundo animado aparece"
                f.addView(Ui.text(c, atualTxt, 12.5f, if (tem) Ui.OK else Ui.WARN, true, mono = true), Ui.lp(top = 10, c = c))
                f.addView(Ui.button(c, if (ocupado) "copiando..." else (if (video) "escolher vídeo da galeria" else "escolher imagem da galeria"), Ui.Kind.PRIMARY, if (video) R.drawable.ic_video else R.drawable.ic_image) {
                    if (!ocupado) escolher(video)
                }, Ui.lp(top = 10, c = c))
                if (tem) f.addView(Ui.button(c, "remover e voltar ao animado", Ui.Kind.GHOST, R.drawable.ic_trash) { remove() }, Ui.lp(top = 8, c = c))
                f.addView(Ui.text(c, if (video)
                    "O vídeo toca em loop, sem som. Vídeos curtos e leves (até 1080p) gastam menos bateria. O arquivo é copiado para dentro do app e nada é enviado pela internet."
                else
                    "A imagem é copiada para dentro do app e nada é enviado pela internet.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
            }
            else -> f.addView(Ui.text(c, "Só a cor de fundo do app, sem nenhum desenho. A cor muda em CORES.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
        }
        if (msg.isNotEmpty()) f.addView(Ui.text(c, msg, 12.5f, Ui.WARN, true), Ui.lp(top = 8, c = c))

        f.addView(Ui.slider(c, "Escurecer o fundo", Prefs.bgEscuro, 0, 90, "%", onMove = { x ->
            Prefs.bgEscuro = x
            act.ajustaFundo()
        }, onFim = { x -> Prefs.bgEscuro = x; act.ajustaFundo() }), Ui.lp(top = 16, c = c))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            f.addView(Ui.slider(c, "Desfoque do fundo", Prefs.bgBlur, 0, 25, "", onMove = { x ->
                Prefs.bgBlur = x
                act.ajustaFundo()
            }, onFim = { x -> Prefs.bgBlur = x; act.ajustaFundo() }), Ui.lp(top = 12, c = c))
        } else {
            f.addView(Ui.text(c, "O desfoque do fundo precisa do Android 12 ou mais novo.", 12f, Ui.MUTED), Ui.lp(top = 12, c = c))
        }
        return f
    }

    private fun restaura() {
        Temas.aplica(Temas.get("brasa"))
        Prefs.bgTipo = "animado"
        Prefs.bgEscuro = 25
        Prefs.bgBlur = 0
        Prefs.escala = 100
        Prefs.maiusc = true
        Prefs.anim = true
        Prefs.particulas = true
        Prefs.pulso = true
        Prefs.vibrar = false
        msg = ""
        act.reaplica()
    }

    private fun corPropria(qual: Int) {
        Ui.dialogTexto(act, "COR PRÓPRIA", "#RRGGBB (ex.: #FF2D55)") { s ->
            val t = s.trim().let { if (it.startsWith("#")) it else "#$it" }
            val cor = try { Color.parseColor(t) } catch (_: Exception) { null }
            if (cor == null) Ui.toast(act, "Cor inválida. Use 6 letras/números, como FF2D55.")
            else {
                when (qual) {
                    1 -> Prefs.accent = cor
                    2 -> Prefs.accent2 = cor
                    else -> Prefs.baseCor = cor
                }
                muda()
            }
        }
    }

    // ---------------- escolher video / imagem da galeria ----------------
    @Suppress("DEPRECATION")
    private fun escolher(video: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (video) "video/*" else "image/*"
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_MIDIA && res == Activity.RESULT_OK && u != null) copia(u, video)
        }
        try {
            act.startActivityForResult(i, REQ_MIDIA)
        } catch (_: Exception) {
            Ui.toast(act, "Não consegui abrir a galeria")
        }
    }

    private class Copia(val nome: String?, val erro: String)

    private fun copia(uri: Uri, video: Boolean) {
        ocupado = true
        msg = ""
        redesenha()
        val ctx = act.applicationContext
        Bg.run({ copiar(ctx, uri, video) }) { r ->
            ocupado = false
            if (r.nome != null) {
                val velho = Prefs.bgArquivo
                Prefs.bgArquivo = r.nome
                Prefs.bgTipo = if (video) "video" else "imagem"
                if (velho != null && velho != r.nome) try { File(ctx.filesDir, velho).delete() } catch (_: Exception) {}
                msg = ""
                NLog.add("visual", "fundo novo: ${r.nome}")
                act.refazFundo()
            } else msg = r.erro
            redesenha()
        }
    }

    private fun copiar(c: Context, uri: Uri, video: Boolean): Copia {
        val nome = "fundo_" + System.currentTimeMillis() + (if (video) ".mp4" else ".img")
        val dest = File(c.filesDir, nome)
        return try {
            val ins = c.contentResolver.openInputStream(uri) ?: return Copia(null, "Não consegui abrir esse arquivo.")
            ins.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            if (dest.length() > LIMITE_MB * 1048576L) {
                dest.delete()
                Copia(null, "Arquivo grande demais (mais de $LIMITE_MB MB). Escolha um menor.")
            } else if (dest.length() == 0L) {
                dest.delete()
                Copia(null, "O arquivo veio vazio.")
            } else Copia(nome, "")
        } catch (t: Throwable) {
            try { dest.delete() } catch (_: Exception) {}
            Copia(null, "Não consegui copiar: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun remove() {
        val a = Prefs.bgArquivo
        if (a != null) try { File(act.filesDir, a).delete() } catch (_: Exception) {}
        Prefs.bgArquivo = null
        Prefs.bgTipo = "animado"
        msg = ""
        fundo()
    }
}
