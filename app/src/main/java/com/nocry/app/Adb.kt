package com.nocry.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.os.Build
import android.os.Looper
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AndroidUtils
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.Random

/**
 * Cliente ADB embutido (depuracao sem fio). A chave fica no armazenamento do app: depois de parear UMA vez,
 * o app conecta sozinho sempre que a depuracao sem fio estiver ligada.
 * Baseado no exemplo da libadb-android (GPL-3.0-or-later OU Apache-2.0).
 */
class NocryAdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = ctx.filesDir
        val kf = File(dir, "adb_private.key")
        val cf = File(dir, "adb_cert.pem")
        var k: PrivateKey? = null
        var c: Certificate? = null
        try {
            if (kf.exists() && cf.exists()) {
                k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                c = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }
        } catch (_: Exception) {
            k = null
            c = null
        }
        if (k == null || c == null) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom())
            val pair = gen.generateKeyPair()
            val pub = pair.public
            val priv = pair.private
            val algorithmName = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 10L * 365L * 86400000L)
            val ext = CertificateExtensions()
            ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pub).identifier))
            val name = X500Name("CN=NOCRY")
            ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
            info.set("subject", CertificateSubjectName(name))
            info.set("key", CertificateX509Key(pub))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(name))
            info.set("extensions", ext)
            val x509 = X509CertImpl(info)
            x509.sign(priv, algorithmName)
            k = priv
            c = x509
            try {
                kf.writeBytes(priv.encoded)
                val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(x509.encoded)
                cf.writeText("-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n")
            } catch (_: Exception) {
            }
        }
        key = k!!
        cert = c!!
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "brevent"
}

/** Acesso unico ao ADB do proprio aparelho. Tudo aqui pode bloquear: chame fora da tela (Bg.io). */
object Adb {
    private val lock = Any()
    private var mgr: NocryAdbManager? = null

    @Volatile var lastError: String = ""
    @Volatile var needsPairing: Boolean = false

    class Res(val ok: Boolean, val out: String)

    private fun manager(ctx: Context): NocryAdbManager {
        synchronized(lock) {
            val m = mgr
            if (m != null) return m
            val n = NocryAdbManager(ctx.applicationContext)
            mgr = n
            return n
        }
    }

    /**
     * Ultimo estado conhecido da conexao. O isConnected() da biblioteca usa a MESMA trava do connect/autoConnect,
     * que fica presa ate 5-9 s procurando a porta quando a depuracao sem fio caiu. Como as telas leem
     * "connected" na thread da tela a cada 1,2 s, isso congelava o app (principalmente ao voltar do segundo plano).
     * Agora a tela so le este valor guardado e a conferencia de verdade roda em segundo plano.
     */
    @Volatile private var conectadoCache = false
    @Volatile private var conferindo = false

    private fun confere(): Boolean {
        val v = try { mgr?.isConnected == true } catch (_: Throwable) { false }
        guarda(v)
        return v
    }

    /** guarda o estado e, se mudou, avisa a tela na hora (sem esperar o proximo ciclo de 1,2 s) */
    private fun guarda(v: Boolean) {
        if (conectadoCache == v) return
        conectadoCache = v
        Bg.ui { Engine.onChange?.invoke() }
    }

    val connected: Boolean
        get() {
            if (Looper.myLooper() != Looper.getMainLooper()) return confere()
            // na thread da tela: nunca espera a trava; atualiza o valor por tras e devolve o ultimo conhecido
            if (mgr != null && !conferindo) {
                conferindo = true
                Bg.io { try { confere() } finally { conferindo = false } }
            }
            return conectadoCache
        }

