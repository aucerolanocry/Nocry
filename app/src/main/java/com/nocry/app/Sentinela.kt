package com.nocry.app

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Sentry: manda os erros (crash e travada) pro painel do dono, pra ver o que quebra no celular das pessoas.
 *  - Só liga se o build tiver o SENTRY_DSN (segredo do GitHub). Sem ele, não faz nada.
 *  - Não manda dado pessoal: sem IP, sem print da tela, sem árvore de telas.
 *  - Antes de sair do celular, tudo passa por [limpa]: token do Discord, chave, pix, e-mail, CPF e telefone viram "[oculto]".
 *  - O relatório local (Erros) continua funcionando igual.
 */
object Sentinela {
    private const val PREFS = "nocry_erros"
    private const val CHAVE_LIGADO = "enviar_sentry"

    @Volatile var ligado = false
        private set

    fun instalar(c: Context) {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isEmpty() || BuildConfig.PREVIEW) return
        val app = c.applicationContext
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHAVE_LIGADO, true)) return
        try {
            SentryAndroid.init(app) { o ->
                o.dsn = dsn
                o.release = "nocry@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                o.environment = BuildConfig.SENTRY_ENV.ifBlank { "publico" }
                o.isSendDefaultPii = false
                o.isAttachScreenshot = false
                o.isAttachViewHierarchy = false
                o.isAnrEnabled = true
                o.maxBreadcrumbs = 40
                o.beforeSend = SentryOptions.BeforeSendCallback { ev, _ -> limpaEvento(ev) }
                o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { b, _ -> limpaRastro(b) }
            }
            ligado = true
        } catch (_: Throwable) {
            ligado = false
        }
    }

    /** liga/desliga o envio (vale a partir da próxima vez que o app abrir) */
    fun definir(c: Context, enviar: Boolean) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CHAVE_LIGADO, enviar).apply()
    }

    // ------------------------------------------------------------------ limpeza

    private val regras: List<Pair<Regex, String>> = listOf(
        // token do Discord (normal e com 2FA)
        Regex("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}") to "[oculto]",
        Regex("mfa\\.[A-Za-z0-9_-]{20,}") to "[oculto]",
        // "token: xxx", "authorization=xxx", "chave: xxx" ...
        Regex("(?i)\\b(authorization|bearer|token|senha|password|secret|key|chave|pix)(\\s*[:=]\\s*|\\s+)[^\\s,;\"'}]+") to "$1$2[oculto]",
        // e-mail
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[oculto]",
        // CPF / CNPJ
        Regex("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b") to "[oculto]",
        Regex("\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b") to "[oculto]",
        // telefone (+55 ...)
        Regex("\\+?55\\s?\\(?\\d{2}\\)?\\s?9?\\d{4}-?\\d{4}") to "[oculto]",
        // chave aleatória do pix (uuid) e textos longos que parecem segredo
        Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b") to "[oculto]",
        Regex("\\b[A-Za-z0-9_+/=-]{32,}\\b") to "[oculto]",
    )

    fun limpa(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        var t: String = s
        for ((r, troca) in regras) t = r.replace(t, troca)
        return t
    }

    private fun limpaEvento(ev: SentryEvent): SentryEvent {
        try {
            ev.message?.let { m ->
                m.formatted = limpa(m.formatted)
                m.message = limpa(m.message)
                m.params = m.params?.map { limpa(it) ?: "" }
            }
            ev.exceptions?.forEach { e -> e.value = limpa(e.value) }
            ev.breadcrumbs?.forEach { limpaRastro(it) }
            ev.extras?.keys?.toList()?.forEach { k -> ev.setExtra(k, limpa(ev.getExtra(k)?.toString()) ?: "") }
            ev.setTag("tela", Erros.telaAtual)
            ev.user = null
        } catch (_: Throwable) {}
        return ev
    }

    private fun limpaRastro(b: Breadcrumb): Breadcrumb {
        try {
            b.message = limpa(b.message)
            b.data.keys.toList().forEach { k -> b.setData(k, limpa(b.getData(k)?.toString()) ?: "") }
        } catch (_: Throwable) {}
        return b
    }
}
