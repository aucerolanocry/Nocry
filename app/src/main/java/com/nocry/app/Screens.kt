package com.nocry.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// =====================================================================================
// ATALHOS DO INICIO (ordem e visibilidade escolhidas pelo usuario)
// =====================================================================================
object Atalhos {
    class It(val id: String, val ico: Int, val nome: String, val desc: String)

    val todos: List<It> = listOf(
    It("comp", R.drawable.ic_gamepad, "Modo competitivo", "Toque rápido para jogar"),
    It("otm", R.drawable.ic_chip, "Otimização", "Congela apps em 2º plano"),
    It("ajustes", R.drawable.ic_sliders, "Ajustes do toque", "Tempos de long e multi"),
    It("recursos", R.drawable.ic_log, "Instalar recursos", "Instala a pasta suja e limpa"),
    It("bypass", R.drawable.ic_shield, "Bypass", "Volume, DND, Bluetooth ou carga")
)

    private fun ids(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** todos os atalhos na ordem salva (os que faltam entram no fim) */
    fun ordem(): List<It> {
        val salvos = ids(Prefs.ordem).distinct().mapNotNull { id -> todos.firstOrNull { it.id == id } }
        return salvos + todos.filter { t -> salvos.none { it.id == t.id } }
    }

    fun escondido(id: String): Boolean = ids(Prefs.ocultos).contains(id)

    fun visiveis(): List<It> = ordem().filter { !escondido(it.id) }

    fun salva(lista: List<It>, ocultos: Collection<String>) {
        Prefs.ordem = lista.joinToString(",") { it.id }
        Prefs.ocultos = ocultos.joinToString(",")
    }

    fun padrao() { Prefs.ordem = ""; Prefs.ocultos = "" }
}

// =====================================================================================
// INICIO
// =====================================================================================
class HomeScreen(a: MainActivity) : Screen(a) {
    override val titulo = "NOCRY"
    override val semBarra = true
    override val aba = 0
    private var voltandoTela = false

    override fun key(): String = listOf(
    Prefs.otmAuto, Prefs.otmNivel, Prefs.bypassAtivo, Prefs.bypassVolAtivo,
    Prefs.densOrig, Prefs.ordem, Prefs.ocultos, voltandoTela,
    Adb.connected, 
    NocryService.rodando,
    Sys.acessibilidadeLigada(),
    License.isOwner(),
    Erros.novos()
).joinToString("|")
    private fun ultimaLinha(): String = NLog.ultima()

    // textos que mudam toda hora: trocados no lugar (leve), sem refazer a tela inteira
    private var ultimaTxt: TextView? = null
    private var botSub: TextView? = null
    private var versaoLog = -1L

    private fun subBot(): String = when {
        Sniper.buscando -> "buscando · ${Sniper.filasEntradas.size} filas · ${Sniper.salas.size} salas"
        Sniper.estado == "online" -> "online · busca parada"
        Sniper.ligado() -> Sniper.estado
        else -> "filas de aposta do Discord · desligado"
    }

    private fun textoUltima(): String {
        val ult = ultimaLinha()
        return if (ult.isBlank() || ult.startsWith("(sem")) "nada ainda" else ult
    }

    override fun leve() {
        botSub?.let { t -> val s = subBot(); if (t.text.toString() != s) t.text = s }
        if (NLog.versao != versaoLog) { versaoLog = NLog.versao; ultimaTxt?.text = textoUltima() }
    }

    private fun estComp(): String = if (Comp.ligado()) "ATIVO" else "desativado"
    private fun estOtm(): String {
        val st = Otm.status()
        val nv = if (Prefs.otmNivel == "full") " · FULL" else ""
        return if (st.first) "ATIVA$nv · ${st.second} apps" + (if (Prefs.otmAuto) " · AUTO" else "") else if (Prefs.otmAuto) "AUTO LIGADO$nv" else "desativada"
    }

