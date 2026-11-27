package com.nocry.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// =====================================================================================
// IA do NOCRY: a pessoa escreve do jeito dela ("liga o bypass, liga o bot e desliga o bot em 5 min")
// e o app faz. Dois cérebros:
//  - IA de verdade (Claude), pelo servidor do NOCRY, quando o dono colocar a chave lá (nunca fica no app);
//  - comandos grátis, aqui no celular, quando a IA de verdade não está disponível.
// As ações são sempre feitas AQUI no celular, pela lista fechada abaixo (a IA só escolhe da lista).
// =====================================================================================

/** um passo: a ação, daqui a quanto tempo (0 = agora) ou a hora certa, e um detalhe (ex.: qual tela) */
class Passo(val acao: String, val atrasoMs: Long = 0L, val arg: String = "", val quando: Long = 0L)

object Acoes {
    /** tudo que a IA sabe fazer (os mesmos nomes vão pro servidor da IA) */
    val nomes: Map<String, String> = linkedMapOf(
        "bypass_ligar" to "ligar o bypass",
        "bypass_desligar" to "desligar o bypass (restaura o jogo)",
        "bot_ligar" to "ligar o bot de fila e começar a buscar",
        "bot_desligar" to "desligar o bot de fila",
        "bot_pausar" to "pausar a busca do bot (continua conectado)",
        "jogo_abrir" to "abrir o Free Fire",
        "otm_ligar" to "ligar a otimização",
        "otm_desligar" to "desligar a otimização",
        "comp_ligar" to "ligar o modo competitivo (toque rápido)",
        "comp_desligar" to "desligar o modo competitivo",
        "adb_ligar" to "ligar o ADB (conexão)",
        "menu_meet" to "colocar o atalho do Google Meet no menu do topo",
        "menu_toque" to "colocar o atalho do Toque rápido no menu do topo",
        "menu_tirar_meet" to "tirar o atalho do Meet do menu do topo",
        "menu_tirar_toque" to "tirar o atalho do Toque rápido do menu do topo",
        "instalar_pastas" to "instalar as pastas que faltam",
        "verificar" to "verificar os problemas do app e arrumar o que der sozinho",
        "tela" to "abrir uma tela do app (arg: inicio, conexao, bypass, recursos, bot, otimizacao, competitivo, ajustes, estudio, logs, ajuda, caderno, rank, eventos, partidas)",
        "cancelar_agendados" to "cancelar tudo que estava agendado"
    )

    /** o contrário de cada ação (pra "liga o bot por 30 minutos") */
    val contrario = mapOf(
        "bypass_ligar" to "bypass_desligar", "bot_ligar" to "bot_desligar",
        "otm_ligar" to "otm_desligar", "comp_ligar" to "comp_desligar"
    )

    fun descricao(p: Passo): String = when (p.acao) {
        "tela" -> "abrir a tela ${p.arg}"
        else -> nomes[p.acao] ?: p.acao
    }

    private val main = Handler(Looper.getMainLooper())

    /** roda na linha da tela e espera o resultado (o bot e o menu do topo mexem na tela) */
    private fun <T> naTela(bloco: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return try { bloco() } catch (_: Throwable) { null }
        val trava = CountDownLatch(1)
        var r: T? = null
        main.post { try { r = bloco() } catch (_: Throwable) {} finally { trava.countDown() } }
        trava.await(10, TimeUnit.SECONDS)
        return r
    }

