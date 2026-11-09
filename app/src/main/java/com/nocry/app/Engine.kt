package com.nocry.app

import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

object Engine {
    private val lock = Any()
    private val ex: ExecutorService = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, "nocry-engine").apply { isDaemon = true }
    })
    private val app get() = Sys.app

    fun post(r: () -> Unit) {
        ex.execute {
            try { r() } catch (t: Throwable) { NLog.add("erro", t.toString()) }
        }
    }

    @Volatile var onChange: (() -> Unit)? = null
    private fun mudou() { val f = onChange; if (f != null) Bg.ui { f() } }

    // ======================= VOLUME =======================
    private var cu = 0
    private var cd = 0
    private var lu = 0L
    private var ld = 0L
    private var lastUp = false
    private var lastAt = 0L
    private val gesto = ArrayList<Char>()
    private val gestoT = ArrayList<Long>()

    fun precisaTeclas(): Boolean = Prefs.volAtivo || Prefs.bypassVolAtivo || (Prefs.mcAtivo && Prefs.densOrig != null)

    fun volKey(up: Boolean) {
        if (!License.valid()) return
        val now = SystemClock.elapsedRealtime()
        post { synchronized(lock) { onVol(up, now) } }
    }

    private fun onVol(up: Boolean, now: Long) {
        if (up == lastUp && now - lastAt < 60) return
        lastUp = up
        lastAt = now

        if (Prefs.mcAtivo && Prefs.densOrig != null) {
            gesto.add(if (up) 'U' else 'D')
            gestoT.add(now)
            while (gesto.size > 4) { gesto.removeAt(0); gestoT.removeAt(0) }
            if (gesto.size == 4 && String(gesto.toCharArray()) == "UDUD" && now - gestoT[0] <= 3000) {
                gesto.clear()
                gestoT.clear()
                NLog.add("carregador", "gesto de parar (VOL+ VOL- VOL+ VOL-)")
                pararMc()
                return
            }
        } else if (gesto.isNotEmpty()) {
            gesto.clear()
            gestoT.clear()
        }

        // BYPASS AQUI (antes do return do volume)
        EngineBypass.onVolume(up, now)

        if (!Prefs.volAtivo) return
        val nu = Prefs.clicksUp
        val nd = Prefs.clicksDown
        if (up) {
            cd = 0
            cu = if (now - lu <= 900) cu + 1 else 1
            lu = now
            if (cu >= nu) {
                cu = 0
                aplicaVol("rapido", "VOL+ ${nu}x")
            }
        } else {
            cu = 0
            cd = if (now - ld <= 900) cd + 1 else 1
            ld = now
            if (cd >= nd) {
                cd = 0
                aplicaVol("normal", "VOL- ${nd}x")
            }
        }
    }

    private fun aplicaVol(modo: String, quem: String) {
        val rc = Toque.aplica(modo, Mark.VOL)
        NLog.add("volume", "$quem: $modo rc=$rc ${Toque.lido()}")
        if (rc == 0) {
            if (modo == "rapido") Notif.evento(app, "TOQUE RÁPIDO LIGADO", "${Toque.txtLigado()} · $quem", Notif.Efeito.LIGOU)
            else Notif.evento(app, "TOQUE NORMAL", "${Toque.txtNormal()} · $quem", Notif.Efeito.DESLIGOU)
        } else if (rc == 1) {
            Notif.evento(app, "O CELULAR NÃO ACEITOU A MUDANÇA", "Veja a tela Conexão (permissão ou ADB).", Notif.Efeito.FALHOU)
        }
        mudou()
    }

    fun iniciarVol() {
        synchronized(lock) {
            cu = 0; cd = 0; lu = 0L; ld = 0L
            Prefs.volAtivo = true
            NLog.add("volume", "ligado (VOL+ ${Prefs.clicksUp}x = ${Toque.txtLigado()} · VOL- ${Prefs.clicksDown}x = ${Toque.txtNormal()})")
        }
        Notif.evento(app, "TOQUE VIA VOLUME ATIVADO", "VOL+ ${Prefs.clicksUp}x liga o toque rápido · VOL− ${Prefs.clicksDown}x volta ao normal", Notif.Efeito.LIGOU)
        NocryService.sync(app)
        mudou()
        Presenca.evento("modo_on", "TOQUE VIA VOLUME")
    }

    fun pararVol(): Int {
        val estava = Prefs.volAtivo
        var rc = 2
        synchronized(lock) {
            if (Prefs.markVol) {
                rc = Toque.aplica("normal", Mark.VOL)
                NLog.add("volume", "parou: normal rc=$rc ${Toque.lido()}")
            } else NLog.add("volume", "parou")
            Prefs.volAtivo = false
        }
        if (estava) Notif.evento(app, "TOQUE VIA VOLUME DESATIVADO",
            if (rc == 1) "Não consegui voltar o toque ao normal: veja a tela Conexão." else "O toque está no normal.",
            if (rc == 1) Notif.Efeito.FALHOU else Notif.Efeito.DESLIGOU)
        NocryService.sync(app)
        mudou()
        if (estava) Presenca.evento("modo_off", "TOQUE VIA VOLUME")
        return rc
    }

    // ======================= CARREGADOR (TOQUE) =======================
    private var prevCarga: Boolean? = null
    private var tentCarga = 0

    fun iniciarCarga(gatilho: String, rep: Int) {
        synchronized(lock) {
            Prefs.cargaGatilho = gatilho
            Prefs.cargaRepTotal = rep
            Prefs.cargaRep = rep
            Prefs.cargaAtivo = true
            prevCarga = null
            tentCarga = 0
            NLog.add("toque-carga", "ligado (gatilho: $gatilho, repeticoes: $rep)")
        }
        Notif.evento(app, "TOQUE VIA CARREGADOR ATIVADO",
            if (gatilho == "colocou") "Colocar no carregador liga o toque rápido · tirar volta ao normal" else "Tirar do carregador liga o toque rápido · colocar volta ao normal",
            Notif.Efeito.LIGOU)
        NocryService.sync(app)
        mudou()
        Presenca.evento("modo_on", "TOQUE VIA CARREGADOR")
    }

    fun pararCarga(): Int {
        val estava = Prefs.cargaAtivo
        var rc = 2
        synchronized(lock) {
            if (Prefs.markCarga) {
                rc = Toque.aplica("normal", Mark.CARGA)
                NLog.add("toque-carga", "parou: normal rc=$rc ${Toque.lido()}")
            } else NLog.add("toque-carga", "parou")
            Prefs.cargaAtivo = false
            Prefs.cargaRep = 0
        }
        if (estava) Notif.evento(app, "TOQUE VIA CARREGADOR DESATIVADO",
            if (rc == 1) "Não consegui voltar o toque ao normal: veja a tela Conexão." else "O toque está no normal.",
            if (rc == 1) Notif.Efeito.FALHOU else Notif.Efeito.DESLIGOU)
        NocryService.sync(app)
        mudou()
        if (estava) Presenca.evento("modo_off", "TOQUE VIA CARREGADOR")
        return rc
    }

    private fun toqueCarga(now: Boolean) {
        if (!Prefs.cargaAtivo) { prevCarga = null; return }
        val want = if (Prefs.cargaGatilho == "colocou") (if (now) "rapido" else "normal") else (if (!now) "rapido" else "normal")
        val prev = prevCarga
        var faz = ""
        if (prev != null && now != prev) faz = "mudou"
        else if (Prefs.markCarga && want == "normal") faz = "corrige"
        if (faz.isEmpty()) { prevCarga = now; return }

        val tinha = Prefs.markCarga
        val rc = Toque.aplica(want, Mark.CARGA)
        NLog.add("toque-carga", "carregador=${if (now) "ligado" else "desligado"} ($faz): $want rc=$rc ${Toque.lido()}")
        if (rc == 0) {
            if (want == "rapido") Notif.evento(app, "TOQUE RÁPIDO LIGADO", "${Toque.txtLigado()} · carregador", Notif.Efeito.LIGOU)
            else Notif.evento(app, "TOQUE NORMAL", "${Toque.txtNormal()} · carregador", Notif.Efeito.DESLIGOU)
        }
        if (rc != 1) {
            prevCarga = now
            tentCarga = 0
            if (want == "normal" && tinha) {
                var rep = Prefs.cargaRep
                if (rep > 0) {
                    rep -= 1
                    Prefs.cargaRep = rep
                    NLog.add("toque-carga", "repeticoes restantes: $rep")
                    if (rep <= 0) {
                        Notif.evento(app, "TOQUE VIA CARREGADOR TERMINOU", "Acabaram as repetições: desligou sozinho.", Notif.Efeito.DESLIGOU)
                        NLog.add("toque-carga", "terminou as repeticoes, parando")
                        Prefs.cargaAtivo = false
                        NocryService.sync(app)
                        Presenca.evento("modo_off", "TOQUE VIA CARREGADOR (ACABARAM AS REPETIÇÕES)")
                    }
                }
            }
            mudou()
        } else {
            tentCarga += 1
            NLog.add("toque-carga", "nao consegui por o toque $want (tentativa $tentCarga)")
            if (tentCarga >= 3) {
                prevCarga = now
                tentCarga = 0
                if (faz == "corrige") Prefs.markCarga = false
            }
        }
    }

    // ======================= MODO CARREGADOR =======================
    private var prevMc: Boolean? = null
    private var tentMc = 0

    fun iniciarMc(ff: String, dens: Int, ciclos: Int) {
        synchronized(lock) {
            Prefs.mcFf = ff
            Prefs.mcDens = dens
            Prefs.mcCycles = ciclos
            Prefs.mcCyclesTotal = ciclos
            Prefs.mcAtivo = true
            prevMc = Power.plugged(app)
            tentMc = 0
            gesto.clear()
            gestoT.clear()
            NLog.add("carregador", "ligado (${if (ff == "normal") "FF NORMAL" else "FF MAX"} · density $dens · $ciclos vez(es))")
        }
        NocryService.sync(app)
        mudou()
        Presenca.evento("modo_on", "MODO CARREGADOR")
    }

    fun pararMc(): Boolean {
        val estava = Prefs.mcAtivo
        var ok = true
        synchronized(lock) {
            if (Prefs.densOrig != null) {
                ok = Adb.ensure(app) && MC.densOff()
                if (ok) Notif.evento(app, "MODO CARREGADOR DESATIVADO", "A tela voltou ao normal.", Notif.Efeito.DESLIGOU) else NLog.add("carregador", "nao consegui devolver a density")
            }
            Prefs.mcAtivo = false
            Prefs.mcCycles = 0
            prevMc = null
            NLog.add("carregador", "parou")
        }
        NocryService.sync(app)
        mudou()
        if (estava) Presenca.evento("modo_off", "MODO CARREGADOR")
        return ok
    }

    private fun modoCarga(now: Boolean) {
        if (!Prefs.mcAtivo) { prevMc = null; return }
        if (Prefs.mcCycles <= 0) { pararMc(); return }
        val prev = prevMc
        if (prev == null) { prevMc = now; return }
        if (now && !prev) {
            val dens = Prefs.mcDens
            val ok = Adb.ensure(app) && MC.densOn(dens)
            if (ok) {
                val pkg = if (Prefs.mcFf == "normal") Const.FF_NORMAL else Const.FF_MAX
                val abriu = Sys.abrirFf(pkg)
                NLog.add("carregador", "ligou: density $dens aplicada · abrir $pkg=$abriu")
                Notif.evento(app, "MODO CARREGADOR ATIVADO", "Pra parar: VOL+ VOL− VOL+ VOL−.", Notif.Efeito.LIGOU)
                prevMc = now
                tentMc = 0
            } else {
                tentMc += 1
                NLog.add("carregador", "nao consegui aplicar a density $dens (tentativa $tentMc) ${Adb.lastError}")
                if (tentMc >= 3) { prevMc = now; tentMc = 0 }
            }
            mudou()
        } else if (!now && prev) {
            if (Prefs.densOrig != null) {
                if (Adb.ensure(app) && MC.densOff()) {
                    gesto.clear()
                    gestoT.clear()
                    val c = Prefs.mcCycles - 1
                    Prefs.mcCycles = c
                    NLog.add("carregador", "tirou: density voltou · repeticoes restantes: $c")
                    Notif.evento(app, "MODO CARREGADOR", "Voltou ao normal.", Notif.Efeito.DESLIGOU)
                    prevMc = now
                    if (c <= 0) pararMc()
                    mudou()
                }
            } else prevMc = now
        } else prevMc = now
    }

    fun tick() {
        if (!License.valid()) return
        synchronized(lock) {
            val now = Power.plugged(app)
            toqueCarga(now)
            modoCarga(now)
        }
    }

    fun carregador() { post { tick() } }

    fun resumo(): String {
        val l = ArrayList<String>()
        if (Prefs.volAtivo) l.add("VOLUME")
        if (Prefs.cargaAtivo) l.add("TOQUE/CARREGADOR")
        if (Prefs.mcAtivo) l.add("MODO CARREGADOR")
        if (Prefs.otmAuto) l.add("OTIMIZACAO AUTO")
        val estadoBypass = if (Prefs.bypassMark) "APLICADO" else "PRONTO"
        if (Prefs.bypassAtivo) l.add("BYPASS ${when (Prefs.bypassGatilho) { "dnd" -> "NÃO PERTURBE"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; else -> Prefs.bypassGatilho.uppercase() }} · $estadoBypass")
        if (Prefs.bypassVolAtivo) l.add("BYPASS VOLUME · $estadoBypass")
        Sniper.resumo().takeIf { it.isNotEmpty() }?.let { l.add(it) }
        return if (l.isEmpty()) "nada ligado" else l.joinToString(" · ")
    }
}
