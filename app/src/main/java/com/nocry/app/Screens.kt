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

// =====================================================================================
// INICIO
// =====================================================================================
class HomeScreen(a: MainActivity) : Screen(a) {
    override val titulo = "NOCRY"
    override val semBarra = true
    override val aba = 0

    private var ringView: RingView? = null

    override fun key(): String = listOf(
        Adb.connected, Sys.hasWriteSecure(), Sys.acessibilidadeLigada(), NocryService.rodando,
        Prefs.volAtivo, Prefs.cargaAtivo, Prefs.mcAtivo, Prefs.otmAuto, Prefs.cargaRep, Prefs.mcCycles,
        Comp.ligado(), Otm.status().first, Prefs.densOrig, ultimaLinha(), License.role()
    ).joinToString("|")

    private fun ultimaLinha(): String = NLog.text(null).substringAfterLast('\n')

    private fun estComp(): String = if (Comp.ligado()) "ATIVO" else "desativado"
    private fun estVol(): String = if (Prefs.volAtivo) "VOL+ ${Prefs.clicksUp}x · VOL− ${Prefs.clicksDown}x" else "parado"
    private fun estCarga(): String = if (Prefs.cargaAtivo) "LIGADO · ${Prefs.cargaRep} restante(s)" else "parado"
    private fun estMc(): String = when {
        Prefs.mcAtivo -> "LIGADO · density ${Prefs.mcDens}"
        Prefs.densOrig != null -> "TELA ALTERADA"
        else -> "parado"
    }
    private fun estOtm(): String {
        val st = Otm.status()
        return if (st.first) "ATIVA · ${st.second} apps" + (if (Prefs.otmAuto) " · AUTO" else "") else if (Prefs.otmAuto) "AUTO LIGADO" else "desativada"
    }

