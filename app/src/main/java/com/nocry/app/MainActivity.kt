package com.nocry.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** Base de toda tela: monta uma coluna rolavel e redesenha so quando o estado muda. */
abstract class Screen(val act: MainActivity) {
    abstract val titulo: String
    lateinit var root: LinearLayout
    private var rolagem: android.widget.ScrollView? = null
    private var ultimo = "\u0000"

    /** a tela inicial desenha o proprio cabecalho */
    open val semBarra: Boolean = false

    /** numero da aba da barra de baixo quando esta tela e a raiz de uma aba (-1 = nenhuma) */
    open val aba: Int = -1

    fun criar(): View {
        val sv = android.widget.ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        rolagem = sv
        root = Ui.vbox(act)
        val p = Ui.dp(act, 16)
        root.setPadding(p, if (semBarra) Ui.dp(act, 14) else Ui.dp(act, 6), p, Ui.dp(act, 28))
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ultimo = key()
        render()
        return sv
    }

    abstract fun render()

    /** onde a tela estava rolada (pra voltar no mesmo ponto quando o visual muda) */
    fun rolagemY(): Int = rolagem?.scrollY ?: 0

    fun restauraY(y: Int) {
        val s = rolagem ?: return
        if (y <= 0) return
        s.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                if (s.height > 0 && (s.getChildAt(0)?.height ?: 0) > 0) {
                    s.removeOnLayoutChangeListener(this)
                    s.scrollTo(0, y)
                }
            }
        })
    }

    /** assinatura do estado: se mudar, a tela e redesenhada */
    open fun key(): String = ""

    fun tick() {
        val k = key()
        if (k != ultimo) { ultimo = k; render() }
    }

    fun redesenha() { ultimo = key(); render() }

    /** chamado ao voltar pra esta tela */
    open fun aoMostrar() { redesenha() }

    protected fun add(v: View, top: Int = 10) { root.addView(v, Ui.lp(top = top, c = act)) }
    protected fun limpa() { root.removeAllViews() }
}

class MainActivity : Activity() {
    private class Aba(val nome: String, val icone: Int)

    private val abas = listOf(
        Aba("Início", R.drawable.ic_home),
        Aba("Conexão", R.drawable.ic_wifi),
        Aba("Estúdio", R.drawable.ic_brush),
        Aba("Logs", R.drawable.ic_log),
        Aba("Sobre", R.drawable.ic_info)
    )

    private val pilha = ArrayList<Screen>()
    private val views = ArrayList<View>()
    private lateinit var raiz: FrameLayout
    private lateinit var bg: BgView
    private lateinit var coluna: LinearLayout
    private lateinit var topo: View
    private lateinit var barra: TextView
    private lateinit var voltar: View
    private lateinit var marcaTopo: View
    private lateinit var conteudo: FrameLayout
    private lateinit var nav: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val conectando = AtomicBoolean(false)
    private var gate = false
    private var intro: IntroView? = null

    /** quem pediu uma tela do sistema (escolher video/imagem) recebe a resposta aqui */
    var aoResultado: ((Int, Int, Intent?) -> Unit)? = null

