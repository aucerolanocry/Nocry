package com.nocry.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// =====================================================================================
// IA por voz "estilo Jarvis": uma luz colorida contorna a tela inteira enquanto a IA ouve, pensa e fala,
// com uma pílula no topo mostrando o microfone. Fica por cima de qualquer tela do app (início, bot...).
// =====================================================================================

/** cores da luz da IA: seguem o tema (no tema sangue fica vermelha, no vidro fica clara...) */
private fun coresIA(): IntArray {
    val a = Theme.accent
    val b = Theme.accent2
    return intArrayOf(a, Theme.mix(a, b, 0.5f), b, Theme.mix(a, Color.WHITE, 0.45f), a)
}

/** a luz que contorna a tela: gira, pulsa com a voz e some devagar quando a IA para */
class BordaIA(c: Context) : View(c) {
    /** 0..1: altura da voz (o microfone manda) */
    var nivel = 0f
    /** 0 ouvindo, 1 pensando, 2 falando */
    var estado = 0
    var sumindo = false
    var aoSumir: (() -> Unit)? = null

    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private var sombra: SweepGradient? = null
    private var ang = 0f
    private var suave = 0f
    private var alfa = 0f
    private val r = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sombra = SweepGradient(w / 2f, h / 2f, coresIA(), null)
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        val sh = sombra ?: return
        alfa = if (sumindo) maxOf(0f, alfa - 0.05f) else minOf(1f, alfa + 0.07f)
        suave += (nivel - suave) * 0.22f
        val t = System.nanoTime() / 1e9
        ang += when (estado) { 1 -> 5.5f; 2 -> 3f; else -> 1.8f } + suave * 5f
        m.setRotate(ang, w / 2f, h / 2f)
        sh.setLocalMatrix(m)
        p.shader = sh
        // pensando: respira; falando: pulsa; ouvindo: segue a voz
        val pulso = when (estado) {
            1 -> (0.5f + 0.5f * Math.sin(t * 3.0).toFloat()) * 2.5f * d
            2 -> (0.5f + 0.5f * Math.sin(t * 7.0).toFloat()) * 3f * d
            else -> suave * 7f * d
        }
        val base = 3.2f * d + pulso
        val raio = 36f * d
        r.set(0f, 0f, w, h)
        for ((k, a) in listOf(8f to 0.07f, 5f to 0.14f, 3f to 0.30f, 1.6f to 0.75f, 0.8f to 1f)) {
            p.strokeWidth = base * k
            p.alpha = (255 * a * alfa).toInt().coerceIn(0, 255)
            cv.drawRoundRect(r, raio, raio, p)
        }
        if (sumindo && alfa <= 0f) { aoSumir?.invoke(); aoSumir = null; return }
        postInvalidateOnAnimation()
    }
}