    private fun dorme(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    /** faz agora ou agenda. Devolve uma linha pra mostrar. Chame fora da tela (pode demorar) */
    fun fazerOuAgendar(c: Context, act: MainActivity?, p: Passo): String {
        if (p.atrasoMs > 0 || p.quando > System.currentTimeMillis()) {
            val it = Agenda.adicionar(c, p)
            return "⏰ às ${hora(it.quando)}: ${descricao(p)}"
        }
        return fazer(c, act, p)
    }

    fun hora(t: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(t))

    /** faz a ação agora (bloqueia: nunca na linha da tela) */
    fun fazer(c: Context, act: MainActivity?, p: Passo): String {
        val app = c.applicationContext
        val r = try { fazerSeguro(app, act, p) } catch (t: Throwable) { "✗ ${descricao(p)}: deu erro (${t.javaClass.simpleName})" }
        NLog.add("ia", r)
        return r
    }

    private fun fazerSeguro(app: Context, act: MainActivity?, p: Passo): String = when (p.acao) {
        "bypass_ligar" -> {
            if (Prefs.bypassAtivo || Prefs.bypassVolAtivo) "✓ o bypass já estava ligado"
            else if (!Adb.ensure(app)) "✗ bypass: o ADB não está conectado. Peça \"liga o ADB\" ou abra a Conexão"
            else {
                Downloader.esquecerPastas()
                val ff = Prefs.bypassFf
                val volume = Prefs.bypassGatilho == "volume"
                when {
                    !Downloader.pastasInstaladas(ff).first -> "✗ bypass: falta a pasta suja. Peça \"instala as pastas\""
                    volume && !(Sys.acessibilidadeLigada() || Sys.ligarAcessibilidade()) -> "✗ bypass: a escuta do volume está desligada (precisa do ADB)"
                    !Config.verificarLimite() -> "✗ bypass: chegou no limite de hoje"
                    else -> {
                        if (volume) EngineBypass.iniciarVolume() else EngineBypass.iniciar(Prefs.bypassGatilho, ff, Prefs.bypassTipo)
                        "✓ bypass ligado"
                    }
                }
            }
        }
        "bypass_desligar" -> when (EngineBypass.parar()) {
            0 -> "✓ bypass desligado e o jogo restaurado"
            1 -> "⚠ bypass desligado, mas não deu pra restaurar o jogo (ADB). Peça \"liga o ADB\" e de novo \"desliga o bypass\""
            else -> "✓ bypass desligado"
        }
        "bot_ligar" -> ligarBot()
        "bot_desligar" -> {
            naTela { Sniper.desligar() }
            "✓ bot de fila desligado"
        }
        "bot_pausar" -> {
            naTela { if (Sniper.buscando) Sniper.alternarBusca() }
            "✓ busca do bot pausada"
        }
        "jogo_abrir" -> {
            val pm = app.packageManager
            val pref = if (Prefs.jogarFf == "max") Const.FF_MAX else Const.FF_NORMAL
            val pkg = listOf(pref, Const.FF_NORMAL, Const.FF_MAX).firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            if (pkg == null) "✗ o Free Fire não está instalado"
            else {
                val i = pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = naTela { (act ?: app).startActivity(i); true } == true
                if (ok) "✓ abrindo o Free Fire" else "✗ não consegui abrir o jogo (abra o app e peça de novo)"
            }
        }
        "otm_ligar" -> if (Otm.status().first) "✓ a otimização já estava ligada"
            else if (Otm.ativar {}) "✓ otimização ligada" else "✗ otimização: o ADB não está conectado"
        "otm_desligar" -> if (Otm.desativar {}) "✓ otimização desligada (tudo voltou ao normal)" else "✗ otimização: o ADB não está conectado"
        "comp_ligar" -> if (Comp.ligado()) "✓ o competitivo já estava ligado"
            else if (!Toque.podeMexer()) "✗ competitivo: faça a Conexão primeiro"
            else if (Comp.ativar()) "✓ modo competitivo ligado" else "✗ o aparelho não aceitou o competitivo"
        "comp_desligar" -> if (!Comp.ligado()) "✓ o competitivo já estava desligado"
            else if (Comp.desativar(true)) "✓ modo competitivo desligado" else "✗ o aparelho não aceitou desligar o competitivo"
        "adb_ligar" -> if (Adb.connected) "✓ o ADB já estava ligado"
            else if (AdbAuto.ligarManual(app)) "✓ ADB ligado" else "✗ não consegui ligar o ADB: abra a Conexão"
        "menu_meet", "menu_toque" -> {
            val meet = p.acao == "menu_meet"
            val nome = if (meet) "Meet" else "Toque rápido"
            if (MenuRapido.noMenu(meet)) "✓ o $nome já está no menu do topo"
            else if (act == null) "✗ abra o app pra eu colocar o $nome no menu do topo"
            else { naTela { MenuRapido.adicionar(act, meet) {} }; "✓ pedi pro Android colocar o $nome no menu do topo (confirme na janelinha)" }
        }
        "menu_tirar_meet", "menu_tirar_toque" -> {
            val meet = p.acao == "menu_tirar_meet"
            val nome = if (meet) "Meet" else "Toque rápido"
            if (!MenuRapido.noMenu(meet)) "✓ o $nome não estava no menu do topo"
            else if (act == null) "✗ abra o app pra eu tirar o $nome do menu do topo"
            else { naTela { MenuRapido.tirar(act, meet) {} }; "✓ $nome tirado do menu do topo" }
        }
        "instalar_pastas" -> instalarPastas(app)
        "verificar" -> verificar(app)
        "tela" -> if (act == null) "✗ abra o app pra eu mostrar a tela" else {
            naTela { abrirTela(act, p.arg) }
            "✓ abrindo ${p.arg.ifBlank { "o início" }}"
        }
        "cancelar_agendados" -> {
            val n = Agenda.lista().size
            Agenda.cancelarTudo(app)
            if (n == 0) "✓ não tinha nada agendado" else "✓ cancelei $n ${if (n == 1) "coisa agendada" else "coisas agendadas"}"
        }
        else -> "✗ não sei fazer \"${p.acao}\""
    }

    private fun ligarBot(): String {
        if (Sniper.buscando) return "✓ o bot já estava buscando"
        if (!Sniper.conectado) {
            val ok = naTela { Sniper.ligar() } ?: false
            if (!ok) return "✗ bot: não deu pra ligar. Cole o token da conta no Cofre do bot"
            var t = 0
            while (!Sniper.conectado && t < 60) { dorme(500); t++ }
            if (!Sniper.conectado) return "✗ bot: a conta não conectou. Veja a internet e o token"
        }
        var t = 0
        while (Sniper.conferindoAoLigar && t < 120) { dorme(500); t++ }
        naTela { if (!Sniper.buscando) Sniper.alternarBusca() }
        dorme(400)
        return if (Sniper.buscando) "✓ bot de fila ligado e buscando" else "⚠ bot conectado, mas a busca não começou: escolha modo e valor na tela do bot"
    }

    private fun instalarPastas(app: Context): String {
        val ff = Prefs.bypassFf
        Downloader.esquecerPastas()
        val (a, b) = Downloader.pastasInstaladas(ff)
        val jobs = ArrayList<Instalador.Job>()
        if (!a && !Instalador.naFila(ff, "suja")) jobs.add(Instalador.Job(ff, "suja"))
        if (!b && !Instalador.naFila(ff, "limpa")) jobs.add(Instalador.Job(ff, "limpa"))
        if (jobs.isEmpty()) return if (a && b) "✓ as duas pastas já estão instaladas" else "✓ as pastas já estão sendo instaladas"
        Instalador.pedir(app, jobs)
        return "✓ instalando " + jobs.joinToString(" e ") { if (it.tipo == "suja") "a pasta suja" else "a pasta limpa" } + " (avisa na notificação)"
    }

    /** confere tudo e arruma sozinho o que dá (ADB, escuta do volume, pastas) */
    private fun verificar(app: Context): String {
        val itens = try { Diag.rodar(app) } catch (_: Throwable) { emptyList() }
        val ruins = itens.filter { !it.ok }
        val linhas = ArrayList<String>()
        if (ruins.isEmpty()) linhas += "✓ conferi tudo: está tudo certo"
        for (i in ruins) {
            val arrumou = when (i.id) {
                "adb" -> AdbAuto.ligarManual(app)
                "acess" -> Adb.connected && Sys.ligarAcessibilidade()
                else -> false
            }
            linhas += if (arrumou) "✓ ${i.nome}: estava ${i.txt}, já arrumei" else "⚠ ${i.nome}: ${i.txt}. ${i.dica}"
        }
        val ff = Prefs.bypassFf
        val (a, b) = Downloader.pastasInstaladas(ff)
        if (!a || !b) linhas += instalarPastas(app)
        return linhas.joinToString("\n")
    }

    private fun abrirTela(act: MainActivity, nome: String) {
        when (nome) {
            "conexao" -> act.irAba(1)
            "estudio" -> act.irAba(2)
            "logs" -> act.irAba(3)
            "bypass" -> act.abre(BypassScreen(act))
            "recursos" -> act.abre(RecursosScreen(act))
            "bot" -> act.abre(SniperScreen(act))
            "otimizacao" -> act.abre(OtmScreen(act))
            "competitivo" -> act.abre(CompScreen(act))
            "ajustes" -> act.abre(AjustesScreen(act))
            "ajuda" -> act.abre(AjudaScreen(act))
            "caderno" -> act.abre(CadernoScreen(act))
            "rank" -> act.abre(RankScreen(act))
            "eventos" -> act.abre(EventosScreen(act))
            "partidas" -> act.abre(PartidasScreen(act))
            else -> act.irAba(0)
        }
    }
}

// ---------------------------------------------------------------- comandos grátis (sem IA)
object Comandos {
    private fun limpa(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private val LIGA = Regex("\\b(liga|ligar|ligue|ativa|ativar|ative|inicia|iniciar|inicie|comeca|comecar|comece|abre|abrir|abra|coloca|colocar|coloque|adiciona|adicionar|adicione|add|bota|botar|bote|poe|joga|jogar|entra|entrar|conecta|conectar|conecte|instala|instalar|instale|roda|rodar)\\b")
    private val DESLIGA = Regex("\\b(desliga|desligar|desligue|desativa|desativar|desative|para|parar|pare|tira|tirar|tire|remove|remover|remova|fecha|fechar|feche|cancela|cancelar|cancele|pausa|pausar|pause|restaura|restaurar)\\b")
    private val PAUSA = Regex("\\b(pausa|pausar|pause)\\b")
    private val NUM = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "meia" to -1)
    private val TEMPO = Regex("\\b(em|daqui a|daqui|depois de|de|apos|dentro de|por|durante)\\s+(\\d+|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta|quarenta|meia)\\s*(segundos?|seg|s|minutos?|mins?|m|horas?|hrs?|h)\\b")
    private val HORA = Regex("\\bas\\s+(\\d{1,2})(?:\\s*(?:[:h])\\s*(\\d{2})?|\\s+horas?)")
    private val MENU = Regex("\\b(atalho|atalhos|menu|topo|configurac\\w*|bloco|barra)\\b")

