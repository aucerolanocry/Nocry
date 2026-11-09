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
    override val titulo = "SNIPER"

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
        Bg.io { if (Sniper.restaurarSozinho()) Bg.ui { for (p in pedacos) p.atualiza() } }
        add(pedaco({ assinaturaTopo() }) { topo(it) }, 4)
        add(pedaco({ Sniper.formularios.joinToString("|") { it.nonce } }) { formularios(it) }, 0)
        add(Ui.segmento(c, listOf("Partidas", "Busca", "Conta"), abaAtual) { i ->
            if (i != abaAtual) { abaAtual = i; esconderTeclado(); render() }
        }, 12)
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
            !Cofre.tem(c) -> Ui.button(c, "colar o token", Ui.Kind.PRIMARY, R.drawable.ic_key) { abaAtual = 2; render() }
            !Sniper.ligado() -> Ui.button(c, "ligar o bot", Ui.Kind.PRIMARY, R.drawable.ic_power) { Sniper.ligar() }
            Sniper.estado != "online" -> Ui.button(c, "conectando...", Ui.Kind.GHOST) {}
            Sniper.buscando -> Ui.button(c, "▌▌  pausar busca", Ui.Kind.DANGER) { Sniper.alternarBusca() }
            else -> Ui.button(c, "►  iniciar busca", Ui.Kind.PRIMARY) { Sniper.alternarBusca() }
        }
        card.addView(botao, Ui.lp(top = 12, c = c))
        b.addView(card)
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
        val foto = texto("📷", 20f, Ui.TEXT)
        foto.gravity = Gravity.CENTER
        foto.background = Botao(c, "ghost", 12)
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
        add(dica("Toque no número pra trocar de sala. A mensagem automática sai sozinha assim que a sala abre. " +
            "Se nenhuma pessoa responder em ${Sniper.tempoResposta / 1000}s, ele cancela e volta a procurar. Sala com ★ (aposta confirmada) fica até a org apagar."), 10)
        atualizaChat()
    }

    private fun salaAtual(): SSala? = Sniper.salaAtiva?.let { Sniper.salas[it] }

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
                s.encerrada && !s.protegida -> Theme.alpha(Ui.MUTED, 0x90)
                s.protegida -> Ui.OK
                s.respondida -> Ui.TEXT
                agora - s.aberta > Sniper.tempoResposta - 10_000 -> Ui.BAD
                else -> Ui.WARN
            }
            val rot = (if (s.apostaOk) "★" else if (s.encerrada) "✗" else "") + "${i + 1}" + (if (s.naoLidas > 0) " •${s.naoLidas}" else "")
            caixa(rot, s.org, sel, corTexto = cor) { Sniper.ativarSala(i) }
        }
        b.addView(grade(itens, 4))
        salaAtual()?.let { s ->
            b.addView(Ui.text(c, "SALA ${Sniper.numeroSala(s.id)} · ${s.org} · ${s.nome}", 11f, if (s.protegida) Ui.OK else Ui.MUTED, true, mono = true), Ui.lp(top = 10, c = c))
        }
    }

    private fun assinaturaAcoes(): String {
        val s = salaAtual()
        return "${s?.id}|${s?.protegida}|${s?.encerrada}|${Sniper.salas.size}"
    }

    private fun acoes(b: LinearLayout) {
        val c = act
        val sa = salaAtual()
        if (sa != null && sa.encerrada && !sa.protegida) {
            b.addView(dica("A org já fechou essa sala (cancelou ou acabou). Ela some sozinha em 3 min."))
            b.addView(Ui.button(c, "tirar do painel agora", Ui.Kind.GHOST) { Sniper.largar() }, Ui.lp(top = 8, c = c))
            return
        }
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
        val linha = LinearLayout(c)
        linha.orientation = LinearLayout.HORIZONTAL
        linha.gravity = if (eu) Gravity.END else Gravity.START
        val b = Ui.vbox(c)
        b.background = Botao(c, if (eu) "grad" else if (bot) "ico" else "ghost", 14)
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 9))
        val corTexto = if (eu) Theme.sobreGrad() else if (bot) Ui.MUTED else Ui.TEXT
        val nome = texto("${if (eu) "você" else l.nome} · ${l.hora}", 10.5f, if (eu) Theme.alpha(Theme.sobreGrad(), 0xB0) else if (bot) Theme.alpha(Ui.MUTED, 0xB0) else Ui.ACC2, true)
        b.addView(nome)
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
            b.addView(iv, Ui.lp(top = 4, c = c))
        }
        if (l.texto.isNotEmpty()) {
            val t = texto(l.texto, if (bot) 12.5f else 14.5f, corTexto)
            t.maxWidth = (largura * 0.72).toInt()
            if (bot) t.typeface = Typeface.create(Theme.fonte(c, false, false), Typeface.NORMAL)
            b.addView(t, Ui.lp(top = 2, c = c))
        }
        linha.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linha.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(c, 6) }
        return linha
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
        add(pedaco({ "${Sniper.temBackup()}|${Sniper.podeArquivos()}|${Sniper.qtdRespostasSalvas()}|${Sniper.orgs.size}" }) { backup(it) }, 12)
        add(pedaco({ val l = Sniper.logs(1).lastOrNull(); "${l?.hora}${l?.texto}|${Sniper.logs(200).size}" }) { registro(it) }, 12)
    }

    private fun conta(b: LinearLayout) {
        val c = act
        val card = Ui.card(c, ligado = Sniper.conectado)
        card.addView(Ui.labelIcone(c, R.drawable.ic_user, "conta do discord"))
        val temToken = Cofre.tem(c)
        if (!temToken || Sniper.estado == "token") {
            card.addView(Ui.text(c, if (Sniper.estado == "token") "O Discord recusou o token" else "Cole o token da conta", 15f, Ui.TEXT, true), Ui.lp(top = 12, c = c))
            card.addView(dica("Use a conta temporária. O token fica cifrado neste celular e no backup (que só abre com a sua key de dono). Se o Discord pedir pra trocar a senha, o token muda: é só colar o novo aqui."), Ui.lp(top = 6, c = c))
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

    private fun assinaturaFerramentas() = "${Sniper.descobrindo}|${Sniper.progresso}|${Sniper.qtdPendentes()}|${Sniper.conectado}|${Sniper.buscando}|${Sniper.qtdRespostasSalvas()}"

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
        val nr = Sniper.qtdRespostasSalvas()
        if (nr > 0) {
            card.addView(Ui.item(c, "Respostas dos formulários", "O bot responde sozinho os formulários das orgs com o que você já respondeu. Toque pra apagar e responder de novo.", 0, "$nr") {
                Ui.dialogConfirma(c, "APAGAR RESPOSTAS", "Apaga as $nr respostas salvas. Da próxima vez que uma org pedir formulário, ele pergunta de novo.", "APAGAR") { Sniper.apagarRespostas() }
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

    private fun backup(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_bolt, "backup"))
        card.addView(dica("O bot salva sozinho tudo que aprendeu (valores das filas, respostas dos formulários, ajustes e o token cifrado com a sua key) em " +
            "Download/Nocry/sniper_backup.json. Esse arquivo fica mesmo se desinstalar. Instalou de novo: entra com a key de dono e abre o Sniper, que volta tudo sozinho."), Ui.lp(top = 6, c = c))
        if (!Sniper.podeArquivos()) {
            card.addView(Ui.text(c, "falta liberar o acesso aos arquivos pra ele salvar e voltar sozinho", 11f, Ui.WARN, true, mono = true), Ui.lp(top = 8, c = c))
            card.addView(Ui.button(c, "liberar acesso aos arquivos", Ui.Kind.PRIMARY, R.drawable.ic_lock) {
                try {
                    val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    i.data = Uri.parse("package:${act.packageName}")
                    act.startActivity(i)
                } catch (_: Exception) {
                    try { act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
                }
            }, Ui.lp(top = 8, c = c))
        } else {
            card.addView(Ui.text(c, if (Sniper.temBackup()) "✓ backup automático ligado · volta sozinho se reinstalar" else "✓ acesso liberado · o backup sai na próxima mudança",
                10.5f, Ui.OK, true, mono = true), Ui.lp(top = 8, c = c))
        }
        val linha = Ui.hbox(c)
        linha.addView(Ui.button(c, "restaurar", Ui.Kind.PRIMARY) { restaurarBackup() }, pesoUm())
        linha.addView(Ui.button(c, "salvar agora", Ui.Kind.GHOST) {
            Bg.io {
                val ok = Sniper.salvarBackup()
                Bg.ui { Ui.toast(c, if (ok) "backup salvo em Download/Nocry" else "não consegui salvar o backup") }
            }
        }, pesoUm(8))
        card.addView(linha, Ui.lp(top = 10, c = c))
        b.addView(card)
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
            val saida = java.io.File(f.parentFile, "registro_sniper.txt")
            saida.writeText((if (velho.exists()) velho.readText() else "") + f.readText())
            val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".fileprovider", saida)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_SUBJECT, "registro do Sniper")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.startActivity(Intent.createChooser(i, "Compartilhar registro"))
        } catch (t: Throwable) {
            Ui.toast(c, "não consegui compartilhar: ${t.message}")
        }
    }

    private fun registro(b: LinearLayout) {
        val c = act
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_log, "registro"))
        card.addView(dica("Verde deu certo · vermelho deu erro · branco é aviso. O registro COMPLETO (tudo desde que liga, com data e detalhes) fica salvo num arquivo: toque em compartilhar e me manda."), Ui.lp(top = 6, c = c))
        val f = Sniper.arquivoRegistro()
        val tam = if (f.exists()) f.length() else 0L
        card.addView(Ui.text(c, if (tam > 0) "arquivo: ${"%.1f".format(tam / 1024.0)} KB" else "arquivo ainda vazio", 10.5f, Ui.MUTED, true, mono = true), Ui.lp(top = 8, c = c))
        card.addView(Ui.button(c, "compartilhar registro completo", Ui.Kind.PRIMARY, R.drawable.ic_log) { compartilharRegistro() }, Ui.lp(top = 10, c = c))
        card.addView(Ui.button(c, "apagar registro", Ui.Kind.DANGER) {
            Ui.dialogConfirma(c, "APAGAR REGISTRO", "Apaga o arquivo do registro e a lista daqui. Começa do zero.", "APAGAR") { Sniper.apagarRegistro() }
        }, Ui.lp(top = 8, c = c))
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