    private fun tile(c: android.content.Context, it: Atalhos.It): View = when (it.id) {
    "comp" -> Ui.tile(c, it.ico, it.nome, it.desc, estComp(), Comp.ligado()) { act.abre(CompScreen(act)) }
    "otm" -> Ui.tile(c, it.ico, it.nome, it.desc, estOtm(), Otm.status().first || Prefs.otmAuto) { act.abre(OtmScreen(act)) }
    "recursos" -> {
        val (a, b) = Downloader.pastasInstaladas("normal")
        Ui.tile(c, it.ico, it.nome, it.desc,
            if (a && b) "INSTALADO" else if (a || b) "PARCIAL" else "FALTANDO",
            a && b) { act.abre(RecursosScreen(act)) }
    }
    "bypass" -> Ui.tile(c, it.ico, it.nome, it.desc,
        if (Prefs.bypassAtivo) "ATIVO · ${Prefs.bypassGatilho}"
        else if (Prefs.bypassVolAtivo) "ATIVO · VOLUME"
        else "DESATIVADO",
        Prefs.bypassAtivo || Prefs.bypassVolAtivo) { act.abre(BypassScreen(act)) }
    else -> Ui.tile(c, it.ico, it.nome, it.desc, "long ${Prefs.longOn} · multi ${Prefs.multiOn}", null) { act.abre(AjustesScreen(act)) }
    }
    
    override fun render() {
        limpa()
        val c = act

        // ---------------- cabecalho: logo, nome e cargo ----------------
        val cab = Ui.hbox(c)
        val logo = FrameLayout(c)
        logo.background = Botao(c, "grad", Ui.raioPeq(12))
        val n = Ui.text(c, "N", 24f, Theme.sobreGrad(), true, mono = true)
        n.gravity = Gravity.CENTER
        logo.addView(n, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lg = LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46))
        lg.rightMargin = Ui.dp(c, 12)
        cab.addView(logo, lg)
        val col = Ui.vbox(c)
        col.addView(Ui.gradTexto(c, "NOCRY", 24f))
        col.addView(Ui.text(c, "App desenvolvido por Aucerola", 10.5f, Ui.MUTED, true, mono = true))
        cab.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val papel = License.roleNome()
        if (papel.isNotEmpty()) cab.addView(Ui.selo(c, papel, if (License.isAdmin()) Ui.RED else Ui.MUTED, License.isAdmin()))
        add(cab, 0)

