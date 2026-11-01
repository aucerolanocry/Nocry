package com.nocry.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class RecursosScreen(a: MainActivity) : Screen(a) {
    override val titulo = "INSTALAR RECURSOS"

    private var ff = "normal"
    private var log = ""
    private var ocupado = false

    override fun key(): String = "$ff|$log|$ocupado"

    override fun render() {
        limpa()
        val c = act

        add(Ui.gradTexto(c, "INSTALAR RECURSOS", 28f), 0)
        add(Ui.text(c, "BAIXE AS PASTAS DE CONFIGURAÇÃO DIRETO PELO APP.", 13f, Ui.MUTED), 4)

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
            card.addView(Ui.label(c, "STATUS DAS PASTAS"))
            card.addView(Ui.status(c, "PASTA SUJA (XIT)", if (pastaA) "INSTALADA" else "FALTA", pastaA), Ui.lp(top = 8, c = c))
            card.addView(Ui.status(c, "PASTA LIMPA (RESTAURAR)", if (pastaB) "INSTALADA" else "FALTA", pastaB))
            add(card, 0)

            add(Ui.label(c, "ESCOLHA O FREE FIRE"), 18)
            add(Ui.segmento(c, listOf("NORMAL", "MAX"), if (ff == "normal") 0 else 1) { i ->
                if (ocupado) return@segmento
                ff = if (i == 0) "normal" else "max"
                redesenha()
            }, 8)

            // BOTÃO DE INSTALAR SUJA
            add(Ui.button(c, "INSTALAR SUJA (AUTOMÁTICO)", Ui.Kind.PRIMARY, R.drawable.ic_check) {
                if (ocupado) return@button
                ocupado = true; log = "INICIANDO SUJA..."; redesenha()

                val nome = if (ff == "normal") "sujasc.zip" else "sujaffmax.zip"
                val url = "https://nocry-download.osreaisnocry.workers.dev/$nome"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/call_rec" else "/storage/emulated/0/MIUI/BugReportCache/image"

                Baixador.baixar(c, url, nome, tamanhoEsperado = -1, sha256 = null, soWifi = false,
                    ouvinte = object : Baixador.Ouvinte {
                        override fun aoProgredir(baixado: Long, total: Long) {
                            log = "BAIXANDO SUJA... ${Baixador.formatar(baixado)} de ${Baixador.formatar(total)}"
                            redesenha()
                        }

                        override fun aoTerminar(arquivo: File) {
                            log = "SUJA BAIXADA! EXTRAINDO..."
                            redesenha()
                            Bg.run({ extrair(arquivo, destino, "android") }) { ok ->
                                ocupado = false
                                log = if (ok) "PASTA SUJA INSTALADA!" else "FALHA AO EXTRAIR"
                                redesenha()
                            }
                        }

                        override fun aoFalhar(motivo: String) {
                            ocupado = false
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
                ocupado = true; log = "INICIANDO LIMPA..."; redesenha()

                val nome = if (ff == "normal") "android.zip" else "limpaffmax.zip"
                val url = "https://nocry-download.osreaisnocry.workers.dev/$nome"
                val destino = if (ff == "normal") "/storage/emulated/0/MIUI/sound_recorder/app_rec" else "/storage/emulated/0/MIUI/BugReportCache/draft"

                Baixador.baixar(c, url, nome, tamanhoEsperado = -1, sha256 = null, soWifi = false,
                    ouvinte = object : Baixador.Ouvinte {
                        override fun aoProgredir(baixado: Long, total: Long) {
                            log = "BAIXANDO LIMPA... ${Baixador.formatar(baixado)} de ${Baixador.formatar(total)}"
                            redesenha()
                        }

                        override fun aoTerminar(arquivo: File) {
                            log = "LIMPA BAIXADA! EXTRAINDO..."
                            redesenha()
                            Bg.run({ extrair(arquivo, destino, "android") }) { ok ->
                                ocupado = false
                                log = if (ok) "PASTA LIMPA INSTALADA!" else "FALHA AO EXTRAIR"
                                redesenha()
                            }
                        }

                        override fun aoFalhar(motivo: String) {
                            ocupado = false
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
            add(Ui.label(c, "LOG"), 18)
            add(Ui.text(c, log, 12f, Ui.TEXT, mono = true), 8)
        }
    }

    private fun extrair(arquivo: File, destino: String, pastaRaizEsperada: String): Boolean {
        return try {
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
            NLog.add("download", "extraído em: $destino")
            true
        } catch (e: Exception) {
            NLog.add("download", "falha extrair: ${e.message}")
            false
        }
    }
}
