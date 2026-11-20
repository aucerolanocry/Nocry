package com.nocry.app

import android.app.Application
import android.os.StrictMode

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // primeiro de tudo: se algo der errado daqui pra frente, fica registrado (Logs › Relatório de erros)
        Erros.instalar(this)
        // só na prévia do CI: avisa no logcat quando a tela faz trabalho pesado (arquivo/rede) na thread principal
        if (BuildConfig.PREVIEW) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        Prefs.init(this)
        Sys.init(this)
        NLog.init(this)
        Notif.init(this)
        Sniper.init(this)
        NLog.add("app", "iniciou")
        // toque via volume/carregador e modo carregador saíram: quem tinha algum ligado volta ao normal sozinho
        if (Engine.temModoAntigo()) Bg.io { Engine.desligarModosAntigos() }
    }
}