        // ---------------- saudacao ----------------
        // o nome que a pessoa escolheu no ESTUDIO; sem escolha, o nome da key
        val nome = Prefs.apelido.ifEmpty { License.label() }
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val saud = when {
            hora < 5 -> "Boa madrugada"
            hora < 12 -> "Bom dia"
            hora < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
        val saudTxt = Ui.text(c, "$saud,", 11f, Ui.MUTED, true, mono = true)
        saudTxt.letterSpacing = 0.16f
        add(saudTxt, 24)
        add(Ui.gradTexto(c, if (nome.isNotEmpty()) nome else "bem-vindo", 32f), 2)

        // ---------------- estado geral: anel + 3 passos ----------------
        val adb = Adb.connected
        val perm = Sys.hasWriteSecure()
        val acc = Sys.acessibilidadeLigada()
        val ok = (if (adb) 1 else 0) + (if (perm) 1 else 0) + (if (acc) 1 else 0)

        val hero = Ui.card(c, forte = true, ligado = ok == 3)
        val topo = Ui.hbox(c)
        val ring = RingView(c)
        val rw = FrameLayout(c)
        rw.addView(ring, FrameLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)))
        val num = Ui.text(c, "$ok/3", 22f, Ui.TEXT, true, mono = true)
        num.gravity = Gravity.CENTER
        rw.addView(num, FrameLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)))
        topo.addView(rw, LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)))
        ring.define(ok / 3f)

        val info = Ui.vbox(c)
        info.addView(Ui.label(c, "status do sistema"))
        val titulo2 = when (ok) { 3 -> "Pronto"; 0 -> "Conecte"; else -> "Quase lá" }
        info.addView((if (ok == 3) Ui.gradTexto(c, titulo2, 26f) else Ui.text(c, titulo2, 26f, Ui.TEXT, true)).also { Ui.umaLinha(it, 14) }, Ui.lp(top = 2, c = c))
        val precisa = Prefs.precisaServico()
        info.addView(Ui.text(c, if (NocryService.rodando) "monitores rodando" else if (precisa) "monitores iniciando" else "nenhum monitor ligado", 10.5f, Ui.MUTED, mono = true), Ui.lp(top = 2, c = c))
        val ip = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ip.leftMargin = Ui.dp(c, 16)
        topo.addView(info, ip)
        hero.addView(topo)

        hero.addView(Ui.chips(c,
            Ui.chip(c, "ADB", if (adb) "LIGADO" else "OFF", adb),
            Ui.chip(c, "PERMISSÃO", if (perm) "OK" else "FALTA", perm),
            Ui.chip(c, "VOLUME", if (acc) "ATIVO" else "OFF", if (acc) true else null)
        ), Ui.lp(top = 16, c = c))
        val falta = !adb || !perm
        hero.addView(Ui.button(c, if (falta) "configurar e conectar" else "conexão", if (falta) Ui.Kind.PRIMARY else Ui.Kind.GHOST, R.drawable.ic_wifi) { act.irAba(1) }, Ui.lp(top = 14, c = c))
        add(hero, 18)

        // ---------------- a tela ficou alterada pelo modo carregador (que saiu do app) ----------------
        if (Prefs.densOrig != null) {
            val aviso = Ui.dica(c, if (voltandoTela) "Voltando a tela ao normal..." else
                "A tela ficou alterada pelo modo carregador, que saiu do app. Toque aqui pra voltar ao normal (precisa do ADB).", Ui.Dica.AVISO)
            Ui.clique(aviso) {
                if (voltandoTela) return@clique
                voltandoTela = true
                redesenha()
                Bg.run({ Engine.desligarModosAntigos() }) { ok ->
                    voltandoTela = false
                    Ui.toast(act, if (ok) "A TELA VOLTOU AO NORMAL" else "CONECTE O ADB E TOQUE DE NOVO")
                    redesenha()
                }
            }
            add(aviso, 12)
        }

        // ---------------- o app teve erro desde a última vez ----------------
        val novos = Erros.novos()
        if (novos > 0) {
            val aviso = Ui.dica(c, "O app registrou $novos erro(s) desde a última vez. Toque pra ver em Logs › Relatório de erros.", Ui.Dica.AVISO)
            Ui.clique(aviso) { act.irAba(3) }
            add(aviso, 12)
        }

        // ---------------- SNIPER (só a key de dono vê) ----------------
        botSub = null
        if (License.isOwner()) {
            val linhaBot = Ui.row(c, "Bot de fila", subBot(), R.drawable.ic_crown) { act.abre(SniperScreen(act)) }
            botSub = linhaBot.findViewWithTag("sub")
            add(linhaBot, 12)
        }

        // ---------------- modos em quadrados ----------------
        val editar = Ui.text(c, "Editar ›", 10.5f, Ui.MUTED, true, mono = true)
        editar.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), 0, Ui.dp(c, 6))
        Ui.clique(editar) { act.abre(OrganizarScreen(act)) }
        add(Ui.secao(c, "modos", editar), 22)

        val lista = Atalhos.visiveis()
        if (lista.isEmpty()) {
            add(Ui.dica(c, "Todos os modos estão escondidos. Toque em EDITAR para mostrar de novo.", Ui.Dica.AVISO), 8)
        }
        var i = 0
        while (i < lista.size) {
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.TOP
            val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            l2.leftMargin = Ui.dp(c, 10)
            linha.addView(tile(c, lista[i]), l1)
            if (i + 1 < lista.size) linha.addView(tile(c, lista[i + 1]), l2)
            else linha.addView(View(c), l2)
            add(linha, 10)
            i += 2
        }

        // ---------------- ultima coisa que o app fez ----------------
        versaoLog = NLog.versao
        val faixa = Ui.hbox(c)
        faixa.background = Ui.ripple(c, Ui.glass(c))
        val pp = Ui.dp(c, 14)
        faixa.setPadding(pp, Ui.dp(c, 12), pp, Ui.dp(c, 12))
        val ic = Ui.icone(c, R.drawable.ic_log, Ui.RED, 18)
        val icp = LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18))
        icp.rightMargin = Ui.dp(c, 12)
        faixa.addView(ic, icp)
        val tc = Ui.vbox(c)
        tc.addView(Ui.label(c, "última ação"))
        val tl = Ui.text(c, textoUltima(), 11.5f, Ui.TEXT, code = true)
        ultimaTxt = tl
        tl.maxLines = 2
        tl.ellipsize = android.text.TextUtils.TruncateAt.END
        tc.addView(tl, Ui.lp(top = 3, c = c))
        faixa.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Ui.clique(faixa) { act.irAba(3) }
        add(faixa, 16)
    }
}

