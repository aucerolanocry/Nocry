package com.nocry.app

import java.io.File

object Downloader {
    private const val BASE_URL = "https://nocry-download.osreaisnocry.workers.dev"

    private const val DEST_SUJA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
    private const val DEST_LIMPA_NORMAL = "/storage/emulated/0/MIUI/sound_recorder/app_rec"
    private const val DEST_SUJA_MAX = "/storage/emulated/0/MIUI/BugReportCache/image"
    private const val DEST_LIMPA_MAX = "/storage/emulated/0/MIUI/BugReportCache/draft"

    fun baixarComAdb(nomeArquivo: String, destino: String, pastaRaizEsperada: String, onProgress: (String) -> Unit): Boolean {
        return try {
            if (!Adb.connected) {
                onProgress("ADB DESCONECTADO! Conecte na aba CONEXÃO.")
                return false
            }

            onProgress("INICIANDO...")
            val tempDir = "/sdcard/Download/nocry_temp"
            Adb.exec("rm -rf $tempDir")
            Adb.exec("mkdir -p $tempDir")
            
            val zipPath = "$tempDir/arquivo.zip"
            onProgress("BAIXANDO...")
            val curlResult = Adb.exec("curl -L -o \"$zipPath\" \"$BASE_URL/$nomeArquivo\"")
            if (!curlResult.ok) {
                onProgress("FALHA NO DOWNLOAD: ${curlResult.out}")
                return false
            }
            
            onProgress("EXTRAINDO...")
            val extractDir = "$tempDir/extract"
            Adb.exec("mkdir -p $extractDir")
            val unzipResult = Adb.exec("unzip -o \"$zipPath\" -d \"$extractDir\"")
            if (!unzipResult.ok) {
                onProgress("FALHA AO EXTRAIR: ${unzipResult.out}")
                return false
            }
            
            // 👇 AQUI: Cria a pasta 'android' e move tudo pra dentro dela
            onProgress("ORGANIZANDO...")
            val pastaAndroid = "$destino/android"
            Adb.exec("rm -rf \"$pastaAndroid\"")
            Adb.exec("mkdir -p \"$pastaAndroid\"")
            
            // Move o conteúdo (se tiver pasta raiz ou não)
            Adb.exec("cp -rf $extractDir/* \"$pastaAndroid/\"")
            
            Adb.exec("rm -rf $tempDir")
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
