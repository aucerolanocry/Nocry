package com.nocry.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Downloader {
    private const val PASTA_A_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
    private const val PASTA_B_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val PASTA_A_MAX = "/storage/emulated/0/MIUI/BugReportCache/image"
    private const val PASTA_B_MAX = "/storage/emulated/0/MIUI/BugReportCache/draft"

    private const val URL_PASTA_A = "https://github.com/aucerolanocry/ff-installer/releases/download/v3/sujas.zip"
    private const val URL_PASTA_B = "https://github.com/aucerolanocry/ff-installer/releases/download/v3/android.zip"
    private const val URL_PASTA_A_MAX = "https://github.com/aucerolanocry/ff-installer/releases/download/v3/sujas.zip"
    private const val URL_PASTA_B_MAX = "https://github.com/aucerolanocry/ff-installer/releases/download/v3/android.zip"

    private fun baixar(url: String, destino: File, onProgress: (Long, Long) -> Unit): Boolean {
        return try {
            val conexao = URL(url).openConnection() as HttpURLConnection
            conexao.connectTimeout = 15000
            conexao.readTimeout = 30000
            conexao.instanceFollowRedirects = true
            conexao.connect()

            if (conexao.responseCode != 200) {
                NLog.add("download", "HTTP ${conexao.responseCode}")
                return false
            }

            val total = conexao.contentLengthLong
            var baixado = 0L
            destino.parentFile?.mkdirs()
            conexao.inputStream.use { input ->
                FileOutputStream(destino).use { output ->
                    val buffer = ByteArray(8192)
                    var lido: Int
                    while (input.read(buffer).also { lido = it } > 0) {
                        output.write(buffer, 0, lido)
                        baixado += lido
                        onProgress(baixado, total)
                    }
                }
            }
            conexao.disconnect()
            NLog.add("download", "baixado: ${destino.name}")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha: ${e.message}")
            false
        }
    }

    private fun extrair(zipFile: File, destino: String): Boolean {
        return try {
            File(destino).mkdirs()
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nome = entry.name
                    if (nome.endsWith("/")) {
                        File(destino, nome).mkdirs()
                    } else {
                        val arquivo = File(destino, nome)
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
            NLog.add("download", "extraído em: $destino")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha extrair: ${e.message}")
            false
        }
    }

    fun baixarPastaA(ff: String, onProgress: (String) -> Unit): Boolean {
        val url = if (ff == "max") URL_PASTA_A_MAX else URL_PASTA_A
        val destino = if (ff == "max") PASTA_A_MAX else PASTA_A_NORMAL
        val zipTemp = File(Sys.app.cacheDir, "pasta_a.zip")
        onProgress("BAIXANDO PASTA A...")
        if (!baixar(url, zipTemp) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA A... $pct%")
            }) {
            onProgress("FALHA AO BAIXAR PASTA A")
            return false
        }
        onProgress("EXTRAINDO PASTA A...")
        val destinoPai = File(destino).parent ?: destino
        if (!extrair(zipTemp, destinoPai)) {
            onProgress("FALHA AO EXTRAIR PASTA A")
            zipTemp.delete()
            return false
        }
        zipTemp.delete()
        onProgress("PASTA A INSTALADA!")
        return true
    }

    fun baixarPastaB(ff: String, onProgress: (String) -> Unit): Boolean {
        val url = if (ff == "max") URL_PASTA_B_MAX else URL_PASTA_B
        val destino = if (ff == "max") PASTA_B_MAX else PASTA_B_NORMAL
        val zipTemp = File(Sys.app.cacheDir, "pasta_b.zip")
        onProgress("BAIXANDO PASTA B...")
        if (!baixar(url, zipTemp) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA B... $pct%")
            }) {
            onProgress("FALHA AO BAIXAR PASTA B")
            return false
        }
        onProgress("EXTRAINDO PASTA B...")
        val destinoPai = File(destino).parent ?: destino
        if (!extrair(zipTemp, destinoPai)) {
            onProgress("FALHA AO EXTRAIR PASTA B")
            zipTemp.delete()
            return false
        }
        zipTemp.delete()
        onProgress("PASTA B INSTALADA!")
        return true
    }

    fun pastasInstaladas(ff: String): Pair<Boolean, Boolean> {
        val a = if (ff == "max") PASTA_A_MAX else PASTA_A_NORMAL
        val b = if (ff == "max") PASTA_B_MAX else PASTA_B_NORMAL
        return Pair(File("$a/android").exists(), File("$b/android").exists())
    }
}
