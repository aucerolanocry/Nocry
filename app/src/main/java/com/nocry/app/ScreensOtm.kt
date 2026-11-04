package com.nocry.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================================
// TELA QUE MOSTRA UM TRABALHO ACONTECENDO AO VIVO (otimizacao, preparar FF...)
// =====================================================================================
class RunScreen(a: MainActivity, override val titulo: String, private val work: ((String) -> Unit) -> Boolean) : Screen(a) {
    private var tv: TextView? = null
    private var iniciou = false
    private var fim = false
    private var ok = false
    private val buf = StringBuilder()

    override fun key(): String = "$fim"

    private fun anexa(l: String) {
        buf.append(l).append('\n')
        tv?.text = buf.toString()
    }

    override fun render() {
        limpa()
        val c = act
        val hero = Ui.hero(c, if (fim) (if (ok) R.drawable.ic_check else R.drawable.ic_warn) else R.drawable.ic_bolt, "andamento",
            if (!fim) "Trabalhando..." else if (ok) "Concluído" else "Terminou com avisos", if (!fim) null else ok,
            if (!fim) "Não feche o app enquanto termina." else "Veja abaixo o que foi feito.")
        if (!fim) {
            val bp = BarraProgresso(c)
            bp.define(null)
            hero.addView(bp, Ui.lp(top = 16, h = Ui.dp(c, 8), c = c))
        }
        add(hero, 0)
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_log, "registro"))
        val t = Ui.text(c, buf.toString(), 10.5f, Ui.TEXT, code = true)
        t.setTextIsSelectable(true)
        tv = t
        card.addView(t, Ui.lp(top = 10, c = c))
        add(card, 12)
        if (fim) add(Ui.button(c, "voltar", Ui.Kind.GHOST, R.drawable.ic_back) { act.fecha() }, 16)
        if (!iniciou) {
            iniciou = true
            Bg.io {
                val r = try {
                    work { l -> Bg.ui { anexa(l) } }
                } catch (t2: Throwable) {
                    Bg.ui { anexa("[x] erro: $t2") }
                    false
                }
                Bg.ui {
                    ok = r
                    fim = true
                    redesenha()
                }
            }
        }
    }
}

// =====================================================================================
// OTIMIZACAO
// =====================================================================================
class OtmScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Otimização"

    override fun key(): String = listOf(Otm.status(), Prefs.otmAuto, Prefs.otmMsg, Prefs.otmPerf, Adb.connected).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        cabecalho("Otimização", "Fecha e congela os apps em segundo plano para liberar RAM.")
        val st = Otm.status()
        val on = st.first
        val hero = Ui.hero(c, R.drawable.ic_chip, "otimização", if (on) "Ativa" else "Desativada", on,
            if (on) "${st.second} apps congelados agora." else "Os apps em segundo plano estão livres.")
        hero.addView(Ui.chips(c,
            Ui.chip(c, "ADB", if (Adb.connected) "OK" else "OFF", Adb.connected),
            Ui.chip(c, "AUTOMÁTICO", if (Prefs.otmAuto) "LIGADO" else "DESLIGADO", if (Prefs.otmAuto) true else null),
            Ui.chip(c, "CONGELADOS", if (on) st.second.toString() else "0", if (on) true else null)
        ), Ui.lp(top = 16, c = c))
        add(hero, 12)
        add(Ui.dica(c, "Os protegidos ficam de fora. Desativar devolve tudo como estava.", Ui.Dica.INFO), 12)

        if (on) {
            add(Ui.button(c, "desativar (descongela tudo)", Ui.Kind.DANGER, R.drawable.ic_stop) { act.abre(RunScreen(act, "Desativando") { log -> Otm.desativar(log) }) }, 14)
            add(Ui.button(c, "otimizar de novo", Ui.Kind.GHOST, R.drawable.ic_check) { act.abre(RunScreen(act, "Otimizando") { log -> Otm.ativar(log) }) }, 10)
        } else {
            add(Ui.button(c, "ativar otimização", Ui.Kind.PRIMARY, R.drawable.ic_check) { act.abre(RunScreen(act, "Otimizando") { log -> Otm.ativar(log) }) }, 14)
        }

        add(Ui.grupo(c, "ferramentas", listOf(
            Ui.item(c, "Ver apps (NGC)", "o que vai fechar, o que fica protegido e por quê", R.drawable.ic_eye) { act.abre(OtmVerScreen(act)) },
            Ui.item(c, "Proteger app", "escolha o que nunca fecha", R.drawable.ic_shield) { act.abre(OtmProtegerScreen(act)) },
            Ui.item(c, "Fechar mais apps", "apps do sistema com ícone que você quer fechar junto", R.drawable.ic_xcircle) { act.abre(OtmMaisScreen(act)) },
            Ui.item(c, "Preparar o FF", "compila o Free Fire e tira da economia de energia", R.drawable.ic_gamepad) {
                Ui.dialogEscolha(act, "PREPARAR O FF", listOf("FREE FIRE NORMAL", "FREE FIRE MAX")) { i ->
                    val pkg = if (i == 0) Const.FF_NORMAL else Const.FF_MAX
                    act.abre(RunScreen(act, "Preparando o FF") { log -> Otm.prepararFf(pkg, log) })
                }
            }
        )), 22)

        add(Ui.grupo(c, "automático", listOf(
            Ui.itemChave(c, "Ligar sozinho ao abrir o FF", "abriu o FF = ativa · saiu por 45 s = desativa (só o que ele ativou). Precisa do ADB.", Prefs.otmAuto) { v ->
                if (v) {
                    if (!Adb.connected) { Ui.toast(act, "Conecte o ADB primeiro (CONEXÃO)"); redesenha() }
                    else { OtmAuto.ligar(); Ui.toast(act, "AUTOMÁTICO LIGADO — abra o FF"); redesenha() }
                } else {
                    Bg.run({ OtmAuto.desligar(); true }) { _ -> Ui.toast(act, "AUTOMÁTICO DESLIGADO"); redesenha() }
                }
            }
        )), 20)

        add(Ui.grupo(c, "opções", listOf(
            Ui.itemChave(c, "Mensagens fecham", "WhatsApp, Telegram, Signal, Messenger e Discord saem da memória, mas ficam sem avisar até você abrir o app", Prefs.otmMsg == "fechar") { v ->
                Prefs.otmMsg = if (v) "fechar" else "proteger"
            },
            Ui.itemChave(c, "CPU/GPU em nível fixo", "estável, não mais forte (esquenta mais). Vale na próxima ativação", Prefs.otmPerf) { v -> Prefs.otmPerf = v }
        )), 20)
    }
}