    private fun tem(p: String, r: String) = Regex(r).containsMatchIn(p)

    /** entende o pedido. Vazio = não entendeu nada */
    fun entender(texto: String): List<Passo> {
        val partes = limpa(texto)
            .split(Regex("\\s*(?:,|;|\\.|!|\\be depois\\b|\\bdepois disso\\b|\\bem seguida\\b|\\be entao\\b|\\bentao\\b|\\be\\b)\\s*"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val passos = ArrayList<Passo>()
        var ultimoLiga: Boolean? = null
        var ultimoAtraso = 0L
        var ultimoQuando = 0L
        for (p in partes) {
            val desl = DESLIGA.containsMatchIn(p)
            val lig = LIGA.containsMatchIn(p)
            val liga: Boolean? = when { desl -> false; lig -> true; else -> ultimoLiga }
            // tempo: "em 5 minutos" (atraso) ou "por 30 minutos" (liga agora e desliga depois) ou "às 20h"
            var atraso = 0L
            var duracao = 0L
            TEMPO.find(p)?.let { m ->
                val n = m.groupValues[2].toIntOrNull() ?: NUM[m.groupValues[2]] ?: 0
                val u = m.groupValues[3]
                val ms = when {
                    u.startsWith("h") -> if (n == -1) 30 * 60_000L else n * 3_600_000L
                    u.startsWith("s") -> n * 1000L
                    else -> if (n == -1) 30_000L else n * 60_000L
                }
                if (m.groupValues[1] == "por" || m.groupValues[1] == "durante") duracao = ms else atraso = ms
            }
            var quando = 0L
            HORA.find(p)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && mi in 0..59) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                    quando = cal.timeInMillis
                }
            }
            // sem tempo nesta parte, mas a anterior tinha ("em 5 min desliga o bot e o bypass"): herda
            val semAcaoPropria = !desl && !lig
            if (atraso == 0L && quando == 0L && duracao == 0L && semAcaoPropria) { atraso = ultimoAtraso; quando = ultimoQuando }

            val achados = ArrayList<String>()
            val menu = MENU.containsMatchIn(p)
            when {
                tem(p, "\\b(erro|erros|problema|problemas|bug|bugs|diagnostic\\w*|verifica\\w*|arruma\\w*|conserta\\w*|checa\\w*|testa o app)\\b") -> achados += "verificar"
                tem(p, "agend") && liga == false -> achados += "cancelar_agendados"
                tem(p, "\\b(tela|aba)\\b") || tem(p, "^(vai|va|ir) (pra|para)\\b") -> achados += "tela:" + qualTela(p)
                else -> {
                    if (tem(p, "\\b(pasta|pastas|recurso|recursos)\\b") && !tem(p, "\\b(bypass|baipas|bypas)\\b")) achados += "instalar_pastas"
                    if (tem(p, "\\bmeet\\b")) achados += if (liga == false) "menu_tirar_meet" else "menu_meet"
                    if (tem(p, "toque rapido") && (menu || tem(p, "\\bmeet\\b"))) achados += if (liga == false) "menu_tirar_toque" else "menu_toque"
                    else if (tem(p, "\\b(competitivo|comp|toque rapido)\\b")) achados += if (liga == false) "comp_desligar" else "comp_ligar"
                    if (tem(p, "\\b(bypass|bypas|baipas|bai pass)\\b")) achados += if (liga == false) "bypass_desligar" else "bypass_ligar"
                    if (tem(p, "\\b(bot|bots|fila|sniper|robo)\\b")) achados += if (liga == false) (if (PAUSA.containsMatchIn(p)) "bot_pausar" else "bot_desligar") else "bot_ligar"
                    if (tem(p, "\\b(jogo|free ?fire|ff|freefire|partida)\\b") && liga != false) achados += "jogo_abrir"
                    if (tem(p, "otimiz")) achados += if (liga == false) "otm_desligar" else "otm_ligar"
                    if (tem(p, "\\b(adb|depuracao|conexao)\\b") && liga != false) achados += "adb_ligar"
                }
            }
            for (a in achados) {
                val (acao, arg) = if (a.startsWith("tela:")) "tela" to a.substring(5) else a to ""
                passos += Passo(acao, atraso, arg, quando)
                if (duracao > 0) Acoes.contrario[acao]?.let { passos += Passo(it, atraso + duracao, "", 0L) }
            }
            if (achados.isNotEmpty() || lig || desl) ultimoLiga = liga
            if (achados.isNotEmpty()) { ultimoAtraso = atraso; ultimoQuando = quando }
        }
        // "verifica os erros e arruma" virava 2 vezes a mesma coisa
        return passos.distinctBy { "${it.acao}|${it.arg}|${it.atrasoMs}|${it.quando}" }
    }

