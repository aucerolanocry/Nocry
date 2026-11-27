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

/** cores da luz da IA (ciano, lilás, rosa, dourado) */
private val CORES_IA = intArrayOf(0xFF7FE7FF.toInt(), 0xFFB388FF.toInt(), 0xFFFF7EDB.toInt(), 0xFFFFC46B.toInt(), 0xFF7FE7FF.toInt())

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
        sombra = SweepGradient(w / 2f, h / 2f, CORES_IA, null)
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
        giro = SweepGradient(w / 2f, h / 2f, CORES_IA, null)
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

/** a conversa por voz: ouve (sem janela do Google), pensa, fala e, no modo conversa, volta a ouvir */
object SessaoIA {
    enum class Estado { PARADO, OUVINDO, PENSANDO, FALANDO }

    const val REQ_MIC = 7703
    @Volatile var estado = Estado.PARADO
        private set

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var act: MainActivity? = null
    private var camada: FrameLayout? = null
    private var borda: BordaIA? = null
    private var orbe: OrbeMic? = null
    private var txt: TextView? = null
    private var semOuvir = 0
    private var fimFala: Runnable? = null

    /** conversa por voz (mãos livres): depois de falar, volta a ouvir */
    private val conversa get() = Prefs.lerNum("ia_maos", 0L) == 1L

    val ativa get() = estado != Estado.PARADO

    /** começa a ouvir. Sem a permissão do microfone, pede; sem reconhecimento de voz, usa a janela do Google */
    fun iniciar(a: MainActivity, semJanela: () -> Unit) {
        act = a
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { semJanela(); return }
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.aoPermissao = { req, ok ->
                if (req == REQ_MIC) { if (ok) iniciar(a, semJanela) else { Ui.toast(a, "sem o microfone liberado eu uso a janela do Google"); semJanela() } }
            }
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Voz.init(a)
        mostra(a)
        semOuvir = 0
        ouvir()
    }

    private fun mostra(a: MainActivity) {
        if (camada?.parent != null) { borda?.sumindo = false; return }
        val raiz = a.window.decorView as? ViewGroup ?: return
        val f = FrameLayout(a)
        val b = BordaIA(a)
        f.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // pílula do topo: microfone + o que está acontecendo + fechar
        val pil = LinearLayout(a)
        pil.orientation = LinearLayout.HORIZONTAL
        pil.gravity = Gravity.CENTER_VERTICAL
        pil.background = Ui.shape(a, Theme.alpha(0xFF0B0B12.toInt(), 0xE8), 99, Theme.alpha(Color.WHITE, 0x40), 1)
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
        x.setOnClickListener { parar() }
        pil.addView(x, LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)))
        // tocar na pílula: abre a conversa
        pil.setOnClickListener { if (a.atual() !is AssistenteScreen) a.abre(AssistenteScreen(a)) }
        val idSb = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        val topo = (if (idSb > 0) a.resources.getDimensionPixelSize(idSb) else Ui.dp(a, 24)) + Ui.dp(a, 8)
        f.addView(pil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).also {
            it.topMargin = topo; it.leftMargin = Ui.dp(a, 18); it.rightMargin = Ui.dp(a, 18)
        })
        raiz.addView(f, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        camada = f; borda = b; orbe = o; txt = t
    }

    private fun texto(s: String) { txt?.text = s }

    private val ouvinte = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { texto("Ouvindo...") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { borda?.nivel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { borda?.nivel = 0f }
        override fun onError(error: Int) {
            if (estado != Estado.OUVINDO) return
            val nada = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (nada && conversa && semOuvir < 1) { semOuvir++; main.postDelayed({ if (estado == Estado.OUVINDO) ouvir() }, 300); return }
            if (!nada) act?.let { Ui.toast(it, if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) "sem internet pra entender a voz" else "não deu pra ouvir agora") }
            parar()
        }
        override fun onResults(results: Bundle?) {
            val fala = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (fala.isEmpty()) { parar(); return }
            semOuvir = 0
            responder(fala)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { texto("“$it”") }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun ouvir() {
        val a = act ?: return
        estado = Estado.OUVINDO
        borda?.estado = 0
        orbe?.ativo = true
        texto("Ouvindo...")
        Voz.parar()
        try {
            val s = sr ?: SpeechRecognizer.createSpeechRecognizer(a.applicationContext).also { it.setRecognitionListener(ouvinte); sr = it }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            s.startListening(i)
        } catch (_: Throwable) { parar() }
    }

    private fun responder(fala: String) {
        val a = act ?: return
        estado = Estado.PENSANDO
        borda?.estado = 1
        orbe?.ativo = false
        texto("Pensando...")
        Assistente.anotar(true, fala)
        val c = a.applicationContext
        Bg.io {
            val r = Assistente.responder(c, a, fala)
            Bg.ui {
                Assistente.anotar(false, r)
                if (estado == Estado.PENSANDO) falar(r)
            }
        }
    }

    private fun falar(r: String) {
        estado = Estado.FALANDO
        borda?.estado = 2
        texto(r.lines().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('✓', '✗', '⚠', '⏰', ' ') ?: "")
        val depois = {
            if (estado == Estado.FALANDO) { if (conversa && act?.naFrente == true) ouvir() else parar() }
        }
        Voz.aoTerminar = { fimFala?.let { main.removeCallbacks(it) }; depois() }
        // se a voz do celular não responder, não fica preso: termina sozinho pelo tamanho do texto
        fimFala?.let { main.removeCallbacks(it) }
        val f = Runnable { if (!Voz.falando()) depois() }
        fimFala = f
        main.postDelayed(f, maxOf(5000L, r.length * 90L))
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

    /** para tudo e apaga a luz devagar */
    fun parar() {
        estado = Estado.PARADO
        try { sr?.cancel() } catch (_: Throwable) {}
        Voz.aoTerminar = null
        fimFala?.let { main.removeCallbacks(it) }
        Voz.parar()
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
