package com.nocry.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
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
            "manutencao" -> "O app está em manutenção agora. Volta daqui a pouco."
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

    // --------- infos do aparelho (pro painel): tudo leitura rápida, sem permissão ---------
    /** resolução da tela: "largura x altura @densidade" */
    private fun tela(c: Context): String = try {
        val d = c.resources.displayMetrics
        "${d.widthPixels}x${d.heightPixels}@${d.densityDpi}"
    } catch (_: Throwable) { "" }

    /** RAM total em bytes */
    private fun ramTotal(c: Context): Long = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem
    } catch (_: Throwable) { 0L }

    /** armazenamento total do aparelho em bytes */
    private fun armazTotal(): Long = try {
        val s = StatFs(Environment.getDataDirectory().path)
        s.blockCountLong * s.blockSizeLong
    } catch (_: Throwable) { 0L }

    /** heurística: o "celular" é na verdade um emulador no PC? */
    private fun ehEmulador(): Boolean {
        val fp = (Build.FINGERPRINT ?: "")
        val mo = (Build.MODEL ?: "")
        val hw = (Build.HARDWARE ?: "")
        val pr = (Build.PRODUCT ?: "")
        return fp.startsWith("generic") || fp.startsWith("unknown") || fp.contains("vbox") || fp.contains("emulator") ||
            mo.contains("google_sdk") || mo.contains("Emulator") || mo.contains("Android SDK built for") ||
            (Build.MANUFACTURER ?: "").contains("Genymotion") || (Build.BRAND ?: "").startsWith("generic") ||
            hw == "goldfish" || hw == "ranchu" || hw.contains("vbox") || hw.contains("ttVM") ||
            pr == "sdk" || pr.contains("emulator") || pr.contains("simulator") || pr.contains("vbox")
    }

    /** fuso horário: "Zona GMT±h" */
    private fun fuso(): String = try {
        val tz = TimeZone.getDefault()
        val h = tz.rawOffset / 3600000
        tz.id + " GMT" + (if (h >= 0) "+$h" else "$h")
    } catch (_: Throwable) { "" }

    /** CPU: "N núcleos · arquitetura" */
    private fun cpu(): String = try {
        val n = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
        "$n núcleos · $abi"
    } catch (_: Throwable) { "" }

    /** heurística básica de root (su/magisk/test-keys) — pode dar falso negativo se escondido */
    private fun temRoot(): Boolean = try {
        if ((Build.TAGS ?: "").contains("test-keys")) true
        else arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/system/bin/magisk", "/data/adb/magisk", "/data/adb/su"
        ).any { File(it).exists() }
    } catch (_: Throwable) { false }

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
    fun role(): String = if (BuildConfig.PREVIEW) "owner" else if (valid()) (campo(3) ?: "") else ""
    fun isOwner(): Boolean = role() == "owner"
    fun isStaff(): Boolean = role() == "staff"
    fun isAdmin(): Boolean = role() == "owner" || role() == "staff"
    fun label(): String = if (BuildConfig.PREVIEW) "Prévia" else Prefs.licLabel
    fun roleNome(): String = when (role()) { "owner" -> "DONO"; "staff" -> "STAFF"; "user" -> "USUÁRIO"; else -> "" }

    /** vencimento em ms (0 = sem vencimento) */
    fun expira(): Long = campo(4)?.toLongOrNull() ?: 0L
    private fun ts(): Long = campo(5)?.toLongOrNull() ?: 0L

    /** a key atual vale agora? (confere assinatura, aparelho, vencimento, prazo sem internet e relogio) */
    fun valid(): Boolean {
        if (BuildConfig.PREVIEW) return true
        if (Prefs.licBloqueio == "manutencao") return false   // em manutenção: trava, mas a key continua salva
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

    /** quando o servidor confirmou a key pela última vez (ms epoch); 0 = nunca */
    fun ultimaConfirmacao(): Long = ts()

    // ------------------------------------------------------------------ servidor
    private fun nonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun post(caminho: String, corpo: JSONObject): JSONObject? {
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

    /**
     * troca a key de dono (bloqueia: chame fora da tela). Precisa estar como dono e ter o código de uso único.
     * null = trocou (a key guardada no app já passa a ser a nova); senão, o motivo pra mostrar.
     */
    fun trocarKeyDono(codigo: String, nova: String): String? {
        val base = BuildConfig.API_URL.trim().trimEnd('/')
        if (base.isEmpty()) return "Sem servidor configurado."
        val atual = Prefs.licKey ?: return "Entre com a sua key de dono primeiro."
        if (!isOwner()) return "Só a key de dono pode trocar."
        return try {
            val cx = URL("$base/api/owner_key").openConnection() as HttpURLConnection
            cx.requestMethod = "POST"
            cx.connectTimeout = 10000
            cx.readTimeout = 12000
            cx.doOutput = true
            cx.setRequestProperty("Content-Type", "application/json")
            cx.setRequestProperty("X-Key", atual)
            val corpo = JSONObject().put("codigo", codigo.trim()).put("nova", nova)
            cx.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (cx.responseCode in 200..299) cx.inputStream else cx.errorStream
            val j = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
            if (j.optBoolean("ok")) {
                Prefs.licKey = nova
                NLog.add("key", "trocou a key de dono")
                null
            } else when (val r = j.optString("reason")) {
                "codigo" -> "Código errado."
                "codigo_usado" -> "Esse código já foi usado."
                "codigo_vencido" -> "O código venceu. Peça outro."
                "formato" -> "A key nova precisa ter de 12 a 64 letras, números ou traço."
                "igual" -> "A key nova é igual à atual."
                "em_uso" -> "Essa key já existe. Escolha outra."
                "denied" -> "Só a key de dono pode trocar."
                "limit" -> "Muitas tentativas. Espere uns minutos."
                else -> "O servidor não aceitou ($r)."
            }
        } catch (_: Throwable) {
            "Sem internet agora. Tente de novo."
        }
    }

    /**
     * entra com uma key (bloqueia: chame fora da tela). So guarda se a resposta vier assinada e certa.
     * renovar = o app so esta reconfirmando sozinho (no painel aparece como "renovou", nao como "entrou")
     */
    fun login(c: Context, keyDigitada: String, renovar: Boolean = false): Result {
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
            .put("tela", tela(c))
            .put("ram", ramTotal(c))
            .put("armaz", armazTotal())
            .put("emu", if (ehEmulador()) 1 else 0)
            .put("patch", if (Build.VERSION.SDK_INT >= 23) (Build.VERSION.SECURITY_PATCH ?: "") else "")
            .put("fuso", fuso())
            .put("cpu", cpu())
            .put("root", if (temRoot()) 1 else 0)
            .put("modo", if (renovar) "renovar" else "entrar")
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
        Prefs.licBloqueio = ""
        Prefs.licVisto = System.currentTimeMillis()
        // quem entra pela tela da key ja leu (e marcou) a lista do que o app envia, incluindo a atividade
        if (!renovar) Prefs.avisoAtividade = true
        cacheSig = null
        NLog.add("key", "entrou como " + roleNome() + (if (Prefs.licLabel.isNotEmpty()) " (" + Prefs.licLabel + ")" else ""))
        return Result(true, "ok")
    }

    /** confere de novo com o servidor. Sem internet = continua valendo dentro do prazo; "nao" do servidor = sai */
    fun revalida(c: Context): Result {
        val k = Prefs.licKey ?: return Result(false, "invalid")
        ultimaTentativa = System.currentTimeMillis()
        val estavaManut = Prefs.licBloqueio == "manutencao"
        val r = login(c, k, renovar = true)
        when {
            // manutenção: trava sem apagar a key (a key fica salva, volta sozinho quando o dono desligar)
            r.reason == "manutencao" -> {
                Prefs.licBloqueio = "manutencao"
                cacheOk = false; cacheSig = null
                Bg.ui { Sys.app.let { NocryService.sync(it) }; saiu?.invoke() }
            }
            r.negado() -> { Prefs.licBloqueio = r.reason; sair("o servidor disse: " + r.reason) }
            r.ok -> { if (estavaManut) Bg.ui { voltou?.invoke() } }   // manutenção acabou: volta pro app sozinho
            else -> NLog.add("key", "não consegui confirmar agora (" + r.reason + ")")
        }
        return r
    }

    /** chamado de tempos em tempos pelo servico e pela tela: confirma com o servidor quando passou do prazo */
    fun tick(c: Context) {
        if (Prefs.licKey == null) return
        val agora = System.currentTimeMillis()
        // em manutenção ignora o prazo e segue conferindo, pra voltar sozinho assim que o dono desligar
        if (Prefs.licBloqueio != "manutencao" && agora - ts() < STALE_MS) return
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

    /** a tela principal quer saber quando a manutenção acaba (volta pro app sozinho) */
    @Volatile var voltou: (() -> Unit)? = null

    private fun desfaz() {
        try { Engine.desligarModosAntigos() } catch (_: Throwable) {}
        try { if (Comp.ligado()) Comp.desativar(true) } catch (_: Throwable) {}
        try { if (Prefs.otmAuto) OtmAuto.desligar() } catch (_: Throwable) {}
        // otimização ligada na mão também volta ao normal (sem a key o botão DESATIVAR some da tela)
        try { if (Otm.estado().isNotEmpty()) Otm.desativar { } } catch (_: Throwable) {}
    }
}
