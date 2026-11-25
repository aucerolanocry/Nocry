package com.nocry.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela do SNIPER (só dono), em 3 abas:
 *  PARTIDAS  salas abertas, chat, respostas rápidas, confirmar/cancelar
 *  BUSCA     org, gelo, modo e valor + o que está acontecendo em cada org
 *  CONTA     token, ligar/desligar, ajustes, ferramentas e registro
 *
 * Cada pedaço da tela só é refeito quando o que ele mostra muda (antes a tela inteira era refeita
 * várias vezes por segundo e travava). O chat só ganha as mensagens novas no fim.
 */
class SniperScreen(a: MainActivity) : Screen(a) {
    override val titulo = "BOT DE FILA"

    private var abaAtual = 0
    /** lista "suas orgs": as que já estão na conta ficam juntas numa linha até abrir */
    private var orgsAbertas = false

    /** abre direto numa aba (0 partidas, 1 busca, 2 conta) */
    fun irPraAba(i: Int) { abaAtual = i.coerceIn(0, 2) }
    private var geracao = 0

    /** pedaço que se refaz sozinho quando a "assinatura" (o que ele mostra) muda */
    private inner class Pedaco(val assinatura: () -> String, val montar: (LinearLayout) -> Unit) {
        val box: LinearLayout = Ui.vbox(act)
        private var ultima: String? = null
        fun atualiza() {
            val sig = try { assinatura() } catch (_: Throwable) { "" }
            if (sig == ultima) return
            ultima = sig
            box.removeAllViews()
            try { montar(box) } catch (t: Throwable) { NLog.add("bot de fila", "tela: $t") }
        }
    }

    private val pedacos = ArrayList<Pedaco>()
    private fun pedaco(assinatura: () -> String, montar: (LinearLayout) -> Unit): View {
        val p = Pedaco(assinatura, montar)
        pedacos.add(p)
        p.atualiza()
        return p.box
    }

    // partes que não podem ser refeitas (o teclado fecharia / a rolagem voltaria)
    private var campoMsg: EditText? = null
    private var campoToken: EditText? = null
    private var chatCard: View? = null
    private var chatRolagem: ScrollView? = null
    private var chatLista: LinearLayout? = null
    private var chatSala = ""
    /** ids das bolhas que estão na tela, na ordem (pra só pôr as novas, sem refazer o chat) */
    private val chatIds = ArrayList<String>()
    /** aviso "mensagens anteriores" no alto do chat (some quando a conversa toda já está na tela) */
    private var chatTopo: TextView? = null
    private var carregandoAnteriores = false
    private val CHAT_JANELA = 120
    private val CHAT_LOTE = 100
    private var tempoTxt: TextView? = null
    private var contagemTxt: TextView? = null

    private val largura: Int get() = act.resources.displayMetrics.widthPixels
    private val altura: Int get() = act.resources.displayMetrics.heightPixels

    // ======================================================================== montagem
    override fun render() {
        limpa()
        pedacos.clear()
        chatLista = null
        chatCard = null
        chatSala = ""
        chatIds.clear()
        chatTopo = null
        contagemTxt = null
        val c = act
        Bg.io { if (Sniper.restaurarSozinho()) Bg.ui { for (p in pedacos) p.atualiza() } }
        add(pedaco({ assinaturaTopo() }) { topo(it) }, 4)
        add(pedaco({ Sniper.formularios.joinToString("|") { it.nonce } }) { formularios(it) }, 0)
        add(pedaco({ "$abaAtual|${Sniper.salas.values.sumOf { it.naoLidas }}" }) { abasBot(it) }, 14)
        when (abaAtual) {
            0 -> abaPartidas()
            1 -> abaBusca()
            else -> abaConta()
        }
        val g = ++geracao
        val loop = object : Runnable {
            override fun run() {
                if (g != geracao || !root.isAttachedToWindow) return
                // em segundo plano nao redesenha (antes ficava refazendo a tela a cada 0,6 s com o app minimizado)
                if (act.naFrente) tique()
                root.postDelayed(this, 600)
            }
        }
        root.postDelayed(loop, 600)
    }

    private fun tique() {
        for (p in pedacos) p.atualiza()
        atualizaChat()
        tempoTxt?.text = subTopo()
        contagemTxt?.let { t ->
            t.text = estadoSala()
            salaAtual()?.let { s -> t.setTextColor(estadoCurto(s).second) }
        }
    }