/** tela "lendo o celular...": cartao com barra andando */
private fun carregandoCelular(c: Context): View {
    val card = Ui.hero(c, R.drawable.ic_chip, "aguarde", "Lendo o celular...", null, "Isso leva só alguns segundos.")
    val bp = BarraProgresso(c)
    bp.define(null)
    card.addView(bp, Ui.lp(top = 16, h = Ui.dp(c, 8), c = c))
    return card
}

/** grupo de apps (nome, detalhe e tamanho em pilula); vazio vira uma linha so com o aviso */
private fun grupoApps(c: Context, titulo: String, itens: List<View>, vazio: String): View =
    Ui.grupo(c, titulo, if (itens.isEmpty()) listOf(Ui.item(c, vazio)) else itens)

/** linha com um check verde e o texto (lista do que a otimizacao tambem ajusta) */
private fun feito(c: Context, texto: String): View {
    val l = Ui.hbox(c)
    l.gravity = Gravity.TOP
    l.setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9))
    val ip = LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14))
    ip.topMargin = Ui.dp(c, 2)
    ip.rightMargin = Ui.dp(c, 10)
    l.addView(Ui.icone(c, R.drawable.ic_check, Ui.OK, 14), ip)
    l.addView(Ui.text(c, texto, 10.5f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return l
}

// =====================================================================================
// VER APPS (so olhando)
// =====================================================================================
class OtmVerScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Ver apps (NGC)"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var falhou = false
    private var iniciou = false

    override fun key(): String = "$carregando|$falhou|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        falhou = false
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            falhou = p == null
            carregando = false
            redesenha()
        }
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        val p = plano
        if (carregando) { add(carregandoCelular(c), 0); return }
        if (p == null) {
            add(Ui.dica(c, "Não consegui ler o celular. Confira a Conexão e tente de novo.", Ui.Dica.AVISO), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        val tot = p.c.sumOf { it.kb }
        val hero = Ui.hero(c, R.drawable.ic_chip, "seu celular", p.perfil(), null, "RAM livre: ${Otm.gb(p.memAvail)} GB · ${p.nivel()}")
        hero.addView(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_xcircle, "vai fechar", p.c.size.toString(), Ui.RED),
            Ui.infoBox(c, R.drawable.ic_shield, "protegidos", p.x.size.toString(), Ui.OK),
            Ui.infoBox(c, R.drawable.ic_bolt, "libera ~", "${Otm.mb(tot)} MB")
        ), Ui.lp(top = 16, c = c))
        add(hero, 0)
        if (p.setFail) add(Ui.dica(c, "O celular não deixou ler as configurações: ativar não fecha nada até resolver — reinicie o celular.", Ui.Dica.AVISO), 12)

        add(grupoApps(c, "vai fechar e congelar (${p.c.size})",
            p.c.map { Ui.item(c, Otm.curto(it.pk), if (it.escolhido) "escolhido por você" else it.pk, 0, "${Otm.mb(it.kb)} MB") },
            "nenhum app em segundo plano"), 20)
        add(grupoApps(c, "ficam protegidos (${p.x.size})",
            p.x.map { Ui.item(c, Otm.curto(it.pk), it.why, R.drawable.ic_shield) }, "nenhum"), 20)
        val sis = ArrayList<View>()
        for (s in p.s.take(12)) sis.add(Ui.item(c, Otm.curto(s.pk), if (s.pode) "pode fechar (em Fechar mais apps)" else "sistema" + (if (s.why.isNotEmpty()) " · " + s.why else ""), 0, "${Otm.mb(s.kb)} MB"))
        if (p.s.size > 12) sis.add(Ui.item(c, "+ mais ${p.s.size - 12} apps do sistema"))
        if (p.n.isNotEmpty()) sis.add(Ui.item(c, "+ ${p.n.size} processos nativos do Android", "não mexe", 0, "${Otm.mb(p.n.sumOf { it.kb })} MB"))
        add(grupoApps(c, "sistema rodando — só olhando (${p.s.size})", sis, "nenhum"), 20)

        val aj = ArrayList<String>()
        aj.add("animações zeradas e sem desfoque de janela")
        if (p.hz in 90..240) aj.add("tela travada em ${p.hz} Hz")
        aj.add("wi-fi sem teste de login")
        aj.add("NOCRY, Termux e FF fora da economia de bateria")
        aj.add("bateria inteligente e economia desligadas")
        aj.add("sem pop-up de aviso, sem scan wi-fi/bluetooth em 2º plano, sem vibração de toque")
        if (p.sdk >= 31) {
            aj.add("congelador de apps em cache do Android ligado")
            aj.add("Android não mata mais os processos em segundo plano")
            aj.add("Free Fire em modo jogo \"desempenho\" (se o jogo oferecer)")
        }
        if (Prefs.otmPerf && p.sdk >= 30) aj.add("CPU/GPU em nível fixo (se o celular aceitar)")
        add(Ui.grupo(c, "também ajusta (volta ao desativar)", aj.map { feito(c, it) }), 20)
        add(Ui.button(c, "atualizar", Ui.Kind.GHOST) { carrega() }, 16)
    }
}

