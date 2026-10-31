package com.nocry.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================================
// TELA QUE MOSTRA UM TRABALHO ACONTECENDO AO VIVO (otimizacao, preparar FF...)
// =====================================================================================
class RunScreen(a: MainActivity, override val titulo: String, private val work: ((String) -> Unit) -> Boolean) : Screen(a) {
    private var tv: TextView? = null
    private var iniciou = false
    private var fim = false
    private var ok = false
    private val buf = StringBuilder()

    override fun key(): String = "$fim"

    private fun anexa(l: String) {
        buf.append(l).append('\n')
        tv?.text = buf.toString()
    }

    override fun render() {
        limpa()
        val t = Ui.text(act, buf.toString(), 12.5f, Ui.TEXT, mono = true)
        t.setTextIsSelectable(true)
        tv = t
        add(t, 0)
        if (fim) add(Ui.button(act, "voltar", Ui.Kind.GHOST) { act.fecha() }, 16)
        if (!iniciou) {
            iniciou = true
            Bg.io {
                val r = try {
                    work { l -> Bg.ui { anexa(l) } }
                } catch (t2: Throwable) {
                    Bg.ui { anexa("[x] erro: $t2") }
                    false
                }
                Bg.ui {
                    ok = r
                    fim = true
                    redesenha()
                }
            }
        }
    }
}

// =====================================================================================
// OTIMIZACAO
// =====================================================================================
class OtmScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Otimização"

    override fun key(): String = listOf(Otm.status(), Prefs.otmAuto, Prefs.otmMsg, Prefs.otmPerf, Adb.connected).joinToString("|")

    override fun render() {
        limpa()
        val c = act
        val st = Otm.status()
        val card = Ui.card(c)
        card.addView(Ui.label(c, "status"))
        card.addView(Ui.text(c, if (st.first) "ATIVA" else "DESATIVADA", 26f, if (st.first) Ui.OK else Ui.BAD, true, mono = true), Ui.lp(top = 4, c = c))
        if (st.first) card.addView(Ui.text(c, "${st.second} apps congelados", 13f, Ui.MUTED, mono = true))
        card.addView(Ui.status(c, "ADB", if (Adb.connected) "CONECTADO" else "DESCONECTADO", Adb.connected), Ui.lp(top = 8, c = c))
        card.addView(Ui.status(c, "AUTOMÁTICO AO ABRIR O FF", if (Prefs.otmAuto) "LIGADO" else "DESLIGADO", null))
        add(card, 0)
        add(Ui.text(c, "Mostra, fecha e congela os apps em segundo plano (menos os protegidos). DESATIVAR devolve tudo como estava.", 12f, Ui.MUTED), 10)

        add(Ui.button(c, "ativar otimização", Ui.Kind.PRIMARY, R.drawable.ic_check) { act.abre(RunScreen(act, "OTIMIZANDO") { log -> Otm.ativar(log) }) }, 14)
        add(Ui.button(c, "desativar (descongela tudo)", Ui.Kind.DANGER, R.drawable.ic_stop) { act.abre(RunScreen(act, "DESATIVANDO") { log -> Otm.desativar(log) }) }, 10)

        add(Ui.label(c, "ferramentas"), 18)
        add(Ui.row(c, "Ver apps (NGC)", "o que vai fechar, o que fica protegido e por quê", R.drawable.ic_eye) { act.abre(OtmVerScreen(act)) }, 8)
        add(Ui.row(c, "Proteger app", "escolha o que nunca fecha", R.drawable.ic_shield) { act.abre(OtmProtegerScreen(act)) })
        add(Ui.row(c, "Fechar mais apps (sistema)", "apps do sistema com ícone que você quer fechar junto", R.drawable.ic_xcircle) { act.abre(OtmMaisScreen(act)) })
        add(Ui.row(c, "Preparar o FF", "compila o Free Fire e tira da economia de energia", R.drawable.ic_gamepad) {
            Ui.dialogEscolha(act, "PREPARAR O FF", listOf("FREE FIRE NORMAL", "FREE FIRE MAX")) { i ->
                val pkg = if (i == 0) Const.FF_NORMAL else Const.FF_MAX
                act.abre(RunScreen(act, "PREPARANDO O FF") { log -> Otm.prepararFf(pkg, log) })
            }
        })

        add(Ui.label(c, "automático"), 18)
        add(Ui.toggle(c, "Ligar sozinho ao abrir o FF", "abriu o FF = ativa · saiu por 45 s = desativa (só o que ele ativou). Precisa do ADB.", Prefs.otmAuto) { v ->
            if (v) {
                if (!Adb.connected) { Ui.toast(act, "Conecte o ADB primeiro (CONEXÃO)"); redesenha() }
                else { OtmAuto.ligar(); Ui.toast(act, "AUTOMÁTICO LIGADO — abra o FF"); redesenha() }
            } else {
                Bg.run({ OtmAuto.desligar(); true }) { _ -> Ui.toast(act, "AUTOMÁTICO DESLIGADO"); redesenha() }
            }
        }, 8)

        add(Ui.label(c, "opções"), 18)
        add(Ui.toggle(c, "Mensagens fecham", "WhatsApp, Telegram, Signal, Messenger e Discord saem da memória, mas ficam sem avisar até você abrir o app", Prefs.otmMsg == "fechar") { v ->
            Prefs.otmMsg = if (v) "fechar" else "proteger"
        }, 8)
        add(Ui.toggle(c, "CPU/GPU em nível fixo", "estável, não mais forte (esquenta mais). Vale na próxima ativação", Prefs.otmPerf) { v -> Prefs.otmPerf = v })
    }
}

