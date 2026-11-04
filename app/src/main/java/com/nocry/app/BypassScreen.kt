package com.nocry.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

class BypassScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Bypass"

    override fun key(): String = listOf(
        Prefs.bypassAtivo, Prefs.bypassGatilho, Prefs.bypassFf, Prefs.bypassTipo,
        Prefs.bypassMark, Prefs.bypassVolAtivo, Prefs.bypassCliques, Adb.connected
    ).joinToString("|")

    override fun render() {
        limpa()
        val c = act

        add(Ui.text(c, "Ativa pelo volume, não perturbe, bluetooth ou carregador — e aplica a pasta suja no Free Fire escolhido.", 10.5f, Ui.MUTED), 0)

        val ativo = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        val gat = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
        val nomeGat = when (gat) { "dnd" -> "NÃO PERTURBE"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; "volume" -> "VOLUME"; else -> gat.uppercase() }
        val hero = Ui.hero(c, R.drawable.ic_shield, "bypass", if (ativo) "Ativo" else "Desativado", ativo,
            if (ativo) "Escutando o gatilho em segundo plano." else "Escolha o gatilho, o Free Fire e o tipo.")
        hero.addView(Ui.chips(c,
            Ui.chip(c, "ADB", if (Adb.connected) "OK" else "OFF", Adb.connected),
            Ui.chip(c, "GATILHO", nomeGat, if (ativo) true else null),
            Ui.chip(c, "FREE FIRE", if (Prefs.bypassFf == "max") "MAX" else "NORMAL", null)
        ), Ui.lp(top = 16, c = c))
        add(hero, 12)

        val nx = Prefs.bypassCliques
        add(Ui.grupo(c, "escolha o gatilho", listOf(
            itemGatilho(R.drawable.ic_dnd, "Não perturbe", "ligar = ativa · desligar = desativa", "dnd"),
            itemGatilho(R.drawable.ic_bluetooth, "Bluetooth", "ligar = ativa · desligar = desativa", "bt"),
            itemGatilho(R.drawable.ic_plug, "Carregador", "tirar = ativa · colocar = desativa", "carga"),
            itemGatilho(R.drawable.ic_volume, "Volume", "VOL+ ${nx}x = ativa · VOL− ${nx}x = desativa", "volume")
        )), 20)

        add(Ui.label(c, "escolha o free fire"), 20)
        add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (Prefs.bypassFf == "normal") 0 else 1) { i ->
            Prefs.bypassFf = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        add(Ui.label(c, "escolha o tipo"), 18)
        add(Ui.segmento(c, listOf("COMPLETO", "NORMAL", "SIMPLES"),
            when (Prefs.bypassTipo) { "completo" -> 0; "normal" -> 1; else -> 2 }) { i ->
            Prefs.bypassTipo = when (i) { 0 -> "completo"; 1 -> "normal"; else -> "simples" }
            redesenha()
        }, 8)

        if (Prefs.bypassGatilho == "volume") {
            add(Ui.label(c, "cliques do volume (2 a 10)"), 18)
            add(Ui.segmento(c, listOf("2x", "3x", "4x", "5x", "6x", "7x", "8x", "9x", "10x"),
                Prefs.bypassCliques - 2) { i ->
                Prefs.bypassCliques = i + 2
                redesenha()
            }, 8)
        }

        add(Ui.button(c, if (ativo) "desativar bypass" else "ativar bypass",
            if (ativo) Ui.Kind.DANGER else Ui.Kind.PRIMARY, R.drawable.ic_check) {
            if (ativo) {
                Bg.run({ EngineBypass.parar(); EngineBypass.pararVolume(); 0 }) { _ ->
                    Ui.toast(act, "BYPASS DESATIVADO")
                    redesenha()
                }
            } else {
                if (!Adb.connected) {
                    Ui.toast(act, "CONECTE O ADB PRIMEIRO")
                    return@button
                }
                if (!Config.verificarLimite()) {
                    Ui.toast(act, "LIMITE DIÁRIO ATINGIDO")
                    return@button
                }
                val ff = Prefs.bypassFf
                if (!Downloader.pastasInstaladas(ff).first) {
                    val nomeFf = if (ff == "max") "FF MAX" else "FF NORMAL"
                    Ui.dialogConfirma(act, "FALTA A PASTA SUJA", "A pasta suja do $nomeFf ainda não foi instalada. Sem ela o bypass não tem o que aplicar. Instalar agora?", "INSTALAR") {
                        act.abre(RecursosScreen(act, ff))
                    }
                    return@button
                }
                if (Prefs.bypassGatilho == "volume") {
                    Bg.run({ if (!Sys.acessibilidadeLigada()) Sys.ligarAcessibilidade() else true; EngineBypass.iniciarVolume(); 0 }) { _ ->
                        if (Sys.acessibilidadeLigada()) {
                            Ui.toast(act, "BYPASS ATIVO — VOL+ ${Prefs.bypassCliques}X APLICA")
                        } else {
                            EngineBypass.pararVolume()
                            Ui.toast(act, "LIGUE A ACESSIBILIDADE (TELA DE CONEXÃO) PARA USAR O VOLUME")
                        }
                        redesenha()
                    }
                } else {
                    Bg.run({ EngineBypass.iniciar(Prefs.bypassGatilho, Prefs.bypassFf, Prefs.bypassTipo); 0 }) { _ ->
                        Ui.toast(act, "BYPASS ATIVO · ${Prefs.bypassGatilho}")
                        redesenha()
                    }
                }
            }
        }, 18)

        if (Prefs.bypassMark) {
            add(Ui.button(c, "restaurar padrão agora", Ui.Kind.GHOST, R.drawable.ic_stop) {
                Bg.run({ Config.restaurar(Prefs.bypassFf); Prefs.bypassMark = false; 0 }) { _ ->
                    Ui.toast(act, "PADRÃO RESTAURADO")
                    redesenha()
                }
            }, 10)
        }

        add(Ui.dica(c, "O app fica em segundo plano escutando o gatilho. Para parar, toque em desativar.", Ui.Dica.INFO), 18)
    }

    /** um gatilho dentro do grupo: icone, nome, como funciona e o marcador de escolhido */
    private fun itemGatilho(iconRes: Int, nome: String, desc: String, id: String): View {
        val c = act
        val atual = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
        val sel = atual == id
        val r = Ui.hbox(c)
        r.minimumHeight = Ui.dp(c, 56)
        r.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10))
        val ip = LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36))
        ip.rightMargin = Ui.dp(c, 12)
        r.addView(Ui.caixaIcone(c, iconRes, sel, 36), ip)
        val col = Ui.vbox(c)
        col.addView(Ui.text(c, nome, 13f, if (sel) Ui.RED else Ui.TEXT, true))
        col.addView(Ui.text(c, desc, 10.5f, Ui.MUTED), Ui.lp(top = 2, c = c))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // marcador redondo: aro sempre, miolo so no escolhido
        val marca = FrameLayout(c)
        marca.background = Ui.shape(c, android.graphics.Color.TRANSPARENT, 10, if (sel) Ui.RED else Theme.alpha(android.graphics.Color.WHITE, 0x44), 2)
        if (sel) {
            val miolo = View(c)
            miolo.background = Ui.shape(c, Ui.RED, 5)
            marca.addView(miolo, FrameLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10), Gravity.CENTER))
        }
        val mp = LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20))
        mp.leftMargin = Ui.dp(c, 8)
        r.addView(marca, mp)
        Ui.clique(r) {
            Prefs.bypassGatilho = id
            redesenha()
        }
        return r
    }
}
