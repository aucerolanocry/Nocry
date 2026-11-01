package com.nocry.app

import android.view.View
import android.widget.LinearLayout

class BypassScreen(a: MainActivity) : Screen(a) {
    override val titulo = "BYPASS"

    override fun key(): String = listOf(
        Prefs.bypassAtivo, Prefs.bypassGatilho, Prefs.bypassFf, Prefs.bypassTipo,
        Prefs.bypassMark, Prefs.bypassVolAtivo, Adb.connected
    ).joinToString("|")

    override fun render() {
        limpa()
        val c = act

        add(Ui.gradTexto(c, "BYPASS", 28f), 0)
        add(Ui.text(c, "ESCOLHA O MÉTODO, O FF E O TIPO.", 13f, Ui.MUTED), 4)

        val ativo = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        val card = Ui.card(c, forte = true, ligado = ativo)
        card.addView(Ui.label(c, "STATUS"))
        card.addView(Ui.text(c, if (ativo) "ATIVO" else "DESATIVADO", 26f, if (ativo) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        if (ativo) {
            val gat = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
            val nome = when(gat) { "dnd" -> "DND"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; "volume" -> "VOLUME"; else -> gat }
            card.addView(Ui.text(c, "GATILHO: $nome", 13f, Ui.MUTED, mono = true))
            card.addView(Ui.text(c, "FF: ${Prefs.bypassFf} · TIPO: ${Prefs.bypassTipo}", 13f, Ui.MUTED, mono = true))
        }
        card.addView(Ui.status(c, "ADB", if (Adb.connected) "CONECTADO" else "DESCONECTADO", Adb.connected), Ui.lp(top = 8, c = c))
        add(card, 0)

        add(Ui.label(c, "ESCOLHA O GATILHO"), 18)
        cardGatilho("📵 DND", "QUANDO LIGAR O NÃO PERTURBE", "dnd")
        cardGatilho("🔵 BLUETOOTH", "QUANDO LIGAR O BLUETOOTH", "bt")
        cardGatilho("🔌 CARREGADOR", "TIRAR = APLICA · COLOCAR = RESTAURA", "carga")
        cardGatilho("🔊 VOLUME", "VOL+ 3X = APLICA · VOL- 3X = RESTAURA", "volume")

        add(Ui.label(c, "ESCOLHA O FREE FIRE"), 18)
        add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (Prefs.bypassFf == "normal") 0 else 1) { i ->
            Prefs.bypassFf = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        add(Ui.label(c, "ESCOLHA O TIPO"), 18)
        add(Ui.segmento(c, listOf("COMPLETO", "NORMAL", "SIMPLES"),
            when (Prefs.bypassTipo) { "completo" -> 0; "normal" -> 1; else -> 2 }) { i ->
            Prefs.bypassTipo = when (i) { 0 -> "completo"; 1 -> "normal"; else -> "simples" }
            redesenha()
        }, 8)

        add(Ui.button(c, if (ativo) "DESATIVAR" else "ATIVAR",
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
                if (Prefs.bypassGatilho == "volume") {
                    Bg.run({ EngineBypass.iniciarVolume(); 0 }) { _ ->
                        Ui.toast(act, "BYPASS ATIVO — VOL+ 3X APLICA")
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
            add(Ui.button(c, "RESTAURAR PADRÃO AGORA", Ui.Kind.GHOST, R.drawable.ic_stop) {
                Bg.run({ Config.restaurar(Prefs.bypassFf); Prefs.bypassMark = false; 0 }) { _ ->
                    Ui.toast(act, "PADRÃO RESTAURADO")
                    redesenha()
                }
            }, 10)
        }

        add(Ui.text(c, "O APP FICA EM SEGUNDO PLANO ESCUTANDO O GATILHO. PARA PARAR, TOQUE EM DESATIVAR.", 12f, Ui.MUTED), 18)
    }

    private fun cardGatilho(nome: String, desc: String, id: String) {
        val c = act
        val atual = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
        val r = Ui.hbox(c)
        r.background = Ui.ripple(c, Ui.glass(c), Painel(c, mascara = true))
        r.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
        val col = Ui.vbox(c)
        col.addView(Ui.text(c, nome, 15f, if (atual == id) Ui.RED else Ui.TEXT, true))
        col.addView(Ui.text(c, desc, 12f, Ui.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val dot = View(c)
        dot.background = Ui.shape(c, if (atual == id) Ui.RED else Ui.MUTED, 8)
        r.addView(dot, LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)))
        Ui.clique(r) {
            Prefs.bypassGatilho = id
            redesenha()
        }
        add(r, 8)
    }
}
