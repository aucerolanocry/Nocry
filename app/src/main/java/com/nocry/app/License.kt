package com.nocry.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/**
 * Key de acesso. O servidor (Cloudflare Worker) confere a key, registra o aparelho e devolve uma resposta ASSINADA
 * (ECDSA P-256). O app so guarda e confia no que bate com a chave publica embutida aqui: editar as preferencias
 * ou copiar a resposta pra outro celular nao funciona (a resposta leva o codigo do aparelho dentro).
 *
 * Sem internet o app continua valendo por GRACE_MS depois da ultima confirmacao.
 */
object License {
    /** chave PUBLICA do servidor (pode ficar no repositorio; a privada fica so no Cloudflare) */
    private const val PUB =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmddGlhxa71p98ONQklUZPEk3ZqLw4YpIfWyFvUvHcGEB5conGxgwA1q4RxHU+B+dWT15OQg2xrxrbS18c2r6Gg=="

    const val GRACE_MS = 3L * 24 * 3600 * 1000
    const val STALE_MS = 6L * 3600 * 1000
    private const val RETRY_MS = 10L * 60 * 1000

    class Result(val ok: Boolean, val reason: String) {
        fun texto(): String = when (reason) {
            "ok" -> "Pronto."
            "invalid" -> "Key inválida. Confira se digitou certo."
            "revoked" -> "Essa key foi desativada."
            "expired" -> "Essa key venceu."
            "blocked" -> "Este aparelho foi bloqueado nessa key."
            "device_limit" -> "Essa key já está em outro celular."
            "limit" -> "Muitas tentativas. Espere uns minutos e tente de novo."
            "net" -> "Sem conexão com o servidor. Confira a internet."
            "nourl" -> "Este app foi gerado sem o endereço do servidor."
            "sig" -> "A resposta do servidor não passou na conferência."
            else -> "O servidor não respondeu direito ($reason)."
        }
        /** motivos em que o servidor disse NAO de verdade (diferente de falta de internet) */
        fun negado(): Boolean = reason in setOf("invalid", "revoked", "expired", "blocked", "device_limit")
    }

    @Volatile private var cacheSig: String? = null
    @Volatile private var cacheOk = false
    @Volatile private var ultimaTentativa = 0L
    @Volatile private var revalidando = false

    // ------------------------------------------------------------------ aparelho
    /** codigo anonimo do aparelho: sha256 do ANDROID_ID (nunca o IMEI nem o numero do telefone) */
    fun devId(c: Context): String {
        val id = try { Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Exception) { null } ?: "sem-id"
        return sha256hex("nocry1:" + id).take(32)
    }

