package com.nocry.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Downloader {
    private const val BASE_URL = "https://nocry-download.osreaisnocry.workers.dev"

    private const val DEST_SUJA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
    private const val DEST_LIMPA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val DEST_SUJA_MAX = "/storage/emulated/0/MIUI/BugReportCache/image"
    private const val DEST_LIMPA_MAX = "/storage/emulated/0/MIUI/BugReportCache/draft"

    fun baixarComAndroid(nomeArquivo: String, destino: String, pastaRaizEsperada: String, onProgress: (String) -> Unit): Boolean {
        return try {
            val app = Sys.app
            val url = URL("$BASE_URL/$nomeArquivo")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode != 200) {
                onProgress("HTTP ${conn.responseCode}")
                return false
            }

            val total = conn.contentLengthLong
            var baixado = 0L
            
            val destinoDir = File(destino)
            destinoDir.mkdirs()
            
            // Salva o zip na pasta de cache
            val zipFile = File(app.cacheDir, nomeArquivo)
            onProgress("BAIXANDO...")

            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(1024 * 1024) // 1 MB
                    var lido: Int
                    while (input.read(buffer).also { lido = it } > 0) {
                        output.write(buffer, 0, lido)
                        output.flush()
                        baixado += lido
                        val pct = if (total > 0) (baixado * 100 / total) else 0
                        onProgress("BAIXANDO... $pct%")
                    }
                }
            }
            conn.disconnect()
            onProgress("DOWNLOAD COMPLETO! EXTRAINDO...")

            // Extrai direto (sem temp)
            val ok = extrairDireto(zipFile, destino, pastaRaizEsperada)
            zipFile.delete()
            
            onProgress(if (ok) "INSTALADO COM SUCESSO!" else "FALHA AO EXTRAIR")
            ok
        } catch (e: Exception) {
            onProgress("FALHA: ${e.message}")
            false
        }
    }

    private fun extrairDireto(zipFile: File, destino: String, pastaRaizEsperada: String?): Boolean {
        return try {
            val destinoDir = File(destino)
            destinoDir.mkdirs()
            
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nome = entry.name
                    val prefixo = if (pastaRaizEsperada != null && nome.startsWith("$pastaRaizEsperada/")) "$pastaRaizEsperada/" else ""
                    val caminhoLimpo = if (prefixo.isNotEmpty()) nome.substringAfter(prefixo) else nome
                    
                    if (caminhoLimpo.isNotEmpty() && !caminhoLimpo.endsWith("/")) {
                        val arquivo = File(destinoDir, caminhoLimpo)
                        arquivo.parentFile?.mkdirs()
                        FileOutputStream(arquivo).use { out ->
                            val buffer = ByteArray(8192)
                            var lido: Int
                            while (zip.read(buffer).also { lido = it } > 0) {
                                out.write(buffer, 0, lido)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            NLog.add("download", "extraído e renomeado em: $destino")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha extrair: ${e.message}")
            false
        }
    }

    // ... (Funções antigas mantidas pra não quebrar o app)
    fun baixarPastaSujaNormal(onProgress: (String) -> Unit): Boolean { return false }
    fun baixarPastaLimpaNormal(onProgress: (String) -> Unit): Boolean { return false }
    fun baixarPastaSujaMax(onProgress: (String) -> Unit): Boolean { return false }
    fun baixarPastaLimpaMax(onProgress: (String) -> Unit): Boolean { return false }
    fun baixarScriptsNormal(onProgress: (String) -> Unit): Boolean { return false }
    fun baixarScriptsMax(onProgress: (String) -> Unit): Boolean { return false }

    fun pastasInstaladas(ff: String): Pair<Boolean, Boolean> {
        return if (ff == "max") {
            Pair(File("$DEST_SUJA_MAX/android").exists(), File("$DEST_LIMPA_MAX/android").exists())
        } else {
            Pair(File("$DEST_SUJA_NORMAL/android").exists(), File("$DEST_LIMPA_NORMAL/android").exists())
        }
    }
}
