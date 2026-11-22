package com.nocry.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/**
 * Login do Discord DENTRO do bot de fila (só dono). Abre o discord.com; o dono entra com email e
 * senha; quando loga, o app pega o token sozinho, salva cifrado (Cofre) e liga o bot. Volta pro bot.
 *
 * Isto NÃO é a entrada do app — a entrada continua sendo a tela da key (KeyGate). Esta tela só é
 * aberta quando o dono toca "entrar com email e senha" na aba Conta do bot de fila.
 */
class LoginDiscordScreen(a: MainActivity) : Screen(a) {
    override val titulo = "ENTRAR NO DISCORD"
    override fun render() {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun criar(): View {
        val box = Ui.vbox(act)
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 16))

        box.addView(Ui.text(act, "Entre com a conta do bot. O app pega o token sozinho e salva cifrado no celular.", 12f, Ui.MUTED))

        val web = WebView(act)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36"
        box.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1f
            topMargin = Ui.dp(act, 12)
        })

        var pego = false
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun pegarToken(token: String) {
                if (pego) return
                pego = true
                Bg.ui {
                    val t = token.trim().trim('"')
                    if (t.length >= 20) {
                        if (Sniper.ligado()) Sniper.desligar()
                        Sniper.salvarToken(t)
                        Sniper.estado = "desligado"
                        Sniper.ligar()
                        Ui.toast(act, "Token salvo. Ligando o bot...")
                        act.fecha()
                    } else {
                        pego = false
                        Ui.toast(act, "Nao consegui pegar o token. Tente entrar de novo.")
                    }
                }
            }
        }, "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Fica tentando pegar o token a cada ~0,7s (o Discord só carrega os modulos com o
                // token depois do login, e troca de tela sem recarregar a pagina). O setInterval
                // continua rodando mesmo quando o Discord navega por dentro, ate achar o token.
                val js = """
                    (function() {
                        if (window.__nocryOn) return;
                        window.__nocryOn = true;
                        function temGetToken(o){ return o && typeof o.getToken === 'function'; }
                        function pega(req){
                            try {
                                for (var k in req.c) {
                                    var e = req.c[k] && req.c[k].exports;
                                    if (!e) continue;
                                    if (temGetToken(e)) return e.getToken();
                                    if (temGetToken(e.default)) return e.default.getToken();
                                    if (temGetToken(e.Z)) return e.Z.getToken();
                                    if (temGetToken(e.ZP)) return e.ZP.getToken();
                                }
                            } catch(_) {}
                            return null;
                        }
                        var n = 0;
                        var t = setInterval(function(){
                            n++;
                            try {
                                if (!window.__nocryReq && window.webpackChunkdiscord_app) {
                                    window.webpackChunkdiscord_app.push([[Math.random()],{},function(req){ window.__nocryReq = req; }]);
                                }
                                if (window.__nocryReq) {
                                    var tk = pega(window.__nocryReq);
                                    if (tk) { clearInterval(t); window.Android.pegarToken(String(tk)); return; }
                                }
                            } catch(_) {}
                            if (n > 45) clearInterval(t);
                        }, 700);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }
        web.loadUrl("https://discord.com/login")
        return box
    }
}