/** a bolinha do microfone: cores girando por dentro e um brilho que respira */
class OrbeMic(c: Context, private val tamDp: Int) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val fundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private var giro: SweepGradient? = null
    private var ang = 0f
    private val icone = c.getDrawable(R.drawable.ic_mic)?.mutate()
    /** true enquanto a IA está ouvindo (gira mais rápido) */
    var ativo = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        giro = SweepGradient(w / 2f, h / 2f, coresIA(), null)
        brilho.shader = RadialGradient(w / 2f, h / 2f, w / 2f, intArrayOf(Theme.alpha(Color.WHITE, 0x55), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val t = System.nanoTime() / 1e9
        val resp = 0.5f + 0.5f * Math.sin(t * (if (ativo) 6.0 else 2.0)).toFloat()
        val raio = minOf(w, h) / 2f
        ang += if (ativo) 4f else 1.2f
        giro?.let { g -> m.setRotate(ang, cx, cy); g.setLocalMatrix(m); fundo.shader = g }
        // brilho em volta
        cv.drawCircle(cx, cy, raio, brilho)
        // bola colorida
        cv.drawCircle(cx, cy, raio * (0.78f + 0.04f * resp), fundo)
        // miolo escuro com o microfone
        fundo.shader = null
        fundo.color = Theme.alpha(0xFF0B0B12.toInt(), 0xE6)
        cv.drawCircle(cx, cy, raio * 0.64f, fundo)
        aro.strokeWidth = 1.2f * d
        aro.color = Theme.alpha(Color.WHITE, (0x40 + 0x60 * resp).toInt())
        cv.drawCircle(cx, cy, raio * 0.64f, aro)
        icone?.let { ic ->
            val s = (raio * 0.62f).toInt()
            ic.setTint(Color.WHITE)
            ic.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            ic.draw(cv)
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(wm: Int, hm: Int) {
        val s = (tamDp * d).toInt()
        setMeasuredDimension(s, s)
    }
}

/** a IA por voz: com o microfone ligado ela ouve sem parar (até com o app minimizado), pensa, fala e volta a ouvir */
object SessaoIA {
    enum class Estado { PARADO, OUVINDO, PENSANDO, FALANDO }

    const val REQ_MIC = 7703
    @Volatile var estado = Estado.PARADO
        private set

    /** microfone ligado: ela fica ouvindo sem parar até você tocar de novo (dentro e fora do app) */
    @Volatile var ligada = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var act: MainActivity? = null
    /** contexto pra ouvir e falar (o do app, que vale dentro e fora do NOCRY) */
    private var ctx: Context? = null
    private var camada: FrameLayout? = null
    private var borda: BordaIA? = null
    private var orbe: OrbeMic? = null
    private var txt: TextView? = null
    private var fimFala: Runnable? = null
    /** cada escuta tem um número: resposta atrasada de uma escuta velha é ignorada
     *  (era isso + reaproveitar o reconhecedor antigo que dava "não deu pra ouvir agora") */
    private var sessao = 0
    private var errosSeguidos = 0
    private var inicioEscuta = 0L
    private var ultimoStatus: String? = null

    /** com o app minimizado, continua ouvindo (padrão: sim) */
    var foraDoApp: Boolean
        get() = Prefs.lerNum("ia_fundo", 1L) == 1L
        set(v) = Prefs.gravaNum("ia_fundo", if (v) 1L else 0L)

    /** fora da tela da IA: conversa só quando você fala o nome dela ("Nocry").
     *  Comandos (liga o bypass, abre o jogo...) e palavras-chave valem sempre */
    var soComNome: Boolean
        get() = Prefs.lerNum("ia_nome", 1L) == 1L
        set(v) = Prefs.gravaNum("ia_nome", if (v) 1L else 0L)

    /** o nome que você fala pra chamar ela (padrão "Nocry") */
    var nomeChamada: String
        get() = Prefs.extraTxt("ia_chamada")?.trim()?.takeIf { it.isNotEmpty() } ?: "Nocry"
        set(v) { Prefs.extraTxt("ia_chamada", v.trim().take(30)) }

    /** depois de chamar ela (ou dela responder), por uns segundos ela entende que o próximo pedido é pra ela */
    private var conversaAte = 0L

    val ativa get() = ligada || estado != Estado.PARADO

    private fun temMic(c: Context) = Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** o botão do microfone: toca = começa a ouvir; toca de novo = para */
    fun alternar(a: MainActivity, semJanela: () -> Unit) { if (ligada) desligar(a) else ligar(a, semJanela) }

    /** começa a ouvir sem parar. Sem a permissão do microfone, pede; sem reconhecimento de voz, usa a janela do Google */
    fun ligar(a: MainActivity, semJanela: () -> Unit = {}) {
        act = a
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { semJanela(); return }
        if (!temMic(a)) {
            a.aoPermissao = { req, ok ->
                if (req == REQ_MIC) { if (ok) ligar(a, semJanela) else { Ui.toast(a, "sem o microfone liberado eu uso a janela do Google"); semJanela() } }
            }
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        ctx = a.applicationContext
        Voz.init(a)
        ligada = true
        Prefs.gravaNum("ia_ouvindo", 1L)
        // o serviço com microfone é o que deixa continuar ouvindo com o app minimizado (só dá pra ligar com o app aberto)
        if (foraDoApp) IaFundoService.iniciar(a)
        if (a.naFrente) mostra(a)
        errosSeguidos = 0
        if (estado == Estado.PARADO) ouvir()
        IaTile.atualizar(a)
    }

    /** volta a ouvir pelo menu do topo ou pela notificação (o serviço já está rodando) */
    fun ligarFundo(c: Context) {
        if (!temMic(c) || !SpeechRecognizer.isRecognitionAvailable(c)) return
        ctx = c.applicationContext
        Voz.init(c)
        ligada = true
        Prefs.gravaNum("ia_ouvindo", 1L)
        errosSeguidos = 0
        act?.let { if (it.naFrente) mostra(it) }
        if (estado == Estado.PARADO) ouvir()
        IaTile.atualizar(c)
    }

    /** desliga o microfone (e o serviço de segundo plano) */
    fun desligar(c: Context?, fecharServico: Boolean = true) {
        ligada = false
        Prefs.gravaNum("ia_ouvindo", 0L)
        parar()
        val cc = c ?: ctx ?: return
        if (fecharServico) IaFundoService.fechar(cc)
        IaTile.atualizar(cc)
    }

    /** o app voltou pra frente: mostra a luz; se o microfone ficou ligado da última vez, já volta a ouvir */
    fun aoVoltar(a: MainActivity) {
        act = a
        if (BuildConfig.PREVIEW) return
        if (ligada) {
            mostra(a)
            if (estado == Estado.PARADO) { errosSeguidos = 0; ouvir() }
        } else if (Prefs.lerNum("ia_ouvindo", 0L) == 1L && temMic(a) && License.valid()) ligar(a)
    }

    /** o app saiu da tela: sem o serviço de segundo plano o Android não deixa ouvir, então pausa (volta quando abrir) */
    fun aoSair(a: MainActivity) {
        if (act === a && ligada && !IaFundoService.ativo) parar()
    }

    /** a tela do app foi destruída: solta ela (a escuta continua pelo serviço) */
    fun soltar(a: MainActivity) {
        if (act !== a) return
        act = null
        camada = null; borda = null; orbe = null; txt = null
    }

    /** o serviço de segundo plano fechou: com o app fora da tela, não dá mais pra ouvir */
    fun servicoFechou() {
        if (ligada && act?.naFrente != true) parar()
    }

    /** a pílula do topo: microfone + o que está acontecendo + botão de parar */
    private fun criaPilula(a: Context, aoTocar: () -> Unit): Triple<View, OrbeMic, TextView> {
        val pil = LinearLayout(a)
        pil.orientation = LinearLayout.HORIZONTAL
        pil.gravity = Gravity.CENTER_VERTICAL
        pil.background = Ui.shape(a, Theme.alpha(0xFF0B0B12.toInt(), 0xE8), 99, Theme.alpha(Theme.accent, 0x90), 1)
        pil.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6))
        pil.elevation = Ui.dp(a, 8).toFloat()
        val o = OrbeMic(a, 34)
        o.ativo = true
        pil.addView(o)
        val t = Ui.text(a, "Ouvindo...", 12f, Ui.TEXT, true)
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        pil.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.dp(a, 10); it.rightMargin = Ui.dp(a, 8) })
        val x = Ui.icone(a, R.drawable.ic_xcircle, Ui.MUTED, 20)
        x.contentDescription = "parar a IA"
        x.setOnClickListener { desligar(a) }
        pil.addView(x, LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)))
        pil.setOnClickListener { aoTocar() }
        return Triple(pil, o, t)
    }

    private fun mostra(a: MainActivity) {
        val f0 = camada
        if (f0 != null && f0.context === a && f0.parent != null) { borda?.sumindo = false; return }
        if (f0 != null) (f0.parent as? ViewGroup)?.removeView(f0)
        val raiz = a.window.decorView as? ViewGroup ?: return
        val f = FrameLayout(a)
        val b = BordaIA(a)
        f.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val (pil, o, t) = criaPilula(a) { if (a.atual() !is AssistenteScreen) a.abre(AssistenteScreen(a)) }
        val idSb = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        val topo = (if (idSb > 0) a.resources.getDimensionPixelSize(idSb) else Ui.dp(a, 24)) + Ui.dp(a, 8)
        f.addView(pil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).also {
            it.topMargin = topo; it.leftMargin = Ui.dp(a, 18); it.rightMargin = Ui.dp(a, 18)
        })
        raiz.addView(f, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        camada = f; borda = b; orbe = o; txt = t
        t.text = when (estado) { Estado.PENSANDO -> "Pensando..."; Estado.FALANDO -> ultimoStatus ?: "Falando..."; else -> "Ouvindo..." }
        b.estado = when (estado) { Estado.PENSANDO -> 1; Estado.FALANDO -> 2; else -> 0 }
    }

    /** escreve o que está acontecendo na pílula do topo e na notificação */
    private fun texto(s: String, notif: Boolean = true) {
        txt?.text = s
        if (notif && s != ultimoStatus) { ultimoStatus = s; IaFundoService.status(s) }
    }

    private fun nomeErro(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "internet demorou"
        SpeechRecognizer.ERROR_NETWORK -> "sem internet"
        SpeechRecognizer.ERROR_AUDIO -> "microfone ocupado ou com problema"
        SpeechRecognizer.ERROR_SERVER -> "servidor de voz"
        SpeechRecognizer.ERROR_CLIENT -> "cliente"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "reconhecedor ocupado"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "sem permissão do microfone"
        10 -> "muitos pedidos"
        11 -> "serviço de voz caiu"
        12, 13 -> "idioma indisponível"
        else -> "código $e"
    }

    private fun ouvinte(id: Int) = object : RecognitionListener {
        fun velho() = id != sessao || estado != Estado.OUVINDO
        override fun onReadyForSpeech(params: Bundle?) { if (!velho()) texto("Ouvindo...") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (!velho()) borda?.nivel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (!velho()) borda?.nivel = 0f }
        override fun onError(error: Int) { if (!velho()) aoErro(error) }
        override fun onResults(results: Bundle?) {
            if (velho()) return
            val fala = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            errosSeguidos = 0
            // tira barulho de fundo: pedaço curtinho ou com confiança muito baixa é ruído, não comando
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 1f
            val ruido = fala.length < 2 || (conf in 0f..0.001f) || (!naTelaIAagora() && conf in 0f..0.3f)
            if (fala.isEmpty() || ruido) { if (ligada) deNovo(120) else parar(); return }
            decidir(fala)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (velho()) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { texto("“$it”", notif = false) }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun aoErro(error: Int) {
        val nada = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (!nada) NLog.add("ia", "escuta: erro $error (${nomeErro(error)})")
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            (act ?: ctx)?.let { Ui.toast(it, "libere o microfone pro NOCRY nas configurações do celular") }
            desligar(ctx); return
        }
        if (!ligada) { parar(); return }
        // ouvindo sempre: silêncio ou erro passageiro → volta a ouvir sozinha, sem aviso
        if (nada) {
            errosSeguidos = 0
            // se o silêncio voltou rápido demais, espera um pouco (não fica apitando sem parar)
            deNovo(if (System.currentTimeMillis() - inicioEscuta < 1500) 1200 else 150)
            return
        }
        errosSeguidos++
        sessao++   // o reconhecedor velho pode avisar mais um erro ao ser fechado: esse aviso é ignorado
        trocarReconhecedor()
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) texto("Sem internet pra entender a voz... tentando de novo")
        else if (error == SpeechRecognizer.ERROR_AUDIO) texto("Microfone ocupado (outro app usando)... tentando de novo")
        deNovo(when { errosSeguidos <= 2 -> 600L; errosSeguidos <= 6 -> 2500L; else -> 10_000L })
    }

    private fun trocarReconhecedor() {
        try { sr?.destroy() } catch (_: Throwable) {}
        sr = null
    }

    /** volta a ouvir daqui a pouco (se nada mudou nesse meio tempo) */
    private fun deNovo(ms: Long) {
        val id = sessao
        main.postDelayed({ if (ligada && id == sessao && estado == Estado.OUVINDO) ouvir() }, ms)
    }

    private fun ouvir() {
        val a = ctx ?: act?.applicationContext ?: return
        sessao++
        val id = sessao
        estado = Estado.OUVINDO
        borda?.estado = 0
        orbe?.ativo = true
        texto("Ouvindo...")
        Voz.parar()
        inicioEscuta = System.currentTimeMillis()
        try {
            // um reconhecedor novo a cada escuta: reaproveitar o antigo dava "ocupado" e derrubava a escuta
            trocarReconhecedor()
            val s = SpeechRecognizer.createSpeechRecognizer(a)
            s.setRecognitionListener(ouvinte(id))
            sr = s
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.packageName)
                // assim que você faz uma pausa curtinha, ela já fecha e executa (não fica esperando mais)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                // só o microfone do celular; não tenta puxar áudio de outras fontes
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            s.startListening(i)
        } catch (t: Throwable) {
            NLog.add("ia", "escuta: não começou (${t.javaClass.simpleName})")
            if (ligada) { errosSeguidos++; deNovo(3000) } else parar()
        }
    }

    /** a tela da IA está aberta na frente? (na tela ela pega tudo; fora dela, filtra o ruído) */
    private fun naTelaIAagora() = act?.let { it.naFrente && it.atual() is AssistenteScreen } == true

    /** o que fazer com o que ela ouviu */
    private fun decidir(fala: String) {
        val naTelaIA = naTelaIAagora()
        // 1) uma palavra-chave sua: faz na hora
        val pc = PalavrasChave.achar(fala)
        if (pc != null) { responder(fala, pc, true); return }
        // 2) chamou pelo nome ("Ô João!" → "Oi!"; ou tudo junto: "João, abre o jogo")
        val semNome = PalavrasChave.tirarNome(fala, nomeChamada)
        if (semNome != null) {
            if (semNome.isBlank()) { Assistente.anotar(true, fala); Assistente.anotar(false, "Oi!"); falar("Oi!") } else responder(fala, semNome, false)
            return
        }
        // 3) na tela da IA, logo depois de chamar ela, ou com "só quando me chamar" desligado: tudo vai pra IA
        if (naTelaIA || !soComNome || System.currentTimeMillis() < conversaAte) { responder(fala, fala, false); return }
        // 4) fora da tela: comandos que ela entende sozinha (liga o bypass, abre o jogo...)
        val ps = Comandos.entender(fala).filter { it.acao != "pesquisar" && it.acao != "youtube" }
        if (ps.isNotEmpty()) { responder(fala, fala, true); return }
        // conversa de fundo (sem o nome dela): ignora e continua ouvindo
        texto("Ouvindo...")
        deNovo(100)
    }

    private fun responder(fala: String, pedido: String, localPrimeiro: Boolean) {
        val c = ctx ?: act?.applicationContext ?: return
        val a = act
        sessao++
        estado = Estado.PENSANDO
        borda?.estado = 1
        orbe?.ativo = false
        texto("Pensando...")
        Assistente.anotar(true, fala)
        Bg.io {
            val r = try { Assistente.responder(c, a, pedido, localPrimeiro = localPrimeiro) } catch (t: Throwable) { "Deu um erro aqui (${t.javaClass.simpleName}). Tenta de novo." }
            Bg.ui {
                Assistente.anotar(false, r)
                if (estado == Estado.PENSANDO) falar(r)
            }
        }
    }

    /** a tela da IA vai responder um pedido escrito: para de ouvir enquanto isso */
    fun pausar() {
        if (!ligada || estado != Estado.OUVINDO) return
        sessao++
        try { sr?.cancel() } catch (_: Throwable) {}
        estado = Estado.PENSANDO
        borda?.estado = 1
        orbe?.ativo = false
        texto("Pensando...")
    }

    /** resposta de um pedido escrito com o microfone ligado: fala e volta a ouvir */
    fun falarEVoltar(r: String) { if (ligada) falar(r) else Voz.falar(r) }

    private fun falar(r: String) {
        estado = Estado.FALANDO
        borda?.estado = 2
        texto(r.lines().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('✓', '✗', '⚠', '⏰', ' ') ?: "")
        val depois = {
            if (estado == Estado.FALANDO) {
                // por uns segundos depois de falar, o que você disser é pra ela (sem precisar chamar de novo)
                conversaAte = System.currentTimeMillis() + 10_000L
                if (ligada) ouvir() else parar()
            }
        }
        Voz.aoTerminar = { fimFala?.let { main.removeCallbacks(it) }; depois() }
        // se a voz não avisar que terminou, não fica presa: confere de tempos em tempos
        fimFala?.let { main.removeCallbacks(it) }
        val f = object : Runnable {
            override fun run() {
                if (estado != Estado.FALANDO) return
                if (Voz.falando() || Voz.buscando) main.postDelayed(this, 700) else depois()
            }
        }
        fimFala = f
        main.postDelayed(f, maxOf(4000L, r.length * 85L))
        Voz.falar(r)
    }

    /** só na prévia do CI: mostra a luz ouvindo, pensando e falando (sem microfone) pra tirar print e vídeo */
    fun demo(a: MainActivity) {
        if (!BuildConfig.PREVIEW) return
        act = a
        mostra(a)
        estado = Estado.OUVINDO
        var k = 0
        val passo = object : Runnable {
            override fun run() {
                if (camada == null) return
                k++
                when {
                    k < 60 -> { borda?.estado = 0; orbe?.ativo = true; borda?.nivel = (0.25 + 0.75 * Math.abs(Math.sin(k / 3.0))).toFloat()
                        texto(if (k < 22) "Ouvindo..." else "“liga o bypass e abre o Free Fire MAX”") }
                    k < 90 -> { borda?.estado = 1; orbe?.ativo = false; borda?.nivel = 0f; texto("Pensando...") }
                    else -> { borda?.estado = 2; texto("Pronto! Liguei o bypass e abri o Free Fire MAX.") }
                }
                if (k > 150) k = 0
                main.postDelayed(this, 100)
            }
        }
        main.post(passo)
    }

    /** para de ouvir agora e apaga a luz devagar (o microfone continua "ligado" se você não desligou) */
    fun parar() {
        estado = Estado.PARADO
        sessao++
        trocarReconhecedor()
        Voz.aoTerminar = null
        fimFala?.let { main.removeCallbacks(it) }
        Voz.parar()
        ultimoStatus = null
        IaFundoService.status(null)
        val f = camada
        val b = borda
        camada = null; borda = null; orbe = null; txt = null
        if (f != null && b != null) {
            f.getChildAt(1)?.animate()?.alpha(0f)?.setDuration(250)?.start()
            b.aoSumir = { (f.parent as? ViewGroup)?.removeView(f) }
            b.sumindo = true
        } else if (f != null) (f.parent as? ViewGroup)?.removeView(f)
    }
}

