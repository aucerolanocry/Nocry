package com.nocry.app

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela do SNIPER (só dono). Monta uma vez e vai atualizando os pedaços sozinha,
 * sem mexer no campo de escrever (o teclado não fecha quando chega mensagem).
 */
class SniperScreen(a: MainActivity) : Screen(a) {
    override val titulo = "SNIPER"

    private val secConta by lazy { Ui.vbox(act) }
    private val secBusca by lazy { Ui.vbox(act) }
    private val secSalasTopo by lazy { Ui.vbox(act) }
    private val secChat by lazy { Ui.vbox(act) }
    private val secSalasBotoes by lazy { Ui.vbox(act) }
    private val secOrgs by lazy { Ui.vbox(act) }
    private val secAlvo by lazy { Ui.vbox(act) }
    private val secAjustes by lazy { Ui.vbox(act) }
    private val secLog by lazy { Ui.vbox(act) }

    private var campoMsg: EditText? = null
    private var campoToken: EditText? = null
    private var linhaEnviar: View? = null
    private var tempoTxt: TextView? = null
    private var visto = -1L
    private var geracao = 0

    override fun render() {
        limpa()
        val c = act
        if (!License.isOwner()) {
            cabecalho(titulo, "Só a key de dono usa essa tela.")
            return
        }
        cabecalho(titulo, "Entra sozinho nas filas das orgs, abre a sala, manda a mensagem automática e te deixa conversar, confirmar e cancelar.")
        for (s in listOf(secConta, secBusca)) solta(s)
        add(secConta, 14)
        add(secBusca, 12)

        // ---- salas e chat ----
        val cardSalas = Ui.card(c)
        cardSalas.addView(Ui.labelIcone(c, R.drawable.ic_gamepad, "salas"))
        for (s in listOf(secSalasTopo, secChat, secSalasBotoes)) solta(s)
        cardSalas.addView(secSalasTopo, Ui.lp(top = 10, c = c))
        cardSalas.addView(secChat, Ui.lp(top = 10, c = c))
        cardSalas.addView(linhaDeEnviar(), Ui.lp(top = 10, c = c))
        cardSalas.addView(secSalasBotoes, Ui.lp(top = 10, c = c))
        add(cardSalas, 12)

        for (s in listOf(secOrgs, secAlvo, secAjustes, secLog)) solta(s)
        add(secOrgs, 12)
        add(secAlvo, 12)
        add(secAjustes, 12)
        add(secLog, 12)

        visto = -1L
        atualiza()
        val g = ++geracao
        val loop = object : Runnable {
            override fun run() {
                if (g != geracao || !root.isAttachedToWindow) return
                if (Sniper.versao.get() != visto) atualiza() else tempo()
                root.postDelayed(this, 700)
            }
        }
        root.postDelayed(loop, 700)
    }

    private fun solta(v: View) { (v.parent as? ViewGroup)?.removeView(v) }

    // ======================================================================== atualização
    private fun atualiza() {
        visto = Sniper.versao.get()
        try {
            conta(); busca(); salasTopo(); chat(); salasBotoes(); orgs(); alvo(); ajustes(); registro()
        } catch (t: Throwable) {
            NLog.add("sniper", "tela: $t")
        }
    }

    private fun tempo() {
        val t = tempoTxt ?: return
        t.text = tempoBusca()
    }

    private fun tempoBusca(): String {
        if (!Sniper.buscando) return "--:--"
        val s = (System.currentTimeMillis() - Sniper.inicioBusca) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    // ---------------- conta ----------------
    private fun conta() {
        val c = act
        secConta.removeAllViews()
        val card = Ui.card(c, ligado = Sniper.conectado)
        card.addView(Ui.labelIcone(c, R.drawable.ic_user, "conta do discord"))
        val temToken = Cofre.tem(c)
        val (txt, cor) = when (Sniper.estado) {
            "online" -> "● ONLINE · ${Sniper.nomeConta}" to Ui.OK
            "conectando" -> "○ CONECTANDO..." to Ui.WARN
            "caiu" -> "○ RECONECTANDO..." to Ui.WARN
            "token" -> "✗ O DISCORD RECUSOU O TOKEN" to Ui.BAD
            else -> (if (temToken) "○ DESLIGADO" else "○ SEM TOKEN") to Ui.MUTED
        }
        card.addView(Ui.text(c, txt, 15f, cor, true, mono = true), Ui.lp(top = 10, c = c))
        if (!temToken || Sniper.estado == "token") {
            card.addView(Ui.text(c, "Cole o token da conta (de preferência a conta temporária). Ele fica cifrado e só neste celular.", 11f, Ui.MUTED), Ui.lp(top = 8, c = c))
            val e = campoToken ?: Ui.field(c, "token da conta", false).also {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                campoToken = it
            }
            solta(e)
            card.addView(e, Ui.lp(top = 10, c = c))
            card.addView(Ui.button(c, "salvar token e ligar", Ui.Kind.PRIMARY, R.drawable.ic_key) {
                val t = e.text.toString().trim()
                if (t.length < 20) { Ui.toast(c, "token parece incompleto"); return@button }
                Sniper.salvarToken(t)
                e.setText("")
                Sniper.estado = "desligado"
                Sniper.ligar()
            }, Ui.lp(top = 10, c = c))
        } else if (!Sniper.ligado()) {
            card.addView(Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }, Ui.lp(top = 12, c = c))
        } else {
            card.addView(Ui.button(c, "desligar o bot", Ui.Kind.GHOST, R.drawable.ic_stop) {
                Ui.dialogConfirma(c, "DESLIGAR", "O bot sai do Discord e para de buscar. As filas em que ele já entrou continuam lá.", "DESLIGAR") {
                    Bg.io { Sniper.desligar() }
                }
            }, Ui.lp(top = 12, c = c))
        }
        secConta.addView(card)
    }

