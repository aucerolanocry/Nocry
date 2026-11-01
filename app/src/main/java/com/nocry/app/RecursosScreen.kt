package com.nocry.app

import android.os.Handler
import android.os.Looper

class RecursosScreen(a: MainActivity) : Screen(a) {
    override val titulo = "INSTALAR RECURSOS"

    private var ff = "normal"
    private var log = ""
    private var ocupado = false
    private val h = Handler(Looper.getMainLooper())

    override fun key(): String = "$ff|$log|$ocupado"

    private fun atualizaTela(novoLog: String) {
        h.post { log = novoLog; redesenha() }
    }

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
            if (ocupado) return@segmento
            ff = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        add(Ui.button(c, "BAIXAR PASTA SUJA (XIT)", Ui.Kind.PRIMARY, R.drawable.ic_check) {
            if (ocupado) return@button
            ocupado = true; log = "INICIANDO..."; redesenha()
            Bg.run({
                val nome = if (ff == "normal") "sujasc.zip" else "sujaffmax.zip"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/call_rec" else "/storage/emulated/0/MIUI/BugReportCache/image"
                Downloader.baixarComAndroid(nome, destino, "android") { msg -> atualizaTela(msg) }
            }) { ok ->
                ocupado = false
                log = if (ok) "PASTA SUJA INSTALADA!" else "FALHA AO INSTALAR"
                redesenha()
            }
        }, 18)

        add(Ui.button(c, "BAIXAR PASTA LIMPA (RESTAURAR)", Ui.Kind.GHOST, R.drawable.ic_stop) {
            if (ocupado) return@button
            ocupado = true; log = "INICIANDO..."; redesenha()
            Bg.run({
                val nome = if (ff == "normal") "android.zip" else "limpaffmax.zip"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/app_rec" else "/storage/emulated/0/MIUI/BugReportCache/draft"
                Downloader.baixarComAndroid(nome, destino, "android") { msg -> atualizaTela(msg) }
            }) { ok ->
                ocupado = false
                log = if (ok) "PASTA LIMPA INSTALADA!" else "FALHA AO INSTALAR"
                redesenha()
            }
        }, 10)

        if (log.isNotEmpty()) {
            add(Ui.label(c, "LOG"), 18)
            add(Ui.text(c, log, 12f, Ui.TEXT, mono = true), 8)
        }
    }
}