/**
 * Palavras-chave suas: falou a palavra, ela faz o que você combinou na hora (sem esperar a IA).
 * Ex.: "modo jogo" → "liga o bypass, liga a otimização e abre o free fire max".
 */
object PalavrasChave {
    class Item(val palavra: String, val faz: String)

    fun lista(): List<Item> = try {
        val a = org.json.JSONArray(Prefs.extraTxt("ia_palavras")?.takeIf { it.isNotBlank() } ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> Item(o.optString("p"), o.optString("f")) }?.takeIf { it.palavra.isNotBlank() && it.faz.isNotBlank() }
        }
    } catch (_: Throwable) { emptyList() }

    private fun salvar(l: List<Item>) {
        val a = org.json.JSONArray()
        l.forEach { a.put(org.json.JSONObject().put("p", it.palavra).put("f", it.faz)) }
        Prefs.extraTxt("ia_palavras", a.toString())
    }

    fun adicionar(palavra: String, faz: String) {
        val k = limpa(palavra)
        salvar(lista().filter { limpa(it.palavra) != k } + Item(palavra.trim(), faz.trim()))
    }

    fun remover(palavra: String) { salvar(lista().filter { it.palavra != palavra }) }

    fun limpa(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** o que fazer, se a fala tem uma das palavras-chave (a maior primeiro) */
    fun achar(fala: String): String? {
        val n = " " + limpa(fala) + " "
        return lista().sortedByDescending { it.palavra.length }.firstOrNull { val k = limpa(it.palavra); k.isNotEmpty() && n.contains(" $k ") }?.faz
    }

    /** "Nocry" do jeito que o Google costuma escrever: "nocry", "no cry", "nocri", "nó cry", "nokri"... */
    private const val NOCRY = "no\\s?[ck]\\s?r(?:i|y|ai)e?"

    /** tira o nome dela da fala ("ô joão, abre o jogo" → "abre o jogo"). null = não chamou */
    fun tirarNome(fala: String, nome: String): String? {
        val n = limpa(fala)
        val k = limpa(nome)
        val dela = if (k.isEmpty() || Regex(NOCRY).matches(k)) NOCRY else k.split(" ").joinToString("\\s+") { Regex.escape(it) } + "|" + NOCRY
        val re = Regex("(?:^|\\s)(?:(?:o|oh|ei|oi|ok|ola|fala|e ai)\\s+)?(?:$dela)(?=\\s|$)")
        val m = re.find(n) ?: return null
        return (n.substring(0, m.range.first) + " " + n.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * IA em segundo plano: o serviço com microfone que deixa ela continuar ouvindo com o NOCRY minimizado.
 * Nada aparece por cima dos outros apps: só a notificação (obrigatória do Android) com Pausar e Desligar.
 */
class IaFundoService : android.app.Service() {
    companion object {
        @Volatile var ativo = false
            private set
        private var eu: IaFundoService? = null
        private const val ID = 1077

        /** mostra na notificação o que a IA está fazendo (null = esperando) */
        fun status(s: String?) { eu?.let { sv -> Handler(Looper.getMainLooper()).post { sv.notifica(s) } } }

        /** liga o serviço (só com o app aberto: o Android não deixa começar a ouvir escondido) */
        fun iniciar(c: Context) {
            if (ativo) return
            try {
                val i = Intent(c, IaFundoService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (t: Throwable) { NLog.add("ia", "segundo plano não ligou (${t.javaClass.simpleName})") }
        }

        fun fechar(c: Context) {
            try { c.stopService(Intent(c, IaFundoService::class.java)) } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun montar(s: String?): android.app.Notification {
        val abrir = android.app.PendingIntent.getActivity(this, 77, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val alternar = android.app.PendingIntent.getService(this, 79, Intent(this, IaFundoService::class.java).setAction("alternar"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val fechar = android.app.PendingIntent.getService(this, 78, Intent(this, IaFundoService::class.java).setAction("fechar"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val ic = { r: Int -> android.graphics.drawable.Icon.createWithResource(this, r) }
        val ouvindo = SessaoIA.ligada
        return android.app.Notification.Builder(this, Notif.CH_SVC)
            .setSmallIcon(R.drawable.ic_ia)
            .setContentTitle(if (ouvindo) "IA do NOCRY ouvindo" else "IA do NOCRY pausada")
            .setContentText(s ?: if (ouvindo) "Fale um comando. Pra conversar, me chame: \"${SessaoIA.nomeChamada}\"" else "Toque em Ouvir pra ela voltar a te ouvir")
            .setContentIntent(abrir)
            .addAction(android.app.Notification.Action.Builder(ic(R.drawable.ic_mic), if (ouvindo) "Pausar" else "Ouvir", alternar).build())
            .addAction(android.app.Notification.Action.Builder(ic(R.drawable.ic_xcircle), "Desligar", fechar).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notifica(s: String?) {
        try { getSystemService(android.app.NotificationManager::class.java)?.notify(ID, montar(s)) } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        try { Prefs.init(this); Notif.init(this) } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, montar(null), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, montar(null))
        } catch (t: Throwable) { NLog.add("ia", "segundo plano não ligou (${t.javaClass.simpleName})"); stopSelf(); return }
        eu = this
        ativo = true
        Voz.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "fechar", "parar" -> { SessaoIA.desligar(this, fecharServico = false); stopSelf(); return START_NOT_STICKY }
            "alternar", "falar" -> { if (SessaoIA.ligada) SessaoIA.desligar(this, fecharServico = false) else SessaoIA.ligarFundo(this); notifica(null) }
            // o Android reiniciou o serviço (tinha fechado): volta a ouvir sozinho
            null -> { if (Prefs.lerNum("ia_ouvindo", 0L) == 1L && !SessaoIA.ligada) SessaoIA.ligarFundo(this) }
        }
        // START_STICKY: se o Android fechar o serviço por falta de memória, ele liga de novo
        return android.app.Service.START_STICKY
    }

    /** o usuário tirou o app dos recentes: o serviço continua ouvindo (não morre junto) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (SessaoIA.ligada) notifica(null) else { try { stopSelf() } catch (_: Throwable) {} }
    }

    override fun onDestroy() {
        ativo = false
        eu = null
        SessaoIA.servicoFechou()
        super.onDestroy()
    }
}

/** bloco "IA NOCRY" no menu do topo do celular: liga e desliga o microfone da IA */
class IaTile : android.service.quicksettings.TileService() {
    companion object {
        fun atualizar(c: Context) {
            try { android.service.quicksettings.TileService.requestListeningState(c, android.content.ComponentName(c, IaTile::class.java)) } catch (_: Throwable) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            t.state = if (SessaoIA.ligada) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE
            t.label = "IA NOCRY"
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (SessaoIA.ligada) "ouvindo" else "desligada"
            t.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        when {
            SessaoIA.ligada -> SessaoIA.desligar(this)
            IaFundoService.ativo -> SessaoIA.ligarFundo(this)
            else -> {
                // o Android só deixa ligar o microfone em segundo plano com uma tela do app na frente:
                // abre uma tela invisível que liga tudo e fecha na hora (você continua onde estava)
                val i = Intent(this, IaLigarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                try {
                    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 80, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                    else @Suppress("DEPRECATION") startActivityAndCollapse(i)
                } catch (_: Throwable) {}
            }
        }
        onStartListening()
    }
}

/** tela invisível do bloco "IA NOCRY": liga o microfone da IA em segundo plano e fecha na hora (sem entrar no app) */
class IaLigarActivity : android.app.Activity() {
    private var ok = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Prefs.init(applicationContext) } catch (_: Throwable) {}
        val mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ok = mic && SpeechRecognizer.isRecognitionAvailable(this)
        if (!ok) {
            // falta liberar o microfone (só na primeira vez): aí abre o app pra pedir
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ia_ouvir", true)) } catch (_: Throwable) {}
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ok || isFinishing) return
        ok = false
        // com esta tela (invisível) na frente, o Android deixa ligar o serviço do microfone
        IaFundoService.iniciar(this)
        SessaoIA.ligarFundo(this)
        Handler(Looper.getMainLooper()).postDelayed({ finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }, 300)
    }
}
