package com.nocry.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// =====================================================================================
// Instalacao das pastas (suja e limpa) em segundo plano.
// Roda num servico em primeiro plano: continua baixando e extraindo com o app minimizado,
// com a tela desligada ou fechado nos recentes. A fila fica salva: se o Android matar o app
// no meio, ela continua sozinha (o download retoma de onde parou, nao comeca do zero).
// =====================================================================================
object Instalador {
    class Job(val ff: String, val tipo: String) {
        val id: String get() = "$ff:$tipo"
        val suja: Boolean get() = tipo == "suja"
        val nomeZip: String get() = when {
            ff == "max" && suja -> "sujaffmax.zip"
            ff == "max" -> "limpaffmax.zip"
            suja -> "sujasc.zip"
            else -> "android.zip"
        }
        val destino: String get() = when {
            ff == "max" && suja -> "/storage/emulated/0/MIUI/BugReportCache/image"
            ff == "max" -> "/storage/emulated/0/MIUI/BugReportCache/draft"
            suja -> "/storage/emulated/0/MIUI/sound_recorder/call_rec"
            else -> "/storage/emulated/0/MIUI/sound_recorder/app_rec"
        }
        val url: String get() = "https://nocry-download.osreaisnocry.workers.dev/$nomeZip"
        val nomeCurto: String get() = if (suja) "PASTA SUJA" else "PASTA LIMPA"
        val rotulo: String get() = nomeCurto + " · " + (if (ff == "max") "FF MAX" else "FF NORMAL")
    }

    enum class Fase { PARADO, PREPARANDO, BAIXANDO, ESPERANDO, EXTRAINDO }

    @Volatile var fase = Fase.PARADO
    @Volatile var atual: Job? = null
    @Volatile var feito = 0L
    @Volatile var total = -1L
    @Volatile var aviso = ""
    /** ultimo resultado (aparece na tela depois que acaba) */
    @Volatile var resultado = ""
    @Volatile var resultadoOk: Boolean? = null
    @Volatile var cancelar = false
    /** muda sempre que algo muda: a tela redesenha quando ve um numero novo */
    @Volatile var versao = 0
    private val trava = Any()

    fun mudou() { versao++ }

    fun fila(): List<Job> = (Prefs.instalaFila ?: "").split(',')
        .filter { it.contains(':') }
        .map { Job(it.substringBefore(':'), it.substringAfter(':')) }

    private fun salva(l: List<Job>) { Prefs.instalaFila = l.joinToString(",") { it.id }.ifEmpty { null } }

    fun ocupado(): Boolean = fase != Fase.PARADO || fila().isNotEmpty()

    /** a pasta esta na fila (ou instalando agora)? */
    fun naFila(ff: String, tipo: String): Boolean = fila().any { it.ff == ff && it.tipo == tipo }

    /** poe na fila e liga o servico (pode chamar de novo com outra pasta: ela entra no fim da fila) */
    fun pedir(c: Context, jobs: List<Job>) {
        synchronized(trava) {
            val l = fila().toMutableList()
            for (j in jobs) if (l.none { it.id == j.id }) l.add(j)
            salva(l)
        }
        cancelar = false
        resultado = ""
        resultadoOk = null
        mudou()
        NLog.add("instalar", "pedido: " + jobs.joinToString { it.rotulo })
        InstalaService.inicia(c)
    }

    fun cancelarTudo() {
        synchronized(trava) { salva(emptyList()) }
        cancelar = true
        mudou()
        NLog.add("instalar", "cancelado pelo usuario")
    }

    private fun tiraDaFila(j: Job) {
        synchronized(trava) { salva(fila().filter { it.id != j.id }) }
    }

    private fun esvazia() {
        synchronized(trava) { salva(emptyList()) }
    }

    fun percent(): Int = if (total > 0) ((feito * 100) / total).toInt().coerceIn(0, 100) else -1