    // ---------------- busca ----------------
    private fun busca() {
        val c = act
        secBusca.removeAllViews()
        val card = Ui.card(c, forte = true, ligado = Sniper.buscando)
        card.addView(Ui.labelIcone(c, R.drawable.ic_bolt, "busca"))
        val alvo = "${Sniper.org} · ${Sniper.modos.sorted().joinToString(" ").ifEmpty { "sem modo" }} · ${Sniper.textoValores(Sniper.valores)}"
        card.addView(Ui.text(c, alvo, 12f, Ui.TEXT, true, mono = true), Ui.lp(top = 8, c = c))
        val t = Ui.text(c, tempoBusca(), 11f, Ui.MUTED, mono = true)
        tempoTxt = t
        card.addView(Ui.chips(c,
            Ui.chip(c, "FILAS", "${Sniper.filasEntradas.size}/${Sniper.maxFilas}", if (Sniper.filasEntradas.isNotEmpty()) true else null),
            Ui.chip(c, "SALAS", "${Sniper.salas.size}", if (Sniper.salas.isNotEmpty()) true else null),
            Ui.chip(c, "APOSTAS", "${Sniper.apostas}", if (Sniper.apostas > 0) true else null)
        ), Ui.lp(top = 12, c = c))
        val linha = Ui.hbox(c)
        linha.addView(Ui.text(c, if (Sniper.buscando) "buscando há " else "parado ", 11f, Ui.MUTED, mono = true))
        linha.addView(t)
        card.addView(linha, Ui.lp(top = 8, c = c))
        card.addView(if (Sniper.buscando)
            Ui.button(c, "▌▌  pausar busca", Ui.Kind.DANGER) { Sniper.alternarBusca() }
        else
            Ui.button(c, "►  iniciar busca", Ui.Kind.PRIMARY) { Sniper.alternarBusca() }, Ui.lp(top = 12, c = c))
        secBusca.addView(card)
    }

