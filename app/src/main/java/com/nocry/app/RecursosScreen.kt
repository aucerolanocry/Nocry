package com.nocry.app

class RecursosScreen(a: MainActivity) : Screen(a) {
    override val titulo = "INSTALAR RECURSOS"

    private var ff = "normal"
    private var log = ""
    private var ocupado = false

    override fun key(): String = "$ff|$log|$ocupado"

    override fun render() {
        limpa()
        val c = act

        add(Ui.gradTexto(c, "INSTALAR RECURSOS", 28f), 0)
        add(Ui.text(c, "BAIXE AS PASTAS DE CONFIGURAÇÃO DIRETO PELO APP.", 13f, Ui.MUTED), 4)

        val (pastaA, pastaB) = Downloader.pastasInstaladas(ff)
        val card = Ui.card(c)
        card.addView(Ui.label(c, "STATUS DAS PASTAS"))
        card.addView(Ui.status(c, "PASTA SUJA (XIT)", if (pastaA) "INSTALADA" else "FALTA", pastaA), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "PASTA LIMPA (RESTAURAR)", if (pastaB) "INSTALADA" else "FALTA", pastaB))
        add(card, 0)

        add(Ui.label(c, "ESCOLHA O FREE FIRE"), 18)
        add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (ff == "normal") 0 else 1) { i ->
            ff = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        add(Ui.button(c, "BAIXAR PASTA SUJA (XIT)", Ui.Kind.PRIMARY, R.drawable.ic_check) {
            if (ocupado) return@button
            ocupado = true; log = ""; redesenha()
            Bg.run({
                if (ff == "normal") Downloader.baixarPastaSujaNormal { msg -> Bg.ui { log = msg; redesenha() } }
                else Downloader.baixarPastaSujaMax { msg -> Bg.ui { log = msg; redesenha() } }
            }) { ok ->
                ocupado = false
                log = if (ok) "PASTA SUJA INSTALADA COM SUCESSO!" else "FALHA AO BAIXAR PASTA SUJA"
                redesenha()
            }
        }, 18)

        add(Ui.button(c, "BAIXAR PASTA LIMPA (RESTAURAR)", Ui.Kind.GHOST, R.drawable.ic_stop) {
            if (ocupado) return@button
            ocupado = true; log = ""; redesenha()
            Bg.run({
                if (ff == "normal") Downloader.baixarPastaLimpaNormal { msg -> Bg.ui { log = msg; redesenha() } }
                else Downloader.baixarPastaLimpaMax { msg -> Bg.ui { log = msg; redesenha() } }
            }) { ok ->
                ocupado = false
                log = if (ok) "PASTA LIMPA INSTALADA COM SUCESSO!" else "FALHA AO BAIXAR PASTA LIMPA"
                redesenha()
            }
        }, 10)

        add(Ui.button(c, "BAIXAR TUDO DE UMA VEZ", Ui.Kind.PRIMARY, R.drawable.ic_download) {
            if (ocupado) return@button
            ocupado = true; log = ""; redesenha()
            Bg.run({
                val okA = if (ff == "normal") Downloader.baixarPastaSujaNormal { msg -> Bg.ui { log = msg; redesenha() } }
                          else Downloader.baixarPastaSujaMax { msg -> Bg.ui { log = msg; redesenha() } }
                
                if (okA) {
                    if (ff == "normal") Downloader.baixarPastaLimpaNormal { msg -> Bg.ui { log = msg; redesenha() } }
                    else Downloader.baixarPastaLimpaMax { msg -> Bg.ui { log = msg; redesenha() } }
                } else false
            }) { ok ->
                ocupado = false
                log = if (ok) "TUDO INSTALADO COM SUCESSO!" else "FALHA EM UM DOS DOWNLOADS"
                redesenha()
            }
        }, 10)

        if (log.isNotEmpty()) {
            add(Ui.label(c, "LOG"), 18)
            add(Ui.text(c, log, 12f, Ui.TEXT, mono = true), 8)
        }
    }
}
