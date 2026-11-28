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
        // titulos na Big Shoulders; texto corrido na Rajdhani: a Big Shoulders e estreita demais pra paragrafo
        // em caixa alta (cansava e parecia tudo espremido), a Rajdhani e mais larga e continua com cara de jogo
        Pack("bigs", "Big Shoulders", "BigShoulders-Regular.ttf", "BigShoulders-Bold.ttf", "Rajdhani-SemiBold.ttf", "Rajdhani-Bold.ttf", 1.28f, 0.06f),
        Pack("russo", "Russo + Exo", "RussoOne-Regular.ttf", "RussoOne-Regular.ttf", "Exo2.ttf", "Exo2.ttf", 0.96f, 0.04f, variavel = true),
        Pack("orbitron", "Orbitron", "Orbitron.ttf", "Orbitron.ttf", "Oxanium.ttf", "Oxanium.ttf", 0.92f, 0.06f, variavel = true),
        Pack("tektur", "Tektur", "Tektur-Regular.ttf", "Tektur-Medium.ttf", "Tektur-Regular.ttf", "Tektur-Medium.ttf", 1.0f, 0.08f),
        Pack("outfit", "Outfit", "Outfit-Regular.ttf", "Outfit-Bold.ttf", "Outfit-Regular.ttf", "Outfit-Bold.ttf", 1.04f, 0.05f),
        Pack("bricolage", "Bricolage", "Bricolage-Regular.ttf", "Bricolage-Bold.ttf", "Bricolage-Regular.ttf", "Bricolage-Bold.ttf", 1.0f, 0.04f),
        Pack("gloock", "Gloock", "Gloock-Regular.ttf", "Gloock-Regular.ttf", "WorkSans-Regular.ttf", "WorkSans-Bold.ttf", 1.0f, 0.06f),
        Pack("poppins", "Poppins", "Poppins-Regular.ttf", "Poppins-Bold.ttf", "Poppins-Regular.ttf", "Poppins-Bold.ttf", 0.98f, 0.04f),
        Pack("instrument", "Instrument", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", 1.02f, 0.06f),
        // titulos na Pixelify; corpo (explicacoes, descricoes) na Instrument Sans - em caixa alta e
        // tamanho pequeno ela fica mais limpa e legivel que a Red Hat Mono (pesada) e a Outfit
        // (geometrica demais, letras apertadas). Escala 0.88 reduz o app todo de uma vez.
        Pack("pixelify", "Pixelify", "PixelifySans-Medium.ttf", "PixelifySans-Medium.ttf", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", 0.88f, 0.06f),
        Pack("grotesk", "Space Grotesk", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", "SpaceGrotesk.ttf", 1.0f, 0.06f, variavel = true),
        Pack("mono", "JetBrains Mono", "JetBrainsMono.ttf", "JetBrainsMono.ttf", "JetBrainsMono.ttf", "JetBrainsMono.ttf", 0.96f, 0.04f, variavel = true),
        Pack("sistema", "Sistema", "", "", "", "", 1.0f, 0.04f, sistema = true)

    ) + (if (BuildConfig.PREVIEW) listOf(
        // visual HUD (só na prévia): títulos Oxanium (geométrica, de painel) e texto Instrument Sans (limpo, fácil de ler)
        Pack("hud", "Oxanium + Instrument", "Oxanium.ttf", "Oxanium.ttf", "InstrumentSans-Regular.ttf", "InstrumentSans-Bold.ttf", 0.98f, 0.08f, variavel = true)
    ) else emptyList())

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
    val scanner: Boolean = false,
    /** efeito animado nos cartões (Efeitos.kt); "" = nenhum */
    val efeito: String = "",
    /** desfoque do fundo (-1 = deixa como a pessoa escolheu) */
    val blur: Int = -1
)

object Temas {
    val FUNDOS = listOf("brasas", "grade", "aurora", "sol", "chuva", "neve", "petalas", "ouro", "noite", "inferno", "sangue", "caveiras", "tempestade", "toxico", "vazio") +
        (if (BuildConfig.PREVIEW) Hud.FUNDOS else emptyList())

    fun nomeFundo(id: String): String = when (id) {
        "brasas" -> "Brasas"
        "grade" -> "Grade"
        "aurora" -> "Aurora"
        "sol" -> "Sol"
        "chuva" -> "Chuva"
        "neve" -> "Neve"
        "petalas" -> "Pétalas"
        "ouro" -> "Ouro"
        "caveiras" -> "Caveiras"
        "sangue" -> "Sangue"
        "inferno" -> "Inferno"
        "tempestade" -> "Tempestade"
        "toxico" -> "Tóxico"
        "vazio" -> "Vazio"
        "holo" -> "Holograma"
        "reator" -> "Reator"
        "radar" -> "Radar"
        "plasma" -> "Plasma"
        else -> "Noite"
    }

    /** visual HUD futurista (painel de IA): o dono não gostou (1.0.151), fica só na prévia do CI */
    private val HUD: List<Tema> = if (!BuildConfig.PREVIEW) emptyList() else listOf(
        Tema("x_holo", "Holograma", "Painel azul de IA, anéis girando", 0xFF38E1FF.toInt(), 0xFF2E6BFF.toInt(), 0xFF03070D.toInt(), "hudx", "hud", 4, 1, 60, 80, "holo", blur = 0),
        Tema("x_reator", "Reator", "Vermelho e dourado, núcleo de energia", 0xFFFF3B47.toInt(), 0xFFFFB648.toInt(), 0xFF0B0405.toInt(), "hudx", "hud", 4, 1, 60, 80, "reator", blur = 0),
        Tema("x_comando", "Comando", "Radar verde de central tática", 0xFF3DFFA0.toInt(), 0xFF2BD9C5.toInt(), 0xFF020A06.toInt(), "hudx", "hud", 4, 1, 55, 80, "radar", blur = 0),
        Tema("x_plasma", "Plasma", "Ondas roxas de energia", 0xFFB070FF.toInt(), 0xFFFF4FD8.toInt(), 0xFF07040F.toInt(), "hudx", "hud", 4, 1, 65, 80, "plasma", blur = 0)
    )

    val lista = HUD + listOf(
        // o visual principal do NOCRY: vidro claro, cantos redondos, botoes em pilula e luzes azul/roxa no fundo
        Tema("vidro", "Vidro", "O visual principal do NOCRY", 0xFFFFFFFF.toInt(), 0xFFDCE4FF.toInt(), 0xFF06070C.toInt(), "vidro", "bigs", 22, 1, 35, 55, "noite", blur = 8),
        // o visual de antes do Vidro: reto, prata e com os cantos acesos
        Tema("prata", "HUD prata", "Reto e prata, o visual de antes", 0xFFFFFFFF.toInt(), 0xFFB8B8C4.toInt(), 0xFF050507.toInt(), "contorno", "bigs", 2, 1, 60, 70, "chuva", blur = 0),
        Tema("neon", "Neon", "Ciano e rosa, cara de PC gamer", 0xFF22E4FF.toInt(), 0xFFFF3CAC.toInt(), 0xFF05040F.toInt(), "contorno", "orbitron", 12, 1, 85, 70, "grade", true),
        Tema("militar", "Militar", "Verde-oliva e areia", 0xFFD9C89A.toInt(), 0xFF7C8A4A.toInt(), 0xFF0A0B06.toInt(), "solido", "russo", 4, 1, 25, 85, "noite"),
        Tema("brasa", "Brasa", "Gamer agressivo", 0xFFFF2D55.toInt(), 0xFFFF8A1F.toInt(), 0xFF0A0506.toInt(), "hud", "bigs", 2, 2, 55, 78, "brasas"),
        Tema("cyber", "Cyber", "Neon HUD", 0xFF22E4FF.toInt(), 0xFFA66BFF.toInt(), 0xFF05060F.toInt(), "contorno", "tektur", 10, 1, 70, 70, "grade", true),
        Tema("aurora", "Aurora", "Vidro verde e azul", 0xFF3DDC84.toInt(), 0xFF4D7CFF.toInt(), 0xFF05080D.toInt(), "vidro", "outfit", 24, 1, 50, 55, "aurora"),
        Tema("synth", "Synthwave", "Retrô anos 80", 0xFFFF3CAC.toInt(), 0xFFFFB020.toInt(), 0xFF0D0221.toInt(), "vidro", "bricolage", 20, 1, 65, 55, "sol"),
        Tema("ouro", "Ouro", "Luxo escuro", 0xFFF6D27A.toInt(), 0xFFC8872B.toInt(), 0xFF0B0805.toInt(), "solido", "gloock", 14, 1, 35, 92, "ouro"),
        Tema("matrix", "Matrix", "Terminal hacker", 0xFF3DFF7A.toInt(), 0xFF0FA64A.toInt(), 0xFF020A04.toInt(), "contorno", "pixelify", 2, 1, 60, 70, "chuva", true),
        Tema("sakura", "Sakura", "Suave e arredondado", 0xFFFF8FC7.toInt(), 0xFFB48CFF.toInt(), 0xFF110A18.toInt(), "vidro", "poppins", 30, 1, 50, 55, "petalas"),
        Tema("gelo", "Gelo", "Minimal prata", 0xFFE8F1FF.toInt(), 0xFF7FC8FF.toInt(), 0xFF070A0F.toInt(), "vidro", "instrument", 20, 1, 40, 55, "neve"),
        Tema("grafite", "Grafite", "Corporativo, clean", 0xFF9FB4C7.toInt(), 0xFF3E6FA8.toInt(), 0xFF0A0C10.toInt(), "contorno", "instrument", 16, 1, 35, 45, "noite"),
        Tema("platina", "Platina", "Executivo, elegante", 0xFFE4E4EC.toInt(), 0xFF7B6CFF.toInt(), 0xFF08090D.toInt(), "vidro", "outfit", 20, 1, 30, 45, "grade"),
        Tema("nebulosa", "Nebulosa", "Roxo profundo, misterioso", 0xFFB388FF.toInt(), 0xFF5B2A9E.toInt(), 0xFF08050F.toInt(), "vidro", "bricolage", 26, 1, 60, 50, "noite"),
        Tema("esmeralda", "Esmeralda", "Verde elegante, sofisticado", 0xFF4FD1A5.toInt(), 0xFF1B6E52.toInt(), 0xFF04100A.toInt(), "vidro", "instrument", 22, 1, 45, 55, "grade"),
        // temas de rank: do bronze ao topo, cada um com cor, brilho e letra proprios
        Tema("bronze", "Bronze", "Rank: começo da temporada", 0xFFE0A070.toInt(), 0xFF8C4A2F.toInt(), 0xFF0D0805.toInt(), "solido", "bigs", 6, 1, 45, 80, "brasas"),
        Tema("diamante", "Diamante", "Rank: brilho de cristal", 0xFF8FE3FF.toInt(), 0xFFB388FF.toInt(), 0xFF050814.toInt(), "vidro", "russo", 16, 1, 65, 55, "neve"),
        Tema("mestre", "Mestre", "Rank: roxo e vermelho", 0xFFFF4D6D.toInt(), 0xFFB14DFF.toInt(), 0xFF0B0512.toInt(), "hud", "russo", 2, 2, 70, 75, "noite"),
        Tema("graomestre", "Grão-Mestre", "Rank: o topo, fogo e ouro", 0xFFFFD36B.toInt(), 0xFFFF5A1F.toInt(), 0xFF0A0604.toInt(), "hud", "orbitron", 2, 2, 80, 78, "brasas", true),
        // temas animados: fundo vivo + efeito nos cartões (dá pra trocar o efeito e a força em Estúdio › Efeitos)
        Tema("t_inferno", "Inferno", "Chamas lambendo os cartões", 0xFFFF7A1A.toInt(), 0xFFFF2A00.toInt(), 0xFF0A0402.toInt(), "hud", "russo", 2, 2, 80, 78, "inferno", efeito = "inferno", blur = 0),
        Tema("t_sangue", "Sangue", "Escorrendo pelos cartões", 0xFFFF2337.toInt(), 0xFF8A0014.toInt(), 0xFF0A0203.toInt(), "solido", "gloock", 12, 1, 55, 82, "sangue", efeito = "sangue", blur = 0),
        Tema("t_tempestade", "Tempestade", "Raios estalando nos cartões", 0xFF9EC9FF.toInt(), 0xFF7A5CFF.toInt(), 0xFF05060E.toInt(), "contorno", "tektur", 10, 1, 70, 70, "tempestade", efeito = "raio", blur = 0),
        Tema("t_toxico", "Tóxico", "Gosma verde e bolhas", 0xFF5CFF4A.toInt(), 0xFF0FA64A.toInt(), 0xFF020A03.toInt(), "solido", "pixelify", 8, 1, 60, 80, "toxico", efeito = "toxico", blur = 0),
        Tema("t_gelo", "Ártico", "Pingentes e neve nos cartões", 0xFFE6F6FF.toInt(), 0xFF6FC8FF.toInt(), 0xFF060A12.toInt(), "vidro", "instrument", 20, 1, 50, 55, "neve", efeito = "gelo", blur = 0),
        Tema("t_vazio", "Vazio", "Buraco negro girando", 0xFFC9A0FF.toInt(), 0xFFFF6BD8.toInt(), 0xFF05030A.toInt(), "vidro", "bricolage", 24, 1, 65, 55, "vazio", efeito = "vazio", blur = 0),
        Tema("t_morte", "Morte", "Caveiras e névoa", 0xFFE8E2D4.toInt(), 0xFFB3121F.toInt(), 0xFF050505.toInt(), "contorno", "bigs", 6, 1, 45, 80, "caveiras", efeito = "morte", blur = 0),
        Tema("t_diamante", "Diamante", "Luz arco-íris e brilhos nos cartões", 0xFF9EF6FF.toInt(), 0xFFFF8AF0.toInt(), 0xFF070812.toInt(), "vidro", "outfit", 22, 1, 60, 55, "neve", efeito = "diamante", blur = 0),
        Tema("t_neon", "Neon City", "Sol listrado e chão neon andando", 0xFFFF3CAC.toInt(), 0xFF22E4FF.toInt(), 0xFF07040F.toInt(), "contorno", "orbitron", 12, 1, 85, 70, "grade", efeito = "neon", scanner = true, blur = 0),
    )

    fun get(id: String): Tema = lista.firstOrNull { it.id == id } ?: lista[0]

    /** temas novos: ficam no fim da lista com o selo NOVO */
    fun novo(id: String) = id.startsWith("t_") || id.startsWith("x_")

    /** aplica o tema: cores, formato, fonte e fundo animado. Video/imagem escolhidos pelo usuario continuam. */
    fun aplica(t: Tema) {
        Prefs.tema = t.id
        Prefs.accent = t.c1
        Prefs.accent2 = t.c2
        Prefs.baseCor = t.base
        Prefs.angulo = 135
        Prefs.cartao = t.cartao
        Prefs.fonte = t.fonte
        Prefs.fonteTexto = ""          // o tema decide as duas letras (titulo e texto)
        Prefs.raio = t.raio
        Prefs.borda = t.borda
        Prefs.brilho = t.brilho
        // a opacidade dos cartoes e escolha da pessoa (padrao 0 = transparente): trocar de tema nao mexe nela
        Prefs.bgPreset = t.fundo
        Prefs.extraTxt("efeito_cartao", t.efeito)
        Prefs.scanner = t.scanner
        if (t.blur >= 0) Prefs.bgBlur = t.blur
        if (Prefs.bgTipo == "solido") Prefs.bgTipo = "animado"
        // visual HUD (só na prévia): o fundo animado faz parte do visual
        if (BuildConfig.PREVIEW && t.cartao == "hudx") Prefs.bgTipo = "animado"
    }

    /** par de cores (cor 1, cor 2) pra escolher rapido */
    val GRADIENTES: List<Pair<Int, Int>> = lista.map { it.c1 to it.c2 } + listOf(
        0xFF22E4FF.toInt() to 0xFF3DDC84.toInt(),
        0xFFFFB020.toInt() to 0xFFFF2D55.toInt(),
        0xFFA66BFF.toInt() to 0xFF22E4FF.toInt(),
        0xFFF2F2F6.toInt() to 0xFF8A8A9A.toInt()
    )
}
