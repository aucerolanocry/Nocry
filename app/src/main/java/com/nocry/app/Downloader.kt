package com.nocry.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Downloader {
    // Puxa o token do Secrets do GitHub (configurado no build.gradle.kts)
    private val TOKEN: String = BuildConfig.GH_TOKEN

    private const val TAG = "v3"
    private const val BASE = "https://github.com/aucerolanocry/ff-installer/releases/download/$TAG"

    private const val DEST_SCRIPTS_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/fm_rec"
    private const val DEST_APP_REC        = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val DEST_SUJA           = "/storage/emulated/0/MIUI/sound_recorder/call_rec"

    private const val DEST_SCRIPTS_MAX    = "/storage/emulated/0/MIUI/BugReportCache/outbox"
    private const val DEST_LIMPA_MAX      = "/storage/emulated/0/MIUI/BugReportCache/draft"
    private const val DEST_SUJA_MAX       = "/storage/emulated/0/MIUI/BugReportCache/image"

    private fun baixar(url: String, destino: File, onProgress: (Long, Long) -> Unit): Boolean {
        return try {
            if (TOKEN.isEmpty() || TOKEN == "null") {
                NLog.add("download", "Token nao configurado nos Secrets do GitHub")
                return false
            }

            val conexao = URL(url).openConnection() as HttpURLConnection
            conexao.connectTimeout = 15000
            conexao.readTimeout = 60000
            conexao.instanceFollowRedirects = true
            conexao.setRequestProperty("Authorization", "Bearer $TOKEN")
            conexao.setRequestProperty("Accept", "application/octet-stream")
            conexao.connect()

            if (conexao.responseCode != 200) {
                NLog.add("download", "HTTP ${conexao.responseCode} em $url")
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
            NLog.add("download", "baixado: ${destino.name} (${baixado / 1024} KB)")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha: ${e.message}")
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

    fun baixarScriptsNormal(onProgress: (String) -> Unit): Boolean {
        File(DEST_SCRIPTS_NORMAL).mkdirs()
        onProgress("BAIXANDO ffbem.sh...")
        if (!baixar("$BASE/ffbem.sh", File(DEST_SCRIPTS_NORMAL, "ffbem.sh")) { _, _ -> }) return false
        onProgress("BAIXANDO ffmal.sh...")
        if (!baixar("$BASE/ffmal.sh", File(DEST_SCRIPTS_NORMAL, "ffmal.sh")) { _, _ -> }) return false
        onProgress("SCRIPTS OK")
        return true
    }

    fun baixarPastaLimpaNormal(onProgress: (String) -> Unit): Boolean {
        val zip = File(Sys.app.cacheDir, "android.zip")
        onProgress("BAIXANDO PASTA LIMPA...")
        if (!baixar("$BASE/android.zip", zip) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA LIMPA... $pct%")
            }) return false
        onProgress("EXTRAINDO...")
        val ok = extrair(zip, DEST_APP_REC, "android")
        zip.delete()
        if (ok) onProgress("PASTA LIMPA OK")
        return ok
    }

    fun baixarPastaSujaNormal(onProgress: (String) -> Unit): Boolean {
        val zip = File(Sys.app.cacheDir, "sujasc.zip")
        onProgress("BAIXANDO PASTA SUJA+...")
        if (!baixar("$BASE/sujasc.zip", zip) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA SUJA+... $pct%")
            }) return false
        onProgress("EXTRAINDO...")
        val ok = extrair(zip, DEST_SUJA, "android")
        zip.delete()
        if (ok) onProgress("PASTA SUJA+ OK")
        return ok
    }

    fun baixarScriptsMax(onProgress: (String) -> Unit): Boolean {
        File(DEST_SCRIPTS_MAX).mkdirs()
        onProgress("BAIXANDO ffbem.sh (MAX)...")
        if (!baixar("$BASE/ffbem.sh", File(DEST_SCRIPTS_MAX, "ffbem.sh")) { _, _ -> }) return false
        onProgress("BAIXANDO ffmal.sh (MAX)...")
        if (!baixar("$BASE/ffmal.sh", File(DEST_SCRIPTS_MAX, "ffmal.sh")) { _, _ -> }) return false
        onProgress("SCRIPTS MAX OK")
        return true
    }

    fun baixarPastaSujaMax(onProgress: (String) -> Unit): Boolean {
        val zip = File(Sys.app.cacheDir, "sujaffmax.zip")
        onProgress("BAIXANDO PASTA SUJA MAX...")
        if (!baixar("$BASE/sujaffmax.zip", zip) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA SUJA MAX... $pct%")
            }) return false
        onProgress("EXTRAINDO...")
        val ok = extrair(zip, DEST_SUJA_MAX, "android")
        zip.delete()
        if (ok) onProgress("PASTA SUJA MAX OK")
        return ok
    }

    fun baixarPastaLimpaMax(onProgress: (String) -> Unit): Boolean {
        val zip = File(Sys.app.cacheDir, "limpaffmax.zip")
        onProgress("BAIXANDO PASTA LIMPA MAX...")
        if (!baixar("$BASE/limpaffmax.zip", zip) { b, t ->
                val pct = if (t > 0) (b * 100 / t) else 0
                onProgress("BAIXANDO PASTA LIMPA MAX... $pct%")
            }) return false
        onProgress("EXTRAINDO...")
        val ok = extrair(zip, DEST_LIMPA_MAX, "android")
        zip.delete()
        if (ok) onProgress("PASTA LIMPA MAX OK")
        return ok
    }

    fun pastasInstaladas(ff: String): Pair<Boolean, Boolean> {
        return if (ff == "max") {
            Pair(File("$DEST_SUJA_MAX/android").exists(), File("$DEST_LIMPA_MAX/android").exists())
        } else {
            Pair(File("$DEST_SUJA/android").exists(), File("$DEST_APP_REC/android").exists())
        }
    }
}