// =====================================================================================
// PROTEGER APP
// =====================================================================================
class OtmProtegerScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Proteger app"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var iniciou = false

    override fun key(): String = "$carregando|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            carregando = false
            redesenha()
        }
    }

    private fun alterna(pk: String, why: String) {
        Ui.toast(act, Otm.alternaProtecao(pk, why))
        carrega()
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        if (carregando) { add(carregandoCelular(c), 0); return }
        val p = plano
        if (p == null) {
            add(Ui.dica(c, "Não consegui ler o celular. Confira a Conexão.", Ui.Dica.AVISO), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        add(Ui.dica(c, "Toque num app para alternar. Sem escudo = fecha na otimização · com escudo = nunca fecha. Só dá para mudar o que você protegeu e as mensagens.", Ui.Dica.INFO), 0)
        val fecham = p.c.filter { !it.escolhido }.map { a2 ->
            Ui.item(c, Otm.curto(a2.pk), "toque para proteger", R.drawable.ic_xcircle, "${Otm.mb(a2.kb)} MB") { alterna(a2.pk, "") }
        }
        add(grupoApps(c, "vão fechar", fecham, "nenhum app em segundo plano"), 18)
        val prot = ArrayList<View>()
        for (x in p.x) prot.add(Ui.item(c, Otm.curto(x.pk), x.why, R.drawable.ic_shield) { alterna(x.pk, x.why) })
        val vistos = HashSet<String>()
        p.c.forEach { vistos.add(it.pk) }
        p.x.forEach { vistos.add(it.pk) }
        for (pk in Otm.protegidos()) {
            if (pk in vistos) continue
            prot.add(Ui.item(c, Otm.curto(pk), "sua lista · não está rodando agora", R.drawable.ic_shield) { alterna(pk, "SUA LISTA") })
        }
        add(grupoApps(c, "protegidos", prot, "nenhum"), 20)
        add(Ui.button(c, "adicionar pelo nome do app", Ui.Kind.GHOST, R.drawable.ic_shield) {
            Ui.dialogTexto(act, "PROTEGER APP", "ex.: com.whatsapp") { nome ->
                if (Otm.protegerPorNome(nome)) { Ui.toast(act, "PROTEGIDO"); carrega() } else Ui.toast(act, "Nome inválido (só letras, números e ponto)")
            }
        }, 18)
    }
}

// =====================================================================================
// FECHAR MAIS APPS (apps do sistema com icone)
// =====================================================================================
class OtmMaisScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Fechar mais apps"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var iniciou = false

    override fun key(): String = "$carregando|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            carregando = false
            redesenha()
        }
    }

    private fun alterna(pk: String) {
        val agora = Otm.alternaExtra(pk)
        Ui.toast(act, if (agora) "$pk VAI FECHAR NA OTIMIZAÇÃO" else "$pk NÃO FECHA MAIS")
        carrega()
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        if (carregando) { add(carregandoCelular(c), 0); return }
        val p = plano
        if (p == null) {
            add(Ui.dica(c, "Não consegui ler o celular. Confira a Conexão.", Ui.Dica.AVISO), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        add(Ui.dica(c, "Toque num app do sistema para fechar junto na otimização. Toque de novo para tirar. Só aparece o que não é essencial (Telefone, Configurações, Play Services, teclado... ficam de fora).", Ui.Dica.INFO), 0)
        val mostrados = HashSet<String>()
        val escolhidos = ArrayList<View>()
        for (a2 in p.c) {
            if (!a2.escolhido) continue
            mostrados.add(a2.pk)
            escolhidos.add(Ui.item(c, Otm.curto(a2.pk), "vai fechar", R.drawable.ic_xcircle, "${Otm.mb(a2.kb)} MB") { alterna(a2.pk) })
        }
        for (pk in Otm.extras()) {
            if (pk in mostrados) continue
            val why = p.x.firstOrNull { it.pk == pk }?.why
            escolhidos.add(Ui.item(c, Otm.curto(pk), "escolhido · " + (why ?: "não está rodando"), R.drawable.ic_xcircle) { alterna(pk) })
        }
        add(grupoApps(c, "escolhidos por você", escolhidos, "nenhum"), 18)
        val pode = p.s.filter { it.pode }.map { s -> Ui.item(c, Otm.curto(s.pk), "toque para fechar junto", 0, "${Otm.mb(s.kb)} MB") { alterna(s.pk) } }
        add(grupoApps(c, "você pode escolher", pode, "nenhum app do sistema com ícone rodando agora"), 20)
    }
}

// =====================================================================================
// LOGS
// =====================================================================================
class LogScreen(a: MainActivity, private val tag: String?) : Screen(a) {
    override val titulo = if (tag == null) "Logs" else "Log · " + tag
    override val aba = if (tag == null) 3 else -1
    /** tipo escolhido nos filtros de cima (null = tudo) */
    private var filtro: String? = null

    override fun key(): String = NLog.text(tag).hashCode().toString() + "|" + filtro

    private class Item(val dia: String, val hora: String, val tipo: String, val msg: String)

    companion object {
        /** formato gravado pelo NLog: "dd/MM HH:mm:ss [tipo] mensagem" */
        private val RX = Regex("""^(\d\d/\d\d) (\d\d:\d\d:\d\d) \[([^\]]+)\] (.*)$""")
        private const val MAX_ITENS = 100

        /** cor de cada tipo de registro (erros em vermelho, modos na cor do tema, setup em verde...) */
        fun corTipo(t: String): Int = when {
            t.contains("erro") -> Ui.BAD
            t == "volume" || t == "toque-carga" || t == "carregador" || t == "comp" || t.startsWith("bypass") -> Ui.RED
            t.startsWith("otm") || t == "otimizacao" -> Ui.ACC2
            t == "key" || t == "setup" || t == "pareamento" || t == "acessibilidade" -> Ui.OK
            t == "download" -> 0xFF7FC8FF.toInt()
            else -> 0xFF9AA3B5.toInt()
        }
    }

    private fun le(): List<Item> {
        val bruto = NLog.text(tag)
        if (bruto.startsWith("(sem registros")) return emptyList()
        val l = ArrayList<Item>()
        for (linha in bruto.split('\n')) {
            val m = RX.matchEntire(linha)
            if (m != null) l.add(Item(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]))
            else if (linha.isNotBlank()) l.add(Item("", "", "app", linha))
        }
        l.reverse()
        return l
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, "Tudo o que o NOCRY fez, do mais novo pro mais antigo.", 10.5f, Ui.MUTED), 0)

        val todos = le()
        val erros = todos.count { it.tipo.contains("erro") }

        // ---------------- numeros ----------------
        add(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_log, "registros", todos.size.toString()),
            Ui.infoBox(c, R.drawable.ic_stat, "último", todos.firstOrNull()?.hora?.take(5)?.ifEmpty { "—" } ?: "—"),
            Ui.infoBox(c, R.drawable.ic_warn, "erros", erros.toString(), if (erros > 0) Ui.BAD else Ui.OK)
        ), 12)

        // ---------------- filtros por tipo ----------------
        if (tag == null && todos.isNotEmpty()) {
            val tipos = todos.groupingBy { it.tipo }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key }
            val f = filtro
            if (f != null && f !in tipos) filtro = null
            add(filtros(c, tipos), 16)
        }
        val lista = (if (filtro == null) todos else todos.filter { it.tipo == filtro }).take(MAX_ITENS)

        // ---------------- registros, agrupados por dia ----------------
        val card = Ui.card(c)
        if (lista.isEmpty()) {
            card.addView(Ui.text(c, "Nada registrado ainda.", 11f, Ui.MUTED))
        } else {
            val hoje = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(java.util.Date())
            var dia = "\u0000"
            for ((k, item) in lista.withIndex()) {
                if (item.dia != dia) {
                    dia = item.dia
                    val rot = when { item.dia.isEmpty() -> "antes"; item.dia == hoje -> "hoje"; else -> item.dia }
                    card.addView(Ui.label(c, rot), Ui.lp(top = if (k == 0) 0 else 16, c = c))
                }
                card.addView(itemLog(c, item), Ui.lp(top = 10, c = c))
            }
        }
        add(card, 12)

        add(Ui.chips(c,
            Ui.button(c, "copiar", Ui.Kind.GHOST) {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("NOCRY log", NLog.text(tag)))
                Ui.toast(act, "COPIADO")
            },
            Ui.button(c, "limpar", Ui.Kind.DANGER) { NLog.clear(); redesenha() }
        ), 14)
    }

    /** pilulas de filtro roláveis na horizontal: "tudo" + os tipos que mais aparecem */
    private fun filtros(c: Context, tipos: List<String>): View {
        val hs = android.widget.HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val l = Ui.hbox(c)
        val opcoes = listOf<String?>(null) + tipos
        for ((i, o) in opcoes.withIndex()) {
            val sel = o == filtro
            val t = Ui.text(c, o ?: "tudo", 10f, if (sel) Theme.sobreGrad() else Ui.MUTED, true, mono = true)
            t.background = if (sel) Botao(c, "grad", 12) else Ui.shape(c, Theme.alpha(Color.WHITE, 0x0A), 12, Theme.alpha(Color.WHITE, 0x22), 1)
            t.setPadding(Ui.dp(c, 13), Ui.dp(c, 8), Ui.dp(c, 13), Ui.dp(c, 8))
            Ui.clique(t) { filtro = o; redesenha() }
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) p.leftMargin = Ui.dp(c, 6)
            l.addView(t, p)
        }
        hs.addView(l)
        return hs
    }

    /** um registro: barrinha colorida do tipo, horario + etiqueta do tipo e a mensagem */
    private fun itemLog(c: Context, e: Item): View {
        val r = Ui.hbox(c)
        r.gravity = Gravity.TOP
        val cor = corTipo(e.tipo)
        val barra = View(c)
        barra.background = Ui.shape(c, cor, 2)
        r.addView(barra, LinearLayout.LayoutParams(Ui.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT))
        val col = Ui.vbox(c)
        val topo = Ui.hbox(c)
        if (e.hora.isNotEmpty()) {
            val h = Ui.text(c, e.hora, 9.5f, Ui.MUTED, code = true)
            val hp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            hp.rightMargin = Ui.dp(c, 8)
            topo.addView(h, hp)
        }
        val tg = Ui.text(c, e.tipo, 8.5f, cor, true, mono = true)
        tg.background = Ui.shape(c, Theme.alpha(cor, 0x1E), 6)
        tg.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2))
        topo.addView(tg)
        col.addView(topo)
        val m = Ui.text(c, e.msg, 10.5f, Ui.TEXT, code = true)
        m.maxLines = 4
        m.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(m, Ui.lp(top = 4, c = c))
        val cp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cp.leftMargin = Ui.dp(c, 10)
        r.addView(col, cp)
        return r
    }
}

