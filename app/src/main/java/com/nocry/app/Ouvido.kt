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
 *  - Enquanto ela pensa ou fala ([mudo]), o som é jogado fora (ela não escuta a própria voz).
 *  - O áudio só sai do celular quando você realmente falou, e não é guardado em lugar nenhum.
 */
object Ouvido {
    interface Ouvinte {
        /** chegou som de verdade do microfone */
        fun pronto()
        /** volume de agora, de 0 a 1 (pra luz da tela) */
        fun nivel(n: Float)
        /** você terminou de falar: áudio em WAV (16 kHz, mono) e a duração em segundos */
        fun fala(wav: ByteArray, seg: Float)
        /** o microfone não abriu ou ficou mudo (o Android não deixou) */
        fun falhou(motivo: String)
        /** true enquanto ela pensa, fala ou está escrevendo o que você disse: o som é jogado fora */
        fun mudo(): Boolean
    }

    /** resultado de escrever a fala. texto == null: não deu; [motivo] diz por quê (limite, rede, indisponivel) */
    class Texto(val texto: String?, val motivo: String)

    private const val HZ = 16000
    private const val QUADRO = 320            // 20 ms de som
    private const val PRE_ROLL = 20           // guarda 400 ms antes de você começar (pra não cortar a primeira palavra)
    private const val FIM_SILENCIO = 45       // 900 ms quieto = terminou a frase
    private const val MAX_QUADROS = 700       // frase mais longa: 14 s
    private const val MIN_VOZ = 12            // menos de 240 ms de voz = estalo, não é fala
    private const val MUDO_QUADROS = 250      // 5 s de silêncio absoluto = o Android silenciou o microfone

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var geracao = 0
    @Volatile private var fio: Thread? = null

    val rodando: Boolean get() = fio?.isAlive == true

    /** abre o microfone e começa a separar as falas. Pode chamar de novo: se já está rodando, não faz nada */
    fun iniciar(c: Context, l: Ouvinte): Boolean {
        if (rodando) return true
        val minha = ++geracao
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
    private fun abrir(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val buf = maxOf(min * 2, QUADRO * 2 * 16)
        // VOICE_RECOGNITION costuma vir já limpo pra voz; se o celular não aceitar, o microfone comum
        for (fonte in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(fonte, HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording()
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        NLog.add("ia", "escuta: microfone próprio aberto (fonte " + (if (fonte == MediaRecorder.AudioSource.MIC) "comum" else "de voz") + ")")
                        return r
                    }
                }
                r.release()
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun laco(minha: Int, l: Ouvinte) {
        var aberturasSeguidas = 0
        var primeiroSom = true
        while (minha == geracao) {
            val ar = abrir()
            if (ar == null) {
                aberturasSeguidas++
                if (aberturasSeguidas >= 3) { main.post { if (minha == geracao) l.falhou("o microfone não abriu") }; break }
                dorme(700)
                continue
            }
            aberturasSeguidas = 0
            try {
                lerAte(ar, minha, l) { primeiroSom }.also { if (it) primeiroSom = false }
            } finally {
                try { ar.stop() } catch (_: Throwable) {}
                try { ar.release() } catch (_: Throwable) {}
            }
            // saiu do laço de leitura por erro (outro app pegou o microfone, fone ligou/desligou...): espera um pouco e abre de novo
            if (minha == geracao) dorme(500)
        }
    }

    private fun dorme(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** lê o microfone e separa as falas. Devolve true se chegou som de verdade alguma vez */
    private fun lerAte(ar: AudioRecord, minha: Int, l: Ouvinte, esperandoPrimeiro: () -> Boolean): Boolean {
        val pcm = ShortArray(QUADRO)
        val pre = ArrayDeque<ShortArray>()
        val saida = ByteArrayOutputStream(64 * 1024)
        var piso = -55f                // barulho de fundo (dB): sobe devagar e desce depressa
        var emFala = false
        var recentes = 0               // últimos 6 quadros: 1 = tinha voz
        var vozTotal = 0
        var quadrosFala = 0
        var silencio = 0
        var zeros = 0
        var avisouMudo = false
        var chegouSom = false
        var n = 0

        fun zera() { emFala = false; recentes = 0; vozTotal = 0; quadrosFala = 0; silencio = 0; saida.reset(); pre.clear() }

        fun fecha() {
            val cortar = maxOf(0, silencio - 15) * QUADRO * 2       // tira o silêncio do final (deixa 300 ms)
            val bytes = saida.toByteArray()
            val tam = maxOf(0, bytes.size - cortar)
            val voz = vozTotal
            zera()
            if (voz < MIN_VOZ || tam < QUADRO * 2 * MIN_VOZ) return
            val wav = montaWav(bytes, tam)
            val seg = tam / 2f / HZ
            if (minha == geracao) main.post { if (minha == geracao) l.fala(wav, seg) }
        }

        while (minha == geracao) {
            var off = 0
            while (off < QUADRO && minha == geracao) {
                val k = ar.read(pcm, off, QUADRO - off)
                if (k <= 0) {
                    // erro de leitura (-6 = o Android tirou o microfone de nós): sai pra abrir de novo
                    NLog.add("ia", "escuta: leitura do microfone falhou ($k)")
                    return chegouSom
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

            // silêncio absoluto por vários segundos = o Android silenciou o microfone (segundo plano sem permissão, chave de privacidade)
            if (maxAbs == 0) {
                zeros++
                if (zeros >= MUDO_QUADROS && !avisouMudo) {
                    avisouMudo = true
                    main.post { if (minha == geracao) l.falhou("o Android deixou o microfone mudo") }
                }
            } else {
                zeros = 0
                avisouMudo = false
                if (!chegouSom) { chegouSom = true; if (esperandoPrimeiro()) main.post { if (minha == geracao) l.pronto() } }
            }

            n++
            val quieto = l.mudo()
            if (n % 3 == 0) {
                val nv = if (quieto) 0f else ((db + 60f) / 40f).coerceIn(0f, 1f)
                main.post { if (minha == geracao) l.nivel(nv) }
            }

            // ela pensando/falando/escrevendo: joga o som fora e recomeça do zero
            if (quieto) { if (emFala || pre.isNotEmpty()) zera(); continue }

            if (!emFala) {
                // o barulho de fundo desce depressa e sobe devagar (uma fala não vira "barulho")
                piso += (db - piso) * (if (db < piso) 0.25f else 0.003f)
                piso = piso.coerceIn(-75f, -38f)
            }
            val limiar = maxOf(piso + 11f, -48f)
            val voz = db > (if (emFala) limiar - 4f else limiar)
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
                }
            } else {
                escreve(saida, pcm)
                quadrosFala++
                if (voz) { vozTotal++; silencio = 0 } else silencio++
                if (silencio >= FIM_SILENCIO || quadrosFala >= MAX_QUADROS) fecha()
            }
        }
        return chegouSom
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
        val j = IA.post(c, "ouvir", corpo, 25000) ?: return Texto(null, "rede")
        if (!j.optBoolean("ok")) {
            val r = j.optString("reason")
            NLog.add("ia", "escuta: servidor não escreveu a fala ($r)")
            return Texto(null, if (r == "stt_limite") "limite" else "indisponivel")
        }
        val t = j.optString("texto").trim()
        NLog.add("ia", "escuta: ${"%.1f".format(seg)}s de fala escritos em ${System.currentTimeMillis() - t0} ms")
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
