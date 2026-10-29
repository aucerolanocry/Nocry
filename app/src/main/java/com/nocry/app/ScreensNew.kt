package com.nocry.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

// =====================================================================================
// TELA DA KEY (entrada) com aviso do que e enviado
// =====================================================================================
class KeyGate(private val act: MainActivity) {
    private val dono = FrameLayout(act)
    private var ocupado = false
    private var erro = ""
    private var keyTxt = Prefs.licKey ?: ""
    private var concordo = Prefs.consentiu
    private var campo: EditText? = null

    fun criar(): View {
        monta()
        // key guardada que deixou de valer (ex.: passou do prazo sem internet): tenta confirmar sozinho
        val salva = Prefs.licKey
        if (salva != null && Prefs.consentiu) {
            Bg.later(300) { tenta(salva) }
        }
        return dono
    }

    private fun monta() {
        campo?.let { keyTxt = it.text.toString() }
        dono.removeAllViews()
        val c = act
        val sv = ScrollView(c)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = false
        val col = Ui.vbox(c)
        col.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(c, 20)
        col.setPadding(p, Ui.dp(c, 28), p, Ui.dp(c, 28))

        val marca = Ui.text(c, "NOCRY", 44f, Ui.RED, true, mono = true)
        marca.letterSpacing = 0.2f
        col.addView(marca)
        col.addView(Ui.text(c, "Modo competitivo, toque e otimização. Para entrar, use a sua key.", 14f, Ui.MUTED), Ui.lp(top = 4, c = c))

        val card = Ui.card(c, forte = true)
        card.addView(Ui.label(c, "sua key"))
        val e = Ui.field(c, "NOCRY-XXXX-XXXX", false)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.setText(keyTxt)
        e.setSelection(e.text.length)
        campo = e
        card.addView(e, Ui.lp(top = 8, c = c))

        card.addView(Ui.label(c, "o que o NOCRY envia"), Ui.lp(top = 16, c = c))
        val lista = listOf(
            "seu IP (o servidor vê sozinho)",
            "marca e modelo do celular",
            "versão do Android e do app",
            "um código anônimo do aparelho (não é o IMEI nem o número do telefone)"
        )
        for (l in lista) card.addView(Ui.text(c, "·  $l", 12.5f, Ui.TEXT), Ui.lp(top = 5, c = c))
        card.addView(Ui.text(c, "Não envia telefone, localização, contatos, mensagens nem a lista de apps. Serve para saber quem usa cada key e poder desativar uma key.", 12f, Ui.MUTED), Ui.lp(top = 8, c = c))

        val linha = Ui.hbox(c)
        linha.addView(Ui.text(c, "Li e concordo", 14f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        linha.addView(Ui.interruptor(c, concordo) { v -> concordo = v })
        card.addView(linha, Ui.lp(top = 14, c = c))

        if (erro.isNotEmpty()) card.addView(Ui.text(c, erro, 13f, Ui.WARN, true), Ui.lp(top = 12, c = c))
        card.addView(Ui.button(c, if (ocupado) "confirmando..." else "entrar", Ui.Kind.PRIMARY, R.drawable.ic_key) {
            tenta(e.text.toString())
        }, Ui.lp(top = 14, c = c))
        col.addView(card, Ui.lp(top = 22, c = c))

        val dev = License.devId(c).take(8)
        col.addView(Ui.text(c, "versão ${License.versao(c)} · aparelho $dev", 11f, Ui.MUTED, mono = true), Ui.lp(top = 16, c = c))
        col.addView(Ui.text(c, "Sem key? Peça a quem te passou o app.", 12f, Ui.MUTED), Ui.lp(top = 4, c = c))

        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dono.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun tenta(key: String) {
        if (ocupado) return
        val k = key.trim()
        if (k.length < 4) { erro = "Digite a sua key."; monta(); return }
        if (!concordo) { erro = "Marque \"Li e concordo\" para continuar."; monta(); return }
        ocupado = true
        erro = ""
        keyTxt = k
        monta()
        Prefs.consentiu = true
        Bg.run({ License.login(act.applicationContext, k) }) { r ->
            ocupado = false
            if (r.ok) act.entrou()
            else {
                erro = r.texto()
                // se a key salva foi recusada de vez, esquece ela
                if (r.negado()) Prefs.licKey = null
                monta()
            }
        }
    }
}

// =====================================================================================
// PERSONALIZAR (fundo, cores, vidro, fonte...)
// =====================================================================================
class PersonalizarScreen(a: MainActivity) : Screen(a) {
    override val titulo = "Personalizar"
    override val aba = 2

    private var ocupado = false
    private var msg = ""

    companion object {
        private const val REQ_MIDIA = 4101
        private const val LIMITE_MB = 400
    }

    override fun key(): String = listOf(
        Prefs.bgTipo, Prefs.bgPreset, Prefs.bgArquivo, Prefs.bgEscuro, Prefs.bgBlur, Prefs.accent,
        Prefs.vidro, Prefs.raio, Prefs.fonte, Prefs.escala, Prefs.neon, Prefs.anim, ocupado, msg
    ).joinToString("|")

    private fun arquivo(): File? = Prefs.bgArquivo?.let { File(act.filesDir, it) }?.takeIf { it.isFile }

    private fun mb(f: File): String {
        val m = f.length() / 1048576.0
        return if (m >= 10) "${m.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", m)
    }

    /** mudou algo que afeta todas as telas */
    private fun tudo() { act.reaplica() }

    /** mudou so o fundo */
    private fun fundo() { act.refazFundo(); redesenha() }

    override fun render() {
        limpa()
        val c = act
        add(Ui.text(c, "Deixe o NOCRY do seu jeito. Tudo muda na hora e fica salvo.", 13f, Ui.MUTED), 0)

        // ---------------- fundo ----------------
        val f = Ui.card(c)
        f.addView(Ui.label(c, "fundo"))
        val tipos = listOf("animado", "imagem", "vídeo", "liso")
        val atual = when (Prefs.bgTipo) { "imagem" -> 1; "video" -> 2; "solido" -> 3; else -> 0 }
        f.addView(Ui.segmento(c, tipos, atual) { i ->
            Prefs.bgTipo = when (i) { 1 -> "imagem"; 2 -> "video"; 3 -> "solido"; else -> "animado" }
            fundo()
        }, Ui.lp(top = 10, c = c))

        when (Prefs.bgTipo) {
            "animado" -> {
                f.addView(Ui.text(c, "Desenhos do próprio app. Eles seguem a cor de destaque.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
                val nomes = listOf("brasas", "aurora", "grade", "noite")
                f.addView(Ui.segmento(c, nomes, nomes.indexOf(Prefs.bgPreset).coerceAtLeast(0)) { i ->
                    Prefs.bgPreset = nomes[i]
                    fundo()
                }, Ui.lp(top = 8, c = c))
            }
            "imagem", "video" -> {
                val video = Prefs.bgTipo == "video"
                val tem = arquivo() != null
                val atualTxt = arquivo()?.let { (if (video) "vídeo escolhido" else "imagem escolhida") + " · " + mb(it) }
                    ?: "nada escolhido ainda, por isso o fundo animado aparece"
                f.addView(Ui.text(c, atualTxt, 12.5f, if (tem) Ui.OK else Ui.WARN, true, mono = true), Ui.lp(top = 10, c = c))
                f.addView(Ui.button(c, if (ocupado) "copiando..." else (if (video) "escolher vídeo da galeria" else "escolher imagem da galeria"), Ui.Kind.PRIMARY, if (video) R.drawable.ic_video else R.drawable.ic_image) {
                    if (!ocupado) escolher(video)
                }, Ui.lp(top = 10, c = c))
                if (tem) f.addView(Ui.button(c, "remover e voltar ao animado", Ui.Kind.GHOST, R.drawable.ic_trash) { remove() }, Ui.lp(top = 8, c = c))
                f.addView(Ui.text(c, if (video)
                    "O vídeo toca em loop, sem som. Vídeos curtos e leves (até 1080p) gastam menos bateria. O arquivo é copiado para dentro do app e nada é enviado pela internet."
                else
                    "A imagem é copiada para dentro do app e nada é enviado pela internet.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
            }
            else -> f.addView(Ui.text(c, "Só a cor de fundo do app, sem nenhum desenho.", 12f, Ui.MUTED), Ui.lp(top = 10, c = c))
        }
        if (msg.isNotEmpty()) f.addView(Ui.text(c, msg, 12.5f, Ui.WARN, true), Ui.lp(top = 8, c = c))

        f.addView(Ui.slider(c, "Escurecer o fundo", Prefs.bgEscuro, 0, 90, "%", onMove = { v ->
            Prefs.bgEscuro = v
            act.ajustaFundo()
        }, onFim = { v -> Prefs.bgEscuro = v; act.ajustaFundo() }), Ui.lp(top = 16, c = c))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            f.addView(Ui.slider(c, "Desfoque do fundo", Prefs.bgBlur, 0, 25, "", onMove = { v ->
                Prefs.bgBlur = v
                act.ajustaFundo()
            }, onFim = { v -> Prefs.bgBlur = v; act.ajustaFundo() }), Ui.lp(top = 10, c = c))
        } else {
            f.addView(Ui.text(c, "O desfoque do fundo precisa do Android 12 ou mais novo.", 12f, Ui.MUTED), Ui.lp(top = 12, c = c))
        }
        add(f, 12)

        // ---------------- cores ----------------
        val k = Ui.card(c)
        k.addView(Ui.label(c, "cor de destaque"))
        k.addView(Ui.cores(c, Theme.PALETA, Prefs.accent, { v -> Prefs.accent = v; tudo() }, { corPropria() }), Ui.lp(top = 12, c = c))
        k.addView(Ui.toggle(c, "Brilho neon", "borda e luz na cor de destaque", Prefs.neon) { v -> Prefs.neon = v; tudo() }, Ui.lp(top = 14, c = c))
        add(k, 12)

        // ---------------- vidro e forma ----------------
        val v = Ui.card(c)
        v.addView(Ui.label(c, "vidro e forma"))
        v.addView(Ui.slider(c, "Opacidade do vidro", Prefs.vidro, 20, 95, "%") { x -> Prefs.vidro = x; tudo() }, Ui.lp(top = 12, c = c))
        v.addView(Ui.slider(c, "Arredondamento", Prefs.raio, 6, 30, " dp") { x -> Prefs.raio = x; tudo() }, Ui.lp(top = 12, c = c))
        add(v, 12)

        // ---------------- texto ----------------
        val t = Ui.card(c)
        t.addView(Ui.label(c, "texto"))
        val fontes = listOf("grotesk", "mono", "sistema")
        t.addView(Ui.segmento(c, listOf("Space Grotesk", "Mono", "Sistema"), fontes.indexOf(Prefs.fonte).coerceAtLeast(0)) { i ->
            Prefs.fonte = fontes[i]
            tudo()
        }, Ui.lp(top = 10, c = c))
        t.addView(Ui.slider(c, "Tamanho do texto", Prefs.escala, 85, 130, "%") { x -> Prefs.escala = x; tudo() }, Ui.lp(top = 12, c = c))
        t.addView(Ui.toggle(c, "Animações", "fundo animado, anel e transições", Prefs.anim) { x -> Prefs.anim = x; tudo() }, Ui.lp(top = 14, c = c))
        add(t, 12)

        add(Ui.button(c, "voltar ao visual padrão", Ui.Kind.GHOST, R.drawable.ic_sparkle) {
            Ui.dialogConfirma(act, "VISUAL PADRÃO", "Cores, fonte, vidro e fundo voltam ao padrão. O vídeo ou a imagem que você escolheu continua guardado.", "VOLTAR") {
                restaura()
            }
        }, 16)
    }

    private fun restaura() {
        Prefs.bgTipo = "animado"
        Prefs.bgPreset = "brasas"
        Prefs.bgEscuro = 55
        Prefs.bgBlur = 0
        Prefs.accent = Theme.PALETA[0].valor
        Prefs.vidro = 62
        Prefs.raio = 18
        Prefs.fonte = "grotesk"
        Prefs.escala = 100
        Prefs.neon = true
        Prefs.anim = true
        msg = ""
        tudo()
    }

    private fun corPropria() {
        Ui.dialogTexto(act, "COR PRÓPRIA", "#RRGGBB (ex.: #FF2D55)") { s ->
            val t = s.trim().let { if (it.startsWith("#")) it else "#$it" }
            val cor = try { Color.parseColor(t) } catch (_: Exception) { null }
            if (cor == null) Ui.toast(act, "Cor inválida. Use 6 letras/números, como FF2D55.")
            else { Prefs.accent = cor; tudo() }
        }
    }

    // ---------------- escolher video / imagem da galeria ----------------
    @Suppress("DEPRECATION")
    private fun escolher(video: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (video) "video/*" else "image/*"
        act.aoResultado = { req, res, data ->
            val u = data?.data
            if (req == REQ_MIDIA && res == Activity.RESULT_OK && u != null) copia(u, video)
        }
        try {
            act.startActivityForResult(i, REQ_MIDIA)
        } catch (_: Exception) {
            Ui.toast(act, "Não consegui abrir a galeria")
        }
    }

    private class Copia(val nome: String?, val erro: String)

    private fun copia(uri: Uri, video: Boolean) {
        ocupado = true
        msg = ""
        redesenha()
        val ctx = act.applicationContext
        Bg.run({ copiar(ctx, uri, video) }) { r ->
            ocupado = false
            if (r.nome != null) {
                val velho = Prefs.bgArquivo
                Prefs.bgArquivo = r.nome
                Prefs.bgTipo = if (video) "video" else "imagem"
                if (velho != null && velho != r.nome) try { File(ctx.filesDir, velho).delete() } catch (_: Exception) {}
                msg = ""
                NLog.add("visual", "fundo novo: ${r.nome}")
                act.refazFundo()
            } else msg = r.erro
            redesenha()
        }
    }

    private fun copiar(c: Context, uri: Uri, video: Boolean): Copia {
        val nome = "fundo_" + System.currentTimeMillis() + (if (video) ".mp4" else ".img")
        val dest = File(c.filesDir, nome)
        return try {
            val ins = c.contentResolver.openInputStream(uri) ?: return Copia(null, "Não consegui abrir esse arquivo.")
            ins.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            if (dest.length() > LIMITE_MB * 1048576L) {
                dest.delete()
                Copia(null, "Arquivo grande demais (mais de $LIMITE_MB MB). Escolha um menor.")
            } else if (dest.length() == 0L) {
                dest.delete()
                Copia(null, "O arquivo veio vazio.")
            } else Copia(nome, "")
        } catch (t: Throwable) {
            try { dest.delete() } catch (_: Exception) {}
            Copia(null, "Não consegui copiar: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun remove() {
        val a = Prefs.bgArquivo
        if (a != null) try { File(act.filesDir, a).delete() } catch (_: Exception) {}
        Prefs.bgArquivo = null
        Prefs.bgTipo = "animado"
        msg = ""
        fundo()
    }
}