    private val ciclo = object : Runnable {
        override fun run() {
            if (!gate && !License.valid()) mostraGate() else atual()?.tick()
            h.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        mostraBarras()

        raiz = FrameLayout(this)
        raiz.setBackgroundColor(Theme.base)
        bg = BgView(this)
        raiz.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        coluna = LinearLayout(this)
        coluna.orientation = LinearLayout.VERTICAL
        coluna.fitsSystemWindows = true
        raiz.addView(coluna, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)

        License.saiu = { mostraGate() }
        montaChrome()
        if (License.valid()) abre(HomeScreen(this)) else mostraGate()

        // intro de abertura (so quando o app abre do zero, nunca ao recriar a tela)
        if (savedInstanceState == null && Intro.deveTocar(this)) tocaIntro()
    }

    // ------------------------------------------------------------------ intro
    /** toca a intro por cima do app, em tela cheia (toque pula). Tambem usada pelo botao "assistir agora". */
    fun tocaIntro() {
        if (intro != null) return
        val v = IntroView(this) {
            intro = null
            mostraBarras()
        }
        intro = v
        escondeBarras()
        raiz.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @Suppress("DEPRECATION")
    private val uiBase = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    @Suppress("DEPRECATION")
    private fun mostraBarras() { window.decorView.systemUiVisibility = uiBase }

    @Suppress("DEPRECATION")
    private fun escondeBarras() {
        window.decorView.systemUiVisibility = uiBase or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ------------------------------------------------------------------ moldura (barra de cima e de baixo)
    private fun montaChrome() {
        coluna.removeAllViews()
        topo = criaTopo()
        coluna.addView(topo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        conteudo = FrameLayout(this)
        coluna.addView(conteudo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = LinearLayout(this)
        nav.orientation = LinearLayout.VERTICAL
        coluna.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun criaTopo(): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.HORIZONTAL
        t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 4))

        val v = FrameLayout(this)
        v.background = Ui.ripple(this, Botao(this, "ico"), Botao(this, "ico", mascara = true))
        v.addView(Ui.icone(this, R.drawable.ic_back, Theme.mix(Ui.TEXT, Theme.accent, 0.35f), 20), FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER))
        v.contentDescription = "Voltar"
        Ui.clique(v) { onBackPressed() }
        voltar = v
        val vp = LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40))
        vp.rightMargin = Ui.dp(this, 12)
        t.addView(v, vp)

        // barrinha em degrade antes do titulo (aparece nas abas, onde nao tem botao de voltar)
        val marca = View(this)
        marca.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Theme.accent, Theme.accent2)).also { it.cornerRadius = Ui.dp(this, 2).toFloat() }
        marcaTopo = marca
        val mp = LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 22))
        mp.rightMargin = Ui.dp(this, 10)
        mp.leftMargin = Ui.dp(this, 4)
        t.addView(marca, mp)

        barra = Ui.text(this, "NOCRY", 22f, Ui.TEXT, true)
        barra.maxLines = 1
        barra.ellipsize = android.text.TextUtils.TruncateAt.END
        t.addView(barra, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return t
    }

    /** barra de baixo: pilula de vidro flutuante; a aba ativa vira um botao em degrade com o nome */
    private fun preencheNav() {
        nav.removeAllViews()
        if (gate) return
        val ativa = pilha.firstOrNull()?.aba ?: 0
        val m = Ui.dp(this, 14)
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = Painel(this, "vidro", true, false, 28)
        r.elevation = Ui.dp(this, 10).toFloat()
        r.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7))
        for ((i, a) in abas.withIndex()) {
            val on = i == ativa
            val item = LinearLayout(this)
            item.orientation = LinearLayout.HORIZONTAL
            item.gravity = Gravity.CENTER
            item.contentDescription = a.nome
            if (on) item.background = Botao(this, "grad", 22)
            val cor = if (on) Theme.sobreGrad() else Ui.MUTED
            item.addView(Ui.icone(this, a.icone, cor, 21))
            if (on) {
                val tx = Ui.text(this, a.nome, 11.5f, cor, true, mono = true)
                tx.maxLines = 1
                val tp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tp.leftMargin = Ui.dp(this, 6)
                item.addView(tx, tp)
            }
            Ui.clique(item) { irAba(i) }
            r.addView(item, LinearLayout.LayoutParams(0, Ui.dp(this, 46), if (on) 2.3f else 1f))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(m, Ui.dp(this, 4), m, Ui.dp(this, 10))
        nav.addView(r, lp)
    }

    /** troca de aba: volta pra raiz da aba escolhida */
    fun irAba(i: Int) {
        if (gate) return
        if (pilha.size == 1 && pilha[0].aba == i) return
        pilha.clear()
        views.clear()
        abre(when (i) {
            1 -> SetupScreen(this)
            2 -> EstudioScreen(this)
            3 -> LogScreen(this, null)
            4 -> SobreScreen(this)
            else -> HomeScreen(this)
        })
    }

    // ------------------------------------------------------------------ Key
    private fun mostraGate() {
        if (gate) return
        gate = true
        topo.visibility = View.GONE
        nav.removeAllViews()
        conteudo.removeAllViews()
        val g = KeyGate(this)
        conteudo.addView(g.criar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** a key valeu: abre o app */
    fun entrou() {
        if (!gate) return
        gate = false
        pilha.clear()
        views.clear()
        abre(HomeScreen(this))
    }

    // ------------------------------------------------------------------ telas
    private fun atual(): Screen? = pilha.lastOrNull()

    /** abre uma tela por cima da atual */
    fun abre(s: Screen) {
        val v = s.criar()
        pilha.add(s)
        views.add(v)
        mostra()
    }

    fun fecha() {
        if (pilha.size <= 1) return
        pilha.removeAt(pilha.size - 1)
        views.removeAt(views.size - 1)
        mostra()
        atual()?.aoMostrar()
    }

    private fun mostra(anima: Boolean = true) {
        conteudo.removeAllViews()
        val v = views.last()
        conteudo.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (anima && Prefs.anim) {
            v.alpha = 0f
            v.translationY = Ui.dp(this, 12).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        val s = pilha.last()
        barra.text = s.titulo
        voltar.visibility = if (pilha.size > 1) View.VISIBLE else View.GONE
        marcaTopo.visibility = if (pilha.size > 1) View.GONE else View.VISIBLE
        topo.visibility = if (s.semBarra && pilha.size == 1) View.GONE else View.VISIBLE
        preencheNav()
    }

    /** o visual mudou (cor, fonte, fundo...): refaz moldura e todas as telas abertas, mantendo onde o usuario esta */
    fun reaplica() {
        raiz.setBackgroundColor(Theme.base)
        bg.aplica()
        val ys = pilha.map { it.rolagemY() }
        montaChrome()
        if (gate) {
            gate = false
            mostraGate()
            return
        }
        for (i in pilha.indices) views[i] = pilha[i].criar()
        if (pilha.isNotEmpty()) mostra(false)
        for (i in pilha.indices) pilha[i].restauraY(ys.getOrElse(i) { 0 })
    }

    /** so o fundo mudou (escurecer/desfocar ao arrastar) */
    fun refazFundo() { bg.aplica() }

    /** escurecer / desfocar ao vivo */
    fun ajustaFundo() { bg.ajustaEfeitos() }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            intro != null -> intro?.pula()
            gate -> super.onBackPressed()
            pilha.size > 1 -> fecha()
            (pilha.firstOrNull()?.aba ?: 0) != 0 -> irAba(0)
            else -> super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        aoResultado?.invoke(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        bg.retoma()
        if (!gate && !License.valid()) mostraGate()
        Engine.onChange = { if (!gate) atual()?.tick() }
        if (!gate) atual()?.aoMostrar()
        h.removeCallbacks(ciclo)
        h.postDelayed(ciclo, 1200)
        if (!gate) {
            Bg.io { License.tick(applicationContext) }
            // tenta conectar sozinho (depuracao sem fio ja pareada)
            if (!Adb.connected && conectando.compareAndSet(false, true)) {
                Bg.io {
                    try { Adb.connect(applicationContext, 5000) } finally { conectando.set(false) }
                    Bg.ui { atual()?.tick() }
                }
            }
        }
    }

    override fun onPause() {
        intro?.acabaJa()
        h.removeCallbacks(ciclo)
        Engine.onChange = null
        bg.pausa()
        super.onPause()
    }

    override fun onDestroy() {
        intro?.acabaJa()
        License.saiu = null
        bg.encerra()
        super.onDestroy()
    }
}