/** linha simples de lista dentro de um cartao */
private fun linhas(c: Context, titulo: String, itens: List<String>, vazio: String): LinearLayout {
    val card = Ui.card(c)
    card.addView(Ui.label(c, titulo))
    if (itens.isEmpty()) card.addView(Ui.text(c, vazio, 13f, Ui.MUTED), Ui.lp(top = 6, c = c))
    for (i in itens) card.addView(Ui.text(c, i, 12.5f, Ui.TEXT, mono = true), Ui.lp(top = 6, c = c))
    return card
}

// =====================================================================================
// VER APPS (so olhando)
// =====================================================================================
class OtmVerScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Ver apps (NGC)"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var falhou = false
    private var iniciou = false

    override fun key(): String = "$carregando|$falhou|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        falhou = false
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            falhou = p == null
            carregando = false
            redesenha()
        }
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        val p = plano
        if (carregando) { add(Ui.text(c, "LENDO O CELULAR...", 14f, Ui.MUTED, true, mono = true), 0); return }
        if (p == null) {
            add(Ui.text(c, "[x] Não consegui ler o celular. Confira a CONEXÃO e tente de novo.", 13f, Ui.WARN, true), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        add(Ui.text(c, p.perfil(), 13f, Ui.TEXT, true, mono = true), 0)
        add(Ui.text(c, "RAM LIVRE: ${Otm.gb(p.memAvail)} GB · ${p.nivel()}", 12f, Ui.MUTED, mono = true), 4)
        if (p.setFail) add(Ui.text(c, "O celular não deixou ler as configurações: ATIVAR não fecha nada até resolver — reinicie o celular.", 12f, Ui.WARN, true), 10)

        val tot = p.c.sumOf { it.kb }
        add(linhas(c, "vai fechar e congelar (${p.c.size}) · ~${Otm.mb(tot)} MB",
            p.c.map { it.pk + "  " + Otm.mb(it.kb) + " MB" + (if (it.escolhido) "  · ESCOLHIDO POR VOCÊ" else "") }, "nenhum app em segundo plano"), 14)
        add(linhas(c, "ficam protegidos (${p.x.size})", p.x.map { it.pk + "  " + it.why }, "nenhum"), 12)
        val sis = p.s.take(12).map { it.pk + "  " + Otm.mb(it.kb) + " MB · " + (if (it.pode) "pode fechar (FECHAR MAIS APPS)" else "sistema" + (if (it.why.isNotEmpty()) " · " + it.why else "")) }.toMutableList()
        if (p.s.size > 12) sis.add("+ mais ${p.s.size - 12} apps do sistema")
        if (p.n.isNotEmpty()) sis.add("+ ${p.n.size} processos nativos do Android · ${Otm.mb(p.n.sumOf { it.kb })} MB (não mexe)")
        add(linhas(c, "sistema rodando — só olhando (${p.s.size})", sis, "nenhum"), 12)

        val aj = ArrayList<String>()
        aj.add("animações zeradas e sem desfoque de janela")
        if (p.hz in 90..240) aj.add("tela travada em ${p.hz} Hz")
        aj.add("wi-fi sem teste de login")
        aj.add("NOCRY, Termux e FF fora da economia de bateria")
        aj.add("bateria inteligente e economia desligadas")
        aj.add("sem pop-up de aviso, sem scan wi-fi/bluetooth em 2º plano, sem vibração de toque")
        if (p.sdk >= 31) {
            aj.add("congelador de apps em cache do Android ligado")
            aj.add("Android não mata mais os processos em segundo plano")
            aj.add("Free Fire em modo jogo \"desempenho\" (se o jogo oferecer)")
        }
        if (Prefs.otmPerf && p.sdk >= 30) aj.add("CPU/GPU em nível fixo (se o celular aceitar)")
        add(linhas(c, "também ajusta (volta ao desativar)", aj, ""), 12)
        add(Ui.button(c, "atualizar", Ui.Kind.GHOST) { carrega() }, 14)
    }
}

