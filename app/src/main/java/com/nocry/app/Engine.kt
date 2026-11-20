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

    // ======================= VOLUME (só pro bypass) =======================
    private var lastUp = false
    private var lastAt = 0L

    /** a escuta do volume só existe pro bypass agora (toque via volume saiu do app) */
    fun precisaTeclas(): Boolean = Prefs.bypassVolAtivo

    fun volKey(up: Boolean) {
        if (!License.valid()) return
        val now = SystemClock.elapsedRealtime()
        post { synchronized(lock) { onVol(up, now) } }
    }

    private fun onVol(up: Boolean, now: Long) {
        if (up == lastUp && now - lastAt < 60) return
        lastUp = up
        lastAt = now
        EngineBypass.onVolume(up, now)
    }

    // ======================= MODOS QUE SAÍRAM =======================
    /** tem sobra dos modos que saíram (toque via volume, toque via carregador, modo carregador) pra desfazer? */
    fun temModoAntigo(): Boolean =
        Prefs.volAtivo || Prefs.cargaAtivo || Prefs.mcAtivo || Prefs.markVol || Prefs.markCarga || Prefs.densOrig != null

    /**
     * Toque via volume, toque via carregador e modo carregador saíram do app. Quem atualizou com algum
     * ligado: desliga, devolve o toque ao normal e a tela (density). true = não sobrou nada.
     * Mexe no sistema: nunca na thread da tela.
     */
    fun desligarModosAntigos(): Boolean {
        if (!temModoAntigo()) return true
        synchronized(lock) {
            if (Prefs.volAtivo || Prefs.cargaAtivo || Prefs.mcAtivo) {
                Prefs.volAtivo = false
                Prefs.cargaAtivo = false
                Prefs.mcAtivo = false
                Prefs.cargaRep = 0
                Prefs.mcCycles = 0
                NLog.add("servico", "toque via volume/carregador e modo carregador saíram do app: desligados")
            }
            if (Prefs.markVol || Prefs.markCarga) {
                // o toque rápido tinha ficado ligado por eles. Se o modo competitivo está ligado, ele cuida
                val ok = Prefs.compOrig != null || Toque.aplica("normal", null) != 1
                if (ok) { Prefs.markVol = false; Prefs.markCarga = false }
            }
            if (Prefs.densOrig != null && Adb.ensure(app)) {
                if (MC.densOff()) NLog.add("servico", "a tela do modo carregador voltou ao normal")
            }
        }
        NocryService.sync(app)
        mudou()
        return !temModoAntigo()
    }

    fun resumo(): String {
        val l = ArrayList<String>()
        if (Prefs.otmAuto) l.add("OTIMIZACAO AUTO")
        val estadoBypass = if (Prefs.bypassMark) "APLICADO" else "PRONTO"
        if (Prefs.bypassAtivo) l.add("BYPASS ${when (Prefs.bypassGatilho) { "dnd" -> "NÃO PERTURBE"; "bt" -> "BLUETOOTH"; "carga" -> "CARREGADOR"; else -> Prefs.bypassGatilho.uppercase() }} · $estadoBypass")
        if (Prefs.bypassVolAtivo) l.add("BYPASS VOLUME · $estadoBypass")
        Sniper.resumo().takeIf { it.isNotEmpty() }?.let { l.add(it) }
        return if (l.isEmpty()) "nada ligado" else l.joinToString(" · ")
    }
}
