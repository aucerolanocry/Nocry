package com.nocry.app

import android.content.Context
import android.os.Build
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
    override fun getDeviceName(): String = "NOCRY"
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

    val connected: Boolean
        get() = try { mgr?.isConnected == true } catch (_: Throwable) { false }

    /** conecta (descobre a porta sozinho pelo mDNS). false = nao deu; o motivo fica em lastError */
    fun connect(ctx: Context, timeoutMs: Long = 7000): Boolean {
        if (connected) return true
        return try {
            val m = manager(ctx)
            needsPairing = false
            val ok = if (Build.VERSION.SDK_INT >= 30) m.autoConnect(ctx.applicationContext, timeoutMs) else m.connect(5555)
            if (ok) lastError = ""
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

    fun disconnect() {
        try { mgr?.disconnect() } catch (_: Throwable) {}
    }

    private fun open(cmd: String): AdbStream? {
        val m = mgr ?: return null
        return try { m.openStream("shell:$cmd") } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(120)
            null
        }
    }

    /** roda um comando no shell do aparelho e devolve tudo que ele escreveu. ok=false se estourou o tempo ou nao conectou */
    fun exec(cmd: String, timeoutMs: Long = 12000): Res {
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
        try { disconnect() } catch (_: Throwable) {}
        return connect(ctx) && alive()
    }
}
