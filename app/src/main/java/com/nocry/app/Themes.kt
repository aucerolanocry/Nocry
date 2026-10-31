package com.nocry.app

/** Pacote de fonte: uma fonte pros titulos/rotulos (display) e outra pro texto corrido (body). */
class Pack(
    val id: String,
    val nome: String,
    val disp: String,
    val dispB: String,
    val body: String,
    val bodyB: String,
    /** corrige o tamanho aparente (fontes estreitas ficam menores que as normais) */
    val escala: Float,
    /** espacamento entre letras dos titulos, em em */
    val esp: Float,
    /** fonte variavel (peso escolhido por 'wght') */
    val variavel: Boolean = false,
    val sistema: Boolean = false
)

object Packs {
    val lista = listOf(
        Pack("bigs", "Big Shoulders", "BigShoulders-Regular.ttf", "BigShoulders-Bold.ttf", "BigShoulders-Regular.ttf", "BigShoulders-Bold.ttf", 1.28f, 0.06f),
        Pack("tektur", "Tektur", "Tektur-Regular.ttf", "Tektur-Medium.ttf", "Tektur-Regular.ttf", "Tektur-Medium.ttf", 1.0f, 0.08f),
        Pack("outfit", "Outfit", "Outfit-Regular.ttf", "Outfit-Bold.ttf", "Outfit-Regular.ttf", "Outfit-Bold.ttf", 1.04f, 0.05f),
        Pack("bricolage", "Bricolage", "Bricolage-Regular.ttf", "Bricolage-Bold.ttf", "Bricolage-Regular.ttf", "Bricolage-Bold.ttf", 1.0f, 0.04f),
        Pack("gloock", "Gloock", "Gloock-Regular.ttf", "Gloock-Regular.ttf", "WorkSans-Regular.ttf", "WorkSans-Bold.ttf", 1.0f, 0.06f),
        Pack("poppins", "Poppins", "Poppins-Regular.ttf", "Poppins-Bold.ttf", "Poppins-Regular.ttf", "Poppins-Bold.ttf", 0.98f, 0.04f),
        Pack("instrument", "Instrument", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", 1.02f, 0.06f),
        Pack("pixelify", "Pixelify", "PixelifySans-Medium.ttf", "PixelifySans-Medium.ttf", "RedHatMono-Regular.ttf", "RedHatMono-Bold.ttf", 1.08f, 0.06f),
        Pack("grotesk", "Space Grotesk", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", 1.0f, 0.06f, variavel = true),
        Pack("mono", "JetBrains Mono", "JetBrainsMono.ttf", "JetBrainsMono.ttf", "JetBrainsMono.ttf", "JetBrainsMono.ttf", 0.96f, 0.04f, variavel = true),
        Pack("sistema", "Sistema", "", "", "", "", 1.0f, 0.04f, sistema = true)
    )

    fun existe(id: String): Boolean = lista.any { it.id == id }
    fun get(id: String): Pack = lista.firstOrNull { it.id == id } ?: lista[0]
}

/** Tema pronto: um conjunto de cores, formato e fonte que o usuario aplica com 1 toque. */
class Tema(
    val id: String,
    val nome: String,
    val tag: String,
    val c1: Int,
    val c2: Int,
    val base: Int,
    val cartao: String,
    val fonte: String,
    val raio: Int,
    val borda: Int,
    val brilho: Int,
    val vidro: Int,
    val fundo: String,
    val scanner: Boolean = false
)

object Temas {
    val FUNDOS = listOf("brasas", "grade", "aurora", "sol", "chuva", "neve", "petalas", "ouro", "noite")

    fun nomeFundo(id: String): String = when (id) {
        "brasas" -> "Brasas"
        "grade" -> "Grade"
        "aurora" -> "Aurora"
        "sol" -> "Sol"
        "chuva" -> "Chuva"
        "neve" -> "Neve"
        "petalas" -> "Pétalas"
        "ouro" -> "Ouro"
        else -> "Noite"
    }

    val lista = listOf(
        Tema("brasa", "Brasa", "Gamer agressivo", 0xFFFF2D55.toInt(), 0xFFFF8A1F.toInt(), 0xFF0A0506.toInt(), "hud", "bigs", 2, 2, 55, 78, "brasas"),
        Tema("cyber", "Cyber", "Neon HUD", 0xFF22E4FF.toInt(), 0xFFA66BFF.toInt(), 0xFF05060F.toInt(), "contorno", "tektur", 10, 1, 70, 70, "grade", true),
        Tema("aurora", "Aurora", "Vidro premium", 0xFF3DDC84.toInt(), 0xFF4D7CFF.toInt(), 0xFF05080D.toInt(), "vidro", "outfit", 24, 1, 50, 55, "aurora"),
        Tema("synth", "Synthwave", "Retrô anos 80", 0xFFFF3CAC.toInt(), 0xFFFFB020.toInt(), 0xFF0D0221.toInt(), "vidro", "bricolage", 20, 1, 65, 55, "sol"),
        Tema("ouro", "Ouro", "Luxo escuro", 0xFFF6D27A.toInt(), 0xFFC8872B.toInt(), 0xFF0B0805.toInt(), "solido", "gloock", 14, 1, 35, 92, "ouro"),
        Tema("matrix", "Matrix", "Terminal hacker", 0xFF3DFF7A.toInt(), 0xFF0FA64A.toInt(), 0xFF020A04.toInt(), "contorno", "pixelify", 2, 1, 60, 70, "chuva", true),
        Tema("sakura", "Sakura", "Suave e arredondado", 0xFFFF8FC7.toInt(), 0xFFB48CFF.toInt(), 0xFF110A18.toInt(), "vidro", "poppins", 30, 1, 50, 55, "petalas"),
        Tema("gelo", "Gelo", "Minimal prata", 0xFFE8F1FF.toInt(), 0xFF7FC8FF.toInt(), 0xFF070A0F.toInt(), "vidro", "instrument", 20, 1, 40, 55, "neve")
    )

    fun get(id: String): Tema = lista.firstOrNull { it.id == id } ?: lista[0]

    /** aplica o tema: cores, formato, fonte e fundo animado. Video/imagem escolhidos pelo usuario continuam. */
    fun aplica(t: Tema) {
        Prefs.tema = t.id
        Prefs.accent = t.c1
        Prefs.accent2 = t.c2
        Prefs.baseCor = t.base
        Prefs.angulo = 135
        Prefs.cartao = t.cartao
        Prefs.fonte = t.fonte
        Prefs.raio = t.raio
        Prefs.borda = t.borda
        Prefs.brilho = t.brilho
        Prefs.vidro = t.vidro
        Prefs.bgPreset = t.fundo
        Prefs.scanner = t.scanner
        if (Prefs.bgTipo == "solido") Prefs.bgTipo = "animado"
    }

    /** par de cores (cor 1, cor 2) pra escolher rapido */
    val GRADIENTES: List<Pair<Int, Int>> = lista.map { it.c1 to it.c2 } + listOf(
        0xFF22E4FF.toInt() to 0xFF3DDC84.toInt(),
        0xFFFFB020.toInt() to 0xFFFF2D55.toInt(),
        0xFFA66BFF.toInt() to 0xFF22E4FF.toInt(),
        0xFFF2F2F6.toInt() to 0xFF8A8A9A.toInt()
    )
}