// =====================================================================================
// ATUALIZACAO / SOBRE
// =====================================================================================
object Update {
    class Info(val tag: String, val pagina: String, val apk: String?)

    /**
     * de quanto em quanto tempo confere de novo. A checagem rapida (buscarRapido) nao usa a API do
     * GitHub, entao nao tem o limite de 60 consultas/hora - da pra olhar a cada poucos segundos e o
     * aviso obrigatorio aparecer quase na hora em que a versao nova sai.
     */
    private const val INTERVALO_CHECAGEM = 40 * 1000L
    /** mesmo forcando (ao voltar pro app), nao repete se acabou de olhar */
    private const val INTERVALO_MINIMO = 6 * 1000L

    /**
     * versao mais nova sem gastar a API do GitHub: o link /releases/latest so redireciona pra
     * /releases/tag/vX.Y.Z, entao basta ler o cabecalho Location (segue no maximo 3 redirecionamentos,
     * por causa de repositorio renomeado). O APK de cada release sempre se chama Nocry.apk.
     */
    fun buscarRapido(): Info? {
        return try {
            var url = "https://github.com/" + Const.REPO + "/releases/latest"
            repeat(3) {
                val cx = URL(url).openConnection() as HttpURLConnection
                cx.instanceFollowRedirects = false
                cx.requestMethod = "HEAD"
                cx.connectTimeout = 7000
                cx.readTimeout = 7000
                cx.setRequestProperty("User-Agent", "NOCRY-app")
                val code = cx.responseCode
                val loc = cx.getHeaderField("Location")
                cx.disconnect()
                if (code !in 300..399 || loc.isNullOrEmpty()) return null
                val abs = URL(URL(url), loc).toString()
                val tag = abs.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#').trim('/')
                if (tag.isNotEmpty()) {
                    val base = abs.substringBefore("/releases/tag/")
                    return Info(java.net.URLDecoder.decode(tag, "UTF-8"), "$base/releases/tag/$tag", "$base/releases/download/$tag/Nocry.apk")
                }
                url = abs
            }
            null
        } catch (_: Throwable) { null }
    }

