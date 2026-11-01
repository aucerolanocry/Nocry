package com.nocry.app

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object Baixador {

    interface Ouvinte {
        fun aoProgredir(baixado: Long, total: Long) {}
        fun aoAguardar(motivo: String) {}
        fun aoVerificar() {}
        fun aoTerminar(arquivo: File) {}
        fun aoFalhar(motivo: String) {}
    }

    private const val PREFS = "baixador"
    private val main = Handler(Looper.getMainLooper())

    fun pasta(ctx: Context): File {
        val dir = ctx.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: ctx.applicationContext.filesDir
        dir.mkdirs()
        return dir
    }

    fun baixar(
        ctx: Context,
        url: String,
        nome: String,
        tamanhoEsperado: Long = -1L,
        sha256: String? = null,
        soWifi: Boolean = false,
        ouvinte: Ouvinte
    ): Long {
        NLog.add("download", "pedido: $nome (esperado ${formatar(tamanhoEsperado)}, soWifi=$soWifi, host=${Uri.parse(url).host})")
        return iniciar(ctx, url, nome, tamanhoEsperado, sha256, soWifi, comLog(nome, ouvinte))
    }

    private fun comLog(nome: String, o: Ouvinte): Ouvinte = object : Ouvinte {
        private var ultimoPct = -1
        private var ultimaPausa = ""

        override fun aoProgredir(baixado: Long, total: Long) {
            if (total > 0) {
                val pct = (baixado * 100 / total).toInt() / 10 * 10
                if (pct != ultimoPct) {
                    ultimoPct = pct
                    NLog.add("download", "$nome: $pct% (${formatar(baixado)} de ${formatar(total)})")
                }
            }
            o.aoProgredir(baixado, total)
        }

        override fun aoAguardar(motivo: String) {
            if (motivo != ultimaPausa) {
                ultimaPausa = motivo
                NLog.add("download", "$nome: pausado - $motivo")
            }
            o.aoAguardar(motivo)
        }

        override fun aoVerificar() {
            NLog.add("download", "$nome: baixou tudo, conferindo SHA-256...")
            o.aoVerificar()
        }

        override fun aoTerminar(arquivo: File) {
            NLog.add("download", "$nome: OK, ${formatar(arquivo.length())} conferido em ${arquivo.name}")
            o.aoTerminar(arquivo)
        }

        override fun aoFalhar(motivo: String) {
            NLog.add("download-erro", "$nome: $motivo")
            o.aoFalhar(motivo)
        }
    }

    private fun iniciar(
        ctx: Context,
        url: String,
        nome: String,
        tamanhoEsperado: Long,
        sha256: String?,
        soWifi: Boolean,
        ouvinte: Ouvinte
    ): Long {
        val app = ctx.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val destino = File(pasta(app), nome)

        if (!url.startsWith("https://", ignoreCase = true)) {
            main.post { ouvinte.aoFalhar("O link precisa começar com https://") }
            return -1L
        }

        val idAntigo = prefs.getLong(nome, -1L)
        if (idAntigo != -1L) {
            when (estado(dm, idAntigo)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                DownloadManager.STATUS_SUCCESSFUL -> {
                    NLog.add("download", "$nome: reconectou no download que ja existia (id $idAntigo)")
                    acompanhar(app, dm, idAntigo, destino, tamanhoEsperado, sha256, ouvinte)
                    return idAntigo
                }
                else -> {
                    try { dm.remove(idAntigo) } catch (e: Exception) {}
                    prefs.edit().remove(nome).apply()
                }
            }
        }

        if (destino.isFile && tamanhoEsperado > 0 && destino.length() == tamanhoEsperado) {
            verificar(destino, tamanhoEsperado, sha256, ouvinte)
            return -1L
        }

        if (destino.exists()) {
            NLog.add("download", "$nome: apagou sobra de download incompleto (${formatar(destino.length())})")
            destino.delete()
        }

        if (tamanhoEsperado > 0) {
            val livre = try { StatFs(pasta(app).path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            val precisa = (tamanhoEsperado * 1.05).toLong()
            if (livre < precisa) {
                main.post {
                    ouvinte.aoFalhar(
                        "Sem espaço: precisa de ${formatar(precisa)} livres e só tem ${formatar(livre)}."
                    )
                }
                return -1L
            }
        }

        val pedido = DownloadManager.Request(Uri.parse(url))
            .setTitle(nome)
            .setDescription("Baixando…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!soWifi)
            .setAllowedOverRoaming(!soWifi)
            .setDestinationUri(Uri.fromFile(destino))

        val id = try {
            dm.enqueue(pedido)
        } catch (e: Exception) {
            NLog.add("download", "$nome: Gerenciador de downloads indisponivel (${e.message}); baixando direto pelo app")
            baixarDireto(app, url, nome, tamanhoEsperado, sha256, soWifi, ouvinte)
            return -2L
        }
        prefs.edit().putLong(nome, id).apply()
        NLog.add("download", "$nome: entregue ao Android (id $id), destino ${destino.parent}")
        acompanhar(app, dm, id, destino, tamanhoEsperado, sha256, ouvinte)
        return id
    }

    fun cancelar(ctx: Context, nome: String) {
        val app = ctx.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(nome, -1L)
        if (id != -1L) try { dm.remove(id) } catch (e: Exception) {}
        prefs.edit().remove(nome).apply()
        if (ativos.containsKey(nome)) cancelados[nome] = true
        File(pasta(app), nome).delete()
        File(pasta(app), "$nome.part").delete()
    }

    fun sha256(arq: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(arq).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun formatar(bytes: Long): String {
        if (bytes < 0) return "?"
        val mb = bytes / 1048576.0
        return if (mb >= 1024) "%.2f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }

    private class Foto(val status: Int, val baixado: Long, val total: Long, val motivo: Int)

    private fun foto(dm: DownloadManager, id: Long): Foto? {
        val c = try { dm.query(DownloadManager.Query().setFilterById(id)) } catch (e: Exception) { null }
        if (c == null) return null
        return try {
            if (!c.moveToFirst()) null
            else Foto(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        } finally {
            c.close()
        }
    }

    private fun estado(dm: DownloadManager, id: Long): Int = foto(dm, id)?.status ?: -1

    private fun acompanhar(
        app: Context, dm: DownloadManager, id: Long, destino: File,
        tamanho: Long, sha: String?, o: Ouvinte
    ) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tick = object : Runnable {
            override fun run() {
                val f = foto(dm, id)
                if (f == null) {
                    prefs.edit().remove(destino.name).apply()
                    o.aoFalhar("O download foi cancelado ou removido.")
                    return
                }
                when (f.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> verificar(destino, tamanho, sha, o)
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(destino.name).apply()
                        try { dm.remove(id) } catch (e: Exception) {}
                        destino.delete()
                        o.aoFalhar(explicarErro(f.motivo))
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        o.aoProgredir(f.baixado, if (tamanho > 0) tamanho else f.total)
                        o.aoAguardar(explicarPausa(f.motivo))
                        main.postDelayed(this, 1000)
                    }
                    else -> {
                        o.aoProgredir(f.baixado, if (tamanho > 0) tamanho else f.total)
                        main.postDelayed(this, 700)
                    }
                }
            }
        }
        main.post(tick)
    }

    private fun verificar(arq: File, tamanho: Long, sha: String?, o: Ouvinte) {
        Thread {
            val erro: String? = when {
                !arq.isFile -> "O arquivo não foi encontrado depois do download."
                tamanho > 0 && arq.length() != tamanho ->
                    "Tamanho errado: veio ${arq.length()} bytes e eram esperados $tamanho."
                else -> null
            }
            if (erro != null) {
                main.post { o.aoFalhar(erro) }
                return@Thread
            }
            if (!sha.isNullOrBlank()) {
                main.post { o.aoVerificar() }
                val real = try { sha256(arq) } catch (e: Exception) { "" }
                if (!real.equals(sha.trim(), ignoreCase = true)) {
                    arq.delete()
                    main.post { o.aoFalhar("O arquivo veio corrompido (SHA-256 não bate).") }
                    return@Thread
                }
            }
            main.post { o.aoTerminar(arq) }
        }.start()
    }

    private class Cancelado : RuntimeException("cancelado")
    private class HttpErro(val codigo: Int) : IOException("HTTP $codigo")

    private val ativos = ConcurrentHashMap<String, Boolean>()
    private val cancelados = ConcurrentHashMap<String, Boolean>()

    private fun baixarDireto(app: Context, url: String, nome: String, tamanho: Long, sha: String?, soWifi: Boolean, o: Ouvinte) {
        if (ativos.putIfAbsent(nome, true) != null) {
            NLog.add("download", "$nome: ja esta baixando, ignorei o pedido repetido")
            return
        }
        cancelados.remove(nome)
        val destino = File(pasta(app), nome)
        val parcial = File(pasta(app), "$nome.part")
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        Thread {
            var tentativa = 0
            try {
                while (true) {
                    if (cancelados.containsKey(nome)) throw Cancelado()
                    if (soWifi && cm.isActiveNetworkMetered) {
                        main.post { o.aoAguardar("Esperando conectar no Wi-Fi.") }
                        Thread.sleep(3000)
                        continue
                    }
                    try {
                        val total = transferir(url, parcial, nome, tamanho, o)
                        if (total <= 0 || parcial.length() >= total) {
                            val feito = parcial.length()
                            main.post { o.aoProgredir(feito, feito) }
                            if (destino.exists()) destino.delete()
                            if (!parcial.renameTo(destino)) throw IOException("não consegui renomear o arquivo baixado")
                            verificar(destino, tamanho, sha, o)
                            return@Thread
                        }
                        throw IOException("a conexão fechou antes do fim")
                    } catch (e: HttpErro) {
                        val tentaDeNovo = e.codigo == 408 || e.codigo == 429 || e.codigo in 500..599
                        tentativa++
                        if (!tentaDeNovo || tentativa > 30) {
                            main.post { o.aoFalhar(explicarErro(e.codigo)) }
                            return@Thread
                        }
                        main.post { o.aoAguardar("${explicarErro(e.codigo)} Tentativa $tentativa de 30.") }
                        Thread.sleep(minOf(2000L * tentativa, 15000L))
                    } catch (e: IOException) {
                        tentativa++
                        if (tentativa > 30) {
                            main.post { o.aoFalhar("A conexão não se manteve (${e.message}). Desisti depois de 30 tentativas; chame de novo pra continuar de onde parou.") }
                            return@Thread
                        }
                        main.post { o.aoAguardar("Conexão caiu (${e.message}). Tentativa $tentativa de 30.") }
                        Thread.sleep(minOf(2000L * tentativa, 15000L))
                    }
                }
            } catch (e: Cancelado) {
                parcial.delete()
                main.post { o.aoFalhar("Download cancelado.") }
            } catch (e: Exception) {
                main.post { o.aoFalhar("Erro no download: ${e.message}") }
            } finally {
                ativos.remove(nome)
                cancelados.remove(nome)
            }
        }.start()
    }

    private fun transferir(url: String, parcial: File, nome: String, tamanhoEsperado: Long, o: Ouvinte): Long {
        val tem = if (parcial.isFile) parcial.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", "Nocry")
            if (tem > 0) c.setRequestProperty("Range", "bytes=$tem-")

            val code = c.responseCode
            var inicio = 0L
            val total: Long
            when (code) {
                206 -> {
                    inicio = tem
                    val doRange = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    total = if (doRange > 0) doRange else if (c.contentLengthLong > 0) tem + c.contentLengthLong else tamanhoEsperado
                }
                200 -> {
                    total = if (c.contentLengthLong > 0) c.contentLengthLong else tamanhoEsperado
                }
                416 -> {
                    val t = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    if (t > 0 && tem == t) return t
                    parcial.delete()
                    throw IOException("o servidor recusou a retomada (416); recomeço do zero")
                }
                else -> throw HttpErro(code)
            }

            FileOutputStream(parcial, inicio > 0).use { out ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(256 * 1024)
                    var feito = inicio
                    var ultimo = 0L
                    while (true) {
                        if (cancelados.containsKey(nome)) throw Cancelado()
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        feito += n
                        val agora = System.currentTimeMillis()
                        if (agora - ultimo >= 500) {
                            ultimo = agora
                            main.post { o.aoProgredir(feito, if (total > 0) total else -1) }
                        }
                    }
                }
            }
            return total
        } finally {
            c.disconnect()
        }
    }

    private fun explicarPausa(r: Int) = when (r) {
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Conexão instável: vou tentar de novo sozinho."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Sem internet. Continua assim que a rede voltar."
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Esperando conectar no Wi-Fi."
        else -> "Pausado pelo sistema. Continua sozinho."
    }

    private fun explicarErro(r: Int): String = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Acabou o espaço no celular."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Armazenamento indisponível."
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Já existia um arquivo com esse nome."
        DownloadManager.ERROR_FILE_ERROR -> "Erro ao gravar o arquivo no celular."
        DownloadManager.ERROR_CANNOT_RESUME -> "O servidor não deixou retomar o download."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "O link redireciona demais."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "A conexão caiu no meio. Tente de novo."
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "O servidor respondeu algo que o Android não entende."
        401, 403 -> "O servidor recusou (HTTP $r): link expirado ou sem permissão."
        404 -> "O servidor não achou o arquivo (HTTP 404)."
        416 -> "O servidor rejeitou a retomada (HTTP 416)."
        429 -> "Muitas requisições (HTTP 429). Espere um pouco."
        in 500..599 -> "O servidor está com problema (HTTP $r)."
        in 100..599 -> "O servidor respondeu HTTP $r."
        else -> "Falha desconhecida (código $r)."
    }
}
