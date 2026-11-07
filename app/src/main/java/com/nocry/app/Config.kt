package com.nocry.app

import android.app.NotificationManager
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Config {
    private fun destinos(ff: String): Triple<String, String, String> {
        return if (ff == "max") {
            Triple(
                "/storage/emulated/0/Android/data/com.dts.freefiremax/files/contentcache/Optional",
                "/storage/emulated/0/MIUI/BugReportCache/image",
                "/storage/emulated/0/MIUI/BugReportCache/draft"
            )
        } else {
            Triple(
                "/storage/emulated/0/Android/data/com.dts.freefireth/files/contentcache/Optional",
                "/storage/emulated/0/MIUI/sound_recorder/call_rec",
                "/storage/emulated/0/MIUI/sound_recorder/app_rec"
            )
        }
    }

    /**
     * "Nao perturbe" ligado? Le direto do Android (nao precisa do ADB, entao funciona mesmo se ele cair).
     * null = nao deu pra saber agora: quem chama NAO deve tratar como desligado (senao restauraria a toa).
     */
    fun dndEstado(): Boolean? {
        try {
            val nm = Sys.app.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                val f = nm.currentInterruptionFilter
                if (f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return f != NotificationManager.INTERRUPTION_FILTER_ALL
            }
        } catch (_: Throwable) {}
        if (!Adb.connected) return null
        val r = Adb.exec("settings get global zen_mode")
        val v = r.out.trim()
        return if (r.ok && v.isNotEmpty() && v != "null") v != "0" else null
    }

    /** Bluetooth ligado? Mesmo esquema: direto do Android, ADB so de reserva; null = nao deu pra saber */
    fun btEstado(): Boolean? {
        try {
            return Settings.Global.getInt(Sys.app.contentResolver, Settings.Global.BLUETOOTH_ON) != 0
        } catch (_: Throwable) {}
        if (!Adb.connected) return null
        val r = Adb.exec("settings get global bluetooth_on")
        val v = r.out.trim()
        return if (r.ok && v.isNotEmpty() && v != "null") v == "1" else null
    }

    fun dndLigado(): Boolean = dndEstado() == true
    fun btLigado(): Boolean = btEstado() == true

    /**
     * Troca a pasta "android" do jogo pela de [origem]. So apaga a do jogo se a de origem existir,
     * e so devolve true se a copia terminou de verdade (antes dava "aplicado" mesmo quando falhava).
     */
    private fun troca(destino: String, origem: String, oQue: String): Boolean {
        val r = Adb.exec(
            "if [ -d \"$origem/android\" ]; then " +
                "rm -rf \"$destino/android\" && mkdir -p \"$destino\" && cp -rf \"$origem/android\" \"$destino/\" && echo NOCRY_OK; " +
                "else echo NOCRY_SEM_PASTA; fi",
            180000
        )
        if (r.out.contains("NOCRY_OK")) return true
        if (r.out.contains("NOCRY_SEM_PASTA")) {
            NLog.add("config", "$oQue: a pasta de origem nao existe ($origem/android)")
            Notif.say(Sys.app, "A ${oQue.uppercase()} NÃO ESTÁ INSTALADA. ABRA O NOCRY EM INSTALAR RECURSOS.")
        } else {
            NLog.add("config", "$oQue: a copia nao terminou (adb ok=${r.ok}) " + r.out.take(200))
        }
        return false
    }

    fun aplicar(ff: String = "normal", tipo: String = "completo"): Boolean {
        if (!Adb.ensure(Sys.app)) return false
        val (destino, srcA, _) = destinos(ff)
        return try {
            if (!troca(destino, srcA, "pasta suja")) return false
            
            // 👇 REMOVIDO DAQUI: Não mexe mais no toque.
            // if (tipo == "completo" || tipo == "normal") { ... }

            NLog.add("config", "aplicado: $ff · $tipo")
            Presenca.evento("bypass_aplicou", Presenca.ffNome(ff) + " · " + tipo.uppercase())
            true
        } catch (e: Exception) {
            NLog.add("config", "falha: ${e.message}")
            false
        }
    }

    /** apaga a pasta "android" da suja instalada (call_rec no normal, BugReportCache/image no max) */
    fun apagarSuja(ff: String = "normal"): Boolean {
        if (!Adb.ensure(Sys.app)) return false
        val (_, srcA, _) = destinos(ff)
        return try {
            Adb.exec("rm -rf \"$srcA/android\"")
            NLog.add("config", "suja apagada: $ff")
            true
        } catch (e: Exception) {
            NLog.add("config", "falha ao apagar suja: ${e.message}")
            false
        }
    }

    fun restaurar(ff: String = "normal"): Boolean {
        if (!Adb.ensure(Sys.app)) return false
        val (destino, _, srcB) = destinos(ff)
        return try {
            if (!troca(destino, srcB, "pasta limpa")) return false
            
            // 👇 REMOVIDO DAQUI: Não mexe mais no toque.
            // Sys.putSecure("long_press_timeout", "100")
            // Sys.putSecure("multi_press_timeout", "100")

            NLog.add("config", "restaurado: $ff")
            Presenca.evento("bypass_restaurou", Presenca.ffNome(ff))
            true
        } catch (e: Exception) {
            NLog.add("config", "falha: ${e.message}")
            false
        }
    }

    fun verificarLimite(): Boolean {
        val plano = Prefs.licLabel
        if (plano != "BÁSICO" && plano != "BASICO") return true
        val hoje = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val f = File(Sys.app.filesDir, "uso_$hoje.txt")
        val c = if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else 0
        if (c >= 5) return false
        f.writeText((c + 1).toString())
        return true
    }
}
