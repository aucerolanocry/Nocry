package com.nocry.app

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object EngineBypass {
    private fun ffNome() = if (Prefs.bypassFf == "max") "FF MAX" else "FF NORMAL"
    private fun gatNome(g: String) = when (g) { "dnd" -> "não perturbe"; "bt" -> "bluetooth"; "carga" -> "carregador"; "volume" -> "volume"; else -> g }
    private fun comoUsa(g: String): String = when (g) {
        "dnd" -> "Ligar o não perturbe aplica · desligar restaura"
        "bt" -> "Ligar o bluetooth aplica · desligar restaura"
        "carga" -> "Tirar do carregador aplica · colocar restaura"
        else -> "VOL+ ${Prefs.bypassCliques}x aplica · VOL− ${Prefs.bypassCliques}x restaura"
    }

    @Volatile private var prevDnd = false
    @Volatile private var prevBt = false
    @Volatile private var prevCarga: Boolean? = null
    private var cu = 0; private var lu = 0L
    private var cd = 0; private var ld = 0L
    /** false = ainda nao leu o estado desde que o app/servico ligou (ou desde que o bypass foi ligado) */
    @Volatile private var iniciou = false
    /** depois de uma falha (ex.: ADB caiu), espera um pouco antes de tentar de novo */
    @Volatile private var esperaAte = 0L
    @Volatile private var falhas = 0

    /**
     * As trocas de pasta levam alguns segundos. Antes rodavam ao mesmo tempo (gatilho + botão + volume) e uma
     * estragava a outra. Agora vão numa fila, uma de cada vez, fora do ciclo do serviço.
     */
    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "nocry-bypass").apply { isDaemon = true } }
    private val fazendo = AtomicReference<String?>(null)
    /** "aplicando" / "restaurando" enquanto a troca roda (a tela mostra e não deixa apertar de novo) */
    val trabalhando: String? get() = fazendo.get()
    /** o que o volume pediu e ainda não rodou (true = aplicar): apertou de novo no meio, vale o último */
    private val pedidoVolume = AtomicReference<Boolean?>(null)

    private fun avisaTela() = Bg.ui { Engine.onChange?.invoke() }

    /** gatilho: começa a troca se não tiver outra rodando (se tiver, o próximo ciclo confere de novo) */
    private fun trocar(oQue: String, bloco: () -> Unit) {
        if (!fazendo.compareAndSet(null, oQue)) return
        avisaTela()
        try {
            fila.execute {
                try { bloco() } catch (t: Throwable) { NLog.add("bypass", "erro: $t") }
                finally { fazendo.set(null); avisaTela() }
            }
        } catch (t: Throwable) {
            fazendo.set(null)
        }
    }

    /** roda na fila e espera (desativar/restaurar: espera a troca que estiver rodando acabar). Nunca na thread da tela */
    private fun <T> esperando(oQue: String, bloco: () -> T): T =
        fila.submit<T> {
            fazendo.set(oQue)
            avisaTela()
            try { bloco() } finally { fazendo.set(null); avisaTela() }
        }.get()

    fun iniciar(gatilho: String, ff: String, tipo: String) {
        Prefs.bypassGatilho = gatilho
        Prefs.bypassFf = ff
        Prefs.bypassTipo = tipo
        Prefs.bypassAtivo = true
        prevDnd = false; prevBt = false; prevCarga = null
        iniciou = false; falhas = 0; esperaAte = 0L
        NLog.add("bypass", "ligado: $gatilho · $ff · $tipo")
        Notif.evento(Sys.app, "BYPASS ATIVADO · ${gatNome(gatilho).uppercase()}", "${comoUsa(gatilho)} (${ffNome()} · ${tipo.uppercase()})", Notif.Efeito.LIGOU)
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_on", Presenca.gatNome(gatilho) + " · " + Presenca.ffNome(ff) + " · " + tipo.uppercase())
    }

    /** desativa: 0 = restaurou, 1 = não conseguiu restaurar (ADB), 2 = não estava aplicado */
    fun parar(): Int {
        val estava = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        // primeiro para de escutar o gatilho/volume: nada mais dispara enquanto restaura
        Prefs.bypassAtivo = false
        Prefs.bypassVolAtivo = false
        pedidoVolume.set(null)
        iniciou = false
        val rc = esperando("restaurando") {
            var r = 2
            if (Prefs.bypassMark) {
                r = if (Config.restaurar(Prefs.bypassFf)) 0 else 1
                // antes a marca ficava "aplicado" pra sempre e o proximo bypass achava que ja estava aplicado
                if (r == 0) Prefs.bypassMark = false
            }
            if (Prefs.bypassTipo == "completo") Config.apagarSuja(Prefs.bypassFf)
            r
        }
        prevDnd = false; prevBt = false; prevCarga = null
        falhas = 0; esperaAte = 0L
        NLog.add("bypass", "parou" + if (rc == 1) " (não restaurou: ${Adb.lastError})" else "")
        if (estava) {
            when (rc) {
                0 -> Notif.evento(Sys.app, "BYPASS REMOVIDO E DESATIVADO", "Restaurado · ${ffNome()} voltou ao padrão.", Notif.Efeito.DESLIGOU)
                1 -> Notif.evento(Sys.app, "BYPASS DESATIVADO, MAS NÃO RESTAUREI", "O ADB não respondeu. Abra o NOCRY > Bypass e toque em restaurar padrão.", Notif.Efeito.FALHOU)
                else -> Notif.evento(Sys.app, "BYPASS DESATIVADO", "Não estava aplicado: nada pra restaurar.", Notif.Efeito.DESLIGOU)
            }
        }
        NocryService.sync(Sys.app)
        if (estava) Presenca.evento("bypass_off", "")
        avisaTela()
        return rc
    }

    /** botão "restaurar padrão agora": espera a troca que estiver rodando e restaura */
    fun restaurarAgora(): Boolean = esperando("restaurando") {
        if (!Prefs.bypassMark) true
        else Config.restaurar(Prefs.bypassFf).also {
            if (it) {
                Prefs.bypassMark = false
                Notif.evento(Sys.app, "BYPASS REMOVIDO", "Restaurado · ${ffNome()} voltou ao padrão.", Notif.Efeito.DESLIGOU)
            }
        }
    }

    fun iniciarVolume() {
        Prefs.bypassVolAtivo = true
        cu = 0; cd = 0; lu = 0L; ld = 0L
        pedidoVolume.set(null)
        NLog.add("bypass-volume", "ligado")
        Notif.evento(Sys.app, "BYPASS ATIVADO · VOLUME", "${comoUsa("volume")} (${ffNome()} · ${Prefs.bypassTipo.uppercase()})", Notif.Efeito.LIGOU)
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_on", "VOLUME · " + Presenca.ffNome(Prefs.bypassFf) + " · " + Prefs.bypassTipo.uppercase())
    }

    fun pararVolume() {
        val estava = Prefs.bypassVolAtivo
        Prefs.bypassVolAtivo = false
        NLog.add("bypass-volume", "parado")
        if (estava) Notif.evento(Sys.app, "BYPASS DESATIVADO", if (Prefs.bypassMark) "O volume parou de escutar. O bypass continua aplicado: restaure no app." else "O volume parou de escutar.", Notif.Efeito.DESLIGOU)
        NocryService.sync(Sys.app)
        if (estava) Presenca.evento("bypass_off", "VOLUME")
    }

    fun tick() {
        if (!Prefs.bypassAtivo) { iniciou = false; return }
        if (fazendo.get() != null) return        // tem troca rodando: confere de novo quando acabar
        if (!iniciou) {
            // acabou de ligar (ou o Android fechou e reabriu o app): parte do que JA esta aplicado.
            // Assim nao reaplica a toa e, se o gatilho foi desligado com o app fechado, restaura na hora.
            prevDnd = Prefs.bypassMark
            prevBt = Prefs.bypassMark
            prevCarga = if (Prefs.bypassMark) false else null
            iniciou = true
        }
        if (System.currentTimeMillis() < esperaAte) return
        when (Prefs.bypassGatilho) {
            "dnd" -> {
                // null = nao deu pra ler agora: espera (antes, o ADB cair contava como "desligou" e restaurava no meio da partida)
                val ligado = Config.dndEstado() ?: return
                if (ligado != prevDnd) trocar(oQue(ligado)) { if (muda(ligado, "DND")) prevDnd = ligado }
            }
            "bt" -> {
                val ligado = Config.btEstado() ?: return
                if (ligado != prevBt) trocar(oQue(ligado)) { if (muda(ligado, "BT")) prevBt = ligado }
            }
            "carga" -> {
                val plugado = Power.plugged(Sys.app)
                val prev = prevCarga
                if (prev == null) { prevCarga = plugado; return }
                // tirou do carregador = aplica; ligou no carregador = restaura
                if (plugado != prev) trocar(oQue(!plugado)) { if (muda(!plugado, "carregador")) prevCarga = plugado }
            }
        }
    }

    private fun oQue(aplicar: Boolean) = if (aplicar) "aplicando" else "restaurando"

    /**
     * aplicar=true aplica a suja, false restaura a limpa. Devolve true quando resolveu
     * (ou ja estava assim); false = falhou e tenta de novo daqui a pouco, sozinho.
     */
    private fun muda(aplicar: Boolean, nome: String): Boolean {
        // desativaram enquanto esperava a vez: não aplica mais nada
        if (aplicar && !Prefs.bypassAtivo) return true
        var restaurou = false
        val ok = if (aplicar) {
            Prefs.bypassMark || Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo).also {
                if (it) { Prefs.bypassMark = true; Notif.evento(Sys.app, "BYPASS APLICADO", "Já está no jogo · ${ffNome()} · pelo $nome", Notif.Efeito.LIGOU) }
            }
        } else {
            !Prefs.bypassMark || Config.restaurar(Prefs.bypassFf).also {
                if (it) { Prefs.bypassMark = false; restaurou = true; Notif.evento(Sys.app, "BYPASS REMOVIDO", "Restaurado · ${ffNome()} voltou ao padrão · pelo $nome", Notif.Efeito.DESLIGOU) }
            }
        }
        if (ok) {
            if (falhas > 0) NLog.add("bypass", "deu certo depois de $falhas tentativa(s)")
            falhas = 0
            esperaAte = 0L
            if (restaurou) autodestruirSeCompleto()
        } else {
            falhas++
            esperaAte = System.currentTimeMillis() + minOf(3000L * falhas, 30000L)
            NLog.add("bypass", (if (aplicar) "nao aplicou" else "nao restaurou") + " ($nome), tentativa $falhas: " + Adb.lastError)
            if (falhas == 1 || falhas % 10 == 0) {
                Notif.evento(Sys.app, if (aplicar) "AINDA NÃO APLIQUEI O BYPASS" else "AINDA NÃO RESTAUREI",
                    "O ADB não respondeu. Tentando de novo sozinho...", Notif.Efeito.FALHOU)
            }
        }
        avisaTela()
        return ok
    }

    private fun autodestruirSeCompleto() {
        if (Prefs.bypassTipo != "completo") return
        Config.apagarSuja(Prefs.bypassFf)
        NLog.add("bypass", "autodestruição (modo completo) - apagou a suja")
        Notif.evento(Sys.app, "BYPASS REMOVIDO (COMPLETO)", "Restaurado e a pasta suja foi apagada. Pra usar de novo, instale em Instalar recursos.", Notif.Efeito.DESLIGOU)
        Prefs.bypassAtivo = false
        Prefs.bypassVolAtivo = false
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_off", "AUTODESTRUIÇÃO (MODO COMPLETO)")
    }

    fun onVolume(up: Boolean, now: Long) {
        if (!Prefs.bypassVolAtivo) return
        val cliques = Prefs.bypassCliques
        if (up) {
            cd = 0
            cu = if (now - lu <= 900) cu + 1 else 1
            lu = now
            if (cu >= cliques) { cu = 0; pedirPeloVolume(true, cliques) }
        } else {
            cu = 0
            cd = if (now - ld <= 900) cd + 1 else 1
            ld = now
            if (cd >= cliques) { cd = 0; pedirPeloVolume(false, cliques) }
        }
    }

    /**
     * Antes rodava a cópia aqui mesmo: os cliques apertados enquanto copiava ficavam esperando e depois
     * aplicavam e restauravam um atrás do outro. Agora vale o último pedido: se apertar VOL− no meio de
     * um VOL+, ele termina de aplicar e restaura em seguida.
     */
    private fun pedirPeloVolume(aplicar: Boolean, cliques: Int) {
        val jaTinha = pedidoVolume.getAndSet(aplicar)
        if (jaTinha != null) return                       // já tem um pedido esperando a vez: ele faz o último
        val ocupado = fazendo.get()
        if (ocupado != null) {
            Notif.evento(Sys.app, "ESPERA UM POUCO", "Ainda ${ocupado} a pasta. Depois ${if (aplicar) "aplica" else "restaura"} sozinho.", Notif.Efeito.INFO)
        }
        fila.execute {
            val quer = pedidoVolume.getAndSet(null) ?: return@execute
            if (!Prefs.bypassVolAtivo) return@execute
            fazendo.set(oQue(quer))
            avisaTela()
            try {
                if (quer) aplicarPeloVolume(cliques) else restaurarPeloVolume(cliques)
            } catch (t: Throwable) {
                NLog.add("bypass-volume", "erro: $t")
            } finally {
                fazendo.set(null)
                avisaTela()
            }
        }
    }

    private fun aplicarPeloVolume(cliques: Int) {
        if (Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo)) {
            Prefs.bypassMark = true
            Notif.evento(Sys.app, "BYPASS APLICADO", "Já está no jogo · ${ffNome()} · VOL+ ${cliques}x", Notif.Efeito.LIGOU)
        } else {
            // antes falhava calado e parecia que o volume nao tinha funcionado
            NLog.add("bypass-volume", "nao aplicou: " + Adb.lastError)
            Notif.evento(Sys.app, "NÃO DEU PRA APLICAR AGORA", "O ADB não respondeu. Aperte VOL+ ${cliques}x de novo em alguns segundos.", Notif.Efeito.FALHOU)
        }
    }

    private fun restaurarPeloVolume(cliques: Int) {
        if (!Prefs.bypassMark) {
            Notif.evento(Sys.app, "NADA PRA RESTAURAR", "O bypass não está aplicado agora.", Notif.Efeito.INFO)
            return
        }
        if (Config.restaurar(Prefs.bypassFf)) {
            Prefs.bypassMark = false
            Notif.evento(Sys.app, "BYPASS REMOVIDO", "Restaurado · ${ffNome()} voltou ao padrão · VOL− ${cliques}x", Notif.Efeito.DESLIGOU)
            autodestruirSeCompleto()
        } else {
            NLog.add("bypass-volume", "nao restaurou: " + Adb.lastError)
            Notif.evento(Sys.app, "NÃO DEU PRA RESTAURAR AGORA", "O ADB não respondeu. Aperte VOL− ${cliques}x de novo em alguns segundos.", Notif.Efeito.FALHOU)
        }
    }

    fun resumo(): String {
        return when {
            Prefs.bypassAtivo -> "BYPASS(${Prefs.bypassGatilho})"
            Prefs.bypassVolAtivo -> "BYPASS-VOLUME"
            else -> ""
        }
    }
}