    /**
     * Joga fora uma conexao morta. Quando a depuracao sem fio cai (troca de Wi-Fi, celular dormiu), o socket
     * fica "meio aberto": a biblioteca ainda acha que esta conectada e o proximo comando espera a resposta
     * PRA SEMPRE segurando a trava dela. Ai nenhum "conectar" funcionava ate a pessoa desligar e ligar a
     * depuracao (o que matava o socket velho). Aqui fecha o socket por fora da trava (destrava quem estava
     * esperando) e cria um gerenciador novo na proxima conexao (mesmas chaves, nao precisa parear de novo).
     */
    private fun descarta(motivo: String) {
        val m = synchronized(lock) { val x = mgr; mgr = null; x }
        guarda(false)
        if (m == null) return
        NLog.add("adb", "conexão travada descartada ($motivo)")
        val t = Thread {
            try {
                val f = AbsAdbConnectionManager::class.java.getDeclaredField("mAdbConnection")
                f.isAccessible = true
                (f.get(m) as? AdbConnection)?.close()
            } catch (_: Throwable) {}
        }
        t.isDaemon = true
        t.start()
    }

    /** conecta (descobre a porta sozinho pelo mDNS). false = nao deu; o motivo fica em lastError */
    fun connect(ctx: Context, timeoutMs: Long = 7000): Boolean {
        if (connected) {
            // a biblioteca diz "conectado", mas pode ser a conexao velha morta: confere de verdade antes de confiar
            if (ocupado() || execAgora("echo ok", 4000).out.trim() == "ok") return true
            descarta("não respondeu ao conectar")
        }
        return try {
            val m = manager(ctx)
            needsPairing = false
            val ok = if (Build.VERSION.SDK_INT >= 30) m.autoConnect(ctx.applicationContext, timeoutMs) else m.connect(5555)
            if (ok) { lastError = ""; Prefs.adbJaConectou = true }
            guarda(ok)
            ok
        } catch (e: AdbPairingRequiredException) {
            needsPairing = true
            lastError = "precisa parear primeiro"
            false
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(120)
            false
        }
    }

    /** pareia com o codigo de 6 digitos + a porta mostrada em "Parear com codigo" */
    fun pair(ctx: Context, port: Int, code: String): Boolean {
        return try {
            val m = manager(ctx)
            val ok = m.pair(AndroidUtils.getHostIpAddress(ctx.applicationContext), port, code)
            if (ok) { needsPairing = false; lastError = "" } else lastError = "codigo ou porta errados"
            ok
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(120)
            false
        }
    }

    /** desliga. Nunca espera a trava da biblioteca (se a conexao travou, ia congelar quem chamou) */
    fun disconnect() = descarta("desligado")

    /**
     * abre um comando. A biblioteca espera a resposta do celular SEM limite de tempo; com a conexao morta isso
     * nunca voltava. Agora espera no maximo [limite]: passou disso, a conexao esta morta e e descartada.
     */
    private fun open(cmd: String, limite: Long = 8000): AdbStream? {
        val m = mgr ?: return null
        val res = java.util.concurrent.atomic.AtomicReference<AdbStream?>(null)
        val t = Thread {
            try { res.set(m.openStream("shell:" + soAscii(cmd))) } catch (e: Throwable) {
                lastError = (e.message ?: e.javaClass.simpleName).take(120)
            }
        }
        t.isDaemon = true
        t.start()
        t.join(limite)
        if (t.isAlive) {
            lastError = "o ADB parou de responder"
            descarta("comando sem resposta")
            return null
        }
        return res.get()
    }

    /** roda um comando no shell do aparelho e devolve tudo que ele escreveu. ok=false se estourou o tempo ou nao conectou */
    /** comandos longos rodando agora (cópia de pasta, otimização) */
    private val longos = java.util.concurrent.atomic.AtomicInteger(0)

    /** tem cópia/comando longo rodando: aí o "echo ok" pode demorar e NÃO é motivo pra derrubar a conexão (mataria a cópia) */
    fun ocupado(): Boolean = longos.get() > 0

    fun exec(cmd: String, timeoutMs: Long = 12000): Res {
        if (timeoutMs <= 15000) return execAgora(cmd, timeoutMs)
        longos.incrementAndGet()
        try { return execAgora(cmd, timeoutMs) } finally { longos.decrementAndGet() }
    }

