package com.nocry.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class RecursosScreen(a: MainActivity, ffInicial: String = "normal") : Screen(a) {
    override val titulo = "Instalar recursos"

    private var ff = ffInicial
    private var log = ""
    private var ocupado = false
    private var percent: Float? = null

    override fun key(): String = "$ff|$log|$ocupado|$percent"

    override fun render() {
        limpa()
        val c = act

        add(Ui.text(c, "Instala a pasta suja (usada pelo bypass) e a pasta limpa (pra restaurar depois), direto pelo app.", 10.5f, Ui.MUTED), 0)

        val temPermissao = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        if (!temPermissao) {
            add(Ui.text(c, "⚠️ O app precisa da permissão 'Acesso a todos os arquivos'.", 13f, Ui.WARN, true), 8)
            add(Ui.button(c, "PERMITIR ACESSO AOS ARQUIVOS", Ui.Kind.PRIMARY) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${act.packageName}")
                act.startActivity(intent)
            }, 8)
        } else {
            val (pastaA, pastaB) = Downloader.pastasInstaladas(ff)
            val card = Ui.card(c)
            card.addView(Ui.labelIcone(c, R.drawable.ic_download, "STATUS DAS PASTAS"))
            card.addView(Ui.status(c, "PASTA SUJA (XIT)", if (pastaA) "INSTALADA" else "FALTA", pastaA), Ui.lp(top = 8, c = c))
            card.addView(Ui.status(c, "PASTA LIMPA (RESTAURAR)", if (pastaB) "INSTALADA" else "FALTA", pastaB))
            add(card, 0)

            add(Ui.labelIcone(c, R.drawable.ic_gamepad, "ESCOLHA O FREE FIRE"), 18)
            add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (ff == "normal") 0 else 1) { i ->
                if (ocupado) return@segmento
                ff = if (i == 0) "normal" else "max"
                redesenha()
            }, 8)

            // BOTÃO DE INSTALAR SUJA
            add(Ui.button(c, "INSTALAR SUJA (AUTOMÁTICO)", Ui.Kind.PRIMARY, R.drawable.ic_check) {
                if (ocupado) return@button
                ocupado = true; log = "INICIANDO SUJA..."; percent = null; redesenha()

                val nome = if (ff == "normal") "sujasc.zip" else "sujaffmax.zip"
                val url = "https://nocry-download.osreaisnocry.workers.dev/$nome"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/call_rec" else "/storage/emulated/0/MIUI/BugReportCache/image"

                Baixador.baixar(c, url, nome, tamanhoEsperado = -1, sha256 = null, soWifi = false,
                    headers = mapOf("X-Key" to Prefs.licKey.orEmpty()),
                    ouvinte = object : Baixador.Ouvinte {
                        override fun aoProgredir(baixado: Long, total: Long) {
                            log = "BAIXANDO SUJA... ${Baixador.formatar(baixado)} de ${Baixador.formatar(total)}"
                            percent = if (total > 0) (baixado.toFloat() / total.toFloat()) else null
                            redesenha()
                        }

                        override fun aoTerminar(arquivo: File) {
                            log = "SUJA BAIXADA! EXTRAINDO..."
                            percent = null
                            redesenha()
                            Bg.run({ extrair(arquivo, destino, "android") }) { ok ->
                                ocupado = false
                                percent = if (ok) 1f else null
                                log = if (ok) "PASTA SUJA INSTALADA!" else "FALHA AO EXTRAIR"
                                redesenha()
                            }
                        }

                        override fun aoFalhar(motivo: String) {
                            ocupado = false
                            percent = null
                            log = "FALHA NA SUJA: $motivo"
                            redesenha()
                        }

                        override fun aoAguardar(motivo: String) {
                            log = "AGUARDANDO SUJA: $motivo"
                            redesenha()
                        }

                        override fun aoVerificar() {
                            log = "VERIFICANDO SUJA..."
                            redesenha()
                        }
                    })
            }, 18)

            // BOTÃO DE INSTALAR LIMPA
            add(Ui.button(c, "INSTALAR LIMPA (AUTOMÁTICO)", Ui.Kind.GHOST, R.drawable.ic_stop) {
                if (ocupado) return@button
                ocupado = true; log = "INICIANDO LIMPA..."; percent = null; redesenha()

                val nome = if (ff == "normal") "android.zip" else "limpaffmax.zip"
                val url = "https://nocry-download.osreaisnocry.workers.dev/$nome"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/app_rec" else "/storage/emulated/0/MIUI/BugReportCache/draft"

                Baixador.baixar(c, url, nome, tamanhoEsperado = -1, sha256 = null, soWifi = false,
                    headers = mapOf("X-Key" to Prefs.licKey.orEmpty()),
                    ouvinte = object : Baixador.Ouvinte {
                        override fun aoProgredir(baixado: Long, total: Long) {
                            log = "BAIXANDO LIMPA... ${Baixador.formatar(baixado)} de ${Baixador.formatar(total)}"
                            percent = if (total > 0) (baixado.toFloat() / total.toFloat()) else null
                            redesenha()
                        }

                        override fun aoTerminar(arquivo: File) {
                            log = "LIMPA BAIXADA! EXTRAINDO..."
                            percent = null
                            redesenha()
                            Bg.run({ extrair(arquivo, destino, "android") }) { ok ->
                                ocupado = false
                                percent = if (ok) 1f else null
                                log = if (ok) "PASTA LIMPA INSTALADA!" else "FALHA AO EXTRAIR"
                                redesenha()
                            }
                        }

                        override fun aoFalhar(motivo: String) {
                            ocupado = false
                            percent = null
                            log = "FALHA NA LIMPA: $motivo"
                            redesenha()
                        }

                        override fun aoAguardar(motivo: String) {
                            log = "AGUARDANDO LIMPA: $motivo"
                            redesenha()
                        }

                        override fun aoVerificar() {
                            log = "VERIFICANDO LIMPA..."
                            redesenha()
                        }
                    })
            }, 10)
        }

        if (log.isNotEmpty()) {
            val sucesso = log.contains("INSTALADA!")
            val falha = log.contains("FALHA")
            val card = Ui.card(c, forte = ocupado)
            card.addView(Ui.labelIcone(c, if (falha) R.drawable.ic_warn else R.drawable.ic_download, if (ocupado) "BAIXANDO" else if (sucesso) "PRONTO" else if (falha) "FALHOU" else "LOG"))
            if (ocupado || sucesso) {
                val bp = BarraProgresso(c)
                bp.define(if (sucesso) 1f else percent)
                card.addView(bp, Ui.lp(top = 10, c = c))
            }
            val cor = if (falha) Ui.BAD else if (sucesso) Ui.OK else Ui.TEXT
            card.addView(Ui.text(c, log, 12f, cor, sucesso || falha, mono = true), Ui.lp(top = 8, c = c))
            add(card, 18)
        }
    }

    private fun extrair(arquivo: File, destino: String, pastaRaizEsperada: String): Boolean {
        return try {
            val destinoDir = File(destino)
            destinoDir.mkdirs()
            
            val tempDir = File(destinoDir, "_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            ZipInputStream(arquivo.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val nome = entry.name
                    
                    val prefixo = if (pastaRaizEsperada.isNotEmpty() && nome.startsWith("$pastaRaizEsperada/")) "$pastaRaizEsperada/" else ""
                    val caminhoLimpo = if (prefixo.isNotEmpty()) nome.substringAfter(prefixo) else nome
                    
                    if (caminhoLimpo.isNotEmpty() && !caminhoLimpo.endsWith("/")) {
                        val f = File(tempDir, caminhoLimpo)
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

            // Cria a pasta 'android' e move tudo pra dentro dela
            val pastaAndroid = File(destinoDir, "android")
            pastaAndroid.mkdirs()
            
            tempDir.listFiles()?.forEach { f ->
                val alvo = File(pastaAndroid, f.name)
                if (alvo.exists()) alvo.deleteRecursively()
                f.renameTo(alvo)
            }
            
            tempDir.deleteRecursively()
            
            NLog.add("download", "extraído em: $destino/android")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha extrair: ${e.message}")
            false
        }
    }
}
