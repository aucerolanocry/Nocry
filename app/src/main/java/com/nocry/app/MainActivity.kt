package com.nocry.app

import android.app.Activity
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
    private var ultimo = "\u0000"

    fun criar(): View {
        val sv = android.widget.ScrollView(act)
        root = Ui.vbox(act)
        val p = Ui.dp(act, 16)
        root.setPadding(p, p, p, p * 2)
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ultimo = key()
        render()
        return sv
    }

    abstract fun render()

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
    private val pilha = ArrayList<Screen>()
    private val views = ArrayList<View>()
    private lateinit var barra: TextView
    private lateinit var voltar: TextView
    private lateinit var conteudo: FrameLayout
    private val h = Handler(Looper.getMainLooper())
    private val conectando = AtomicBoolean(false)

    private val ciclo = object : Runnable {
        override fun run() {
            atual()?.tick()
            h.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Ui.BG)

        val topo = LinearLayout(this)
        topo.orientation = LinearLayout.HORIZONTAL
        topo.gravity = Gravity.CENTER_VERTICAL
        topo.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10))
        topo.setBackgroundColor(Ui.BG)

        voltar = Ui.text(this, "‹", 30f, Ui.RED, true)
        voltar.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 4))
        voltar.setOnClickListener { onBackPressed() }
        topo.addView(voltar)

        barra = Ui.text(this, "NOCRY", 16f, Ui.TEXT, true, mono = true)
        barra.letterSpacing = 0.14f
        topo.addView(barra, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(topo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val linha = View(this)
        linha.setBackgroundColor(Ui.LINE)
        raiz.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        conteudo = FrameLayout(this)
        raiz.addView(conteudo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        abre(HomeScreen(this))
    }

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

    private fun mostra() {
        conteudo.removeAllViews()
        val v = views.last()
        conteudo.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val s = pilha.last()
        barra.text = s.titulo
        voltar.visibility = if (pilha.size > 1) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (pilha.size > 1) fecha() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        Engine.onChange = { atual()?.tick() }
        atual()?.aoMostrar()
        h.removeCallbacks(ciclo)
        h.postDelayed(ciclo, 1200)
        // tenta conectar sozinho (depuracao sem fio ja pareada)
        if (!Adb.connected && conectando.compareAndSet(false, true)) {
            Bg.io {
                try { Adb.connect(applicationContext, 5000) } finally { conectando.set(false) }
                Bg.ui { atual()?.tick() }
            }
        }
    }

    override fun onPause() {
        h.removeCallbacks(ciclo)
        Engine.onChange = null
        super.onPause()
    }
}
