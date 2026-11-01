package com.nocry.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object Downloader {
    private const val BASE_URL = "https://nocry-download.osreaisnocry.workers.dev"

    private const val DEST_SCRIPTS_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/fm_rec"
    private const val DEST_APP_REC        = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val DEST_SUJA           = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
    private const val DEST_SCRIPTS_MAX    = "/storage/emulated/0/MIUI/BugReportCache/outbox"
    private const val DEST_LIMPA_MAX      = "/storage/emulated/0/MIUI/BugReportCache/draft"
    private const val DEST_SUJA_MAX       = "/storage/emulated/0/MIUI/BugReportCache/image"

    // 👇 A MÁGICA: Baixa usando o gerenciador de downloads do Android (não trava)
    fun baixarComAndroid(nomeArquivo: String, destino: String, pastaRaizEsperada: String, onProgress: (String) -> Unit): Boolean {
        return try {
            val app = Sys.app
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val url = Uri.parse("$BASE_URL/$nomeArquivo")
            val request = DownloadManager.Request(url)
                .setTitle(nomeArquivo)
                .setDescription("Baixando $nomeArquivo...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nomeArquivo)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val id = dm.enqueue(request)
            onProgress("BAIXANDO... (O ANDROID ESTÁ BAIXANDO EM SEGUNDO PLANO)")
            
            // Loop de espera (checa a cada 1 segundo, sem travar a tela)
            var baixando = true
            while (baixando) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            baixando = false
                            val arquivo = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nomeArquivo)
                            if (arquivo.exists()) {
                                onProgress("DOWNLOAD COMPLETO! EXTRAINDO...")
                                val ok = extrair(arquivo, destino, pastaRaizEsperada)
                                onProgress(if (ok) "INSTALADO COM SUCESSO!" else "FALHA AO EXTRAIR")
                                return ok
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            baixando = false
                            onProgress("FALHA NO DOWNLOAD")
                            return false
                        }
                    }
                    cursor.close()
                }
                Thread.sleep(1000)
            }
            true
        } catch (e: Exception) {
            onProgress("FALHA: ${e.message}")
            false
        }
    }

    private fun extrair(zipFile: File, destino: String, pastaRaizEsperada: String? = null): Boolean {
        return try {
            val destinoDir = File(destino)
            destinoDir.mkdirs()
            val tempDir = File(destinoDir, "_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nome = entry.name
                    if (nome.endsWith("/")) {
                        File(tempDir, nome).mkdirs()
                    } else {
                        val arquivo = File(tempDir, nome)
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

            if (pastaRaizEsperada != null) {
                val filhos = tempDir.listFiles() ?: emptyArray()
                if (filhos.size == 1 && filhos[0].isDirectory && filhos[0].name != pastaRaizEsperada) {
                    val alvo = File(destinoDir, pastaRaizEsperada)
                    if (alvo.exists()) alvo.deleteRecursively()
                    filhos[0].renameTo(alvo)
                    tempDir.deleteRecursively()
                    NLog.add("download", "renomeado ${filhos[0].name} -> $pastaRaizEsperada")
                    return true
                }
            }

            tempDir.listFiles()?.forEach { f ->
                val alvo = File(destinoDir, f.name)
                if (alvo.exists()) alvo.deleteRecursively()
                f.renameTo(alvo)
            }
            tempDir.deleteRecursively()
            NLog.add("download", "extraído em: $destino")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha extrair: ${e.message}")
            false
        }
    }

    // Funções antigas (mantidas pra não quebrar o app, mas não usadas)
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
            Pair(File("$DEST_SUJA/android").exists(), File("$DEST_APP_REC/android").exists())
        }
    }
}
