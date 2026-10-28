package com.nocry.app

import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Cerebro dos monitores: toque via volume, toque via carregador e modo carregador.
 * Os eventos chegam do servico de acessibilidade (volume) e do receptor/ciclo do servico (carregador).
 * Tudo roda numa fila so, uma coisa de cada vez.
 */
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

    /** a tela quer saber quando algo mudou */
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

    /** precisa ouvir o volume? (toque via volume ligado, ou gesto de parar com a density alterada) */
    fun precisaTeclas(): Boolean = Prefs.volAtivo || (Prefs.mcAtivo && Prefs.densOrig != null)

    /** chamado a cada aperto de VOL+ (up=true) ou VOL- (up=false) */
    fun volKey(up: Boolean) {
        val now = SystemClock.elapsedRealtime()
        post { synchronized(lock) { onVol(up, now) } }
    }

    private fun onVol(up: Boolean, now: Long) {
        if (up == lastUp && now - lastAt < 60) return        // o mesmo aperto chegando por dois caminhos
        lastUp = up
        lastAt = now

        // gesto de parar o MODO CARREGADOR: VOL+ VOL- VOL+ VOL- em ate 3 s (so enquanto a density esta alterada)
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
            Notif.say(app, if (modo == "rapido") "TOQUE LIGADO ${Toque.txtLigado()}" else "TOQUE NORMAL ${Toque.txtNormal()}")
        } else if (rc == 1) {
            Notif.say(app, "O APARELHO NAO ACEITOU A MUDANCA - veja CONEXAO")
        }
        mudou()
    }

    fun iniciarVol() {
        synchronized(lock) {
            cu = 0; cd = 0; lu = 0L; ld = 0L
            Prefs.volAtivo = true
            NLog.add("volume", "ligado (VOL+ ${Prefs.clicksUp}x = ${Toque.txtLigado()} · VOL- ${Prefs.clicksDown}x = ${Toque.txtNormal()})")
        }
        NocryService.sync(app)
        mudou()
    }

    /** para e devolve o toque ao normal se foi este monitor que deixou LIGADO. Devolve o codigo do aplica (2 = nada a fazer) */
    fun pararVol(): Int {
        var rc = 2
        synchronized(lock) {
            if (Prefs.markVol) {
                rc = Toque.aplica("normal", Mark.VOL)
                NLog.add("volume", "parou: normal rc=$rc ${Toque.lido()}")
                if (rc != 1) Notif.say(app, "TOQUE VIA VOLUME PARADO - toque normal")
            } else NLog.add("volume", "parou")
            Prefs.volAtivo = false
        }
        NocryService.sync(app)
        mudou()
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
        NocryService.sync(app)
        mudou()
    }

    fun pararCarga(): Int {
        var rc = 2
        synchronized(lock) {
            if (Prefs.markCarga) {
                rc = Toque.aplica("normal", Mark.CARGA)
                NLog.add("toque-carga", "parou: normal rc=$rc ${Toque.lido()}")
                if (rc != 1) Notif.say(app, "TOQUE VIA CARREGADOR PARADO - toque normal")
            } else NLog.add("toque-carga", "parou")
            Prefs.cargaAtivo = false
            Prefs.cargaRep = 0
        }
        NocryService.sync(app)
        mudou()
        return rc
    }

    private fun toqueCarga(now: Boolean) {
        if (!Prefs.cargaAtivo) { prevCarga = null; return }
        // qual lado e o LIGADO: "tirou" = carregador DESLIGADO · "colocou" = carregador LIGADO
        val want = if (Prefs.cargaGatilho == "colocou") (if (now) "rapido" else "normal") else (if (!now) "rapido" else "normal")
        val prev = prevCarga
        var faz = ""
        if (prev != null && now != prev) faz = "mudou"
        else if (Prefs.markCarga && want == "normal") faz = "corrige"      // deixou LIGADO e o carregador ja esta do lado normal
        if (faz.isEmpty()) { prevCarga = now; return }

        val tinha = Prefs.markCarga
        val rc = Toque.aplica(want, Mark.CARGA)
        NLog.add("toque-carga", "carregador=${if (now) "ligado" else "desligado"} ($faz): $want rc=$rc ${Toque.lido()}")
        if (rc == 0) {
            Notif.say(app, if (want == "rapido") "TOQUE LIGADO ${Toque.txtLigado()}" else "TOQUE NORMAL ${Toque.txtNormal()}")
        }
        if (rc != 1) {
            prevCarga = now
            tentCarga = 0
            if (want == "normal" && tinha) {                         // fechou uma repeticao (ligou e voltou)
                var rep = Prefs.cargaRep
                if (rep > 0) {
                    rep -= 1
                    Prefs.cargaRep = rep
                    NLog.add("toque-carga", "repeticoes restantes: $rep")
                    if (rep <= 0) {
                        Notif.say(app, "TOQUE VIA CARREGADOR TERMINOU AS REPETICOES")
                        NLog.add("toque-carga", "terminou as repeticoes, parando")
                        Prefs.cargaAtivo = false
                        NocryService.sync(app)
                    }
                }
            }
            mudou()
        } else {
            tentCarga += 1
            NLog.add("toque-carga", "nao consegui por o toque $want (tentativa $tentCarga)")
            if (tentCarga >= 3) {                                   // desiste desta vez (nao fica tentando pra sempre)
                prevCarga = now
                tentCarga = 0
                if (faz == "corrige") Prefs.markCarga = false
            }
        }
    }

    // ======================= MODO CARREGADOR (density + FF) =======================
    private var prevMc: Boolean? = null
    private var tentMc = 0

    fun iniciarMc(ff: String, dens: Int, ciclos: Int) {
        synchronized(lock) {
            Prefs.mcFf = ff
            Prefs.mcDens = dens
            Prefs.mcCycles = ciclos
            Prefs.mcCyclesTotal = ciclos
            Prefs.mcAtivo = true
            prevMc = Power.plugged(app)          // ligado agora = espera tirar e ligar de novo
            tentMc = 0
            gesto.clear()
            gestoT.clear()
            NLog.add("carregador", "ligado (${if (ff == "normal") "FF NORMAL" else "FF MAX"} · density $dens · $ciclos vez(es))")
        }
        NocryService.sync(app)
        mudou()
    }

    /** para o modo carregador e devolve a density. false = nao deu pra devolver (a tela fica marcada pra restaurar) */
    fun pararMc(): Boolean {
        var ok = true
        synchronized(lock) {
            if (Prefs.densOrig != null) {
                ok = Adb.ensure(app) && MC.densOff()
                if (ok) Notif.say(app, "MODO CARREGADOR PARADO") else NLog.add("carregador", "nao consegui devolver a density (restaure em MODO CARREGADOR)")
            }
            Prefs.mcAtivo = false
            Prefs.mcCycles = 0
            prevMc = null
            NLog.add("carregador", "parou")
        }
        NocryService.sync(app)
        mudou()
        return ok
    }

    private fun modoCarga(now: Boolean) {
        if (!Prefs.mcAtivo) { prevMc = null; return }
        if (Prefs.mcCycles <= 0) { pararMc(); return }
        val prev = prevMc
        if (prev == null) { prevMc = now; return }
        if (now && !prev) {                                          // LIGOU o carregador
            val dens = Prefs.mcDens
            val ok = Adb.ensure(app) && MC.densOn(dens)
            if (ok) {
                val pkg = if (Prefs.mcFf == "normal") Const.FF_NORMAL else Const.FF_MAX
                val abriu = Sys.abrirFf(pkg)
                NLog.add("carregador", "ligou: density $dens aplicada · abrir $pkg=$abriu")
                Notif.say(app, "MODO CARREGADOR ATIVO")
                prevMc = now
                tentMc = 0
            } else {
                tentMc += 1
                NLog.add("carregador", "nao consegui aplicar a density $dens (tentativa $tentMc) ${Adb.lastError}")
                if (tentMc >= 3) { prevMc = now; tentMc = 0 }        // desiste desta vez
            }
            mudou()
        } else if (!now && prev) {                                    // TIROU o carregador
            if (Prefs.densOrig != null) {
                if (Adb.ensure(app) && MC.densOff()) {
                    gesto.clear()
                    gestoT.clear()
                    val c = Prefs.mcCycles - 1
                    Prefs.mcCycles = c
                    NLog.add("carregador", "tirou: density voltou · repeticoes restantes: $c")
                    Notif.say(app, "MODO CARREGADOR: VOLTOU AO NORMAL")
                    prevMc = now
                    if (c <= 0) pararMc()
                    mudou()
                }                                                     // se nao voltou, tenta de novo no proximo ciclo
            } else prevMc = now
        } else prevMc = now
    }

    // ======================= CICLO (1 vez por segundo, chamado pelo servico) =======================
    fun tick() {
        synchronized(lock) {
            val now = Power.plugged(app)
            toqueCarga(now)
            modoCarga(now)
        }
    }

    /** evento do receptor de carregador (resposta imediata, sem esperar o proximo ciclo) */
    fun carregador() { post { tick() } }

    /** texto curto do que esta rodando (notificacao fixa) */
    fun resumo(): String {
        val l = ArrayList<String>()
        if (Prefs.volAtivo) l.add("VOLUME")
        if (Prefs.cargaAtivo) l.add("TOQUE/CARREGADOR")
        if (Prefs.mcAtivo) l.add("MODO CARREGADOR")
        if (Prefs.otmAuto) l.add("OTIMIZACAO AUTO")
        return if (l.isEmpty()) "nada ligado" else l.joinToString(" · ")
    }
}