    fun versaoInstalada(c: Context): String =
        try { c.packageManager.getPackageInfo(Const.PKG, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** busca a ultima versao publicada no GitHub (repositorio publico, sem token) */
    fun buscar(): Info? {
        return try {
            val u = URL("https://api.github.com/repos/" + Const.REPO + "/releases/latest")
            val cx = u.openConnection() as HttpURLConnection
            cx.connectTimeout = 8000
            cx.readTimeout = 8000
            cx.setRequestProperty("Accept", "application/vnd.github+json")
            if (cx.responseCode != 200) return null
            val j = JSONObject(cx.inputStream.bufferedReader().use { it.readText() })
            var apk: String? = null
            val arr = j.optJSONArray("assets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
            }
            Info(j.optString("tag_name"), j.optString("html_url"), apk)
        } catch (_: Throwable) { null }
    }

    private fun nums(v: String): List<Int> = v.trim().removePrefix("v").split('.', '-', '+').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** a versao publicada e mais nova que a instalada? */
    fun maisNova(atual: String, publicada: String): Boolean {
        val a = nums(atual)
        val b = nums(publicada)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** o que o app ja sabe (sem rede, instantaneo): se a versao instalada ja esta desatualizada frente a ultima conhecida */
    fun pendente(c: Context): Info? {
        val tag = Prefs.updateTagConhecida
        if (tag.isEmpty()) return null
        if (!maisNova(versaoInstalada(c), tag)) return null
        return Info(tag, Prefs.updatePaginaConhecida, Prefs.updateApkConhecida.takeIf { it.isNotEmpty() })
    }

    /** busca no github (no maximo a cada 20 min), atualiza o cache local e avisa (notificacao) na primeira vez que ve cada versao nova */
    /** forcar = olhar agora mesmo (ao abrir/voltar pro app ou na tarefa de fundo), sem esperar o intervalo */
    fun atualizarCache(c: Context, forcar: Boolean = false) {
        val agora = System.currentTimeMillis()
        val passou = agora - Prefs.updateUltimaChecagem
        if (passou in 0 until (if (forcar) INTERVALO_MINIMO else INTERVALO_CHECAGEM)) return
        Prefs.updateUltimaChecagem = agora
        val i = buscarRapido() ?: buscar() ?: return
        Prefs.updateTagConhecida = i.tag
        Prefs.updatePaginaConhecida = i.pagina
        Prefs.updateApkConhecida = i.apk ?: ""
        if (!maisNova(versaoInstalada(c), i.tag)) return
        if (Prefs.updateAvisada == i.tag) return
        Prefs.updateAvisada = i.tag
        Notif.atualizacaoDisponivel(c, i.tag, i.apk ?: i.pagina)
    }

    /** checagem antiga, mantida por compatibilidade: so atualiza o cache (o aviso/bloqueio sai do pendente()) */
    fun checarEmSegundoPlanoEAvisar(c: Context) = atualizarCache(c)

    /** baixa o APK da versao nova e abre o instalador do sistema; chama onProgresso(null) quando indeterminado */
    fun baixarEInstalar(c: Context, info: Info, onProgresso: (Float?) -> Unit, onPronto: () -> Unit, onErro: (String) -> Unit) {
        val url = info.apk
        if (url == null) { onErro("Essa versão não tem um .apk anexado — baixe pela página de releases."); return }
        val app = c.applicationContext
        val nome = "nocry-${info.tag}.apk"
        Baixador.baixar(app, url, nome, tamanhoEsperado = -1, sha256 = null, soWifi = false, ouvinte = object : Baixador.Ouvinte {
            override fun aoProgredir(baixado: Long, total: Long) {
                onProgresso(if (total > 0) baixado.toFloat() / total.toFloat() else null)
            }
            override fun aoAguardar(motivo: String) { onProgresso(null) }
            override fun aoVerificar() { onProgresso(null) }
            override fun aoFalhar(motivo: String) { onErro(motivo) }
            override fun aoTerminar(arquivo: File) {
                try {
                    if (!app.packageManager.canRequestPackageInstalls()) {
                        onErro("Autorize \"instalar apps desconhecidos\" pro NOCRY e toque em instalar de novo.")
                        try {
                            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            app.startActivity(i)
                        } catch (_: Exception) {}
                        return
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".fileprovider", arquivo)
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(uri, "application/vnd.android.package-archive")
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.startActivity(i)
                    onPronto()
                } catch (e: Exception) {
                    onErro("Não consegui abrir o instalador: ${e.message}")
                }
            }
        })
    }
}

class SobreScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Sobre"
    override val aba = 4
    private var msg = ""
    private var info: Update.Info? = null
    private var buscando = false
    private var instalando = false
    private var percent: Float? = null

    override fun key(): String = "$msg|$buscando|$instalando|$percent|${License.role()}"

    private fun versao(): String = try { act.packageManager.getPackageInfo(Const.PKG, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun abre(url: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { Ui.toast(act, "Não consegui abrir o navegador") }
    }

    override fun render() {
        limpa()
        val c = act

        // ---------------- marca ----------------
        val marca = Ui.hbox(c)
        val logo = FrameLayout(c)
        logo.background = Botao(c, "icoOn", 14)
        val n = Ui.text(c, "N", 22f, Theme.sobreGrad(), true, mono = true)
        n.gravity = Gravity.CENTER
        logo.addView(n, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lg = LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48))
        lg.rightMargin = Ui.dp(c, 12)
        marca.addView(logo, lg)
        val mc = Ui.vbox(c)
        mc.addView(Ui.gradTexto(c, "NOCRY", 24f))
        mc.addView(Ui.text(c, "versão " + versao(), 10f, Ui.MUTED, true, mono = true), Ui.lp(top = 2, c = c))
        marca.addView(mc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(marca, 4)
        add(Ui.text(c, "Toque via volume e carregador, com monitores de segundo plano direto no celular.", 10.5f, Ui.MUTED), 12)

        // ---------------- sua conta: avatar, nome, cargo e grade de informacoes ----------------
        val kc = Ui.card(c, forte = true)
        kc.addView(Ui.label(c, "sua conta"))
        val perfil = Ui.hbox(c)
        val nome = License.label()
        val av = FrameLayout(c)
        av.background = Botao(c, "grad", 26)
        val ini = Ui.text(c, (nome.trim().firstOrNull() ?: 'N').uppercaseChar().toString(), 19f, Theme.sobreGrad(), true, mono = true)
        ini.gravity = Gravity.CENTER
        av.addView(ini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ap = LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52))
        ap.rightMargin = Ui.dp(c, 12)
        perfil.addView(av, ap)
        val pc = Ui.vbox(c)
        pc.addView(Ui.text(c, if (nome.isNotEmpty()) nome else "Usuário", 16f, Ui.TEXT, true))
        val papel = License.roleNome()
        if (papel.isNotEmpty()) {
            val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            sp.topMargin = Ui.dp(c, 6)
            pc.addView(Ui.selo(c, papel, if (License.isAdmin()) Ui.RED else Ui.MUTED, License.isAdmin()), sp)
        }
        perfil.addView(pc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kc.addView(perfil, Ui.lp(top = 12, c = c))

        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val e = License.expira()
        val horas = (License.restaOffline() / 3600000L).coerceAtLeast(0)
        kc.addView(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_key, "validade", if (e == 0L) "Sem vencimento" else fmt.format(java.util.Date(e)), Ui.OK),
            Ui.infoBox(c, R.drawable.ic_globe, "sem internet", if (horas >= 48) "${horas / 24} dias" else "$horas h", if (horas >= 12) Ui.OK else Ui.WARN)
        ), Ui.lp(top = 16, c = c))
        kc.addView(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_chip, "aparelho", License.devId(c).take(8)),
            Ui.infoBox(c, R.drawable.ic_info, "versão", versao())
        ), Ui.lp(top = 8, c = c))
        kc.addView(Ui.text(c, "A key é confirmada com o servidor a cada poucas horas, quando há internet.", 10f, Ui.MUTED), Ui.lp(top = 12, c = c))
        if (License.isAdmin() && BuildConfig.API_URL.isNotBlank()) {
            kc.addView(Ui.button(c, "abrir painel de keys", Ui.Kind.PRIMARY, R.drawable.ic_globe) {
                abre(BuildConfig.API_URL.trimEnd('/') + "/admin")
            }, Ui.lp(top = 14, c = c))
            kc.addView(Ui.text(c, "No painel, entre com a sua própria key.", 10f, Ui.MUTED), Ui.lp(top = 6, c = c))
        }
        kc.addView(Ui.button(c, "sair da key", Ui.Kind.GHOST, R.drawable.ic_logout) {
            Ui.dialogConfirma(act, "SAIR DA KEY", "O app volta para a tela da key e os monitores param (o toque e a tela voltam ao normal).", "SAIR") {
                License.sair("você saiu")
            }
        }, Ui.lp(top = 12, c = c))
        add(kc, 16)

        // ---------------- atualizacao ----------------
        val card = Ui.card(c)
        card.addView(Ui.labelIcone(c, R.drawable.ic_download, "atualização"))
        val erroMsg = msg.contains("Falhou") || msg.contains("Não consegui")
        card.addView(Ui.text(c, if (msg.isEmpty()) "Toque em verificar para ver se há versão nova." else msg, 11f, if (erroMsg) Ui.WARN else Ui.TEXT), Ui.lp(top = 10, c = c))
        if (instalando) {
            val bp = BarraProgresso(c)
            bp.define(percent)
            card.addView(bp, Ui.lp(top = 10, c = c))
        }
        card.addView(Ui.button(c, if (buscando) "procurando..." else "verificar atualização", Ui.Kind.GHOST) {
            if (!buscando && !instalando) {
                buscando = true
                redesenha()
                Bg.run({ Update.buscar() }) { i ->
                    buscando = false
                    if (i == null) { msg = "Não consegui verificar agora (sem internet ou ainda não há versão publicada)."; info = null }
                    else if (Update.maisNova(versao(), i.tag)) { msg = "Versão nova disponível: ${i.tag}"; info = i }
                    else { msg = "Você está na versão mais nova (${i.tag})."; info = null }
                    redesenha()
                }
            }
        }, Ui.lp(top = 12, c = c))
        val inf = info
        if (inf != null) {
            card.addView(Ui.button(c, if (instalando) "baixando..." else "baixar e instalar agora", Ui.Kind.PRIMARY, R.drawable.ic_download) {
                if (!instalando) {
                    instalando = true; percent = null; msg = "Baixando ${inf.tag}..."; redesenha()
                    Update.baixarEInstalar(c, inf,
                        onProgresso = { p -> percent = p; redesenha() },
                        onPronto = { instalando = false; msg = "Baixado! Confirme a instalação na tela que abriu."; redesenha() },
                        onErro = { erro -> instalando = false; msg = "Falhou: $erro"; redesenha() }
                    )
                }
            }, Ui.lp(top = 8, c = c))
        }
        add(card, 12)

        // ---------------- links ----------------
        add(Ui.label(c, "links"), 22)
        val link = "https://github.com/" + Const.REPO + "/releases/latest"
        add(Ui.row(c, "Compartilhar o app", "manda o link de download pra alguém", R.drawable.ic_globe) {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, "Baixe o NOCRY: $link")
            try { act.startActivity(Intent.createChooser(i, "Compartilhar")) } catch (_: Exception) {}
        }, 8)
        add(Ui.row(c, "Página de download", "github.com/" + Const.REPO, R.drawable.ic_download) { abre(link) }, 8)

        // ---------------- creditos ----------------
        add(Ui.text(c, "App desenvolvido por Aucerola · ADB embutido: libadb-android (Muntashir Al-Islam), licença Apache-2.0.", 9.5f, Ui.MUTED), 22)
        add(Ui.text(c, "Fontes: Big Shoulders, Tektur, Outfit, Bricolage Grotesque, Gloock, Work Sans, Instrument Sans, Italiana, Pixelify Sans, Red Hat Mono, Space Grotesk, JetBrains Mono (licença SIL OFL) e Poppins (licença OFL).", 9.5f, Ui.MUTED), 6)
    }
}