    override fun render() {
        limpa()
        val c = act

        // cabecalho: nome + selo do cargo
        val cab = Ui.hbox(c)
        val col = Ui.vbox(c)
        val t = Ui.text(c, "NOCRY", 30f, Ui.RED, true, mono = true)
        t.letterSpacing = 0.2f
        col.addView(t)
        col.addView(Ui.text(c, "COMPETITIVO · TOQUE · OTIMIZAÇÃO", 10f, Ui.MUTED, mono = true))
        cab.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val papel = License.roleNome()
        if (papel.isNotEmpty()) cab.addView(Ui.selo(c, papel, if (License.isAdmin()) Ui.RED else Ui.MUTED, License.isAdmin()))
        add(cab, 0)

        // estado geral: anel + 3 passos
        val adb = Adb.connected
        val perm = Sys.hasWriteSecure()
        val acc = Sys.acessibilidadeLigada()
        val ok = (if (adb) 1 else 0) + (if (perm) 1 else 0) + (if (acc) 1 else 0)

        val hero = Ui.card(c, forte = true)
        val topo = Ui.hbox(c)
        val ring = RingView(c)
        ringView = ring
        val rw = FrameLayout(c)
        rw.addView(ring, FrameLayout.LayoutParams(Ui.dp(c, 92), Ui.dp(c, 92)))
        val num = Ui.text(c, "$ok/3", 20f, Ui.TEXT, true, mono = true)
        num.gravity = Gravity.CENTER
        rw.addView(num, FrameLayout.LayoutParams(Ui.dp(c, 92), Ui.dp(c, 92)))
        topo.addView(rw, LinearLayout.LayoutParams(Ui.dp(c, 92), Ui.dp(c, 92)))
        ring.define(ok / 3f, if (ok == 3) Ui.OK else Ui.BAD)

        val info = Ui.vbox(c)
        info.addView(Ui.label(c, "status"))
        val titulo2 = when (ok) { 3 -> "PRONTO"; 0 -> "CONECTE"; else -> "QUASE LÁ" }
        info.addView(Ui.text(c, titulo2, 24f, if (ok == 3) Ui.OK else Ui.TEXT, true), Ui.lp(top = 2, c = c))
        val precisa = Prefs.precisaServico()
        info.addView(Ui.text(c, if (NocryService.rodando) "monitores rodando" else if (precisa) "monitores iniciando" else "nenhum monitor ligado", 12f, Ui.MUTED, mono = true), Ui.lp(top = 2, c = c))
        val ip = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ip.leftMargin = Ui.dp(c, 16)
        topo.addView(info, ip)
        hero.addView(topo)

        hero.addView(Ui.status(c, "ADB (depuração sem fio)", if (adb) "CONECTADO" else "DESCONECTADO", adb), Ui.lp(top = 12, c = c))
        hero.addView(Ui.status(c, "PERMISSÃO DO APP", if (perm) "LIBERADA" else "FALTA", perm))
        hero.addView(Ui.status(c, "ESCUTA DO VOLUME", if (acc) "ACESSIBILIDADE" else "DESLIGADA", if (acc) true else null))
        val falta = !adb || !perm
        hero.addView(Ui.button(c, if (falta) "configurar e conectar" else "conexão", if (falta) Ui.Kind.PRIMARY else Ui.Kind.GHOST, R.drawable.ic_wifi) { act.irAba(1) }, Ui.lp(top = 12, c = c))
        add(hero, 16)

        // funcoes em quadrados
        add(Ui.label(c, "funções"), 20)
        val itens = listOf<View>(
            Ui.tile(c, R.drawable.ic_gamepad, "Modo competitivo", estComp(), Comp.ligado()) { act.abre(CompScreen(act)) },
            Ui.tile(c, R.drawable.ic_volume, "Toque via volume", estVol(), Prefs.volAtivo) { act.abre(VolScreen(act)) },
            Ui.tile(c, R.drawable.ic_bolt, "Toque via carregador", estCarga(), Prefs.cargaAtivo) { act.abre(CargaScreen(act)) },
            Ui.tile(c, R.drawable.ic_plug, "Modo carregador", estMc(), Prefs.mcAtivo) { act.abre(McScreen(act)) },
            Ui.tile(c, R.drawable.ic_chip, "Otimização", estOtm(), Otm.status().first || Prefs.otmAuto) { act.abre(OtmScreen(act)) },
            Ui.tile(c, R.drawable.ic_sliders, "Ajustes do toque", "long ${Prefs.longOn} · multi ${Prefs.multiOn}", null) { act.abre(AjustesScreen(act)) }
        )
        var i = 0
        while (i < itens.size) {
            val linha = Ui.hbox(c)
            linha.gravity = Gravity.TOP
            val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            l2.leftMargin = Ui.dp(c, 10)
            linha.addView(itens[i], l1)
            if (i + 1 < itens.size) linha.addView(itens[i + 1], l2)
            add(linha, if (i == 0) 8 else 10)
            i += 2
        }

        // ultima coisa que o app fez
        val ult = ultimaLinha()
        val faixa = Ui.hbox(c)
        faixa.background = Ui.glass(c)
        val pp = Ui.dp(c, 14)
        faixa.setPadding(pp, Ui.dp(c, 12), pp, Ui.dp(c, 12))
        val ic = Ui.icone(c, R.drawable.ic_log, Ui.RED, 18)
        val icp = LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18))
        icp.rightMargin = Ui.dp(c, 12)
        faixa.addView(ic, icp)
        val tc = Ui.vbox(c)
        tc.addView(Ui.label(c, "última ação"))
        val txt = if (ult.isBlank() || ult.startsWith("(sem")) "nada ainda" else ult
        val tl = Ui.text(c, txt, 11.5f, Ui.TEXT, mono = true)
        tl.maxLines = 2
        tl.ellipsize = android.text.TextUtils.TruncateAt.END
        tc.addView(tl, Ui.lp(top = 3, c = c))
        faixa.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        faixa.isClickable = true
        faixa.setOnClickListener { act.irAba(3) }
        add(faixa, 14)
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
        add(Ui.text(c, "Sem Termux: o NOCRY conversa com a depuração sem fio do próprio celular. Faça os passos abaixo UMA vez (precisa estar no Wi-Fi).", 13f, Ui.MUTED), 0)

        // 1
        val c1 = Ui.card(c)
        c1.addView(Ui.label(c, "1 · depuração sem fio"))
        c1.addView(Ui.text(c, "Ligue em Opções do desenvolvedor → Depuração sem fio. (Se não aparece Opções do desenvolvedor: Sobre o telefone → toque 7 vezes em Número da versão.)", 13f), Ui.lp(top = 6, c = c))
        c1.addView(Ui.button(c, "abrir opções do desenvolvedor", Ui.Kind.GHOST) {
            abreConfig(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        }, Ui.lp(top = 10, c = c))
        add(c1, 14)

        // 2
        val c2 = Ui.card(c)
        val conectado = Adb.connected
        c2.addView(Ui.label(c, "2 · parear (só na primeira vez)"))
        if (conectado) {
            c2.addView(Ui.status(c, "PAREAMENTO", "FEITO", true))
        } else {
            c2.addView(Ui.text(c, "Toque no botão, depois vá em Depuração sem fio → Parear com código de pareamento. Quando a notificação do NOCRY aparecer, toque em DIGITAR CÓDIGO e escreva os 6 números (a tela de pareamento do Android fica aberta).", 13f), Ui.lp(top = 6, c = c))
            c2.addView(Ui.button(c, "parear pela notificação") {
                if (!temNotif()) { pedeNotif(); Ui.toast(act, "Permita as notificações e toque de novo") }
                else {
                    Pairing.iniciar(act)
                    Ui.toast(act, "Agora abra: Depuração sem fio → Parear com código")
                    abreConfig(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
            }, Ui.lp(top = 10, c = c))
            c2.addView(Ui.text(c, "Ou digite aqui (porta e código da tela de pareamento):", 12f, Ui.MUTED), Ui.lp(top = 12, c = c))
            val fp = Ui.field(c, "porta (ex.: 37155)", true)
            fp.setText(if (portaTxt.isNotEmpty()) portaTxt else if (Pairing.porta > 0) Pairing.porta.toString() else "")
            campoPorta = fp
            c2.addView(fp, Ui.lp(top = 6, c = c))
            val fc = Ui.field(c, "código de 6 números", true)
            fc.setText(codigoTxt)
            campoCodigo = fc
            c2.addView(fc, Ui.lp(top = 6, c = c))
            c2.addView(Ui.button(c, if (ocupado) "aguarde..." else "parear agora", Ui.Kind.GHOST) {
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
        add(c2, 12)

        // 3
        val c3 = Ui.card(c)
        c3.addView(Ui.label(c, "3 · conectar"))
        c3.addView(Ui.status(c, "ADB", if (conectado) "CONECTADO" else "DESCONECTADO", conectado), Ui.lp(top = 4, c = c))
        if (!conectado && Adb.lastError.isNotEmpty()) c3.addView(Ui.text(c, "Motivo: " + Adb.lastError, 12f, Ui.WARN))
        c3.addView(Ui.button(c, if (conectado) "reconectar" else "conectar agora", Ui.Kind.GHOST) {
            if (!ocupado) roda("Conectando...") {
                Adb.disconnect()
                if (Adb.connect(act.applicationContext, 9000)) "CONECTADO" else "Não conectou: " + Adb.lastError
            }
        }, Ui.lp(top = 8, c = c))
        c3.addView(Ui.text(c, "Se a depuração sem fio desligar (trocou de Wi-Fi ou reiniciou o celular), ligue de novo e toque aqui. Não precisa parear de novo.", 12f, Ui.MUTED), Ui.lp(top = 8, c = c))
        add(c3, 12)

        // 4
        val c4 = Ui.card(c)
        val perm = Sys.hasWriteSecure()
        c4.addView(Ui.label(c, "4 · liberar o app"))
        c4.addView(Ui.status(c, "PERMISSÃO DE AJUSTES", if (perm) "LIBERADA" else "FALTA", perm), Ui.lp(top = 4, c = c))
        c4.addView(Ui.text(c, "O app se dá a permissão de mudar o toque e se tira da economia de bateria. Depois disso o toque via volume e via carregador funciona até sem ADB.", 12f, Ui.MUTED))
        c4.addView(Ui.button(c, if (perm) "liberar de novo" else "liberar permissões", Ui.Kind.GHOST) {
            if (!Adb.connected) Ui.toast(act, "Conecte o ADB primeiro (passos 2 e 3)")
            else if (!ocupado) roda("Liberando...") { if (Sys.darPermissoes()) "LIBERADO" else "O celular não aceitou" }
        }, Ui.lp(top = 8, c = c))
        add(c4, 12)

        // 5
        val c5 = Ui.card(c)
        val acc = Sys.acessibilidadeLigada()
        c5.addView(Ui.label(c, "5 · escuta do volume"))
        c5.addView(Ui.status(c, "ACESSIBILIDADE", if (acc) "LIGADA" else "DESLIGADA", acc), Ui.lp(top = 4, c = c))
        c5.addView(Ui.text(c, "É assim que o app percebe VOL+ e VOL−. O NOCRY só olha o aperto das teclas de volume e não lê sua tela.", 12f, Ui.MUTED))
        c5.addView(Ui.button(c, if (acc) "desligar" else "ligar", Ui.Kind.GHOST) {
            if (acc) { Sys.desligarAcessibilidade(); redesenha() }
            else if (Sys.ligarAcessibilidade()) { Ui.toast(act, "LIGADA"); redesenha() }
            else {
                Ui.toast(act, "Ligue o NOCRY na lista de acessibilidade")
                abreConfig(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }, Ui.lp(top = 8, c = c))
        c5.addView(Ui.text(c, "Se o Android bloquear (\"configuração restrita\"): Informações do app → ⋮ → Permitir configurações restritas. Depois do passo 4 o app liga sozinho.", 12f, Ui.MUTED), Ui.lp(top = 8, c = c))
        c5.addView(Ui.button(c, "informações do app", Ui.Kind.GHOST) {
            try {
                act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + Const.PKG)))
            } catch (_: Exception) {}
        }, Ui.lp(top = 8, c = c))
        add(c5, 12)

        // 6
        val c6 = Ui.card(c)
        c6.addView(Ui.label(c, "6 · avisos e bateria"))
        c6.addView(Ui.status(c, "NOTIFICAÇÕES", if (temNotif()) "PERMITIDAS" else "BLOQUEADAS", temNotif()), Ui.lp(top = 4, c = c))
        if (!temNotif()) c6.addView(Ui.button(c, "permitir notificações", Ui.Kind.GHOST) { pedeNotif() }, Ui.lp(top = 8, c = c))
        c6.addView(Ui.button(c, "tirar da economia de bateria", Ui.Kind.GHOST) {
            abreConfig(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }, Ui.lp(top = 8, c = c))
        c6.addView(Ui.text(c, "Escolha \"Todos os apps\", ache o NOCRY e marque \"Sem restrição\". Sem isso o Android pode fechar os monitores.", 12f, Ui.MUTED), Ui.lp(top = 8, c = c))
        add(c6, 12)
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
        val on = Comp.ligado()
        val card = Ui.card(c)
        card.addView(Ui.label(c, "status"))
        card.addView(Ui.text(c, if (on) "ATIVO" else "DESATIVADO", 26f, if (on) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        card.addView(Ui.text(c, "long_press_timeout = ${Sys.getSecure("long_press_timeout") ?: "padrão"}\nmulti_press_timeout = ${Sys.getSecure("multi_press_timeout") ?: "padrão"}", 13f, Ui.MUTED, mono = true), Ui.lp(top = 8, c = c))
        add(card, 0)
        add(Ui.text(c, "ATIVO: toque rápido (long = 1 · multi = 0).\nINATIVO: volta ao que o aparelho tinha antes.", 13f, Ui.MUTED), 12)
        if (!Toque.podeMexer()) add(Ui.text(c, "Sem permissão e sem ADB. Faça a CONEXÃO primeiro.", 13f, Ui.WARN, true), 10)
        add(Ui.button(c, if (ocupado) "aguarde..." else "ativar modo competitivo") { if (!ocupado) roda({ Comp.ativar() }, "MODO COMPETITIVO ATIVO", "O aparelho não aceitou a mudança") }, 14)
        add(Ui.button(c, "desativar modo competitivo", Ui.Kind.DANGER) { if (!ocupado) roda({ Comp.desativar(true) }, "DESATIVADO — tudo voltou ao normal", "O aparelho não aceitou a mudança") }, 10)
        add(Ui.text(c, "DESATIVAR também devolve a tela, se o MODO CARREGADOR tiver deixado a density alterada.", 12f, Ui.MUTED), 10)
    }
}

// =====================================================================================
// TOQUE VIA VOLUME
// =====================================================================================
class VolScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Toque via volume"
    private var ocupado = false

    override fun key(): String = listOf(Prefs.volAtivo, Prefs.markVol, KeyService.ativo, Adb.connected, Prefs.clicksUp, Prefs.clicksDown, Prefs.longOn, Prefs.multiOn, Prefs.normal, ocupado).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        val on = Prefs.volAtivo
        val card = Ui.card(c)
        card.addView(Ui.label(c, "monitor"))
        card.addView(Ui.text(c, if (on) "LIGADO" else "PARADO", 26f, if (on) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        card.addView(Ui.text(c, "VOL+ ${Prefs.clicksUp}x rápido → LIGADO (long ${Prefs.longOn} · multi ${Prefs.multiOn})\nVOL− ${Prefs.clicksDown}x rápido → NORMAL (${Prefs.normal})", 13f, Ui.TEXT, mono = true), Ui.lp(top = 8, c = c))
        val escuta = if (KeyService.ativo) "acessibilidade" else if (Adb.connected) "ADB (getevent)" else "NENHUMA — faça a CONEXÃO"
        card.addView(Ui.status(c, "ESCUTA", escuta, KeyService.ativo || Adb.connected), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "TOQUE AGORA", if (Prefs.markVol) "LIGADO POR AQUI" else "NORMAL", null))
        add(card, 0)
        add(Ui.text(c, "Só mexe em long press e multi press. Sem density, sem abrir app. Aperte o mesmo botão de volume rápido (até 0,9 s entre os apertos).", 12f, Ui.MUTED), 10)

        if (on) add(Ui.button(c, if (ocupado) "aguarde..." else "parar monitor", Ui.Kind.DANGER) {
            if (!ocupado) {
                ocupado = true; redesenha()
                Bg.run({ Engine.pararVol() }) { rc ->
                    ocupado = false
                    Ui.toast(act, if (rc == 1) "Parou, mas o aparelho não aceitou voltar ao normal" else "MONITOR PARADO")
                    redesenha()
                }
            }
        }, 14)
        else add(Ui.button(c, "ligar monitor") {
            if (!KeyService.ativo && !Adb.connected) Ui.toast(act, "Sem escuta: ligue a acessibilidade ou conecte o ADB (CONEXÃO)")
            else if (!Toque.podeMexer()) Ui.toast(act, "Sem permissão para mudar o toque (CONEXÃO)")
            else { Engine.iniciarVol(); Ui.toast(act, "MONITOR DE VOLUME LIGADO"); redesenha() }
        }, 14)

        add(Ui.row(c, "Ajustes do toque", "valores 1–600 e quantos apertos") { act.abre(AjustesScreen(act)) }, 14)
        add(Ui.row(c, "Testar agora", "liga e volta ao normal, mostrando o que o aparelho leu") { act.abre(TestarScreen(act)) })
        add(Ui.row(c, "Ver log", "o que o monitor percebeu e fez") { act.abre(LogScreen(act, "volume")) })
    }
}

// =====================================================================================
// TOQUE VIA CARREGADOR
// =====================================================================================
class CargaScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Toque via carregador"
    private var ocupado = false
    private var gatilho = Prefs.cargaGatilho
    private var rep = Prefs.cargaRepTotal

    override fun key(): String = listOf(Prefs.cargaAtivo, Prefs.markCarga, Prefs.cargaRep, Power.plugged(act), Prefs.longOn, Prefs.multiOn, Prefs.normal, gatilho, rep, ocupado).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        val on = Prefs.cargaAtivo
        val card = Ui.card(c)
        card.addView(Ui.label(c, "monitor"))
        card.addView(Ui.text(c, if (on) "LIGADO" else "PARADO", 26f, if (on) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        val g = if (on) Prefs.cargaGatilho else gatilho
        val txt = if (g == "tirou") "TIROU o carregador → LIGADO (long ${Prefs.longOn} · multi ${Prefs.multiOn})\nCOLOCOU o carregador → NORMAL (${Prefs.normal})"
        else "COLOCOU o carregador → LIGADO (long ${Prefs.longOn} · multi ${Prefs.multiOn})\nTIROU o carregador → NORMAL (${Prefs.normal})"
        card.addView(Ui.text(c, txt, 13f, Ui.TEXT, mono = true), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "CARREGADOR AGORA", if (Power.plugged(c)) "LIGADO" else "DESLIGADO", null), Ui.lp(top = 8, c = c))
        if (on) card.addView(Ui.status(c, "REPETIÇÕES RESTANTES", Prefs.cargaRep.toString(), null))
        card.addView(Ui.status(c, "TOQUE AGORA", if (Prefs.markCarga) "LIGADO POR AQUI" else "NORMAL", null))
        add(card, 0)
        add(Ui.text(c, "Só reage quando o carregador MUDA. Cada repetição = liga e volta ao normal; ao acabar, o monitor para sozinho.", 12f, Ui.MUTED), 10)

        if (on) {
            add(Ui.button(c, if (ocupado) "aguarde..." else "parar monitor", Ui.Kind.DANGER) {
                if (!ocupado) {
                    ocupado = true; redesenha()
                    Bg.run({ Engine.pararCarga() }) { rc ->
                        ocupado = false
                        Ui.toast(act, if (rc == 1) "Parou, mas o aparelho não aceitou voltar ao normal" else "MONITOR PARADO")
                        redesenha()
                    }
                }
            }, 14)
        } else {
            add(Ui.label(c, "quando ligar o toque?"), 14)
            add(Ui.segmento(c, listOf("tirou o carregador", "colocou o carregador"), if (gatilho == "tirou") 0 else 1) { i ->
                gatilho = if (i == 0) "tirou" else "colocou"; redesenha()
            }, 8)
            add(Ui.label(c, "quantas repetições?"), 14)
            add(Ui.segmento(c, listOf("1 vez", "2 vezes", "3 vezes"), rep - 1) { i -> rep = i + 1; redesenha() }, 8)
            add(Ui.button(c, "ligar monitor") {
                if (!Toque.podeMexer()) Ui.toast(act, "Sem permissão para mudar o toque (CONEXÃO)")
                else { Engine.iniciarCarga(gatilho, rep); Ui.toast(act, "MONITOR LIGADO — agora troque o carregador"); redesenha() }
            }, 14)
        }
        add(Ui.row(c, "Ajustes do toque", "valores 1–600") { act.abre(AjustesScreen(act)) }, 14)
        add(Ui.row(c, "Testar agora", "liga e volta ao normal, mostrando o que o aparelho leu") { act.abre(TestarScreen(act)) })
        add(Ui.row(c, "Ver log", "o que o monitor percebeu e fez") { act.abre(LogScreen(act, "toque-carga")) })
    }
}

// =====================================================================================
// MODO CARREGADOR (density + abre o FF)
// =====================================================================================
class McScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Modo carregador"
    private var ocupado = false
    private var ff = Prefs.mcFf
    private var dens = Prefs.mcDens
    private var rep = Prefs.mcCyclesTotal

    override fun key(): String = listOf(Prefs.mcAtivo, Prefs.mcCycles, Prefs.densOrig, Adb.connected, Power.plugged(act), ff, dens, rep, ocupado).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        val on = Prefs.mcAtivo
        val card = Ui.card(c)
        card.addView(Ui.label(c, "monitor"))
        card.addView(Ui.text(c, if (on) "LIGADO" else "PARADO", 26f, if (on) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        val nome = if ((if (on) Prefs.mcFf else ff) == "normal") "FF NORMAL" else "FF MAX"
        card.addView(Ui.text(c, "LIGOU o carregador → density ${if (on) Prefs.mcDens else dens} + abre o $nome\nTIROU o carregador → a density volta ao que era", 13f, Ui.TEXT, mono = true), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "ADB", if (Adb.connected) "CONECTADO" else "DESCONECTADO", Adb.connected), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "CARREGADOR AGORA", if (Power.plugged(c)) "LIGADO" else "DESLIGADO", null))
        if (on) card.addView(Ui.status(c, "REPETIÇÕES RESTANTES", Prefs.mcCycles.toString(), null))
        card.addView(Ui.status(c, "TELA", if (Prefs.densOrig != null) "DENSITY ALTERADA" else "NORMAL", if (Prefs.densOrig != null) false else true))
        add(card, 0)
        add(Ui.text(c, "Precisa do ADB conectado. Pra parar sem abrir o app: VOL+ VOL− VOL+ VOL− rápido (só vale com a density alterada).", 12f, Ui.MUTED), 10)

        if (Prefs.densOrig != null) {
            add(Ui.button(c, if (ocupado) "aguarde..." else "restaurar a tela agora", Ui.Kind.PRIMARY) {
                if (!ocupado) {
                    ocupado = true; redesenha()
                    Bg.run({ if (Prefs.mcAtivo) Engine.pararMc() else (Adb.ensure(act.applicationContext) && MC.densOff()) }) { ok ->
                        ocupado = false
                        Ui.toast(act, if (ok) "TELA RESTAURADA" else "Não consegui — conecte o ADB e tente de novo")
                        redesenha()
                    }
                }
            }, 14)
        }

        if (on) {
            add(Ui.button(c, if (ocupado) "aguarde..." else "parar monitor", Ui.Kind.DANGER) {
                if (!ocupado) {
                    ocupado = true; redesenha()
                    Bg.run({ Engine.pararMc() }) { ok ->
                        ocupado = false
                        Ui.toast(act, if (ok) "MODO CARREGADOR PARADO" else "Parou, mas a density não voltou — conecte o ADB e restaure")
                        redesenha()
                    }
                }
            }, 10)
        } else {
            add(Ui.label(c, "qual Free Fire abrir?"), 14)
            add(Ui.segmento(c, listOf("FF MAX", "FF NORMAL"), if (ff == "max") 0 else 1) { i -> ff = if (i == 0) "max" else "normal"; redesenha() }, 8)
            add(Ui.label(c, "density ao ligar o carregador"), 14)
            add(Ui.row(c, "$dens", "toque para mudar (200 a 9999)") {
                Ui.dialogNumero(act, "DENSITY", "200 a 9999", 200, 9999, dens) { v -> dens = v; redesenha() }
            }, 8)
            add(Ui.label(c, "quantas vezes?"), 14)
            add(Ui.segmento(c, listOf("1 vez", "2 vezes", "3 vezes"), rep - 1) { i -> rep = i + 1; redesenha() }, 8)
            add(Ui.button(c, "ligar monitor") {
                if (!Adb.connected) Ui.toast(act, "Conecte o ADB primeiro (CONEXÃO)")
                else { Engine.iniciarMc(ff, dens, rep); Ui.toast(act, "MODO CARREGADOR LIGADO — ligue o carregador"); redesenha() }
            }, 14)
        }
        add(Ui.row(c, "Ver log", "o que o monitor percebeu e fez") { act.abre(LogScreen(act, "carregador")) }, 14)
    }
}

// =====================================================================================
// AJUSTES DO TOQUE (valores 1-600 e quantos apertos)
// =====================================================================================
class AjustesScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Ajustes do toque"

    override fun key(): String = listOf(Prefs.longOn, Prefs.multiOn, Prefs.normal, Prefs.clicksUp, Prefs.clicksDown).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, "Valores em milissegundos, de 1 a 600. Valem na hora para os monitores de volume e de carregador.", 13f, Ui.MUTED), 0)
        add(Ui.label(c, "toque LIGADO"), 14)
        add(Ui.row(c, "Long press: ${Prefs.longOn}", "toque para mudar (1 a 600)") {
            Ui.dialogNumero(act, "LONG PRESS LIGADO", "1 a 600", 1, 600, Prefs.longOn) { v -> Prefs.longOn = v; redesenha() }
        }, 8)
        add(Ui.row(c, "Multi press: ${Prefs.multiOn}", "toque para mudar (1 a 600)") {
            Ui.dialogNumero(act, "MULTI PRESS LIGADO", "1 a 600", 1, 600, Prefs.multiOn) { v -> Prefs.multiOn = v; redesenha() }
        })
        add(Ui.row(c, "Igualar os dois", "multi press = long press (${Prefs.longOn})") {
            Prefs.multiOn = Prefs.longOn; redesenha()
        })
        add(Ui.label(c, "toque NORMAL"), 14)
        add(Ui.row(c, "Valor normal: ${Prefs.normal}", "usado nos dois quando volta ao normal (1 a 600)") {
            Ui.dialogNumero(act, "VALOR NORMAL", "1 a 600", 1, 600, Prefs.normal) { v -> Prefs.normal = v; redesenha() }
        }, 8)
        add(Ui.label(c, "toque via volume · quantos apertos"), 14)
        add(Ui.row(c, "VOL+ : ${Prefs.clicksUp} apertos", "liga o toque rápido (2 a 9)") {
            Ui.dialogNumero(act, "APERTOS DO VOL+", "2 a 9", 2, 9, Prefs.clicksUp) { v -> Prefs.clicksUp = v; redesenha() }
        }, 8)
        add(Ui.row(c, "VOL− : ${Prefs.clicksDown} apertos", "volta ao normal (2 a 9)") {
            Ui.dialogNumero(act, "APERTOS DO VOL−", "2 a 9", 2, 9, Prefs.clicksDown) { v -> Prefs.clicksDown = v; redesenha() }
        })
        add(Ui.button(c, "voltar ao padrão (1 · 1 · 100 · 2x · 2x)", Ui.Kind.GHOST) {
            Prefs.longOn = 1; Prefs.multiOn = 1; Prefs.normal = 100; Prefs.clicksUp = 2; Prefs.clicksDown = 2; redesenha()
        }, 16)
    }
}

