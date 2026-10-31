package com.nocry.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/** Regras de quando a intro de abertura toca. */
object Intro {
    /** carimbo da instalacao/atualizacao atual (muda quando o app e instalado ou atualizado) */
    fun carimbo(c: Context): Long = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
    } catch (_: Throwable) { 0L }

    fun deveTocar(c: Context): Boolean = when (Prefs.introModo) {
        "off" -> false
        "novo" -> Prefs.introVisto != carimbo(c)
        else -> true
    }

    fun marcaVista(c: Context) { Prefs.introVisto = carimbo(c) }

    /** a musica que o usuario escolheu (copiada pra dentro do app), se existir */
    fun musica(c: Context): File? =
        Prefs.introMusica?.let { File(c.filesDir, it) }?.takeIf { it.isFile && it.length() > 0 }
}

/**
 * A intro: toca o video HACKER 4 (res/raw) por cima do app, em tela cheia.
 * Toque na tela pula. Se o usuario escolheu uma musica propria, o som do video fica mudo
 * e a musica toca no lugar (se ela falhar, volta o som original).
 */
class IntroView(private val act: Activity, private val aoFim: () -> Unit) : FrameLayout(act) {
    private val h = Handler(Looper.getMainLooper())
    private val tv = TextureView(act)
    private val dica: TextView
    private var superficie: Surface? = null
    private var video: MediaPlayer? = null
    private var musica: MediaPlayer? = null
    private var vw = 0
    private var vh = 0
    private var fim = false
    private var saindo = false
    private var comecou = false
    private var faltam = 0
    private var musicaContada = false
    private var dicaSumiu = false
    private var volMusica = 1f
    private var foco: AudioFocusRequest? = null
    private val som = Prefs.introSom
    private val arqMusica: File? = if (Prefs.introSom) Intro.musica(act) else null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true                                          // engole os toques: nada por baixo reage
        addView(tv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dica = Ui.text(act, "TOQUE PARA PULAR", 10f, 0xFF19FF8C.toInt(), false, mono = true)
        dica.alpha = 0f
        dica.letterSpacing = 0.18f
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Ui.dp(act, 30)
        addView(dica, lp)
        setOnClickListener { pula() }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, hh: Int) { prepara(Surface(st)) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, hh: Int) { ajusta() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { acabaJa(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun atributos(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    // ------------------------------------------------------------------ preparo
    private fun prepara(s: Surface) {
        if (fim) return
        superficie = s
        try {
            pedeFoco()
            val m = MediaPlayer()
            video = m
            m.setAudioAttributes(atributos())
            val afd = resources.openRawResourceFd(R.raw.intro_hacker4)
            try { m.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) } finally { afd.close() }
            m.setSurface(s)
            val v = if (som && arqMusica == null) 1f else 0f
            m.setVolume(v, v)
            m.setOnVideoSizeChangedListener { _, w, hh -> vw = w; vh = hh; ajusta() }
            m.setOnPreparedListener { pronto() }
            m.setOnCompletionListener { sai(320) }
            m.setOnErrorListener { _, w, e ->
                NLog.add("intro", "o video nao tocou ($w/$e)")
                acabaJa()
                true
            }
            faltam = 1
            if (arqMusica != null) { faltam++; preparaMusica(arqMusica) }
            m.prepareAsync()
        } catch (t: Throwable) {
            NLog.add("intro", "nao abriu: $t")
            acabaJa()
        }
    }

    private fun preparaMusica(f: File) {
        try {
            val m = MediaPlayer()
            musica = m
            m.setAudioAttributes(atributos())
            m.setDataSource(f.absolutePath)
            m.setOnPreparedListener { pl ->
                if (fim) return@setOnPreparedListener
                val dur = try { pl.duration } catch (_: Throwable) { 0 }
                val ini = Prefs.introInicio * 1000
                if (ini > 0 && dur > ini + 3000) {
                    pl.setOnSeekCompleteListener { musicaOk() }
                    pl.seekTo(ini)
                } else musicaOk()
            }
            m.setOnErrorListener { _, w, e ->
                NLog.add("intro", "a musica escolhida falhou ($w/$e); usando a original")
                usaOriginal()
                true
            }
            m.prepareAsync()
        } catch (t: Throwable) {
            NLog.add("intro", "musica: $t")
            usaOriginal()
        }
    }

    private fun musicaOk() {
        if (musicaContada) return
        musicaContada = true
        pronto()
    }

    /** a musica do usuario nao funcionou: solta ela e deixa o som original do video */
    private fun usaOriginal() {
        val m = musica
        musica = null
        try { m?.release() } catch (_: Throwable) {}
        try { if (som) video?.setVolume(1f, 1f) } catch (_: Throwable) {}
        musicaOk()
    }

    /** cada player preparado chama aqui; quando todos estao prontos, larga video e musica juntos */
    private fun pronto() {
        if (fim) return
        faltam--
        if (faltam > 0 || comecou) return
        comecou = true
        try {
            video?.start()
        } catch (t: Throwable) {
            NLog.add("intro", "start: $t")
            acabaJa()
            return
        }
        try { musica?.start() } catch (_: Throwable) { usaOriginal() }
        Intro.marcaVista(act)
        dica.animate().alpha(0.5f).setStartDelay(900).setDuration(500).start()
        h.postDelayed(ticker, 50)
    }

    /** a cada 50 ms: baixa a musica do usuario nos ultimos 0,9 s e esconde a dica perto do fim */
    private val ticker = object : Runnable {
        override fun run() {
            if (fim) return
            val v = video
            if (v != null && !saindo) {
                try {
                    val resta = v.duration - v.currentPosition
                    if (resta in 0..900) {
                        volMusica = resta / 900f
                        musica?.setVolume(volMusica, volMusica)
                    }
                    if (resta < 1500 && !dicaSumiu) {
                        dicaSumiu = true
                        dica.animate().cancel()
                        dica.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
                    }
                } catch (_: Throwable) {}
            }
            h.postDelayed(this, 50)
        }
    }

    /** o video cabe inteiro na tela (sem cortar o HUD das bordas); o resto fica preto */
    private fun ajusta() {
        val w = tv.width.toFloat()
        val hh = tv.height.toFloat()
        if (w <= 0f || hh <= 0f || vw <= 0 || vh <= 0) return
        val av = vw.toFloat() / vh.toFloat()
        val ac = w / hh
        var sx = 1f
        var sy = 1f
        if (av > ac) sy = (w / av) / hh else sx = (hh * av) / w
        val m = Matrix()
        m.setScale(sx, sy, w / 2f, hh / 2f)
        tv.setTransform(m)
    }

    // ------------------------------------------------------------------ saida
    fun pula() { sai(260) }

    /** some com suavidade (e baixa o som junto) e entrega o app */
    private fun sai(ms: Long) {
        if (fim || saindo) return
        saindo = true
        dica.animate().cancel()
        dica.alpha = 0f
        val a = ValueAnimator.ofFloat(1f, 0f)
        a.duration = ms
        a.addUpdateListener { x ->
            val f = x.animatedValue as Float
            alpha = f
            try {
                if (musica != null) musica?.setVolume(volMusica * f, volMusica * f)
                else if (som) video?.setVolume(f, f)
            } catch (_: Throwable) {}
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) { acabaJa() }
        })
        a.start()
    }

    /** encerra na hora, sem animacao (app foi pro fundo, fechou, erro) */
    fun acabaJa() {
        if (fim) return
        fim = true
        h.removeCallbacks(ticker)
        for (m in listOf(video, musica)) {
            try { m?.release() } catch (_: Throwable) {}
        }
        video = null
        musica = null
        try { superficie?.release() } catch (_: Throwable) {}
        superficie = null
        soltaFoco()
        (parent as? ViewGroup)?.removeView(this)
        aoFim()
    }

    // ------------------------------------------------------------------ foco de audio (abaixa outras musicas enquanto toca)
    private fun pedeFoco() {
        if (!som) return
        try {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(atributos())
                .build()
            am.requestAudioFocus(r)
            foco = r
        } catch (_: Throwable) {}
    }

    private fun soltaFoco() {
        val r = foco ?: return
        foco = null
        try { (act.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(r) } catch (_: Throwable) {}
    }
}
