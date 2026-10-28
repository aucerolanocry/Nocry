package com.nocry.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Ajustes do aparelho: configuracoes seguras, density e permissoes. */
object Sys {
    lateinit var app: Context

    fun init(c: Context) { app = c.applicationContext }

    private val nomeOk = Regex("[a-z_]+")
    private val valorOk = Regex("[0-9]{1,6}")

    // ---------------- permissao propria ----------------
    fun hasWriteSecure(): Boolean =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---------------- configuracoes seguras ----------------
    /** valor atual (null = a chave nao existe) */
    fun getSecure(name: String): String? = try {
        Settings.Secure.getString(app.contentResolver, name)
    } catch (_: Exception) { null }

    /** poe e CONFERE lendo de volta. Usa a permissao propria; se nao tiver, tenta pelo ADB */
    fun putSecure(name: String, value: String): Boolean {
        if (!nomeOk.matches(name) || !valorOk.matches(value)) return false
        if (getSecure(name) == value) return true
        if (hasWriteSecure()) {
            try { Settings.Secure.putString(app.contentResolver, name, value) } catch (_: Exception) {}
        }
        if (getSecure(name) != value && Adb.connected) {
            Adb.exec(
                "settings put secure $name $value </dev/null >/dev/null 2>&1 || " +
                    "content insert --uri content://settings/secure --bind name:s:$name --bind value:s:$value </dev/null >/dev/null 2>&1",
                8000
            )
        }
        return getSecure(name) == value
    }

    /** apaga a chave (volta ao padrao do sistema) e confere */
    fun delSecure(name: String): Boolean {
        if (!nomeOk.matches(name)) return false
        if (getSecure(name) == null) return true
        if (Adb.connected) {
            Adb.exec(
                "settings delete secure $name </dev/null >/dev/null 2>&1 || " +
                    "content delete --uri content://settings/secure --where \"name='$name'\" </dev/null >/dev/null 2>&1",
                8000
            )
        }
        if (getSecure(name) != null && hasWriteSecure()) {
            try { Settings.Secure.putString(app.contentResolver, name, null) } catch (_: Exception) {}
        }
        return getSecure(name) == null
    }

    // ---------------- density (precisa de ADB) ----------------
    class Dens(val fisica: String?, val forcada: String?)

    fun densInfo(): Dens? {
        val r = Adb.exec("wm density </dev/null", 6000)
        if (!r.ok || !r.out.contains("Physical")) return null
        var f: String? = null
        var o: String? = null
        for (l in r.out.lines()) {
            val t = l.trim()
            if (t.startsWith("Physical density:")) f = t.substringAfter(":").trim()
            if (t.startsWith("Override density:")) o = t.substringAfter(":").trim()
        }
        return Dens(f, o)
    }

    /** density em uso agora (a forcada, se tiver; senao a fisica) */
    fun densAgora(): String? {
        val d = densInfo() ?: return null
        return d.forcada ?: d.fisica
    }

    /** density de ANTES: o numero (se havia uma forcada) ou "reset" (se era a do aparelho). null = ADB nao respondeu */
    fun densAntes(): String? {
        val d = densInfo() ?: return null
        return d.forcada ?: "reset"
    }

    /** poe a density e confere ("reset" = a do aparelho) */
    fun densPoe(v: String): Boolean {
        if (v != "reset" && !Regex("[0-9]{3,4}").matches(v)) return false
        if (v == "reset") {
            Adb.exec("wm density reset </dev/null >/dev/null 2>&1", 8000)
            Thread.sleep(1000)
            return densAntes() == "reset"
        }
        Adb.exec("wm density $v </dev/null >/dev/null 2>&1", 8000)
        Thread.sleep(1000)
        return densAgora() == v
    }

    // ---------------- permissoes que o app da a si mesmo pelo ADB ----------------
    /** pm grant WRITE_SECURE_SETTINGS (+ notificacoes) e tira o app da economia de bateria. Precisa de ADB */
    fun darPermissoes(): Boolean {
        if (!Adb.connected) return false
        val p = Const.PKG
        Adb.exec("pm grant $p android.permission.WRITE_SECURE_SETTINGS </dev/null 2>&1", 10000)
        Adb.exec("pm grant $p android.permission.POST_NOTIFICATIONS </dev/null 2>&1", 8000)
        Adb.exec("cmd appops set $p RUN_IN_BACKGROUND allow </dev/null >/dev/null 2>&1; cmd appops set $p RUN_ANY_IN_BACKGROUND allow </dev/null >/dev/null 2>&1; cmd deviceidle whitelist +$p </dev/null >/dev/null 2>&1", 10000)
        NLog.add("setup", "permissoes pedidas pelo ADB; WRITE_SECURE_SETTINGS=" + hasWriteSecure())
        return hasWriteSecure()
    }

    // ---------------- acessibilidade (escuta do volume) ----------------
    private fun servico(): String = ComponentName(app, KeyService::class.java).flattenToString()

    fun acessibilidadeLigada(): Boolean {
        val l = getSecureStr(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val s = servico()
        return l.split(':').any { it.equals(s, ignoreCase = true) }
    }

    private fun getSecureStr(name: String): String? = try {
        Settings.Secure.getString(app.contentResolver, name)
    } catch (_: Exception) { null }

    /** liga o servico de volume sozinho (precisa da permissao propria). false = tem que ligar na mao */
    fun ligarAcessibilidade(): Boolean {
        if (acessibilidadeLigada()) return true
        if (!hasWriteSecure()) return false
        return try {
            val atual = getSecureStr(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val novo = if (atual.isEmpty()) servico() else atual + ":" + servico()
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, novo)
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            NLog.add("setup", "acessibilidade ligada")
            acessibilidadeLigada()
        } catch (t: Exception) {
            NLog.add("setup", "nao consegui ligar a acessibilidade: $t")
            false
        }
    }

    fun desligarAcessibilidade() {
        if (!hasWriteSecure()) return
        try {
            val atual = getSecureStr(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return
            val s = servico()
            val novo = atual.split(':').filter { it.isNotEmpty() && !it.equals(s, ignoreCase = true) }.joinToString(":")
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, novo)
        } catch (_: Exception) {
        }
    }

    // ---------------- abrir o Free Fire ----------------
    fun abrirFf(pkg: String): Boolean {
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return false
        val r = Adb.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 </dev/null 2>&1", 10000)
        return r.ok && !r.out.contains("No activities found")
    }

    fun appInstalado(pkg: String): Boolean = try {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) { false }
}