// =====================================================================================
// TESTAR AGORA
// =====================================================================================
class TestarScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Testar agora"
    private var saida = "Toque em um botão para testar. O app mexe no toque de verdade e mostra o que o aparelho leu de volta."
    private var ocupado = false

    override fun key(): String = saida + ocupado

    private fun testa(modo: String) {
        if (ocupado) return
        ocupado = true
        redesenha()
        Bg.run({
            val rc = Toque.aplica(modo, null)
            val txt = when (rc) { 0 -> "MUDOU"; 2 -> "JÁ ESTAVA ASSIM"; else -> "O APARELHO NÃO ACEITOU" }
            "$txt\nPedido: ${if (modo == "rapido") Toque.txtLigado() else "normal " + Toque.txtNormal()}\nAparelho leu: ${Toque.lido()}\nPermissão própria: ${if (Sys.hasWriteSecure()) "sim" else "não"} · ADB: ${if (Adb.connected) "conectado" else "desconectado"}"
        }) { r ->
            saida = r
            ocupado = false
            redesenha()
        }
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, saida, 13f, Ui.TEXT, mono = true), 0)
        add(Ui.button(c, "testar: ligar o toque rápido") { testa("rapido") }, 16)
        add(Ui.button(c, "testar: voltar ao normal", Ui.Kind.GHOST) { testa("normal") }, 10)
    }
}
