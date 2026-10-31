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
import android.graphics.Typeface
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

/** Fundo animado desenhado pelo proprio app (nao usa video nem imagem de ninguem). Segue as cores 1 e 2 do tema. */
class AnimBg(c: Context) : View(c) {
    var preset: String = "brasas"
    private var rodando = true
    private val d = resources.displayMetrics.density
    private val t0 = System.nanoTime()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Pt(val x: Float, val y: Float, val r: Float, val v: Float, val fase: Float)
    private class Col(val v: Float, val fase: Float, val len: Int)
    private val pts = ArrayList<Pt>()
    private val cols = ArrayList<Col>()
    private var ceu: Shader? = null
    private var luz1: Shader? = null
    private var luz2: Shader? = null
    private var sol: Shader? = null
    private var manchas: Array<Shader>? = null
    private var raioMancha = 1f
    private var linhas = FloatArray(0)
    private var banda: Shader? = null
    private var horizonte: Shader? = null
    private var linhaSol: Shader? = null
    private var larg = 0
    private var alt = 0
    private val chars = "01ABCDEF23456789<>/+=*#".map { it.toString() }

    init {
        val rnd = Random(11)
        for (i in 0 until 64) pts.add(Pt(rnd.nextFloat(), rnd.nextFloat(), 1f + rnd.nextFloat() * 2.6f, 0.025f + rnd.nextFloat() * 0.05f, rnd.nextFloat() * 6.28f))
        tp.typeface = Typeface.MONOSPACE
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        larg = w
        alt = h
        monta()
    }

    private fun radial(x: Float, y: Float, r: Float, cor: Int, a: Float): Shader =
        RadialGradient(x, y, maxOf(r, 1f), Theme.alpha(cor, (a * 255).toInt()), Theme.alpha(cor, 0), Shader.TileMode.CLAMP)

