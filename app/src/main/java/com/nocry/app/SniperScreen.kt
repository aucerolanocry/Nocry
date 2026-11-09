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
                tique()
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
    private fun dica(s: String): View = Ui.dica(act, s, Ui.Dica.INFO)

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
        linha.addView(Ui.text(c, estado, 21f, if (Sniper.buscando) Ui.OK else Ui.TEXT, true))
        col.addView(linha, Ui.lp(top = 3, c = c))
        val sub = Ui.text(c, subTopo(), 10.5f, Ui.MUTED)
        tempoTxt = sub
        col.addView(sub, Ui.lp(top = 3, c = c))
        cab.addView(col, pesoUm(14))
        card.addView(cab)

        val filas = if (Sniper.maxFilas >= 300) "${Sniper.filasEntradas.size}" else "${Sniper.filasEntradas.size}/${Sniper.maxFilas}"
        card.addView(Ui.chips(c,
            numero(R.drawable.ic_chip, "filas", filas, Sniper.filasEntradas.isNotEmpty()),
            numero(R.drawable.ic_gamepad, "salas", "${Sniper.salas.size}", Sniper.salas.isNotEmpty()),
            numero(R.drawable.ic_crown, "apostas", "${Sniper.apostas}", Sniper.apostas > 0)
        ), Ui.lp(top = 16, c = c))

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
        col.background = Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, if (aceso) 0x10 else 0x08), 14,
            if (aceso) Theme.alpha(Theme.accent, 0x77) else Theme.alpha(android.graphics.Color.WHITE, 0x1C), 1)
        col.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 12))
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER
        cab.addView(Ui.icone(c, ico, if (aceso) Theme.accent else Ui.MUTED, 12))
        val r = Ui.text(c, rotulo, 9f, Ui.MUTED, true, mono = true)
        r.letterSpacing = 0.08f
        cab.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 5) })
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
        card.addView(dica(if (verif > 0)
            "Essas orgs mostram uma pergunta anti-robô (conta, comida, emoji) quando clica pra entrar. Sem responder, NÃO entra na fila. Toque em responder e escolha: um toque e já vai."
            else "A org abre um formulário quando clica pra entrar. Sem responder, NÃO entra na fila. Responda uma vez: o bot guarda e responde sozinho nas próximas."), Ui.lp(top = 6, c = c))
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
        if (fs.size > 3) card.addView(dica("+ ${fs.size - 3} esperando" + if (verif > 0) "" else " (respondendo um, os iguais vão sozinhos)"), Ui.lp(top = 8, c = c))
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
                            tv.background = Botao(c, if (sel) "grad" else "ghost", 12)
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
            t.background = Ui.ripple(c, Ui.shape(c, Theme.alpha(Theme.accent, 0x14), 20, Theme.alpha(Theme.accent, 0x5A), 1),
                Ui.shape(c, android.graphics.Color.WHITE, 20))
            t.setPadding(Ui.dp(c, 15), Ui.dp(c, 9), Ui.dp(c, 15), Ui.dp(c, 9))
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
        val foto = android.widget.FrameLayout(c)
        foto.background = Botao(c, "ghost", 12)
        foto.addView(Ui.icone(c, R.drawable.ic_image, Ui.TEXT, 20), android.widget.FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.CENTER))
        foto.contentDescription = "enviar imagem"
        Ui.clique(foto) { escolherImagem() }
        linha.addView(foto, LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)).also { it.rightMargin = Ui.dp(c, 8) })
        linha.addView(e, pesoUm())
        val enviar = Ui.button(c, "enviar", Ui.Kind.PRIMARY) { enviarCampo() }
        linha.addView(enviar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 8) })
        card.addView(linha, Ui.lp(top = 10, c = c))

        card.addView(pedaco({ assinaturaAcoes() }) { acoes(it) }, Ui.lp(top = 12, c = c))
        chatCard = card
        card.visibility = if (Sniper.ordem.isEmpty()) View.GONE else View.VISIBLE
        add(card, 12)
        add(dica("Toque numa sala pra abrir o chat dela. Pra copiar: toque no ícone de copiar da mensagem, nos botões de link/chave/ID que aparecem embaixo dela, " +
            "ou segure o dedo no texto pra escolher um pedaço. Link abre no toque. Se ninguém responder em ${Sniper.tempoResposta / 1000}s, ele cancela e volta a procurar."), 10)
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
            val n = texto(s.nome, 11f, Ui.MUTED)
            n.maxLines = 1
            n.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(n, Ui.lp(top = 1, c = c))
        }
        l.addView(col, pesoUm())
        b.addView(l)
        val ct = Ui.text(c, estadoSala(), 10.5f, Ui.WARN, true, mono = true)
        ct.background = Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x0A), 10, Theme.alpha(android.graphics.Color.WHITE, 0x1C), 1)
        ct.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6))
        ct.gravity = Gravity.CENTER
        contagemTxt = ct
        b.addView(ct, Ui.lp(top = 10, c = c))
    }

    private fun estadoSala(): String {
        val s = salaAtual() ?: return ""
        return when {
            s.apostaOk -> "★ APOSTA CONFIRMADA"
            s.encerrada -> "✗ A ORG ENCERROU ESSA SALA"
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
            sb.append('|').append(id).append(s.naoLidas).append(s.respondida).append(s.apostaOk).append(s.confirmada).append(s.somenteLeitura).append(s.encerrada)
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
            card.addView(dica(if (Sniper.buscando) "Quando formar partida, a sala aparece aqui, a mensagem automática sai na hora e chega notificação."
                else "Escolha org, modo e valor na aba Busca e toque em iniciar busca lá em cima."), Ui.lp(top = 12, c = c))
            b.addView(card)
            return
        }
        b.addView(Ui.label(c, "salas abertas · ${ids.size}"))
        val itens = ids.mapIndexedNotNull { i, id ->
            val s = Sniper.salas[id] ?: return@mapIndexedNotNull null
            salaTile(i, s, id == Sniper.salaAtiva)
        }
        b.addView(grade(itens, 2), Ui.lp(top = 8, c = c))
    }

    /** cor e texto curto do estado da sala (usado no quadradinho e no chat) */
    private fun estadoCurto(s: SSala): Pair<String, Int> {
        val agora = System.currentTimeMillis()
        return when {
            s.apostaOk -> "★ aposta confirmada" to Ui.OK
            s.encerrada -> "a org encerrou" to Theme.alpha(Ui.MUTED, 0xB0)
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
        col.background = if (sel) Botao(c, "grad", 14)
            else Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x0A), 14, Theme.alpha(cor, 0x66), 1)
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
        val est = texto(txt, 11f, if (sel) Theme.alpha(Theme.sobreGrad(), 0xD0) else cor)
        est.maxLines = 1
        col.addView(est, Ui.lp(top = 1, c = c))
        Ui.clique(col) { Sniper.ativarSala(i) }
        return col
    }

    private fun assinaturaAcoes(): String {
        val s = salaAtual()
        return "${s?.id}|${s?.protegida}|${s?.encerrada}|${Sniper.salas.size}"
    }

    private fun acoes(b: LinearLayout) {
        val c = act
        val sa = salaAtual()
        if (sa != null && sa.encerrada && !sa.protegida) {
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
        val t = e.text.toString()
        if (t.isBlank()) return
        mandar(t)
        e.setText("")
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
            eu -> Botao(c, "grad", 16)
            bot -> Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x08), 14, Theme.alpha(Theme.accent, 0x55), 1)
            else -> Botao(c, "ghost", 16)
        }
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 10))
        val corTexto = if (eu) Theme.sobreGrad() else Ui.TEXT
        val corFraca = if (eu) Theme.alpha(Theme.sobreGrad(), 0xB0) else Theme.alpha(Ui.MUTED, 0xD0)
        val larguraMax = (largura * if (eu) 0.74 else 0.66).toInt()

        // cabeçalho: nome · hora · copiar tudo
        val cab = Ui.hbox(c)
        cab.gravity = Gravity.CENTER_VERTICAL
        val nome = texto(if (eu) "você" else l.nome, 11f, if (eu) corFraca else if (bot) Theme.accent else corDoNome(l.nome), true)
        nome.maxLines = 1
        nome.ellipsize = android.text.TextUtils.TruncateAt.END
        cab.addView(nome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.weight = 1f })
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
            b.addView(iv, Ui.lp(top = 6, c = c))
        }
        if (l.texto.isNotEmpty()) {
            val t = textoCopiavel(l.texto, if (bot) 13f else 14.5f, corTexto, eu)
            t.maxWidth = larguraMax
            b.addView(t, Ui.lp(top = 3, c = c))
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
            b.addView(caixaCard, Ui.lp(top = if (l.texto.isNotEmpty()) 8 else 4, c = c))
        }
        for (a in l.arquivos) b.addView(texto("📎 $a", 12f, corFraca), Ui.lp(top = 4, c = c))
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
                p.background = if (eu) Ui.shape(c, Theme.alpha(android.graphics.Color.BLACK, 0x22), 10)
                    else Ui.shape(c, Theme.alpha(Theme.accent, 0x1E), 10, Theme.alpha(Theme.accent, 0x66), 1)
                p.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
                p.addView(Ui.icone(c, R.drawable.ic_copy, if (eu) corTexto else Theme.accent, 13))
                val tx = texto("$rot  ${encurtar(valor)}", 11.5f, corTexto, true)
                tx.maxLines = 1
                tx.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                p.addView(tx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = Ui.dp(c, 7) })
                p.contentDescription = "copiar $rot"
                Ui.clique(p) { copiar(valor, "$rot copiado") }
                col.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 6) })
            }
            b.addView(col, Ui.lp(top = 2, c = c))
        }
        linha.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linha.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 8) }
        return linha
    }

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
        val c = act
        val como = Ui.card(c)
        como.addView(Ui.labelIcone(c, R.drawable.ic_info, "como funciona"))
        como.addView(Ui.lista(c, listOf(
            "Escolha a org (ou TODAS), o gelo do 1X1, os modos e os valores. Pode marcar vários.",
            "Toque em iniciar busca. Ele procura em todas as orgs ao mesmo tempo e entra em toda fila que bate.",
            "Cada org tem um limite de filas: quando ela avisa, ele espera e tenta de novo sozinho."
        )), Ui.lp(top = 12, c = c))
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
        card.addView(Ui.itemEstado(c, "Org", if (Sniper.org == "TODAS") "procura em todas ao mesmo tempo" else "só nessa org", R.drawable.ic_globe,
            Sniper.org, Theme.accent) {
            val l = Sniper.listaOrgs
            Ui.dialogEscolha(c, "ORG", l) { i -> Sniper.trocarOrg(l[i]) }
        }, Ui.lp(top = 6, c = c))

        card.addView(Ui.label(c, "gelo · só vale no 1x1"), Ui.lp(top = 14, c = c))
        card.addView(Ui.segmento(c, listOf("Gelo normal", "Gelo infinito"), if (Sniper.gelo == "INFINITO") 1 else 0) { i ->
            Sniper.escolherGelo(if (i == 1) "INFINITO" else "NORMAL")
        }, Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "modo · ${Sniper.modos.size} marcado${if (Sniper.modos.size == 1) "" else "s"}"), Ui.lp(top = 18, c = c))
        card.addView(grade(Sniper.MODOS.map { m -> caixa(m, null, m in Sniper.modos) { Sniper.alternarModo(m) } }, 4), Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "valor · ${Sniper.valores.size} marcado${if (Sniper.valores.size == 1) "" else "s"}"), Ui.lp(top = 18, c = c))
        val disp = Sniper.valoresDisponiveis()
        card.addView(grade(Sniper.todosValores.map { v ->
            caixa(Sniper.rotuloValor(v), null, v in Sniper.valores, v in disp) { Sniper.alternarValor(v) }
        }, 4), Ui.lp(top = 8, c = c))
        card.addView(dica("Valor apagado não existe nessa org/modo. Com TODAS, o valor que uma org não tem ele procura nas outras." +
            if (Sniper.buscando) " Mudou com a busca ligada? Vale na próxima volta." else ""), Ui.lp(top = 12, c = c))
        b.addView(card)
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
            card.addView(dica("Use a conta temporária. O token fica cifrado neste celular e no backup (que só abre com a sua key de dono). Se o Discord pedir pra trocar a senha, o token muda: é só colar o novo aqui."), Ui.lp(top = 8, c = c))
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
            val (txt, ok) = when (Sniper.estado) {
                "online" -> "CONECTADO" to true
                "conectando" -> "CONECTANDO" to null
                "caiu" -> "RECONECTANDO" to null
                else -> "DESLIGADO" to false
            }
            card.addView(Ui.status(c, "DISCORD", txt, ok), Ui.lp(top = 10, c = c))
            if (Sniper.estado == "online") card.addView(Ui.status(c, "CONTA", Sniper.nomeConta.uppercase(), true))
            card.addView(dica("Com o bot ligado ele fica rodando mesmo com a tela apagada (notificação do Nocry)."), Ui.lp(top = 10, c = c))
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
            Ui.itemChave(c, "Começar a buscar ao ligar", "assim que conectar, já busca com o alvo escolhido", Sniper.buscarAoLigar) { v ->
                Sniper.definirBuscarAoLigar(v)
            },
            Ui.itemChave(c, "Sair das outras filas ao abrir sala", "desligado: continua até o limite de cada org", Sniper.sairAoAbrir) { v ->
                Sniper.definirSairAoAbrir(v)
            }
        )))
    }

    private fun assinaturaFerramentas() = "${Sniper.descobrindo}|${Sniper.progresso}|${Sniper.qtdPendentes()}|${Sniper.conectado}|${Sniper.buscando}|${Sniper.qtdRespostasSalvas()}"

    private fun ferramentas(b: LinearLayout) {
        val c = act
        if (Sniper.descobrindo) {
            val card = Ui.card(c, forte = true)
            card.addView(Ui.status(c, "LENDO AS FILAS", "AGUARDE", null))
            card.addView(Ui.text(c, Sniper.progresso.ifEmpty { "lendo as filas..." }, 12.5f, Ui.WARN, true, mono = true), Ui.lp(top = 6, c = c))
            card.addView(dica("Só lê as filas, não entra em nenhuma. Pode continuar usando o app."), Ui.lp(top = 8, c = c))
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
        b.addView(Ui.grupo(c, "ferramentas", itens), Ui.lp(top = if (Sniper.descobrindo) 12 else 0, c = c))
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
        b.addView(Ui.dica(c, if (pode)
            "Guarda valores das filas, respostas dos formulários, ajustes e o token (cifrado com a sua key). Reinstalou? Entra com a key de dono e abre o bot de fila: volta tudo sozinho."
            else "Libere o acesso a todos os arquivos (o mesmo do bypass) pra ele salvar e voltar sozinho depois de reinstalar. Toque em Backup automático.",
            if (pode) Ui.Dica.OK else Ui.Dica.AVISO), Ui.lp(top = 10, c = c))
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
        if (!f.exists() || f.length() == 0L) { Ui.toast(c, "o registro ainda está vazio"); return }
        try {
            // junta o anterior (se o arquivo ficou grande e foi trocado) com o atual
            val velho = java.io.File(f.parentFile, "registro_anterior.txt")
            val saida = java.io.File(f.parentFile, "registro_bot_de_fila.txt")
            saida.writeText((if (velho.exists()) velho.readText() else "") + f.readText())
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
        val f = Sniper.arquivoRegistro()
        val tam = if (f.exists()) f.length() else 0L
        cab.addView(Ui.selo(c, if (tam > 0) "%.0f KB".format(tam / 1024.0) else "vazio", Theme.accent, tam > 0))
        card.addView(cab)
        card.addView(dica("O registro completo (tudo desde que liga, com data e detalhes) fica num arquivo. Deu problema? Compartilhe e me mande."), Ui.lp(top = 10, c = c))
        val linha = Ui.hbox(c)
        linha.addView(Ui.button(c, "compartilhar", Ui.Kind.PRIMARY, R.drawable.ic_log) { compartilharRegistro() }, pesoUm())
        linha.addView(Ui.button(c, "apagar", Ui.Kind.DANGER, R.drawable.ic_trash) {
            Ui.dialogConfirma(c, "APAGAR REGISTRO", "Apaga o arquivo do registro e a lista daqui. Começa do zero.", "APAGAR") { Sniper.apagarRegistro() }
        }, pesoUm(8))
        card.addView(linha, Ui.lp(top = 12, c = c))
        val ls = Sniper.logs(30)
        val lista = Ui.vbox(c)
        lista.background = Botao(c, "campo", 12)
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