    private fun sha256hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun versao(c: Context): String = try { c.packageManager.getPackageInfo(Const.PKG, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    // ------------------------------------------------------------------ estado
    private fun campos(p: String): List<String>? {
        val f = p.split("|")
        return if (f.size == 7 && f[0] == "NOCRY1") f else null
    }

    private fun verifica(p: String, sigB64: String, dev: String): Boolean {
        return try {
            val f = campos(p) ?: return false
            if (f[1] != dev) return false
            val raw = Base64.decode(sigB64, Base64.DEFAULT)
            val der = rawParaDer(raw) ?: return false
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUB, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(p.toByteArray(Charsets.UTF_8))
            s.verify(der)
        } catch (_: Throwable) { false }
    }

    /** o WebCrypto entrega r||s (64 bytes); o Java quer DER */
    private fun rawParaDer(raw: ByteArray): ByteArray? {
        if (raw.size != 64) return null
        fun inteiro(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val r = inteiro(raw.copyOfRange(0, 32))
        val s = inteiro(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun campo(i: Int): String? {
        val p = Prefs.licP ?: return null
        return campos(p)?.getOrNull(i)
    }

    /** "owner" (dono), "staff" ou "user"; vazio = sem key */
    fun role(): String = if (valid()) (campo(3) ?: "") else ""
    fun isOwner(): Boolean = role() == "owner"
    fun isStaff(): Boolean = role() == "staff"
    fun isAdmin(): Boolean = role() == "owner" || role() == "staff"
    fun label(): String = Prefs.licLabel
    fun roleNome(): String = when (role()) { "owner" -> "DONO"; "staff" -> "STAFF"; "user" -> "USUÁRIO"; else -> "" }

    /** vencimento em ms (0 = sem vencimento) */
    fun expira(): Long = campo(4)?.toLongOrNull() ?: 0L
    private fun ts(): Long = campo(5)?.toLongOrNull() ?: 0L

    /** a key atual vale agora? (confere assinatura, aparelho, vencimento, prazo sem internet e relogio) */
    fun valid(): Boolean {
        val p = Prefs.licP ?: return false
        val sig = Prefs.licSig ?: return false
        if (cacheSig != sig) {
            cacheOk = try { verifica(p, sig, devId(Sys.app)) } catch (_: Throwable) { false }
            cacheSig = sig
        }
        if (!cacheOk) return false
        val agora = System.currentTimeMillis()
        val visto = Prefs.licVisto
        if (agora + 10 * 60_000L < visto) return false                 // relogio voltou pra tras
        if (agora - visto > 60_000L) Prefs.licVisto = agora
        if (agora - ts() > GRACE_MS) return false                       // muito tempo sem confirmar com o servidor
        val e = expira()
        if (e != 0L && agora > e) return false
        return true
    }

    /** quanto falta pro prazo sem internet acabar (ms); negativo = ja acabou */
    fun restaOffline(): Long = ts() + GRACE_MS - System.currentTimeMillis()

    // ------------------------------------------------------------------ servidor
    private fun nonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun post(caminho: String, corpo: JSONObject): JSONObject? {
        val base = BuildConfig.API_URL.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return try {
            val cx = URL(base + caminho).openConnection() as HttpURLConnection
            cx.requestMethod = "POST"
            cx.connectTimeout = 10000
            cx.readTimeout = 12000
            cx.doOutput = true
            cx.setRequestProperty("Content-Type", "application/json")
            cx.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (cx.responseCode in 200..299) cx.inputStream else cx.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: return null
            JSONObject(txt)
        } catch (_: Throwable) { null }
    }

    /** entra com uma key (bloqueia: chame fora da tela). So guarda se a resposta vier assinada e certa */
    fun login(c: Context, keyDigitada: String): Result {
        if (BuildConfig.API_URL.isBlank()) return Result(false, "nourl")
        val key = keyDigitada.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val dev = devId(c)
        val n = nonce()
        val corpo = JSONObject()
            .put("key", key)
            .put("dev", dev)
            .put("nonce", n)
            .put("brand", Build.MANUFACTURER ?: "")
            .put("model", Build.MODEL ?: "")
            .put("android", Build.VERSION.RELEASE ?: "")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("app", versao(c))
            .put("lang", Locale.getDefault().toString())
        val j = post("/v1/auth", corpo) ?: return Result(false, "net")
        if (!j.optBoolean("ok", false)) return Result(false, j.optString("reason", "server"))
        val p = j.optString("p")
        val sig = j.optString("sig")
        val f = campos(p)
        if (f == null || f[6] != n || !verifica(p, sig, dev)) return Result(false, "sig")
        Prefs.licKey = key
        Prefs.licP = p
        Prefs.licSig = sig
        Prefs.licLabel = j.optString("label", "")
        Prefs.licVisto = System.currentTimeMillis()
        cacheSig = null
        NLog.add("key", "entrou como " + roleNome() + (if (Prefs.licLabel.isNotEmpty()) " (" + Prefs.licLabel + ")" else ""))
        return Result(true, "ok")
    }

    /** confere de novo com o servidor. Sem internet = continua valendo dentro do prazo; "nao" do servidor = sai */
    fun revalida(c: Context): Result {
        val k = Prefs.licKey ?: return Result(false, "invalid")
        ultimaTentativa = System.currentTimeMillis()
        val r = login(c, k)
        if (r.negado()) sair("o servidor disse: " + r.reason)
        else if (!r.ok) NLog.add("key", "não consegui confirmar agora (" + r.reason + ")")
        return r
    }

    /** chamado de tempos em tempos pelo servico e pela tela: confirma com o servidor quando passou do prazo */
    fun tick(c: Context) {
        if (Prefs.licKey == null) return
        val agora = System.currentTimeMillis()
        if (agora - ts() < STALE_MS) return
        if (agora - ultimaTentativa < RETRY_MS) return
        if (revalidando) return
        revalidando = true
        try { revalida(c) } finally { revalidando = false }
    }

    /** sai da key e devolve o celular ao normal (toque, density, otimizacao automatica) */
    fun sair(motivo: String) {
        NLog.add("key", "saiu: $motivo")
        Prefs.licKey = null
        Prefs.licP = null
        Prefs.licSig = null
        Prefs.licLabel = ""
        cacheSig = null
        cacheOk = false
        Bg.io { desfaz() }
        Bg.ui { Sys.app.let { NocryService.sync(it) }; saiu?.invoke() }
    }

    /** a tela principal quer saber quando a key some */
    @Volatile var saiu: (() -> Unit)? = null

    private fun desfaz() {
        try { if (Prefs.volAtivo) Engine.pararVol() } catch (_: Throwable) {}
        try { if (Prefs.cargaAtivo) Engine.pararCarga() } catch (_: Throwable) {}
        try { if (Prefs.mcAtivo || Prefs.densOrig != null) Engine.pararMc() } catch (_: Throwable) {}
        try { if (Comp.ligado()) Comp.desativar(true) } catch (_: Throwable) {}
        try { if (Prefs.otmAuto) OtmAuto.desligar() } catch (_: Throwable) {}
    }
}
