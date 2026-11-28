package com.nocry.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * O ouvido do NOCRY: pega o microfone sozinho (sem depender do serviço de voz do Google, que no Xiaomi/Android 16
 * cai toda hora e deixava ela "ouvindo" sem ouvir nada), separa cada vez que você fala e manda só esse pedaço
 * pro servidor do NOCRY escrever o que foi dito (Whisper).
 *
 *  - O microfone fica aberto o tempo todo enquanto ela está ligada: não tem "reiniciar a escuta" a cada frase.
 *  - Se uma fonte de som só manda silêncio (acontece em alguns celulares), troca sozinho pra outra fonte.
 *  - Enquanto ela pensa ou fala, o som é jogado fora (ela não escuta a própria voz).
 *  - O áudio só sai do celular quando você realmente falou, e não é guardado em lugar nenhum.
 */
object Ouvido {
    interface Ouvinte {
        /** chegou som de verdade do microfone */
        fun pronto()
        /** volume de agora, de 0 a 1 (pra luz da tela) */
        fun nivel(n: Float)
        /** detectou que você começou a falar */
        fun vozDetectada()
        /** você terminou de falar: áudio em WAV (16 kHz, mono) e a duração em segundos */
        fun fala(wav: ByteArray, seg: Float)
        /** o microfone não abriu ou só manda silêncio, mesmo trocando de fonte (ele continua tentando sozinho) */
        fun falhou(motivo: String)
        /** true enquanto ela pensa, fala ou está escrevendo o que você disse: o som é jogado fora */
        fun mudo(): Boolean
    }

    /** resultado de escrever a fala. texto == null: não deu; [motivo] diz por quê (limite, rede, indisponivel) */
    class Texto(val texto: String?, val motivo: String)

    private const val HZ = 16000
    private const val QUADRO = 320            // 20 ms de som
    private const val PRE_ROLL = 20           // guarda 400 ms antes de você começar (pra não cortar a primeira palavra)
    private const val FIM_SILENCIO = 40       // 800 ms quieto = terminou a frase
    private const val MANTEM_FIM = 25         // deixa 500 ms de silêncio no final (não corta a última sílaba fraca)
    private const val LIMIAR_MIN = -50f       // abaixo disso nunca é voz (o Xiaomi chia até uns -57 dB com a fonte "de voz"; sua fala fica em -17 a -35)
    private const val FIM_RELATIVO = 24f      // também é "quieto" o que está 24 dB abaixo do nível da sua própria voz
    private const val JANELA_CHAO = 150       // o "chão" de barulho é o volume mais baixo dos últimos 3 s (acompanha o barulho do ambiente)
    private const val FOLGA_CHAO = 10f        // quieto = a menos de 10 dB do chão (barulho de fundo fraco e constante não segura a frase)
    private const val MAX_QUADROS = 700       // frase mais longa: 14 s
    private const val MIN_VOZ = 12            // menos de 240 ms de voz = estalo, não é fala
    private const val MUDO_QUADROS = 200      // 4 s só de silêncio absoluto numa fonte = troca de fonte
    private const val CALIBRA = 40            // 0,8 s medindo o barulho de fundo ao abrir o microfone

    // a fonte "de voz" é a que já deu som no Xiaomi (o log mostrou); se alguma só mandar silêncio, troca sozinho pra próxima
    private val FONTES = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    private val NOMES = arrayOf("de voz", "comum", "de chamada")

    private const val SOM = 1
    private const val MUDO = 2
    private const val ERRO = 3

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var geracao = 0
    @Volatile private var fio: Thread? = null

    val rodando: Boolean get() = fio?.isAlive == true

    @Volatile private var appCtx: Context? = null