    private fun monta() {
        if (larg <= 0 || alt <= 0) return
        val c1 = Theme.accent
        val c2 = Theme.accent2
        val base = Theme.base
        val w = larg.toFloat()
        val h = alt.toFloat()
        val preto = 0xFF000000.toInt()
        ceu = when (preset) {
            "brasas" -> LinearGradient(0f, 0f, 0f, h, intArrayOf(Theme.mix(base, preto, 0.4f), base, Theme.mix(base, c1, 0.30f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            "grade" -> LinearGradient(0f, 0f, 0f, h, Theme.mix(base, preto, 0.35f), Theme.mix(base, c1, 0.16f), Shader.TileMode.CLAMP)
            "sol" -> LinearGradient(0f, 0f, 0f, h * 0.58f, intArrayOf(Theme.mix(base, preto, 0.2f), Theme.mix(base, c1, 0.22f), Theme.mix(base, c1, 0.72f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            "petalas" -> LinearGradient(0f, 0f, 0f, h, Theme.mix(base, c2, 0.14f), Theme.mix(base, c1, 0.22f), Shader.TileMode.CLAMP)
            "neve" -> LinearGradient(0f, 0f, 0f, h, Theme.mix(base, c2, 0.10f), base, Shader.TileMode.CLAMP)
            "noite" -> LinearGradient(0f, 0f, 0f, h, Theme.mix(base, preto, 0.5f), base, Shader.TileMode.CLAMP)
            else -> null
        }
        raioMancha = max(w, h) * 0.6f
        val cores = intArrayOf(c1, c2, Theme.mix(c1, c2, 0.5f))
        manchas = Array(3) { i -> radial(0f, 0f, raioMancha, cores[i], 0.45f) }
        luz1 = when (preset) {
            "brasas" -> radial(w / 2f, h * 1.08f, h * 0.85f, c1, 0.55f)
            "grade" -> radial(w / 2f, 0f, h * 0.6f, c1, 0.22f)
            "sol" -> radial(w / 2f, h * 0.40f, w * 0.85f, c1, 0.35f)
            "chuva" -> radial(w / 2f, h * 1.05f, h * 0.7f, c2, 0.30f)
            "neve" -> radial(w / 2f, -h * 0.02f, h * 0.7f, c2, 0.34f)
            "petalas" -> radial(w / 2f, h * 1.05f, h * 0.75f, c1, 0.40f)
            "ouro" -> radial(w * 0.9f, 0f, h * 0.65f, c1, 0.26f)
            else -> radial(w * 0.8f, h * 0.08f, max(w, h) * 0.7f, c1, 0.33f)
        }
        luz2 = when (preset) {
            "brasas" -> radial(0f, 0f, w * 0.9f, c1, 0.20f)
            "grade" -> radial(w, h * 0.6f, w * 0.9f, c2, 0.20f)
            "neve" -> radial(0f, h * 0.8f, w * 0.7f, c1, 0.10f)
            "petalas" -> radial(w * 0.9f, h * 0.05f, w * 0.9f, c2, 0.30f)
            "ouro" -> radial(0f, h, w, c2, 0.20f)
            else -> null
        }
        val rs = w * 0.30f
        sol = LinearGradient(0f, h * 0.40f - rs, 0f, h * 0.40f + rs, c2, c1, Shader.TileMode.CLAMP)

        val rnd = Random(5)
        cols.clear()
        val n = (w / (15f * d)).toInt().coerceAtLeast(4)
        for (i in 0 until n) cols.add(Col(0.07f + rnd.nextFloat() * 0.14f, rnd.nextFloat(), 8 + rnd.nextInt(12)))

        // linhas de varredura (scanner)
        val passo = 3f * d
        val k = (h / passo).toInt() + 1
        linhas = FloatArray(k * 4)
        for (i in 0 until k) { linhas[i * 4] = 0f; linhas[i * 4 + 1] = i * passo; linhas[i * 4 + 2] = w; linhas[i * 4 + 3] = i * passo }
        horizonte = LinearGradient(0f, 0f, w, 0f, intArrayOf(Theme.alpha(c1, 0), c1, c2, Theme.alpha(c2, 0)), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        val ys = h * 0.58f
        linhaSol = LinearGradient(0f, ys - 2f * d, 0f, ys + 3f * d, Theme.alpha(c2, 0xC0), Theme.alpha(c1, 0), Shader.TileMode.CLAMP)
        banda = LinearGradient(0f, 0f, 0f, 120f * d, intArrayOf(Theme.alpha(c1, 0), Theme.alpha(c1, 0x30), Theme.alpha(c1, 0)), null, Shader.TileMode.CLAMP)
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

        p.shader = null
        p.color = Theme.base
        cv.drawRect(0f, 0f, w, h, p)
        val f = ceu
        if (f != null) {
            p.shader = f
            cv.drawRect(0f, 0f, w, if (preset == "sol") h * 0.58f else h, p)
            p.shader = null
        }
        val l1 = luz1
        if (l1 != null && preset != "aurora") { p.shader = l1; cv.drawRect(0f, 0f, w, h, p); p.shader = null }
        val l2 = luz2
        if (l2 != null) { p.shader = l2; cv.drawRect(0f, 0f, w, h, p); p.shader = null }

        val c1 = Theme.accent
        val c2 = Theme.accent2
        when (preset) {
            "aurora" -> desenhaAurora(cv, w, h, t, c1)
            "grade" -> desenhaGrade(cv, w, h, t, c1, c2)
            "sol" -> desenhaSol(cv, w, h, t, c1, c2)
            "chuva" -> desenhaChuva(cv, w, h, t, c1)
            "neve" -> desenhaNeve(cv, w, h, t)
            "petalas" -> desenhaPetalas(cv, w, h, t, c1, c2)
            "ouro" -> desenhaOuro(cv, w, h, t, c1)
            "noite" -> desenhaEstrelas(cv, w, h, t, 0xFFFFFFFF.toInt(), 170)
            else -> desenhaBrasas(cv, w, h, t, c1, c2)
        }
        if (Prefs.scanner) desenhaScanner(cv, w, h, t)
        if (animar && rodando) postInvalidateOnAnimation()
    }

    private fun desenhaBrasas(cv: Canvas, w: Float, h: Float, t: Float, c1: Int, c2: Int) {
        if (!Prefs.particulas) return
        val quente = Theme.mix(c2, 0xFFFFFFFF.toInt(), 0.35f)
        for (i in 0 until 48) {
            val q = pts[i]
            val prog = (t * q.v + q.y) % 1f                    // 0 = embaixo, 1 = topo
            val y = h * (1f - prog)
            val x = q.x * w + sin(t * 0.7f + q.fase) * 16f * d
            val a = (1f - prog) * 0.9f * (0.55f + 0.45f * abs(sin(t * 1.3f + q.fase)))
            p.color = Theme.alpha(c1, (a * 50f).toInt())
            cv.drawCircle(x, y, q.r * 3.4f * d, p)
            p.color = Theme.alpha(quente, (a * 235f).toInt())
            cv.drawCircle(x, y, q.r * d * 0.8f, p)
        }
    }

    private fun desenhaAurora(cv: Canvas, w: Float, h: Float, t: Float, c1: Int) {
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
        desenhaEstrelas(cv, w, h, t, 0xFFFFFFFF.toInt(), 120)
    }

    /** piso em perspectiva com linhas que correm na direcao de quem olha */
    private fun piso(cv: Canvas, w: Float, h: Float, y0: Float, t: Float, cor: Int, forca: Float) {
        val altp = h - y0
        p.strokeWidth = 1.2f * d
        val n = 9
        for (i in -n..n) {
            val a = 1f - abs(i) / (n + 1f)
            p.color = Theme.alpha(cor, ((0.12f + 0.55f * a) * forca * 255f).toInt())
            cv.drawLine(w / 2f + i * w * 0.035f, y0, w / 2f + i * w * 0.24f, h, p)
        }
        val k = 9
        val sc = if (Prefs.anim) (t * 0.22f) % 1f else 0f
        for (j in 0 until k) {
            val f = (j + sc) / k
            val y = y0 + altp * f * f
            p.color = Theme.alpha(cor, ((0.10f + 0.6f * f) * forca * 255f).toInt())
            cv.drawLine(0f, y, w, y, p)
        }
    }

    private fun desenhaGrade(cv: Canvas, w: Float, h: Float, t: Float, c1: Int, c2: Int) {
        val y0 = h * 0.5f
        piso(cv, w, h, y0, t, c1, 1f)
        // linha do horizonte com brilho
        p.shader = horizonte
        cv.drawRect(0f, y0 - 1f * d, w, y0 + 1f * d, p)
        p.alpha = 70
        cv.drawRect(0f, y0 - 5f * d, w, y0 + 5f * d, p)
        p.shader = null
        if (Prefs.particulas) desenhaEstrelas(cv, w, y0, t, c1, 110)
    }

    private fun desenhaSol(cv: Canvas, w: Float, h: Float, t: Float, c1: Int, c2: Int) {
        val y0 = h * 0.58f
        if (Prefs.particulas) desenhaEstrelas(cv, w, y0 * 0.8f, t, 0xFFFFFFFF.toInt(), 190)
        val cx = w / 2f
        val cy = h * 0.40f
        val r = w * 0.30f
        cv.save()
        val circ = android.graphics.Path()
        circ.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        cv.clipPath(circ)
        p.shader = sol
        cv.drawRect(cx - r, cy - r, cx + r, cy + r, p)
        // listras que "apagam" a parte de baixo do sol, mostrando o ceu atras
        p.shader = ceu
        for (i in 0 until 7) {
            val y = cy + r * 0.08f + i * r * 0.15f
            cv.drawRect(cx - r, y, cx + r, y + (1.5f + i * 1.3f) * d, p)
        }
        p.shader = null
        cv.restore()
        // chao
        p.color = Theme.base
        cv.drawRect(0f, y0, w, h, p)
        piso(cv, w, h, y0, t, c1, 1.2f)
        p.shader = linhaSol
        cv.drawRect(0f, y0 - 2f * d, w, y0 + 3f * d, p)
        p.shader = null
    }

    private fun desenhaChuva(cv: Canvas, w: Float, h: Float, t: Float, c1: Int) {
        if (!Prefs.particulas) return
        tp.textSize = 13f * d
        val celula = 15f * d
        val cabeca = Theme.mix(c1, 0xFFFFFFFF.toInt(), 0.65f)
        val sh = (t * 6f).toInt()
        for ((i, col) in cols.withIndex()) {
            val x = i * celula + 3f * d
            val prog = (t * col.v + col.fase) % 1f
            val yCab = prog * (h + col.len * celula)
            for (j in 0 until col.len) {
                val y = yCab - j * celula
                if (y < -celula || y > h + celula) continue
                val fade = 1f - j / col.len.toFloat()
                val idx = Math.floorMod(i * 31 + (y / celula).toInt() * 17 + (if (j == 0) sh else 0), chars.size)
                tp.color = if (j == 0) Theme.alpha(cabeca, 235) else Theme.alpha(c1, (fade * 0.8f * 255f).toInt())
                cv.drawText(chars[idx], x, y, tp)
            }
        }
    }

    private fun desenhaNeve(cv: Canvas, w: Float, h: Float, t: Float) {
        if (!Prefs.particulas) return
        for (i in 0 until 56) {
            val q = pts[i]
            val prog = (t * (q.v * 1.6f) + q.y) % 1f
            val y = h * prog
            val x = q.x * w + sin(t * 0.6f + q.fase) * 14f * d
            val a = 0.35f + 0.55f * (q.r / 3.6f)
            p.color = Theme.alpha(0xFFFFFFFF.toInt(), (a * 235f).toInt())
            cv.drawCircle(x, y, (q.r * 0.55f + 0.6f) * d, p)
        }
    }

    private fun desenhaPetalas(cv: Canvas, w: Float, h: Float, t: Float, c1: Int, c2: Int) {
        if (!Prefs.particulas) return
        val cor = Theme.mix(c1, 0xFFFFFFFF.toInt(), 0.30f)
        for (i in 0 until 22) {
            val q = pts[i]
            val prog = (t * (q.v * 0.9f) + q.y) % 1f
            val y = h * prog
            val x = q.x * w + sin(t * 0.5f + q.fase) * 26f * d + prog * 40f * d
            val s = (4f + q.r * 2.2f) * d
            cv.save()
            cv.translate(x, y)
            cv.rotate(t * 38f * q.v * 10f + q.fase * 57f)
            p.color = Theme.alpha(if (i % 3 == 0) c2 else cor, 215)
            cv.drawOval(-s, -s * 0.55f, s, s * 0.55f, p)
            cv.restore()
        }
    }

    private fun desenhaOuro(cv: Canvas, w: Float, h: Float, t: Float, c1: Int) {
        // trama diagonal fina
        val passo = 11f * d
        p.strokeWidth = d
        p.color = Theme.alpha(c1, 0x0C)
        var x = -h
        while (x < w) { cv.drawLine(x, h, x + h, 0f, p); x += passo }
        if (!Prefs.particulas) return
        for (i in 0 until 40) {
            val q = pts[i]
            val prog = (t * q.v * 0.5f + q.y) % 1f
            val y = h * (1f - prog)
            val x2 = q.x * w + sin(t * 0.4f + q.fase) * 10f * d
            val a = (1f - prog) * (0.5f + 0.5f * abs(sin(t * 1.1f + q.fase)))
            p.color = Theme.alpha(c1, (a * 60f).toInt())
            cv.drawCircle(x2, y, q.r * 2.6f * d, p)
            p.color = Theme.alpha(Theme.mix(c1, 0xFFFFFFFF.toInt(), 0.4f), (a * 235f).toInt())
            cv.drawCircle(x2, y, q.r * 0.7f * d, p)
        }
    }

    private fun desenhaEstrelas(cv: Canvas, w: Float, h: Float, t: Float, cor: Int, forca: Int) {
        if (!Prefs.particulas) return
        for (q in pts) {
            val a = 0.25f + 0.75f * abs(sin(t * 0.5f + q.fase))
            p.color = Theme.alpha(cor, (a * forca).toInt())
            cv.drawCircle(q.x * w, q.y * h, (q.r * 0.38f + 0.3f) * d, p)
        }
    }

    private fun desenhaScanner(cv: Canvas, w: Float, h: Float, t: Float) {
        p.color = Theme.alpha(0xFF000000.toInt(), 0x30)
        p.strokeWidth = d
        cv.drawLines(linhas, p)
        val b = banda ?: return
        val y = if (Prefs.anim) ((t * 0.12f) % 1.3f - 0.15f) * h else h * 0.3f
        cv.save()
        cv.translate(0f, y)
        p.shader = b
        cv.drawRect(0f, 0f, w, 120f * d, p)
        p.shader = null
        cv.restore()
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
