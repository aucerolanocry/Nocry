package com.nocry.app

object EngineBypass {
    private var prevDnd = false
    private var prevBt = false
    private var prevCarga: Boolean? = null
    private var cu = 0; private var lu = 0L
    private var cd = 0; private var ld = 0L
    /** false = ainda nao leu o estado desde que o app/servico ligou (ou desde que o bypass foi ligado) */
    @Volatile private var iniciou = false
    /** depois de uma falha (ex.: ADB caiu), espera um pouco antes de tentar de novo */
    private var esperaAte = 0L
    private var falhas = 0

    fun iniciar(gatilho: String, ff: String, tipo: String) {
        Prefs.bypassGatilho = gatilho
        Prefs.bypassFf = ff
        Prefs.bypassTipo = tipo
        Prefs.bypassAtivo = true
        prevDnd = false; prevBt = false; prevCarga = null
        iniciou = false; falhas = 0; esperaAte = 0L
        NLog.add("bypass", "ligado: $gatilho · $ff · $tipo")
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_on", Presenca.gatNome(gatilho) + " · " + Presenca.ffNome(ff) + " · " + tipo.uppercase())
    }

    fun parar(): Int {
        val estava = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        var rc = 2
        if (Prefs.bypassMark) {
            rc = if (Config.restaurar(Prefs.bypassFf)) 0 else 1
            if (rc == 0) {
                // antes a marca ficava "aplicado" pra sempre e o proximo bypass achava que ja estava aplicado
                Prefs.bypassMark = false
                Notif.say(Sys.app, "BYPASS DESATIVADO")
            }
        }
        if (Prefs.bypassTipo == "completo") Config.apagarSuja(Prefs.bypassFf)
        Prefs.bypassAtivo = false
        Prefs.bypassVolAtivo = false
        prevDnd = false; prevBt = false; prevCarga = null
        iniciou = false
        NLog.add("bypass", "parou")
        NocryService.sync(Sys.app)
        if (estava) Presenca.evento("bypass_off", "")
        return rc
    }

    fun iniciarVolume() {
        Prefs.bypassVolAtivo = true
        cu = 0; cd = 0; lu = 0L; ld = 0L
        NLog.add("bypass-volume", "ligado")
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_on", "VOLUME · " + Presenca.ffNome(Prefs.bypassFf) + " · " + Prefs.bypassTipo.uppercase())
    }

    fun pararVolume() {
        val estava = Prefs.bypassVolAtivo
        Prefs.bypassVolAtivo = false
        NLog.add("bypass-volume", "parado")
        NocryService.sync(Sys.app)
        if (estava) Presenca.evento("bypass_off", "VOLUME")
    }

    fun tick() {
        if (!Prefs.bypassAtivo) { iniciou = false; return }
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
                if (ligado != prevDnd && muda(ligado, "DND")) prevDnd = ligado
            }
            "bt" -> {
                val ligado = Config.btEstado() ?: return
                if (ligado != prevBt && muda(ligado, "BT")) prevBt = ligado
            }
            "carga" -> {
                val plugado = Power.plugged(Sys.app)
                val prev = prevCarga
                if (prev == null) { prevCarga = plugado; return }
                // tirou do carregador = aplica; ligou no carregador = restaura
                if (plugado != prev && muda(!plugado, "carregador")) prevCarga = plugado
            }
        }
    }

    /**
     * aplicar=true aplica a suja, false restaura a limpa. Devolve true quando resolveu
     * (ou ja estava assim); false = falhou e tenta de novo daqui a pouco, sozinho.
     */
    private fun muda(aplicar: Boolean, nome: String): Boolean {
        var restaurou = false
        val ok = if (aplicar) {
            Prefs.bypassMark || Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo).also {
                if (it) { Prefs.bypassMark = true; Notif.say(Sys.app, "BYPASS APLICADO ($nome)") }
            }
        } else {
            !Prefs.bypassMark || Config.restaurar(Prefs.bypassFf).also {
                if (it) { Prefs.bypassMark = false; restaurou = true; Notif.say(Sys.app, "BYPASS RESTAURADO ($nome)") }
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
                Notif.say(Sys.app, if (aplicar) "AINDA NÃO APLIQUEI O BYPASS (ADB). TENTANDO DE NOVO SOZINHO..." else "AINDA NÃO RESTAUREI (ADB). TENTANDO DE NOVO SOZINHO...")
            }
        }
        Bg.ui { Engine.onChange?.invoke() }
        return ok
    }

    private fun autodestruirSeCompleto() {
        if (Prefs.bypassTipo != "completo") return
        Config.apagarSuja(Prefs.bypassFf)
        NLog.add("bypass", "autodestruição (modo completo) - apagou a suja")
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
            if (cu >= cliques) {
                cu = 0
                if (Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo)) {
                    Prefs.bypassMark = true
                    Notif.say(Sys.app, "BYPASS APLICADO (VOL+ ${cliques}x)")
                }
                Bg.ui { Engine.onChange?.invoke() }
            }
        } else {
            cu = 0
            cd = if (now - ld <= 900) cd + 1 else 1
            ld = now
            if (cd >= cliques) {
                cd = 0
                if (Prefs.bypassMark) {
                    if (Config.restaurar(Prefs.bypassFf)) {
                        Prefs.bypassMark = false
                        Notif.say(Sys.app, "BYPASS RESTAURADO (VOL- ${cliques}x)")
                        autodestruirSeCompleto()
                    }
                }
                Bg.ui { Engine.onChange?.invoke() }
            }
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
