package com.nocry.app

object EngineBypass {
    private var prevDnd = false
    private var prevBt = false
    private var prevCarga: Boolean? = null
    private var cu = 0; private var lu = 0L
    private var cd = 0; private var ld = 0L

    fun iniciar(gatilho: String, ff: String, tipo: String) {
        Prefs.bypassGatilho = gatilho
        Prefs.bypassFf = ff
        Prefs.bypassTipo = tipo
        Prefs.bypassAtivo = true
        prevDnd = false; prevBt = false; prevCarga = null
        NLog.add("bypass", "ligado: $gatilho · $ff · $tipo")
        NocryService.sync(Sys.app)
        Presenca.evento("bypass_on", Presenca.gatNome(gatilho) + " · " + Presenca.ffNome(ff) + " · " + tipo.uppercase())
    }

    fun parar(): Int {
        val estava = Prefs.bypassAtivo || Prefs.bypassVolAtivo
        var rc = 2
        if (Prefs.bypassMark) {
            rc = if (Config.restaurar(Prefs.bypassFf)) 0 else 1
            if (rc == 0) Notif.say(Sys.app, "BYPASS DESATIVADO")
        }
        if (Prefs.bypassTipo == "completo") Config.apagarSuja(Prefs.bypassFf)
        Prefs.bypassAtivo = false
        Prefs.bypassVolAtivo = false
        prevDnd = false; prevBt = false; prevCarga = null
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
        if (!Prefs.bypassAtivo) return
        when (Prefs.bypassGatilho) {
            "dnd" -> {
                val ligado = Adb.connected && Config.dndLigado()
                if (ligado != prevDnd) {
                    if (ligado) {
                        if (Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo)) {
                            Prefs.bypassMark = true
                            Notif.say(Sys.app, "BYPASS APLICADO (DND)")
                        }
                    } else if (Prefs.bypassMark) {
                        if (Config.restaurar(Prefs.bypassFf)) {
                            Prefs.bypassMark = false
                            Notif.say(Sys.app, "BYPASS RESTAURADO (DND)")
                            autodestruirSeCompleto()
                        }
                    }
                    prevDnd = ligado
                    Bg.ui { Engine.onChange?.invoke() }
                }
            }
            "bt" -> {
                val ligado = Adb.connected && Config.btLigado()
                if (ligado != prevBt) {
                    if (ligado) {
                        if (Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo)) {
                            Prefs.bypassMark = true
                            Notif.say(Sys.app, "BYPASS APLICADO (BT)")
                        }
                    } else if (Prefs.bypassMark) {
                        if (Config.restaurar(Prefs.bypassFf)) {
                            Prefs.bypassMark = false
                            Notif.say(Sys.app, "BYPASS RESTAURADO (BT)")
                            autodestruirSeCompleto()
                        }
                    }
                    prevBt = ligado
                    Bg.ui { Engine.onChange?.invoke() }
                }
            }
            "carga" -> {
                val plugado = Power.plugged(Sys.app)
                val prev = prevCarga
                if (prev != null && plugado != prev) {
                    if (!plugado) {
                        if (Config.aplicar(Prefs.bypassFf, Prefs.bypassTipo)) {
                            Prefs.bypassMark = true
                            Notif.say(Sys.app, "BYPASS APLICADO (carregador)")
                        }
                    } else if (Prefs.bypassMark) {
                        if (Config.restaurar(Prefs.bypassFf)) {
                            Prefs.bypassMark = false
                            Notif.say(Sys.app, "BYPASS RESTAURADO (carregador)")
                            autodestruirSeCompleto()
                        }
                    }
                    Bg.ui { Engine.onChange?.invoke() }
                }
                prevCarga = plugado
            }
        }
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