    private fun solta(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    private fun esconderTeclado() {
        val f = act.currentFocus ?: return
        (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(f.windowToken, 0)
    }

    /** texto normal (sem caixa alta), pro chat e explicações curtas */
    private fun texto(s: String, sp: Float, cor: Int, negrito: Boolean = false): TextView {
        val t = TextView(act)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Theme.escalaTexto())
        t.setTextColor(cor)
        t.typeface = Theme.fonte(act, negrito, negrito)
        t.setLineSpacing(0f, 1.18f)
        return t
    }

    /** explicação no balãozinho (igual ao resto do app) */
    /** observação curta: texto pequeno e discreto (menos caixa na tela, menos confusão) */
    private fun nota(s: String): TextView {
        val t = Ui.text(act, s, 11f, Theme.alpha(Ui.MUTED, 0xE0))
        t.setLineSpacing(0f, 1.15f)
        return t
    }

    /** tudo que explica o bot fica aqui (botão ? do topo), em vez de espalhado pela tela */
    private fun ajuda() {
        val c = act
        val box = Ui.vbox(c)
        val p = Ui.dp(c, 20)
        box.setPadding(p, Ui.dp(c, 6), p, Ui.dp(c, 6))
        fun secao(titulo: String, itens: List<String>) {
            box.addView(Ui.label(c, titulo), Ui.lp(top = 14, c = c))
            box.addView(Ui.lista(c, itens), Ui.lp(top = 8, c = c))
        }
        secao("busca", listOf(
            "Na aba Busca escolha a org (ou TODAS), os modos e os valores (de R$ 1 pra cima). Fila de R$ 7,50 entra no R$ 7 (as de ,50 vão junto com o valor normal).",
            "Iniciar busca: ele procura em todas as orgs ao mesmo tempo e entra em toda fila que bate.",
            "Só entra em fila Mobile: fila de Emulador ou Misto (dito na mensagem, no nome do canal ou da categoria) ele pula.",
            "Cada org tem um limite de filas: quando ela avisa, ele espera e tenta de novo sozinho."))
        secao("salas", listOf(
            "Formou partida: a sala aparece em Partidas, a mensagem automática sai na hora e chega notificação.",
            "Ninguém respondeu em ${Sniper.tempoResposta / 1000}s: ele cancela e volta a procurar.",
            "Ninguém confirmou em 3 min: a sala sai do painel sozinha (se ainda estão conversando, espera parar).",
            "Aposta confirmada (★) fica no painel até a org encerrar; aí sai sozinha 3 min depois."))
        secao("chat", listOf(
            "@ jogador e @ ADM põem a marcação no campo: escreve o resto e manda.",
            "Copiar: ícone no canto da mensagem, botões de link/chave pix/ID embaixo dela, ou segure o dedo no texto.",
            "Verificação anti-robô de algumas orgs: aparece em cima, um toque na resposta e entra."))
        val rola = ScrollView(c)
        rola.addView(box)
        android.app.AlertDialog.Builder(c, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("COMO O BOT DE FILA FUNCIONA")
            .setView(rola)
            .setPositiveButton("ENTENDI", null)
            .show()
    }

    private fun pesoUm(esq: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(act, esq) }

    /** botão quadradinho que marca/desmarca (modo, valor, sala) */
    private fun caixa(rot: String, sub: String?, sel: Boolean, ativo: Boolean = true, corTexto: Int? = null, aoTocar: () -> Unit): View {
        val c = act
        val b = Ui.vbox(c)
        b.gravity = Gravity.CENTER
        b.background = Botao(c, if (sel) "grad" else "ghost", Ui.raioPeq(12))
        b.setPadding(Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12))
        b.minimumHeight = Ui.dp(c, 52)
        val cor = corTexto ?: if (sel) Theme.sobreGrad() else if (ativo) Ui.TEXT else Theme.alpha(Ui.MUTED, 0x70)
        val t = Ui.text(c, rot, 13.5f, cor, true, mono = true)
        t.gravity = Gravity.CENTER
        b.addView(t)
        if (sub != null) {
            val s = texto(sub, 10f, if (sel) Theme.alpha(Theme.sobreGrad(), 0xC0) else Ui.MUTED)
            s.gravity = Gravity.CENTER
            s.maxLines = 1
            b.addView(s)
        }
        Ui.clique(b) { aoTocar() }
        return b
    }

    private fun grade(itens: List<View>, colunas: Int): View {
        val c = act
        val col = Ui.vbox(c)
        var i = 0
        while (i < itens.size) {
            val linha = Ui.hbox(c)
            for (k in 0 until colunas) {
                val v = itens.getOrNull(i + k) ?: View(c)
                linha.addView(v, pesoUm(if (k > 0) 6 else 0))
            }
            col.addView(linha, Ui.lp(top = if (i == 0) 0 else 6, c = c))
            i += colunas
        }
        return col
    }

    // ======================================================================== topo (sempre aparece)
    private fun assinaturaTopo() = listOf(Sniper.estado, Sniper.nomeConta, Sniper.ligado(), Sniper.buscando, Sniper.filasEntradas.size,
        Sniper.salas.size, Sniper.apostas, Sniper.maxFilas, Cofre.tem(act), Sniper.salas.values.count { it.naoLidas > 0 }).joinToString("|")

    private fun tempoBusca(): String {
        if (!Sniper.buscando) return "parado"
        val s = (System.currentTimeMillis() - Sniper.inicioBusca) / 1000
        return "buscando há " + if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun topo(b: LinearLayout) {
        val c = act
        val semToken = !Cofre.tem(c)
        val (estado, corPonto) = when {
            semToken -> "Sem token" to Theme.alpha(android.graphics.Color.WHITE, 0x55)
            Sniper.estado == "token" -> "Token recusado" to Ui.BAD
            Sniper.estado == "online" && Sniper.buscando -> "Buscando" to Ui.OK
            Sniper.estado == "online" -> "Online" to Ui.OK
            Sniper.estado == "conectando" -> "Conectando" to Ui.WARN
            Sniper.estado == "caiu" -> "Reconectando" to Ui.WARN
            else -> "Desligado" to Theme.alpha(android.graphics.Color.WHITE, 0x55)
        }
        val card = Ui.card(c, forte = true, ligado = Sniper.buscando)
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(Ui.caixaIcone(c, R.drawable.ic_crown, Sniper.buscando, 58), LinearLayout.LayoutParams(Ui.dp(c, 58), Ui.dp(c, 58)))
        val col = Ui.vbox(c)
        col.addView(Ui.label(c, "bot de fila"))
        val linha = Ui.hbox(c)
        linha.gravity = Gravity.CENTER_VERTICAL
        val ponto = View(c)
        ponto.background = Ui.shape(c, corPonto, 5)
        linha.addView(ponto, LinearLayout.LayoutParams(Ui.dp(c, 9), Ui.dp(c, 9)).also { it.rightMargin = Ui.dp(c, 9) })
        linha.addView(Ui.text(c, estado, 21f, if (Sniper.buscando) Ui.OK else Ui.TEXT, true).also { Ui.umaLinha(it, 12) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(linha, Ui.lp(top = 3, c = c))
        val sub = Ui.text(c, subTopo(), 10.5f, Ui.MUTED)
        tempoTxt = sub
        col.addView(sub, Ui.lp(top = 3, c = c))
        cab.addView(col, pesoUm(14))
        val aj = android.widget.FrameLayout(c)
        aj.background = Botao(c, "ico", Ui.raioPeq(12))
        aj.addView(Ui.icone(c, R.drawable.ic_info, Ui.MUTED, 17), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 17), Ui.dp(c, 17), Gravity.CENTER))
        aj.contentDescription = "como funciona"
        Ui.clique(aj) { ajuda() }
        cab.addView(aj, LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)).also { it.leftMargin = Ui.dp(c, 8); it.gravity = Gravity.TOP })
        card.addView(cab)

        val filas = if (Sniper.maxFilas >= 300) "${Sniper.filasEntradas.size}" else "${Sniper.filasEntradas.size}/${Sniper.maxFilas}"
        val tFilas = numero(R.drawable.ic_chip, "filas", filas, Sniper.filasEntradas.isNotEmpty())
        Ui.clique(tFilas) { if (abaAtual != 1) { abaAtual = 1; esconderTeclado(); render() } }
        val tSalas = numero(R.drawable.ic_gamepad, "salas", "${Sniper.salas.size}", Sniper.salas.isNotEmpty())
        Ui.clique(tSalas) { if (abaAtual != 0) { abaAtual = 0; esconderTeclado(); render() } }
        val tApostas = numero(R.drawable.ic_crown, "apostas", "${Sniper.apostas}", Sniper.apostas > 0)
        Ui.clique(tApostas) { if (abaAtual != 0) { abaAtual = 0; esconderTeclado(); render() } }
        card.addView(Ui.chips(c, tFilas, tSalas, tApostas), Ui.lp(top = 16, c = c))

        val botao = when {
            semToken -> Ui.button(c, "colar o token", Ui.Kind.PRIMARY, R.drawable.ic_key) { abaAtual = 2; render() }
            !Sniper.ligado() -> Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }
            Sniper.estado != "online" -> Ui.button(c, "conectando...", Ui.Kind.GHOST, R.drawable.ic_wifi) {}
            Sniper.buscando -> Ui.button(c, "pausar busca", Ui.Kind.DANGER, R.drawable.ic_stop) { Sniper.alternarBusca() }
            else -> Ui.button(c, "iniciar busca", Ui.Kind.PRIMARY, R.drawable.ic_play) { Sniper.alternarBusca() }
        }
        card.addView(botao, Ui.lp(top = 14, c = c))
        b.addView(card)
    }

    /** linha de baixo do topo: conta + tempo de busca (atualiza sozinha a cada 0,6 s) */
    private fun subTopo(): String = when {
        !Cofre.tem(act) -> "Cole o token da conta na aba Conta."
        Sniper.estado == "token" -> "O Discord recusou o token: troque na aba Conta."
        Sniper.estado == "online" -> "${Sniper.nomeConta} · ${tempoBusca()}"
        Sniper.estado == "conectando" || Sniper.estado == "caiu" -> "Entrando no Discord..."
        else -> "Toque em ligar o bot."
    }

    /** quadradinho de número do topo (filas, salas, apostas): aceso fica com o número em degradê */
    private fun numero(ico: Int, rotulo: String, valor: String, aceso: Boolean): View {
        val c = act
        val col = Ui.vbox(c)
        col.gravity = Gravity.CENTER_HORIZONTAL
        // ripple pra dar o toque e deixar claro que é clicável
        // cara de botão: tem coisa = verde aceso; vazio = cinza
        col.background = Ui.ripple(c, Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, if (aceso) 0x10 else 0x08), Ui.raioPeq(14),
            if (aceso) Theme.alpha(Theme.accent, 0x77) else Theme.alpha(android.graphics.Color.WHITE, 0x1C), 1))
        col.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 12))
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER
        cab.addView(Ui.icone(c, ico, if (aceso) Theme.accent else Ui.MUTED, 12))
        val r = Ui.text(c, rotulo, 9f, Ui.MUTED, true, mono = true)
        r.letterSpacing = 0.08f
        cab.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 5) })
        // setinha: avisa que dá pra tocar e abrir
        val seta = android.widget.FrameLayout(c)
        seta.background = Ui.shape(c, if (aceso) Ui.OK else Theme.alpha(android.graphics.Color.WHITE, 0x66), 99)
        seta.addView(Ui.icone(c, R.drawable.ic_chevron, 0xFF0D0F14.toInt(), 7), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 7), Ui.dp(c, 7), Gravity.CENTER))
        cab.addView(seta, LinearLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12)).also { it.leftMargin = Ui.dp(c, 5) })
        col.addView(cab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = if (aceso) Ui.gradTexto(c, valor, 22f) else Ui.text(c, valor, 22f, Ui.TEXT, true)
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = Ui.dp(c, 4); it.gravity = Gravity.CENTER_HORIZONTAL })
        return col
    }

    /** abas do bot (largura toda, ícone + nome; a de partidas mostra quantas mensagens novas) */
    private fun abasBot(b: LinearLayout) {
        val c = act
        val novas = Sniper.salas.values.sumOf { it.naoLidas }
        val itens = listOf(
            Triple(R.drawable.ic_gamepad, "Partidas", novas),
            Triple(R.drawable.ic_sliders, "Busca", 0),
            Triple(R.drawable.ic_user, "Conta", 0)
        )
        val r = Ui.hbox(c)
        r.background = Botao(c, "ghost")
        r.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4))
        for ((i, it) in itens.withIndex()) {
            val sel = i == abaAtual
            val cel = Ui.hbox(c)
            cel.gravity = Gravity.CENTER
            cel.setPadding(Ui.dp(c, 4), Ui.dp(c, 11), Ui.dp(c, 4), Ui.dp(c, 11))
            if (sel) cel.background = Botao(c, "grad", minOf(Prefs.raio, 14))
            val cor = if (sel) Theme.sobreGrad() else Ui.MUTED
            cel.addView(Ui.icone(c, it.first, cor, 15))
            val t = Ui.text(c, it.second, 11.5f, cor, true, mono = true)
            t.maxLines = 1
            cel.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { lp -> lp.leftMargin = Ui.dp(c, 7) })
            if (it.third > 0) {
                val badge = Ui.text(c, if (it.third > 99) "99+" else "${it.third}", 9.5f, android.graphics.Color.WHITE, true, mono = true)
                badge.gravity = Gravity.CENTER
                badge.background = Ui.shape(c, Ui.BAD, 9)
                badge.setPadding(Ui.dp(c, 6), Ui.dp(c, 1), Ui.dp(c, 6), Ui.dp(c, 1))
                cel.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { lp -> lp.leftMargin = Ui.dp(c, 6) })
            }
            Ui.clique(cel) { if (i != abaAtual) { abaAtual = i; esconderTeclado(); render() } }
            r.addView(cel, pesoUm(if (i > 0) 4 else 0))
        }
        b.addView(r)
    }

    // ======================================================================== formulário da org
    private fun formularios(b: LinearLayout) {
        val c = act
        val fs = Sniper.formularios.toList()
        if (fs.isEmpty()) return
        val card = Ui.card(c, forte = true)
        val verif = fs.count { it.verificacao }
        card.addView(Ui.text(c, when {
            fs.size == 1 && verif == 1 -> "🛡 verificação pra entrar na fila"
            fs.size == 1 -> "📝 a org pediu um formulário"
            else -> "📝 ${fs.size} esperando resposta"
        }, 13f, Ui.WARN, true, mono = true))
        card.addView(nota(if (verif > 0) "Sem responder, não entra na fila. Toque em responder: um toque na resposta e já vai."
            else "Sem responder, não entra na fila. Responda uma vez: o bot guarda e responde sozinho nas próximas."), Ui.lp(top = 4, c = c))
        for (f in fs.take(3)) {
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.CENTER_VERTICAL
            val info = Ui.vbox(c)
            info.addView(Ui.text(c, "${f.org} · ${f.titulo}", 12.5f, Ui.TEXT, true, mono = true))
            val sub = if (f.verificacao) f.campos.firstOrNull()?.dica?.ifEmpty { null } ?: f.campos.joinToString(" · ") { it.rotulo }
                else f.campos.joinToString(" · ") { it.rotulo }
            info.addView(texto(sub.ifEmpty { "sem campos" }, 11f, Ui.MUTED))
            linha.addView(info, pesoUm())
            linha.addView(Ui.button(c, "responder", Ui.Kind.PRIMARY) { dialogFormulario(f) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 8) })
            card.addView(linha, Ui.lp(top = 12, c = c))
        }
        if (fs.size > 3) card.addView(nota("+ ${fs.size - 3} esperando"), Ui.lp(top = 8, c = c))
        b.addView(card, Ui.lp(top = 12, c = c))
    }

    private fun dialogFormulario(f: SForm) {
        val c = act
        val box = Ui.vbox(c)
        val p = Ui.dp(c, 20)
        box.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8))
        box.addView(texto("${f.org} · ${f.nome}", 11.5f, Ui.MUTED))
        for (t in f.textos) box.addView(texto(t.replace(Regex("[#*_`>]+"), "").trim(), 12.5f, Ui.TEXT), Ui.lp(top = 8, c = c))
        // customId -> como ler a resposta
        val leitores = LinkedHashMap<String, () -> List<String>>()
        // verificação com 1 pergunta de escolha: tocou na resposta, já envia
        val umToque = f.verificacao && f.campos.size == 1 && f.campos[0].tipo in listOf(3, 21) && f.campos[0].maximo <= 1
        var enviarAgora: ((Map<String, List<String>>) -> Unit)? = null
        for (campo in f.campos) {
            box.addView(Ui.text(c, campo.rotulo + if (campo.obrigatorio) " *" else "", 12.5f, Ui.TEXT, true), Ui.lp(top = 14, c = c))
            if (campo.dica.isNotEmpty() && campo.dica != campo.rotulo) box.addView(texto(campo.dica, 11f, Ui.MUTED), Ui.lp(top = 2, c = c))
            val conhecida = if (f.verificacao) null else Sniper.respostaConhecida(campo)
            if (f.verificacao && campo.dica.isNotEmpty()) {
                // a pergunta em destaque
                (box.getChildAt(box.childCount - 1) as? TextView)?.let { tv ->
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * Theme.escalaTexto())
                    tv.setTextColor(Ui.TEXT)
                }
            }
            when (campo.tipo) {
                4 -> {
                    val e = Ui.field(c, campo.dica.ifEmpty { "resposta" }, false)
                    e.setText(conhecida?.firstOrNull() ?: campo.inicial)
                    if (campo.maximo in 1..3999) e.filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(campo.maximo))
                    box.addView(e, Ui.lp(top = 6, c = c))
                    leitores[campo.customId] = { listOf(e.text.toString().trim()) }
                }
                3, 21, 22, 23 -> {
                    val escolhidos = LinkedHashSet<String>(conhecida ?: campo.padrao)
                    val botoes = ArrayList<Pair<String, TextView>>()
                    fun pinta() {
                        for ((v, tv) in botoes) {
                            val sel = v in escolhidos
                            tv.background = Botao(c, if (sel) "grad" else "ghost", Ui.raioPeq(12))
                            tv.setTextColor(if (sel) Theme.sobreGrad() else Ui.TEXT)
                        }
                    }
                    for ((rot, v) in campo.opcoes) {
                        val tv = texto(rot, 16f, Ui.TEXT, true)
                        tv.gravity = Gravity.CENTER_VERTICAL
                        tv.minHeight = Ui.dp(c, 50)
                        tv.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12))
                        Ui.clique(tv) {
                            if (campo.tipo == 23) { if (!escolhidos.remove(v)) escolhidos.add(v) }
                            else if (campo.maximo <= 1) { escolhidos.clear(); escolhidos.add(v) }
                            else if (!escolhidos.remove(v) && escolhidos.size < campo.maximo) escolhidos.add(v)
                            pinta()
                            if (umToque) enviarAgora?.invoke(mapOf(campo.customId to listOf(v)))
                        }
                        botoes.add(v to tv)
                        box.addView(tv, Ui.lp(top = 6, c = c))
                    }
                    pinta()
                    leitores[campo.customId] = { escolhidos.toList() }
                }
                else -> {
                    box.addView(texto("esse campo o app não sabe responder · se for obrigatório, responda pelo Discord", 11f, Ui.BAD), Ui.lp(top = 4, c = c))
                    leitores[campo.customId] = { emptyList() }
                }
            }
        }
        val rola = ScrollView(c)
        rola.addView(box)
        val dlg = android.app.AlertDialog.Builder(c, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(f.titulo)
            .setView(rola)
            .setPositiveButton(if (f.verificacao) "ENVIAR" else "ENVIAR E LEMBRAR", null)
            .setNegativeButton("DEPOIS", null)
            .setNeutralButton("IGNORAR") { _, _ -> Sniper.ignorarFormulario(f) }
            .create()
        enviarAgora = { valores ->
            Sniper.responderPeloApp(f, valores)
            dlg.dismiss()
        }
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val valores = HashMap<String, List<String>>()
                for ((cid, ler) in leitores) valores[cid] = ler()
                val falta = f.campos.firstOrNull { campo ->
                    val v = valores[campo.customId] ?: emptyList()
                    campo.obrigatorio && (v.isEmpty() || v.all { it.isBlank() } || (campo.tipo == 4 && (v.firstOrNull()?.length ?: 0) < campo.minimo))
                }
                if (falta != null) { Ui.toast(c, "preencha: ${falta.rotulo}"); return@setOnClickListener }
                Sniper.responderPeloApp(f, valores)
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    // ======================================================================== PARTIDAS
    private fun abaPartidas() {
        val c = act
        add(pedaco({ assinaturaSalas() }) { salas(it) }, 12)

        // chat (montado uma vez só)
        val card = Ui.card(c)
        card.addView(pedaco({ Sniper.salaAtiva ?: "-" }) { cabecaChat(it) })
        card.addView(pedaco({ assinaturaAcoes() }) { acoesTopo(it) }, Ui.lp(top = 10, c = c))

        val sv = object : ScrollView(c) {
            private var yAnt = 0f
            private var tAnt = -1L

            /** rola o chat enquanto ele tem pra onde ir; no topo/fim, quem rola é a página (antes o chat travava a tela) */
            private fun decide(ev: MotionEvent) {
                if (ev.eventTime == tAnt && ev.actionMasked == MotionEvent.ACTION_MOVE) return
                tAnt = ev.eventTime
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { yAnt = ev.y; parent?.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.y - yAnt
                        yAnt = ev.y
                        val podeChat = when {
                            dy > 0 -> canScrollVertically(-1)
                            dy < 0 -> canScrollVertically(1)
                            else -> true
                        }
                        parent?.requestDisallowInterceptTouchEvent(podeChat)
                    }
                }
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                decide(ev)
                return super.onTouchEvent(ev)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                decide(ev)
                return super.onInterceptTouchEvent(ev)
            }
        }
        sv.isVerticalScrollBarEnabled = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.background = Botao(c, "campo", Ui.raioPeq(14))
        val lista = Ui.vbox(c)
        val p = Ui.dp(c, 10)
        lista.setPadding(p, p, p, p)
        sv.addView(lista, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (altura * 0.40).toInt()).also { it.topMargin = Ui.dp(c, 10) })
        chatRolagem = sv
        chatLista = lista
        // chegou no topo do chat: põe as mensagens anteriores (a conversa da sala fica inteira)
        sv.setOnScrollChangeListener { _, _, y, _, _ -> if (y < Ui.dp(c, 60) && chatTopo != null) carregarAnteriores() }

        // respostas rápidas (um toque e já manda)
        val hs = HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val rapidas = Ui.hbox(c)
        val frases = listOf("Go", "Bora", "Full UMP e XM8?", "Sem mobilador", "Regras ok ✅", "Cria a sala", "Manda o ID", "Pronto")
        for ((i, f) in frases.withIndex()) {
            val t = texto(f, 11.5f, Ui.TEXT, true)
            t.background = Ui.ripple(c, Ui.shape(c, Theme.alpha(Theme.accent, 0x14), Ui.raioPeq(20), Theme.alpha(Theme.accent, 0x5A), 1),
                Ui.shape(c, android.graphics.Color.WHITE, Ui.raioPeq(20)))
            t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6))
            Ui.clique(t) { mandar(f) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = Ui.dp(c, 6)
            rapidas.addView(t, lp)
        }
        hs.addView(rapidas)
        card.addView(hs, Ui.lp(top = 8, c = c))

        // escrever: uma linha só com [imagem] [@] [mensagem] [enviar]. O @ abre: jogador, ADM ou outra pessoa
        val linha = Ui.hbox(c)
        linha.gravity = Gravity.CENTER_VERTICAL
        val e = campoMsg ?: Ui.field(c, "mensagem", false).also {
            it.imeOptions = EditorInfo.IME_ACTION_SEND
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            it.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviarCampo(); true } else false }
            campoMsg = it
        }
        solta(e)
        e.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
        val tam = Ui.dp(c, 44)
        fun redondo(ico: Int, desc: String, forte: Boolean, acao: () -> Unit): View {
            val f = android.widget.FrameLayout(c)
            f.background = if (forte) Botao(c, "grad", 99) else Botao(c, "ghost", 99)
            f.addView(Ui.icone(c, ico, if (forte) Theme.sobreGrad() else Ui.TEXT, 19), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19), Gravity.CENTER))
            f.contentDescription = desc
            Ui.clique(f) { acao() }
            return f
        }
        linha.addView(redondo(R.drawable.ic_image, "enviar imagem", false) { escolherImagem() }, LinearLayout.LayoutParams(tam, tam).also { it.rightMargin = Ui.dp(c, 6) })
        linha.addView(redondo(R.drawable.ic_user, "marcar alguém", false) {
            Ui.dialogEscolha(act, "MARCAR", listOf("@ jogador", "@ ADM", "@ outra pessoa")) { i ->
                when (i) { 0 -> marcarPapel("jogador"); 1 -> marcarPapel("adm"); else -> escolherPessoa(null) }
            }
        }, LinearLayout.LayoutParams(tam, tam).also { it.rightMargin = Ui.dp(c, 6) })
        linha.addView(e, pesoUm())
        linha.addView(redondo(R.drawable.ic_up, "enviar", true) { enviarCampo() }, LinearLayout.LayoutParams(tam, tam).also { it.leftMargin = Ui.dp(c, 6) })
        card.addView(linha, Ui.lp(top = 8, c = c))

        card.addView(pedaco({ assinaturaAcoes() }) { acoes(it) }, Ui.lp(top = 12, c = c))
        chatCard = card
        card.visibility = if (Sniper.ordem.isEmpty()) View.GONE else View.VISIBLE
        add(card, 12)

        atualizaChat()
    }

    private fun salaAtual(): SSala? = Sniper.salaAtiva?.let { Sniper.salas[it] }

    /** topo do chat: org e nome da sala + estado que conta os segundos */
    private fun cabecaChat(b: LinearLayout) {
        val c = act
        val s = salaAtual()
        val l = Ui.hbox(c)
        l.gravity = Gravity.CENTER_VERTICAL
        l.addView(Ui.caixaIcone(c, R.drawable.ic_gamepad, s?.protegida == true, 36), LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)).also { it.rightMargin = Ui.dp(c, 10) })
        val col = Ui.vbox(c)
        col.addView(Ui.text(c, if (s == null) "chat da sala" else "sala ${Sniper.numeroSala(s.id)} · ${s.org}", 14f, Ui.TEXT, true))
        if (s != null) {
            val n = texto(if (s.fila.isNotEmpty()) "${s.fila} · ${s.nome}" else s.nome, 11f, Ui.MUTED)
            n.maxLines = 1
            n.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(n, Ui.lp(top = 1, c = c))
        }
        l.addView(col, pesoUm())
        b.addView(l)
        val ct = Ui.text(c, estadoSala(), 10.5f, Ui.WARN, true, mono = true)
        ct.background = Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x0A), Ui.raioPeq(10), Theme.alpha(android.graphics.Color.WHITE, 0x1C), 1)
        ct.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6))
        ct.gravity = Gravity.CENTER
        contagemTxt = ct
        b.addView(ct, Ui.lp(top = 10, c = c))

    }

    /** logo abaixo do tempo: confirmar/cancelar grandes e os atalhos da sala (pix, go, id/senha, discord) */
    private fun acoesTopo(b: LinearLayout) {
        val c = act
        val sa = salaAtual() ?: return
        if (sa.encerrada) {
            b.addView(Ui.dica(c, "A org já fechou essa sala (cancelou ou acabou). Ela some sozinha em 3 min.", Ui.Dica.AVISO))
            b.addView(Ui.button(c, "tirar do painel agora", Ui.Kind.GHOST, R.drawable.ic_trash) { Sniper.largar() }, Ui.lp(top = 8, c = c))
            return
        }
        val linha = Ui.hbox(c)
        linha.addView(Ui.button(c, "confirmar", Ui.Kind.PRIMARY, R.drawable.ic_check) { Sniper.confirmar() }, pesoUm())
        linha.addView(Ui.button(c, "cancelar", Ui.Kind.DANGER, R.drawable.ic_xcircle) {
            Ui.dialogConfirma(c, "CANCELAR SALA", "Ele clica em cancelar/sair na sala e volta a procurar nessa org.", "CANCELAR") { Sniper.cancelar() }
        }, pesoUm(8))
        b.addView(linha)
        val semGo = sa.temGo == false && !Sniper.orgTemGo(sa.org)
        val at = Ui.hbox(c)
        at.addView(atalho("pix", R.drawable.ic_copy, false) { pixDaSala(sa) }, pesoUm())
        at.addView(atalho(if (sa.goAuto) "go ligado" else if (semGo) "sem go" else "go auto", R.drawable.ic_play, sa.goAuto) {
            Ui.toast(act, if (sa.goAuto) "desligando..." else "procurando o botão de go...")
            Sniper.goAutomatico(!sa.goAuto) { msg -> Bg.ui { Ui.toast(act, msg) } }
        }, pesoUm(6))
        at.addView(atalho("id/senha", R.drawable.ic_key, false) {
            val v = idESenhaDaSala(sa)
            if (v != null) copiar(v, "id e senha copiados") else Ui.toast(act, "não achei id/senha no chat dessa sala")
        }, pesoUm(6))
        at.addView(atalho("discord", R.drawable.ic_globe, false) { abrirNoDiscord(sa) }, pesoUm(6))
        // pediram tela: o link do Google Meet que mandaram na sala (acende quando tem)
        val meet = sa.chat.toList().asReversed().firstNotNullOfOrNull { m -> Meet.achar(m.texto + "\n" + m.card) }
        at.addView(atalho("meet", R.drawable.ic_video, meet != null) {
            if (meet == null) Ui.toast(act, "ninguém mandou link do Meet nessa sala")
            else { Ui.toast(act, "link do meet copiado"); Meet.abrir(act, meet) }
        }, pesoUm(6))
        b.addView(at, Ui.lp(top = 8, c = c))
    }

    /** botãozinho quadrado: ícone em cima, nome embaixo (aceso quando ligado) */
    private fun atalho(rot: String, ico: Int, ligado: Boolean, acao: () -> Unit): View {
        val c = act
        val b = Ui.vbox(c)
        b.gravity = Gravity.CENTER
        val borda = if (ligado) Theme.alpha(Theme.accent, 0xCC) else Theme.alpha(android.graphics.Color.WHITE, 0x24)
        b.background = Ui.ripple(c, Ui.shape(c, Theme.alpha(if (ligado) Theme.accent else android.graphics.Color.WHITE, if (ligado) 0x26 else 0x0A), Ui.raioPeq(14), borda, 1),
            Ui.shape(c, android.graphics.Color.WHITE, Ui.raioPeq(14)))
        b.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 9))
        b.addView(Ui.icone(c, ico, if (ligado) Theme.accent else Ui.TEXT, 19), LinearLayout.LayoutParams(Ui.dp(c, 19), Ui.dp(c, 19)))
        val t = Ui.text(c, rot, 10.5f, Ui.TEXT, true, mono = true)
        t.gravity = Gravity.CENTER
        Ui.umaLinha(t, 8)
        b.addView(t, Ui.lp(top = 5, c = c))
        b.contentDescription = rot
        Ui.clique(b) { acao() }
        return b
    }

    /**
     * pix da sala: pega a chave que o ADM (ou o bot da org) mandou, copia na hora e já mostra o QR com o valor da sala.
     * Se nenhum ADM mandou chave ainda, abre pra colar/digitar.
     */
    private fun pixDaSala(sa: SSala) {
        // quem pode ter mandado a chave: ADM primeiro, depois o bot da org, depois quem ainda não se sabe (mediador
        // que não foi marcado). Nunca jogador nem você. Qualquer tipo de chave vale (e-mail, CPF, CNPJ, telefone, aleatória)
        val tipos = setOf("chave pix", "e-mail", "CPF", "CNPJ", "telefone")
        fun prioridade(m: SChat): Int {
            val papel = sa.pessoas[m.autorId]?.papel
            return when {
                m.tipo == "voce" || papel == "jogador" -> -1
                papel == "adm" -> 3
                m.tipo == "bot" -> 2
                else -> 1
            }
        }
        val msgs = sa.chat.toList().asReversed()
        var melhor: String? = null
        var nota = 0
        for (m in msgs) {
            val p = prioridade(m)
            if (p <= nota) continue
            val k = copiaveis(m.texto + "\n" + m.card).firstOrNull { it.first in tipos }?.second ?: continue
            melhor = k; nota = p
            if (p == 3) break
        }
        // valor: o que o ADM escreveu ("Valor a ser pago: R$ 3,50"); se não tiver, o da fila
        val valorMsg = msgs.filter { prioridade(it) > 0 }.firstNotNullOfOrNull { m ->
            Caderno.valorDe(m.texto + " " + m.card).takeIf { it > 0 }
        }
        val valor = valorMsg ?: Caderno.valorDe(sa.fila)
        if (melhor != null) {
            copiar(Pix.normaliza(melhor), "chave pix do ADM copiada")
            Pix.mostrar(act, melhor, valor)
        } else {
            Ui.toast(act, "o ADM ainda não mandou chave pix")
            Pix.abrir(act, null, valor)
        }
    }

    /** acha o id e a senha mais recentes no chat da sala (pra copiar os dois juntos) */
    private fun idESenhaDaSala(s: SSala): String? {
        var id: String? = null
        var senha: String? = null
        for (m in chatLinhas(s).asReversed()) {
            for ((rot, v) in copiaveis(m.texto)) {
                if (senha == null && rot == "senha") senha = v
                if (id == null && (rot == "ID" || rot == "sala" || rot == "código")) id = v
            }
            if (id != null && senha != null) break
        }
        return when {
            id != null && senha != null -> "ID: $id\nSenha: $senha"
            id != null -> "ID: $id"
            senha != null -> "Senha: $senha"
            else -> null
        }
    }

    /** abre o Discord direto no canal exato dessa sala (na org certa) */
    private fun abrirNoDiscord(s: SSala) {
        val g = s.guildId ?: "@me"
        val url = "https://discord.com/channels/$g/${s.id}"
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            Ui.toast(act, "não consegui abrir o Discord")
        }
    }

    private fun estadoSala(): String {
        val s = salaAtual() ?: return ""
        val agora = System.currentTimeMillis()
        val saiEm = mmss(Sniper.SALA_SEM_CONFIRMAR - (agora - s.aberta))
        return when {
            s.encerrada -> (if (s.apostaOk) "★ CONFIRMADA · A ORG ENCERROU" else "✗ A ORG ENCERROU") +
                " · SAI EM ${mmss(Sniper.SALA_SEM_CONFIRMAR - (agora - s.ultima))}"
            s.apostaOk -> "★ APOSTA CONFIRMADA"
            s.confirmada -> "✓ VOCÊ CONFIRMOU"
            s.somenteLeitura -> "SÓ LEITURA · SAI EM $saiEm SE NINGUÉM CONFIRMAR"
            s.respondida -> if (agora - s.aberta < Sniper.SALA_SEM_CONFIRMAR) "ADVERSÁRIO RESPONDEU · SEM CONFIRMAR SAI EM $saiEm"
                else "SEM CONFIRMAR · SAI QUANDO A CONVERSA PARAR"
            else -> {
                val falta = ((Sniper.tempoResposta - (System.currentTimeMillis() - s.aberta)) / 1000).coerceAtLeast(0)
                "ESPERANDO · CANCELA EM ${falta}s"
            }
        }
    }

    private fun assinaturaSalas(): String {
        val agora = System.currentTimeMillis()
        val sb = StringBuilder(Sniper.salaAtiva ?: "-")
        for (id in Sniper.ordem) {
            val s = Sniper.salas[id] ?: continue
            sb.append('|').append(id).append(s.fila).append(s.naoLidas).append(s.respondida).append(s.apostaOk).append(s.confirmada).append(s.somenteLeitura).append(s.encerrada)
                .append((agora - s.aberta) / 10_000)
        }
        return sb.toString()
    }

    private fun salas(b: LinearLayout) {
        val c = act
        val ids = Sniper.ordem.toList()
        chatCard?.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        if (ids.isEmpty()) {
            val card = Ui.card(c)
            card.gravity = Gravity.CENTER_HORIZONTAL
            card.addView(Ui.caixaIcone(c, R.drawable.ic_gamepad, Sniper.buscando, 52), LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
            val t = Ui.text(c, if (Sniper.buscando) "Procurando partida..." else "Nenhuma sala aberta", 16f, Ui.TEXT, true)
            t.gravity = Gravity.CENTER
            card.addView(t, Ui.lp(top = 12, c = c))
            val n = nota(if (Sniper.buscando) "Quando formar partida, a sala aparece aqui e chega notificação."
                else "Escolha org, modo e valor na aba Busca e toque em iniciar busca.")
            n.gravity = Gravity.CENTER
            card.addView(n, Ui.lp(top = 6, c = c))
            b.addView(card)
            return
        }
        b.addView(Ui.secao(c, "salas abertas · ${ids.size}"))
        val itens = ids.mapIndexedNotNull { i, id ->
            val s = Sniper.salas[id] ?: return@mapIndexedNotNull null
            salaTile(i, s, id == Sniper.salaAtiva)
        }
        b.addView(grade(itens, 2), Ui.lp(top = 8, c = c))
    }

    private fun mmss(ms: Long): String {
        val seg = (ms / 1000).coerceAtLeast(0)
        return "${seg / 60}:${(seg % 60).toString().padStart(2, '0')}"
    }

    /** cor e texto curto do estado da sala (usado no quadradinho e no chat) */
    private fun estadoCurto(s: SSala): Pair<String, Int> {
        val agora = System.currentTimeMillis()
        return when {
            s.encerrada -> "a org encerrou" to Theme.alpha(Ui.MUTED, 0xB0)
            s.apostaOk -> "★ aposta confirmada" to Ui.OK
            s.confirmada -> "✓ você confirmou" to Ui.OK
            s.somenteLeitura -> "só leitura" to Ui.MUTED
            s.respondida -> "adversário respondeu" to Ui.ACC2
            agora - s.aberta > Sniper.tempoResposta - 10_000 -> "cancelando já" to Ui.BAD
            else -> "esperando resposta" to Ui.WARN
        }
    }

    private fun salaTile(i: Int, s: SSala, sel: Boolean): View {
        val c = act
        val (txt, cor) = estadoCurto(s)
        val col = Ui.vbox(c)
        col.background = if (sel) Botao(c, "grad", Ui.raioPeq(14))
            else Ui.shape(c, Theme.alpha(Theme.mix(0xFF0B0A12.toInt(), cor, 0.10f), 0xCC), Ui.raioPeq(14), Theme.alpha(cor, 0x88), 1)
        col.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 11))
        val l1 = Ui.hbox(c)
        l1.gravity = Gravity.CENTER_VERTICAL
        val ponto = View(c)
        ponto.background = Ui.shape(c, if (sel) Theme.sobreGrad() else cor, 4)
        l1.addView(ponto, LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)).also { it.rightMargin = Ui.dp(c, 7) })
        l1.addView(Ui.text(c, "sala ${i + 1}", 11f, if (sel) Theme.alpha(Theme.sobreGrad(), 0xC0) else Ui.MUTED, true, mono = true), pesoUm())
        if (s.naoLidas > 0) {
            val badge = Ui.text(c, "${s.naoLidas}", 9.5f, android.graphics.Color.WHITE, true, mono = true)
            badge.background = Ui.shape(c, Ui.BAD, 9)
            badge.setPadding(Ui.dp(c, 6), Ui.dp(c, 1), Ui.dp(c, 6), Ui.dp(c, 1))
            l1.addView(badge)
        }
        col.addView(l1)
        val org = Ui.text(c, s.org, 14f, if (sel) Theme.sobreGrad() else Ui.TEXT, true)
        org.maxLines = 1
        org.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(org, Ui.lp(top = 5, c = c))
        if (s.fila.isNotEmpty()) {
            val fl = Ui.text(c, s.fila, 11f, if (sel) Theme.alpha(Theme.sobreGrad(), 0xE0) else Theme.accent, true, mono = true)
            Ui.umaLinha(fl, 8)
            col.addView(fl, Ui.lp(top = 1, c = c))
        }
        val est = texto(txt, 11f, if (sel) Theme.alpha(Theme.sobreGrad(), 0xD0) else cor)
        est.maxLines = 1
        col.addView(est, Ui.lp(top = 1, c = c))
        Ui.clique(col) { Sniper.ativarSala(i) }
        return col
    }

    private fun assinaturaAcoes(): String {
        val s = salaAtual()
        return "${s?.id}|${s?.protegida}|${s?.encerrada}|${Sniper.salas.size}|${s?.goAuto}|${s?.temGo}|${s?.chat?.size}"
    }

    private fun acoes(b: LinearLayout) {
        val c = act
        val sa = salaAtual()
        if (sa == null || sa.encerrada) return
        val tirar = Ui.text(c, "tirar essa sala do painel", 11f, Ui.MUTED, true, mono = true)
        tirar.gravity = Gravity.CENTER
        tirar.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 2))
        Ui.clique(tirar) {
            Ui.dialogConfirma(c, "TIRAR DO PAINEL", "A sala só some daqui. No Discord nada muda (não clica em nada).", "TIRAR") { Sniper.largar() }
        }
        b.addView(tirar)
    }

    private fun enviarCampo() {
        val e = campoMsg ?: return
        var t = e.text.toString()
        if (t.isBlank()) return
        // @nome que veio do botão de marcar vira <@id> (marca de verdade no Discord); o maior primeiro
        for ((rot, id) in marcados.entries.sortedByDescending { it.key.length }) t = t.replace(rot, "<@$id>")
        marcados.clear()
        mandar(t)
        e.setText("")
    }

    // ---------------- marcar jogador / ADM ----------------
    /** "@nome" que está no campo -> id da pessoa */
    private val marcados = LinkedHashMap<String, String>()

    private fun botaoMarcar(rot: String, ico: Int, acao: () -> Unit): View {
        val c = act
        val b = Ui.hbox(c)
        b.gravity = Gravity.CENTER
        b.background = Ui.ripple(c, Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x0A), Ui.raioPeq(12), Theme.alpha(android.graphics.Color.WHITE, 0x24), 1),
            Ui.shape(c, android.graphics.Color.WHITE, Ui.raioPeq(12)))
        b.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10))
        b.addView(Ui.icone(c, ico, Ui.ACC2, 14))
        val t = Ui.text(c, rot, 11f, Ui.TEXT, true, mono = true)
        t.maxLines = 1
        b.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 6) })
        Ui.clique(b) { acao() }
        return b
    }

    private fun nomeDe(p: SPessoa): String = p.nome.ifBlank { if (p.papel == "adm") "ADM" else if (p.papel == "jogador") "jogador" else "pessoa" }

    private fun marcarPapel(papel: String) {
        if (salaAtual() == null) { Ui.toast(act, "nenhuma sala aberta"); return }
        val l = Sniper.pessoasDaSala(papel)
        when {
            l.size == 1 -> inserirMarca(l[0])
            l.size > 1 -> escolherPessoa(papel)
            else -> {
                Ui.toast(act, if (papel == "adm") "ainda não sei quem é o ADM: escolha na lista" else "ainda não sei quem é o jogador: escolha na lista")
                escolherPessoa(null, papelDefinir = papel)
            }
        }
    }

    /** lista de quem está na sala; papelDefinir = quem você escolher passa a ser jogador/ADM dessa sala */
    private fun escolherPessoa(papel: String?, papelDefinir: String? = null) {
        val c = act
        val l = Sniper.pessoasDaSala(papel)
        if (l.isEmpty()) {
            Ui.dialogAviso(c, "MARCAR", "Ainda não sei quem está nessa sala. Assim que alguém falar ou a org marcar os jogadores no card, eles aparecem aqui.", "OK") {}
            return
        }
        val nomes = l.map { p -> nomeDe(p) + when (p.papel) { "jogador" -> "  · jogador"; "adm" -> "  · ADM"; else -> "" } }
        val titulo = when (papelDefinir ?: papel) { "adm" -> "MARCAR ADM"; "jogador" -> "MARCAR JOGADOR"; else -> "MARCAR" }
        Ui.dialogEscolha(c, titulo, nomes) { i ->
            val p = l[i]
            if (papelDefinir != null && p.papel != papelDefinir) Sniper.definirPapel(p.id, papelDefinir)
            inserirMarca(p)
        }
    }

    private fun inserirMarca(p: SPessoa) {
        val e = campoMsg ?: return
        var rot = "@" + nomeDe(p).replace(Regex("\\s+"), "_")
        // dois com o mesmo nome: @nome2
        var n = 2
        while (marcados[rot] != null && marcados[rot] != p.id) rot = "@" + nomeDe(p).replace(Regex("\\s+"), "_") + n++
        marcados[rot] = p.id
        val ini = e.selectionStart.coerceAtLeast(0)
        val atual = e.text.toString()
        val antes = atual.substring(0, ini)
        val espaco = if (antes.isNotEmpty() && !antes.endsWith(" ")) " " else ""
        e.text.insert(ini, "$espaco$rot ")
        e.requestFocus()
        try {
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Throwable) {}
    }

    // ---------------- imagem ----------------
    private fun escolherImagem() {
        if (Sniper.salas.isEmpty()) { Ui.toast(act, "nenhuma sala aberta"); return }
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_IMAGEM && res == Activity.RESULT_OK && u != null) {
                val ctx = act.applicationContext
                Ui.toast(act, "enviando imagem...")
                Bg.io {
                    val p = try { prepararImagem(ctx, u) } catch (_: Throwable) { null }
                    if (p == null) Bg.ui { Ui.toast(act, "não consegui abrir essa imagem") }
                    else Sniper.enviarImagem(p.first, p.second, p.third)
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ_IMAGEM)
        } catch (_: Exception) {
            Ui.toast(act, "não consegui abrir a galeria")
        }
    }

    private fun mandar(t: String) {
        if (Sniper.salas.isEmpty()) { Ui.toast(act, "nenhuma sala aberta"); return }
        Sniper.enviar(t)
    }

    /** só acrescenta as mensagens novas no fim (não refaz o chat inteiro) */
    private fun chatLinhas(s: SSala?): List<SChat> = if (s == null) emptyList() else synchronized(s.chat) { s.chat.toList() }

    private fun limpaChat(lista: LinearLayout) {
        lista.removeAllViews()
        chatIds.clear()
        chatTopo = null
    }

    /**
     * O chat guarda a conversa inteira da sala. Ao abrir, mostra as últimas [CHAT_JANELA] (desenhar centenas de
     * bolhas de uma vez travaria a tela) e, quando você rola até o topo, vai pondo as anteriores, até a primeira.
     */
    private fun atualizaChat() {
        val lista = chatLista ?: return
        val sv = chatRolagem ?: return
        val s = salaAtual()
        val id = s?.id ?: ""
        val linhas = chatLinhas(s)
        if (id != chatSala) {
            marcados.clear()                // trocou de sala: @nome da outra sala não vale aqui
            limpaChat(lista)
            chatSala = id
        }
        if (linhas.isEmpty()) {
            if (chatIds.isNotEmpty() || lista.childCount == 0) {
                limpaChat(lista)
                lista.addView(texto(if (s == null) "o chat aparece aqui" else "carregando as mensagens...", 12.5f, Ui.MUTED))
            }
            return
        }
        if (chatIds.isEmpty() && lista.childCount > 0) limpaChat(lista)   // tira o "carregando..."
        val novas = linhas.map { it.id }
        val ini = if (chatIds.isEmpty()) -1 else novas.indexOf(chatIds[0])
        // o que está na tela tem que continuar igual, na mesma ordem, dentro da conversa; se não (histórico recarregado), refaz
        if (chatIds.isNotEmpty() && (ini < 0 || ini + chatIds.size > novas.size || novas.subList(ini, ini + chatIds.size) != chatIds)) {
            limpaChat(lista)
        }
        if (chatIds.isEmpty()) {
            val de = maxOf(0, linhas.size - CHAT_JANELA)
            for (l in linhas.subList(de, linhas.size)) {
                lista.addView(bolha(l))
                chatIds.add(l.id)
            }
            atualizaTopo(de)
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            return
        }
        atualizaTopo(ini)
        val fim = ini + chatIds.size
        if (fim >= linhas.size) return
        val noFim = sv.scrollY + sv.height >= lista.height - Ui.dp(act, 80)
        for (l in linhas.subList(fim, linhas.size)) {
            lista.addView(bolha(l))
            chatIds.add(l.id)
        }
        if (noFim) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    /** o aviso no alto do chat: quantas mensagens ainda tem antes (toque ou role até ele pra ver) */
    private fun atualizaTopo(qtd: Int) {
        val lista = chatLista ?: return
        val t = chatTopo
        if (qtd <= 0) {
            if (t != null) { lista.removeView(t); chatTopo = null }
            return
        }
        val txt = "↑ $qtd mensagens anteriores · role pra cima"
        if (t == null) {
            val n = texto(txt, 11f, Ui.MUTED)
            n.gravity = Gravity.CENTER
            n.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 10))
            Ui.clique(n) { carregarAnteriores() }
            lista.addView(n, 0)
            chatTopo = n
        } else if (t.text.toString() != txt) t.text = txt
    }

    /** põe mais um lote de mensagens antigas em cima, sem a tela pular de lugar */
    private fun carregarAnteriores() {
        if (carregandoAnteriores) return
        val lista = chatLista ?: return
        val sv = chatRolagem ?: return
        if (chatIds.isEmpty()) return
        val linhas = chatLinhas(salaAtual())
        val ini = linhas.indexOfFirst { it.id == chatIds[0] }
        if (ini <= 0) { atualizaTopo(0); return }
        carregandoAnteriores = true
        val de = maxOf(0, ini - CHAT_LOTE)
        val alturaAntes = lista.height
        val yAntes = sv.scrollY
        val pos = if (chatTopo != null) 1 else 0
        val lote = linhas.subList(de, ini)
        for ((k, l) in lote.withIndex()) lista.addView(bolha(l), pos + k)
        chatIds.addAll(0, lote.map { it.id })
        atualizaTopo(de)
        // depois de medir, desce o tanto que entrou em cima: quem estava lendo continua no mesmo ponto
        lista.post {
            sv.scrollTo(0, yAntes + (lista.height - alturaAntes))
            carregandoAnteriores = false
        }
    }

    private fun bolha(l: SChat): View {
        val c = act
        val eu = l.tipo == "voce"
        val bot = l.tipo == "bot"
        val linha = Ui.hbox(c)
        linha.gravity = if (eu) Gravity.END else Gravity.START
        // avatar com a inicial (pessoa e bot), cor pela pessoa
        if (!eu) {
            val corAv = corDoNome(l.nome)
            val av = Ui.text(c, l.nome.trim().firstOrNull()?.uppercase() ?: "?", 12f, android.graphics.Color.WHITE, true, mono = true)
            av.gravity = Gravity.CENTER
            av.background = Ui.shape(c, Theme.alpha(corAv, if (bot) 0x55 else 0xCC), 15)
            linha.addView(av, LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)).also { it.rightMargin = Ui.dp(c, 8); it.topMargin = Ui.dp(c, 2) })
        }
        val b = Ui.vbox(c)
        b.background = when {
            eu -> Botao(c, "grad", Ui.raioPeq(16))
            bot -> Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x08), Ui.raioPeq(14), Theme.alpha(Theme.accent, 0x55), 1)
            else -> Botao(c, "ghost", Ui.raioPeq(16))
        }
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 10))
        val corTexto = if (eu) Theme.sobreGrad() else Ui.TEXT
        val corFraca = if (eu) Theme.alpha(Theme.sobreGrad(), 0xB0) else Theme.alpha(Ui.MUTED, 0xD0)
        val larguraMax = (largura * if (eu) 0.74 else 0.66).toInt()

        // cabeçalho: nome · hora · copiar tudo
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER_VERTICAL
        val esq = Ui.hbox(c)
        esq.gravity = Gravity.CENTER_VERTICAL
        val nome = texto(if (eu) "você" else l.nome, 11f, if (eu) corFraca else if (bot) Theme.accent else corDoNome(l.nome), true)
        nome.maxLines = 1
        nome.ellipsize = android.text.TextUtils.TruncateAt.END
        esq.addView(nome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // selo do papel (ADM / JOGADOR) de quem já foi identificado na sala
        val papel = if (!eu && !bot) salaAtual()?.pessoas?.get(l.autorId)?.papel else null
        if (papel == "adm" || papel == "jogador") {
            val corTag = if (papel == "adm") Theme.accent else Ui.OK
            val tag = Ui.text(c, if (papel == "adm") "ADM" else "JOGADOR", 8f, corTag, true, mono = true)
            tag.background = Ui.shape(c, Theme.alpha(corTag, 0x22), Ui.raioPeq(7))
            tag.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2))
            esq.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 6) })
        }
        cab.addView(esq, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(texto(l.hora, 10f, corFraca), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 8) })
        val tudo = listOf(l.texto, l.card).filter { it.isNotBlank() && it != "(mensagem que o app não lê)" }.joinToString("\n")
        if (tudo.isNotEmpty()) {
            val cp = android.widget.FrameLayout(c)
            cp.addView(Ui.icone(c, R.drawable.ic_copy, corFraca, 14), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14), Gravity.CENTER))
            cp.contentDescription = "copiar mensagem"
            Ui.clique(cp) { copiar(tudo, "mensagem copiada") }
            cab.addView(cp, LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 24)).also { it.leftMargin = Ui.dp(c, 2) })
        }
        b.addView(cab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (l.imagem != null) {
            val iv = ImageView(c)
            iv.adjustViewBounds = true
            iv.maxWidth = (largura * 0.62).toInt()
            iv.maxHeight = Ui.dp(c, 300)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.minimumHeight = Ui.dp(c, 80)
            iv.minimumWidth = Ui.dp(c, 120)
            carregarImagem(l.imagem, iv)
            val grande = l.imagemGrande
            if (grande != null) Ui.clique(iv) {
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(grande))) } catch (_: Exception) {}
            }
            b.addView(iv, solto(6))
        }
        if (l.texto.isNotEmpty()) {
            val t = textoCopiavel(l.texto, if (bot) 13f else 14.5f, corTexto, eu)
            t.maxWidth = larguraMax
            b.addView(t, solto(3))
        }
        // card (org/ADM): com barrinha do lado, inteiro e copiável
        if (l.card.isNotEmpty()) {
            val caixaCard = Ui.hbox(c)
            val barra = View(c)
            barra.background = Ui.shape(c, Theme.accent, 2)
            caixaCard.addView(barra, LinearLayout.LayoutParams(Ui.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT).also { it.rightMargin = Ui.dp(c, 10) })
            val t = textoCopiavel(l.card, 12.5f, corTexto, eu)
            t.maxWidth = larguraMax - Ui.dp(c, 13)
            caixaCard.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            b.addView(caixaCard, solto(if (l.texto.isNotEmpty()) 8 else 4))
        }
        for (a in l.arquivos) b.addView(texto("📎 $a", 12f, corFraca), solto(4))
        // botões da org (só mostra quais tem; quem clica é confirmar/cancelar lá embaixo)
        if (l.botoes.isNotEmpty()) {
            val fl = Ui.hbox(c)
            for ((i, bt) in l.botoes.take(4).withIndex()) {
                val p = Ui.text(c, bt, 9.5f, corFraca, true, mono = true)
                p.maxLines = 1
                p.background = Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x0C), 8, Theme.alpha(android.graphics.Color.WHITE, 0x22), 1)
                p.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4))
                fl.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { if (i > 0) it.leftMargin = Ui.dp(c, 6) })
            }
            val hs = HorizontalScrollView(c)
            hs.isHorizontalScrollBarEnabled = false
            hs.addView(fl)
            b.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 8) })
        }
        // o que dá pra copiar com um toque: link, chave pix, ID/senha da sala...
        val achados = copiaveis(tudo)
        if (achados.isNotEmpty()) {
            val col = Ui.vbox(c)
            for ((rot, valor) in achados) {
                val p = Ui.hbox(c)
                p.gravity = Gravity.CENTER_VERTICAL
                p.background = if (eu) Ui.shape(c, Theme.alpha(android.graphics.Color.BLACK, 0x22), Ui.raioPeq(10))
                    else Ui.shape(c, Theme.alpha(Theme.accent, 0x1E), Ui.raioPeq(10), Theme.alpha(Theme.accent, 0x66), 1)
                p.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
                p.addView(Ui.icone(c, R.drawable.ic_copy, if (eu) corTexto else Theme.accent, 13))
                val tx = texto("$rot  ${encurtar(valor)}", 11.5f, corTexto, true)
                tx.maxLines = 1
                tx.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                p.addView(tx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 7) })
                p.contentDescription = "copiar $rot"
                Ui.clique(p) { copiar(valor, "$rot copiado") }
                if (rot == "chave pix") {
                    // chave pix: ao lado, o botão do QR code / copia e cola (com o valor da sala)
                    val lin = Ui.hbox(c)
                    lin.gravity = Gravity.CENTER_VERTICAL
                    lin.addView(p)
                    val q = Ui.hbox(c)
                    q.gravity = Gravity.CENTER_VERTICAL
                    q.background = p.background.constantState?.newDrawable()?.mutate() ?: p.background
                    q.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
                    q.addView(texto("QR", 11.5f, corTexto, true))
                    q.contentDescription = "QR code do pix"
                    Ui.clique(q) { Pix.mostrar(act, valor, salaAtual()?.fila?.let { Caderno.valorDe(it) } ?: 0.0) }
                    lin.addView(q, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 6) })
                    col.addView(lin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 6) })
                    continue
                }
                col.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 6) })
            }
            b.addView(col, solto(2))
        }
        linha.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linha.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 8) }
        return linha
    }

    /** largura do próprio conteúdo (dentro da bolha, MATCH_PARENT prende tudo na largura do cabeçalho) */
    private fun solto(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .also { it.topMargin = Ui.dp(act, top) }

    /** texto que dá pra selecionar (toque longo) e com link que abre no toque */
    private fun textoCopiavel(s: String, sp: Float, cor: Int, eu: Boolean): TextView {
        val t = texto(s, sp, cor)
        t.setTextIsSelectable(true)
        t.setLinkTextColor(if (eu) Theme.sobreGrad() else Ui.ACC2)
        try {
            android.text.util.Linkify.addLinks(t, android.text.util.Linkify.WEB_URLS)
            t.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            t.setTextIsSelectable(true)
        } catch (_: Throwable) {}
        return t
    }

    private fun copiar(valor: String, aviso: String) {
        try {
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("nocry", valor))
            // Android 13+ já mostra o próprio aviso de copiado
            if (android.os.Build.VERSION.SDK_INT < 33) Ui.toast(act, aviso)
        } catch (_: Throwable) {
            Ui.toast(act, "não consegui copiar")
        }
    }

    private fun encurtar(v: String): String = if (v.length <= 26) v else v.take(14) + "…" + v.takeLast(9)

    /** cor fixa por pessoa (o mesmo nome sempre com a mesma cor) */
    private fun corDoNome(n: String): Int {
        val cores = intArrayOf(0xFF7C9CFF.toInt(), 0xFF4FD1C5.toInt(), 0xFFF6AD55.toInt(), 0xFFB794F4.toInt(), 0xFFF687B3.toInt(), 0xFF68D391.toInt())
        return cores[(n.lowercase().hashCode() and 0x7fffffff) % cores.size]
    }

    /** acha o que vale um botão de copiar: link, chave pix (e-mail, CPF/CNPJ, telefone, aleatória), ID/senha/código */
    private fun copiaveis(t: String): List<Pair<String, String>> {
        if (t.isBlank()) return emptyList()
        val out = LinkedHashMap<String, String>()   // valor -> rótulo
        fun add(rot: String, v0: String) {
            val v = v0.trim().trimEnd('.', ',', ';', ')', '!', '?', ']', '*')
            if (v.length < 3 || out.keys.any { it.contains(v) || v.contains(it) && it.length > 4 }) return
            out[v] = rot
        }
        val pix = Regex("(?i)\\bpix\\b|chave").containsMatchIn(t)
        RX_URL.findAll(t).forEach { add("link", it.value) }
        RX_UUID.findAll(t).forEach { add("chave pix", it.value) }
        RX_EMAIL.findAll(t).forEach { m -> if (out.keys.none { k -> k.startsWith("http") && m.value in k }) add(if (pix) "chave pix" else "e-mail", m.value) }
        RX_CNPJ.findAll(t).forEach { add(if (pix) "chave pix" else "CNPJ", it.value) }
        RX_FONE.findAll(t).forEach { add(if (pix) "chave pix" else "telefone", it.value) }
        RX_CPF.findAll(t).forEach { add(if (pix) "chave pix" else "CPF", it.value) }
        RX_ROTULO.findAll(t).forEach { m ->
            val r = m.groupValues[1].lowercase()
            val rot = when {
                r.startsWith("senha") -> "senha"
                r.startsWith("c") && !r.startsWith("chave") -> "código"
                r.startsWith("chave") || r == "pix" -> "chave pix"
                r == "sala" -> "sala"
                else -> "ID"
            }
            add(rot, m.groupValues[2])
        }
        return out.entries.take(4).map { it.value to it.key }
    }

    private fun carregarImagem(url: String, iv: ImageView) {
        cacheImg.get(url)?.let { iv.setImageBitmap(it); return }
        iv.tag = url
        Bg.io {
            val bmp = try {
                val r = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                r.connectTimeout = 15000
                r.readTimeout = 20000
                r.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null }
            if (bmp != null) {
                cacheImg.put(url, bmp)
                Bg.ui { if (iv.tag == url) iv.setImageBitmap(bmp) }
            }
        }
    }

    companion object {
        private const val REQ_IMAGEM = 4301
        private const val REQ_BACKUP = 4302

        private val RX_URL = Regex("""https?://[^\s<>"]+""")
        private val RX_EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
        private val RX_UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val RX_CNPJ = Regex("""\b\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}\b""")
        private val RX_CPF = Regex("""\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11}\b""")
        private val RX_FONE = Regex("""(?:\+55\s?)?\(?\b\d{2}\)?\s?9\d{4}[-\s]?\d{4}\b""")
        private val RX_ROTULO = Regex("""(?i)\b(id|senha|sala|c[oó]digo|chave(?:\s*pix)?|pix)\s*(?:da sala|do jogo)?\s*[:=\-]\s*([A-Za-z0-9@._+\-]{3,})""")

        /** imagens do chat já baixadas (até ~16 MB) */
        private val cacheImg = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        /** lê a imagem escolhida; se for muito grande, diminui e manda em JPEG (o Discord aceita até ~10 MB) */
        fun prepararImagem(ctx: android.content.Context, uri: Uri): Triple<ByteArray, String, String>? {
            val cr = ctx.contentResolver
            val mime = cr.getType(uri) ?: "image/jpeg"
            val bruto = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bruto, 0, bruto.size, o)
            val maior = maxOf(o.outWidth, o.outHeight)
            val heic = mime.contains("heic") || mime.contains("heif")
            if (!heic && bruto.size <= 7_500_000 && maior in 1..2560) {
                val ext = when (mime) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
                return Triple(bruto, "imagem.$ext", if (ext == "jpg") "image/jpeg" else mime)
            }
            var amostra = 1
            while (maior / amostra > 2560) amostra *= 2
            val bmp = BitmapFactory.decodeByteArray(bruto, 0, bruto.size, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Triple(out.toByteArray(), "imagem.jpg", "image/jpeg")
        }
    }

    // ======================================================================== BUSCA
    private fun abaBusca() {
        add(pedaco({ assinaturaContaOrgs() }) { contaOrgs(it) }, 12)
        add(pedaco({ assinaturaAlvo() }) { alvo(it) }, 12)
        add(pedaco({ assinaturaOrgs() }) { orgs(it) }, 12)
    }

    private fun assinaturaContaOrgs(): String =
        "${Sniper.servidoresCarregados()}|${Sniper.procurando}|${Sniper.conferindoAoLigar}|${Sniper.progresso}|${Sniper.procuraFeitas}|" +
            Sniper.orgs.keys.joinToString("") { (if (Sniper.naConta(it)) "1" else "0") + (if (Sniper.convites.containsKey(it)) "L" else "") } + "|$orgsAbertas"

    /**
     * Suas orgs: em quais a conta está (✓) e em quais não (✗, com o link pra entrar pelo app do Discord).
     * Enquanto o bot confere os IDs (ao ligar), mostra a barra de progresso aqui.
     */
    private fun contaOrgs(b: LinearLayout) {
        val c = act
        val todas = Sniper.orgs.keys.toList()
        val card = Ui.card(c)
        val topo = Ui.hbox(c)
        topo.gravity = Gravity.CENTER_VERTICAL
        topo.addView(Ui.label(c, "suas orgs"), pesoUm())
        if (!Sniper.servidoresCarregados()) {
            card.addView(topo)
            card.addView(nota("Ligue o bot pra ver em quais orgs a sua conta está e pegar o link das que faltam."), Ui.lp(top = 8, c = c))
            b.addView(card)
            return
        }
        val dentro = todas.count { Sniper.naConta(it) }
        topo.addView(Ui.selo(c, "$dentro de ${todas.size} na conta", if (dentro == todas.size) Ui.OK else Ui.WARN, true))
        card.addView(topo)
        if (Sniper.procurando) {
            val pct = if (Sniper.procuraTotal > 0) (Sniper.procuraFeitas.toFloat() / Sniper.procuraTotal).coerceIn(0f, 1f) else 0f
            card.addView(barraProgresso(pct), Ui.lp(top = 12, h = Ui.dp(c, 6), c = c))
            card.addView(texto(Sniper.progresso.ifEmpty { "conferindo os IDs..." }, 11.5f, Ui.WARN), Ui.lp(top = 6, c = c))
            if (Sniper.conferindoAoLigar) card.addView(nota("Conferindo os IDs antes de buscar. Só lê, não entra em fila."), Ui.lp(top = 2, c = c))
        }
        // as que faltam primeiro; as que já estão na conta numa linha só (toque pra ver)
        val fora = todas.filter { !Sniper.naConta(it) }
        val mostrar = if (orgsAbertas || dentro == 0) fora + todas.filter { Sniper.naConta(it) } else fora
        if (fora.isEmpty() && !orgsAbertas) card.addView(nota("A conta está em todas as orgs. Nada pra entrar."), Ui.lp(top = 8, c = c))
        for ((k, o) in mostrar.withIndex()) {
            val ok = Sniper.naConta(o)
            val link = Sniper.convites[o]
            val (txt, cor) = when {
                ok -> "✓ na conta" to Ui.OK
                link != null -> "✗ fora · entrar ›" to Ui.BAD
                else -> "✗ fora · pôr link" to Ui.MUTED
            }
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.CENTER_VERTICAL
            linha.minimumHeight = Ui.dp(c, 42)
            linha.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6))
            val ponto = View(c)
            ponto.background = Ui.shape(c, cor, 4)
            linha.addView(ponto, LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)).also { it.rightMargin = Ui.dp(c, 10) })
            linha.addView(Ui.text(c, o, 12.5f, Ui.TEXT, true, mono = true).also { Ui.umaLinha(it, 9) }, pesoUm())
            linha.addView(texto(txt, 12f, cor))
            Ui.clique(linha) {
                when {
                    !ok && link != null -> abrirLink(link)
                    !ok -> pedirLink(o)
                    link != null -> copiar(link, "link da $o copiado")
                    else -> Ui.toast(c, "a conta já está na $o")
                }
            }
            if (k > 0) card.addView(divisoria())
            card.addView(linha, Ui.lp(top = if (k == 0) 8 else 0, c = c))
        }
        if (dentro > 0) {
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.CENTER_VERTICAL
            linha.minimumHeight = Ui.dp(c, 40)
            linha.addView(texto(if (orgsAbertas) "esconder as que já estão na conta" else "✓ $dentro orgs na conta", 12f, if (orgsAbertas) Ui.MUTED else Ui.OK), pesoUm())
            linha.addView(texto(if (orgsAbertas) "▴" else "ver ▾", 12f, Ui.MUTED))
            Ui.clique(linha) { orgsAbertas = !orgsAbertas; for (p in pedacos) p.atualiza() }
            if (mostrar.isNotEmpty()) card.addView(divisoria())
            card.addView(linha, Ui.lp(top = if (mostrar.isEmpty()) 4 else 0, c = c))
        }
        card.addView(Ui.chips(c,
            Ui.button(c, "ver todos os links", Ui.Kind.GHOST) { dialogLinks() }
        ), Ui.lp(top = 10, c = c))
        b.addView(card)
    }

    private fun divisoria(): View = View(act).also {
        it.setBackgroundColor(Theme.alpha(android.graphics.Color.WHITE, 0x10))
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, Ui.dp(act, 1)))
    }

    private fun barraProgresso(pct: Float): View {
        val c = act
        val trilho = LinearLayout(c)
        trilho.background = Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x18), 3)
        val cheio = View(c)
        cheio.background = Botao(c, "grad", 3)
        trilho.addView(cheio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, maxOf(pct, 0.03f)))
        trilho.addView(View(c), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1f - pct, 0f)))
        return trilho
    }

    private fun abrirLink(link: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        catch (_: Throwable) { copiar(link, "link copiado (abra no Discord)") }
    }

    private fun pedirLink(o: String) {
        Ui.dialogTexto(act, "LINK DA $o", "cole o convite (discord.gg/...)") { t ->
            Sniper.definirConvite(o, t)
            if (Sniper.convites[o] == null) Ui.toast(act, "não parece um link de convite")
        }
    }

    /** todos os links das orgs: toque pra abrir no Discord, ou copie tudo de uma vez */
    private fun dialogLinks() {
        val c = act
        val lista = Sniper.orgs.keys.mapNotNull { o -> Sniper.convites[o]?.let { o to it } }
        if (lista.isEmpty()) {
            Ui.toast(c, "ainda não tem link guardado: ligue o bot que ele pega os das orgs em que você está")
            return
        }
        val col = Ui.vbox(c)
        val p = Ui.dp(c, 18)
        col.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8))
        var dlg: android.app.AlertDialog? = null
        for ((o, link) in lista) {
            val l = Ui.vbox(c)
            l.setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9))
            l.addView(Ui.text(c, o, 12.5f, if (Sniper.naConta(o)) Ui.OK else Ui.TEXT, true, mono = true))
            l.addView(Ui.text(c, link.removePrefix("https://"), 11.5f, Ui.MUTED, code = true))
            Ui.clique(l) { dlg?.dismiss(); abrirLink(link) }
            col.addView(l)
        }
        val sv = android.widget.ScrollView(c)
        sv.addView(col)
        dlg = android.app.AlertDialog.Builder(c, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (Prefs.maiusc) "LINKS DAS ORGS" else "Links das orgs")
            .setView(sv)
            .setPositiveButton("COPIAR TODOS") { _, _ -> copiar(lista.joinToString("\n") { "${it.first}: ${it.second}" }, "${lista.size} links copiados") }
            .setNegativeButton("FECHAR", null)
            .show()
    }

    private fun assinaturaAlvo() = listOf(Sniper.org, Sniper.gelo, Sniper.modos.sorted(), Sniper.valores.sorted(),
        Sniper.todosValores.size, Sniper.buscando).joinToString("|")

    private fun alvo(b: LinearLayout) {
        val c = act
        // org
        b.addView(Ui.grupo(c, "onde procurar", listOf(
            Ui.itemEstado(c, "Org", if (Sniper.org == "TODAS") "todas ao mesmo tempo" else "só essa org", R.drawable.ic_globe,
                Sniper.org, Theme.accent) {
                val l = Sniper.listaOrgs
                Ui.dialogEscolha(c, "ORG", l) { i -> Sniper.trocarOrg(l[i]) }
            }
        )))

        // modo (+ gelo só quando tem 1X1)
        val cm = Ui.card(c)
        val tm = Ui.hbox(c)
        tm.gravity = Gravity.CENTER_VERTICAL
        tm.addView(Ui.label(c, "modo"), pesoUm())
        tm.addView(Ui.selo(c, "${Sniper.modos.size} marcado${if (Sniper.modos.size == 1) "" else "s"}", Theme.accent, Sniper.modos.isNotEmpty()))
        cm.addView(tm)
        cm.addView(grade(Sniper.MODOS.map { m -> caixa(m, null, m in Sniper.modos) { Sniper.alternarModo(m) } }, 4), Ui.lp(top = 10, c = c))
        if ("1X1" in Sniper.modos) {
            cm.addView(Ui.label(c, "gelo do 1x1"), Ui.lp(top = 16, c = c))
            cm.addView(Ui.segmento(c, listOf("Normal", "Infinito"), if (Sniper.gelo == "INFINITO") 1 else 0) { i ->
                Sniper.escolherGelo(if (i == 1) "INFINITO" else "NORMAL")
            }, Ui.lp(top = 8, c = c))
        }
        b.addView(cm, Ui.lp(top = 12, c = c))

        // valor
        val cv = Ui.card(c)
        val tv = Ui.hbox(c)
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.addView(Ui.label(c, "valor"), pesoUm())
        tv.addView(Ui.selo(c, "${Sniper.valores.size} marcado${if (Sniper.valores.size == 1) "" else "s"}", Theme.accent, Sniper.valores.isNotEmpty()))
        cv.addView(tv)
        val disp = Sniper.valoresDisponiveis()
        cv.addView(grade(Sniper.todosValores.map { v ->
            caixa(Sniper.rotuloValor(v), null, v in Sniper.valores, v in disp) { Sniper.alternarValor(v) }
        }, 4), Ui.lp(top = 10, c = c))
        if (Sniper.todosValores.any { it !in disp }) cv.addView(nota("Valor apagado: não tem nessa org/modo."), Ui.lp(top = 10, c = c))
        if (Sniper.buscando) cv.addView(nota("Mudou com a busca ligada? Vale na próxima volta."), Ui.lp(top = 4, c = c))
        b.addView(cv, Ui.lp(top = 12, c = c))
    }

    private fun assinaturaOrgs(): String =
        Sniper.buscando.toString() + Sniper.org + Sniper.statusOrg.entries.sortedBy { it.key }.joinToString { it.key + it.value.first + it.value.second } +
            Sniper.modos.sorted() + Sniper.valores.sorted()

    private fun orgs(b: LinearLayout) {
        val c = act
        val filas = Sniper.filasAlvo()
        val linhas = ArrayList<View>()
        for (o in Sniper.orgsAlvo()) {
            val tem = filas.any { it[0] == o }
            val st = Sniper.statusOrg[o]
            val (txt, cor) = when {
                !tem -> "não tem esse modo/valor" to Theme.alpha(Ui.MUTED, 0x90)
                st == null -> (if (Sniper.buscando) "esperando a vez" else "parado") to Ui.MUTED
                st.second == "ok" -> st.first to Ui.OK
                st.second == "erro" -> st.first to Ui.BAD
                st.second == "pausa" -> st.first to Ui.WARN
                else -> st.first to Ui.TEXT
            }
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.CENTER_VERTICAL
            linha.minimumHeight = Ui.dp(c, 44)
            linha.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
            val ponto = View(c)
            ponto.background = Ui.shape(c, cor, 4)
            linha.addView(ponto, LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)).also { it.rightMargin = Ui.dp(c, 10) })
            linha.addView(Ui.text(c, o, 12.5f, Ui.TEXT, true, mono = true), LinearLayout.LayoutParams(Ui.dp(c, 104), ViewGroup.LayoutParams.WRAP_CONTENT))
            val t = texto(txt, 12f, cor)
            t.gravity = Gravity.END
            linha.addView(t, pesoUm(8))
            linhas.add(linha)
        }
        b.addView(Ui.grupo(c, "cada org agora", linhas))
        if (Sniper.semValor.isNotEmpty()) {
            b.addView(Ui.dica(c, "Sem valor ainda (não entram): ${Sniper.semValor.joinToString(", ")}. Use Descobrir valores na aba Conta.", Ui.Dica.AVISO), Ui.lp(top = 10, c = c))
        }
    }

    // ======================================================================== CONTA
    private fun abaConta() {
        add(pedaco({ listOf(Sniper.estado, Sniper.nomeConta, Sniper.ligado(), Cofre.tem(act)).joinToString("|") }) { conta(it) }, 12)
        add(pedaco({ assinaturaAjustes() }) { ajustes(it) }, 12)
        add(pedaco({ assinaturaFerramentas() }) { ferramentas(it) }, 12)
        add(pedaco({ "${Sniper.temBackup()}|${Sniper.podeArquivos()}|${Sniper.qtdRespostasSalvas()}|${Sniper.orgs.size}" }) { backup(it) }, 12)
        add(pedaco({ val l = Sniper.logs(1).lastOrNull(); "${l?.hora}${l?.texto}|${Sniper.logs(200).size}" }) { registro(it) }, 12)
    }

    private fun conta(b: LinearLayout) {
        val c = act
        val card = Ui.card(c, ligado = Sniper.conectado)
        card.addView(Ui.labelIcone(c, R.drawable.ic_user, "conta do discord"))
        val temToken = Cofre.tem(c)
        if (!temToken || Sniper.estado == "token") {
            card.addView(Ui.status(c, "TOKEN", if (Sniper.estado == "token") "RECUSADO" else "FALTA", false), Ui.lp(top = 10, c = c))
            card.addView(nota("Use a conta temporária. O token fica cifrado no celular e no backup (só abre com a sua key). Trocou a senha no Discord? Cole o token novo."), Ui.lp(top = 8, c = c))
            val e = campoToken ?: Ui.field(c, "token da conta", false).also {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                campoToken = it
            }
            solta(e)
            card.addView(e, Ui.lp(top = 12, c = c))
            card.addView(Ui.button(c, "salvar token e ligar", Ui.Kind.PRIMARY, R.drawable.ic_key) {
                val t = e.text.toString().trim()
                if (t.length < 20) { Ui.toast(c, "token parece incompleto"); return@button }
                if (Sniper.ligado()) Sniper.desligar()
                Sniper.salvarToken(t)
                e.setText("")
                esconderTeclado()
                Sniper.estado = "desligado"
                Sniper.ligar()
            }, Ui.lp(top = 10, c = c))
            card.addView(Ui.button(c, "entrar com email e senha", Ui.Kind.GHOST, R.drawable.ic_user) {
                esconderTeclado()
                act.abre(LoginDiscordScreen(act))
            }, Ui.lp(top = 8, c = c))
        } else {
            val (txt, ok) = when (Sniper.estado) {
                "online" -> "CONECTADO" to true
                "conectando" -> "CONECTANDO" to null
                "caiu" -> "RECONECTANDO" to null
                else -> "DESLIGADO" to false
            }
            card.addView(Ui.status(c, "DISCORD", txt, ok), Ui.lp(top = 10, c = c))
            if (Sniper.estado == "online") card.addView(Ui.status(c, "CONTA", Sniper.nomeConta.uppercase(), true))

            card.addView(if (!Sniper.ligado())
                Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }
            else
                Ui.button(c, "desligar o bot", Ui.Kind.GHOST, R.drawable.ic_stop) {
                    Ui.dialogConfirma(c, "DESLIGAR", "O bot sai do Discord e para de buscar. As filas em que ele já entrou continuam lá.", "DESLIGAR") {
                        Bg.io { Sniper.desligar() }
                    }
                }, Ui.lp(top = 12, c = c))
            card.addView(Ui.item(c, "Trocar token", "Apaga o token salvo e desliga o bot.", R.drawable.ic_key, null) {
                Ui.dialogConfirma(c, "TROCAR TOKEN", "O token salvo é apagado e o bot desliga. Depois é só colar o novo.", "APAGAR") {
                    Bg.io { Sniper.apagarToken() }
                }
            }, Ui.lp(top = 8, c = c))
        }
        b.addView(card)
    }

    private fun assinaturaAjustes() = listOf(Sniper.maxFilas, Sniper.tempoResposta, Sniper.msgAuto, Sniper.sairAoAbrir, Sniper.buscarAoLigar).joinToString("|")

    private fun ajustes(b: LinearLayout) {
        val c = act
        b.addView(Ui.grupo(c, "ajustes", listOf(
            Ui.itemEstado(c, "Mensagem automática", Sniper.msgAuto.replace(Regex("^#+\\s*"), ""), R.drawable.ic_sparkle, "editar", Theme.accent) {
                Ui.dialogTexto(c, "MENSAGEM AUTOMÁTICA", Sniper.msgAuto) { t -> if (t.isNotBlank()) Sniper.definirMsgAuto(t) }
            },
            Ui.itemEstado(c, "Tempo pra responder", "Sem resposta de pessoa nesse tempo: cancela a sala e volta a procurar.", R.drawable.ic_bolt,
                "${Sniper.tempoResposta / 1000}s", Theme.accent) {
                Ui.dialogNumero(c, "TEMPO PRA RESPONDER (s)", "de 10 a 300 segundos", 10, 300, (Sniper.tempoResposta / 1000).toInt()) { n -> Sniper.definirTempoResposta(n) }
            },
            Ui.itemEstado(c, "Limite de filas do bot", "300 = sem limite (quem segura é o limite de cada org).", R.drawable.ic_chip,
                if (Sniper.maxFilas >= 300) "sem limite" else "${Sniper.maxFilas}", Theme.accent) {
                Ui.dialogNumero(c, "LIMITE DE FILAS DO BOT", "de 1 a 300 (300 = sem limite)", 1, 300, Sniper.maxFilas) { n -> Sniper.definirMaxFilas(n) }
            },
            Ui.itemChave(c, "Começar a buscar ao ligar", "assim que conectar, já busca com o alvo escolhido", Sniper.buscarAoLigar, R.drawable.ic_power) { v ->
                Sniper.definirBuscarAoLigar(v)
            },
            Ui.itemChave(c, "Sair das outras filas ao abrir sala", "desligado: continua até o limite de cada org", Sniper.sairAoAbrir, R.drawable.ic_logout) { v ->
                Sniper.definirSairAoAbrir(v)
            }
        )))
    }

    private fun assinaturaFerramentas() = "${Sniper.descobrindo}|${Sniper.procurando}|${Sniper.progresso}|${Sniper.qtdPendentes()}|${Sniper.conectado}|${Sniper.buscando}|${Sniper.qtdRespostasSalvas()}"

    private fun ferramentas(b: LinearLayout) {
        val c = act
        if (Sniper.descobrindo || Sniper.procurando) {
            val card = Ui.card(c, forte = true)
            card.addView(Ui.status(c, "LENDO AS FILAS", "AGUARDE", null))
            card.addView(Ui.text(c, Sniper.progresso.ifEmpty { "lendo as filas..." }, 12.5f, Ui.WARN, true, mono = true), Ui.lp(top = 6, c = c))
            card.addView(nota("Só lê as filas, não entra em nenhuma."), Ui.lp(top = 6, c = c))
            b.addView(card, Ui.lp(top = 0, c = c))
        }
        val itens = ArrayList<View>()
        val np = Sniper.qtdPendentes()
        if (np > 0) {
            itens.add(Ui.itemEstado(c, "Descobrir valores", "Lê o valor das $np filas que vieram sem valor (${Sniper.pendentes.keys.joinToString(", ")}).",
                R.drawable.ic_eye, "$np", Ui.WARN) {
                when {
                    Sniper.descobrindo -> Ui.toast(c, "já está lendo as filas")
                    !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                    else -> Sniper.descobrir()
                }
            })
        }
        itens.add(Ui.item(c, "Procurar filas novas", "A org postou o painel de novo e deu \"ID velho\"? Lê os canais de todas as orgs e pega os IDs novos sozinho. Também roda sozinho quando aparece ID velho.", R.drawable.ic_sparkle, null) {
            when {
                Sniper.descobrindo || Sniper.procurando -> Ui.toast(c, "já está lendo as filas")
                !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                else -> Sniper.procurarFilasNovas()
            }
        })
        itens.add(Ui.item(c, "Conferir todos os valores", "Abre cada fila de todas as orgs e corrige ID com valor trocado. Uns 5 min. Pause a busca antes.", R.drawable.ic_check, null) {
            when {
                Sniper.descobrindo -> Ui.toast(c, "já está lendo as filas")
                !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                Sniper.buscando -> Ui.toast(c, "pause a busca primeiro")
                else -> Sniper.descobrir(conferirTudo = true)
            }
        })
        val nr = Sniper.qtdRespostasSalvas()
        if (nr > 0) {
            itens.add(Ui.itemEstado(c, "Respostas dos formulários", "O bot responde sozinho com o que você já respondeu. Toque pra apagar.", R.drawable.ic_trash, "$nr", Theme.accent) {
                Ui.dialogConfirma(c, "APAGAR RESPOSTAS", "Apaga as $nr respostas salvas. Da próxima vez que uma org pedir formulário, ele pergunta de novo.", "APAGAR") { Sniper.apagarRespostas() }
            })
        }
        b.addView(Ui.grupo(c, "ferramentas", itens), Ui.lp(top = if (Sniper.descobrindo || Sniper.procurando) 12 else 0, c = c))
    }

    private fun backup(b: LinearLayout) {
        val c = act
        val pode = Sniper.podeArquivos()
        val itens = ArrayList<View>()
        itens.add(Ui.itemEstado(c, "Backup automático", "Download/Nocry · volta sozinho se reinstalar", R.drawable.ic_shield,
            when { !pode -> "falta acesso"; Sniper.temBackup() -> "ligado"; else -> "pronto" }, if (pode) Ui.OK else Ui.WARN,
            if (pode) null else ({
                try {
                    val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    i.data = Uri.parse("package:${act.packageName}")
                    act.startActivity(i)
                } catch (_: Exception) {
                    try { act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
                }
            })))
        itens.add(Ui.item(c, "Salvar agora", "grava o backup com tudo que ele sabe agora", R.drawable.ic_download, null) {
            Bg.io {
                val ok = Sniper.salvarBackup()
                Bg.ui { Ui.toast(c, if (ok) "backup salvo em Download/Nocry" else "não consegui salvar o backup") }
            }
        })
        itens.add(Ui.item(c, "Restaurar de um arquivo", "escolha o sniper_backup.json", R.drawable.ic_up, null) { restaurarBackup() })
        b.addView(Ui.grupo(c, "backup", itens))
        if (!pode) b.addView(Ui.dica(c, "Libere o acesso a todos os arquivos (o mesmo do bypass) pra ele salvar e voltar sozinho depois de reinstalar. Toque em Backup automático.",
            Ui.Dica.AVISO), Ui.lp(top = 10, c = c))
    }

    private fun restaurarBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_BACKUP && res == Activity.RESULT_OK && u != null) {
                val ctx = act.applicationContext
                Bg.io {
                    val txt = try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Throwable) { null }
                    val erro = if (txt == null) "não consegui abrir o arquivo"
                        else try { Sniper.importarDados(org.json.JSONObject(txt)) } catch (_: Throwable) { "esse arquivo não é um backup válido" }
                    Bg.ui { Ui.toast(act, erro ?: "backup restaurado"); render() }
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ_BACKUP)
        } catch (_: Exception) {
            Ui.toast(act, "não consegui abrir os arquivos")
        }
    }

    private fun compartilharRegistro() {
        val c = act
        val f = Sniper.arquivoRegistro()
        Ui.toast(c, "preparando o registro...")
        // junta o anterior (se o arquivo ficou grande e foi trocado) com o atual, copiando em segundo plano:
        // o registro pode ter vários MB e ler tudo na tela travava o app
        Bg.run({
            try {
                if (!f.exists() || f.length() == 0L) null else {
                    val velho = java.io.File(f.parentFile, "registro_anterior.txt")
                    val saida = java.io.File(f.parentFile, "registro_bot_de_fila.txt")
                    saida.outputStream().buffered().use { o ->
                        if (velho.exists()) velho.inputStream().use { it.copyTo(o) }
                        f.inputStream().use { it.copyTo(o) }
                    }
                    saida
                }
            } catch (_: Throwable) { null }
        }) { saida -> if (saida == null) Ui.toast(c, "o registro ainda está vazio") else enviarRegistro(saida) }
    }

    private fun enviarRegistro(saida: java.io.File) {
        val c = act
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".fileprovider", saida)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_SUBJECT, "registro do bot de fila")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.startActivity(Intent.createChooser(i, "Compartilhar registro"))
        } catch (t: Throwable) {
            Ui.toast(c, "não consegui compartilhar: ${t.message}")
        }
    }

    private fun registro(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(Ui.labelIcone(c, R.drawable.ic_log, "registro"), pesoUm())
        val tam = Sniper.tamanhoRegistro.coerceAtLeast(0L)
        cab.addView(Ui.selo(c, if (tam > 0) "%.0f KB".format(tam / 1024.0) else "vazio", Theme.accent, tam > 0))
        card.addView(cab)
        card.addView(nota("Tudo desde que liga, com data e detalhes. Deu problema? Compartilhe e me mande."), Ui.lp(top = 6, c = c))
        val linha = Ui.hbox(c)
        linha.addView(Ui.button(c, "compartilhar", Ui.Kind.PRIMARY, R.drawable.ic_log) { compartilharRegistro() }, pesoUm())
        linha.addView(Ui.button(c, "apagar", Ui.Kind.DANGER, R.drawable.ic_trash) {
            Ui.dialogConfirma(c, "APAGAR REGISTRO", "Apaga o arquivo do registro e a lista daqui. Começa do zero.", "APAGAR") { Sniper.apagarRegistro() }
        }, pesoUm(8))
        card.addView(linha, Ui.lp(top = 12, c = c))
        val ls = Sniper.logs(30)
        val lista = Ui.vbox(c)
        lista.background = Botao(c, "campo", Ui.raioPeq(12))
        val p = Ui.dp(c, 12)
        lista.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 10))
        if (ls.isEmpty()) lista.addView(Ui.text(c, "nada ainda", 11f, Ui.MUTED, code = true))
        for (l in ls.reversed()) {
            val (marca, cor) = when (l.tipo) {
                "ok" -> "✓" to Ui.OK
                "ganhou" -> "★" to Ui.OK
                "erro" -> "✗" to Ui.BAD
                else -> "•" to Ui.TEXT
            }
            val r = Ui.hbox(c)
            r.addView(Ui.text(c, l.hora, 10.5f, Ui.MUTED, code = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.rightMargin = Ui.dp(c, 8) })
            r.addView(Ui.text(c, "$marca ${l.texto}", 11f, cor, code = true), pesoUm())
            lista.addView(r, Ui.lp(top = 4, c = c))
        }
        card.addView(lista, Ui.lp(top = 12, c = c))
        b.addView(card)
    }
}