    // ---------------- salas ----------------
    private fun salasTopo() {
        val c = act
        secSalasTopo.removeAllViews()
        val ids = Sniper.ordem.toList()
        if (ids.isEmpty()) {
            secSalasTopo.addView(Ui.text(c, "Nenhuma sala aberta. Quando formar partida ela aparece aqui sozinha (e chega notificação).", 11.5f, Ui.MUTED))
            return
        }
        var linha = Ui.hbox(c)
        for ((i, id) in ids.withIndex()) {
            if (i > 0 && i % 6 == 0) {
                secSalasTopo.addView(linha, Ui.lp(top = if (i == 6) 0 else 6, c = c))
                linha = Ui.hbox(c)
            }
            val s = Sniper.salas[id] ?: continue
            val sel = id == Sniper.salaAtiva
            val rot = (if (s.apostaOk) "★" else "") + "${i + 1}" + (if (s.naoLidas > 0) "·${s.naoLidas}" else "")
            val cor = when {
                sel -> Theme.sobreGrad()
                s.protegida -> Ui.OK
                s.respondida -> Ui.OK
                System.currentTimeMillis() - s.aberta > Sniper.tempoResposta - 10_000 -> Ui.BAD
                else -> Ui.WARN
            }
            val tv = Ui.text(c, rot, 14f, cor, true, mono = true)
            tv.gravity = Gravity.CENTER
            tv.background = Botao(c, if (sel) "grad" else "ghost", 12)
            tv.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12))
            Ui.clique(tv) { Sniper.ativarSala(i) }
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 6 > 0) p.leftMargin = Ui.dp(c, 6)
            linha.addView(tv, p)
        }
        // completa a última linha pra os botões ficarem do mesmo tamanho
        val sobra = (6 - ids.size % 6) % 6
        for (k in 0 until sobra) {
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.leftMargin = Ui.dp(c, 6)
            linha.addView(View(c), p)
        }
        secSalasTopo.addView(linha, Ui.lp(top = if (ids.size > 6) 6 else 0, c = c))
        Sniper.salaAtiva?.let { Sniper.salas[it] }?.let { s ->
            val extra = when {
                s.apostaOk -> " · ★ APOSTA CONFIRMADA"
                s.confirmada -> " · ✓ VOCÊ CONFIRMOU"
                s.somenteLeitura -> " · SÓ LEITURA"
                else -> ""
            }
            secSalasTopo.addView(Ui.text(c, "SALA ${Sniper.numeroSala(s.id)} · ${s.org} · ${s.nome}$extra", 11f, if (s.protegida) Ui.OK else Ui.MUTED, true, mono = true), Ui.lp(top = 8, c = c))
        }
    }

    private fun chat() {
        val c = act
        secChat.removeAllViews()
        val caixa = Ui.vbox(c)
        caixa.background = Botao(c, "campo", 14)
        val p = Ui.dp(c, 12)
        caixa.setPadding(p, p, p, p)
        caixa.minimumHeight = Ui.dp(c, 240)
        val s = Sniper.salaAtiva?.let { Sniper.salas[it] }
        val linhas = if (s == null) emptyList() else synchronized(s.chat) { s.chat.takeLast(16) }
        if (linhas.isEmpty()) {
            caixa.addView(Ui.text(c, if (s == null) "o chat da sala aparece aqui" else "carregando as mensagens...", 11.5f, Ui.MUTED, code = true))
        }
        for (l in linhas) {
            val cor = when (l.tipo) { "voce" -> Ui.RED; "bot" -> Ui.MUTED; else -> Ui.TEXT }
            val tv = Ui.text(c, "${l.hora} ${if (l.tipo == "voce") "●" else "○"} ${l.nome.uppercase()} › ${l.texto}", 12f, cor, code = true)
            caixa.addView(tv, Ui.lp(top = 3, c = c))
        }
        secChat.addView(caixa)
    }

    private fun linhaDeEnviar(): View {
        linhaEnviar?.let { solta(it); return it }
        val c = act
        val linha = Ui.hbox(c)
        val e = Ui.field(c, "escreva pro adversário", false)
        e.imeOptions = EditorInfo.IME_ACTION_SEND
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val enviar = {
            val t = e.text.toString()
            if (t.isNotBlank()) { Sniper.enviar(t); e.setText("") }
        }
        e.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviar(); true } else false }
        campoMsg = e
        linha.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = Ui.button(c, "enviar", Ui.Kind.PRIMARY) { enviar() }
        val bp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bp.leftMargin = Ui.dp(c, 8)
        linha.addView(b, bp)
        linhaEnviar = linha
        return linha
    }

    private fun salasBotoes() {
        val c = act
        secSalasBotoes.removeAllViews()
        val linha = Ui.hbox(c)
        val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l2.leftMargin = Ui.dp(c, 8)
        linha.addView(Ui.button(c, "✓ confirmar", Ui.Kind.PRIMARY) { Sniper.confirmar() }, l1)
        linha.addView(Ui.button(c, "✗ cancelar", Ui.Kind.DANGER) {
            if (Sniper.salas.isEmpty()) { Sniper.cancelar(); return@button }
            Ui.dialogConfirma(c, "CANCELAR", "Clica em cancelar/sair na sala aberta no chat e libera a vaga.", "CANCELAR SALA") { Sniper.cancelar() }
        }, l2)
        secSalasBotoes.addView(linha)
        if (Sniper.salas.isNotEmpty()) {
            secSalasBotoes.addView(Ui.button(c, "tirar essa sala do painel", Ui.Kind.GHOST) {
                Ui.dialogConfirma(c, "TIRAR DO PAINEL", "A sala só some daqui. No Discord nada muda (não clica em nada).", "TIRAR") { Sniper.largar() }
            }, Ui.lp(top = 8, c = c))
        }
    }

    // ---------------- status de cada org ----------------
    private fun orgs() {
        val c = act
        secOrgs.removeAllViews()
        if (!Sniper.buscando && Sniper.statusOrg.isEmpty()) return
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_info, "cada org"))
        val filas = Sniper.filasAlvo()
        for (o in Sniper.orgsAlvo()) {
            val tem = filas.any { it[0] == o }
            val st = Sniper.statusOrg[o]
            val (txt, cor) = when {
                !tem -> "não tem esse modo/valor" to Ui.MUTED
                st == null -> (if (Sniper.buscando) "na vez dela ainda" else "parado") to Ui.MUTED
                st.second == "ok" -> st.first to Ui.OK
                st.second == "erro" -> st.first to Ui.BAD
                st.second == "pausa" -> st.first to Ui.WARN
                else -> st.first to Ui.TEXT
            }
            val linha = Ui.hbox(c)
            linha.addView(Ui.text(c, o, 11.5f, Ui.TEXT, true, mono = true), LinearLayout.LayoutParams(Ui.dp(c, 110), ViewGroup.LayoutParams.WRAP_CONTENT))
            linha.addView(Ui.text(c, txt, 11f, cor, code = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(linha, Ui.lp(top = 8, c = c))
        }
        if (Sniper.semValor.isNotEmpty()) {
            card.addView(Ui.text(c, "Ainda sem valor (não entram): ${Sniper.semValor.joinToString(", ")} · use DESCOBRIR VALORES em ajustes", 10.5f, Ui.MUTED), Ui.lp(top = 10, c = c))
        }
        secOrgs.addView(card)
    }

    // ---------------- alvo ----------------
    private fun alvo() {
        val c = act
        secAlvo.removeAllViews()
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_sliders, "alvo"))
        card.addView(Ui.button(c, "org · ${Sniper.org}  ▾", Ui.Kind.GHOST) {
            val l = Sniper.listaOrgs
            Ui.dialogEscolha(c, "ORG", l) { i -> Sniper.trocarOrg(l[i]) }
        }, Ui.lp(top = 10, c = c))

        card.addView(Ui.label(c, "gelo · vale no 1x1"), Ui.lp(top = 14, c = c))
        card.addView(Ui.segmento(c, listOf("Gelo normal", "Gelo infinito"), if (Sniper.gelo == "INFINITO") 1 else 0) { i ->
            Sniper.escolherGelo(if (i == 1) "INFINITO" else "NORMAL")
        }, Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "modo · marque quantos quiser"), Ui.lp(top = 14, c = c))
        card.addView(grade(Sniper.MODOS.map { m -> Triple(m, m in Sniper.modos, true) }, 4) { m -> Sniper.alternarModo(m) }, Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "valor · marque quantos quiser"), Ui.lp(top = 14, c = c))
        val disp = Sniper.valoresDisponiveis()
        card.addView(grade(Sniper.todosValores.map { v -> Triple(v, v in Sniper.valores, v in disp) }, 4, rotulo = { Sniper.rotuloValor(it) }) { v ->
            Sniper.alternarValor(v)
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.text(c, "Valor apagado = não existe nessa org/modo. Com TODAS, o valor que uma org não tem é procurado nas outras.", 10.5f, Ui.MUTED), Ui.lp(top = 10, c = c))
        secAlvo.addView(card)
    }

    /** caixinhas que marcam/desmarcam (modos, valores) */
    private fun grade(itens: List<Triple<String, Boolean, Boolean>>, colunas: Int, rotulo: (String) -> String = { it }, aoTocar: (String) -> Unit): View {
        val c = act
        val col = Ui.vbox(c)
        var i = 0
        while (i < itens.size) {
            val linha = Ui.hbox(c)
            for (k in 0 until colunas) {
                val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (k > 0) p.leftMargin = Ui.dp(c, 6)
                val item = itens.getOrNull(i + k)
                if (item == null) { linha.addView(View(c), p); continue }
                val (chave, sel, ativo) = item
                val tv = Ui.text(c, rotulo(chave), 12.5f, if (sel) Theme.sobreGrad() else if (ativo) Ui.TEXT else Theme.alpha(Ui.MUTED, 0x70), true, mono = true)
                tv.gravity = Gravity.CENTER
                tv.background = Botao(c, if (sel) "grad" else "ghost", 12)
                tv.setPadding(Ui.dp(c, 4), Ui.dp(c, 13), Ui.dp(c, 4), Ui.dp(c, 13))
                Ui.clique(tv) { aoTocar(chave) }
                linha.addView(tv, p)
            }
            col.addView(linha, Ui.lp(top = if (i == 0) 0 else 6, c = c))
            i += colunas
        }
        return col
    }

    // ---------------- ajustes ----------------
    private fun ajustes() {
        val c = act
        secAjustes.removeAllViews()
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_sliders, "ajustes"))
        card.addView(Ui.item(c, "Limite de filas do bot", "Teto do próprio bot (todas as orgs juntas). Cada org já segura o limite dela e avisa; ele respeita e tenta de novo depois.", 0, "${Sniper.maxFilas}") {
            Ui.dialogNumero(c, "LIMITE DE FILAS DO BOT", "de 1 a 100", 1, 100, Sniper.maxFilas) { n -> Sniper.definirMaxFilas(n) }
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.toggle(c, "Sair das outras filas ao abrir sala", "Desligado (padrão): continua entrando nas filas até o limite de cada org. Ligado: abriu sala, sai das outras filas e pausa a busca.", Sniper.sairAoAbrir) { v ->
            Sniper.definirSairAoAbrir(v)
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.toggle(c, "Começar a buscar ao ligar", "Assim que conectar, já inicia a busca com o alvo escolhido.", Sniper.buscarAoLigar) { v ->
            Sniper.definirBuscarAoLigar(v)
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.item(c, "Tempo pra responder", "Sala sem resposta de pessoa nesse tempo: ele cancela e volta a procurar.", 0, "${Sniper.tempoResposta / 1000}s") {
            Ui.dialogNumero(c, "TEMPO PRA RESPONDER (s)", "de 10 a 300 segundos", 10, 300, (Sniper.tempoResposta / 1000).toInt()) { n -> Sniper.definirTempoResposta(n) }
        }, Ui.lp(top = 8, c = c))
        card.addView(Ui.item(c, "Mensagem automática", Sniper.msgAuto, 0, null) {
            Ui.dialogTexto(c, "MENSAGEM AUTOMÁTICA", Sniper.msgAuto) { t -> if (t.isNotBlank()) Sniper.definirMsgAuto(t) }
        }, Ui.lp(top = 8, c = c))
        val np = Sniper.qtdPendentes()
        if (np > 0 || Sniper.descobrindo) {
            val sub = if (Sniper.descobrindo) Sniper.progresso.ifEmpty { "lendo as filas..." }
                else "Lê o valor das $np filas que vieram como \"Desconhecido\" (${Sniper.pendentes.keys.joinToString(", ")}). Só lê, não entra em fila."
            card.addView(Ui.item(c, "Descobrir valores", sub, 0, if (Sniper.descobrindo) "..." else "$np") {
                when {
                    Sniper.descobrindo -> Ui.toast(c, "já está descobrindo")
                    !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                    else -> Sniper.descobrir()
                }
            }, Ui.lp(top = 8, c = c))
        }
        card.addView(Ui.item(c, "Conferir todos os valores", if (Sniper.descobrindo) Sniper.progresso.ifEmpty { "lendo as filas..." }
            else "Abre cada fila de todas as orgs e corrige ID com valor trocado (FURIA/MORRO estavam assim). Demora uns 3 min. Só lê.", 0, null) {
            when {
                Sniper.descobrindo -> Ui.toast(c, "já está lendo as filas")
                !Sniper.conectado -> Ui.toast(c, "ligue o bot primeiro")
                Sniper.buscando -> Ui.toast(c, "pause a busca primeiro")
                else -> Sniper.descobrir(conferirTudo = true)
            }
        }, Ui.lp(top = 8, c = c))
        if (Cofre.tem(c)) {
            card.addView(Ui.item(c, "Trocar token", "Apaga o token salvo e desliga o bot.", 0, null) {
                Ui.dialogConfirma(c, "TROCAR TOKEN", "O token salvo é apagado e o bot desliga. Depois é só colar o novo.", "APAGAR") {
                    Bg.io { Sniper.apagarToken() }
                }
            }, Ui.lp(top = 8, c = c))
        }
        secAjustes.addView(card)
    }

    // ---------------- registro ----------------
    private fun registro() {
        val c = act
        secLog.removeAllViews()
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_log, "registro"))
        val ls = Sniper.logs(25)
        if (ls.isEmpty()) card.addView(Ui.text(c, "nada ainda", 11f, Ui.MUTED, code = true), Ui.lp(top = 8, c = c))
        for (l in ls.reversed()) {
            val (marca, cor) = when (l.tipo) {
                "ok" -> "✓" to Ui.OK
                "ganhou" -> "★" to Ui.OK
                "erro" -> "✗" to Ui.BAD
                else -> "•" to Ui.MUTED
            }
            card.addView(Ui.text(c, "${l.hora} $marca ${l.texto}", 11f, if (l.tipo == "info") Ui.TEXT else cor, code = true), Ui.lp(top = 4, c = c))
        }
        secLog.addView(card)
    }
}
