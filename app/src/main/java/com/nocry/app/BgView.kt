package com.nocry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.ExifInterface
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Fundo animado desenhado pelo proprio app (nao usa video nem imagem de ninguem). Segue a cor de destaque. */
class AnimBg(c: Context) : View(c) {
    var preset: String = "brasas"
    private var rodando = true
    private val d = resources.displayMetrics.density
    private val t0 = System.nanoTime()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Pt(val x: Float, val y: Float, val r: Float, val v: Float, val fase: Float)
    private val pts = ArrayList<Pt>()
    private var fundo: Shader? = null
    private var manchas: Array<Shader>? = null
    private var brilho: Shader? = null
    private var raioMancha = 1f
    private var larg = 0
    private var alt = 0

    init {
        val rnd = Random(11)
        for (i in 0 until 64) pts.add(Pt(rnd.nextFloat(), rnd.nextFloat(), 1f + rnd.nextFloat() * 2.6f, 0.025f + rnd.nextFloat() * 0.05f, rnd.nextFloat() * 6.28f))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        larg = w
        alt = h
        monta()
    }

    private fun monta() {
        if (larg <= 0 || alt <= 0) return
        val ac = Theme.accent
        val base = Theme.base
        val w = larg.toFloat()
        val h = alt.toFloat()
        fundo = when (preset) {
            "brasas" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(Theme.mix(base, 0xFF000000.toInt(), 0.5f), base, Theme.mix(base, ac, 0.38f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            "grade" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(Theme.mix(base, 0xFF000000.toInt(), 0.4f), Theme.mix(base, ac, 0.20f)), null, Shader.TileMode.CLAMP)
            "noite" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(Theme.mix(base, 0xFF000000.toInt(), 0.6f), base), null, Shader.TileMode.CLAMP)
            else -> null
        }
        raioMancha = max(w, h) * 0.6f
        val cores = intArrayOf(ac, Theme.girar(ac, 55f), Theme.girar(ac, -60f))
        manchas = Array(3) { i -> RadialGradient(0f, 0f, raioMancha, Theme.alpha(cores[i], 0x70), Theme.alpha(cores[i], 0), Shader.TileMode.CLAMP) }
        brilho = RadialGradient(w * 0.8f, h * 0.08f, max(w, h) * 0.7f, Theme.alpha(ac, 0x55), Theme.alpha(ac, 0), Shader.TileMode.CLAMP)
    }

    fun recolore() { monta(); invalidate() }
    fun pausa() { rodando = false }
    fun retoma() { rodando = true; invalidate() }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val animar = Prefs.anim
        val t = if (animar) (System.nanoTime() - t0) / 1_000_000_000f else 4f
        val ac = Theme.accent

        p.shader = null
        p.color = Theme.base
        cv.drawRect(0f, 0f, w, h, p)
        val f = fundo
        if (f != null) { p.shader = f; cv.drawRect(0f, 0f, w, h, p); p.shader = null }

        when (preset) {
            "aurora" -> desenhaAurora(cv, w, h, t)
            "grade" -> desenhaGrade(cv, w, h, t, ac)
            "noite" -> desenhaNoite(cv, w, h, t, ac)
            else -> desenhaBrasas(cv, w, h, t, ac)
        }
        if (animar && rodando) postInvalidateOnAnimation()
    }

    private fun desenhaBrasas(cv: Canvas, w: Float, h: Float, t: Float, ac: Int) {
        val quente = Theme.mix(ac, 0xFFFFFFFF.toInt(), 0.35f)
        for (q in pts) {
            val prog = (t * q.v + q.y) % 1f                    // 0 = embaixo, 1 = topo
            val y = h * (1f - prog)
            val x = q.x * w + sin(t * 0.7f + q.fase) * 16f * d
            val vida = (1f - prog)
            val a = (vida * 0.9f * (0.55f + 0.45f * abs(sin(t * 1.3f + q.fase))))
            p.color = Theme.alpha(ac, (a * 46f).toInt())
            cv.drawCircle(x, y, q.r * 3.4f * d, p)
            p.color = Theme.alpha(quente, (a * 230f).toInt())
            cv.drawCircle(x, y, q.r * d * 0.8f, p)
        }
    }

    private fun desenhaAurora(cv: Canvas, w: Float, h: Float, t: Float) {
        val m = manchas ?: return
        for (i in 0 until 3) {
            val cx = w * (0.5f + 0.38f * sin(t * (0.11f + i * 0.037f) + i * 2.1f))
            val cy = h * (0.30f + 0.28f * sin(t * (0.083f + i * 0.051f) + i * 1.3f) + i * 0.18f)
            cv.save()
            cv.translate(cx, cy)
            p.shader = m[i]
            cv.drawCircle(0f, 0f, raioMancha, p)
            cv.restore()
        }
        p.shader = null
    }

    private fun desenhaGrade(cv: Canvas, w: Float, h: Float, t: Float, ac: Int) {
        val br = brilho
        if (br != null) { p.shader = br; cv.drawRect(0f, 0f, w, h, p); p.shader = null }
        val passo = 56f * d
        p.strokeWidth = 1f * d
        // verticais paradas
        var x = (w / 2f) % passo
        while (x < w) {
            p.color = Theme.alpha(ac, 0x1C)
            cv.drawLine(x, 0f, x, h, p)
            x += passo
        }
        // horizontais descendo devagar; ficam mais fortes perto de baixo
        val desloc = (t * 14f * d) % passo
        var y = desloc - passo
        while (y < h) {
            val k = (y / h).coerceIn(0f, 1f)
            p.color = Theme.alpha(ac, (14 + 52 * k).toInt())
            cv.drawLine(0f, y, w, y, p)
            y += passo
        }
    }

    private fun desenhaNoite(cv: Canvas, w: Float, h: Float, t: Float, ac: Int) {
        val br = brilho
        if (br != null) { p.shader = br; cv.drawRect(0f, 0f, w, h, p); p.shader = null }
        for (q in pts) {
            val a = 0.25f + 0.75f * abs(sin(t * 0.5f + q.fase))
            p.color = Theme.alpha(0xFFFFFFFF.toInt(), (a * 170f).toInt())
            cv.drawCircle(q.x * w, q.y * h, (q.r * 0.38f + 0.3f) * d, p)
        }
    }
}