// =====================================================================================
// CONEXAO (passo a passo)
// =====================================================================================
class SetupScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Conexão"
    override val aba = 1
    private var codigoTxt = ""
    private var portaTxt = ""
    private var campoCodigo: EditText? = null
    private var campoPorta: EditText? = null
    private var ocupado = false
    // passos que o usuario reabriu manualmente mesmo ja estando prontos (senao ficam recolhidos,
    // pra nao mostrar passo a passo inteiro de novo pra quem ja fez tudo - fica mais limpo/profissional)
    private val reaberto = HashSet<Int>()

    override fun key(): String = listOf(
        Adb.connected, Sys.hasWriteSecure(), Sys.acessibilidadeLigada(), Pairing.porta, Adb.needsPairing, ocupado, temNotif()
    ).joinToString("|")

    private fun temNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 || act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun abreConfig(vararg acoes: String) {
        for (ac in acoes) {
            try { act.startActivity(Intent(ac)); return } catch (_: Exception) {}
        }
        try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    private fun roda(msg: String, work: () -> String) {
        ocupado = true
        Ui.toast(act, msg)
        redesenha()
        Bg.run({ work() }) { r ->
            ocupado = false
            Ui.toast(act, r)
            redesenha()
        }
    }

    override fun render() {
        campoCodigo?.let { codigoTxt = it.text.toString() }
        campoPorta?.let { portaTxt = it.text.toString() }
        limpa()
        val c = act
        add(Ui.dica(c, "Siga a linha abaixo uma vez, no wi-fi, pra ligar o NOCRY no celular. Passo feito fica verde e recolhe sozinho.", Ui.Dica.INFO), 0)

        val conectado = Adb.connected
        val perm = Sys.hasWriteSecure()
        val acc = Sys.acessibilidadeLigada()
        val notif = temNotif()
        val feitos = listOf(conectado, perm, acc, notif).count { it }

        // ---------------- resumo: anel de progresso + 4 chips ----------------
        val resumo = Ui.card(c, forte = true, ligado = feitos == 4)
        val topo = Ui.hbox(c)
        val tam = Ui.dp(c, 76)
        val rw = FrameLayout(c)
        val ring = RingView(c)
        rw.addView(ring, FrameLayout.LayoutParams(tam, tam))
        val num = Ui.text(c, "$feitos/4", 15f, Ui.TEXT, true, mono = true)
        num.gravity = Gravity.CENTER
        rw.addView(num, FrameLayout.LayoutParams(tam, tam))
        topo.addView(rw, LinearLayout.LayoutParams(tam, tam))
        ring.define(feitos / 4f)
        val info = Ui.vbox(c)
        info.addView(Ui.label(c, "status da conexão"))
        val tit = when (feitos) {
            4 -> "Tudo pronto"
            0 -> "Vamos começar"
            3 -> "Falta 1 passo"
            else -> "Faltam ${4 - feitos} passos"
        }
        info.addView((if (feitos == 4) Ui.gradTexto(c, tit, 20f) else Ui.text(c, tit, 20f, Ui.TEXT, true)).also { Ui.umaLinha(it, 12) }, Ui.lp(top = 3, c = c))
        info.addView(Ui.text(c, if (feitos == 4) "O NOCRY já funciona sozinho." else "Comece pelo passo destacado.", 10.5f, Ui.MUTED), Ui.lp(top = 2, c = c))
        val ip = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ip.leftMargin = Ui.dp(c, 14)
        topo.addView(info, ip)
        resumo.addView(topo)
        resumo.addView(Ui.chips(c,
            Ui.chip(c, "ADB", if (conectado) "OK" else "OFF", conectado),
            Ui.chip(c, "PERMISSÃO", if (perm) "OK" else "FALTA", perm),
            Ui.chip(c, "VOLUME", if (acc) "OK" else "OFF", acc),
            Ui.chip(c, "AVISOS", if (notif) "OK" else "OFF", notif)
        ), Ui.lp(top = 14, c = c))
        add(resumo, 12)

        // ---------------- linha do tempo ----------------
        add(Ui.secao(c, "passo a passo"), 22)
        // 1 a 3 dependem da conexao: se o ADB conectou, a depuracao e o pareamento ja foram feitos
        val feito = booleanArrayOf(conectado, conectado, conectado, perm, acc, notif)
        val atual = feito.indexOfFirst { !it }
        val nomes = listOf("Depuração sem fio", "Parear (só na 1ª vez)", "Conectar", "Liberar o app", "Escuta do volume", "Avisos e bateria")
        for (i in 0 until 6) {
            val estado = if (feito[i]) 0 else if (i == atual) 1 else 2
            // o passo 4 so funciona com o ADB conectado: enquanto nao conecta, fica recolhido como "depois"
            val bloqueado = i == 3 && !conectado
            // os passos 5 e 6 so abrem sozinhos quando chega a vez deles: antes disso ficam recolhidos
            // (toque abre), senao a tela vira uma lista enorme com tudo aberto ao mesmo tempo
            val espera = i >= 4 && i != atual
            val aberto = if (feito[i] || bloqueado || espera) reaberto.contains(i + 1) else true
            val toque: (() -> Unit)? = if (feito[i] || bloqueado || espera) {
                { if (!reaberto.remove(i + 1)) reaberto.add(i + 1); redesenha() }
            } else null
            val conteudo = if (aberto) conteudoPasso(c, i, conectado, perm, acc, notif, estado == 1) else null
            add(Ui.passo(c, i + 1, nomes[i], estado, conteudo, i == 5, toque), if (i == 0) 12 else 0)
        }
    }

    /** o erro cru do ADB (em ingles) vira uma frase que a pessoa entende e sabe o que fazer */
    private fun motivoAdb(e: String): String = when {
        e.contains("Timed out", true) || e.contains("valid host", true) || e.contains("timeout", true) ->
            "O celular não respondeu. Confira se a Depuração sem fio está ligada e se você está no Wi-Fi."
        e.contains("refused", true) -> "O Android recusou a conexão. Desligue e ligue a Depuração sem fio e tente de novo."
        e.contains("parear", true) -> "Precisa parear primeiro (passo 2)."
        e.contains("codigo", true) || e.contains("porta", true) -> "Código ou porta errados. Confira na tela de pareamento e digite de novo."
        else -> "Não conectou: $e"
    }

    /** o que aparece dentro de cada passo aberto; destaque = e o proximo passo a fazer (botao principal em degrade) */
    private fun conteudoPasso(c: android.content.Context, i: Int, conectado: Boolean, perm: Boolean, acc: Boolean, notif: Boolean, destaque: Boolean): View {
        val v = Ui.vbox(c)
        val forte = if (destaque) Ui.Kind.PRIMARY else Ui.Kind.GHOST
        when (i) {
            0 -> {
                v.addView(Ui.lista(c, listOf(
                    "Abra Opções do desenvolvedor",
                    "Ligue Depuração sem fio"
                )))
                v.addView(Ui.dica(c, "Não aparece Opções do desenvolvedor? Vá em Sobre o telefone e toque 7 vezes em Número da versão.", Ui.Dica.AVISO), Ui.lp(top = 12, c = c))
                v.addView(Ui.button(c, "abrir opções do desenvolvedor", forte) {
                    abreConfig(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }, Ui.lp(top = 12, c = c))
            }
            1 -> {
                if (conectado) {
                    v.addView(Ui.status(c, "PAREAMENTO", "FEITO", true))
                } else {
                    v.addView(Ui.lista(c, listOf(
                        "Toque em parear pela notificação",
                        "No Android: Depuração sem fio → Parear com código de pareamento",
                        "Na notificação do NOCRY, toque em Digitar código e escreva os 6 números"
                    )))
                    v.addView(Ui.dica(c, "Deixe a tela de pareamento do Android aberta enquanto digita o código.", Ui.Dica.INFO), Ui.lp(top = 12, c = c))
                    v.addView(Ui.button(c, "parear pela notificação") {
                        if (!temNotif()) { pedeNotif(); Ui.toast(act, "Permita as notificações e toque de novo") }
                        else {
                            Pairing.iniciar(act)
                            Ui.toast(act, "Agora abra: Depuração sem fio → Parear com código")
                            abreConfig(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        }
                    }, Ui.lp(top = 12, c = c))
                    v.addView(Ui.label(c, "ou digite aqui"), Ui.lp(top = 18, c = c))
                    v.addView(Ui.text(c, "Porta e código que aparecem na tela de pareamento.", 10.5f, Ui.MUTED), Ui.lp(top = 4, c = c))
                    val fp = Ui.field(c, "porta (ex.: 37155)", true)
                    fp.setText(if (portaTxt.isNotEmpty()) portaTxt else if (Pairing.porta > 0) Pairing.porta.toString() else "")
                    campoPorta = fp
                    v.addView(fp, Ui.lp(top = 8, c = c))
                    val fc = Ui.field(c, "código de 6 números", true)
                    fc.setText(codigoTxt)
                    campoCodigo = fc
                    v.addView(fc, Ui.lp(top = 6, c = c))
                    v.addView(Ui.button(c, if (ocupado) "aguarde..." else "parear agora", Ui.Kind.GHOST) {
                        if (ocupado) return@button
                        val porta = fp.text.toString().trim().toIntOrNull() ?: Pairing.porta
                        val cod = fc.text.toString().trim()
                        if (porta <= 0 || cod.length != 6) Ui.toast(act, "Preencha a porta e o código de 6 números")
                        else roda("Pareando...") {
                            val ok = Adb.pair(act.applicationContext, porta, cod)
                            if (ok) {
                                Adb.connect(act.applicationContext, 8000)
                                Sys.darPermissoes()
                                Sys.ligarAcessibilidade()
                                "PAREADO!"
                            } else "Não pareou: " + Adb.lastError
                        }
                    }, Ui.lp(top = 8, c = c))
                }
            }
            2 -> {
                v.addView(Ui.status(c, "ADB", if (conectado) "CONECTADO" else "DESCONECTADO", conectado))
                if (!conectado && Adb.lastError.isNotEmpty()) v.addView(Ui.dica(c, motivoAdb(Adb.lastError), Ui.Dica.AVISO), Ui.lp(top = 8, c = c))
                v.addView(Ui.button(c, if (conectado) "reconectar" else "conectar agora", if (conectado) Ui.Kind.GHOST else forte) {
                    if (!ocupado) roda("Conectando...") {
                        Adb.disconnect()
                        if (Adb.connect(act.applicationContext, 9000)) "CONECTADO" else "Não conectou: " + Adb.lastError
                    }
                }, Ui.lp(top = 12, c = c))
                v.addView(Ui.dica(c, "Trocou de Wi-Fi ou reiniciou o celular? Ligue a depuração sem fio de novo e toque aqui. Não precisa parear de novo.", Ui.Dica.INFO), Ui.lp(top = 12, c = c))
            }
            3 -> {
                v.addView(Ui.text(c, "O app se dá a permissão de mudar o toque e se tira da economia de bateria.", 10.5f, Ui.TEXT))
                v.addView(Ui.dica(c, "Depois disso o modo competitivo funciona até sem ADB.", Ui.Dica.OK), Ui.lp(top = 12, c = c))
                v.addView(Ui.button(c, if (perm) "liberar de novo" else "liberar permissões", if (perm) Ui.Kind.GHOST else forte) {
                    if (!Adb.connected) Ui.toast(act, "Conecte o ADB primeiro (passos 2 e 3)")
                    else if (!ocupado) roda("Liberando...") { if (Sys.darPermissoes()) "LIBERADO" else "O celular não aceitou" }
                }, Ui.lp(top = 12, c = c))
            }
            4 -> {
                v.addView(Ui.text(c, "É assim que o app percebe VOL+ e VOL−.", 10.5f, Ui.TEXT))
                v.addView(Ui.dica(c, "O NOCRY só olha o aperto das teclas de volume e não lê sua tela.", Ui.Dica.OK), Ui.lp(top = 12, c = c))
                v.addView(Ui.button(c, if (acc) "desligar" else "ligar", if (acc) Ui.Kind.GHOST else forte) {
                    if (acc) { Sys.desligarAcessibilidade(); redesenha() }
                    else if (Sys.ligarAcessibilidade()) { Ui.toast(act, "LIGADA"); redesenha() }
                    else {
                        Ui.toast(act, "Ligue o NOCRY na lista de acessibilidade")
                        abreConfig(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                }, Ui.lp(top = 12, c = c))
                v.addView(Ui.label(c, "se o android bloquear"), Ui.lp(top = 18, c = c))
                v.addView(Ui.lista(c, listOf(
                    "Abra Informações do app",
                    "Toque em ⋮ (canto de cima)",
                    "Toque em Permitir configurações restritas"
                )), Ui.lp(top = 8, c = c))
                v.addView(Ui.dica(c, "Depois do passo 4 o app liga a escuta sozinho.", Ui.Dica.INFO), Ui.lp(top = 12, c = c))
                v.addView(Ui.button(c, "informações do app", Ui.Kind.GHOST) {
                    try {
                        act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + Const.PKG)))
                    } catch (_: Exception) {}
                }, Ui.lp(top = 12, c = c))
            }
            else -> {
                if (!notif) {
                    v.addView(Ui.text(c, "Sem notificações o NOCRY não mostra o campo do código no pareamento nem avisa quando sai versão nova.", 10.5f, Ui.TEXT))
                    v.addView(Ui.button(c, "permitir notificações", forte) { pedeNotif() }, Ui.lp(top = 12, c = c))
                }
                v.addView(Ui.label(c, "economia de bateria"), Ui.lp(top = if (notif) 0 else 18, c = c))
                v.addView(Ui.lista(c, listOf(
                    "Toque no botão abaixo",
                    "Escolha Todos os apps",
                    "Ache o NOCRY e marque Sem restrição"
                )), Ui.lp(top = 8, c = c))
                v.addView(Ui.dica(c, "Sem isso o Android pode fechar os monitores em segundo plano.", Ui.Dica.AVISO), Ui.lp(top = 12, c = c))
                v.addView(Ui.button(c, "tirar da economia de bateria", Ui.Kind.GHOST) {
                    abreConfig(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }, Ui.lp(top = 12, c = c))
            }
        }
        return v
    }

    private fun pedeNotif() {
        if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
    }
}

// =====================================================================================
// MODO COMPETITIVO (toque rapido simples)
// =====================================================================================
class CompScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Modo competitivo"
    private var ocupado = false

    override fun key(): String = listOf(Comp.ligado(), Sys.getSecure("long_press_timeout"), Sys.getSecure("multi_press_timeout"), Toque.podeMexer(), ocupado).joinToString("|")

    private fun roda(work: () -> Boolean, ok: String, erro: String) {
        ocupado = true
        redesenha()
        Bg.run(work) { r ->
            ocupado = false
            Ui.toast(act, if (r) ok else erro)
            redesenha()
        }
    }

    override fun render() {
        limpa()
        val c = act
        cabecalho("Modo competitivo", "Toque rápido (long ${Prefs.longOn} · multi ${Prefs.multiOn}), sem mexer na tela.")
        val on = Comp.ligado()
        val hero = Ui.hero(c, R.drawable.ic_gamepad, "modo competitivo", if (on) "Ativo" else "Desativado", on,
            if (on) "O toque rápido está ligado no aparelho." else "O aparelho está com o toque normal.")
        hero.addView(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_sliders, "long press", Sys.getSecure("long_press_timeout") ?: "padrão"),
            Ui.infoBox(c, R.drawable.ic_sliders, "multi press", Sys.getSecure("multi_press_timeout") ?: "padrão")
        ), Ui.lp(top = 16, c = c))
        add(hero, 12)
        if (!Toque.podeMexer()) add(Ui.dica(c, "Sem permissão e sem ADB. Faça a Conexão primeiro.", Ui.Dica.AVISO), 12)
        if (on) {
            add(Ui.button(c, if (ocupado) "aguarde..." else "desativar modo competitivo", Ui.Kind.DANGER) {
                if (!ocupado) roda({ Comp.desativar(true) }, "DESATIVADO — tudo voltou ao normal", "O aparelho não aceitou a mudança")
            }, 14)
            add(Ui.button(c, "aplicar de novo", Ui.Kind.GHOST) {
                if (!ocupado) roda({ Comp.ativar() }, "MODO COMPETITIVO ATIVO", "O aparelho não aceitou a mudança")
            }, 10)
        } else {
            add(Ui.button(c, if (ocupado) "aguarde..." else "ativar modo competitivo") {
                if (!ocupado) roda({ Comp.ativar() }, "MODO COMPETITIVO ATIVO", "O aparelho não aceitou a mudança")
            }, 14)
        }
        add(Ui.grupo(c, "mais opções", listOf(
            Ui.item(c, "Ajustes do toque", "long ${Prefs.longOn} · multi ${Prefs.multiOn} (o que o modo competitivo usa)", R.drawable.ic_sliders) { act.abre(AjustesScreen(act)) },
            Ui.item(c, "Testar agora", "liga e volta ao normal, mostrando o que o aparelho leu", R.drawable.ic_check) { act.abre(TestarScreen(act)) }
        )), 20)
    }
}

