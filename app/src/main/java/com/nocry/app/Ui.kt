package com.nocry.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pecas visuais do NOCRY: tudo escuro, vermelho de destaque, letras de terminal. */
object Ui {
    val BG = 0xFF050505.toInt()
    val CARD = 0xFF0F0F12.toInt()
    val CARD2 = 0xFF17171C.toInt()
    val LINE = 0xFF2A2A33.toInt()
    val RED = 0xFFE8283A.toInt()
    val RED_DARK = 0xFF3A0E14.toInt()
    val TEXT = 0xFFF2F2F4.toInt()
    val MUTED = 0xFF8E8E98.toInt()
    val OK = 0xFF44FF88.toInt()
    val WARN = 0xFFF2B84B.toInt()

    enum class Kind { PRIMARY, DANGER, GHOST }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor)
        return g
    }

    private fun ripple(bg: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

    fun text(c: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.setLineSpacing(0f, 1.15f)
        if (mono) t.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        else if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, c: Context? = null): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        if (c != null && top > 0) p.topMargin = dp(c, top)
        return p
    }

    fun vbox(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun hbox(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    /** caixa arredondada onde ficam os itens */
    fun card(c: Context): LinearLayout {
        val l = vbox(c)
        l.background = shape(c, CARD, 14, LINE, 1)
        val p = dp(c, 14)
        l.setPadding(p, p, p, p)
        return l
    }

    /** etiqueta pequena vermelha em cima de cada bloco */
    fun label(c: Context, s: String): TextView {
        val t = text(c, s.uppercase(), 11f, RED, true)
        t.letterSpacing = 0.12f
        return t
    }

    fun spacer(c: Context, h: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
        return v
    }

    /** bolinha + texto de estado: "ADB · CONECTADO" */
    fun status(c: Context, nome: String, valor: String, ok: Boolean?): View {
        val r = hbox(c)
        val cor = when (ok) { true -> OK; false -> RED; null -> WARN }
        val dot = View(c)
        dot.background = shape(c, cor, 8)
        r.addView(dot, LinearLayout.LayoutParams(dp(c, 9), dp(c, 9)))
        val n = text(c, nome, 13f, MUTED, mono = true)
        val np = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        np.leftMargin = dp(c, 10)
        r.addView(n, np)
        r.addView(text(c, valor, 13f, cor, true, mono = true))
        r.setPadding(0, dp(c, 5), 0, dp(c, 5))
        return r
    }

    fun button(c: Context, label: String, kind: Kind = Kind.PRIMARY, onClick: () -> Unit): TextView {
        val t = text(c, label.uppercase(), 13f, if (kind == Kind.GHOST) TEXT else 0xFFFFFFFF.toInt(), true)
        t.gravity = Gravity.CENTER
        t.letterSpacing = 0.06f
        val fill = when (kind) { Kind.PRIMARY -> RED; Kind.DANGER -> RED_DARK; Kind.GHOST -> CARD2 }
        val stroke = if (kind == Kind.PRIMARY) RED else LINE
        t.background = ripple(shape(c, fill, 12, stroke, 1))
        val ph = dp(c, 14)
        t.setPadding(ph, dp(c, 14), ph, dp(c, 14))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** linha de menu: titulo, descricao curta e setinha */
    fun row(c: Context, titulo: String, sub: String, onClick: () -> Unit): View {
        val r = hbox(c)
        r.background = ripple(shape(c, CARD, 14, LINE, 1))
        val p = dp(c, 14)
        r.setPadding(p, dp(c, 13), p, dp(c, 13))
        val col = vbox(c)
        col.addView(text(c, titulo.uppercase(), 14f, TEXT, true))
        if (sub.isNotEmpty()) {
            val s = text(c, sub, 12f, MUTED)
            s.setPadding(0, dp(c, 2), 0, 0)
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(text(c, "›", 22f, RED, true))
        r.isClickable = true
        r.setOnClickListener { onClick() }
        return r
    }

    fun toggle(c: Context, titulo: String, sub: String, ligado: Boolean, onChange: (Boolean) -> Unit): View {
        val r = hbox(c)
        r.background = shape(c, CARD, 14, LINE, 1)
        val p = dp(c, 14)
        r.setPadding(p, dp(c, 11), p, dp(c, 11))
        val col = vbox(c)
        col.addView(text(c, titulo.uppercase(), 14f, TEXT, true))
        if (sub.isNotEmpty()) {
            val s = text(c, sub, 12f, MUTED)
            s.setPadding(0, dp(c, 2), 0, 0)
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(c)
        sw.isChecked = ligado
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.addView(sw)
        return r
    }

    /** par de botoes lado a lado (ex.: TIROU | COLOCOU) com o escolhido em destaque */
    fun segmento(c: Context, opcoes: List<String>, escolhido: Int, onPick: (Int) -> Unit): View {
        val r = hbox(c)
        for ((i, o) in opcoes.withIndex()) {
            val sel = i == escolhido
            val t = text(c, o.uppercase(), 12f, if (sel) 0xFFFFFFFF.toInt() else MUTED, true)
            t.gravity = Gravity.CENTER
            t.background = ripple(shape(c, if (sel) RED else CARD2, 10, if (sel) RED else LINE, 1))
            t.setPadding(dp(c, 6), dp(c, 12), dp(c, 6), dp(c, 12))
            t.isClickable = true
            t.setOnClickListener { onPick(i) }
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(c, 8)
            r.addView(t, p)
        }
        return r
    }

    fun field(c: Context, hint: String, numero: Boolean): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(MUTED)
        e.setTextColor(TEXT)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.typeface = Typeface.MONOSPACE
        e.inputType = if (numero) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.background = shape(c, CARD2, 10, LINE, 1)
        val p = dp(c, 12)
        e.setPadding(p, dp(c, 11), p, dp(c, 11))
        e.setSingleLine(true)
        return e
    }

    fun toast(c: Context, msg: String) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    // ---------------- caixas de dialogo ----------------
    private fun builder(c: Context) = AlertDialog.Builder(c, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

    fun dialogNumero(c: Context, titulo: String, dica: String, min: Int, max: Int, atual: Int, onOk: (Int) -> Unit) {
        val e = field(c, dica, true)
        e.setText(atual.toString())
        e.setSelection(e.text.length)
        val box = vbox(c)
        val p = dp(c, 20)
        box.setPadding(p, dp(c, 8), p, 0)
        box.addView(text(c, "de $min a $max", 12f, MUTED))
        box.addView(e, lp(top = 8, c = c))
        builder(c).setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = e.text.toString().trim().toIntOrNull()
                if (v == null || v < min || v > max) toast(c, "Use um numero de $min a $max") else onOk(v)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogTexto(c: Context, titulo: String, dica: String, onOk: (String) -> Unit) {
        val e = field(c, dica, false)
        val box = vbox(c)
        val p = dp(c, 20)
        box.setPadding(p, dp(c, 8), p, 0)
        box.addView(e)
        builder(c).setTitle(titulo).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogEscolha(c: Context, titulo: String, opcoes: List<String>, onPick: (Int) -> Unit) {
        builder(c).setTitle(titulo)
            .setItems(opcoes.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    fun dialogConfirma(c: Context, titulo: String, msg: String, ok: String = "SIM", onOk: () -> Unit) {
        builder(c).setTitle(titulo).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("CANCELAR", null)
            .show()
    }
}
