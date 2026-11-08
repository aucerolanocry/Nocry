package com.nocry.app

import android.annotation.SuppressLint
import android.graphics.Typeface
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
    override val titulo = "SNIPER"

    private var aba = 0
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
            try { montar(box) } catch (t: Throwable) { NLog.add("sniper", "tela: $t") }
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
    private var chatPrimeiro: String? = null
    private var chatQtd = 0
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
        chatQtd = 0
        chatPrimeiro = null
        contagemTxt = null
        val c = act
        if (!License.isOwner()) {
            cabecalho(titulo, "Só a key de dono usa essa tela.")
            return
        }
        add(pedaco({ assinaturaTopo() }) { topo(it) }, 4)
        add(Ui.segmento(c, listOf("Partidas", "Busca", "Conta"), aba) { i ->
            if (i != aba) { aba = i; esconderTeclado(); render() }
        }, 12)
        when (aba) {
            0 -> abaPartidas()
            1 -> abaBusca()
            else -> abaConta()
        }
        val g = ++geracao
        val loop = object : Runnable {
            override fun run() {
                if (g != geracao || !root.isAttachedToWindow) return
                tique()
                root.postDelayed(this, 600)
            }
        }
        root.postDelayed(loop, 600)
    }

    private fun tique() {
        for (p in pedacos) p.atualiza()
        atualizaChat()
        tempoTxt?.text = tempoBusca()
        contagemTxt?.let { t -> t.text = estadoSala() }
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

    private fun dica(s: String): View = Ui.text(act, s, 11f, Ui.MUTED)

    private fun pesoUm(esq: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(act, esq) }

    /** botão quadradinho que marca/desmarca (modo, valor, sala) */
    private fun caixa(rot: String, sub: String?, sel: Boolean, ativo: Boolean = true, corTexto: Int? = null, aoTocar: () -> Unit): View {
        val c = act
        val b = Ui.vbox(c)
        b.gravity = Gravity.CENTER
        b.background = Botao(c, if (sel) "grad" else "ghost", 12)
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
        val card = Ui.card(c, forte = true, ligado = Sniper.buscando)
        val (txt, cor) = when (Sniper.estado) {
            "online" -> "● ONLINE · ${Sniper.nomeConta}" to Ui.OK
            "conectando" -> "○ CONECTANDO..." to Ui.WARN
            "caiu" -> "○ RECONECTANDO..." to Ui.WARN
            "token" -> "✗ TOKEN RECUSADO · TROQUE EM CONTA" to Ui.BAD
            else -> (if (Cofre.tem(c)) "○ BOT DESLIGADO" else "○ SEM TOKEN · VÁ EM CONTA") to Ui.MUTED
        }
        val l1 = Ui.hbox(c)
        l1.gravity = Gravity.CENTER_VERTICAL
        l1.addView(Ui.text(c, txt, 12.5f, cor, true, mono = true), pesoUm())
        val t = Ui.text(c, tempoBusca(), 11f, if (Sniper.buscando) Ui.OK else Ui.MUTED, mono = true)
        tempoTxt = t
        l1.addView(t)
        card.addView(l1)
        val filas = if (Sniper.maxFilas >= 300) "${Sniper.filasEntradas.size}" else "${Sniper.filasEntradas.size}/${Sniper.maxFilas}"
        card.addView(Ui.chips(c,
            Ui.chip(c, "FILAS", filas, if (Sniper.filasEntradas.isNotEmpty()) true else null),
            Ui.chip(c, "SALAS", "${Sniper.salas.size}", if (Sniper.salas.isNotEmpty()) true else null),
            Ui.chip(c, "APOSTAS", "${Sniper.apostas}", if (Sniper.apostas > 0) true else null)
        ), Ui.lp(top = 12, c = c))
        val botao = when {
            !Cofre.tem(c) -> Ui.button(c, "colar o token", Ui.Kind.PRIMARY, R.drawable.ic_key) { aba = 2; render() }
            !Sniper.ligado() -> Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }
            Sniper.estado != "online" -> Ui.button(c, "conectando...", Ui.Kind.GHOST) {}
            Sniper.buscando -> Ui.button(c, "▌▌  pausar busca", Ui.Kind.DANGER) { Sniper.alternarBusca() }
            else -> Ui.button(c, "►  iniciar busca", Ui.Kind.PRIMARY) { Sniper.alternarBusca() }
        }
        card.addView(botao, Ui.lp(top = 12, c = c))
        b.addView(card)
    }

    // ======================================================================== PARTIDAS
    private fun abaPartidas() {
        val c = act
        add(pedaco({ assinaturaSalas() }) { salas(it) }, 12)

        // chat (montado uma vez só)
        val card = Ui.card(c)
        val cabeca = Ui.hbox(c)
        cabeca.gravity = Gravity.CENTER_VERTICAL
        cabeca.addView(Ui.labelIcone(c, R.drawable.ic_gamepad, "chat da sala"), pesoUm())
        val ct = Ui.text(c, estadoSala(), 10.5f, Ui.MUTED, true, mono = true)
        contagemTxt = ct
        cabeca.addView(ct)
        card.addView(cabeca)

        val sv = object : ScrollView(c) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                // a página também rola: enquanto o dedo está no chat, quem rola é o chat
                if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) parent?.requestDisallowInterceptTouchEvent(true)
                return super.onTouchEvent(ev)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                return super.onInterceptTouchEvent(ev)
            }
        }
        sv.isVerticalScrollBarEnabled = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.background = Botao(c, "campo", 14)
        val lista = Ui.vbox(c)
        val p = Ui.dp(c, 10)
        lista.setPadding(p, p, p, p)
        sv.addView(lista, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (altura * 0.40).toInt()).also { it.topMargin = Ui.dp(c, 10) })
        chatRolagem = sv
        chatLista = lista

        // respostas rápidas (um toque e já manda)
        val hs = HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val rapidas = Ui.hbox(c)
        val frases = listOf("Go", "Bora", "Full UMP e XM8?", "Sem mobilador", "Regras ok ✅", "Cria a sala", "Manda o ID", "Pronto")
        for ((i, f) in frases.withIndex()) {
            val t = texto(f, 13f, Ui.TEXT, true)
            t.background = Botao(c, "ghost", 12)
            t.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            Ui.clique(t) { mandar(f) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = Ui.dp(c, 6)
            rapidas.addView(t, lp)
        }
        hs.addView(rapidas)
        card.addView(hs, Ui.lp(top = 10, c = c))

        // escrever
        val linha = Ui.hbox(c)
        linha.gravity = Gravity.CENTER_VERTICAL
        val e = campoMsg ?: Ui.field(c, "escreva pro adversário...", false).also {
            it.imeOptions = EditorInfo.IME_ACTION_SEND
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            it.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviarCampo(); true } else false }
            campoMsg = it
        }
        solta(e)
        linha.addView(e, pesoUm())
        val enviar = Ui.button(c, "enviar", Ui.Kind.PRIMARY) { enviarCampo() }
        linha.addView(enviar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 8) })
        card.addView(linha, Ui.lp(top = 10, c = c))

        card.addView(pedaco({ assinaturaAcoes() }) { acoes(it) }, Ui.lp(top = 12, c = c))
        chatCard = card
        card.visibility = if (Sniper.ordem.isEmpty()) View.GONE else View.VISIBLE
        add(card, 12)
        add(dica("Toque no número pra trocar de sala. A mensagem automática sai sozinha assim que a sala abre. " +
            "Se nenhuma pessoa responder em ${Sniper.tempoResposta / 1000}s, ele cancela e volta a procurar. Sala com ★ (aposta confirmada) fica até a org apagar."), 10)
        atualizaChat()
    }

    private fun salaAtual(): SSala? = Sniper.salaAtiva?.let { Sniper.salas[it] }

    private fun estadoSala(): String {
        val s = salaAtual() ?: return ""
        return when {
            s.apostaOk -> "★ APOSTA CONFIRMADA"
            s.confirmada -> "✓ VOCÊ CONFIRMOU"
            s.somenteLeitura -> "SÓ LEITURA"
            s.respondida -> "ADVERSÁRIO RESPONDEU"
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
            sb.append('|').append(id).append(s.naoLidas).append(s.respondida).append(s.apostaOk).append(s.confirmada).append(s.somenteLeitura)
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
            card.addView(Ui.labelIcone(c, R.drawable.ic_gamepad, "salas"))
            card.addView(Ui.text(c, "Nenhuma sala aberta", 15f, Ui.TEXT, true), Ui.lp(top = 12, c = c))
            card.addView(dica(if (Sniper.buscando) "Buscando. Quando formar partida a sala aparece aqui, a mensagem automática sai na hora e chega notificação."
                else "Toque em INICIAR BUSCA lá em cima. Antes, escolha org, modo e valor na aba BUSCA."), Ui.lp(top = 6, c = c))
            b.addView(card)
            return
        }
        val agora = System.currentTimeMillis()
        val itens = ids.mapIndexedNotNull { i, id ->
            val s = Sniper.salas[id] ?: return@mapIndexedNotNull null
            val sel = id == Sniper.salaAtiva
            val cor = when {
                sel -> null
                s.protegida -> Ui.OK
                s.respondida -> Ui.TEXT
                agora - s.aberta > Sniper.tempoResposta - 10_000 -> Ui.BAD
                else -> Ui.WARN
            }
            val rot = (if (s.apostaOk) "★" else "") + "${i + 1}" + (if (s.naoLidas > 0) " •${s.naoLidas}" else "")
            caixa(rot, s.org, sel, corTexto = cor) { Sniper.ativarSala(i) }
        }
        b.addView(grade(itens, 4))
        salaAtual()?.let { s ->
            b.addView(Ui.text(c, "SALA ${Sniper.numeroSala(s.id)} · ${s.org} · ${s.nome}", 11f, if (s.protegida) Ui.OK else Ui.MUTED, true, mono = true), Ui.lp(top = 10, c = c))
        }
    }

    private fun assinaturaAcoes(): String {
        val s = salaAtual()
        return "${s?.id}|${s?.protegida}|${Sniper.salas.size}"
    }

    private fun acoes(b: LinearLayout) {
        val c = act
        val linha = Ui.hbox(c)
        linha.addView(Ui.button(c, "✓ confirmar", Ui.Kind.PRIMARY) { Sniper.confirmar() }, pesoUm())
        linha.addView(Ui.button(c, "✗ cancelar", Ui.Kind.DANGER) {
            Ui.dialogConfirma(c, "CANCELAR SALA", "Ele clica em cancelar/sair na sala e volta a procurar nessa org.", "CANCELAR") { Sniper.cancelar() }
        }, pesoUm(8))
        b.addView(linha)
        val tirar = Ui.text(c, "tirar essa sala do painel", 11f, Ui.MUTED, true, mono = true)
        tirar.gravity = Gravity.CENTER
        tirar.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4))
        Ui.clique(tirar) {
            Ui.dialogConfirma(c, "TIRAR DO PAINEL", "A sala só some daqui. No Discord nada muda (não clica em nada).", "TIRAR") { Sniper.largar() }
        }
        b.addView(tirar)
    }

    private fun enviarCampo() {
        val e = campoMsg ?: return
        val t = e.text.toString()
        if (t.isBlank()) return
        mandar(t)
        e.setText("")
    }

    private fun mandar(t: String) {
        if (Sniper.salas.isEmpty()) { Ui.toast(act, "nenhuma sala aberta"); return }
        Sniper.enviar(t)
    }

    /** só acrescenta as mensagens novas no fim (não refaz o chat inteiro) */
    private fun atualizaChat() {
        val lista = chatLista ?: return
        val sv = chatRolagem ?: return
        val s = salaAtual()
        val id = s?.id ?: ""
        val linhas = if (s == null) emptyList() else synchronized(s.chat) { s.chat.toList() }
        if (id != chatSala || linhas.firstOrNull()?.id != chatPrimeiro || linhas.size < chatQtd) {
            lista.removeAllViews()
            chatSala = id
            chatQtd = 0
            chatPrimeiro = linhas.firstOrNull()?.id
        }
        if (linhas.isEmpty()) {
            if (lista.childCount == 0) lista.addView(texto(if (s == null) "o chat aparece aqui" else "carregando as mensagens...", 12.5f, Ui.MUTED))
            return
        }
        if (chatQtd == 0) lista.removeAllViews()
        if (linhas.size == chatQtd) return
        val noFim = sv.scrollY + sv.height >= lista.height - Ui.dp(act, 80)
        for (l in linhas.drop(chatQtd)) lista.addView(bolha(l))
        val primeiraVez = chatQtd == 0
        chatQtd = linhas.size
        if (noFim || primeiraVez) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bolha(l: SChat): View {
        val c = act
        val eu = l.tipo == "voce"
        val bot = l.tipo == "bot"
        val linha = LinearLayout(c)
        linha.orientation = LinearLayout.HORIZONTAL
        linha.gravity = if (eu) Gravity.END else Gravity.START
        val b = Ui.vbox(c)
        b.background = Botao(c, if (eu) "grad" else if (bot) "ico" else "ghost", 14)
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 9))
        val corTexto = if (eu) Theme.sobreGrad() else if (bot) Ui.MUTED else Ui.TEXT
        val nome = texto("${if (eu) "você" else l.nome} · ${l.hora}", 10.5f, if (eu) Theme.alpha(Theme.sobreGrad(), 0xB0) else if (bot) Theme.alpha(Ui.MUTED, 0xB0) else Ui.ACC2, true)
        b.addView(nome)
        val t = texto(l.texto, if (bot) 12.5f else 14.5f, corTexto)
        t.maxWidth = (largura * 0.72).toInt()
        if (bot) t.typeface = Typeface.create(Theme.fonte(c, false, false), Typeface.NORMAL)
        b.addView(t, Ui.lp(top = 2, c = c))
        linha.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linha.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 6) }
        return linha
    }

    // ======================================================================== BUSCA
    private fun abaBusca() {
        val c = act
        val como = Ui.card(c)
        como.addView(Ui.labelIcone(c, R.drawable.ic_info, "como funciona"))
        for ((i, s) in listOf(
            "Escolha a org (ou TODAS), o gelo do 1X1, os modos e os valores. Pode marcar vários.",
            "Toque em INICIAR BUSCA. Ele procura em todas as orgs ao mesmo tempo e entra em toda fila que bate.",
            "Cada org tem um limite de filas: quando ela avisa, ele espera e tenta de novo sozinho."
        ).withIndex()) {
            val l = Ui.hbox(c)
            l.addView(Ui.text(c, "${i + 1}", 13f, Ui.RED, true, mono = true), LinearLayout.LayoutParams(Ui.dp(c, 22), ViewGroup.LayoutParams.WRAP_CONTENT))
            l.addView(texto(s, 12.5f, Ui.TEXT), pesoUm())
            como.addView(l, Ui.lp(top = 8, c = c))
        }
        add(como, 12)
        add(pedaco({ assinaturaAlvo() }) { alvo(it) }, 12)
        add(pedaco({ assinaturaOrgs() }) { orgs(it) }, 12)
    }

    private fun assinaturaAlvo() = listOf(Sniper.org, Sniper.gelo, Sniper.modos.sorted(), Sniper.valores.sorted(),
        Sniper.todosValores.size, Sniper.buscando).joinToString("|")

    private fun alvo(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_sliders, "alvo"))
        card.addView(Ui.label(c, "org"), Ui.lp(top = 12, c = c))
        card.addView(Ui.button(c, "${Sniper.org}  ▾", Ui.Kind.GHOST) {
            val l = Sniper.listaOrgs
            Ui.dialogEscolha(c, "ORG", l) { i -> Sniper.trocarOrg(l[i]) }
        }, Ui.lp(top = 6, c = c))

        card.addView(Ui.label(c, "gelo · só vale no 1x1"), Ui.lp(top = 16, c = c))
        card.addView(Ui.segmento(c, listOf("Gelo normal", "Gelo infinito"), if (Sniper.gelo == "INFINITO") 1 else 0) { i ->
            Sniper.escolherGelo(if (i == 1) "INFINITO" else "NORMAL")
        }, Ui.lp(top = 6, c = c))

        card.addView(Ui.label(c, "modo · marque quantos quiser"), Ui.lp(top = 16, c = c))
        card.addView(grade(Sniper.MODOS.map { m -> caixa(m, null, m in Sniper.modos) { Sniper.alternarModo(m) } }, 4), Ui.lp(top = 6, c = c))

        card.addView(Ui.label(c, "valor · marque quantos quiser"), Ui.lp(top = 16, c = c))
        val disp = Sniper.valoresDisponiveis()
        card.addView(grade(Sniper.todosValores.map { v ->
            caixa(Sniper.rotuloValor(v), null, v in Sniper.valores, v in disp) { Sniper.alternarValor(v) }
        }, 4), Ui.lp(top = 6, c = c))
        card.addView(dica("Valor apagado não existe nessa org/modo. Com TODAS, o valor que uma org não tem ele procura nas outras."), Ui.lp(top = 10, c = c))
        if (Sniper.buscando) card.addView(dica("Mudou o alvo com a busca ligada? Vale na próxima volta, não precisa pausar."), Ui.lp(top = 6, c = c))
        b.addView(card)
    }

    private fun assinaturaOrgs(): String =
        Sniper.buscando.toString() + Sniper.org + Sniper.statusOrg.entries.sortedBy { it.key }.joinToString { it.key + it.value.first + it.value.second } +
            Sniper.modos.sorted() + Sniper.valores.sorted()

    private fun orgs(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_info, "cada org"))
        card.addView(dica("O que está acontecendo em cada org agora."), Ui.lp(top = 6, c = c))
        val filas = Sniper.filasAlvo()
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
            linha.addView(Ui.text(c, o, 12f, Ui.TEXT, true, mono = true), LinearLayout.LayoutParams(Ui.dp(c, 112), ViewGroup.LayoutParams.WRAP_CONTENT))
            linha.addView(texto(txt, 12f, cor), pesoUm())
            card.addView(linha, Ui.lp(top = 10, c = c))
        }
        if (Sniper.semValor.isNotEmpty()) {
            card.addView(dica("Sem valor ainda (não entram): ${Sniper.semValor.joinToString(", ")}. Use DESCOBRIR VALORES na aba CONTA."), Ui.lp(top = 12, c = c))
        }
        b.addView(card)
    }

    // ======================================================================== CONTA
    private fun abaConta() {
        add(pedaco({ listOf(Sniper.estado, Sniper.nomeConta, Sniper.ligado(), Cofre.tem(act)).joinToString("|") }) { conta(it) }, 12)
        add(pedaco({ assinaturaAjustes() }) { ajustes(it) }, 12)
        add(pedaco({ assinaturaFerramentas() }) { ferramentas(it) }, 12)
        add(pedaco({ val l = Sniper.logs(1).lastOrNull(); "${l?.hora}${l?.texto}" }) { registro(it) }, 12)
    }

    private fun conta(b: LinearLayout) {
        val c = act
        val card = Ui.card(c, ligado = Sniper.conectado)
        card.addView(Ui.labelIcone(c, R.drawable.ic_user, "conta do discord"))
        val temToken = Cofre.tem(c)
        if (!temToken || Sniper.estado == "token") {
            card.addView(Ui.text(c, if (Sniper.estado == "token") "O Discord recusou o token" else "Cole o token da conta", 15f, Ui.TEXT, true), Ui.lp(top = 12, c = c))
            card.addView(dica("Use a conta temporária. O token fica cifrado só neste celular. Se o Discord pedir pra trocar a senha, o token muda: é só colar o novo aqui."), Ui.lp(top = 6, c = c))
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
        } else {
            val (txt, cor) = when (Sniper.estado) {
                "online" -> "Conectado como ${Sniper.nomeConta}" to Ui.OK
                "conectando" -> "Conectando..." to Ui.WARN
                "caiu" -> "Reconectando..." to Ui.WARN
                else -> "Bot desligado" to Ui.MUTED
            }
            card.addView(Ui.text(c, txt, 15f, cor, true), Ui.lp(top = 12, c = c))
            card.addView(dica("Com o bot ligado ele fica rodando mesmo com a tela apagada (notificação do Nocry)."), Ui.lp(top = 6, c = c))
            card.addView(if (!Sniper.ligado())
                Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }
            else
                Ui.button(c, "desligar o bot", Ui.Kind.GHOST, R.drawable.ic_stop) {
                    Ui.dialogConfirma(c, "DESLIGAR", "O bot sai do Discord e para de buscar. As filas em que ele já entrou continuam lá.", "DESLIGAR") {
                        Bg.io { Sniper.desligar() }
                    }
                }, Ui.lp(top = 12, c = c))
            card.addView(Ui.item(c, "Trocar token", "Apaga o token salvo e desliga o bot.", 0, null) {
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
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_sliders, "ajustes"))
        card.addView(Ui.item(c, "Mensagem automática", Sniper.msgAuto, 0, null) {
            Ui.dialogTexto(c, "MENSAGEM AUTOMÁTICA", Sniper.msgAuto) { t -> if (t.isNotBlank()) Sniper.definirMsgAuto(t) }
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.item(c, "Tempo pra responder", "Sem resposta de pessoa nesse tempo: cancela a sala e volta a procurar.", 0, "${Sniper.tempoResposta / 1000}s") {
            Ui.dialogNumero(c, "TEMPO PRA RESPONDER (s)", "de 10 a 300 segundos", 10, 300, (Sniper.tempoResposta / 1000).toInt()) { n -> Sniper.definirTempoResposta(n) }
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.item(c, "Limite de filas do bot", "300 = sem limite (quem segura é o limite de cada org).", 0, if (Sniper.maxFilas >= 300) "sem limite" else "${Sniper.maxFilas}") {
            Ui.dialogNumero(c, "LIMITE DE FILAS DO BOT", "de 1 a 300 (300 = sem limite)", 1, 300, Sniper.maxFilas) { n -> Sniper.definirMaxFilas(n) }
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.toggle(c, "Começar a buscar ao ligar", "Assim que conectar, já inicia a busca com o alvo escolhido.", Sniper.buscarAoLigar) { v ->
            Sniper.definirBuscarAoLigar(v)
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.toggle(c, "Sair das outras filas ao abrir sala", "Desligado: continua entrando até o limite de cada org. Ligado: abriu sala, sai das outras filas e pausa.", Sniper.sairAoAbrir) { v ->
            Sniper.definirSairAoAbrir(v)
        }, Ui.lp(top = 8, c = c))
        b.addView(card)
    }

    private fun assinaturaFerramentas() = "${Sniper.descobrindo}|${Sniper.progresso}|${Sniper.qtdPendentes()}|${Sniper.conectado}|${Sniper.buscando}"

    private fun ferramentas(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_bolt, "ferramentas"))
        if (Sniper.descobrindo) {
            card.addView(Ui.text(c, Sniper.progresso.ifEmpty { "lendo as filas..." }, 12.5f, Ui.WARN, true, mono = true), Ui.lp(top = 10, c = c))
            card.addView(dica("Só lê as filas, não entra em nenhuma. Pode continuar usando o app."), Ui.lp(top = 4, c = c))
        }
        val np = Sniper.qtdPendentes()
        if (np > 0) {
            card.addView(Ui.item(c, "Descobrir valores", "Lê o valor das $np filas que vieram como \"Desconhecido\" (${Sniper.pendentes.keys.joinToString(", ")}).", 0, "$np") {
                when {
                    Sniper.descobrindo -> Ui.toast(c, "já está lendo as filas")
                    !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                    else -> Sniper.descobrir()
                }
            }, Ui.lp(top = 8, c = c))
        }
        card.addView(Ui.item(c, "Conferir todos os valores", "Abre cada fila de todas as orgs e corrige ID com valor trocado. Uns 3 min. Pause a busca antes.", 0, null) {
            when {
                Sniper.descobrindo -> Ui.toast(c, "já está lendo as filas")
                !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                Sniper.buscando -> Ui.toast(c, "pause a busca primeiro")
                else -> Sniper.descobrir(conferirTudo = true)
            }
        }, Ui.lp(top = 8, c = c))
        b.addView(card)
    }

    private fun registro(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_log, "registro"))
        card.addView(dica("Verde deu certo · vermelho deu erro · branco é aviso. Se algo falhar, manda print disso."), Ui.lp(top = 6, c = c))
        val ls = Sniper.logs(30)
        if (ls.isEmpty()) card.addView(Ui.text(c, "nada ainda", 11f, Ui.MUTED, code = true), Ui.lp(top = 8, c = c))
        for (l in ls.reversed()) {
            val (marca, cor) = when (l.tipo) {
                "ok" -> "✓" to Ui.OK
                "ganhou" -> "★" to Ui.OK
                "erro" -> "✗" to Ui.BAD
                else -> "•" to Ui.TEXT
            }
            card.addView(Ui.text(c, "${l.hora} $marca ${l.texto}", 11f, cor, code = true), Ui.lp(top = 5, c = c))
        }
        b.addView(card)
    }
}
