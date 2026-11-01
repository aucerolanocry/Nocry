package com.nocry.app

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

    fun dndLigado(): Boolean {
        val r = Adb.exec("dumpsys notification | grep mZenMode=")
        return r.ok && r.out.contains("ZEN_MODE_IMPORTANT_INTERRUPTIONS")
    }

    fun btLigado(): Boolean {
        val r = Adb.exec("settings get global bluetooth_on")
        return r.ok && r.out.trim() == "1"
    }

    fun aplicar(ff: String = "normal", tipo: String = "completo"): Boolean {
        if (!Adb.ensure(Sys.app)) return false
        val (destino, srcA, _) = destinos(ff)
        return try {
            Adb.exec("rm -rf \"$destino/android\"")
            Adb.exec("cp -rf \"$srcA/android\" \"$destino/\"")
            
            // 👇 REMOVIDO DAQUI: Não mexe mais no toque.
            // if (tipo == "completo" || tipo == "normal") { ... }

            NLog.add("config", "aplicado: $ff · $tipo")
            true
        } catch (e: Exception) {
            NLog.add("config", "falha: ${e.message}")
            false
        }
    }

    fun restaurar(ff: String = "normal"): Boolean {
        if (!Adb.ensure(Sys.app)) return false
        val (destino, _, srcB) = destinos(ff)
        return try {
            Adb.exec("rm -rf \"$destino/android\"")
            Adb.exec("cp -rf \"$srcB/android\" \"$destino/\"")
            
            // 👇 REMOVIDO DAQUI: Não mexe mais no toque.
            // Sys.putSecure("long_press_timeout", "100")
            // Sys.putSecure("multi_press_timeout", "100")

            NLog.add("config", "restaurado: $ff")
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
