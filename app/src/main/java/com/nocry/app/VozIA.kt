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
        if (sumindo && alfa <= 0f) {
            // nunca tirar a view da tela no meio do desenho (era o "DecorView contains null child" que fechava o app)
            val cb = aoSumir; aoSumir = null
            if (cb != null) post { try { cb() } catch (_: Throwable) {} }
            return
        }
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
    /** quando você faz uma pausa, fecha a escuta e executa na hora (o Google costuma ignorar o tempo de silêncio do pedido) */
    private var fecharPorPausa: Runnable? = null
    private var ultimaParcial = ""
    /** o que ela está falando agora (pra não confundir a própria voz com você ao interromper) */
    private var falandoTexto = ""
    private var errosNaFala = 0
    /** cada escuta tem um número: resposta atrasada de uma escuta velha é ignorada
     *  (era isso + reaproveitar o reconhecedor antigo que dava "não deu pra ouvir agora") */
    private var sessao = 0
    private var errosSeguidos = 0
    private var inicioEscuta = 0L
    /** a última vez que o reconhecedor deu sinal de vida (pronto, nível do som...). Sem sinal por uns segundos = travou */
    @Volatile private var ultimoSinal = 0L
    private var travadas = 0
    /** a pessoa já falou nesta escuta: o Google pode demorar pra devolver o texto, então o vigia espera mais */
    @Volatile private var falou = false
    /** alterna entre o reconhecedor do Google e o padrão do celular quando um deles não está respondendo */
    private var preferirPadrao = false
    private var recEnergia: android.content.BroadcastReceiver? = null
    private var ultimoStatus: String? = null

    /** com o app minimizado, continua ouvindo (padrão: sim) */
    var foraDoApp: Boolean
        get() = Prefs.lerNum("ia_fundo", 1L) == 1L
        set(v) = Prefs.gravaNum("ia_fundo", if (v) 1L else 0L)

    /** fora da tela da IA: conversa só quando você fala o nome dela ("Nocry").
     *  Já vem DESLIGADO (ela responde normal, sem precisar do nome). Chave nova pra valer pra todo mundo.
     *  Comandos (liga o bypass, abre o jogo...) e palavras-chave valem sempre */
    var soComNome: Boolean
        get() = Prefs.lerNum("ia_nome2", 0L) == 1L
        set(v) = Prefs.gravaNum("ia_nome2", if (v) 1L else 0L)

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
        if (!podeOuvir(a)) { semJanela(); return }
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
        // você ligou o microfone: tenta o ouvido novo de novo (uma pausa antiga não vale mais)
        Prefs.gravaNum("ia_stt_ate", 0L)
        vigiarEnergia(a)
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
        if (!temMic(c) || !podeOuvir(c)) return
        ctx = c.applicationContext
        Voz.init(c)
        ligada = true
        Prefs.gravaNum("ia_stt_ate", 0L)
        vigiarEnergia(c)
        Prefs.gravaNum("ia_ouvindo", 1L)
        errosSeguidos = 0
        act?.let { if (it.naFrente) mostra(it) }
        if (estado == Estado.PARADO) ouvir()
        IaTile.atualizar(c)
    }

    /** desliga o microfone (e o serviço de segundo plano) */
    fun desligar(c: Context?, fecharServico: Boolean = true) {
        ligada = false
        soltarEnergia(c)
        Prefs.gravaNum("ia_ouvindo", 0L)
        parar()
        val cc = c ?: ctx ?: return
        if (fecharServico) IaFundoService.fechar(cc)
        IaTile.atualizar(cc)
    }

    /** o app voltou pra frente: se ela já estava ouvindo (microfone ligado por você), mostra a luz e continua.
     *  Abrir o app ou a tela da IA NUNCA liga o microfone sozinho: só quando você toca nele. */
    fun aoVoltar(a: MainActivity) {
        act = a
        if (BuildConfig.PREVIEW) return
        if (ligada) {
            mostra(a)
            if (estado == Estado.PARADO) { errosSeguidos = 0; ouvir() }
        } else if (!IaFundoService.ativo && Prefs.lerNum("ia_ouvindo", 0L) == 1L) {
            // sobrou a marca de "ouvindo" de quando o app foi fechado: apaga, senão o Android podia religar o microfone depois
            Prefs.gravaNum("ia_ouvindo", 0L)
        }
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
        if (f0 != null) f0.post { try { (f0.parent as? ViewGroup)?.removeView(f0) } catch (_: Throwable) {} }
        val raiz = a.window.decorView as? ViewGroup ?: return
        val f = FrameLayout(a)
        val b = BordaIA(a)
        f.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val (pil, o, t) = criaPilula(a) { if (!interromper() && a.atual() !is AssistenteScreen) a.abre(AssistenteScreen(a)) }
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

    private fun ouvinte(id: Int, durante: Boolean = false) = object : RecognitionListener {
        fun velho() = id != sessao || !(estado == Estado.OUVINDO || (durante && estado == Estado.FALANDO))
        override fun onReadyForSpeech(params: Bundle?) { if (!velho()) { ultimoSinal = android.os.SystemClock.elapsedRealtime(); travadas = 0; texto("Ouvindo...") } }
        override fun onBeginningOfSpeech() { ultimoSinal = android.os.SystemClock.elapsedRealtime(); falou = true }
        override fun onRmsChanged(rmsdB: Float) { ultimoSinal = android.os.SystemClock.elapsedRealtime(); if (!velho()) borda?.nivel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { ultimoSinal = android.os.SystemClock.elapsedRealtime(); if (!velho()) borda?.nivel = 0f }
        override fun onError(error: Int) {
            if (velho()) return
            // escutando enquanto ela fala: erro ou silêncio só recomeça a escuta (sem aviso)
            if (estado == Estado.FALANDO) { if (++errosNaFala < 6) main.postDelayed({ if (id == sessao && estado == Estado.FALANDO) escutarDuranteFala() }, 500); return }
            aoErro(error)
        }
        override fun onResults(results: Bundle?) {
            if (velho()) return
            // ainda falando = só ouviu a própria voz: continua escutando pra você poder interromper
            if (estado == Estado.FALANDO) { main.postDelayed({ if (id == sessao && estado == Estado.FALANDO) escutarDuranteFala() }, 250); return }
            fecharPorPausa?.let { main.removeCallbacks(it) }
            // se o resultado final vier vazio, aproveita o que ela já tinha entendido (pra nunca sumir o que você falou)
            val fala = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: ultimaParcial.trim()
            ultimaParcial = ""
            errosSeguidos = 0
            // fora da tela da IA, corta barulho de fundo (pedaço curtinho ou de confiança baixa). Na tela, pega tudo.
            // confiança 0 (ou negativa) quer dizer "não informada": o Google manda assim quase sempre, então não conta
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            val ruido = !naTelaIAagora() && (PalavrasChave.limpa(fala).length < 3 || (conf > 0f && conf < 0.15f))
            if (fala.isBlank() || ruido) { if (ligada) deNovo(120) else parar(); return }
            decidir(fala)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (velho()) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (p.isBlank()) return
            if (estado == Estado.FALANDO) {
                // você falou por cima dela (e não é a voz dela no microfone): ela para e te escuta
                if (eco(p) || PalavrasChave.limpa(p).length < 3) return
                cortarFala()
                estado = Estado.OUVINDO
                borda?.estado = 0
                orbe?.ativo = true
            }
            ultimaParcial = p
            texto("“$p”", notif = false)
            // parou de falar por ~2s depois de já ter dito algo: fecha e executa (não fica esperando sem fim)
            fecharPorPausa?.let { main.removeCallbacks(it) }
            val meu = sessao
            val r = Runnable {
                if (velho()) return@Runnable
                try { sr?.stopListening() } catch (_: Throwable) {}
                // reserva: se o reconhecedor não devolver nada em 2s, usa o que já entendeu (nunca trava esperando)
                main.postDelayed({
                    if (meu == sessao && estado == Estado.OUVINDO && ultimaParcial.trim().length >= 2) {
                        val p = ultimaParcial.trim(); ultimaParcial = ""; decidir(p)
                    }
                }, 2000)
            }
            fecharPorPausa = r
            main.postDelayed(r, 2000)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun aoErro(error: Int) {
        fecharPorPausa?.let { main.removeCallbacks(it) }
        // erro 11 ("serviço de voz caiu") e 5 vêm toda hora no Android 16 quando você fica em silêncio:
        // é só um "não ouvi nada". Antes contava como erro e ela esperava até 10s sem ouvir (era o "às vezes não ouve")
        val caiu = error == 11 || error == SpeechRecognizer.ERROR_CLIENT
        if (caiu) trocarReconhecedor()
        val nada = caiu || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        // o Google às vezes dá "não entendi" mesmo tendo entendido algo: aproveita o que ela captou
        if (nada && ultimaParcial.trim().length >= 3) {
            val p = ultimaParcial.trim(); ultimaParcial = ""
            decidir(p); return
        }
        if (!nada) NLog.add("ia", "escuta: erro $error (${nomeErro(error)})" + if (carregando()) " · carregando" else "")
        if (caiu && ligada) { errosSeguidos = 0; deNovo(200); return }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            if (act?.naFrente == true) {
                (act ?: ctx)?.let { Ui.toast(it, "libere o microfone pro NOCRY nas configurações do celular") }
                desligar(ctx); return
            }
            // fora da tela: o Android tirou o microfone por um instante. Continua ligada e tenta de novo sozinha
            NLog.add("ia", "microfone negado em segundo plano; tentando de novo")
            trocarReconhecedor()
            errosSeguidos++
            if (ligada) deNovo(if (errosSeguidos <= 3) 1500 else 5000)
            return
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
        // um reconhecedor que dá erro várias vezes seguidas: tenta o outro (Google ↔ padrão do celular)
        if (errosSeguidos == 3 || errosSeguidos == 6) preferirPadrao = !preferirPadrao
        sessao++   // o reconhecedor velho pode avisar mais um erro ao ser fechado: esse aviso é ignorado
        trocarReconhecedor()
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) texto("Sem internet pra entender a voz... tentando de novo")
        else if (error == SpeechRecognizer.ERROR_AUDIO) texto("Microfone ocupado (outro app usando)... tentando de novo")
        deNovo(when { errosSeguidos <= 2 -> 500L; else -> 3000L })
    }

    /** usa o reconhecimento de voz do Google quando tem (o do Xiaomi às vezes não responde) */
    private fun criarReconhecedor(a: Context): SpeechRecognizer {
        if (preferirPadrao) return SpeechRecognizer.createSpeechRecognizer(a)
        try {
            val l = a.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            val g = l.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
                ?: l.firstOrNull { it.serviceInfo.packageName.startsWith("com.google") }
            if (g != null) return SpeechRecognizer.createSpeechRecognizer(a, android.content.ComponentName(g.serviceInfo.packageName, g.serviceInfo.name))
        } catch (_: Throwable) {}
        return SpeechRecognizer.createSpeechRecognizer(a)
    }

    private fun trocarReconhecedor() {
        try { sr?.destroy() } catch (_: Throwable) {}
        sr = null
    }

    private fun carregando(): Boolean {
        val c = ctx ?: return false
        return try { Power.plugged(c) } catch (_: Throwable) { false }
    }

    /**
     * se o reconhecedor não dá sinal nenhum (nem o "pronto" nem o nível do som) por uns segundos, ele travou por dentro
     * (acontecia com o celular no carregador: outro serviço segurando o microfone) e antes ela ficava "ouvindo" pra sempre,
     * sem erro nenhum. Agora recomeça a escuta sozinha e, se repetir, troca de reconhecedor.
     */
    private fun vigiar(id: Int) {
        main.postDelayed({
            if (id != sessao || !ligada || estado != Estado.OUVINDO) return@postDelayed
            val parado = android.os.SystemClock.elapsedRealtime() - ultimoSinal
            if (parado >= (if (falou) 20000L else 6000L)) {
                travadas++
                NLog.add("ia", "escuta: sem sinal do microfone há ${parado / 1000}s (carregando: ${if (carregando()) "sim" else "não"}, vez $travadas); recomecei")
                if (travadas >= 2) preferirPadrao = !preferirPadrao
                sessao++
                trocarReconhecedor()
                deNovo(400)
                return@postDelayed
            }
            vigiar(id)
        }, 3000)
    }

    /** ligou ou tirou o carregador: o Android muda o áudio e o microfone trava. Recomeça a escuta do zero */
    private fun vigiarEnergia(c: Context) {
        if (recEnergia != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!ligada) return
                NLog.add("ia", "escuta: o carregador foi ${if (intent?.action == Intent.ACTION_POWER_CONNECTED) "ligado" else "tirado"}; recomecei a escuta")
                travadas = 0; errosSeguidos = 0; preferirPadrao = false; falhasNuvem = 0
                if (estado == Estado.OUVINDO) { sessao++; trocarReconhecedor(); Ouvido.parar(); deNovo(800) }
            }
        }
        try {
            val f = android.content.IntentFilter().also { it.addAction(Intent.ACTION_POWER_CONNECTED); it.addAction(Intent.ACTION_POWER_DISCONNECTED) }
            c.applicationContext.registerReceiver(r, f)
            recEnergia = r
        } catch (_: Throwable) {}
    }

    private fun soltarEnergia(c: Context?) {
        val r = recEnergia ?: return
        recEnergia = null
        try { (c ?: ctx)?.applicationContext?.unregisterReceiver(r) } catch (_: Throwable) {}
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
        ultimaParcial = ""
        fecharPorPausa?.let { main.removeCallbacks(it) }
        inicioEscuta = System.currentTimeMillis()
        if (ouvidoNovo()) { ouvirProprio(a); return }
        Ouvido.parar()
        try {
            // um reconhecedor novo a cada escuta: reaproveitar o antigo dava "ocupado" e derrubava a escuta
            trocarReconhecedor()
            val s = criarReconhecedor(a)
            s.setRecognitionListener(ouvinte(id))
            sr = s
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.packageName)
                // quando você faz uma pausa de ~2s, ela fecha e executa (não fica esperando mais)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
                // só o microfone do celular; não tenta puxar áudio de outras fontes
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            s.startListening(i)
            falou = false
            ultimoSinal = android.os.SystemClock.elapsedRealtime()
            vigiar(id)
        } catch (t: Throwable) {
            NLog.add("ia", "escuta: não começou (${t.javaClass.simpleName})")
            if (ligada) { errosSeguidos++; deNovo(3000) } else parar()
        }
    }

    // ------------------------------------------------------------------ ouvido novo: microfone aberto + Whisper no servidor
    // O reconhecedor do Google cai toda hora no Android 16 (erros 5 e 11) e ela ficava "ouvindo" sem ouvir.
    // Agora o app abre o microfone sozinho, separa cada fala (Ouvido.kt) e o servidor do NOCRY escreve o texto.
    // Se o servidor não responder (sem internet, limite do dia, servidor sem a novidade), cai no reconhecedor do celular.

    /** o ouvido novo está valendo agora? (não vale na prévia, sem key, ou em pausa porque o servidor não respondeu) */
    fun ouvidoNovo(): Boolean = !BuildConfig.PREVIEW && BuildConfig.API_URL.isNotBlank() && Prefs.licKey != null &&
        System.currentTimeMillis() >= Prefs.lerNum("ia_stt_ate", 0L)

    /** dá pra ouvir: pelo ouvido novo ou pelo reconhecedor do celular */
    fun podeOuvir(c: Context) = ouvidoNovo() || SpeechRecognizer.isRecognitionAvailable(c)

    /** escrevendo o que você disse (o microfone fica mudo nesse meio tempo) */
    @Volatile private var entendendo = false
    private var falhasNuvem = 0

    /** deixa o ouvido novo de lado por um tempo e volta pro reconhecedor do celular */
    private fun nuvemPausa(ms: Long, motivo: String) {
        Prefs.gravaNum("ia_stt_ate", System.currentTimeMillis() + ms)
        NLog.add("ia", "escuta: ouvido novo em pausa ($motivo); uso o reconhecedor do celular")
        relatar("pausa: $motivo", forcar = true)
        Ouvido.parar()
        entendendo = false
        falhasNuvem = 0
        if (ligada && estado == Estado.OUVINDO) ouvir()
    }

    private fun dica(): String {
        val k = PalavrasChave.lista().take(5).joinToString("") { ", " + it.palavra }
        return ("$nomeChamada, Free Fire, bypass, otimização$k").take(190)
    }

    private fun ouvirProprio(a: Context) {
        trocarReconhecedor()
        entendendo = false
        borda?.nivel = 0f
        val novo = !Ouvido.rodando
        Ouvido.iniciar(a, ouvidoOuvinte)
        // 8 s e 45 s depois de abrir o microfone, manda pro painel da staff só os números do que ele ouviu (nunca áudio nem texto)
        if (novo) {
            val id = ++relatoId
            main.postDelayed({ if (id == relatoId && ligada) relatar("8s") }, 8_000)
            main.postDelayed({ if (id == relatoId && ligada) relatar("45s") }, 45_000)
        }
    }

    private var relatoId = 0
    private var ultimoRelato = 0L

    /** manda pro painel da staff os números do ouvido (quanto som chegou, qual fonte, o que falhou). Sem áudio e sem texto. */
    private fun relatar(motivo: String, forcar: Boolean = false) {
        val agora = System.currentTimeMillis()
        if (!forcar && agora - ultimoRelato < 30_000) return
        ultimoRelato = agora
        val c = ctx ?: return
        val d = Ouvido.resumo().put("quando", motivo).put("ouvidoNovo", ouvidoNovo()).put("estado", estado.name)
            .put("fundo", IaFundoService.ativo).put("naFrente", act?.naFrente == true).put("pausaAte", Prefs.lerNum("ia_stt_ate", 0L) - agora)
        Bg.io { try { Comunidade.x(c, "diag", org.json.JSONObject().put("dados", org.json.JSONObject().put("voz", d))) } catch (_: Throwable) {} }
    }

    private val ouvidoOuvinte = object : Ouvido.Ouvinte {
        override fun mudo() = !ligada || estado != Estado.OUVINDO || entendendo
        override fun pronto() { if (!mudo()) texto("Ouvindo...", notif = false) }
        override fun nivel(n: Float) { borda?.nivel = if (mudo()) 0f else n }
        override fun vozDetectada() { if (!mudo()) texto("Te ouvindo falar...", notif = false) }
        override fun falhou(motivo: String) {
            if (!ligada) return
            // o microfone em si não está dando som: o jeito antigo usaria o mesmo microfone, então não adianta trocar.
            // Ele continua tentando sozinho; aqui só avisa o motivo (em vez de ficar "ouvindo" de mentira)
            // outro app (gravação de tela, transmissão) pode estar com o microfone: o Android deixa só um mandar som
            val ocupado = if (motivo.contains("silêncio")) Ouvido.microfoneOcupado() else null
            NLog.add("ia", "escuta: ${ocupado ?: motivo}")
            if (ocupado != null) {
                texto("Outro app está com o microfone (gravação ou transmissão). Continuo tentando.")
                act?.let { if (it.naFrente) Ui.toast(it, "$ocupado. Se puder, deixe o NOCRY aberto na frente ou use o chat por escrito") }
            } else {
                texto("Sem som do microfone: $motivo")
                act?.let { if (it.naFrente) Ui.toast(it, "o microfone não está dando som ($motivo). Veja a permissão de microfone do NOCRY") }
            }
            relatar(ocupado ?: motivo, forcar = true)
        }
        override fun fala(wav: ByteArray, seg: Float) {
            if (mudo()) return
            val c = ctx ?: return
            entendendo = true
            val minha = sessao
            borda?.nivel = 0f
            texto("Entendendo...", notif = false)
            val dica = dica()
            // se por algum motivo a resposta nunca voltar, não deixa o microfone mudo pra sempre
            main.postDelayed({ if (entendendo && sessao == minha) entendendo = false }, 50_000)
            Bg.io {
                val r = try { Ouvido.transcrever(c, wav, seg, dica) } catch (_: Throwable) { Ouvido.Texto(null, "rede") }
                Bg.ui { aoTexto(r, minha, seg, dica) }
            }
        }
    }

    /** o servidor devolveu o que você disse */
    private fun aoTexto(r: Ouvido.Texto, minha: Int, seg: Float, dica: String) {
        if (minha != sessao) return   // enquanto escrevia, você parou ou pediu por escrito: outro caminho cuida
        entendendo = false
        if (!ligada || estado != Estado.OUVINDO) return
        val t = r.texto
        if (t == null) {
            when (r.motivo) {
                "limite" -> { texto("Acabou o limite de voz de hoje: uso a escuta do celular"); nuvemPausa(6 * 3_600_000L, "limite do dia") }
                "indisponivel" -> nuvemPausa(2 * 60_000L, "servidor sem o ouvido novo")
                else -> {
                    falhasNuvem++
                    if (falhasNuvem >= 4) nuvemPausa(45_000L, "sem resposta do servidor")
                    else texto("Sem internet pra entender a voz... fala de novo")
                }
            }
            return
        }
        falhasNuvem = 0
        val fala = t.trim()
        relatar("escreveu ${fala.length} letras")
        // ruído que o Whisper "escreve" como frase, ou pedaço curtinho fora da tela da IA: ignora e continua ouvindo
        if (Ouvido.lixo(fala, seg, dica) || (!naTelaIAagora() && PalavrasChave.limpa(fala).length < 3)) { texto("Ouvindo...", notif = false); return }
        errosSeguidos = 0
        decidir(fala)
    }

    /** a tela da IA está aberta na frente? (na tela ela pega tudo; fora dela, filtra o ruído) */
    private fun naTelaIAagora() = act?.naFrente == true

    /** o que fazer com o que ela ouviu */
    private fun decidir(fala: String) {
        val naTelaIA = naTelaIAagora()
        // 0) criar, apagar ou listar palavra-chave falando ("quando eu falar bora jogar, abre o free fire"): vai direto, sem disparar a que já existe
        if (Acoes.gerePalavra(fala)) { responder(fala, fala, true); return }
        // 1) uma palavra-chave sua: faz na hora
        val pc = PalavrasChave.achar(fala)
        if (pc != null) { responder(fala, pc, true); return }
        // 2) chamou pelo nome ("Ô João!" → "Oi!"; ou tudo junto: "João, abre o jogo")
        val semNome = PalavrasChave.tirarNome(fala, nomeChamada)
        if (semNome != null) {
            if (semNome.isBlank()) { Assistente.anotar(true, fala); Assistente.anotar(false, "Oi!"); falar("Oi!") }
            else responder(fala, semNome, Comandos.entender(semNome).any { it.acao != "pesquisar" && it.acao != "youtube" })
            return
        }
        // 2b) ela acabou de perguntar ("qual bypass?", "normal ou max?"): a resposta curta vai pra ela, mesmo sem chamar o nome
        if (Acoes.aguardandoFf()) { responder(fala, fala, false); return }
        // 3) na tela da IA, logo depois de chamar ela, ou com "só quando me chamar" desligado: tudo vai pra IA
        if (naTelaIA || !soComNome || System.currentTimeMillis() < conversaAte) { responder(fala, fala, false); return }
        // 4) fora da tela: comandos que ela entende sozinha (liga o bypass, abre o jogo...)
        val ps = Comandos.entender(fala).filter { it.acao != "pesquisar" && it.acao != "youtube" }
        if (ps.isNotEmpty()) { responder(fala, fala, true); return }
        // conversa de fundo (sem o nome dela): não responde, mas mostra que ouviu e como chamar
        NLog.add("ia", "ouvi sem o nome: $fala")
        texto("Ouvi: \"${fala.take(40)}\". Pra conversar, me chame: ${nomeChamada}")
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
        val minha = sessao
        var avisou = false
        main.postDelayed({
            if (estado == Estado.PENSANDO && sessao == minha) {
                avisou = true
                NLog.add("ia", "resposta ainda não veio depois de 20s")
                falar("Tô demorando mais que o normal, mas continuo nisso.")
            }
        }, 20_000)
        // mesma fila da conversa escrita: o que você falou e o que você digitou são respondidos na ordem
        Assistente.pedir(c, a, pedido, null, localPrimeiro) { r ->
            // veio depois do aviso de demora: fala quando ela está só ouvindo (se você já pediu outra coisa, fica só no chat)
            if (ligada && ((estado == Estado.PENSANDO && sessao == minha) || (avisou && estado == Estado.OUVINDO))) falar(r)
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
                // espera a voz dela sumir do alto-falante antes de voltar a ouvir (senão ela escuta a própria voz e repete)
                if (ligada) main.postDelayed({ if (ligada && estado == Estado.FALANDO) ouvir() }, 500) else parar()
            }
        }
        Voz.aoTerminar = { fimFala?.let { main.removeCallbacks(it) }; depois() }
        // se a voz não avisar que terminou, não fica presa: confere de tempos em tempos
        fimFala?.let { main.removeCallbacks(it) }
        val comecou = System.currentTimeMillis()
        val limite = 8_000L + r.length * 120L
        val f = object : Runnable {
            override fun run() {
                if (estado != Estado.FALANDO) return
                val ainda = Voz.falando() || Voz.buscando
                if (ainda && System.currentTimeMillis() - comecou < limite) { main.postDelayed(this, 700); return }
                if (ainda) { NLog.add("ia", "fala travou: cortei e voltei a ouvir"); cortarFala() }
                depois()
            }
        }
        fimFala = f
        main.postDelayed(f, maxOf(4000L, r.length * 85L))
        falandoTexto = r
        errosNaFala = 0
        // o microfone fica DESLIGADO enquanto ela fala (senão ela escutava a própria voz e repetia no chat).
        // pra interromper, toque no microfone ou na barrinha do topo.
        try { sr?.cancel() } catch (_: Throwable) {}
        Voz.falar(r)
    }

    /** escuta enquanto ela fala (pra você poder interromper) */
    private fun escutarDuranteFala() {
        val a = ctx ?: act?.applicationContext ?: return
        if (estado != Estado.FALANDO || !ligada) return
        sessao++
        val id = sessao
        ultimaParcial = ""
        try {
            trocarReconhecedor()
            val s = criarReconhecedor(a)
            s.setRecognitionListener(ouvinte(id, durante = true))
            sr = s
            s.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.packageName))
        } catch (_: Throwable) {}
    }

    /** é a voz dela voltando pelo microfone? (as palavras ouvidas estão quase todas no que ela está falando) */
    private fun eco(ouvido: String): Boolean {
        val dela = PalavrasChave.limpa(falandoTexto).split(" ").filter { it.length > 1 }.toSet()
        val ws = PalavrasChave.limpa(ouvido).split(" ").filter { it.length > 1 }
        if (ws.isEmpty()) return true
        return ws.count { it in dela }.toFloat() / ws.size >= 0.6f
    }

    /** para a fala dela na hora */
    private fun cortarFala() {
        Voz.aoTerminar = null
        fimFala?.let { main.removeCallbacks(it) }
        Voz.parar()
    }

    /** tocou no microfone (ou na pílula) enquanto ela fala: ela para e já te escuta */
    fun interromper(): Boolean {
        if (estado != Estado.FALANDO) return false
        cortarFala()
        if (ligada) ouvir() else parar()
        return true
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
        Ouvido.parar()
        entendendo = false
        trocarReconhecedor()
        Voz.aoTerminar = null
        fimFala?.let { main.removeCallbacks(it) }
        fecharPorPausa?.let { main.removeCallbacks(it) }
        ultimaParcial = ""
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
        } else if (f != null) f.post { try { (f.parent as? ViewGroup)?.removeView(f) } catch (_: Throwable) {} }
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
    private const val NOCRY = "no\\s?(?:[ck]\\s?r(?:i|y|ai)e?|kwai|kuai|quai[s]?|cuai|crai|krai)"

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

        /** liga o serviço (só com o app aberto: o Android só dá o microfone em segundo plano se começar com o app na tela).
         *  Se já está ligado, "renova": pega o microfone de novo enquanto o app está na tela */
        fun iniciar(c: Context) {
            try {
                val i = Intent(c, IaFundoService::class.java)
                if (ativo) i.action = "renovar"
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
            // o Android obriga essa notificação enquanto ela ouve em segundo plano; você não precisa tocar nela
            .setContentText(s ?: if (ouvindo) "Pode falar, estou te ouvindo. Pra conversar, me chame: \"${SessaoIA.nomeChamada}\"" else "Pausada")
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
        } catch (t: Throwable) {
            // o Android reabriu o serviço com o app fora da tela e não deixou pegar o microfone
            NLog.add("ia", "segundo plano não ligou (${t.javaClass.simpleName}); o Android tenta de novo sozinho")
            stopSelf(); return
        }
        eu = this
        ativo = true
        Voz.init(this)
        // voltou sozinho depois de ter sido fechado? já volta a ouvir, sem você tocar em nada
        if (Prefs.lerNum("ia_ouvindo", 0L) == 1L && !SessaoIA.ligada) Handler(Looper.getMainLooper()).postDelayed({ SessaoIA.ligarFundo(this) }, 400)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // não conseguiu ficar em primeiro plano (o Android não deu o microfone): já avisou, só fecha
        if (!ativo) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            "fechar", "parar" -> { SessaoIA.desligar(this, fecharServico = false); stopSelf(); return START_NOT_STICKY }
            "alternar", "falar" -> { if (SessaoIA.ligada) SessaoIA.desligar(this, fecharServico = false) else SessaoIA.ligarFundo(this); notifica(null) }
            // o app está na tela agora: pega o microfone de novo (vale pra continuar ouvindo depois de minimizar)
            "renovar" -> {
                try {
                    if (Build.VERSION.SDK_INT >= 30) startForeground(ID, montar(null), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(ID, montar(null))
                } catch (t: Throwable) { NLog.add("ia", "não renovou o microfone (${t.javaClass.simpleName})") }
            }
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

/** segura o app vivo só enquanto um pedido escrito está sendo feito: sem isso o Android congela o app quando você
 *  sai da conversa e a resposta nunca chega. Some sozinho (e a notificação junto) quando o pedido termina. */
class IaPedidoService : android.app.Service() {
    companion object {
        @Volatile private var ativo = false
        private const val ID = 1078

        /** chamado com o app na tela (a hora de digitar e mandar): o Android deixa começar o serviço agora */
        fun segurar(c: Context) {
            if (IaFundoService.ativo || ativo) return
            try {
                val i = Intent(c, IaPedidoService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (t: Throwable) { NLog.add("ia", "pedido: não segurou o app (${t.javaClass.simpleName})") }
        }

        /** termina o último pedido: espera um instante (pode chegar outro logo) e fecha */
        fun soltar(c: Context) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!Assistente.pensando) try { c.stopService(Intent(c, IaPedidoService::class.java)) } catch (_: Throwable) {}
            }, 1500)
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try { Prefs.init(this); Notif.init(this) } catch (_: Throwable) {}
        try {
            val n = android.app.Notification.Builder(this, Notif.CH_SVC)
                .setSmallIcon(R.drawable.ic_ia)
                .setContentTitle("IA do NOCRY")
                .setContentText("Fazendo o seu pedido…")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
            ativo = true
        } catch (t: Throwable) {
            NLog.add("ia", "pedido: o Android não deixou segurar o app (${t.javaClass.simpleName})")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // se o Android reiniciar sozinho sem pedido nenhum, não faz nada e fecha
        if (!Assistente.pensando) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ativo = false
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
        ok = mic && SessaoIA.podeOuvir(this)
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