// =====================================================================================
// PROTEGER APP
// =====================================================================================
class OtmProtegerScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Proteger app"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var iniciou = false

    override fun key(): String = "$carregando|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            carregando = false
            redesenha()
        }
    }

    private fun alterna(pk: String, why: String) {
        Ui.toast(act, Otm.alternaProtecao(pk, why))
        carrega()
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        if (carregando) { add(Ui.text(c, "LENDO O CELULAR...", 14f, Ui.MUTED, true, mono = true), 0); return }
        val p = plano
        if (p == null) {
            add(Ui.text(c, "[x] Não consegui ler o celular. Confira a CONEXÃO.", 13f, Ui.WARN, true), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        add(Ui.text(c, "Toque num app para alternar. Sem escudo = FECHA na otimização · com escudo = nunca fecha. Só dá para mudar o que você protegeu e as mensagens.", 12f, Ui.MUTED), 0)
        add(Ui.label(c, "vão fechar"), 14)
        if (p.c.isEmpty()) add(Ui.text(c, "nenhum app em segundo plano", 13f, Ui.MUTED), 6)
        for (a2 in p.c) {
            if (a2.escolhido) continue
            add(Ui.row(c, a2.pk.removePrefix("com."), Otm.mb(a2.kb).toString() + " MB · toque para proteger") { alterna(a2.pk, "") }, 6)
        }
        add(Ui.label(c, "protegidos"), 14)
        for (x in p.x) add(Ui.row(c, x.pk.removePrefix("com."), x.why, R.drawable.ic_shield) { alterna(x.pk, x.why) }, 6)
        val vistos = HashSet<String>()
        p.c.forEach { vistos.add(it.pk) }
        p.x.forEach { vistos.add(it.pk) }
        for (pk in Otm.protegidos()) {
            if (pk in vistos) continue
            add(Ui.row(c, pk.removePrefix("com."), "SUA LISTA · não está rodando agora", R.drawable.ic_shield) { alterna(pk, "SUA LISTA") }, 6)
        }
        add(Ui.button(c, "adicionar pelo nome do app", Ui.Kind.GHOST) {
            Ui.dialogTexto(act, "PROTEGER APP", "ex.: com.whatsapp") { nome ->
                if (Otm.protegerPorNome(nome)) { Ui.toast(act, "PROTEGIDO"); carrega() } else Ui.toast(act, "Nome inválido (só letras, números e ponto)")
            }
        }, 16)
    }
}

// =====================================================================================
// FECHAR MAIS APPS (apps do sistema com icone)
// =====================================================================================
class OtmMaisScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Fechar mais apps"
    private var plano: Otm.Plano? = null
    private var carregando = false
    private var iniciou = false

    override fun key(): String = "$carregando|${plano != null}"

    private fun carrega() {
        if (carregando) return
        carregando = true
        redesenha()
        Bg.run({ if (Adb.ensure(act.applicationContext)) Otm.lerPlano() else null }) { p ->
            plano = p
            carregando = false
            redesenha()
        }
    }

    private fun alterna(pk: String) {
        val agora = Otm.alternaExtra(pk)
        Ui.toast(act, if (agora) "$pk VAI FECHAR NA OTIMIZAÇÃO" else "$pk NÃO FECHA MAIS")
        carrega()
    }

    override fun render() {
        limpa()
        val c = act
        if (!iniciou) { iniciou = true; carrega(); return }
        if (carregando) { add(Ui.text(c, "LENDO O CELULAR...", 14f, Ui.MUTED, true, mono = true), 0); return }
        val p = plano
        if (p == null) {
            add(Ui.text(c, "[x] Não consegui ler o celular. Confira a CONEXÃO.", 13f, Ui.WARN, true), 0)
            add(Ui.button(c, "tentar de novo", Ui.Kind.GHOST) { carrega() }, 14)
            return
        }
        add(Ui.text(c, "Toque num app do sistema para fechar junto na otimização. Toque de novo para tirar. Só aparece o que NÃO é essencial (Telefone, Configurações, Play Services, teclado... ficam de fora).", 12f, Ui.MUTED), 0)
        val mostrados = HashSet<String>()
        add(Ui.label(c, "escolhidos por você"), 14)
        var algum = false
        for (a2 in p.c) {
            if (!a2.escolhido) continue
            algum = true
            mostrados.add(a2.pk)
            add(Ui.row(c, Otm.curto(a2.pk), Otm.mb(a2.kb).toString() + " MB · VAI FECHAR", R.drawable.ic_xcircle) { alterna(a2.pk) }, 6)
        }
        for (pk in Otm.extras()) {
            if (pk in mostrados) continue
            algum = true
            val why = p.x.firstOrNull { it.pk == pk }?.why
            add(Ui.row(c, Otm.curto(pk), "ESCOLHIDO · " + (why ?: "NÃO ESTÁ RODANDO")) { alterna(pk) }, 6)
        }
        if (!algum) add(Ui.text(c, "nenhum", 13f, Ui.MUTED), 6)
        add(Ui.label(c, "você pode escolher"), 14)
        val pode = p.s.filter { it.pode }
        if (pode.isEmpty()) add(Ui.text(c, "nenhum app do sistema com ícone rodando agora", 13f, Ui.MUTED), 6)
        for (s in pode) add(Ui.row(c, Otm.curto(s.pk), Otm.mb(s.kb).toString() + " MB") { alterna(s.pk) }, 6)
    }
}