    private fun qualTela(p: String): String = when {
        tem(p, "inicio|home|comeco") -> "inicio"
        tem(p, "conexao|adb") -> "conexao"
        tem(p, "bypass") -> "bypass"
        tem(p, "recurso|pasta") -> "recursos"
        tem(p, "\\bbot\\b|fila") -> "bot"
        tem(p, "otimiz") -> "otimizacao"
        tem(p, "competitivo") -> "competitivo"
        tem(p, "ajuste") -> "ajustes"
        tem(p, "estudio|tema|visual|cor") -> "estudio"
        tem(p, "log|erro") -> "logs"
        tem(p, "ajuda|suporte|staff") -> "ajuda"
        tem(p, "caderno") -> "caderno"
        tem(p, "rank") -> "rank"
        tem(p, "evento") -> "eventos"
        tem(p, "partida") -> "partidas"
        else -> "inicio"
    }
}

// ---------------------------------------------------------------- IA de verdade (pelo servidor do NOCRY)
object IA {
    class Resposta(val texto: String, val passos: List<Passo>)

    /** a IA de verdade disse que não está ligada no servidor: não pergunta de novo por 10 min */
    @Volatile private var semIaAte = 0L
    @Volatile var ligada = false
        private set

    /** null = IA de verdade indisponível agora (sem chave no servidor ou sem internet): usa os comandos grátis */
    fun perguntar(c: Context, texto: String, hist: List<Pair<String, String>>): Resposta? {
        if (System.currentTimeMillis() < semIaAte) return null
        val h = JSONArray()
        hist.takeLast(6).forEach { h.put(JSONObject().put("role", it.first).put("content", it.second.take(600))) }
        val acoes = JSONObject()
        Acoes.nomes.forEach { (k, v) -> acoes.put(k, v) }
        val j = Comunidade.x(c, "ia", JSONObject().put("texto", texto.take(500)).put("estado", estado(c)).put("hist", h).put("acoes", acoes))
            ?: return null
        if (!j.optBoolean("ok")) {
            if (j.optString("reason") == "ia_limite") return Resposta("Você usou todas as perguntas da IA de hoje. Até amanhã eu entendo os comandos simples.", emptyList())
            ligada = false
            semIaAte = System.currentTimeMillis() + 10 * 60_000L
            return null
        }
        ligada = true
        val passos = ArrayList<Passo>()
        val arr = j.optJSONArray("passos") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), 12)) {
            val o = arr.optJSONObject(i) ?: continue
            val acao = o.optString("acao")
            if (acao !in Acoes.nomes) continue
            val min = o.optDouble("min", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1440.0) }
            passos += Passo(acao, (min * 60_000L).toLong(), o.optString("arg").take(20))
        }
        return Resposta(j.optString("resposta").take(1200), passos)
    }

    /** o que a IA precisa saber do app agora (sem token, pix, e-mail: passa pela limpeza do Sentinela) */
    fun estado(c: Context): JSONObject {
        val ff = Prefs.bypassFf
        val (a, b) = try { Downloader.pastasInstaladas(ff) } catch (_: Throwable) { Pair(false, false) }
        val logs = try { Sentinela.limpa(NLog.text().takeLast(1500)) ?: "" } catch (_: Throwable) { "" }
        return JSONObject()
            .put("bypass", if (Prefs.bypassAtivo || Prefs.bypassVolAtivo) "ligado (${Prefs.bypassGatilho})" else "desligado")
            .put("bot", if (Sniper.buscando) "buscando" else if (Sniper.conectado) "conectado, sem buscar" else "desligado")
            .put("adb", if (Adb.connected) "conectado" else "desconectado")
            .put("permissoes", if (Sys.hasWriteSecure()) "ok" else "faltando")
            .put("pastas", "suja ${if (a) "instalada" else "falta"}, limpa ${if (b) "instalada" else "falta"} ($ff)")
            .put("otimizacao", if (Otm.status().first) "ligada" else "desligada")
            .put("competitivo", if (Comp.ligado()) "ligado" else "desligado")
            .put("menu_topo", "meet ${if (MenuRapido.noMenu(true)) "sim" else "não"}, toque rápido ${if (MenuRapido.noMenu(false)) "sim" else "não"}")
            .put("agendados", Agenda.lista().joinToString("; ") { Acoes.hora(it.quando) + " " + it.acao })
            .put("versao", License.versao(c))
            .put("erros_novos", try { Erros.novos() } catch (_: Throwable) { 0 })
            .put("log", logs)
    }
}

