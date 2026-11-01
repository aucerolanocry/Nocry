package com.nocry.app

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object Downloader {
    private const val DEST_SUJA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
    private const val DEST_LIMPA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val DEST_SUJA_MAX = "/storage/emulated/0/MIUI/BugReportCache/image"
    private const val DEST_LIMPA_MAX = "/storage/emulated/0/MIUI/BugReportCache/draft"

    // Instala o arquivo que já está na pasta Downloads
    fun instalarDoDownload(nomeArquivo: String, destino: String, pastaRaizEsperada: String, onProgress: (String) -> Unit): Boolean {
        return try {
            val app = Sys.app
            val arquivo = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nomeArquivo)
            
            if (!arquivo.exists()) {
                onProgress("ARQUIVO NÃO ENCONTRADO NA PASTA DOWNLOADS!")
                return false
            }

            onProgress("EXTRAINDO...")
            val destinoDir = File(destino)
            destinoDir.mkdirs()

            ZipInputStream(arquivo.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nome = entry.name
                    val prefixo = if (pastaRaizEsperada.isNotEmpty() && nome.startsWith("$pastaRaizEsperada/")) "$pastaRaizEsperada/" else ""
                    val caminhoLimpo = if (prefixo.isNotEmpty()) nome.substringAfter(prefixo) else nome
                    
                    if (caminhoLimpo.isNotEmpty() && !caminhoLimpo.endsWith("/")) {
                        val f = File(destinoDir, caminhoLimpo)
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
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
            onProgress("INSTALADO COM SUCESSO!")
            true
        } catch (e: Exception) {
            onProgress("FALHA: ${e.message}")
            false
        }
    }

    fun pastasInstaladas(ff: String): Pair<Boolean, Boolean> {
        return if (ff == "max") {
            Pair(File("$DEST_SUJA_MAX/android").exists(), File("$DEST_LIMPA_MAX/android").exists())
        } else {
            Pair(File("$DEST_SUJA_NORMAL/android").exists(), File("$DEST_LIMPA_NORMAL/android").exists())
        }
    }
}