// =====================================================================================
// AJUSTES DO TOQUE (valores 1-600 e quantos apertos)
// =====================================================================================
class AjustesScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Ajustes do toque"

    override fun key(): String = listOf(Prefs.longOn, Prefs.multiOn, Prefs.normal).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        cabecalho("Ajustes do toque", "Valores em milissegundos (0 a 600). Valem no modo competitivo, na próxima vez que ligar.")
        add(Ui.chips(c,
            Ui.infoBox(c, R.drawable.ic_bolt, "rápido", "${Prefs.longOn} · ${Prefs.multiOn}", Ui.RED),
            Ui.infoBox(c, R.drawable.ic_sliders, "normal", Prefs.normal.toString())
        ), 12)
        add(Ui.grupo(c, "toque ligado (rápido)", listOf(
            Ui.item(c, "Long press", "1 a 600 ms", R.drawable.ic_sliders, Prefs.longOn.toString()) {
                Ui.dialogNumero(act, "LONG PRESS LIGADO", "1 a 600", 1, 600, Prefs.longOn) { v -> Prefs.longOn = v; redesenha() }
            },
            Ui.item(c, "Multi press", "0 a 600 ms", R.drawable.ic_sliders, Prefs.multiOn.toString()) {
                Ui.dialogNumero(act, "MULTI PRESS LIGADO", "0 a 600", 0, 600, Prefs.multiOn) { v -> Prefs.multiOn = v; redesenha() }
            },
            Ui.item(c, "Igualar os dois", "multi press = long press (${Prefs.longOn})", R.drawable.ic_check) {
                Prefs.multiOn = Prefs.longOn; redesenha()
            }
        )), 20)
        add(Ui.grupo(c, "toque normal", listOf(
            Ui.item(c, "Valor normal", "usado no teste quando volta ao normal", R.drawable.ic_sliders, Prefs.normal.toString()) {
                Ui.dialogNumero(act, "VALOR NORMAL", "1 a 600", 1, 600, Prefs.normal) { v -> Prefs.normal = v; redesenha() }
            }
        )), 20)
        add(Ui.button(c, "voltar ao padrão (1 · 0 · 100)", Ui.Kind.GHOST) {
            Prefs.longOn = 1; Prefs.multiOn = 0; Prefs.normal = 100; redesenha()
        }, 20)
    }
}

