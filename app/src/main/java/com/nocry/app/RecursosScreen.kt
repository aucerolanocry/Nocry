package com.nocry.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View

class RecursosScreen(a: MainActivity, ffInicial: String = "normal") : Screen(a) {
    override val titulo = "Instalar recursos"

    private var ff = ffInicial

    private fun temPermissao(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    // a instalacao roda no servico: a tela so mostra o estado (e redesenha quando ele muda)
    override fun key(): String = listOf(
        ff, Instalador.versao / 2, Instalador.fila().joinToString { it.id }, temPermissao(),
        Downloader.pastasInstaladas(ff), SegundoPlano.estado(act)
    ).joinToString("|")

    override fun render() {
        limpa()
        val c = act

        add(Ui.text(c, "Instala a pasta suja (usada pelo bypass) e a pasta limpa (pra restaurar depois). Pode minimizar ou fechar o app: continua sozinho e avisa na notificação.", 10.5f, Ui.MUTED), 0)

        if (!temPermissao()) {
            add(Ui.hero(c, R.drawable.ic_lock, "permissão", "Falta liberar", false, "O app precisa do acesso a todos os arquivos pra instalar as pastas."), 12)
            add(Ui.dica(c, "Na tela que abrir, ache o NOCRY e ligue \"Permitir acesso para gerenciar todos os arquivos\".", Ui.Dica.INFO), 12)
            add(Ui.button(c, "permitir acesso aos arquivos", Ui.Kind.PRIMARY, R.drawable.ic_lock) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${act.packageName}")
                    act.startActivity(intent)
                } catch (_: Exception) {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }, 8)
            return
        }

        val (pastaA, pastaB) = Downloader.pastasInstaladas(ff)
        val nomeFf = if (ff == "max") "FF MAX" else "FF normal"
        val tudo = pastaA && pastaB
        val ocupado = Instalador.ocupado()

        // ---------------- andamento (quando esta instalando) ----------------
        if (ocupado) {
            val j = Instalador.atual ?: Instalador.fila().firstOrNull()
            val p = Instalador.percent()
            val fase = Instalador.fase
            val card = Ui.hero(c, if (fase == Instalador.Fase.ESPERANDO) R.drawable.ic_wifi else R.drawable.ic_download, "instalando agora",
                j?.nomeCurto?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Preparando",
                null, ((if (j != null) (if (j.ff == "max") "FF MAX · " else "FF NORMAL · ") else "") +
                    Instalador.textoFase().ifEmpty { "PREPARANDO..." }).lowercase().replaceFirstChar { it.uppercase() })
            val bp = BarraProgresso(c)
            bp.define(if (p >= 0) p / 100f else null)
            card.addView(bp, Ui.lp(top = 16, h = Ui.dp(c, 8), c = c))
            val depois = Instalador.fila().filter { it.id != j?.id }
            val linha = (if (p >= 0) "$p%" else "") + (if (depois.isNotEmpty()) (if (p >= 0) " · " else "") + "depois: " + depois.joinToString(" + ") { it.rotulo.lowercase() } else "")
            if (linha.isNotEmpty()) card.addView(Ui.text(c, linha, 10.5f, Ui.MUTED, true, mono = true), Ui.lp(top = 6, c = c))
            add(card, 12)
            add(Ui.dica(c, "Pode minimizar, desligar a tela ou fechar o app: a instalação continua sozinha e avisa na notificação quando terminar. Se a internet cair, ela espera e continua de onde parou.", Ui.Dica.OK), 10)
            add(Ui.button(c, "cancelar instalação", Ui.Kind.GHOST, R.drawable.ic_stop) {
                Ui.dialogConfirma(c, "Cancelar a instalação?", "O que já foi baixado dessa pasta é apagado.", "cancelar instalação") {
                    Instalador.cancelarTudo()
                    redesenha()
                }
            }, 10)
        } else if (Instalador.resultado.isNotEmpty()) {
            add(Ui.dica(c, Instalador.resultado.lowercase().replaceFirstChar { it.uppercase() }, if (Instalador.resultadoOk == true) Ui.Dica.OK else Ui.Dica.AVISO), 12)
        }

        // ---------------- pastas do ff escolhido ----------------
        val hero = Ui.hero(c, R.drawable.ic_download, "pastas do $nomeFf",
            if (tudo) "Tudo instalado" else if (pastaA || pastaB) "Falta uma" else "Nada instalado", if (tudo) true else if (pastaA || pastaB) null else false,
            if (tudo) "O bypass já pode aplicar e restaurar." else "Instale as duas abaixo.")
        hero.addView(Ui.chips(c,
            Ui.chip(c, "SUJA (XIT)", if (Instalador.naFila(ff, "suja")) "NA FILA" else if (pastaA) "INSTALADA" else "FALTA", if (Instalador.naFila(ff, "suja")) null else pastaA),
            Ui.chip(c, "LIMPA", if (Instalador.naFila(ff, "limpa")) "NA FILA" else if (pastaB) "INSTALADA" else "FALTA", if (Instalador.naFila(ff, "limpa")) null else pastaB)
        ), Ui.lp(top = 16, c = c))
        add(hero, if (ocupado || Instalador.resultado.isNotEmpty()) 18 else 12)

        add(Ui.labelIcone(c, R.drawable.ic_gamepad, "escolha o free fire"), 20)
        add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (ff == "normal") 0 else 1) { i ->
            ff = if (i == 0) "normal" else "max"
            redesenha()
        }, 8)

        val faltam = ArrayList<Instalador.Job>()
        if (!pastaA) faltam.add(Instalador.Job(ff, "suja"))
        if (!pastaB) faltam.add(Instalador.Job(ff, "limpa"))
        val pendentes = faltam.filter { !Instalador.naFila(it.ff, it.tipo) }
        if (pendentes.isNotEmpty()) {
            add(Ui.button(c, if (pendentes.size == 2) "instalar as duas" else "instalar a que falta", Ui.Kind.PRIMARY, R.drawable.ic_download) {
                Instalador.pedir(act, pendentes)
                redesenha()
            }, 16)
        }

        add(Ui.grupo(c, if (pendentes.isEmpty()) "instalar de novo" else "ou só uma", listOf(
            itemPasta("suja", "Pasta suja", "a que o bypass aplica no jogo", pastaA),
            itemPasta("limpa", "Pasta limpa", "a que volta o jogo ao normal", pastaB)
        )), 16)

        add(SegundoPlano.grupo(act) { redesenha() }, 18)
    }

    private fun itemPasta(tipo: String, titulo: String, sub: String, instalada: Boolean): View {
        val c = act
        val naFila = Instalador.naFila(ff, tipo)
        val valor = if (naFila) "NA FILA" else if (instalada) "INSTALADA" else "FALTA"
        val cor = if (naFila) Ui.WARN else if (instalada) Ui.OK else null
        return Ui.itemEstado(c, titulo, sub, R.drawable.ic_download, valor, cor, if (naFila) null else ({
            if (instalada) {
                Ui.dialogConfirma(c, "Instalar de novo?", "A $titulo do ${if (ff == "max") "FF MAX" else "FF normal"} é baixada e trocada pela nova.", "instalar") {
                    Instalador.pedir(act, listOf(Instalador.Job(ff, tipo)))
                    redesenha()
                }
            } else {
                Instalador.pedir(act, listOf(Instalador.Job(ff, tipo)))
                redesenha()
            }
        }))
    }
}