    private fun execAgora(cmd: String, timeoutMs: Long): Res {
        val s = open(cmd) ?: return Res(false, "")
        val buf = ByteArrayOutputStream()
        val t = Thread {
            try {
                val ins = s.openInputStream()
                val b = ByteArray(8192)
                while (true) {
                    val n = ins.read(b)
                    if (n < 0) break
                    synchronized(buf) { buf.write(b, 0, n) }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        val done = !t.isAlive
        if (!done) {
            try { s.close() } catch (_: Throwable) {}
            t.join(500)
        } else {
            try { s.close() } catch (_: Throwable) {}
        }
        val out = synchronized(buf) { buf.toString("UTF-8") }.replace("\r", "")
        return Res(done, out)
    }

    /** roda e entrega cada linha assim que chega (ex.: apps fechando ao vivo). ok=false se estourou o tempo */
    /** o pedido de abertura do ADB quebra com acento em alguns celulares: tira o acento (comentário/texto, nada de código) */
    private fun soAscii(s: String): String {
        if (s.all { it.code < 128 }) return s
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val b = StringBuilder(n.length)
        for (ch in n) b.append(if (ch.code < 128) ch else '-')
        return b.toString()
    }

    /**
     * Script grande (otimização): antes ia inteiro dentro do pedido de abertura do ADB, que tem limite de tamanho
     * em alguns celulares ("não consegui ler o celular"). Agora vai em pedaços base64 (só letras e números)
     * pra um arquivo em /data/local/tmp e roda de lá. Se não der pra gravar, tenta do jeito antigo.
     */
    private fun gravarScript(script: String): String? {
        val nome = "/data/local/tmp/.nocry_" + System.nanoTime() + ".sh"
        val b64 = android.util.Base64.encodeToString(soAscii(script).toByteArray(Charsets.US_ASCII), android.util.Base64.NO_WRAP)
        if (!execAgora("rm -f $nome.b64; : > $nome.b64 && echo GRAVOU", 8000).out.contains("GRAVOU")) return null
        for (pedaco in b64.chunked(2400)) {
            if (!execAgora("printf %s '$pedaco' >> $nome.b64 && echo GRAVOU", 8000).out.contains("GRAVOU")) {
                execAgora("rm -f $nome.b64", 4000)
                return null
            }
        }
        val r = execAgora("base64 -d $nome.b64 > $nome 2>/dev/null; rm -f $nome.b64; [ -s $nome ] && echo GRAVOU", 8000)
        if (!r.out.contains("GRAVOU")) { execAgora("rm -f $nome", 4000); return null }
        return nome
    }

    fun execScript(script: String, timeoutMs: Long): Res {
        val arq = gravarScript(script) ?: return exec(script, timeoutMs)
        return exec("sh $arq </dev/null 2>&1; rm -f $arq", timeoutMs)
    }

    fun execScriptLines(script: String, timeoutMs: Long, onLine: (String) -> Unit): Res {
        val arq = gravarScript(script) ?: return execLines(script, timeoutMs, onLine)
        return execLines("sh $arq </dev/null 2>&1; rm -f $arq", timeoutMs, onLine)
    }

    fun execLines(cmd: String, timeoutMs: Long, onLine: (String) -> Unit): Res {
        val s = open(cmd) ?: return Res(false, "")
        val all = StringBuilder()
        val t = Thread {
            try {
                val r = BufferedReader(InputStreamReader(s.openInputStream(), Charsets.UTF_8))
                while (true) {
                    val l = r.readLine() ?: break
                    val line = l.replace("\r", "")
                    synchronized(all) { all.append(line).append('\n') }
                    try { onLine(line) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        val done = !t.isAlive
        try { s.close() } catch (_: Throwable) {}
        if (!done) t.join(500)
        return Res(done, synchronized(all) { all.toString() })
    }

    /** abre um fluxo continuo (ex.: getevent). Quem chama le e fecha */
    fun stream(cmd: String): AdbStream? = open(cmd)

    /** o ADB responde de verdade? */
    fun alive(): Boolean = connected && exec("echo ok", 4000).out.trim() == "ok"

    /** garante a conexao (reconecta se preciso). false = sem ADB agora */
    fun ensure(ctx: Context): Boolean {
        if (alive()) return true
        // celular ocupado (copiando pasta etc.): o teste pode demorar. Tenta de novo com mais calma antes de derrubar
        if (connected && (ocupado() || execAgora("echo ok", 9000).out.trim() == "ok")) return true
        try { disconnect() } catch (_: Throwable) {}
        return connect(ctx) && alive()
    }
}

/**
 * Religa a depuracao sem fio sozinho (Android 11+). Ela desliga quando troca de Wi-Fi ou reinicia o celular,
 * e antes a pessoa tinha que ir nas configuracoes ligar de novo. Com a permissao de "configuracoes seguras"
 * que o app ja se deu, ele mesmo liga e reconecta (as chaves do pareamento continuam valendo).
 * Rede que nunca foi autorizada: o Android pergunta "permitir depuracao nesta rede?" — marcando "sempre",
 * nas proximas vezes e automatico.
 */
object AdbAuto {
    private const val CHAVE = "adb_wifi_enabled"
    private val rodando = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var ouvindo = false

    /** da pra fazer sozinho: Android 11+, ja conectou antes e tem a permissao */
    fun pode(): Boolean = Build.VERSION.SDK_INT >= 30 && !BuildConfig.PREVIEW && Prefs.lerNum("adb_auto", 1L) == 1L &&
        Prefs.adbJaConectou && Sys.hasWriteSecure()

    /** o Android perguntou "permitir nesta rede?" e a pessoa não permitiu: não pergunta de novo por 30 min */
    private fun negouRecente(): Boolean = System.currentTimeMillis() - Prefs.lerNum("adb_auto_negado") < 30 * 60_000L

    private fun noWifi(c: Context): Boolean {
        return try {
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
            val n = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Throwable) { false }
    }

    private fun ligada(c: Context): Boolean =
        try { Settings.Global.getInt(c.contentResolver, CHAVE, 0) == 1 } catch (_: Throwable) { false }

    /** liga a depuracao sem fio (se estiver desligada) e reconecta. Segundo plano, uma vez por vez */
    fun religar(c: Context, motivo: String) {
        val app = c.applicationContext
        if (!pode() || !rodando.compareAndSet(false, true)) return
        Bg.io {
            try {
                // o Android desliga a depuracao logo depois de trocar de rede: espera ele terminar antes de religar
                Thread.sleep(2000)
                if (!noWifi(app)) return@io
                if (!ligada(app)) {
                    if (negouRecente()) return@io
                    try {
                        Settings.Global.putInt(app.contentResolver, CHAVE, 1)
                    } catch (t: Throwable) {
                        NLog.add("adb", "não deu pra religar a depuração: " + (t.message ?: "").take(80))
                        return@io
                    }
                    NLog.add("adb", "religuei a depuração sem fio ($motivo)")
                    Thread.sleep(2500)
                }
                for (i in 1..3) {
                    if (Adb.connect(app, 6000)) {
                        Prefs.gravaNum("adb_auto_negado", 0L)
                        NLog.add("adb", "conectou sozinho ($motivo)")
                        Bg.ui { Engine.onChange?.invoke() }
                        return@io
                    }
                    // o Android voltou pra desligado: rede nova, esta esperando a pessoa tocar em "permitir"
                    if (!ligada(app)) {
                        NLog.add("adb", "rede nova: o Android pediu pra permitir a depuração nesta rede")
                        Prefs.gravaNum("adb_auto_negado", System.currentTimeMillis())
                        return@io
                    }
                    Thread.sleep(3000)
                }
            } catch (_: Throwable) {
            } finally {
                rodando.set(false)
            }
        }
    }

    /** fica de olho no Wi-Fi: conectou numa rede, religa e reconecta */
    fun ouvir(c: Context) {
        if (ouvindo || Build.VERSION.SDK_INT < 30) return
        val app = c.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val pedido = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(pedido, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = religar(app, "Wi-Fi conectou")
            })
            ouvindo = true
        } catch (_: Throwable) {}
    }
}