// ---------------------------------------------------------------- agenda ("daqui a 5 minutos", "às 20h")
object Agenda {
    class Item(val id: Int, val quando: Long, val acao: String, val arg: String)

    private const val CHAVE = "ia_agenda"
    private val trava = Any()
    private val main = Handler(Looper.getMainLooper())

    fun lista(): List<Item> = synchronized(trava) {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(Prefs.extraTxt(CHAVE) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Item(o.getInt("id"), o.getLong("q"), o.getString("a"), o.optString("g"))
            }
        } catch (_: Throwable) {}
        out.sortedBy { it.quando }
    }

    private fun salva(l: List<Item>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("id", it.id).put("q", it.quando).put("a", it.acao).put("g", it.arg)) }
        Prefs.extraTxt(CHAVE, arr.toString())
    }

    fun adicionar(c: Context, p: Passo): Item = synchronized(trava) {
        val quando = if (p.quando > 0) p.quando else System.currentTimeMillis() + p.atrasoMs
        val id = ((System.nanoTime() / 1000) % 1_000_000).toInt() + lista().size
        val it = Item(id, quando, p.acao, p.arg)
        salva(lista() + it)
        arma(c.applicationContext, it)
        it
    }

    private fun pi(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, 60_000 + id % 100_000, Intent(c, IaReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun arma(c: Context, it: Item) {
        // alarme do Android: dispara mesmo com o app fechado
        try {
            val am = c.getSystemService(AlarmManager::class.java)
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.quando, pi(c, it.id))
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.quando, pi(c, it.id))
            }
        } catch (_: Throwable) {}
        // e um relógio aqui dentro, que acerta o minuto certinho enquanto o app está aberto
        main.postDelayed({ rodarVencidos(c) }, maxOf(0L, it.quando - System.currentTimeMillis()) + 200L)
    }

    fun cancelar(c: Context, id: Int) = synchronized(trava) {
        try { c.getSystemService(AlarmManager::class.java)?.cancel(pi(c.applicationContext, id)) } catch (_: Throwable) {}
        salva(lista().filter { it.id != id })
    }

    fun cancelarTudo(c: Context) = synchronized(trava) {
        lista().forEach { try { c.getSystemService(AlarmManager::class.java)?.cancel(pi(c.applicationContext, it.id)) } catch (_: Throwable) {} }
        salva(emptyList())
    }

    /** faz o que já passou da hora (alarme ou relógio do app). Cada coisa roda uma vez só */
    fun rodarVencidos(c: Context) {
        val app = c.applicationContext
        val vencidos: List<Item> = synchronized(trava) {
            val agora = System.currentTimeMillis()
            val (v, resto) = lista().partition { it.quando <= agora + 1000 }
            if (v.isNotEmpty()) salva(resto)
            v
        }
        for (v in vencidos) Bg.io {
            val r = Acoes.fazer(app, null, Passo(v.acao, 0L, v.arg))
            try {
                Notif.evento(app, "IA DO NOCRY", r.removePrefix("✓ ").removePrefix("✗ ").removePrefix("⚠ "),
                    if (r.startsWith("✓")) (if (v.acao.endsWith("desligar") || v.acao.startsWith("menu_tirar")) Notif.Efeito.DESLIGOU else Notif.Efeito.LIGOU) else Notif.Efeito.FALHOU)
            } catch (_: Throwable) {}
            Assistente.anotar(false, "⏰ ${Acoes.hora(v.quando)} · $r")
        }
    }
}

