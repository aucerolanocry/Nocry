package com.nocry.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

class BypassScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Bypass"

    /** um toque em ativar/desativar está rodando: não aceita outro (o toque duplo ativava e desativava) */
    private var mexendo = false

    override fun key(): String = listOf(
        Prefs.bypassAtivo, Prefs.bypassGatilho, Prefs.bypassFf, Prefs.bypassTipo,
        Prefs.bypassMark, Prefs.bypassVolAtivo, Prefs.bypassCliques, Adb.connected, SegundoPlano.estado(act), Prefs.vibrarAvisos,
        EngineBypass.trabalhando, mexendo
    ).joinToString("|")

    override fun render() {
        limpa()
        val c = act

        add(Ui.dica(c, "Escolha um gatilho (volume, não perturbe, bluetooth ou carregador). Quando ele acontece, o app aplica a pasta suja no Free Fire escolhido, e quando desfaz, restaura o padrão. Avisa na notificação e com uma vibração curta.", Ui.Dica.INFO), 0)

        val ativo = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        // com o bypass ligado (ou aplicado) não dá pra trocar o jogo/gatilho/tipo: restauraria a pasta errada
        val travado = ativo || Prefs.bypassMark
        val gat = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
        val nomeGat = when (gat) { "dnd" -> "NÃO PERTURBE"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; "volume" -> "VOLUME"; else -> gat.uppercase() }
        val trabalho = EngineBypass.trabalhando
        val nomeFf = if (Prefs.bypassFf == "max") "FF MAX" else "FF NORMAL"
        val hero = Ui.hero(c, R.drawable.ic_shield, "bypass", if (ativo) "Ativo" else "Desativado", ativo,
            when {
                trabalho == "aplicando" -> "Aplicando a pasta suja no $nomeFf... espera terminar."
                trabalho == "restaurando" -> "Restaurando o padrão do $nomeFf... espera terminar."
                ativo && Prefs.bypassMark -> "Aplicado agora no $nomeFf."
                ativo -> "Escutando o gatilho em segundo plano."
                else -> "Escolha o gatilho, o Free Fire e o tipo."
            })
        hero.addView(Ui.chips(c,
            Ui.chip(c, "ADB", if (Adb.connected) "OK" else "OFF", Adb.connected),
            Ui.chip(c, "GATILHO", nomeGat, if (ativo) true else null),
            Ui.chip(c, "AGORA", when (trabalho) { "aplicando" -> "APLICANDO"; "restaurando" -> "RESTAURANDO"; else -> if (Prefs.bypassMark) "APLICADO" else "PADRÃO" },
                if (trabalho != null || Prefs.bypassMark) true else null)
        ), Ui.lp(top = 16, c = c))
        add(hero, 12)

        val nx = Prefs.bypassCliques
        add(Ui.grupo(c, "escolha o gatilho", listOf(
            itemGatilho(R.drawable.ic_dnd, "Não perturbe", "ligar = ativa · desligar = desativa", "dnd"),
            itemGatilho(R.drawable.ic_bluetooth, "Bluetooth", "ligar = ativa · desligar = desativa", "bt"),
            itemGatilho(R.drawable.ic_plug, "Carregador", "tirar = ativa · colocar = desativa", "carga"),
            itemGatilho(R.drawable.ic_volume, "Volume", "VOL+ ${nx}x = ativa · VOL− ${nx}x = desativa", "volume")
        )), 20)

        if (travado) add(Ui.dica(c, "Pra trocar o gatilho, o Free Fire ou o tipo, desative o bypass primeiro (e restaure, se estiver aplicado). Assim ele nunca restaura a pasta do jogo errado.", Ui.Dica.AVISO), 12)

        add(Ui.secao(c, "escolha o free fire"), 20)
        add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (Prefs.bypassFf == "normal") 0 else 1) { i ->
            if (travado) { Ui.toast(act, "DESATIVE O BYPASS PRA TROCAR"); return@segmento }
            Prefs.bypassFf = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        add(Ui.secao(c, "escolha o tipo"), 18)
        add(Ui.segmento(c, listOf("COMPLETO", "NORMAL", "SIMPLES"),
            when (Prefs.bypassTipo) { "completo" -> 0; "normal" -> 1; else -> 2 }) { i ->
            if (travado) { Ui.toast(act, "DESATIVE O BYPASS PRA TROCAR"); return@segmento }
            Prefs.bypassTipo = when (i) { 0 -> "completo"; 1 -> "normal"; else -> "simples" }
            redesenha()
        }, 8)
        if (Prefs.bypassTipo == "completo") {
            add(Ui.dica(c, "Completo: depois de restaurar (ou desativar), apaga a pasta suja. Pra usar de novo, instale ela em Instalar recursos.", Ui.Dica.AVISO), 8)
        }

        if (Prefs.bypassGatilho == "volume") {
            add(Ui.secao(c, "cliques do volume (2 a 5)"), 18)
            add(Ui.segmento(c, listOf("2x", "3x", "4x", "5x"),
                Prefs.bypassCliques - 2) { i ->
                Prefs.bypassCliques = i + 2
                redesenha()
            }, 8)
        }

        add(Ui.button(c, when { mexendo -> "aguarde..."; ativo -> "desativar bypass"; else -> "ativar bypass" },
            if (ativo) Ui.Kind.DANGER else Ui.Kind.PRIMARY, R.drawable.ic_check) {
            if (mexendo) return@button
            mexendo = true
            redesenha()
            if (ativo) {
                // desliga a escuta, espera a troca que estiver rodando e restaura (tudo fora da tela)
                Bg.run({ EngineBypass.parar() }) { rc ->
                    mexendo = false
                    Ui.toast(act, when (rc) {
                        0 -> "BYPASS DESATIVADO E RESTAURADO"
                        1 -> "DESATIVADO, MAS NÃO RESTAUREI (ADB). TOQUE EM RESTAURAR PADRÃO"
                        else -> "BYPASS DESATIVADO"
                    })
                    redesenha()
                }
            } else {
                val ff = Prefs.bypassFf
                val volume = Prefs.bypassGatilho == "volume"
                Bg.run({ prepararAtivacao(ff, volume) }) { r ->
                    mexendo = false
                    when (r) {
                        "adb" -> Ui.toast(act, "CONECTE O ADB PRIMEIRO (TELA DE CONEXÃO)")
                        "pasta" -> Ui.dialogConfirma(act, "FALTA A PASTA SUJA", "A pasta suja do ${if (ff == "max") "FF MAX" else "FF NORMAL"} ainda não foi instalada. Sem ela o bypass não tem o que aplicar. Instalar agora?", "INSTALAR") {
                            act.abre(RecursosScreen(act, ff))
                        }
                        "limite" -> Ui.toast(act, "LIMITE DIÁRIO ATINGIDO")
                        "escuta" -> Ui.toast(act, "LIGUE A ESCUTA DO VOLUME (TELA DE CONEXÃO, PASSO 5)")
                        else -> Ui.toast(act, if (volume) "BYPASS ATIVO — VOL+ ${Prefs.bypassCliques}X APLICA" else "BYPASS ATIVO · ${Prefs.bypassGatilho}")
                    }
                    redesenha()
                }
            }
        }, 18)

        if (Prefs.bypassMark) {
            add(Ui.button(c, if (EngineBypass.trabalhando == "restaurando") "restaurando..." else "restaurar padrão agora", Ui.Kind.GHOST, R.drawable.ic_stop) {
                if (mexendo) return@button
                mexendo = true
                redesenha()
                Bg.run({ EngineBypass.restaurarAgora() }) { ok ->
                    mexendo = false
                    Ui.toast(act, if (ok) "PADRÃO RESTAURADO" else "NÃO DEU PRA RESTAURAR (ADB). TENTE DE NOVO")
                    redesenha()
                }
            }, 10)
        }

        add(Ui.grupo(c, "avisos", listOf(Ui.itemChave(c, "Vibrar nos avisos", "1 vibração = aplicou · 2 = removeu · 3 = falhou", Prefs.vibrarAvisos, R.drawable.ic_warn) { v ->
            Prefs.vibrarAvisos = v
            redesenha()
        })), 18)
        add(SegundoPlano.grupo(act) { redesenha() }, 14)
    }

    /**
     * confere tudo fora da tela antes de ligar. Antes lia o ADB "guardado" na tela (podia estar velho e dizer
     * que não estava conectado) e gastava o limite do dia mesmo quando faltava a pasta.
     * "" = ligou; senão o motivo: adb, pasta, limite, escuta
     */
    private fun prepararAtivacao(ff: String, volume: Boolean): String {
        if (!Adb.ensure(Sys.app)) return "adb"
        Downloader.esquecerPastas()              // confere no disco de verdade
        if (!Downloader.pastasInstaladas(ff).first) return "pasta"
        if (volume && !(Sys.acessibilidadeLigada() || Sys.ligarAcessibilidade() || Sys.acessibilidadeLigada())) return "escuta"
        if (!Config.verificarLimite()) return "limite"
        if (volume) EngineBypass.iniciarVolume()
        else EngineBypass.iniciar(Prefs.bypassGatilho, ff, Prefs.bypassTipo)
        return ""
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
        col.addView(Ui.nome(c, nome, 13.5f, if (sel) Ui.RED else Ui.TEXT))
        col.addView(Ui.text(c, desc, 11f, Ui.MUTED), Ui.lp(top = 2, c = c))
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
            if (Prefs.bypassAtivo || Prefs.bypassVolAtivo || Prefs.bypassMark) {
                if (atual != id) Ui.toast(act, "DESATIVE O BYPASS PRA TROCAR O GATILHO")
                return@clique
            }
            Prefs.bypassGatilho = id
            redesenha()
        }
        return r
    }
}