/** Camada de fundo: video da galeria, imagem, desenho animado ou cor lisa, com escurecimento e desfoque. */
class BgView(c: Context) : FrameLayout(c) {
    private val conteudo = FrameLayout(c)
    private val escuro = View(c)
    private var anim: AnimBg? = null
    private var tv: TextureView? = null
    private var mp: MediaPlayer? = null
    private var vw = 0
    private var vh = 0
    private var pausado = false

    init {
        val mm = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(conteudo, mm)
        addView(escuro, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        aplica()
    }

    /** le as preferencias e remonta tudo */
    fun aplica() {
        libera()
        conteudo.removeAllViews()
        setBackgroundColor(Theme.base)
        val arq = Prefs.bgArquivo?.let { File(context.filesDir, it) }?.takeIf { it.isFile }
        when {
            Prefs.bgTipo == "video" && arq != null -> video(arq)
            Prefs.bgTipo == "imagem" && arq != null -> imagem(arq)
            Prefs.bgTipo == "solido" -> {}
            else -> animado()
        }
        ajustaEfeitos()
    }

    /** so escurecimento e desfoque (pode ser chamado enquanto o usuario arrasta a barra, sem reiniciar o video) */
    fun ajustaEfeitos() {
        escuro.setBackgroundColor(Theme.alpha(0xFF000000.toInt(), Prefs.bgEscuro * 255 / 100))
        desfoque()
    }

    private fun desfoque() {
        if (Build.VERSION.SDK_INT >= 31) {
            val r = Prefs.bgBlur * resources.displayMetrics.density
            conteudo.setRenderEffect(if (r > 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
    }

    private fun animado() {
        val a = AnimBg(context)
        a.preset = Prefs.bgPreset
        anim = a
        conteudo.addView(a, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (pausado) a.pausa()
    }

    private fun imagem(arq: File) {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        conteudo.addView(iv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dm = resources.displayMetrics
        Bg.run({ decodifica(arq, dm.widthPixels, dm.heightPixels) }) { bmp ->
            if (bmp != null) iv.setImageBitmap(bmp)
            else { conteudo.removeAllViews(); animado() }
        }
    }

    private fun decodifica(arq: File, w: Int, h: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(arq.absolutePath, o)
            var s = 1
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val b = BitmapFactory.decodeFile(arq.absolutePath, o2) ?: return null
            val rot = try {
                when (ExifInterface(arq.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Throwable) { 0f }
            if (rot == 0f) b else {
                val m = Matrix()
                m.postRotate(rot)
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            }
        } catch (_: Throwable) { null }
    }

    private fun video(arq: File) {
        val t = TextureView(context)
        tv = t
        t.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { prepara(arq, Surface(st)) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { ajusta() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { paraVideo(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        conteudo.addView(t, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun prepara(arq: File, s: Surface) {
        try {
            val m = MediaPlayer()
            mp = m
            m.setDataSource(arq.absolutePath)
            m.setSurface(s)
            m.isLooping = true
            m.setVolume(0f, 0f)                                   // fundo sempre mudo
            m.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; ajusta() }
            m.setOnPreparedListener { pl -> if (!pausado) pl.start() }
            m.setOnErrorListener { _, _, _ ->
                NLog.add("visual", "o video de fundo nao abriu; usando o fundo animado")
                Bg.ui { paraVideo(); conteudo.removeAllViews(); animado() }
                true
            }
            m.prepareAsync()
        } catch (t: Throwable) {
            NLog.add("visual", "video de fundo: $t")
            Bg.ui { paraVideo(); conteudo.removeAllViews(); animado() }
        }
    }

    /** preenche a tela cortando o excesso (como "cobrir"), sem esticar o video */
    private fun ajusta() {
        val t = tv ?: return
        val w = t.width.toFloat()
        val h = t.height.toFloat()
        if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
        val av = vw.toFloat() / vh.toFloat()
        val ac = w / h
        var sx = 1f
        var sy = 1f
        if (av > ac) sx = (h * av) / w else sy = (w / av) / h
        val m = Matrix()
        m.setScale(sx, sy, w / 2f, h / 2f)
        t.setTransform(m)
    }

    private fun paraVideo() {
        val m = mp
        mp = null
        try { m?.release() } catch (_: Throwable) {}
    }

    private fun libera() {
        paraVideo()
        tv = null
        anim?.pausa()
        anim = null
    }

    fun recolore() { anim?.recolore() }

    fun pausa() {
        pausado = true
        anim?.pausa()
        try { if (mp?.isPlaying == true) mp?.pause() } catch (_: Throwable) {}
    }

    fun retoma() {
        pausado = false
        anim?.retoma()
        try { mp?.start() } catch (_: Throwable) {}
    }

    fun encerra() { libera() }
}