// =====================================================================================
// LOGS
// =====================================================================================
class LogScreen(a: MainActivity, private val tag: String?) : Screen(a) {
    override val titulo = if (tag == null) "Logs" else "Log · " + tag
    override val aba = if (tag == null) 3 else -1

    override fun key(): String = NLog.text(tag).hashCode().toString()

    override fun render() {
        limpa()
        val c = act
        val t = Ui.text(c, NLog.text(tag), 11.5f, Ui.TEXT, code = true)
        t.setTextIsSelectable(true)
        add(t, 0)
        add(Ui.button(c, "copiar", Ui.Kind.GHOST) {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NOCRY log", NLog.text(tag)))
            Ui.toast(act, "COPIADO")
        }, 16)
        add(Ui.button(c, "limpar", Ui.Kind.DANGER) { NLog.clear(); redesenha() }, 10)
    }
}

// =====================================================================================
// ATUALIZACAO / SOBRE
// =====================================================================================
object Update {
    class Info(val tag: String, val pagina: String, val apk: String?)

    /** busca a ultima versao publicada no GitHub (repositorio publico, sem token) */
    fun buscar(): Info? {
        return try {
            val u = URL("https://api.github.com/repos/" + Const.REPO + "/releases/latest")
            val cx = u.openConnection() as HttpURLConnection
            cx.connectTimeout = 8000
            cx.readTimeout = 8000
            cx.setRequestProperty("Accept", "application/vnd.github+json")
            if (cx.responseCode != 200) return null
            val j = JSONObject(cx.inputStream.bufferedReader().use { it.readText() })
            var apk: String? = null
            val arr = j.optJSONArray("assets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
            }
            Info(j.optString("tag_name"), j.optString("html_url"), apk)
        } catch (_: Throwable) { null }
    }

    private fun nums(v: String): List<Int> = v.trim().removePrefix("v").split('.', '-', '+').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** a versao publicada e mais nova que a instalada? */
    fun maisNova(atual: String, publicada: String): Boolean {
        val a = nums(atual)
        val b = nums(publicada)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }
}

class SobreScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Sobre"
    override val aba = 4
    private var msg = ""
    private var link: String? = null
    private var buscando = false

    override fun key(): String = "$msg|$buscando|${License.role()}"

    private fun versao(): String = try { act.packageManager.getPackageInfo(Const.PKG, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun abre(url: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { Ui.toast(act, "Não consegui abrir o navegador") }
    }

    override fun render() {
        limpa()
        val c = act
        add(Ui.gradTexto(c, "NOCRY", 38f), 0)
        add(Ui.text(c, "versão " + versao(), 13f, Ui.MUTED, true, mono = true), 0)
        add(Ui.text(c, "Modo competitivo, toque via volume e carregador e otimização de segundo plano, direto no celular, sem abrir o Termux.", 13f), 12)

        // ---------------- sua key ----------------
        val kc = Ui.card(c, forte = true)
        kc.addView(Ui.label(c, "sua key"))
        val linhaKey = Ui.hbox(c)
        val papel = License.roleNome()
        if (papel.isNotEmpty()) linhaKey.addView(Ui.selo(c, papel, if (License.isAdmin()) Ui.RED else Ui.MUTED, License.isAdmin()))
        val nome = License.label()
        if (nome.isNotEmpty()) {
            val tn = Ui.text(c, nome, 15f, Ui.TEXT, true)
            val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            tp.leftMargin = Ui.dp(c, 10)
            linhaKey.addView(tn, tp)
        }
        kc.addView(linhaKey, Ui.lp(top = 8, c = c))
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        val e = License.expira()
        kc.addView(Ui.status(c, "VALIDADE", if (e == 0L) "SEM VENCIMENTO" else "ATÉ " + fmt.format(java.util.Date(e)), true), Ui.lp(top = 8, c = c))
        val resta = License.restaOffline()
        val horas = (resta / 3600000L).coerceAtLeast(0)
        kc.addView(Ui.status(c, "SEM INTERNET VALE MAIS", if (horas >= 48) "${horas / 24} DIAS" else "$horas H", if (horas >= 12) true else null))
        kc.addView(Ui.text(c, "aparelho " + License.devId(c).take(8) + " · confirmada com o servidor a cada poucas horas, quando há internet.", 11.5f, Ui.MUTED, mono = true), Ui.lp(top = 6, c = c))
        if (License.isAdmin() && BuildConfig.API_URL.isNotBlank()) {
            kc.addView(Ui.button(c, "abrir painel de keys", Ui.Kind.PRIMARY, R.drawable.ic_globe) {
                abre(BuildConfig.API_URL.trimEnd('/') + "/admin")
            }, Ui.lp(top = 12, c = c))
            kc.addView(Ui.text(c, "No painel, entre com a sua própria key.", 11.5f, Ui.MUTED), Ui.lp(top = 6, c = c))
        }
        kc.addView(Ui.button(c, "sair da key", Ui.Kind.GHOST, R.drawable.ic_logout) {
            Ui.dialogConfirma(act, "SAIR DA KEY", "O app volta para a tela da key e os monitores param (o toque e a tela voltam ao normal).", "SAIR") {
                License.sair("você saiu")
            }
        }, Ui.lp(top = 10, c = c))
        add(kc, 14)

        val card = Ui.card(c)
        card.addView(Ui.label(c, "atualização"))
        card.addView(Ui.text(c, if (msg.isEmpty()) "Toque em verificar para ver se há versão nova." else msg, 13f), Ui.lp(top = 6, c = c))
        card.addView(Ui.button(c, if (buscando) "procurando..." else "verificar atualização", Ui.Kind.GHOST) {
            if (!buscando) {
                buscando = true
                redesenha()
                Bg.run({ Update.buscar() }) { i ->
                    buscando = false
                    if (i == null) { msg = "Não consegui verificar agora (sem internet ou ainda não há versão publicada)."; link = null }
                    else if (Update.maisNova(versao(), i.tag)) { msg = "Versão nova disponível: ${i.tag}"; link = i.apk ?: i.pagina }
                    else { msg = "Você está na versão mais nova (${i.tag})."; link = null }
                    redesenha()
                }
            }
        }, Ui.lp(top = 10, c = c))
        val l = link
        if (l != null) card.addView(Ui.button(c, "baixar a versão nova") { abre(l) }, Ui.lp(top = 8, c = c))
        add(card, 14)

        val card2 = Ui.card(c)
        card2.addView(Ui.label(c, "compartilhar"))
        card2.addView(Ui.text(c, "Qualquer pessoa pode baixar o app por este link:\nhttps://github.com/" + Const.REPO + "/releases/latest", 12.5f, Ui.TEXT, code = true), Ui.lp(top = 6, c = c))
        card2.addView(Ui.button(c, "compartilhar link", Ui.Kind.GHOST) {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, "Baixe o NOCRY: https://github.com/" + Const.REPO + "/releases/latest")
            try { act.startActivity(Intent.createChooser(i, "Compartilhar")) } catch (_: Exception) {}
        }, Ui.lp(top = 10, c = c))
        card2.addView(Ui.button(c, "abrir página de download", Ui.Kind.GHOST) { abre("https://github.com/" + Const.REPO + "/releases/latest") }, Ui.lp(top = 8, c = c))
        add(card2, 12)

        add(Ui.text(c, "ADB embutido: libadb-android (Muntashir Al-Islam), licença Apache-2.0.", 11f, Ui.MUTED), 14)
        add(Ui.text(c, "Fontes: Big Shoulders, Tektur, Outfit, Bricolage Grotesque, Gloock, Work Sans, Instrument Sans, Pixelify Sans, Red Hat Mono, Space Grotesk, JetBrains Mono (licença SIL OFL) e Poppins (licença OFL).", 11f, Ui.MUTED), 6)
    }
}