/** alarme da agenda da IA: faz a ação mesmo com o app fechado */
class IaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { Prefs.init(context); Notif.init(context) } catch (_: Throwable) {}
        val pr = goAsync()
        Bg.io {
            try { Agenda.rodarVencidos(context.applicationContext); Thread.sleep(8000) } catch (_: Throwable) {} finally { try { pr.finish() } catch (_: Throwable) {} }
        }
    }
}

// ---------------------------------------------------------------- a conversa
object Assistente {
    class Msg(val eu: Boolean, val texto: String)

    /** a conversa fica na memória enquanto o app está aberto */
    val conversa = ArrayList<Msg>()
    @Volatile var mudou = 0
        private set

    fun anotar(eu: Boolean, texto: String) {
        synchronized(conversa) { conversa.add(Msg(eu, texto)); if (conversa.size > 60) conversa.removeAt(0) }
        mudou++
    }

    val AJUDA = "Não entendi. Exemplos do que eu faço:\n• liga o bypass, liga o bot e desliga o bot em 5 minutos\n• liga o bot por 30 minutos\n• abre o jogo\n• coloca o Meet e o toque rápido no menu do topo\n• às 22h desliga o bot\n• verifica os erros e arruma\n• cancela os agendados"

    /** responde e faz tudo (bloqueia: chame fora da tela) */
    fun responder(c: Context, act: MainActivity?, texto: String): String {
        val hist = synchronized(conversa) { conversa.dropLast(1).takeLast(6).map { (if (it.eu) "user" else "assistant") to it.texto } }
        val ia = try { IA.perguntar(c, texto, hist) } catch (_: Throwable) { null }
        val passos = ia?.passos ?: Comandos.entender(texto)
        val linhas = ArrayList<String>()
        if (ia != null && ia.texto.isNotBlank()) linhas += ia.texto
        if (passos.isEmpty() && ia == null) return AJUDA
        for (p in passos) linhas += Acoes.fazerOuAgendar(c, act, p)
        return linhas.joinToString("\n").ifBlank { "Pronto." }
    }
}

