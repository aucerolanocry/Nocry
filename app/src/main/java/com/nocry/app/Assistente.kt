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
class Passo(val acao: String, val atrasoMs: Long = 0L, val arg: String = "", val quando: Long = 0L, val semPerguntar: Boolean = false)

object Acoes {
    /** tudo que a IA sabe fazer (os mesmos nomes vão pro servidor da IA) */
    val nomes: Map<String, String> = linkedMapOf(
        "bypass_ligar" to "ligar o bypass e ficar esperando o gatilho (arg: o gatilho dnd = não perturbe, bt = bluetooth, carga = carregador ou volume, e o tipo completo, normal ou simples; ex.: \"dnd completo\"). NÃO INVENTE: só ponha no arg o que a pessoa DISSE; se ela não disse o tipo ou o gatilho, deixe o arg vazio (ou só com o que ela disse) que o app mesmo pergunta o que faltou",
        "bypass_aplicar" to "aplicar o bypass AGORA no jogo, na hora, sem esperar gatilho (arg: o tipo completo, normal ou simples, SÓ se a pessoa disse; se não disse, deixe o arg vazio que o app pergunta)",
        "bypass_desligar" to "desligar o bypass (restaura o jogo)",
        "bypass_ff" to "escolher pra qual Free Fire vai o bypass (arg: normal ou max). Use antes de bypass_ligar ou bypass_aplicar quando a pessoa disser qual Free Fire",
        "bot_ligar" to "ligar o bot de fila e começar a buscar (arg opcional: modo e valores em reais, ex.: \"2x2 4 3\" = modo 2x2 e valores R$ 4 e R$ 3)",
        "bot_configurar" to "só escolher o modo e os valores do bot de fila, sem ligar (arg: ex.: \"2x2 4 3\")",
        "bot_desligar" to "desligar o bot de fila",
        "bot_pausar" to "pausar a busca do bot (continua conectado)",
        "jogo_abrir" to "abrir o Free Fire (arg: normal ou max)",
        "abrir_app" to "abrir qualquer app do celular (arg: nome do app, ex.: Play Store, WhatsApp, YouTube, Discord, Câmera, Configurações)",
        "pesquisar" to "pesquisar algo no Google (arg: o que pesquisar)",
        "youtube" to "procurar um vídeo no YouTube (arg: o que procurar)",
        "alarme" to "criar um alarme no relógio do celular (arg: HH:MM)",
        "lanterna_ligar" to "ligar a lanterna",
        "lanterna_desligar" to "desligar a lanterna",
        "otm_ligar" to "ligar a otimização",
        "otm_desligar" to "desligar a otimização agora (o modo automático continua ligado; pra ele parar também, use otm_auto_desligar)",
        "otm_auto_ligar" to "ligar o modo automático da otimização (ela liga sozinha quando o Free Fire abre e desliga quando você sai)",
        "otm_auto_desligar" to "desligar o modo automático da otimização (ela para de ligar sozinha quando o Free Fire abre)",
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
        "cancelar_agendados" to "cancelar tudo que estava agendado",
        "palavra_criar" to "criar uma palavra-chave: quando a pessoa FALAR essa palavra, o app faz o que ela pediu (arg: a palavra, uma barra | e o que fazer; ex.: \"bora jogar|abre o free fire e liga o bypass completo\")",
        "palavra_remover" to "apagar uma palavra-chave que a pessoa criou (arg: a palavra)",
        "palavra_listar" to "mostrar as palavras-chave que a pessoa já criou"
    )

    /** o contrário de cada ação (pra "liga o bot por 30 minutos") */
    val contrario = mapOf(
        "bypass_ligar" to "bypass_desligar", "bypass_aplicar" to "bypass_desligar", "bot_ligar" to "bot_desligar",
        "otm_ligar" to "otm_desligar", "otm_auto_ligar" to "otm_auto_desligar", "comp_ligar" to "comp_desligar"
    )