    /** outro app (gravador de tela, transmissão, chamada) está gravando o microfone? Diz o que achou, ou null.
     *  Só avisa: o Android decide quem recebe o som, o NOCRY não tem como tomar o microfone à força */
    fun microfoneOcupado(): String? {
        val c = appCtx ?: return null
        return try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val l = am.activeRecordingConfigurations
            when {
                android.os.Build.VERSION.SDK_INT >= 29 && l.any { it.isClientSilenced } -> "O Android calou o microfone do NOCRY porque outro app está gravando (gravação de tela ou transmissão)"
                l.size > 1 -> "Outro app está gravando o microfone junto (gravação de tela ou transmissão)"
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    // ---- números pra saber o que aconteceu (vão pro painel da staff só como contagem, nunca o áudio nem o texto)
    @Volatile private var fonteAtual = ""
    @Volatile private var aberturas = 0
    @Volatile private var quadrosLidos = 0L
    @Volatile private var quadrosComSom = 0L
    @Volatile private var maxDb = -90f
    @Volatile private var pisoDb = -90f
    @Volatile private var segmentos = 0
    @Volatile private var ruidos = 0
    @Volatile private var porSilencio = 0
    @Volatile private var porLimite = 0
    @Volatile private var ultP10 = 0
    @Volatile private var ultP90 = 0
    @Volatile private var trocasFonte = 0
    @Volatile private var ultimoMotivo = ""
    @Volatile private var desde = 0L

    fun resumo(): JSONObject = JSONObject()
        .put("fonte", fonteAtual).put("aberturas", aberturas).put("quadros", quadrosLidos).put("comSom", quadrosComSom)
        .put("maxDb", Math.round(maxDb)).put("pisoDb", Math.round(pisoDb)).put("falas", segmentos).put("ruidos", ruidos)
        .put("fimSilencio", porSilencio).put("fimLimite", porLimite).put("p10", ultP10).put("p90", ultP90)
        .put("trocas", trocasFonte).put("motivo", ultimoMotivo).put("segAberto", if (desde > 0) (System.currentTimeMillis() - desde) / 1000 else 0)
        .put("rodando", rodando)

    /** abre o microfone e começa a separar as falas. Pode chamar de novo: se já está rodando, não faz nada */
    fun iniciar(c: Context, l: Ouvinte): Boolean {
        appCtx = c.applicationContext
        if (rodando) return true
        val minha = ++geracao
        aberturas = 0; quadrosLidos = 0; quadrosComSom = 0; maxDb = -90f; segmentos = 0; ruidos = 0; porSilencio = 0; porLimite = 0; ultP10 = 0; ultP90 = 0; trocasFonte = 0; ultimoMotivo = ""
        desde = System.currentTimeMillis()
        val t = Thread({ laco(minha, l) }, "nocry-ouvido")
        t.isDaemon = true
        fio = t
        t.start()
        return true
    }

    fun parar() {
        geracao++
        fio = null
    }

    // ------------------------------------------------------------------ microfone

    @SuppressLint("MissingPermission")
    private fun abrir(idx: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { ultimoMotivo = "buffer do microfone inválido ($min)"; return null }
        val buf = maxOf(min * 2, QUADRO * 2 * 16)
        try {
            val r = AudioRecord(FONTES[idx], HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                ultimoMotivo = "fonte ${NOMES[idx]} não iniciou"
                return null
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                r.release()
                ultimoMotivo = "fonte ${NOMES[idx]} não gravou"
                return null
            }
            aberturas++
            fonteAtual = NOMES[idx]
            NLog.add("ia", "escuta: microfone próprio aberto (fonte ${NOMES[idx]})")
            return r
        } catch (t: Throwable) {
            ultimoMotivo = "microfone: ${t.javaClass.simpleName}"
            return null
        }
    }

    private fun laco(minha: Int, l: Ouvinte) {
        var idx = Prefs.lerNum("ia_fonte2", 0L).toInt().coerceIn(0, FONTES.size - 1)
        var falhasAbrir = 0
        var mudosSeguidos = 0
        var errosSeguidos = 0
        var avisou = false
        while (minha == geracao) {
            val ar = abrir(idx)
            if (ar == null) {
                falhasAbrir++
                idx = (idx + 1) % FONTES.size
                if (falhasAbrir >= FONTES.size && !avisou) {
                    avisou = true
                    val m = ultimoMotivo
                    main.post { if (minha == geracao) l.falhou(m.ifBlank { "o microfone não abriu" }) }
                }
                dorme(if (falhasAbrir >= FONTES.size) 2000L else 300L)
                continue
            }
            falhasAbrir = 0
            var r = ERRO
            try {
                r = ler(ar, idx, minha, l)
            } catch (t: Throwable) {
                ultimoMotivo = "leitura: ${t.javaClass.simpleName}"
            } finally {
                try { ar.stop() } catch (_: Throwable) {}
                try { ar.release() } catch (_: Throwable) {}
            }
            if (minha != geracao) break
            when (r) {
                SOM -> { mudosSeguidos = 0; errosSeguidos = 0; avisou = false; dorme(300L) }
                MUDO -> {
                    // essa fonte só manda silêncio: tenta a próxima
                    mudosSeguidos++
                    trocasFonte++
                    NLog.add("ia", "escuta: fonte ${NOMES[idx]} só mandou silêncio; troquei")
                    idx = (idx + 1) % FONTES.size
                    if (mudosSeguidos >= FONTES.size && !avisou) {
                        avisou = true
                        main.post { if (minha == geracao) l.falhou("o microfone só manda silêncio") }
                    }
                    dorme(200L)
                }
                else -> {
                    errosSeguidos++
                    if (errosSeguidos >= 3) { idx = (idx + 1) % FONTES.size; errosSeguidos = 0 }
                    dorme(500L)
                }
            }
        }
    }

    private fun dorme(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** lê o microfone e separa as falas. Devolve SOM (parou depois de ouvir som), MUDO (só silêncio) ou ERRO (a leitura falhou) */
    private fun ler(ar: AudioRecord, idx: Int, minha: Int, l: Ouvinte): Int {
        val pcm = ShortArray(QUADRO)
        val pre = ArrayDeque<ShortArray>()
        val saida = ByteArrayOutputStream(64 * 1024)
        var piso = -60f                // barulho de fundo (dB): medido ao abrir, depois sobe devagar e desce depressa
        val medidas = FloatArray(CALIBRA)
        var nMed = 0
        var emFala = false
        var recentes = 0               // últimos 6 quadros: 1 = tinha voz
        var vozTotal = 0
        var quadrosFala = 0
        var silencio = 0
        var somaDb = 0.0
        var somaDb2 = 0.0
        var nDb = 0
        var zeros = 0
        var chegouSom = false
        var n = 0
        val dbs = FloatArray(MAX_QUADROS + 8)   // volume de cada quadro da fala em andamento (pra conferir se era fala ou só barulho)
        var nDbs = 0
        var ref = -90f                 // nível da sua voz nesta fala: sobe depressa com as sílabas fortes, desce bem devagar
        val anel = FloatArray(JANELA_CHAO)   // volume suavizado dos últimos 3 s
        var nAnel = 0
        var suave = -90f

        fun zera() { emFala = false; recentes = 0; vozTotal = 0; quadrosFala = 0; silencio = 0; somaDb = 0.0; somaDb2 = 0.0; nDb = 0; nDbs = 0; ref = -90f; saida.reset(); pre.clear() }

        fun fecha() {
            val cortar = maxOf(0, silencio - MANTEM_FIM) * QUADRO * 2       // tira o silêncio do final (deixa 500 ms)
            val bytes = saida.toByteArray()
            val tam = maxOf(0, bytes.size - cortar)
            val voz = vozTotal
            zera()
            if (voz < MIN_VOZ || tam < QUADRO * 2 * MIN_VOZ) { ruidos++; return }
            val wav = montaWav(bytes, tam)
            val seg = tam / 2f / HZ
            segmentos++
            if (minha == geracao) main.post { if (minha == geracao) l.fala(wav, seg) }
        }

        while (minha == geracao) {
            var off = 0
            while (off < QUADRO && minha == geracao) {
                val k = ar.read(pcm, off, QUADRO - off)
                if (k <= 0) {
                    // erro de leitura (-6 = o Android tirou o microfone de nós): sai pra abrir de novo
                    ultimoMotivo = "leitura do microfone falhou ($k)"
                    NLog.add("ia", "escuta: leitura do microfone falhou ($k)")
                    return if (chegouSom) SOM else ERRO
                }
                off += k
            }
            if (minha != geracao) break

            var soma = 0.0
            var maxAbs = 0
            for (i in 0 until QUADRO) {
                val v = pcm[i].toInt()
                soma += v.toDouble() * v
                val a = abs(v)
                if (a > maxAbs) maxAbs = a
            }
            val rms = sqrt(soma / QUADRO)
            val db = if (rms < 1.0) -90f else (20.0 * log10(rms / 32768.0)).toFloat()
            quadrosLidos++
            if (db > maxDb) maxDb = db

            // silêncio absoluto logo ao abrir = essa fonte de som está muda: sai pra trocar de fonte
            if (maxAbs <= 1) {
                zeros++
                if (!chegouSom && zeros >= MUDO_QUADROS) return MUDO
            } else {
                zeros = 0
                quadrosComSom++
                if (!chegouSom) {
                    chegouSom = true
                    Prefs.gravaNum("ia_fonte2", idx.toLong())
                    main.post { if (minha == geracao) l.pronto() }
                }
            }

            val quieto = l.mudo()
            n++
            if (n % 3 == 0) {
                val nv = if (quieto) 0f else ((db + 60f) / 40f).coerceIn(0f, 1f)
                main.post { if (minha == geracao) l.nivel(nv) }
            }

            // ela pensando/falando/escrevendo: joga o som fora e recomeça do zero
            if (quieto) { if (emFala || pre.isNotEmpty()) zera(); continue }

            // chão de barulho: volume (suavizado) mais baixo dos últimos 3 s. Sobe sozinho se o ambiente ficar mais barulhento
            suave = if (nAnel == 0) db else suave + (db - suave) * 0.35f
            anel[nAnel % JANELA_CHAO] = suave
            nAnel++
            var chao = suave
            for (i in 0 until minOf(nAnel, JANELA_CHAO)) if (anel[i] < chao) chao = anel[i]
            chao = chao.coerceIn(-75f, -20f)

            // primeiros 0,8 s: só mede o barulho de fundo (a menor média de 5 quadros) e guarda o som pra não cortar o começo
            if (nMed < CALIBRA) {
                medidas[nMed++] = db
                pre.addLast(pcm.copyOf())
                if (pre.size > PRE_ROLL) pre.removeFirst()
                if (nMed == CALIBRA) {
                    var menor = 0f
                    var achou = false
                    var i = 0
                    while (i + 5 <= CALIBRA) {
                        var s = 0f
                        for (j in i until i + 5) s += medidas[j]
                        val m = s / 5f
                        if (!achou || m < menor) { menor = m; achou = true }
                        i += 5
                    }
                    piso = menor.coerceIn(-75f, -38f)
                    pisoDb = piso
                }
                continue
            }

            if (!emFala) {
                // o barulho de fundo desce depressa e sobe devagar (uma fala não vira "barulho")
                piso += (db - piso) * (if (db < piso) 0.25f else 0.004f)
                piso = piso.coerceIn(-75f, -38f)
                pisoDb = piso
            }
            val limiar = maxOf(piso + 12f, LIMIAR_MIN)
            val voz = db > (if (emFala) limiar - 4f else maxOf(limiar, chao + 8f))
            recentes = ((recentes shl 1) or (if (voz) 1 else 0)) and 0x3F

            if (!emFala) {
                pre.addLast(pcm.copyOf())
                if (pre.size > PRE_ROLL) pre.removeFirst()
                if (recentes.countOneBits() >= 4) {
                    emFala = true
                    vozTotal = recentes.countOneBits()
                    silencio = 0
                    quadrosFala = pre.size
                    for (q in pre) escreve(saida, q)
                    pre.clear()
                    somaDb = db.toDouble(); somaDb2 = db.toDouble() * db; nDb = 1
                    nDbs = 0; ref = db
                    main.post { if (minha == geracao) l.vozDetectada() }
                }
            } else {
                escreve(saida, pcm)
                quadrosFala++
                somaDb += db; somaDb2 += db.toDouble() * db; nDb++
                if (nDbs < dbs.size) dbs[nDbs++] = db
                // nível da sua voz: sobe com as sílabas fortes e desce devagar (0,5 dB por segundo)
                if (db > ref) ref += minOf(db - ref, 3f) * 0.3f else ref -= 0.01f
                // quieto = abaixo do limiar OU bem abaixo da sua própria voz (o chiado do microfone aparece quando você para de falar
                // e antes ele ficava "acima do limiar" pra sempre: a fala nunca terminava e ia até 14 s)
                // e também quando o volume (suavizado) está perto do chão de barulho: um chiado ou ventilador fraco e constante
                // que fica por cima do limiar não segura mais a frase (antes ele fazia a fala ir até os 14 s e juntar com a próxima)
                val tetoQuieto = minOf(chao + FOLGA_CHAO, ref - 14f)
                val quietoAgora = !voz || db < ref - FIM_RELATIVO || suave < tetoQuieto
                if (!quietoAgora) { vozTotal++; silencio = 0 } else silencio++
                // 3 s seguidos de "voz" com volume quase constante = é barulho (ventilador, chiado), não fala: descarta
                if (quadrosFala == 150) {
                    val media = somaDb / nDb
                    val desvio = sqrt(maxOf(0.0, somaDb2 / nDb - media * media))
                    if (desvio < 2.5 && silencio == 0) {
                        piso = (media.toFloat() - 4f).coerceIn(-75f, -30f)
                        pisoDb = piso
                        ruidos++
                        zera()
                        continue
                    }
                }
                // começou "fala" mas a voz nunca passou bem do limiar em mais de 1 s: era chiado. Descarta e sobe o piso pro chiado não disparar de novo
                if (quadrosFala == 80 && ref < limiar + 8f) {
                    piso = maxOf(piso, (somaDb / nDb).toFloat() - 2f).coerceIn(-75f, -38f)
                    pisoDb = piso
                    ruidos++
                    zera()
                    continue
                }
                if (silencio >= FIM_SILENCIO) {
                    porSilencio++
                    fecha()
                } else if (quadrosFala >= MAX_QUADROS) {
                    // chegou no limite sem achar o fim da frase: confere se era fala de verdade ou só barulho contínuo
                    porLimite++
                    val ord = dbs.copyOf(nDbs)
                    ord.sort()
                    val p10 = ord[nDbs / 10]
                    val p90 = ord[(nDbs * 9) / 10]
                    ultP10 = Math.round(p10); ultP90 = Math.round(p90)
                    if (p90 - p10 < 15f) {
                        piso = p90.coerceIn(-75f, -30f)
                        pisoDb = piso
                        ruidos++
                        zera()
                        continue
                    }
                    // tinha fala, mas os trechos quietos ficaram acima do limiar: sobe o piso pra próxima frase terminar sozinha
                    if (p10 > limiar - 4f) { piso = (p10 - 6f).coerceIn(-75f, -38f); pisoDb = piso }
                    fecha()
                }
            }
        }
        return if (chegouSom) SOM else ERRO
    }

    private fun escreve(o: ByteArrayOutputStream, q: ShortArray) {
        val b = ByteArray(q.size * 2)
        for (i in q.indices) {
            val v = q[i].toInt()
            b[i * 2] = v.toByte()
            b[i * 2 + 1] = (v shr 8).toByte()
        }
        o.write(b, 0, b.size)
    }

    private fun montaWav(pcm: ByteArray, len: Int): ByteArray {
        val out = ByteArray(44 + len)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0)
        i32(4, 36 + len)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, HZ); i32(28, HZ * 2); i16(32, 2); i16(34, 16)
        "data".toByteArray().copyInto(out, 36)
        i32(40, len)
        pcm.copyInto(out, 44, 0, len)
        return out
    }

    // ------------------------------------------------------------------ escrever o que foi dito

    /** manda o áudio pro servidor do NOCRY e devolve o texto. Chame fora da tela. */
    fun transcrever(c: Context, wav: ByteArray, seg: Float, dica: String): Texto {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val t0 = System.currentTimeMillis()
        val corpo = JSONObject().put("audio", b64).put("seg", seg.toDouble()).put("dica", dica.take(200))
        // o normal é 1,5 a 4 s. Se travar, não fica 25 s esperando (e com o microfone mudo): corta cedo e tenta mais uma vez
        val leitura = (9000 + seg * 600f).toInt().coerceIn(9000, 18000)
        var j: JSONObject? = null
        for (tentativa in 1..2) {
            j = IA.post(c, "ouvir", corpo, leitura, 5000)
            val rr = j?.optString("reason") ?: ""
            val passageiro = j == null || (!j.optBoolean("ok") && (rr == "stt_erro" || rr == "server"))
            if (!passageiro) break
            if (tentativa == 1) NLog.add("ia", "escuta: o servidor demorou ou falhou, tentando de novo")
        }
        if (j == null) {
            ultimoMotivo = "servidor não respondeu"
            NLog.add("ia", "escuta: o servidor não respondeu")
            return Texto(null, "rede")
        }
        if (!j.optBoolean("ok")) {
            val r = j.optString("reason")
            ultimoMotivo = "servidor: $r"
            NLog.add("ia", "escuta: servidor não escreveu a fala ($r)")
            // erro passageiro do servidor (ou muitos pedidos de uma vez) não pausa o ouvido novo: é só falar de novo
            if (r == "stt_erro" || r == "server" || r == "limit") return Texto(null, "rede")
            return Texto(null, if (r == "stt_limite") "limite" else "indisponivel")
        }
        val t = j.optString("texto").trim()
        val fonte = j.optString("fonte").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        NLog.add("ia", "escuta: ${"%.1f".format(seg)}s de fala escritos em ${System.currentTimeMillis() - t0} ms$fonte")
        return Texto(t, "")
    }

    private val LIXO = listOf("amara org", "legendas pela comunidade", "legenda pela comunidade", "inscreva se", "se inscreva", "obrigado por assistir",
        "obrigada por assistir", "thanks for watching", "subtitles by", "transcricao e legendas")
    private val CURTAS = setOf("obrigado", "obrigada", "tchau", "valeu", "ok", "tchau tchau", "ate a proxima")

    /** o Whisper, quando ouve só ruído, às vezes "escreve" frases de fim de vídeo ou repete a dica: isso não é você falando */
    fun lixo(texto: String, seg: Float, dica: String): Boolean {
        val t = PalavrasChave.limpa(texto)
        if (t.isEmpty()) return true
        if (LIXO.any { t.contains(it) }) return true
        // "obrigado" / "tchau" saindo de um estalo de menos de 1 s
        if (seg < 1.2f && t in CURTAS) return true
        val ws = t.split(" ")
        // a mesma palavra repetida várias vezes ("a a a a a")
        if (ws.size >= 4 && ws.toSet().size == 1) return true
        // repetiu a própria dica (quando só tem ruído, o Whisper às vezes devolve o texto de apoio)
        val d = PalavrasChave.limpa(dica)
        if (ws.size >= 3 && (d.contains(t) || ws.count { it in d.split(" ") } >= ws.size * 0.8)) return true
        return false
    }
}