class AssistenteScreen(a: MainActivity) : Screen(a) {
    override val titulo = "IA do NOCRY"
    private var pensando = false

    private val campo: EditText by lazy {
        Ui.field(act, "escreva o que você quer", false).also { e ->
            e.imeOptions = EditorInfo.IME_ACTION_SEND
            e.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviar(); true } else false }
        }
    }

    override fun key(): String = "${Assistente.mudou}|$pensando|${Agenda.lista().size}|${IA.ligada}"

    fun enviar(texto: String? = null) {
        val t = (texto ?: campo.text.toString()).trim()
        if (t.isEmpty() || pensando) return
        campo.setText("")
        Assistente.anotar(true, t)
        pensando = true
        redesenha()
        val c = act.applicationContext
        Bg.io {
            val r = Assistente.responder(c, act, t)
            Bg.ui {
                Assistente.anotar(false, r)
                pensando = false
                redesenha()
            }
        }
    }

    private fun balao(eu: Boolean, texto: String): View {
        val c = act
        val linha = Ui.hbox(c)
        linha.gravity = if (eu) Gravity.END else Gravity.START
        val t = Ui.text(c, texto, 12.5f, Ui.TEXT, eu)
        t.setLineSpacing(0f, 1.15f)
        t.setPadding(Ui.dp(c, 13), Ui.dp(c, 10), Ui.dp(c, 13), Ui.dp(c, 10))
        t.background = if (eu) Ui.shape(c, Theme.alpha(android.graphics.Color.WHITE, 0x24), 16, Theme.alpha(android.graphics.Color.WHITE, 0x50), 1) else Ui.glass(c)
        linha.addView(if (eu) View(c) else t, LinearLayout.LayoutParams(if (eu) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (eu) 0.18f else 0f))
        if (eu) linha.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        else linha.addView(View(c), LinearLayout.LayoutParams(0, 1, 0.18f))
        return linha
    }

    override fun render() {
        limpa()
        val c = act
        val vazio = synchronized(Assistente.conversa) { Assistente.conversa.isEmpty() }
        if (vazio) {
            add(Ui.dica(c, "Escreva do seu jeito o que você quer e eu faço. Ex.: \"liga o bypass, liga o bot e desliga o bot em 5 minutos\". " +
                (if (IA.ligada) "IA de verdade ligada: entendo qualquer frase e explico os erros." else "Por enquanto entendo os comandos do app (bypass, bot, jogo, otimização, competitivo, menu do topo, horários). A IA de verdade liga quando o dono colocar a chave no servidor."),
                Ui.Dica.INFO), 0)
            add(Ui.secao(c, "experimente"), 18)
            val sug = listOf("Liga o bypass e o bot", "Liga o bot por 30 minutos", "Abre o jogo", "Verifica os erros e arruma",
                "Coloca o Meet e o toque rápido no menu do topo", "Às 23h desliga o bot")
            sug.chunked(2).forEach { par ->
                val l = Ui.hbox(c)
                par.forEachIndexed { i, s ->
                    val b = Ui.text(c, s, 10.5f, Ui.TEXT, true)
                    b.gravity = Gravity.CENTER
                    b.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10))
                    b.background = Ui.ripple(c, Ui.fundoPeq(c, Ui.raioPeq(14)))
                    Ui.clique(b) { enviar(s) }
                    l.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { if (i > 0) it.leftMargin = Ui.dp(c, 8) })
                }
                if (par.size == 1) l.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).also { it.leftMargin = Ui.dp(c, 8) })
                add(l, 8)
            }
        } else {
            val msgs = synchronized(Assistente.conversa) { Assistente.conversa.toList() }
            msgs.forEachIndexed { i, m -> add(balao(m.eu, m.texto), if (i == 0) 4 else 10) }
        }
        if (pensando) add(Ui.text(c, "fazendo...", 10.5f, Ui.MUTED, true, mono = true), 10)

        // o que está agendado, com botão pra cancelar
        val ag = Agenda.lista()
        if (ag.isNotEmpty()) {
            add(Ui.secao(c, "agendado"), 20)
            ag.forEach { it ->
                val r = Ui.hbox(c)
                r.gravity = Gravity.CENTER_VERTICAL
                r.background = Ui.glass(c)
                r.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10))
                r.addView(Ui.text(c, Acoes.hora(it.quando), 13f, Ui.TEXT, true, mono = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { lp -> lp.rightMargin = Ui.dp(c, 12) })
                r.addView(Ui.text(c, Acoes.descricao(Passo(it.acao, 0L, it.arg)), 11f, Ui.MUTED, true).also { t -> Ui.umaLinha(t, 8) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val x = Ui.text(c, "cancelar", 10f, Ui.WARN, true, mono = true)
                x.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6))
                val id = it.id
                Ui.clique(x) { Agenda.cancelar(act, id); redesenha() }
                r.addView(x)
                add(r, 8)
            }
        }

        // campo + enviar
        val linha = Ui.hbox(c)
        linha.gravity = Gravity.CENTER_VERTICAL
        (campo.parent as? ViewGroup)?.removeView(campo)
        linha.addView(campo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val env = FrameLayout(c)
        env.background = Ui.ripple(c, Botao(c, "grad"), Botao(c, "grad", mascara = true))
        env.addView(Ui.icone(c, R.drawable.ic_play, Ui.corSobreGrande(), 18), FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18), Gravity.CENTER))
        env.contentDescription = "enviar"
        Ui.clique(env) { enviar() }
        linha.addView(env, LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)).also { it.leftMargin = Ui.dp(c, 8) })
        add(linha, 18)
    }
}