    /** uma linha explicando o que esta fazendo agora (notificacao e tela) */
    fun textoFase(): String {
        val p = percent()
        return when (fase) {
            Fase.BAIXANDO -> "BAIXANDO " + Baixador.formatar(feito) + (if (total > 0) " DE " + Baixador.formatar(total) else "") + (if (p >= 0) " · $p%" else "")
            Fase.EXTRAINDO -> "EXTRAINDO OS ARQUIVOS" + (if (p >= 0) " · $p%" else "")
            Fase.ESPERANDO -> aviso.ifEmpty { "ESPERANDO A INTERNET..." }
            Fase.PREPARANDO -> "PREPARANDO..."
            Fase.PARADO -> resultado
        }
    }

    // ------------------------------------------------------------------ trabalho
    private class Cancelado : RuntimeException("cancelado")
    private class Falha(msg: String) : Exception(msg)
    private class HttpErro(val codigo: Int) : IOException("HTTP $codigo")

    /** instala uma pasta (bloqueia; roda na thread do servico). true = instalou */
    fun executa(app: Context, job: Job, onMuda: () -> Unit): Boolean {
        atual = job
        fase = Fase.PREPARANDO
        feito = 0L
        total = -1L
        aviso = ""
        mudou(); onMuda()
        val pasta = Baixador.pasta(app)
        val parcial = File(pasta, job.nomeZip + ".part")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                throw Falha("FALTA LIBERAR O ACESSO AOS ARQUIVOS. ABRA O APP EM INSTALAR RECURSOS.")
            }
            migraDownloadAntigo(app, job.nomeZip)
            val zip = baixa(app, job, onMuda)
            fase = Fase.EXTRAINDO
            feito = 0L
            total = zip.length()
            mudou(); onMuda()
            extrai(zip, job.destino, "android", onMuda)
            zip.delete() // libera o espaco: a pasta ja foi extraida
            tiraDaFila(job)
            resultado = job.rotulo + " INSTALADA"
            resultadoOk = true
            NLog.add("instalar", "${job.rotulo}: instalada em ${job.destino}/android")
            Presenca.evento("recurso", job.rotulo)
            InstalaService.avisaFim(app, true, job.rotulo + " INSTALADA", if (job.suja) "PRONTA PRO BYPASS." else "PRONTA PRA RESTAURAR O JOGO.")
            true
        } catch (e: Cancelado) {
            esvazia()
            parcial.delete()
            resultado = "INSTALAÇÃO CANCELADA"
            resultadoOk = false
            NLog.add("instalar", "${job.rotulo}: cancelada")
            false
        } catch (e: Falha) {
            esvazia()
            resultado = "NÃO DEU PRA INSTALAR A ${job.nomeCurto}: ${e.message}"
            resultadoOk = false
            NLog.add("instalar", "${job.rotulo}: falhou - ${e.message}")
            InstalaService.avisaFim(app, false, "NÃO DEU PRA INSTALAR A ${job.nomeCurto}", e.message ?: "")
            false
        } catch (e: Throwable) {
            esvazia()
            resultado = "ERRO NA ${job.nomeCurto}: ${e.message ?: e.javaClass.simpleName}"
            resultadoOk = false
            NLog.add("instalar", "${job.rotulo}: erro - $e")
            InstalaService.avisaFim(app, false, "ERRO AO INSTALAR A ${job.nomeCurto}", e.message ?: e.javaClass.simpleName)
            false
        } finally {
            atual = null
            fase = Fase.PARADO
            mudou()
        }
    }

    /** o app antigo usava o gerenciador de downloads do Android: cancela o que tiver sobrado la pra nao brigar pelo arquivo */
    private fun migraDownloadAntigo(app: Context, nome: String) {
        try {
            val sp = app.getSharedPreferences("baixador", Context.MODE_PRIVATE)
            if (sp.contains(nome)) Baixador.cancelar(app, nome)
        } catch (_: Throwable) {}
    }

    private fun dorme(ms: Long) {
        var falta = ms
        while (falta > 0) {
            if (cancelar) throw Cancelado()
            val p = minOf(falta, 300L)
            try { Thread.sleep(p) } catch (_: InterruptedException) {}
            falta -= p
        }
    }

    private fun baixa(app: Context, job: Job, onMuda: () -> Unit): File {
        val pasta = Baixador.pasta(app)
        val final = File(pasta, job.nomeZip)
        val parcial = File(pasta, job.nomeZip + ".part")
        // o zip completo ja esta aqui (o Android fechou o app no meio da extracao): nao baixa de novo
        if (final.isFile && final.length() > 0) {
            NLog.add("instalar", "${job.rotulo}: o arquivo ja estava baixado, so extraindo")
            return final
        }
        var tentativa = 0
        while (true) {
            if (cancelar) throw Cancelado()
            try {
                val t = transfere(job.url, parcial, pasta, onMuda)
                if (t <= 0 || parcial.length() >= t) {
                    if (!parcial.renameTo(final)) throw Falha("NÃO CONSEGUI SALVAR O ARQUIVO BAIXADO.")
                    NLog.add("instalar", "${job.rotulo}: baixou ${Baixador.formatar(final.length())}")
                    return final
                }
                throw IOException("a conexão fechou antes do fim")
            } catch (e: HttpErro) {
                when (e.codigo) {
                    401, 403 -> throw Falha("O SERVIDOR RECUSOU A SUA KEY (HTTP ${e.codigo}). ABRA O APP E ENTRE DE NOVO.")
                    404 -> throw Falha("O ARQUIVO NÃO ESTÁ NO SERVIDOR (HTTP 404).")
                }
                val tentaDeNovo = e.codigo == 408 || e.codigo == 429 || e.codigo in 500..599
                tentativa++
                if (!tentaDeNovo || tentativa > 40) throw Falha("O SERVIDOR RESPONDEU HTTP ${e.codigo}. TENTE DE NOVO MAIS TARDE.")
                espera("SERVIDOR OCUPADO (HTTP ${e.codigo}) · TENTANDO DE NOVO ($tentativa)", tentativa, onMuda)
            } catch (e: IOException) {
                tentativa++
                if (tentativa > 40) throw Falha("A INTERNET NÃO SE MANTEVE. TOQUE EM INSTALAR DE NOVO: CONTINUA DE ONDE PAROU.")
                espera("SEM INTERNET OU A CONEXÃO CAIU · TENTANDO DE NOVO ($tentativa)", tentativa, onMuda)
            }
        }
    }

    private fun espera(msg: String, tentativa: Int, onMuda: () -> Unit) {
        fase = Fase.ESPERANDO
        aviso = msg
        mudou(); onMuda()
        dorme(minOf(2000L * tentativa, 20000L))
    }

    /** baixa (ou continua baixando) pro arquivo parcial. Devolve o tamanho total (-1 se o servidor nao disse) */
    private fun transfere(url: String, parcial: File, pasta: File, onMuda: () -> Unit): Long {
        val tem = if (parcial.isFile) parcial.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", "Nocry")
            c.setRequestProperty("X-Key", Prefs.licKey.orEmpty())
            if (tem > 0) c.setRequestProperty("Range", "bytes=$tem-")

            val code = c.responseCode
            var inicio = 0L
            val tot: Long
            when (code) {
                206 -> {
                    inicio = tem
                    val doRange = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    tot = if (doRange > 0) doRange else if (c.contentLengthLong > 0) tem + c.contentLengthLong else -1L
                }
                200 -> tot = if (c.contentLengthLong > 0) c.contentLengthLong else -1L
                416 -> {
                    val t = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    if (t > 0 && tem == t) return t
                    parcial.delete()
                    throw IOException("o servidor recusou a retomada; recomeçando do zero")
                }
                else -> throw HttpErro(code)
            }

            // espaco: o que falta baixar + mais ou menos o mesmo tanto pra extrair
            if (tot > 0) {
                val livre = try { StatFs(pasta.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
                val precisa = (tot - inicio) + tot + 50L * 1048576L
                if (livre < precisa) throw Falha("SEM ESPAÇO NO CELULAR: LIBERE PELO MENOS ${Baixador.formatar(precisa - livre)}.")
            }

            fase = Fase.BAIXANDO
            feito = inicio
            total = tot
            aviso = ""
            mudou(); onMuda()

            FileOutputStream(parcial, inicio > 0).use { out ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(256 * 1024)
                    var f = inicio
                    var ultimo = 0L
                    while (true) {
                        if (cancelar) throw Cancelado()
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        f += n
                        val agora = System.currentTimeMillis()
                        if (agora - ultimo >= 500) {
                            ultimo = agora
                            feito = f
                            mudou(); onMuda()
                        }
                    }
                    feito = f
                }
            }
            return tot
        } finally {
            c.disconnect()
        }
    }

    /** conta quantos bytes do zip ja foram lidos (pra mostrar a % da extracao) */
    private class Contador(ins: InputStream, val onLer: (Long) -> Unit) : FilterInputStream(ins) {
        private var lidos = 0L
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) { lidos++; onLer(lidos) }
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { lidos += r; onLer(lidos) }
            return r
        }
    }

    private fun extrai(arquivo: File, destino: String, raiz: String, onMuda: () -> Unit) {
        val destinoDir = File(destino)
        destinoDir.mkdirs()
        val tempDir = File(destinoDir, "_temp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val base = tempDir.canonicalPath + File.separator
        var ultimo = 0L
        try {
            val cont = Contador(FileInputStream(arquivo).buffered(1 shl 16)) { lidos ->
                val agora = System.currentTimeMillis()
                if (agora - ultimo >= 500) {
                    ultimo = agora
                    feito = lidos
                    mudou(); onMuda()
                }
            }
            ZipInputStream(cont).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (cancelar) throw Cancelado()
                    val nome = entry.name.replace('\\', '/')
                    val caminho = if (raiz.isNotEmpty() && nome.startsWith("$raiz/")) nome.substringAfter("$raiz/") else nome
                    if (caminho.isNotEmpty() && !entry.isDirectory && !caminho.endsWith("/")) {
                        val f = File(tempDir, caminho)
                        // protege contra zip com "../" tentando escrever fora da pasta
                        if (f.canonicalPath.startsWith(base)) {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n <= 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            feito = total
            mudou(); onMuda()

            // poe tudo dentro de "android" (troca a pasta antiga pela nova)
            val pastaAndroid = File(destinoDir, "android")
            pastaAndroid.mkdirs()
            tempDir.listFiles()?.forEach { f ->
                val alvo = File(pastaAndroid, f.name)
                if (alvo.exists()) alvo.deleteRecursively()
                if (!f.renameTo(alvo)) {
                    f.copyRecursively(alvo, overwrite = true)
                    f.deleteRecursively()
                }
            }
        } catch (e: Cancelado) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            arquivo.delete()
            throw Falha("O ARQUIVO VEIO ESTRAGADO. TOQUE EM INSTALAR DE NOVO.")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

// =====================================================================================
// Servico da instalacao (primeiro plano, com notificacao de progresso e botao de cancelar)
// =====================================================================================
class InstalaService : Service() {
    companion object {
        const val CANCELAR = "com.nocry.app.INSTALA_CANCELAR"
        private const val ID_PROG = 5001
        private const val ID_FIM = 5002
        @Volatile var rodando = false

        fun inicia(c: Context) {
            val ctx = c.applicationContext
            try {
                ctx.startForegroundService(Intent(ctx, InstalaService::class.java))
            } catch (t: Throwable) {
                NLog.add("instalar", "nao consegui ligar o servico: $t")
            }
        }

        /** tinha instalacao na fila e o Android fechou o app: continua (chamado ao abrir o app e pelo job) */
        fun retoma(c: Context) {
            if (!rodando && Instalador.fila().isNotEmpty()) {
                NLog.add("instalar", "retomando a fila que ficou parada")
                inicia(c)
            }
        }

        fun avisaFim(c: Context, ok: Boolean, titulo: String, texto: String) {
            try {
                val nm = c.getSystemService(NotificationManager::class.java) ?: return
                val abre = PendingIntent.getActivity(
                    c, 12, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(c, Notif.CH_ALERT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(titulo)
                    .setContentText(texto)
                    .setStyle(Notification.BigTextStyle().bigText(texto))
                    .setContentIntent(abre)
                    .setAutoCancel(true)
                    .build()
                nm.notify(ID_FIM + (if (ok) 0 else 1), n)
            } catch (_: Exception) {}
        }
    }

    @Volatile private var th: Thread? = null
    @Volatile private var ultimoStart = 0
    private val trava = Any()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var ultimaNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoStart = startId
        // o Android exige mostrar a notificacao logo que o servico liga
        if (!primeiroPlano()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == CANCELAR) Instalador.cancelarTudo()
        synchronized(trava) {
            if (th == null) {
                if (Instalador.fila().isEmpty()) {
                    encerra()
                    return START_NOT_STICKY
                }
                segura()
                rodando = true
                val t = Thread { trabalha() }
                t.name = "nocry-instalar"
                th = t
                t.start()
            }
        }
        return START_STICKY
    }

    private fun trabalha() {
        Instalador.cancelar = false
        try {
            while (true) {
                // pega o proximo; se acabou, larga a vez dentro da trava (um pedido novo abre outra thread)
                val job = synchronized(trava) {
                    val j = if (Instalador.cancelar) null else Instalador.fila().firstOrNull()
                    if (j == null) { th = null; rodando = false }
                    j
                } ?: break
                Instalador.executa(applicationContext, job) { atualiza() }
            }
        } catch (t: Throwable) {
            NLog.add("instalar", "servico: $t")
            synchronized(trava) { th = null; rodando = false }
        } finally {
            Instalador.cancelar = false
            Instalador.mudou()
            synchronized(trava) { if (th == null) encerra() }
        }
    }

    /** mantem o celular acordado e o wi-fi ligado enquanto baixa (com a tela desligada tambem) */
    private fun segura() {
        try {
            if (wake == null) {
                val pm = getSystemService(PowerManager::class.java)
                wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nocry:instalar")?.also {
                    it.setReferenceCounted(false)
                    it.acquire(3 * 60 * 60 * 1000L)
                }
            }
        } catch (t: Throwable) { NLog.add("instalar", "wakelock: $t") }
        try {
            if (wifi == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                wifi = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nocry:instalar")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            }
        } catch (t: Throwable) { NLog.add("instalar", "wifilock: $t") }
    }

    private fun solta() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wake = null
        try { wifi?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wifi = null
    }

    /** desliga, a nao ser que tenha chegado um pedido novo nesse meio tempo */
    private fun encerra() {
        if (!stopSelfResult(ultimoStart)) return
        solta()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
    }

    private fun notificacao(): Notification {
        val j = Instalador.atual ?: Instalador.fila().firstOrNull()
        val p = Instalador.percent()
        val titulo = if (j == null) "NOCRY · INSTALAÇÃO" else
            (if (Instalador.fase == Instalador.Fase.EXTRAINDO) "EXTRAINDO A " else "INSTALANDO A ") + j.rotulo
        val depois = Instalador.fila().filter { it.id != j?.id }
        val abre = PendingIntent.getActivity(
            this, 13, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancela = PendingIntent.getForegroundService(
            this, 14, Intent(this, InstalaService::class.java).setAction(CANCELAR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, Notif.CH_INST)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(Instalador.textoFase().ifEmpty { "PREPARANDO..." })
            .setContentIntent(abre)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (p >= 0) p else 0, p < 0 && Instalador.fase != Instalador.Fase.ESPERANDO)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "CANCELAR", cancela).build())
        if (depois.isNotEmpty()) b.setSubText("DEPOIS: " + depois.joinToString(" + ") { it.nomeCurto })
        return b.build()
    }

    private fun primeiroPlano(): Boolean = try {
        val n = notificacao()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_PROG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_PROG, n)
        true
    } catch (t: Throwable) {
        // o Android nao deixou ligar agora (ex.: app parado em segundo plano): a fila fica salva e continua quando abrir o app
        NLog.add("instalar", "nao deu pra ficar em primeiro plano: $t")
        false
    }

    private fun atualiza() {
        val agora = System.currentTimeMillis()
        if (agora - ultimaNotif < 700) return
        ultimaNotif = agora
        try { getSystemService(NotificationManager::class.java)?.notify(ID_PROG, notificacao()) } catch (_: Exception) {}
    }

    /** fecharam o app nos recentes no meio da instalacao: se o Android matar tudo junto, volta sozinho */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Instalador.fila().isNotEmpty()) SegundoPlano.agendaVolta(applicationContext, 1500L, InstalaService::class.java)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        solta()
        super.onDestroy()
    }
}