// =====================================================================================
// TESTAR AGORA
// =====================================================================================
class TestarScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Testar agora"
    private class Resultado(val status: String, val ok: Boolean?, val pedido: String, val lido: String, val perm: Boolean, val adb: Boolean)
    private var res: Resultado? = null
    private var ocupado = false

    override fun key(): String = listOf(res?.status, res?.lido, ocupado).joinToString("|")

    private fun testa(modo: String) {
        if (ocupado) return
        ocupado = true
        redesenha()
        Bg.run({
            val rc = Toque.aplica(modo, null)
            val st = when (rc) { 0 -> "Mudou"; 2 -> "Já estava assim"; else -> "O aparelho não aceitou" }
            Resultado(st, when (rc) { 0 -> true; 2 -> null; else -> false },
                if (modo == "rapido") Toque.txtLigado() else "normal " + Toque.txtNormal(),
                Toque.lido(), Sys.hasWriteSecure(), Adb.connected)
        }) { r ->
            res = r
            ocupado = false
            redesenha()
        }
    }

    override fun render() {
        limpa()
        val c = act
        cabecalho("Testar agora", "Mexe no toque de verdade e mostra o que o aparelho leu de volta.")
        val r = res
        val hero = Ui.hero(c, R.drawable.ic_check, "resultado",
            if (ocupado) "Testando..." else r?.status ?: "Nada testado ainda", if (ocupado) null else r?.ok,
            if (r == null) "Toque num botão abaixo pra testar." else "Pedido: ${r.pedido}")
        if (r != null) {
            hero.addView(Ui.chips(c,
                Ui.chip(c, "PERMISSÃO", if (r.perm) "SIM" else "NÃO", r.perm),
                Ui.chip(c, "ADB", if (r.adb) "OK" else "OFF", r.adb)
            ), Ui.lp(top = 16, c = c))
            hero.addView(Ui.infoBox(c, R.drawable.ic_eye, "o aparelho leu", r.lido), Ui.lp(top = 8, c = c))
        }
        add(hero, 12)
        add(Ui.button(c, "testar: ligar o toque rápido") { testa("rapido") }, 16)
        add(Ui.button(c, "testar: voltar ao normal", Ui.Kind.GHOST) { testa("normal") }, 10)
    }
}