    fun descricao(p: Passo): String = when (p.acao) {
        "tela" -> "abrir a tela ${p.arg}"
        "abrir_app" -> "abrir ${p.arg}"
        "pesquisar" -> "pesquisar \"${p.arg}\""
        "youtube" -> "procurar \"${p.arg}\" no YouTube"
        "alarme" -> "alarme às ${p.arg}"
        "jogo_abrir" -> if (p.arg == "max") "abrir o Free Fire MAX" else if (p.arg == "normal") "abrir o Free Fire" else "abrir o jogo"
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
            val (gat, tipo) = bypassArgs(p.arg)
            val ativo = Prefs.bypassAtivo || Prefs.bypassVolAtivo
            val atualGat = if (Prefs.bypassVolAtivo) "volume" else Prefs.bypassGatilho
            val mudaGat = gat != null && gat != atualGat
            val mudaTipo = tipo != null && tipo != Prefs.bypassTipo
            if ((ativo || Prefs.bypassMark) && (mudaGat || mudaTipo)) religarBypass(app, act, p)
            else if (ativo) "✓ o bypass já estava ligado"
            // não disse o tipo ou o gatilho: pergunta antes de ligar (pedidos agendados e retomados não perguntam)
            else if (!Prefs.bypassMark && !p.semPerguntar && (gat == null || tipo == null)) perguntarBypass(false, gat, tipo)
            else {
                // o gatilho e o tipo que você pediu viram a escolha do app (só dá pra trocar com o bypass desligado)
                val gatFinal = if (Prefs.bypassMark) Prefs.bypassGatilho else (gat ?: Prefs.bypassGatilho)
                val volume = gatFinal == "volume"
                // sem dizer qual Free Fire: pergunta antes. Depois, se faltar pasta, instala sozinha e liga quando terminar
                val pergunta = if (Prefs.bypassMark) null else perguntarFf(app, p)
                val erro = pergunta ?: antesDoBypass(app, volume, true, p)
                if (erro != null) erro
                else {
                    if (!Prefs.bypassMark) { Prefs.bypassGatilho = gatFinal; tipo?.let { Prefs.bypassTipo = it } }
                    if (volume) EngineBypass.iniciarVolume() else EngineBypass.iniciar(Prefs.bypassGatilho, Prefs.bypassFf, Prefs.bypassTipo)
                    "✓ bypass ${Prefs.bypassTipo} ligado · gatilho: ${nomeGatilho(Prefs.bypassGatilho)}"
                }
            }
        }
        "bypass_aplicar" -> {
            val tipo = bypassArgs(p.arg).second
            val parado = !(Prefs.bypassAtivo || Prefs.bypassVolAtivo)
            if (Prefs.bypassMark && tipo != null && tipo != Prefs.bypassTipo) religarBypass(app, act, p)
            else if (parado && !Prefs.bypassMark && !p.semPerguntar && tipo == null) perguntarBypass(true, null, null)
            else (if (Prefs.bypassMark) null else ((if (parado) perguntarFf(app, p) else null) ?: antesDoBypass(app, false, parado, p))) ?: when (EngineBypass.aplicarAgora(tipo)) {
                0 -> "✓ bypass ${Prefs.bypassTipo} aplicado agora no ${nomeFfBypass()}"
                2 -> "✓ o bypass já estava aplicado"
                else -> "✗ não consegui aplicar o bypass (ADB). Peça \"liga o ADB\" e tente de novo"
            }
        }
        "bypass_ff" -> {
            val quer = ffDoArg(p.arg)
            val ligado = Prefs.bypassMark || Prefs.bypassAtivo || Prefs.bypassVolAtivo
            if (quer != null && ligado && quer != Prefs.bypassFf) {
                // trocar de Free Fire com o bypass ligado: desliga (o jogo antigo volta ao normal), escolhe o novo e liga de novo do mesmo jeito
                val rc = EngineBypass.parar()
                if (rc == 1 || Prefs.bypassMark || Prefs.bypassAtivo || Prefs.bypassVolAtivo) "✗ não consegui desligar o bypass pra trocar de Free Fire (ADB). Peça \"liga o ADB\" e tente de novo"
                else {
                    val escolha = escolherFf(p.arg)
                    if (!escolha.startsWith("✓")) escolha
                    else escolha + "\n" + fazerSeguro(app, act, Passo("bypass_ligar", 0L, "", 0L, true))
                }
            } else escolherFf(p.arg)
        }
        "bypass_desligar" -> when (EngineBypass.parar()) {
            0 -> "✓ bypass desligado e o jogo restaurado"
            1 -> "⚠ bypass desligado, mas não deu pra restaurar o jogo (ADB). Peça \"liga o ADB\" e de novo \"desliga o bypass\""
            else -> "✓ bypass desligado"
        }
        "palavra_criar" -> criarPalavra(p.arg)
        "palavra_remover" -> removerPalavra(p.arg)
        "palavra_listar" -> listarPalavras()
        "bot_ligar" -> ligarBot(p.arg)
        "bot_configurar" -> configurarBot(p.arg) ?: "✗ diga o modo e os valores, ex.: \"bot no modo 2x2 valores 4 e 3\""
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
            // "max" / "normal" vindo do pedido manda; sem isso, o que está escolhido no app
            val pref = when (p.arg.lowercase()) { "max" -> Const.FF_MAX; "normal" -> Const.FF_NORMAL; else -> if (Prefs.jogarFf == "max") Const.FF_MAX else Const.FF_NORMAL }
            val tem = { pk: String -> pm.getLaunchIntentForPackage(pk) != null }
            val pkg = if (tem(pref)) pref else listOf(Const.FF_NORMAL, Const.FF_MAX).firstOrNull(tem)
            val nome = { pk: String -> if (pk == Const.FF_MAX) "Free Fire MAX" else "Free Fire" }
            if (pkg == null) "✗ o Free Fire não está instalado"
            else {
                val i = pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = abrirAtividade(app, act, i)
                if (!ok) "✗ não consegui abrir o jogo (abra o app e peça de novo)"
                else if (pkg != pref) "✓ abrindo o ${nome(pkg)} (o ${nome(pref)} não está instalado)"
                else "✓ abrindo o ${nome(pkg)}"
            }
        }
        "abrir_app" -> abrirApp(app, act, p.arg)
        "pesquisar" -> abrirLink(app, act, "https://www.google.com/search?q=" + java.net.URLEncoder.encode(p.arg, "UTF-8"), "✓ pesquisando \"${p.arg}\"")
        "youtube" -> abrirLink(app, act, "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(p.arg, "UTF-8"), "✓ procurando \"${p.arg}\" no YouTube")
        "alarme" -> {
            val m = Regex("(\\d{1,2})\\D?(\\d{2})?").find(p.arg)
            val h = m?.groupValues?.get(1)?.toIntOrNull()
            val mi = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            if (h == null || h !in 0..23 || mi !in 0..59) "✗ não entendi a hora do alarme"
            else {
                val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mi)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "NOCRY").putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = abrirAtividade(app, act, i)
                if (ok) "✓ alarme criado pras %02d:%02d".format(h, mi) else "✗ o relógio do celular não aceitou criar o alarme"
            }
        }
        "lanterna_ligar", "lanterna_desligar" -> {
            val liga = p.acao == "lanterna_ligar"
            try {
                val cm = app.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id == null) "✗ esse celular não tem lanterna" else { cm.setTorchMode(id, liga); if (liga) "✓ lanterna ligada" else "✓ lanterna desligada" }
            } catch (_: Throwable) { "✗ não consegui mexer na lanterna" }
        }
        "otm_ligar" -> if (Otm.status().first) "✓ a otimização já estava ligada"
            else if (Otm.ativar {}) "✓ otimização ligada" else "✗ otimização: o ADB não está conectado"
        "otm_desligar" -> if (Otm.desativar {}) "✓ otimização desligada (tudo voltou ao normal)" +
                (if (Prefs.otmAuto) ". O modo automático continua ligado: ela liga de novo quando o Free Fire abrir. Peça \"desliga o modo automático\" pra parar isso" else "")
            else "✗ otimização: o ADB não está conectado"
        "otm_auto_ligar" -> if (Prefs.otmAuto) "✓ o modo automático já estava ligado"
            else if (!Adb.connected && !Adb.ensure(app)) "✗ modo automático: o ADB não está conectado. Peça \"liga o ADB\""
            else { OtmAuto.ligar(); "✓ modo automático ligado: a otimização liga sozinha quando o Free Fire abrir" }
        "otm_auto_desligar" -> if (!Prefs.otmAuto) "✓ o modo automático já estava desligado"
            else { OtmAuto.desligar(); "✓ modo automático desligado: não liga mais sozinho ao abrir o Free Fire" }
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

    /** abre uma tela (jogo, app, link, alarme). Com o NOCRY minimizado o Android bloqueia isso;
     *  aí abre pelo ADB, que pode (quando está conectado) */
    private fun abrirAtividade(app: Context, act: MainActivity?, i: Intent): Boolean {
        if (act?.naFrente == true) return naTela { act.startActivity(i); true } == true
        if (Adb.connected || Adb.ensure(app)) {
            val r = try { Adb.exec(amStart(i), 8000) } catch (_: Throwable) { null }
            if (r != null && r.ok && !r.out.contains("Error", true)) return true
        }
        return naTela { app.startActivity(i); true } == true
    }

    /** o mesmo pedido de abrir tela, escrito como comando do ADB */
    @Suppress("DEPRECATION")
    private fun amStart(i: Intent): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val sb = StringBuilder("am start")
        i.action?.let { sb.append(" -a ").append(q(it)) }
        i.data?.let { sb.append(" -d ").append(q(it.toString())) }
        i.categories?.forEach { sb.append(" -c ").append(q(it)) }
        val comp = i.component
        if (comp != null) sb.append(" -n ").append(q(comp.flattenToShortString()))
        else i.`package`?.let { sb.append(" -p ").append(q(it)) }
        i.extras?.let { b ->
            for (k in b.keySet()) {
                when (val v = b.get(k)) {
                    is String -> sb.append(" --es ").append(q(k)).append(' ').append(q(v))
                    is Int -> sb.append(" --ei ").append(q(k)).append(' ').append(v)
                    is Boolean -> sb.append(" --ez ").append(q(k)).append(' ').append(v)
                    is Long -> sb.append(" --el ").append(q(k)).append(' ').append(v)
                }
            }
        }
        sb.append(" -f 0x10000000")
        return sb.toString()
    }

    private fun limpaNome(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** abre um app pelo nome falado ("play store", "zap", "configurações"...). Nunca abre o próprio NOCRY */
    private fun abrirApp(app: Context, act: MainActivity?, nomeBruto: String): String {
        val alvo = limpaNome(nomeBruto).removePrefix("o ").removePrefix("a ").removePrefix("app ").removePrefix("aplicativo ").trim()
        if (alvo.isEmpty()) return "✗ qual app você quer abrir?"
        val apelidos = mapOf("play store" to "com.android.vending", "playstore" to "com.android.vending", "loja" to "com.android.vending",
            "google play" to "com.android.vending", "zap" to "com.whatsapp", "whats" to "com.whatsapp", "whatsapp" to "com.whatsapp",
            "insta" to "com.instagram.android", "face" to "com.facebook.katana", "yt" to "com.google.android.youtube")
        if (alvo == "configuracoes" || alvo == "configuracao" || alvo == "ajustes do celular") {
            val ok = abrirAtividade(app, act, Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return if (ok) "✓ abrindo as configurações do celular" else "✗ não consegui abrir as configurações"
        }
        val pm = app.packageManager
        val lista = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) } catch (_: Throwable) { emptyList() }
        val apps = lista.mapNotNull { r ->
            val pk = r.activityInfo?.packageName ?: return@mapNotNull null
            if (pk == Const.PKG || pk == app.packageName) null else pk to limpaNome(r.loadLabel(pm).toString())
        }.distinctBy { it.first }
        val porApelido = apelidos[alvo]?.takeIf { pk -> apps.any { it.first == pk } }
        val achado = porApelido ?: apps.firstOrNull { it.second == alvo }?.first
            ?: apps.firstOrNull { it.second.startsWith(alvo) || alvo.startsWith(it.second) }?.first
            ?: apps.firstOrNull { it.second.contains(alvo) }?.first
            ?: apps.maxByOrNull { a -> alvo.split(" ").count { w -> w.length > 2 && a.second.contains(w) } }
                ?.takeIf { a -> alvo.split(" ").any { w -> w.length > 2 && a.second.contains(w) } }?.first
        if (achado == null) return "✗ não achei o app \"$nomeBruto\" no celular"
        val i = pm.getLaunchIntentForPackage(achado)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "✗ não consegui abrir \"$nomeBruto\""
        val ok = abrirAtividade(app, act, i)
        val rotulo = apps.firstOrNull { it.first == achado }?.let { a -> try { pm.getApplicationLabel(pm.getApplicationInfo(a.first, 0)).toString() } catch (_: Throwable) { nomeBruto } } ?: nomeBruto
        return if (ok) "✓ abrindo $rotulo" else "✗ não consegui abrir $rotulo (abra o NOCRY e peça de novo)"
    }

    private fun abrirLink(app: Context, act: MainActivity?, url: String, okTxt: String): String {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = abrirAtividade(app, act, i)
        return if (ok) okTxt else "✗ não consegui abrir o navegador"
    }

    /** o gatilho e o tipo do bypass que vieram no pedido (null = não falou) */
    fun bypassArgs(arg: String): Pair<String?, String?> {
        val a = Comandos.limpa(arg)
        val g = when {
            Regex("\\b(dnd|nao perturb\\w*|nao pertub\\w*)\\b").containsMatchIn(a) -> "dnd"
            Regex("\\b(bt|bluetooth|blutuf\\w*)\\b").containsMatchIn(a) -> "bt"
            Regex("\\b(carga|carregador|tomada)\\b").containsMatchIn(a) -> "carga"
            Regex("\\b(volume|vol)\\b").containsMatchIn(a) -> "volume"
            else -> null
        }
        val t = when {
            a.contains("completo") -> "completo"
            a.contains("simples") -> "simples"
            Regex("\\bnormal\\b").containsMatchIn(a) -> "normal"
            else -> null
        }
        return g to t
    }

    /** o que a pessoa DISSE no pedido inteiro (o "normal" só vale como tipo quando vem junto de "bypass"; senão é o Free Fire normal) */
    fun bypassDito(texto: String): Pair<String?, String?> {
        val a = Comandos.limpa(texto)
        val (g, _) = bypassArgs(a)
        val t = when {
            a.contains("completo") -> "completo"
            a.contains("simples") -> "simples"
            Regex("\\b(bypass|bypas|baipas)\\s+(do\\s+tipo\\s+|tipo\\s+|modo\\s+)?normal\\b|\\b(tipo|modo)\\s+normal\\b").containsMatchIn(a) -> "normal"
            else -> null
        }
        return g to t
    }

    private fun nomeGatilho(g: String) = when (g) { "dnd" -> "não perturbe"; "bt" -> "bluetooth"; "carga" -> "carregador"; "volume" -> "volume"; else -> g }

    // ---- bypass: qual Free Fire e pastas que faltam
    /** quando você escolheu o Free Fire (pra não perguntar de novo logo depois) */
    @Volatile private var ffEscolhidoEm = 0L
    /** o pedido de bypass que ficou esperando você dizer qual Free Fire */
    @Volatile private var pendenteFf: Passo? = null
    @Volatile private var pendenteFfAte = 0L

    /** o pedido de bypass que ficou esperando você dizer o tipo (completo, normal ou simples) e/ou o gatilho */
    private class PendenteBypass(val aplicar: Boolean, val gat: String?, val tipo: String?, val ate: Long)
    @Volatile private var pendBy: PendenteBypass? = null

    private const val P_TIPO = "Qual bypass você quer: completo, normal ou simples?"
    private const val P_GAT = "Qual gatilho: não perturbe, volume, bluetooth ou carregador?"

    /** ligar/aplicar sem dizer o tipo (e o gatilho): pergunta o que faltou e guarda o pedido */
    private fun perguntarBypass(aplicar: Boolean, gat: String?, tipo: String?): String {
        pendBy = PendenteBypass(aplicar, gat, tipo, System.currentTimeMillis() + 2 * 60_000L)
        return when {
            tipo == null && gat == null && !aplicar -> "$P_TIPO E qual gatilho: não perturbe, volume, bluetooth ou carregador? Pode responder os dois juntos, ex.: \"completo no volume\"."
            tipo == null -> P_TIPO
            else -> P_GAT
        }
    }

    /** a resposta curta ("completo", "no volume", "normal no dnd") completa o pedido do bypass que estava esperando */
    private fun respostaBypass(c: Context, act: MainActivity?, b: PendenteBypass, texto: String): String? {
        if (System.currentTimeMillis() > b.ate) { pendBy = null; return null }
        val t = Comandos.limpa(texto).trim()
        if (t.length > 40 || t.split(" ").size > 6) return null
        val (g, ty) = bypassArgs(t)
        if (g == null && ty == null) {
            if (Regex("\\b(nao|cancela\\w*|deixa|esquece|esquec\\w+)\\b").containsMatchIn(t)) { pendBy = null; return "Tudo bem, não liguei o bypass." }
            return null
        }
        val gat = g ?: b.gat
        val tipo = ty ?: b.tipo
        if (tipo == null || (!b.aplicar && gat == null)) return perguntarBypass(b.aplicar, gat, tipo)
        pendBy = null
        val arg = listOfNotNull(if (b.aplicar) null else gat, tipo).joinToString(" ")
        return fazer(c, act, Passo(if (b.aplicar) "bypass_aplicar" else "bypass_ligar", 0L, arg))
    }

    /** o que a pessoa DISSE manda: o que a IA pôs a mais no tipo/gatilho do bypass é tirado (ela não escolhe por você) */
    fun limparBypassIA(passos: List<Passo>, falado: String): List<Passo> {
        val (g, t) = bypassDito(falado)
        return passos.map { p ->
            when (p.acao) {
                "bypass_ligar" -> Passo(p.acao, p.atrasoMs, listOfNotNull(g, t).joinToString(" "), p.quando, p.semPerguntar)
                "bypass_aplicar" -> Passo(p.acao, p.atrasoMs, t ?: "", p.quando, p.semPerguntar)
                else -> p
            }
        }
    }

    /** o pedido de bypass que espera as pastas terminarem de instalar */
    @Volatile private var retomarDepois: Passo? = null
    @Volatile private var retomarAte = 0L

    private fun nomeFfBypass() = if (Prefs.bypassFf == "max") "Free Fire MAX" else "Free Fire"

    /** pergunta pra qual Free Fire é o bypass (só se o celular tem os dois e você não disse). null = pode seguir */
    private fun perguntarFf(app: Context, p: Passo): String? {
        val agora = System.currentTimeMillis()
        if (p.semPerguntar || agora - ffEscolhidoEm < 10 * 60_000L) return null
        val pm = app.packageManager
        val temNormal = pm.getLaunchIntentForPackage(Const.FF_NORMAL) != null
        val temMax = pm.getLaunchIntentForPackage(Const.FF_MAX) != null
        if (!temNormal && !temMax) return "✗ o Free Fire não está instalado no celular"
        if (temNormal != temMax) {                      // só um instalado: usa ele, não precisa perguntar
            Prefs.bypassFf = if (temMax) "max" else "normal"
            ffEscolhidoEm = agora
            return null
        }
        pendenteFf = p
        pendenteFfAte = agora + 3 * 60_000L
        return "Pra qual Free Fire é o bypass: o normal ou o MAX? Me responde só \"normal\" ou \"max\"."
    }

    /** o bypass está ligado e você pediu outro gatilho, tipo ou Free Fire: desliga (o jogo volta ao normal) e liga do jeito novo, de uma vez */
    private fun religarBypass(app: Context, act: MainActivity?, p: Passo): String {
        val rc = EngineBypass.parar()
        if (rc == 1 || Prefs.bypassMark || Prefs.bypassAtivo || Prefs.bypassVolAtivo)
            return "✗ não consegui desligar o bypass pra trocar (ADB). Peça \"liga o ADB\" e tente de novo"
        ffEscolhidoEm = System.currentTimeMillis()     // o Free Fire já era conhecido (estava em uso): não pergunta de novo
        return fazerSeguro(app, act, p)
    }

    /** "max" / "normal" dentro do que a pessoa disse (null = não disse) */
    private fun ffDoArg(arg: String): String? {
        val a = Comandos.limpa(arg)
        return when {
            Regex("\\bmax\\b").containsMatchIn(a) -> "max"
            Regex("\\b(normal|comum|original)\\b").containsMatchIn(a) -> "normal"
            else -> null
        }
    }

    /** guarda a escolha do Free Fire do bypass (com o bypass ligado, quem chama desliga antes) */
    private fun escolherFf(arg: String): String {
        val novo = ffDoArg(arg) ?: return "✗ diga qual Free Fire: normal ou max"
        val nome = if (novo == "max") "Free Fire MAX" else "Free Fire"
        if (novo != Prefs.bypassFf && (Prefs.bypassMark || Prefs.bypassAtivo || Prefs.bypassVolAtivo))
            return "✗ o bypass já está ligado no ${nomeFfBypass()}. Peça \"desliga o bypass\" antes de trocar pro $nome"
        Prefs.bypassFf = novo
        ffEscolhidoEm = System.currentTimeMillis()
        return "✓ o bypass vai usar o $nome"
    }

    // ---- palavras-chave pelo próprio chat ("quando eu falar bora jogar, abre o Free Fire e liga o bypass")
    private val RE_PALAVRA_VIRGULA = Regex("""\b(?:quando|se|toda vez que|sempre que)\s+eu\s+(?:falar|disser|dizer|digitar|escrever|mandar)\s+(?:a\s+)?(?:palavra\s+|frase\s+)?["“']?([^,"”'\n]{2,40}?)["”']?\s*[,:]\s*(?:então\s+|entao\s+|pode\s+|você\s+|voce\s+|vc\s+)*(?:(?:vai\s+|pode\s+)?(?:fazer|faz|faça|faca|executar|executa|execute)\s+)?(.{3,200})""", RegexOption.IGNORE_CASE)
    private val RE_PALAVRA_FAZ = Regex("""\b(?:quando|se|toda vez que|sempre que)\s+eu\s+(?:falar|disser|dizer|digitar|escrever|mandar)\s+(?:a\s+)?(?:palavra\s+|frase\s+)?["“']?(.{2,40}?)["”']?\s+(?:então\s+|entao\s+)?(?:você\s+|voce\s+|vc\s+)?(?:vai\s+)?(?:fazer|faz|faça|faca|executa|executar|execute)\s+(.{3,200})""", RegexOption.IGNORE_CASE)
    private val RE_PALAVRA_CRIAR = Regex("""\b(?:cria|criar|crie|adiciona|adicionar|adicione|coloca|colocar|coloque|configura|configurar|configure|salva|salvar|salve|faz|faça)\s+(?:uma\s+|a\s+)?(?:nova\s+)?palavra[\s-]?chave\s+["“']?([^"”':,\n]{2,40}?)["”']?\s*(?::|,|=|\s+-\s+|\s+que\s+(?:faz|fazer|executa|executar|vai\s+fazer)\s+|\s+que\s+(?=\w)|\s+para\s+(?:fazer\s+)?|\s+pra\s+(?:fazer\s+)?|\s+faz\s+|\s+faça\s+)\s*(.{3,200})""", RegexOption.IGNORE_CASE)
    private val RE_PALAVRA_REMOVER = Regex("""\b(?:apaga|apagar|apague|remove|remover|remova|tira|tirar|tire|deleta|deletar|delete|exclui|excluir|exclua)\s+(?:a\s+)?palavra[\s-]?chave\s+["“']?(.{2,40}?)["”']?\s*[.!?]*\s*\z""", RegexOption.IGNORE_CASE)
    private val RE_PALAVRA_LISTAR = Regex("""\b(?:quais|qual|lista|listar|liste|mostra|mostrar|mostre|ver|veja)\b.{0,30}palavras?[\s-]?chaves?""", RegexOption.IGNORE_CASE)

    /** (palavra, o que fazer) se a fala é pra criar uma palavra-chave. "quando eu falar X, faz Y" sem dizer "palavra-chave"
     *  só vale se o Y é algo que o app entende (liga o bypass, abre o jogo...): senão é só uma frase normal (a IA decide) */
    private fun acharCriar(t: String): Pair<String, String>? {
        RE_PALAVRA_CRIAR.find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        if (t.contains('?')) return null
        val m = RE_PALAVRA_VIRGULA.find(t) ?: RE_PALAVRA_FAZ.find(t) ?: return null
        val faz = m.groupValues[2].trim()
        if (Comandos.entender(faz).none { it.acao != "pesquisar" && it.acao != "youtube" }) return null
        return m.groupValues[1].trim() to faz
    }

    /** a fala é pra criar, apagar ou listar palavra-chave (aí a palavra-chave que já existe não dispara no meio) */
    fun gerePalavra(texto: String): Boolean {
        val t = texto.trim()
        return t.length >= 8 && (RE_PALAVRA_REMOVER.containsMatchIn(t) || RE_PALAVRA_LISTAR.containsMatchIn(t) || acharCriar(t) != null)
    }

    /** entende sozinha (sem esperar a IA) o pedido de criar, apagar ou listar palavra-chave. null = não era isso */
    fun palavraLocal(c: Context, act: MainActivity?, texto: String): String? {
        val t = texto.trim()
        if (t.length < 8) return null
        RE_PALAVRA_REMOVER.find(t)?.let { return fazer(c, act, Passo("palavra_remover", 0L, it.groupValues[1])) }
        acharCriar(t)?.let { return fazer(c, act, Passo("palavra_criar", 0L, it.first + "|" + it.second)) }
        if (RE_PALAVRA_LISTAR.containsMatchIn(t)) return fazer(c, act, Passo("palavra_listar", 0L, ""))
        return null
    }

    private fun criarPalavra(arg: String): String {
        val i = arg.indexOf('|')
        val palavra = (if (i >= 0) arg.substring(0, i) else arg).trim().trim('"', '“', '”', '\'').trim().take(40)
        val faz = (if (i >= 0) arg.substring(i + 1) else "").trim().trim('"', '“', '”').trim().take(300)
        if (PalavrasChave.limpa(palavra).length < 3) return "✗ a palavra-chave precisa ter pelo menos 3 letras"
        if (faz.length < 3) return "✗ me diga o que fazer quando você falar \"$palavra\""
        val jaTinha = PalavrasChave.lista().any { PalavrasChave.limpa(it.palavra) == PalavrasChave.limpa(palavra) }
        PalavrasChave.adicionar(palavra, faz)
        return "✓ " + (if (jaTinha) "troquei a palavra-chave" else "criei a palavra-chave") + " \"$palavra\": quando você falar ela, eu vou: $faz"
    }

    private fun removerPalavra(arg: String): String {
        val k = PalavrasChave.limpa(arg)
        val achou = PalavrasChave.lista().firstOrNull { PalavrasChave.limpa(it.palavra) == k }
            ?: PalavrasChave.lista().firstOrNull { k.isNotEmpty() && PalavrasChave.limpa(it.palavra).contains(k) }
            ?: return "✗ não achei a palavra-chave \"${arg.trim()}\""
        PalavrasChave.remover(achou.palavra)
        return "✓ apaguei a palavra-chave \"${achou.palavra}\""
    }

    private fun listarPalavras(): String {
        val l = PalavrasChave.lista()
        if (l.isEmpty()) return "Você ainda não tem palavras-chave. Diga, por exemplo: \"quando eu falar bora jogar, abre o Free Fire e liga o bypass\"."
        return "Suas palavras-chave:\n" + l.joinToString("\n") { "• \"${it.palavra}\" → ${it.faz}" }
    }

    /** ela está esperando você dizer qual Free Fire (a resposta curta "max" / "normal" tem que chegar nela mesmo sem chamar o nome) */
    fun aguardandoFf(): Boolean = (pendenteFf != null && System.currentTimeMillis() <= pendenteFfAte) || (pendBy?.let { System.currentTimeMillis() <= it.ate } == true)

    /** você respondeu a pergunta do Free Fire ("max", "o normal"): escolhe e faz o que estava esperando. null = não era a resposta */
    fun respostaPendente(c: Context, act: MainActivity?, texto: String): String? {
        pendBy?.let { b -> respostaBypass(c, act, b, texto)?.let { return it } }
        val p = pendenteFf ?: return null
        if (System.currentTimeMillis() > pendenteFfAte) { pendenteFf = null; return null }
        val t = Comandos.limpa(texto).trim()
        if (t.length > 40) return null
        val max = Regex("\\bmax\\b").containsMatchIn(t)
        val normal = !max && Regex("\\b(normal|comum|original)\\b").containsMatchIn(t)
        if (!max && !normal) {
            if (Regex("\\b(nao|cancela\\w*|deixa|esquece|esquec\\w+)\\b").containsMatchIn(t)) { pendenteFf = null; return "Tudo bem, não liguei o bypass." }
            return null
        }
        pendenteFf = null
        val escolha = fazer(c, act, Passo("bypass_ff", 0L, if (max) "max" else "normal"))
        if (!escolha.startsWith("✓")) return escolha
        return escolha + "\n" + fazer(c, act, p)
    }

    /** falta a pasta suja: põe pra instalar e guarda o pedido pra ligar o bypass quando terminar */
    private fun instalarPraBypass(app: Context, ff: String, retomar: Passo?): String {
        val nome = if (ff == "max") "Free Fire MAX" else "Free Fire"
        instalarPastas(app)
        if (retomar != null) {
            retomarDepois = Passo(retomar.acao, 0L, retomar.arg, 0L, true)
            retomarAte = System.currentTimeMillis() + 30 * 60_000L
            ffEscolhidoEm = System.currentTimeMillis()
        }
        return "✓ faltavam as pastas do $nome pro bypass. Já estou instalando (acompanha na notificação)" +
            (if (retomar != null) ". Quando terminar eu ligo o bypass sozinha." else ".")
    }

    /** chamado quando uma pasta termina de instalar: se era o que o bypass esperava, liga agora */
    fun retomarAposInstalar(app: Context) {
        val p = retomarDepois ?: return
        if (System.currentTimeMillis() > retomarAte) { retomarDepois = null; return }
        if (!Downloader.pastasInstaladas(Prefs.bypassFf).first) return      // a pasta suja ainda não ficou pronta (o bypass só precisa dela; a limpa pode terminar depois)
        retomarDepois = null
        ffEscolhidoEm = System.currentTimeMillis()
        Bg.io {
            val r = fazer(app, null, p)
            try {
                Notif.evento(app, "IA DO NOCRY", r.removePrefix("✓ ").removePrefix("✗ ").removePrefix("⚠ "),
                    if (r.startsWith("✓")) Notif.Efeito.LIGOU else Notif.Efeito.FALHOU)
            } catch (_: Throwable) {}
            Assistente.anotar(false, "As pastas terminaram de instalar. $r")
        }
    }

    /** a instalação falhou ou foi cancelada: não liga o bypass depois */
    fun cancelarRetomar() { retomarDepois = null }

    /** o que precisa estar certo antes de ligar ou aplicar o bypass. null = tudo certo */
    private fun antesDoBypass(app: Context, volume: Boolean, contaLimite: Boolean, retomar: Passo? = null): String? {
        if (!Adb.ensure(app)) return "✗ bypass: o ADB não está conectado. Peça \"liga o ADB\" ou abra a Conexão"
        Downloader.esquecerPastas()
        // falta a pasta suja: instala sozinha (em segundo plano), avisa, e liga o bypass quando terminar
        if (!Downloader.pastasInstaladas(Prefs.bypassFf).first) return instalarPraBypass(app, Prefs.bypassFf, retomar)
        if (volume && !(Sys.acessibilidadeLigada() || Sys.ligarAcessibilidade())) return "✗ bypass: a escuta do volume está desligada (precisa do ADB)"
        // o limite do dia só gasta quando o bypass é ligado (se já estava ligado, já foi contado)
        if (contaLimite && !Config.verificarLimite()) return "✗ bypass: chegou no limite de hoje"
        return null
    }

    private val MODO_ARG = Regex("\\b([1-4]|um|dois|tres|quatro)\\s*(?:x|por|vs|v|contra)\\s*([1-4]|um|dois|tres|quatro)\\b")
    private val NUM_ARG = Regex("\\d+(?:[.,]\\d+)?|\\b(?:um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta)\\b")
    private val PALAVRA_NUM = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30)

    /** escolhe o modo e os valores do bot pelo texto ("2x2 4 3" = modo 2x2, valores R$ 4 e R$ 3). null = não achou nada */
    private fun configurarBot(arg: String): String? {
        val a = Comandos.limpa(arg)
        fun n(s: String): Int? = s.toIntOrNull() ?: PALAVRA_NUM[s]
        val modos = MODO_ARG.findAll(a).mapNotNull { m ->
            val x = n(m.groupValues[1]); val y = n(m.groupValues[2])
            if (x != null && y != null) "${x}X$y" else null
        }.filter { it in Sniper.MODOS }.toSet()
        val resto = MODO_ARG.replace(a, " ")
        val valores = NUM_ARG.findAll(resto).mapNotNull { m ->
            val d = m.value.replace(',', '.').toDoubleOrNull() ?: n(m.value)?.toDouble()
            d?.toInt()?.takeIf { it in 1..999 }?.let { (it * 100).toString() }
        }.toList().distinct()
        if (modos.isEmpty() && valores.isEmpty()) return null
        return Sniper.escolherPeloComando(modos, valores)
    }

    private fun ligarBot(arg: String = ""): String {
        // "no modo 2x2 valores 4 e 3": escolhe antes de ligar
        val cfg = if (arg.isNotBlank()) configurarBot(arg) else null
        val r = ligarBotSemEscolha()
        return if (cfg != null) cfg + "\n" + r else r
    }

    private fun ligarBotSemEscolha(): String {
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
    internal fun limpa(s: String): String =
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

    // ---- bypass: "agora" (aplica na hora), tipo (completo/normal/simples) e gatilho (não perturbe, bluetooth, carregador, volume)
    private val AGORA = Regex("\\b(agora|ja|na hora|imediat\\w*|nesse segundo|neste segundo|nesse instante|neste instante|aplica|aplicar|aplique)\\b")

    private fun bypassLigar(p: String): String {
        val tipo = when {
            tem(p, "\\bcompleto\\b") -> "completo"
            tem(p, "\\bsimples\\b") -> "simples"
            tem(p, "\\b(bypass|bypas|baipas)\\s+(do\\s+tipo\\s+|tipo\\s+|modo\\s+)?normal\\b|\\b(tipo|modo)\\s+normal\\b") -> "normal"
            else -> ""
        }
        val gat = when {
            tem(p, "\\b(dnd|nao perturb\\w*|nao pertub\\w*)\\b") -> "dnd"
            tem(p, "\\b(bt|bluetooth|blutuf\\w*)\\b") -> "bt"
            tem(p, "\\b(carregador|tomada)\\b") -> "carga"
            tem(p, "\\bvolume\\b") -> "volume"
            else -> ""
        }
        if (AGORA.containsMatchIn(p)) return "bypass_aplicar" + (if (tipo.isNotEmpty()) ":$tipo" else "")
        val args = listOf(gat, tipo).filter { it.isNotEmpty() }.joinToString(" ")
        return "bypass_ligar" + (if (args.isNotEmpty()) ":$args" else "")
    }

    // ---- bot de fila: "no modo 2x2 valores 4 e 3". Tira isso do texto antes de separar os pedidos (o "e" separaria)
    private const val NUMP = "(?:\\d+(?:[.,]\\d{1,2})?|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta)"
    private val MODO_FILA = Regex("\\b(?:(?:no|nos|de|do)?\\s*modos?\\s+)?([1-4]|um|dois|tres|quatro)\\s*(?:x|por|vs|v|contra)\\s*([1-4]|um|dois|tres|quatro)\\b")
    private val VALORES_FILA = Regex("\\b(?:(?:nos?|de)\\s+)?(?:valor(?:es)?|r\\$)\\s*(?:de\\s+)?(?:r\\$\\s*)?($NUMP(?:\\s*(?:,|e|ou|mais|\\+)\\s*$NUMP)*)(?:\\s*(?:reais|real|conto|contos|pila|pilas))?")
    private val NUM_PALAVRA = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30)

    /** (texto sem o modo/valores, "2x2 4 3" ou null) */
    private fun separarConfigBot(limpo: String): Pair<String, String?> {
        if (!tem(limpo, "\\b(bot|bots|fila|sniper|robo)\\b")) return limpo to null
        fun n(s: String): Int? = s.toIntOrNull() ?: NUM_PALAVRA[s]
        val modos = ArrayList<String>()
        MODO_FILA.findAll(limpo).forEach { m ->
            val x = n(m.groupValues[1]); val y = n(m.groupValues[2])
            if (x != null && y != null) modos += "${x}x$y"
        }
        val valores = ArrayList<String>()
        VALORES_FILA.findAll(limpo).forEach { m ->
            Regex("\\d+|\\b(?:um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta)\\b").findAll(m.groupValues[1].replace(',', ' ')).forEach { v ->
                val num = n(v.value)
                if (num != null) valores += num.toString()
            }
        }
        if (modos.isEmpty() && valores.isEmpty()) return limpo to null
        val sem = VALORES_FILA.replace(MODO_FILA.replace(limpo, " "), " ").replace(Regex("\\s+"), " ").trim()
        return sem to (modos.distinct() + valores.distinct()).joinToString(" ")
    }

    /** "e a otimização?", "também o bot", "e o outro": continua o que você pediu antes (liga/desliga) */
    private val CONTINUA = Regex("^\\s*(e|tambem|e tambem|mais|mas tambem|pode ser|agora)\\b")

    /** entende o pedido. Vazio = não entendeu nada. [anterior] = o que você pediu por último (true = ligar, false = desligar) */
    fun entender(texto: String, anterior: Boolean? = null): List<Passo> {
        val limpo = limpa(texto)
        val (limpoSem, cfgBot) = separarConfigBot(limpo)
        val partes = limpoSem
            .split(Regex("\\s*(?:,|;|\\.|!|\\be depois\\b|\\bdepois disso\\b|\\bem seguida\\b|\\be entao\\b|\\bentao\\b|\\be\\b)\\s*"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val passos = ArrayList<Passo>()
        var ultimoLiga: Boolean? = if (anterior != null && CONTINUA.containsMatchIn(limpo)) anterior else null
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
            val alarme = tem(p, "\\b(alarme|despertador|me acorda|me acordar)\\b")
            if (alarme) quando = 0L    // "alarme às 7h" é a hora do alarme, não pra agendar
            when {
                alarme -> HORA.find(p)?.let { m -> achados += "alarme:" + m.groupValues[1] + ":" + (m.groupValues[2].ifEmpty { "00" }) }
                tem(p, "\\blanterna\\b") -> achados += if (liga == false) "lanterna_desligar" else "lanterna_ligar"
                tem(p, "\\byoutube\\b") && tem(p, "\\b(procura|procurar|procure|pesquisa|pesquisar|pesquise|busca|buscar|video|videos)\\b") ->
                    achados += "youtube:" + p.replace(Regex(".*?\\b(procura|procurar|procure|pesquisa|pesquisar|pesquise|busca|buscar)\\b"), "").replace(Regex("\\b(no|na) youtube\\b|\\byoutube\\b|\\bvideos? de\\b"), "").trim()
                tem(p, "^(pesquisa|pesquisar|pesquise|procura|procurar|procure|busca|buscar|googla|googlar)\\b") ->
                    achados += "pesquisar:" + p.replace(Regex("^(pesquisa|pesquisar|pesquise|procura|procurar|procure|busca|buscar|googla|googlar)\\s*(no google|na internet|sobre|por)?\\s*"), "").trim()
                tem(p, "\\b(erro|erros|problema|problemas|bug|bugs|diagnostic\\w*|verifica\\w*|arruma\\w*|conserta\\w*|checa\\w*|testa o app)\\b") -> achados += "verificar"
                tem(p, "agend") && liga == false -> achados += "cancelar_agendados"
                tem(p, "\\b(tela|aba)\\b") || tem(p, "^(vai|va|ir) (pra|para)\\b") -> achados += "tela:" + qualTela(p)
                else -> {
                    if (tem(p, "\\b(pasta|pastas|recurso|recursos)\\b") && !tem(p, "\\b(bypass|baipas|bypas)\\b")) achados += "instalar_pastas"
                    if (tem(p, "\\bmeet\\b")) achados += if (liga == false) "menu_tirar_meet" else "menu_meet"
                    if (tem(p, "toque rapido") && (menu || tem(p, "\\bmeet\\b"))) achados += if (liga == false) "menu_tirar_toque" else "menu_toque"
                    else if (tem(p, "\\b(competitivo|comp|toque rapido)\\b")) achados += if (liga == false) "comp_desligar" else "comp_ligar"
                    if (tem(p, "\\b(bypass|bypas|baipas|bai pass)\\b")) {
                        if (liga != false) {
                            // "liga o bypass no free fire max": escolhe o Free Fire antes (o "normal" sozinho é o tipo do bypass, não o jogo)
                            if (tem(p, "\\bmax\\b")) achados += "bypass_ff:max"
                            else if (tem(p, "\\b(free ?fire|ff)\\s+(normal|comum)\\b|\\b(normal|comum)\\s+(free ?fire|ff)\\b")) achados += "bypass_ff:normal"
                        }
                        achados += if (liga == false) "bypass_desligar" else bypassLigar(p)
                    }
                    if (tem(p, "\\b(bot|bots|fila|sniper|robo)\\b")) achados += when {
                        liga == false -> if (PAUSA.containsMatchIn(p)) "bot_pausar" else "bot_desligar"
                        // só falou o modo e os valores, sem "liga": só escolhe
                        cfgBot != null && !lig -> "bot_configurar:$cfgBot"
                        cfgBot != null -> "bot_ligar:$cfgBot"
                        else -> "bot_ligar"
                    }
                    if (tem(p, "\\b(jogo|free ?fire|ff|freefire|partida)\\b") && liga != false && !tem(p, "\\b(bypass|bypas|baipas|bai pass)\\b"))
                        achados += "jogo_abrir" + (if (tem(p, "\\bmax\\b")) ":max" else if (tem(p, "\\bnormal\\b")) ":normal" else "")
                    // "modo auto" / "automático" sozinho (sem bot, bypass...) é o modo automático da otimização
                    val outroAssunto = tem(p, "\\b(bot|bots|fila|sniper|robo|bypass|bypas|baipas|competitivo|comp|toque|meet|adb)\\b")
                    if (tem(p, "\\b(auto|automatico|automatica|automaticamente)\\b") && !outroAssunto) achados += if (liga == false) "otm_auto_desligar" else "otm_auto_ligar"
                    else if (tem(p, "otimiz")) achados += if (liga == false) "otm_desligar" else "otm_ligar"
                    if (tem(p, "\\b(adb|depuracao|conexao)\\b") && liga != false) achados += "adb_ligar"
                }
            }
            if (achados.isEmpty() && tem(p, "^(abre|abrir|abra|entra no|entra na|entrar no|entrar na)\\b")) {
                val nome = p.replace(Regex("^(abre|abrir|abra|entra no|entra na|entrar no|entrar na)\\s+(o |a |os |as |app |aplicativo )?"), "").trim()
                if (nome.isNotEmpty()) achados += "abrir_app:$nome"
            }
            for (a in achados) {
                val i = a.indexOf(':')
                val (acao, arg) = if (i > 0) a.substring(0, i) to a.substring(i + 1) else a to ""
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

/** roda uma etapa com tempo máximo: se passar, para de esperar (a etapa termina sozinha em segundo plano) e anota quanto demorou no log */
object Limite {
    fun <T> rodar(nome: String, ms: Long, padrao: T, bloco: () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val caixa = java.util.concurrent.atomic.AtomicReference<T?>(null)
        val erro = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val feito = CountDownLatch(1)
        Bg.io {
            try { caixa.set(bloco()) } catch (t: Throwable) { erro.set(t) } finally { feito.countDown() }
        }
        val ok = try { feito.await(ms, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        if (!ok) { NLog.add("ia", "$nome: passou de ${ms / 1000}s, parei de esperar"); return padrao }
        val e = erro.get()
        if (e != null) { NLog.add("ia", "$nome: erro ${e.javaClass.simpleName} (${dt}ms)"); return padrao }
        if (dt >= 2000) NLog.add("ia", "$nome: ${dt}ms")
        @Suppress("UNCHECKED_CAST")
        return caixa.get() as T
    }
}

// ---------------------------------------------------------------- IA de verdade (pelo servidor do NOCRY)
object IA {
    class Resposta(val texto: String, val passos: List<Passo>)

    /** a IA de verdade disse que não está ligada no servidor: não pergunta de novo por 10 min */
    @Volatile private var semIaAte = 0L
    @Volatile var ligada = false
        private set

    /** pedido pro servidor com tempo maior que o normal (a IA pode levar uns segundos pensando) */
    private fun postIa(c: Context, corpo: JSONObject): JSONObject? = post(c, "ia", corpo, if (corpo.has("imagem")) 50000 else 30000, 8000)

    fun post(c: Context, op: String, corpo: JSONObject, leitura: Int = 50000, conexao: Int = 15000): JSONObject? {
        val base = BuildConfig.API_URL.trim().trimEnd('/')
        val key = Prefs.licKey ?: return null
        if (base.isEmpty()) return null
        corpo.put("key", key).put("dev", License.devId(c)).put("app", License.versao(c)).put("op", op)
        return try {
            val cx = java.net.URL("$base/v1/x").openConnection() as java.net.HttpURLConnection
            cx.requestMethod = "POST"
            cx.connectTimeout = conexao
            cx.readTimeout = leitura
            cx.doOutput = true
            cx.setRequestProperty("Content-Type", "application/json")
            cx.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val st = if (cx.responseCode in 200..299) cx.inputStream else cx.errorStream
            JSONObject(st?.bufferedReader()?.use { it.readText() } ?: return null)
        } catch (_: Throwable) { null }
    }

    /** null = IA de verdade indisponível agora (sem internet, demorou demais, cota do dia acabou): usa os comandos grátis */
    fun perguntar(c: Context, texto: String, hist: List<Pair<String, String>>, imagem: String? = null): Resposta? {
        if (System.currentTimeMillis() < semIaAte && imagem == null) return null
        val h = JSONArray()
        hist.takeLast(8).forEach { h.put(JSONObject().put("role", it.first).put("content", it.second.take(600))) }
        val acoes = JSONObject()
        Acoes.nomes.forEach { (k, v) -> acoes.put(k, v) }
        // o "estado" junta várias coisas do celular: se alguma travar, segue sem ele em vez de prender o pedido
        val est = Limite.rodar("ia/estado", 5_000L, JSONObject().put("aviso", "estado indisponível agora")) { estado(c) }
        val corpo = JSONObject().put("texto", texto.take(800)).put("estado", est).put("hist", h).put("acoes", acoes)
        if (imagem != null) corpo.put("imagem", imagem)
        val j = postIa(c, corpo) ?: return null
        if (!j.optBoolean("ok")) {
            if (j.optString("reason") == "ia_limite") return Resposta("Você usou todas as perguntas da IA de hoje. Até amanhã eu entendo os comandos simples.", emptyList())
            ligada = false
            // sem IA no servidor: espera 10 min pra perguntar de novo; erro passageiro: tenta de novo no próximo pedido
            if (j.optString("reason") == "sem_ia") semIaAte = System.currentTimeMillis() + 10 * 60_000L
            return null
        }
        ligada = true
        // qual cérebro respondeu (gemini, groq, cloudflare): só o nome, pra saber pelo log se a fila está funcionando
        j.optString("fonte").takeIf { it.isNotBlank() }?.let { NLog.add("ia", "cérebro: $it") }
        val passos = ArrayList<Passo>()
        val arr = j.optJSONArray("passos") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), 12)) {
            val o = arr.optJSONObject(i) ?: continue
            val acao = o.optString("acao")
            if (acao !in Acoes.nomes) continue
            val min = o.optDouble("min", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1440.0) }
            passos += Passo(acao, (min * 60_000L).toLong(), o.optString("arg").take(if (acao == "palavra_criar") 220 else 80))
        }
        return Resposta(j.optString("resposta").take(1200), passos)
    }

    /** o que a IA precisa saber do app agora (sem token, pix, e-mail: passa pela limpeza do Sentinela) */
    fun estado(c: Context): JSONObject {
        val ff = Prefs.bypassFf
        val (a, b) = try { Downloader.pastasInstaladas(ff) } catch (_: Throwable) { Pair(false, false) }
        // o log da própria escuta (silêncio, "não entendi", microfone ocupado) é normal: não manda pra IA,
        // senão ela acha que o app está com erro e fica falando disso / rodando "verificar" sem você pedir
        val logs = try {
            Sentinela.limpa(NLog.text().lines().filterNot { it.contains("escuta:") || it.contains("segundo plano não ligou") }.joinToString("\n").takeLast(1500)) ?: ""
        } catch (_: Throwable) { "" }
        return JSONObject()
            .put("bypass", if (Prefs.bypassAtivo || Prefs.bypassVolAtivo) "ligado (${Prefs.bypassGatilho})" else "desligado")
            .put("bot", if (Sniper.buscando) "buscando" else if (Sniper.conectado) "conectado, sem buscar" else "desligado")
            .put("adb", if (Adb.connected) "conectado" else "desconectado")
            .put("permissoes", if (Sys.hasWriteSecure()) "ok" else "faltando")
            .put("pastas", "suja ${if (a) "instalada" else "falta"}, limpa ${if (b) "instalada" else "falta"} ($ff)")
            .put("otimizacao", if (Otm.status().first) "ligada" else "desligada")
            .put("otimizacao_modo_auto", if (Prefs.otmAuto) "ligado" else "desligado")
            .put("competitivo", if (Comp.ligado()) "ligado" else "desligado")
            .put("menu_topo", "meet ${if (MenuRapido.noMenu(true)) "sim" else "não"}, toque rápido ${if (MenuRapido.noMenu(false)) "sim" else "não"}")
            .put("agendados", Agenda.lista().joinToString("; ") { Acoes.hora(it.quando) + " " + it.acao })
            .put("free_fire_instalado", listOf(Const.FF_NORMAL to "normal", Const.FF_MAX to "max")
                .filter { (pk, _) -> try { c.packageManager.getLaunchIntentForPackage(pk) != null } catch (_: Throwable) { false } }.joinToString { it.second }.ifEmpty { "nenhum" })
            .put("hora_celular", SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date()))
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
            val r = Acoes.fazer(app, null, Passo(v.acao, 0L, v.arg, 0L, true))
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

    /** o que você pediu por último (true = ligar, false = desligar): "e a otimização?" continua daí */
    @Volatile private var ultimoLiga: Boolean? = null

    /** o que estava escrito no campo quando você saiu da tela (volta quando você voltar) */
    @Volatile var rascunho = ""

    private val pendentes = java.util.concurrent.atomic.AtomicInteger(0)
    /** tem pedido sendo feito agora (continua mesmo se você sair da tela) */
    val pensando: Boolean get() = pendentes.get() > 0
    private val fila = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nocry-ia").apply { isDaemon = true } }
    private val CUTUCA = Regex("^\\W*(cad[eê]|e a[ií]|e ent[aã]o|al[oô]|responde\\w*|ei|t[aá] a[ií]|demor\\w+|oi|continua\\w*|termina\\w*|u[eê]|uai|vai)\\W*$", RegexOption.IGNORE_CASE)
    /** só "?", "??", "?!", "..." */
    private val SO_PONTUACAO = Regex("^[\\s?!.,…¿¡]+$")

    /** pedidos seus que ficaram sem resposta de verdade (tempo esgotado, erro, servidor fora): um "?" depois refaz eles */
    private class SemResp(val texto: String, val em: Long)
    private val semResposta = ArrayList<SemResp>()
    private fun marcarSemResposta(t: String) = synchronized(semResposta) {
        semResposta.removeAll { it.texto == t }
        semResposta.add(SemResp(t, System.currentTimeMillis()))
        while (semResposta.size > 3) semResposta.removeAt(0)
    }
    private fun marcarRespondido(t: String) = synchronized(semResposta) { semResposta.removeAll { it.texto == t } }
    /** os pedidos sem resposta dos últimos 10 min (null = não tem nenhum) */
    private fun retomar(): String? = synchronized(semResposta) {
        val agora = System.currentTimeMillis()
        semResposta.removeAll { agora - it.em > 10 * 60_000L }
        if (semResposta.isEmpty()) null else semResposta.joinToString(". ") { it.texto }.also { semResposta.clear() }
    }

    /**
     * faz o pedido escrito FORA da tela: você pode sair da conversa, minimizar o app ou digitar outro pedido
     * que ela continua, faz o que você mandou e deixa a resposta no chat (e avisa na tela se você estiver em outra).
     * Vários pedidos seguidos entram numa fila e são respondidos na ordem.
     */
    fun pedir(c: Context, act: MainActivity?, texto: String, imagem: String? = null, localPrimeiro: Boolean = false, aoFim: ((String) -> Unit)? = null) {
        val app = c.applicationContext
        val limpo = texto.trim()
        val cutuca = imagem == null && (SO_PONTUACAO.matches(limpo) || CUTUCA.matches(limpo))
        // "?", "cadê?", "e aí?" enquanto ela ainda está fazendo o pedido anterior: responde na hora, sem entrar na fila
        if (cutuca && pensando) {
            val r = "Já estou cuidando do seu pedido, só mais um segundinho."
            anotar(false, r)
            if (aoFim != null) Bg.ui { aoFim(r) }
            return
        }
        // "?" depois de um pedido que ficou sem resposta: ela refaz o pedido de antes (e não lê só o "?")
        val retomado = if (cutuca) retomar() else null
        val pedido = retomado ?: texto
        if (retomado != null) anotar(false, "↻ Refazendo o que você pediu: \"${retomado.take(80)}\"")
        pendentes.incrementAndGet()
        mudou++
        IaPedidoService.segurar(app)
        fila.execute {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val trava = try {
                (app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nocry:ia")?.also { it.setReferenceCounted(false); it.acquire(120_000L) }
            } catch (_: Throwable) { null }
            // trava de segurança: se algo travar de vez, não deixa a fila presa pra sempre
            val caixa = arrayOfNulls<Res>(1)
            val feito = CountDownLatch(1)
            Bg.io {
                caixa[0] = try { resolver(app, act, pedido, imagem, localPrimeiro) } catch (t: Throwable) {
                    NLog.add("erro", "ia: ${t.javaClass.simpleName}")
                    Res("Deu um erro aqui, mas já estou de volta. Manda um \"?\" que eu tento de novo.", true)
                }
                feito.countDown()
            }
            val ok = try { feito.await(100, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
            val res = caixa[0] ?: if (ok) Res("Pronto.") else Res("Demorei demais pra responder. Manda um \"?\" que eu tento de novo o seu pedido.", true)
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            if (!ok) NLog.add("ia", "pedido: passou de 100s, parei de esperar")
            else if (dt >= 5000) NLog.add("ia", "pedido levou ${dt / 1000}s")
            if (res.falhou) marcarSemResposta(pedido) else marcarRespondido(pedido)
            try { if (trava?.isHeld == true) trava.release() } catch (_: Throwable) {}
            anotar(false, res.texto)
            if (pendentes.decrementAndGet() <= 0) IaPedidoService.soltar(app)
            if (aoFim != null) Bg.ui { aoFim(res.texto) }
        }
    }

    private class Res(val texto: String, val falhou: Boolean = false)

    /** "vou te mandar uma foto", "te mando um print": ela responde e diz onde mandar (sem gastar a IA) */
    private val MANDAR_FOTO = Regex("\\b(vou|vo|ja|ja ja|logo|depois)\\b.{0,25}\\b(mandar|enviar|mostrar|passar)\\b.{0,25}\\b(foto|fotos|imagem|imagens|print|prints|captura|screenshot)\\b|\\b(te\\s+)?(mando|envio|passo|mostro)\\s+(uma\\s+|um\\s+|a\\s+|o\\s+|umas\\s+|uns\\s+|as\\s+|os\\s+)?(foto|fotos|imagem|imagens|print|prints|captura|screenshot)\\b")

    /** guarda se o último pedido foi ligar ou desligar, pra entender "e a otimização?" depois */
    private fun lembrar(passos: List<Passo>) {
        val a = (passos.firstOrNull { it.atrasoMs == 0L } ?: passos.firstOrNull())?.acao ?: return
        val liga = a.endsWith("_ligar") || a == "bypass_aplicar" || a == "bot_configurar" || a.startsWith("menu_") || a == "jogo_abrir"
        val desliga = a.endsWith("_desligar") || a.endsWith("_pausar") || a.startsWith("menu_tirar")
        if (liga || desliga) ultimoLiga = !desliga
    }

    val CRIADOR = "Quem me criou foi o Aucerola, o criador e dono do NOCRY: foi ele que desenvolveu o app e eu. O NOCRY nasceu pra ajudar os jogadores de Free Fire: começou com a conexão e o bypass, depois vieram a otimização, o modo competitivo, a instalação das pastas, o bot de fila, o caderninho, o rank, os eventos e os temas do Estúdio. Por último ele me colocou aqui, pra você falar comigo por voz e eu fazer as coisas por você. Tudo sempre é testado primeiro pelo Aucerola antes de chegar pra todo mundo."

    val AJUDA = "Não entendi. Exemplos do que eu faço:\n• liga o bypass completo agora (aplica na hora)\n• liga o bypass no não perturbe (ou bluetooth, carregador, volume)\n• liga o bot no modo 2x2 valores 4 e 3\n• liga o bot e desliga o bot em 5 minutos\n• liga o bot por 30 minutos\n• abre o jogo\n• coloca o Meet e o toque rápido no menu do topo\n• às 22h desliga o bot\n• verifica os erros e arruma\n• cancela os agendados"

    /** responde e faz tudo (bloqueia: chame fora da tela). imagem = foto em base64 (jpeg) */
    fun responder(c: Context, act: MainActivity?, texto: String, imagem: String? = null, localPrimeiro: Boolean = false): String =
        resolver(c, act, texto, imagem, localPrimeiro).texto

    private fun resolver(c: Context, act: MainActivity?, texto: String, imagem: String? = null, localPrimeiro: Boolean = false): Res {
        // ela tinha perguntado "qual bypass?" ou "normal ou max?": a resposta curta vai direto pro pedido que estava esperando
        if (imagem == null) Acoes.respostaPendente(c, act, texto)?.let { return Res(it) }
        // palavras-chave pelo chat: "quando eu falar bora jogar, abre o free fire", "apaga a palavra-chave bora jogar", "quais são minhas palavras-chave"
        if (imagem == null) Acoes.palavraLocal(c, act, texto)?.let { return Res(it) }
        // "vou te mandar uma foto": responde e diz onde mandar
        if (imagem == null && texto.length < 140 && MANDAR_FOTO.containsMatchIn(Comandos.limpa(texto)))
            return Res("Beleza! Manda a foto pelo botão de imagem aqui no chat e me diz o que você quer que eu veja nela.")
        // palavra-chave ou comando falado fora da tela: o que ela entende sozinha faz na hora, sem esperar a IA
        if (localPrimeiro && imagem == null) {
            val ps = Comandos.entender(texto, ultimoLiga)
            if (ps.isNotEmpty()) { lembrar(ps); return Res(rodarPassos(c, act, ps).ifBlank { "Pronto." }) }
        }
        val hist = synchronized(conversa) { conversa.dropLast(1).takeLast(8).map { (if (it.eu) "user" else "assistant") to it.texto } }
        val ia = Limite.rodar<IA.Resposta?>("ia/servidor", 38_000L, null) { IA.perguntar(c, texto, hist, imagem) }
        if (ia == null && imagem != null) return Res("Não consegui olhar a imagem agora: a IA do servidor não respondeu. Tenta de novo daqui a pouco.", true)
        if (ia == null && Regex("(?i)(quem (te|lhe|voce|vc)? ?(criou|fez|desenvolveu|programou)|seu criador|criador|quem (e|é) o dono|historia (do|desse) app|como (o app|o nocry|voce) (foi|nasceu))").containsMatchIn(texto)) return Res(CRIADOR)
        // o que você DISSE manda: a IA não escolhe o tipo nem o gatilho do bypass por conta própria
        val passos = ia?.passos?.let { Acoes.limparBypassIA(it, texto) } ?: Comandos.entender(texto, ultimoLiga)
        lembrar(passos)
        val linhas = ArrayList<String>()
        if (ia != null && ia.texto.isNotBlank()) linhas += ia.texto
        if (passos.isEmpty() && ia == null) return Res("A IA do servidor não respondeu agora (internet fraca ou ela demorou), e esse pedido eu não sei fazer sem ela. Manda um \"?\" que eu tento de novo.\n\n" + AJUDA.removePrefix("Não entendi. "), true)
        if (passos.isEmpty() && linhas.isEmpty()) return Res("Entendi! Se quiser que eu faça alguma coisa, é só me pedir.")
        val feito = rodarPassos(c, act, passos)
        if (feito.isNotBlank()) linhas += feito
        return Res(linhas.joinToString("\n").ifBlank { "Pronto." })
    }

    /** faz cada passo com tempo máximo (um passo que trava no ADB não prende a conversa) */
    private fun rodarPassos(c: Context, act: MainActivity?, passos: List<Passo>): String {
        val out = ArrayList<String>()
        for (p in passos) {
            out += Limite.rodar("ação ${p.acao}", 45_000L, "⚠ ${Acoes.descricao(p)}: ainda está em andamento (passou de 45 s). Se não terminar, me manda um \"?\".") { Acoes.fazerOuAgendar(c, act, p) }
        }
        return out.joinToString("\n")
    }
}

/** voz da IA: fala as respostas em português (motor de voz do próprio celular, grátis) */
object Voz : android.speech.tts.TextToSpeech.OnInitListener {
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var pronto = false
    private var pendente: String? = null
    private val main = Handler(Looper.getMainLooper())
    /** chamado quando termina de falar (no modo conversa por voz, volta a ouvir) */
    var aoTerminar: (() -> Unit)? = null

    private var app: Context? = null
    private var player: android.media.MediaPlayer? = null
    @Volatile private var semNuvemAte = 0L
    /** buscando o áudio da voz da nuvem (ainda não começou a falar) */
    @Volatile var buscando = false
        private set

    fun init(c: Context) {
        app = c.applicationContext
        if (tts == null) try {
            // a voz do Google é a mais natural; outros motores (de fábrica) deixam a voz robótica com o tom mais agudo
            val google = "com.google.android.tts"
            val temGoogle = try { c.packageManager.getPackageInfo(google, 0); true } catch (_: Throwable) { false }
            tts = if (temGoogle) android.speech.tts.TextToSpeech(c.applicationContext, this, google) else android.speech.tts.TextToSpeech(c.applicationContext, this)
        } catch (_: Throwable) {}
    }

    /** voz anime da nuvem (ElevenLabs, pelo servidor): sempre tenta ela primeiro; sem ela, a do celular */
    private val nuvem = true

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != android.speech.tts.TextToSpeech.SUCCESS) return
        val r = try { t.setLanguage(Locale("pt", "BR")) } catch (_: Throwable) { android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED }
        pronto = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
        aplicar()
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { aoTerminar?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        pendente?.let { falar(it) }
        pendente = null
    }

    fun falar(texto: String) {
        val limpo = texto.lines().map { it.trim().trimStart('✓', '✗', '⚠', '⏰', '•', ' ') }.filter { it.isNotEmpty() }.joinToString(". ").take(1500)
        if (limpo.isEmpty()) return
        val c = app
        if (nuvem && c != null && System.currentTimeMillis() > semNuvemAte && limpo.length <= 600) {
            buscando = true
            Bg.io {
                val arq = try { buscarNuvem(c, limpo) } catch (_: Throwable) { null }
                main.post { buscando = false; if (arq != null && tocar(arq)) Unit else falarLocal(limpo) }
            }
            return
        }
        falarLocal(limpo)
    }

    private fun falarLocal(limpo: String) {
        if (!pronto) { pendente = limpo; return }
        try { tts?.speak(limpo, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nocry-ia") } catch (_: Throwable) {}
    }

    /** pede o áudio pro servidor (mp3) e guarda num arquivo temporário. null = sem voz da nuvem agora */
    private fun buscarNuvem(c: Context, texto: String): java.io.File? {
        val j = IA.post(c, "voz", JSONObject().put("texto", texto)) ?: return null
        if (!j.optBoolean("ok")) {
            val r = j.optString("reason")
            if (r == "sem_voz") semNuvemAte = System.currentTimeMillis() + 30 * 60_000L
            else if (r == "voz_limite") semNuvemAte = System.currentTimeMillis() + 60 * 60_000L
            return null
        }
        return try {
            val b = android.util.Base64.decode(j.optString("audio"), android.util.Base64.DEFAULT)
            if (b.size < 200) return null
            val f = java.io.File(c.cacheDir, "ia_voz.mp3")
            f.writeBytes(b)
            f
        } catch (_: Throwable) { null }
    }

    private fun tocar(f: java.io.File): Boolean = try {
        player?.release()
        val mp = android.media.MediaPlayer()
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { aoTerminar?.invoke() }
        mp.prepare()
        mp.start()
        player = mp
        true
    } catch (_: Throwable) { false }

    fun parar() {
        try { tts?.stop() } catch (_: Throwable) {}
        try { player?.let { if (it.isPlaying) it.stop() } } catch (_: Throwable) {}
    }

    fun falando(): Boolean = try { tts?.isSpeaking == true || player?.isPlaying == true } catch (_: Throwable) { false }

    /** vozes em português do Brasil que o celular tem */
    private fun vozes(): List<android.speech.tts.Voice> = try {
        tts?.voices?.filter { it.locale.language == "pt" && (it.locale.country.isEmpty() || it.locale.country == "BR") }
            ?.filter { !it.features.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            ?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** a voz feminina mais bonita: as do Google em pt-BR com "afs" são femininas; depois, qualquer "female" */
    private fun melhorFeminina(l: List<android.speech.tts.Voice>): android.speech.tts.Voice? =
        // a voz que já está no celular (sem internet) fala na hora; a da internet às vezes demora ou trava em segundo plano
        l.firstOrNull { it.name.contains("afs") && !it.isNetworkConnectionRequired } ?: l.firstOrNull { it.name.contains("afs") }
            ?: l.firstOrNull { it.name.contains("female", true) || it.features.any { f -> f.contains("female", true) } }
            ?: l.firstOrNull { !it.isNetworkConnectionRequired } ?: l.firstOrNull()

    private fun aplicar() {
        val t = tts ?: return
        try {
            // uma voz só: a feminina mais bonita do celular, um pouco mais fofinha (sem passar do ponto e ficar robótica)
            melhorFeminina(vozes())?.let { t.setVoice(it) }
            t.setPitch(1.14f)
            t.setSpeechRate(1.04f)
        } catch (_: Throwable) {}
    }
}

class AssistenteScreen(a: MainActivity) : Screen(a) {
    override val titulo = "IA do NOCRY"
    /** tem pedido sendo feito (vale mesmo se você saiu da tela e voltou) */
    private val pensando: Boolean get() = Assistente.pensando
    /** foto escolhida pra mandar junto (jpeg em base64) e a miniatura pra mostrar */
    private var imagem: String? = null
    private var miniatura: android.graphics.Bitmap? = null

    private companion object {
        const val REQ_VOZ = 7701
        const val REQ_IMG = 7702
        /** a carinha dela (arte original do NOCRY), carregada uma vez só */
        fun fotoIA(c: Context): android.graphics.Bitmap? = Mascote.bmp(c, Mascote.Cara.FALANDO)
    }

    /** sempre falar as respostas (sem isso, fala só quando você pediu por voz) */
    private var falarSempre: Boolean
        get() = Prefs.lerNum("ia_falar", 0L) == 1L
        set(v) = Prefs.gravaNum("ia_falar", if (v) 1L else 0L)

    private val campo: EditText by lazy {
        Ui.field(act, "escreva ou toque no microfone", false).also { e ->
            // o que você digitou e não mandou fica guardado: se sair da tela e voltar, continua lá
            e.setText(Assistente.rascunho)
            e.setSelection(e.text.length)
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { Assistente.rascunho = s?.toString().orEmpty() }
            })
            e.imeOptions = EditorInfo.IME_ACTION_SEND
            e.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { enviar(); true } else false }
        }
    }

    override fun key(): String = "${Assistente.mudou}|${Assistente.pensando}|${Agenda.lista().size}|${IA.ligada}|${imagem?.length ?: 0}|$falarSempre|${SessaoIA.ligada}|${SessaoIA.foraDoApp}|${SessaoIA.soComNome}|${PalavrasChave.lista().size}|${SessaoIA.nomeChamada}|${SessaoIA.estado}"

    fun enviar(texto: String? = null, porVoz: Boolean = false) {
        var t = (texto ?: campo.text.toString()).trim()
        val img = imagem
        if (t.isEmpty() && img != null) t = "O que tem nessa imagem?"
        // pode mandar outro pedido enquanto ela ainda faz o anterior: eles entram na fila
        if (t.isEmpty()) return
        campo.setText("")
        Assistente.rascunho = ""
        imagem = null
        miniatura = null
        val ouvindo = SessaoIA.ligada
        if (ouvindo) SessaoIA.pausar() else Voz.parar()
        Assistente.anotar(true, if (img != null) "📷 $t" else t)
        redesenha()
        val c = act.applicationContext
        // o pedido roda fora da tela: pode sair da conversa ou minimizar o app que ela continua e faz o que você mandou
        Assistente.pedir(c, act, t, img) { r ->
            val aqui = act.naFrente && act.atual() === this@AssistenteScreen
            if (aqui) redesenha()
            // microfone ligado: fala a resposta e volta a ouvir
            if (ouvindo && SessaoIA.ligada) SessaoIA.falarEVoltar(r)
            else if (porVoz || falarSempre) { Voz.aoTerminar = null; Voz.falar(r) }
            // você está em outra tela: avisa em cima do que ela fez
            else if (!aqui) Ui.toast(c, "IA: " + r.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100))
        }
    }

    /** o microfone: toca = ela fica ouvindo sem parar (até com o app minimizado); toca de novo = para.
     *  Sem reconhecimento de voz no celular, usa a janela do Google */
    fun ouvir() {
        // enquanto ela fala, tocar no microfone interrompe e ela já te escuta
        if (SessaoIA.interromper()) { redesenha(); return }
        SessaoIA.alternar(act) { ouvirJanela() }
        redesenha()
    }

    /** palavras-chave: falou a palavra, ela faz o que você combinou */
    private fun palavras() {
        val l = PalavrasChave.lista()
        val ops = ArrayList<String>()
        ops += "+ Nova palavra-chave"
        ops += "Nome pra me chamar: \"${SessaoIA.nomeChamada}\" (toque pra trocar)"
        ops += (if (SessaoIA.soComNome) "✓ " else "") + "Fora da tela da IA, conversar só quando me chamar"
        l.forEach { ops += "✗ \"${it.palavra}\" → ${it.faz}" }
        Ui.dialogEscolha(act, "PALAVRAS-CHAVE", ops) { i ->
            when (i) {
                0 -> novaPalavra()
                1 -> trocarNome()
                2 -> { SessaoIA.soComNome = !SessaoIA.soComNome; redesenha() }
                else -> l.getOrNull(i - 3)?.let { PalavrasChave.remover(it.palavra); Ui.toast(act, "palavra \"${it.palavra}\" apagada"); redesenha() }
            }
        }
    }

    /** o nome que você fala pra chamar ela (ex.: "Ô João!" e ela responde "Oi!") */
    private fun trocarNome() {
        val c = act
        val box = Ui.vbox(c)
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0)
        val e = Ui.field(c, "nome (ex.: João)", false)
        e.setText(SessaoIA.nomeChamada)
        box.addView(e)
        android.app.AlertDialog.Builder(c)
            .setTitle("Nome pra me chamar")
            .setMessage("Fale o nome e ela responde \"Oi!\". Depois é só pedir. Também dá pra falar tudo junto: \"Ô João, abre o jogo\".")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val n = e.text.toString().trim()
                if (PalavrasChave.limpa(n).length < 2) { Ui.toast(c, "escreva um nome"); return@setPositiveButton }
                SessaoIA.nomeChamada = n
                Ui.toast(c, "pronto: me chame de \"$n\"")
                redesenha()
            }
            .setNeutralButton("Voltar pra Nocry") { _, _ -> SessaoIA.nomeChamada = "Nocry"; redesenha() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun novaPalavra() {
        val c = act
        val box = Ui.vbox(c)
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0)
        val p = Ui.field(c, "palavra (ex.: modo jogo)", false)
        val f = Ui.field(c, "o que fazer (ex.: liga o bypass, liga a otimização e abre o free fire max)", false)
        box.addView(p)
        box.addView(f, Ui.lp(top = 10, c = c))
        android.app.AlertDialog.Builder(c)
            .setTitle("Nova palavra-chave")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val pv = p.text.toString().trim()
                val fz = f.text.toString().trim()
                if (pv.isEmpty() || fz.isEmpty()) { Ui.toast(c, "preencha a palavra e o que fazer"); return@setPositiveButton }
                PalavrasChave.adicionar(pv, fz)
                val entende = Comandos.entender(fz).isNotEmpty()
                Ui.toast(c, if (entende) "pronto: fale \"$pv\" e eu faço" else "salvei; esse pedido eu faço com a ajuda da IA (precisa de internet)")
                redesenha()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** reserva: janela de voz do Google */
    private fun ouvirJanela() {
        Voz.parar()
        val it = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Pode falar")
        act.aoResultado = { req, res, data ->
            if (req == REQ_VOZ && res == android.app.Activity.RESULT_OK) {
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { fala -> enviar(fala, porVoz = true) }
            }
        }
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(it, REQ_VOZ)
        } catch (_: Throwable) { Ui.toast(act, "esse celular não tem reconhecimento de voz (instale o app Google)") }
    }

    /** pede pro Android colocar o bloco "IA NOCRY" no menu do topo */
    private fun colocarNoMenu() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sbm = act.getSystemService(android.app.StatusBarManager::class.java)
                sbm.requestAddTileService(android.content.ComponentName(act, IaTile::class.java), "IA NOCRY",
                    android.graphics.drawable.Icon.createWithResource(act, R.drawable.ic_ia), act.mainExecutor) { r ->
                    Ui.toast(act, if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "pronto: puxe o menu do topo e toque em IA NOCRY" else "não foi colocado")
                }
                return
            } catch (_: Throwable) {}
        }
        Ui.toast(act, "puxe o menu do topo, toque no lápis e arraste o bloco IA NOCRY")
    }

    private fun escolherImagem() {
        val it = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_IMG && res == android.app.Activity.RESULT_OK && u != null) {
                val c = act.applicationContext
                Bg.io {
                    val r = carregar(c, u)
                    Bg.ui {
                        if (r == null) Ui.toast(act, "não consegui abrir essa imagem")
                        else { imagem = r.first; miniatura = r.second; redesenha() }
                    }
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(it, REQ_IMG)
        } catch (_: Throwable) { Ui.toast(act, "nenhum app de galeria encontrado") }
    }

    /** abre a foto, diminui (até 1024 px) e vira jpeg em base64 pra mandar pra IA */
    private fun carregar(c: Context, u: android.net.Uri): Pair<String, android.graphics.Bitmap>? {
        return try {
            val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, op) }
            var amostra = 1
            while (maxOf(op.outWidth, op.outHeight) / amostra > 2048) amostra *= 2
            val b0 = c.contentResolver.openInputStream(u)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = amostra })
            } ?: return null
            val esc = 1024f / maxOf(b0.width, b0.height)
            val b = if (esc < 1f) android.graphics.Bitmap.createScaledBitmap(b0, maxOf(1, (b0.width * esc).toInt()), maxOf(1, (b0.height * esc).toInt()), true) else b0
            val out = java.io.ByteArrayOutputStream()
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
            Pair(android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP), b)
        } catch (_: Throwable) { null }
    }

    /** a carinha dela, redondinha (nas mensagens do chat) */
    private fun avatar(tam: Int, anel: Boolean = false): View {
        val c = act
        val f = FrameLayout(c)
        val iv = android.widget.ImageView(c)
        iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        fotoIA(c)?.let { b -> iv.setImageDrawable(androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(c.resources, b).also { it.isCircular = true; it.setAntiAlias(true) }) }
        val borda = if (anel) 3 else 0
        if (anel) f.background = Ui.shape(c, 0, 999, Theme.alpha(Theme.accent, 0xB0), 2)
        f.addView(iv, FrameLayout.LayoutParams(Ui.dp(c, tam - borda * 2), Ui.dp(c, tam - borda * 2), Gravity.CENTER))
        return f
    }

    /** balão do chat: as respostas dela vêm com a fotinho do lado */
    private fun balao(eu: Boolean, texto: String): View {
        val c = act
        val linha = Ui.hbox(c)
        linha.gravity = if (eu) Gravity.END else (Gravity.START or Gravity.BOTTOM)
        val t = Ui.text(c, texto, 12.5f, Ui.TEXT, eu)
        t.setLineSpacing(0f, 1.15f)
        t.setPadding(Ui.dp(c, 13), Ui.dp(c, 10), Ui.dp(c, 13), Ui.dp(c, 10))
        if (eu) {
            t.background = Ui.shape(c, Theme.alpha(Theme.accent, 0x40), 18, Theme.alpha(Theme.accent, 0x90), 1)
            linha.addView(View(c), LinearLayout.LayoutParams(0, 1, 0.18f))
            linha.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            t.background = Ui.glass(c)
            Ui.clique(t) { Voz.falar(texto) }   // tocar na resposta: ela fala de novo
            linha.addView(avatar(30), LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)).also { it.rightMargin = Ui.dp(c, 8) })
            linha.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(View(c), LinearLayout.LayoutParams(Ui.dp(c, 24), 1))
        }
        return linha
    }

    /** balão do visual HUD: letra normal (fácil de ler), painel com colchetes pra ela e caixa da cor do tema pra você */
    private fun balaoHud(eu: Boolean, texto: String): View {
        val c = act
        val linha = Ui.hbox(c)
        linha.gravity = if (eu) Gravity.END else (Gravity.START or Gravity.TOP)
        val t = Ui.text(c, texto, 13.5f, Ui.TEXT, false)
        t.setLineSpacing(0f, 1.25f)
        t.setPadding(Ui.dp(c, 13), Ui.dp(c, 10), Ui.dp(c, 13), Ui.dp(c, 10))
        if (eu) {
            t.background = HudChip(c, ligado = true)
            linha.addView(View(c), LinearLayout.LayoutParams(0, 1, 0.2f))
            linha.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            t.background = Ui.glass(c)
            Ui.clique(t) { Voz.falar(texto) }
            linha.addView(avatar(28, anel = true), LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)).also { it.rightMargin = Ui.dp(c, 8); it.topMargin = Ui.dp(c, 2) })
            linha.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            linha.addView(View(c), LinearLayout.LayoutParams(Ui.dp(c, 18), 1))
        }
        return linha
    }

    /** topo e conversa da IA no visual HUD (só na prévia): ela no meio do anel, o que está fazendo, atalhos e a conversa */
    private fun hudCorpo(c: Context, st: String, cor: Int, vazio: Boolean) {
        val topo = Ui.hbox(c)
        topo.gravity = Gravity.CENTER_VERTICAL
        val nomes = Ui.vbox(c)
        nomes.addView(Ui.text(c, SessaoIA.nomeChamada, 19f, Ui.TEXT, true).also { Ui.umaLinha(it, 12) })
        nomes.addView(Ui.text(c, "IA do NOCRY", 10f, Ui.MUTED, true, mono = true), Ui.lp(top = 2, c = c))
        topo.addView(nomes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topo.addView(botaoRedondo(R.drawable.ic_image, "mandar imagem", imagem != null, 40) { escolherImagem() }, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).also { it.leftMargin = Ui.dp(c, 6) })
        topo.addView(botaoRedondo(R.drawable.ic_sliders, "ajustes da IA", false, 40) { ajustes() }, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).also { it.leftMargin = Ui.dp(c, 6) })
        add(topo, 0)

        // o anel com ela no meio: gira devagar parada, acelera pensando e pulsa ouvindo/falando. Tocar nele = falar
        val tam = if (vazio) 228 else 168
        val nucleo = FrameLayout(c)
        val reator = HudReator(c)
        reator.seguirIA = true
        nucleo.addView(reator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val rosto = tam * 52 / 100
        nucleo.addView(MascoteView(c, false), FrameLayout.LayoutParams(Ui.dp(c, rosto), Ui.dp(c, rosto), Gravity.CENTER))
        nucleo.contentDescription = if (SessaoIA.ligada) "parar de ouvir" else "falar"
        Ui.clique(nucleo) { ouvir() }
        val meio = Ui.vbox(c)
        meio.gravity = Gravity.CENTER_HORIZONTAL
        meio.addView(nucleo, LinearLayout.LayoutParams(Ui.dp(c, tam), Ui.dp(c, tam)))
        meio.addView(Ui.text(c, st, 10.5f, cor, true, mono = true).also { it.gravity = Gravity.CENTER }, Ui.lp(top = 4, c = c))
        if (vazio) meio.addView(Ui.text(c, "Eu converso, olho imagens, abro apps, crio alarmes e mexo no NOCRY. É só falar ou escrever.", 12.5f, Ui.MUTED).also { it.gravity = Gravity.CENTER }, Ui.lp(top = 8, c = c))
        add(meio, 10)

        // atalhos: um toque e ela faz
        val atalhos = listOf<Pair<String, String?>>("Ligar o bypass" to "liga o bypass", "Otimizar" to "liga a otimização",
            "Abrir o Free Fire" to "abre o free fire", "Palavras-chave" to null, "Verificar erros" to "verifica os erros e arruma")
        val hs = android.widget.HorizontalScrollView(c)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val fila = Ui.hbox(c)
        atalhos.forEachIndexed { i, (nome, pedido) ->
            val b = Ui.text(c, nome, 10.5f, Ui.TEXT, true, mono = true)
            b.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            b.background = Ui.ripple(c, HudChip(c), HudChip(c))
            Ui.clique(b) { if (pedido == null) palavras() else enviar(pedido) }
            fila.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { if (i > 0) it.leftMargin = Ui.dp(c, 8) })
        }
        hs.addView(fila)
        add(hs, 14)

        if (!vazio) {
            add(Ui.secao(c, "conversa"), 18)
            val msgs = synchronized(Assistente.conversa) { Assistente.conversa.toList() }
            msgs.takeLast(40).forEach { m -> add(balaoHud(m.eu, m.texto), 8) }
        }
    }

    private fun botaoRedondo(ico: Int, desc: String, principal: Boolean, tam: Int, acao: () -> Unit): View {
        val c = act
        val f = FrameLayout(c)
        f.background = if (principal) Ui.ripple(c, Botao(c, "grad"), Botao(c, "grad", mascara = true)) else Ui.ripple(c, Ui.fundoPeq(c, 99, ico))
        val ti = tam * 2 / 5
        f.addView(Ui.icone(c, ico, if (principal) Ui.corSobreGrande() else Ui.TEXT, ti), FrameLayout.LayoutParams(Ui.dp(c, ti), Ui.dp(c, ti), Gravity.CENTER))
        f.contentDescription = desc
        Ui.clique(f) { acao() }
        return f
    }

    /** ajustes da IA (tudo num lugar só, fora da tela principal) */
    private fun ajustes() {
        val ops = listOf(
            "Palavras-chave (${PalavrasChave.lista().size})",
            "Nome pra me chamar: \"${SessaoIA.nomeChamada}\"",
            "Falar as respostas escritas: " + (if (falarSempre) "sim" else "não"),
            "Ouvir com o app minimizado: " + (if (SessaoIA.foraDoApp) "sim" else "não"),
            "Colocar a IA no menu do topo"
        )
        Ui.dialogEscolha(act, "AJUSTES DA IA", ops) { i ->
            when (i) {
                0 -> palavras()
                1 -> trocarNome()
                2 -> { falarSempre = !falarSempre; if (falarSempre) Voz.falar("Oi! Eu sou a IA do NOCRY.") else Voz.parar(); redesenha() }
                3 -> {
                    SessaoIA.foraDoApp = !SessaoIA.foraDoApp
                    if (SessaoIA.ligada) { if (SessaoIA.foraDoApp) IaFundoService.iniciar(act) else IaFundoService.fechar(act) }
                    redesenha()
                }
                4 -> colocarNoMenu()
            }
        }
    }

    override fun render() {
        limpa()
        val c = act
        Voz.init(c)
        // o microfone NÃO liga sozinho ao abrir a tela: só quando você toca nele

        val vazio = synchronized(Assistente.conversa) { Assistente.conversa.isEmpty() }
        val (st, cor) = when {
            pensando || SessaoIA.estado == SessaoIA.Estado.PENSANDO -> "pensando..." to Ui.MUTED
            SessaoIA.estado == SessaoIA.Estado.FALANDO -> "falando · toque no microfone pra interromper" to Ui.OK
            SessaoIA.ligada -> "ouvindo · pode falar" to Ui.OK
            else -> "microfone desligado · toque nele pra falar" to Ui.MUTED
        }

        if (Hud.on) hudCorpo(c, st, cor, vazio) else {
        // topo: só a carinha dela, o nome, o que ela está fazendo e dois ícones (imagem e ajustes)
        val topo = Ui.hbox(c)
        topo.gravity = Gravity.CENTER_VERTICAL
        topo.background = Ui.glass(c)
        topo.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
        if (!vazio) topo.addView(MascoteView(c), LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)).also { it.rightMargin = Ui.dp(c, 10) })
        val nomes = Ui.vbox(c)
        nomes.addView(Ui.text(c, SessaoIA.nomeChamada, 16f, Ui.TEXT, true).also { Ui.umaLinha(it, 10) })
        nomes.addView(Ui.text(c, if (vazio) "IA do NOCRY" else st, 10f, if (vazio) Ui.MUTED else cor, true, mono = true).also { Ui.umaLinha(it, 9) }, Ui.lp(top = 2, c = c))
        topo.addView(nomes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { if (vazio) it.leftMargin = Ui.dp(c, 6) })
        topo.addView(botaoRedondo(R.drawable.ic_image, "mandar imagem", imagem != null, 40) { escolherImagem() }, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).also { it.leftMargin = Ui.dp(c, 6) })
        topo.addView(botaoRedondo(R.drawable.ic_sliders, "ajustes da IA", false, 40) { ajustes() }, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).also { it.leftMargin = Ui.dp(c, 6) })
        add(topo, 0)

        if (vazio) {
            // primeira vez / conversa limpa: ela aparece grande, de frente, com o que está fazendo
            val col = Ui.vbox(c)
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(MascoteView(c), LinearLayout.LayoutParams(Ui.dp(c, 176), Ui.dp(c, 176)))
            col.addView(Ui.text(c, "Oi! Pode falar comigo.", 19f, Ui.TEXT, true).also { it.gravity = Gravity.CENTER }, Ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 10, c = c))
            col.addView(Ui.text(c, st, 10.5f, cor, true, mono = true).also { it.gravity = Gravity.CENTER }, Ui.lp(top = 6, c = c))
            col.addView(Ui.text(c, "Eu converso, olho imagens, abro apps, crio alarmes e mexo no NOCRY. É só falar ou escrever.", 11.5f, Ui.MUTED, false).also { it.gravity = Gravity.CENTER; it.setLineSpacing(0f, 1.15f) }, Ui.lp(top = 8, c = c))
            add(col, 18)
            val sug = listOf("Ligar o bypass completo", "Ligar o bot de fila", "Abrir o Free Fire MAX", "Ligar a otimização")
            sug.chunked(2).forEach { par ->
                val l = Ui.hbox(c)
                par.forEachIndexed { i, s ->
                    val b = Ui.text(c, s, 10.5f, Ui.TEXT, true)
                    b.gravity = Gravity.CENTER
                    b.setPadding(Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12))
                    b.background = Ui.ripple(c, Ui.glass(c))
                    Ui.clique(b) { enviar(s) }
                    l.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { if (i > 0) it.leftMargin = Ui.dp(c, 8) })
                }
                add(l, 8)
            }
        } else {
            val msgs = synchronized(Assistente.conversa) { Assistente.conversa.toList() }
            msgs.forEachIndexed { i, m -> add(balao(m.eu, m.texto), if (i == 0) 14 else 10) }
        }
        }
        if (pensando) add(if (Hud.on) balaoHud(false, "pensando…") else balao(false, "pensando…"), 10)

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

        // foto escolhida (vai junto com a próxima mensagem)
        miniatura?.let { bmp ->
            val r = Ui.hbox(c)
            r.gravity = Gravity.CENTER_VERTICAL
            r.background = Ui.glass(c)
            r.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
            val iv = android.widget.ImageView(c)
            iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
            r.addView(iv, LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)).also { it.rightMargin = Ui.dp(c, 10) })
            r.addView(Ui.text(c, "imagem pronta: escreva ou fale a pergunta", 10.5f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val x = Ui.text(c, "tirar", 10f, Ui.WARN, true, mono = true)
            x.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), 0, Ui.dp(c, 6))
            Ui.clique(x) { imagem = null; miniatura = null; redesenha() }
            r.addView(x)
            add(r, 14)
        }

        // campo + microfone + enviar
        val linha = Ui.hbox(c)
        linha.gravity = Gravity.CENTER_VERTICAL
        (campo.parent as? ViewGroup)?.removeView(campo)
        linha.addView(campo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = OrbeMic(c, 50)
        mic.contentDescription = if (SessaoIA.ligada) "parar de ouvir" else "falar"
        mic.ativo = SessaoIA.ligada
        mic.isClickable = true
        mic.setOnClickListener { ouvir() }
        linha.addView(mic, LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 50)).also { it.leftMargin = Ui.dp(c, 6) })
        linha.addView(botaoRedondo(R.drawable.ic_play, "enviar", true, 46) { enviar() }, LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)).also { it.leftMargin = Ui.dp(c, 6) })
        add(linha, 18)
    }

}
