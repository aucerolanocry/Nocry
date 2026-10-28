package com.nocry.app

/** Quem deixou o toque LIGADO (por isso e quem devolve ao NORMAL). */
enum class Mark { VOL, CARGA }

private const val K_LONG = "long_press_timeout"
private const val K_MULTI = "multi_press_timeout"

/** Toque via volume / carregador: so long_press_timeout e multi_press_timeout (sem density, sem abrir app). */
object Toque {
    fun getMark(m: Mark): Boolean = if (m == Mark.VOL) Prefs.markVol else Prefs.markCarga
    fun setMark(m: Mark, v: Boolean) { if (m == Mark.VOL) Prefs.markVol = v else Prefs.markCarga = v }

    /** "long X multi Y" lido do aparelho agora (so pra log) */
    fun lido(): String = "long=" + (Sys.getSecure(K_LONG) ?: "null") + " multi=" + (Sys.getSecure(K_MULTI) ?: "null")

    fun txtLigado(): String = "long ${Prefs.longOn} multi ${Prefs.multiOn}"
    fun txtNormal(): String = "${Prefs.normal}"

    /**
     * modo = "rapido" (valores LIGADO) ou "normal" (valor NORMAL nos dois).
     * 0 = mudou · 1 = o aparelho recusou · 2 = ja estava assim
     */
    fun aplica(modo: String, mark: Mark?): Int {
        val tl: Int
        val tm: Int
        if (modo == "rapido") { tl = Prefs.longOn; tm = Prefs.multiOn } else { tl = Prefs.normal; tm = Prefs.normal }
        if (Sys.getSecure(K_LONG) == tl.toString() && Sys.getSecure(K_MULTI) == tm.toString()) {
            if (modo == "rapido") Prefs.compFlag = true else {
                Prefs.compFlag = false
                if (mark != null) setMark(mark, false)
            }
            return 2
        }
        val a = Sys.putSecure(K_LONG, tl.toString())
        val b = Sys.putSecure(K_MULTI, tm.toString())
        if (!(a && b)) return 1
        if (modo == "rapido") {
            Prefs.compFlag = true
            if (mark != null) setMark(mark, true)
        } else {
            Prefs.compFlag = false
            if (mark != null) setMark(mark, false)
        }
        return 0
    }

    /** sem ADB e sem a permissao propria nao tem como mexer */
    fun podeMexer(): Boolean = Sys.hasWriteSecure() || Adb.connected
}

/** Modo competitivo simples: ATIVAR = long 1 / multi 0 (guarda o que tinha antes) · DESATIVAR devolve. */
object Comp {
    /** true = o toque rapido esta ligado agora (por aqui, pelo volume ou pelo carregador) */
    fun ligado(): Boolean {
        val l = Sys.getSecure(K_LONG)
        val on = l == "1" || Prefs.markVol || Prefs.markCarga
        Prefs.compFlag = on
        return on
    }

    fun ativar(): Boolean {
        if (!Toque.podeMexer()) return false
        if (Prefs.compOrig == null) {
            val l = Sys.getSecure(K_LONG)
            val m = Sys.getSecure(K_MULTI)
            var ls = l ?: "null"
            var ms = m ?: "null"
            if (ls == "1") { ls = "100"; ms = "100" }     // ja estava rapido por fora: o "normal" e 100/100
            Prefs.compOrig = "$ls|$ms"
        }
        val a = Sys.putSecure(K_LONG, "1")
        val b = Sys.putSecure(K_MULTI, "0")
        val ok = a && b
        if (ok) Prefs.compFlag = true
        NLog.add("comp", "ativar ok=$ok ${Toque.lido()}")
        return ok
    }

    private fun volta(chave: String, antes: String, padrao: String): Boolean {
        if (antes == "null" || antes.isEmpty()) {
            if (Sys.delSecure(chave)) return true
            return Sys.putSecure(chave, padrao)
        }
        return Sys.putSecure(chave, antes)
    }

    /** force = botao DESATIVAR: sempre devolve (e a density do modo carregador, se estiver aplicada) */
    fun desativar(force: Boolean): Boolean {
        if (!Toque.podeMexer()) return false
        if (!force && Prefs.compOrig == null && !Prefs.compFlag && Sys.getSecure(K_LONG) != "1") return true
        if (force) {
            // a tela primeiro: density alta deixa o celular quase inutilizavel
            if (Prefs.densOrig != null && !Adb.connected) return false
            if (Adb.connected && !(MC.densOff() && MC.recuperar())) return false
        }
        var l = "100"
        var m = "100"
        val o = Prefs.compOrig
        if (o != null) {
            val p = o.split('|')
            if (p.isNotEmpty() && p[0].isNotEmpty()) l = p[0]
            if (p.size > 1 && p[1].isNotEmpty()) m = p[1]
        }
        val a = volta(K_LONG, l, "100")
        val b = volta(K_MULTI, m, "100")
        if (!(a && b)) return false
        Prefs.compOrig = null
        Prefs.compFlag = false
        Prefs.markVol = false
        Prefs.markCarga = false
        NLog.add("comp", "desativar ok ${Toque.lido()}")
        return true
    }
}

/** Modo carregador: liga o carregador = density escolhida + abre o FF · tira = a density volta. Precisa de ADB. */
object MC {
    /** poe a density (guardando a de antes) e confere. Se nao pegar, desfaz */
    fun densOn(v: Int): Boolean {
        if (Prefs.densOrig == null) {
            val antes = Sys.densAntes() ?: return false          // ADB nao respondeu: nao mexe em nada
            Prefs.densOrig = antes
        }
        if (Sys.densPoe(v.toString())) return true
        densOff()
        return false
    }

    /** devolve a density de antes e confere */
    fun densOff(): Boolean {
        val o = Prefs.densOrig
        if (o != null) {
            if (!Sys.densPoe(o)) return false
            Prefs.densOrig = null
            return true
        }
        return true
    }

    /**
     * Sem registro (arquivos perdidos): so com o botao DESATIVAR e so se a tela esta mesmo numa density do modo carregador
     * (a que voce escolheu ou 1500+), volta pra 600.
     */
    fun recuperar(): Boolean {
        if (Prefs.densOrig != null) return true
        val atual = Sys.densAgora() ?: return false
        val n = atual.toIntOrNull() ?: return true
        if (atual == Prefs.mcDens.toString() || n >= 1500) return Sys.densPoe("600")
        return true
    }
}
