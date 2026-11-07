package com.nocry.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Confere versao nova em segundo plano, mesmo com o app fechado e sem nenhum monitor ligado
 * (o Android deixa no minimo a cada 15 min). Achou versao nova: avisa por notificacao, e ao abrir
 * o app a tela de atualizacao obrigatoria ja aparece.
 */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        // de quebra serve de vigia: se o Android fechou o NOCRY com algo ativado (ou instalando), religa
        try {
            if (Prefs.precisaServico() && License.valid() && !NocryService.rodando) {
                NLog.add("servico", "vigia: estava ativado mas parado; religando")
                NocryService.sync(applicationContext)
            }
            InstalaService.retoma(applicationContext)
        } catch (_: Throwable) {}
        Bg.io {
            try { Update.atualizarCache(applicationContext, forcar = true) } finally { jobFinished(p, false) }
        }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean = true

    companion object {
        private const val ID = 7301

        /** agenda uma vez (se ja estiver agendado, deixa como esta) */
        fun agenda(c: Context) {
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(ID) != null) return
                val info = JobInfo.Builder(ID, ComponentName(c, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
            } catch (t: Throwable) {
                NLog.add("app", "nao agendei a checagem de versao: $t")
            }
        }
    }
}
