package com.nocry.app

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * Login do Discord DENTRO do bot de fila (só dono), rápido: o dono digita email e senha, o app
 * entra direto na API do Discord e recebe o token na hora, salva cifrado (Cofre) e liga o bot.
 * Se a conta tiver 2 fatores, aparece o campo do código. Se o Discord pedir captcha, cai pro
 * "colar token" da tela anterior.
 *
 * Isto NÃO é a entrada do app — a entrada continua sendo a tela da key (KeyGate).
 */
class LoginDiscordScreen(a: MainActivity) : Screen(a) {
    override val titulo = "ENTRAR NO DISCORD"
    override fun render() {}

    private var ticket: String? = null
    private var ocupado = false

    override fun criar(): View {
        val sv = ScrollView(act)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        val box = Ui.vbox(act)
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 14), Ui.dp(act, 20), Ui.dp(act, 24))
        sv.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(Ui.text(act, "Entre com o email e a senha da conta do bot. O token é pego na hora e salvo cifrado no celular.", 12f, Ui.MUTED))

        val email = Ui.field(act, "email", false).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        box.addView(email, Ui.lp(top = 14, c = act))

        val senha = Ui.field(act, "senha", false).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(senha, Ui.lp(top = 10, c = act))

        val cod = Ui.field(act, "código de 2 fatores", true)
        cod.visibility = View.GONE
        box.addView(cod, Ui.lp(top = 10, c = act))

        val status = Ui.text(act, "", 12f, Ui.MUTED)
        status.visibility = View.GONE
        box.addView(status, Ui.lp(top = 12, c = act))
        fun aviso(s: String) { status.text = s; status.visibility = View.VISIBLE }

        fun concluir(token: String) {
            if (Sniper.ligado()) Sniper.desligar()
            Sniper.salvarToken(token)
            Sniper.estado = "desligado"
            Sniper.ligar()
            Ui.toast(act, "Token salvo. Ligando o bot...")
            act.fecha()
        }

        fun tratar(r: LoginResultado) {
            when (r) {
                is LoginResultado.Ok -> concluir(r.token)
                is LoginResultado.PrecisaCodigo -> {
                    ticket = r.ticket
                    cod.visibility = View.VISIBLE
                    aviso("Essa conta tem 2 fatores. Abra o app autenticador e digite o código de 6 dígitos.")
                }
                LoginResultado.Captcha ->
                    aviso("O Discord pediu captcha. Por aqui não dá agora — volte e use \"colar token\".")
                is LoginResultado.Erro -> aviso(r.msg)
            }
        }

        box.addView(Ui.button(act, "entrar", Ui.Kind.PRIMARY, R.drawable.ic_key) {
            if (ocupado) return@button
            val e = email.text.toString().trim()
            val s = senha.text.toString()
            val t = ticket
            if (t == null && (e.isEmpty() || s.isEmpty())) { Ui.toast(act, "preencha email e senha"); return@button }
            if (t != null && cod.text.toString().trim().isEmpty()) { Ui.toast(act, "digite o código"); return@button }
            ocupado = true
            aviso(if (t == null) "entrando..." else "conferindo o código...")
            Bg.io {
                val r = if (t == null) Discord.login(e, s, Sniper.idAparelho())
                else Discord.loginCodigo(t, cod.text.toString(), Sniper.idAparelho())
                Bg.ui { ocupado = false; tratar(r) }
            }
        }, Ui.lp(top = 16, c = act))

        return sv
    }
}
