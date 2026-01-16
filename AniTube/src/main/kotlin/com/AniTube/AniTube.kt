package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.math.min

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="

        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val AUDIO_BADGE_SELECTOR = ".aniCC, .epiCC"
        private const val EPISODE_NUMBER_SELECTOR = ".epiItemInfos .epiItemNome"

        private const val LATEST_EPISODES_SECTION = ".epiContainer"

        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"

        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"
        private const val PLAYER_IFRAME = "iframe.metaframe, iframe[src*='bg.mp4']"
        
        // JW Player patterns - REGEX MELHORADO
        private const val PACKER_REGEX = """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'"""
    }

    private fun logDebug(message: String) {
        println("[AniTube-DEBUG] $message")
    }

    // ============== FUNÇÃO DE UNPACK CORRIGIDA ==============
    private fun unpack(p: String, a: Int, c: Int, k: String): String {
        logDebug("🚀 Iniciando unpack EXATO (JS style): a=$a, c=$c, k length=${k.length}")
        
        val dict = k.split("|")
        
        // Função e(c) EXATAMENTE como no JavaScript
        fun e(c: Int): String {
            return if (c < a) {
                ""
            } else {
                e(c / a) + if (c % a > 35) {
                    (c % a + 29).toChar().toString()
                } else {
                    Integer.toString(c % a, 36)
                }
            }
        }
        
        // Construir lookup table - IMPORTANTE: começar de c-1 até 0
        val lookup = mutableMapOf<String, String>()
        for (i in c downTo 1) {
            val key = e(i - 1)
            lookup[key] = dict.getOrElse(i - 1) { key }
        }
        
        logDebug("📊 Lookup table size: ${lookup.size}")
        if (lookup.size <= 10) {
            logDebug("📊 Todas as entradas: ${lookup.entries.joinToString { "${it.key}->${it.value}" }}")
        } else {
            logDebug("📊 Primeiras 10 entradas: ${lookup.entries.take(10).joinToString { "${it.key}->${it.value}" }}")
        }
        
        // Regex para encontrar tokens exatamente como JavaScript /\b\w+\b/
        val tokenPattern = Regex("""\b[a-zA-Z_$][a-zA-Z0-9_$]*\b""")
        val matches = tokenPattern.findAll(p).toList()
        
        logDebug("🔍 Total de tokens encontrados: ${matches.size}")
        
        var result = p
        var replacements = 0
        
        // IMPORTANTE: Substituir de trás para frente para não afetar índices
        matches.reversed().forEach { match ->
            val token = match.value
            val replacement = lookup[token]
            if (replacement != null && replacement != token) {
                // Substituir este token específico
                val start = match.range.start
                val end = match.range.endInclusive + 1
                result = result.substring(0, start) + replacement + result.substring(end)
                replacements++
            }
        }
        
        logDebug("✅ Unpack completo: $replacements tokens substituídos")
        logDebug("📝 Resultado length: ${result.length}")
        
        // Log dos primeiros 500 caracteres para debug
        val preview = if (result.length > 500) result.substring(0, 500) + "..." else result
        logDebug("📄 Decoded preview (primeiros 500 chars):\n$preview")
        
        return result
    }

    // ============== FUNÇÃO MELHORADA PARA EXTRAIR LINKS ==============
    private fun extractVideoLinksFromDecoded(decoded: String): List<String> {
        val links = mutableListOf<String>()
        
        try {
            logDebug("🔍 Analisando decoded para links de vídeo...")
            
            // Padrão 1: URLs googlevideo.com (mais comuns)
            val pattern1 = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""", RegexOption.IGNORE_CASE)
            
            // Padrão 2: URLs com videoplayback
            val pattern2 = Regex("""https?://[^"'\s]*videoplayback[^"'\s]*""", RegexOption.IGNORE_CASE)
            
            // Padrão 3: URLs que contém itag parameter
            val pattern3 = Regex("""https?://[^"'\s]*[?&]itag=\d+[^"'\s]*""", RegexOption.IGNORE_CASE)
            
            // Buscar com todos os padrões
            val allMatches = (pattern1.findAll(decoded) + pattern2.findAll(decoded) + pattern3.findAll(decoded)).toList()
            
            allMatches.forEachIndexed { index, match ->
                val url = match.value
                // Filtrar URLs válidas
                if ((url.contains("googlevideo.com") || url.contains("videoplayback")) && 
                    url.contains("itag=") && 
                    url.length > 50 &&
                    !url.contains("&type=") && // Filtrar URLs de thumbnail
                    !url.contains("&dur=")) {   // Filtrar URLs de duração
                    
                    logDebug("🎬 URL ${index + 1}: ${url.take(80)}...")
                    
                    // Extrair itag para debug
                    val itagMatch = Regex("""[?&]itag=(\d+)""").find(url)
                    val itag = itagMatch?.groupValues?.get(1) ?: "unknown"
                    logDebug("   - Itag: $itag")
                    
                    links.add(url)
                }
            }
            
            logDebug("📊 Total de links válidos encontrados: ${links.size}")
            
            // Remover duplicados
            val uniqueLinks = links.distinct()
            if (uniqueLinks.size != links.size) {
                logDebug("🔄 Removidos ${links.size - uniqueLinks.size} links duplicados")
            }
            
            return uniqueLinks
            
        } catch (e: Exception) {
            logDebug("💥 Erro ao extrair links: ${e.message}")
            return emptyList()
        }
    }

    // ============== FUNÇÃO MELHORADA DE EXTRAÇÃO JW PLAYER ==============
    private suspend fun extractJWPlayerLinks(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            println("\n" + "=".repeat(80))
            println("[AniTube-DEBUG] ======== INÍCIO JW PLAYER ========")
            println("[AniTube-DEBUG] 📌 Iframe SRC: $iframeSrc")
            
            // DEBUG EXTRA: Salvar HTML para análise
            try {
                val testResponse = app.get(iframeSrc, timeout = 30000)
                println("[AniTube-DEBUG] 📄 HTML bruto (primeiros 2000 chars):")
                println(testResponse.text.take(2000))
            } catch (e: Exception) {
                println("[AniTube-DEBUG] ❌ Erro ao buscar iframe: ${e.message}")
            }
            // FIM DEBUG
            
            // Primeira requisição para seguir redirecionamentos
            val response1 = app.get(
                iframeSrc,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to videoUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                allowRedirects = false
            )
            
            println("[AniTube-DEBUG] 📊 Status primeira requisição: ${response1.code}")
            
            var playerUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null && (response1.code == 301 || response1.code == 302)) {
                playerUrl = location
                println("[AniTube-DEBUG] 📍 Redirecionado para: $playerUrl")
            }
            
            // Segunda requisição para obter HTML do player
            println("[AniTube-DEBUG] 🔗 Buscando player URL: $playerUrl")
            val response2 = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                ),
                timeout = 30000
            )
            
            val playerHtml = response2.text
            println("[AniTube-DEBUG] 📄 HTML obtido: ${playerHtml.length} caracteres")
            
            // Procurar packer code - REGEX MELHORADO
            val packerRegex = Regex(PACKER_REGEX, RegexOption.DOT_MATCHES_ALL)
            val match = packerRegex.find(playerHtml)
            
            if (match != null && match.groupValues.size >= 5) {
                println("[AniTube-DEBUG] ✅ Packer code encontrado!")
                val p = match.groupValues[1].replace("\\'", "'")
                val a = match.groupValues[2].toIntOrNull() ?: 62
                val c = match.groupValues[3].toIntOrNull() ?: 361
                val k = match.groupValues[4]
                
                println("[AniTube-DEBUG] 📦 Parâmetros:")
                println("[AniTube-DEBUG]   - p length=${p.length}")
                println("[AniTube-DEBUG]   - a=$a")
                println("[AniTube-DEBUG]   - c=$c")
                println("[AniTube-DEBUG]   - k length=${k.length}")
                println("[AniTube-DEBUG]   - k preview: ${k.take(100)}...")
                
                // Decodificar com nossa função CORRIGIDA
                val decoded = unpack(p, a, c, k)
                
                // Extrair links usando função melhorada
                val videoLinks = extractVideoLinksFromDecoded(decoded)
                
                videoLinks.forEachIndexed { index, url ->
                    try {
                        println("[AniTube-DEBUG] 🔗 Processando URL ${index + 1}/${videoLinks.size}")
                        
                        // Determinar qualidade baseado no itag
                        val quality = when {
                            url.contains("itag=37") || url.contains("itag=46") -> 1080
                            url.contains("itag=22") || url.contains("itag=45") -> 720
                            url.contains("itag=59") || url.contains("itag=44") -> 480
                            url.contains("itag=18") || url.contains("itag=43") -> 360
                            url.contains("itag=34") -> 360
                            url.contains("itag=35") -> 480
                            url.contains("itag=36") -> 240
                            else -> 360
                        }
                        
                        val qualityLabel = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            480 -> "480p"
                            360 -> "360p"
                            240 -> "240p"
                            else -> "SD"
                        }
                        
                        links.add(
                            newExtractorLink(
                                name,
                                "JW Player ($qualityLabel)",
                                url,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://api.anivideo.net/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Origin" to "https://api.anivideo.net",
                                    "Referer" to "https://api.anivideo.net/",
                                    "Accept" to "*/*",
                                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                                    "Accept-Encoding" to "gzip, deflate, br"
                                )
                            }
                        )
                        
                        println("[AniTube-DEBUG] ✅ Link adicionado: $qualityLabel")
                        
                    } catch (e: Exception) {
                        println("[AniTube-DEBUG] ⚠️  Erro ao processar URL ${index + 1}: ${e.message}")
                    }
                }
                
                println("[AniTube-DEBUG] 📊 Total links JW Player encontrados: ${links.size}")
                
            } else {
                println("[AniTube-DEBUG] ❌ Packer code não encontrado no HTML")
                // Tentar encontrar links diretos sem unpack
                val directUrls = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""").findAll(playerHtml)
                directUrls.forEach { urlMatch ->
                    val url = urlMatch.value
                    if (url.contains("itag=")) {
                        println("[AniTube-DEBUG] 🔗 Link direto encontrado: ${url.take(80)}...")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("[AniTube-DEBUG] 💥 Erro no JW Player: ${e.message}")
            e.printStackTrace()
        }
        
        println("[AniTube-DEBUG] ======== FIM JW PLAYER ========")
        println("=".repeat(80))
        return links
    }

    // ============== FUNÇÃO ALTERNATIVA: USAR M3U8 SE JW PLAYER FALHAR ==============
    private suspend fun extractM3u8LinksFromPage(document: org.jsoup.nodes.Document): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            println("[AniTube-DEBUG] 🔍 Buscando links M3U8 na página...")
            
            // 1. Verificar player FHD (#blog2 iframe)
            document.selectFirst(PLAYER_FHD)?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("m3u8", true)) {
                    println("[AniTube-DEBUG] ✅ Player FHD encontrado: $src")
                    links.add(
                        newExtractorLink(
                            name,
                            "Player FHD",
                            src,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = "$mainUrl/"
                            quality = 1080
                        }
                    )
                }
            }
            
            // 2. Verificar scripts que contenham m3u8
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8", true)) {
                    val m3u8Regex = Regex("""https?://[^"'\s]*\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE)
                    val matches = m3u8Regex.findAll(scriptContent)
                    
                    matches.forEach { match ->
                        val m3u8Url = match.value
                        if (!m3u8Url.contains("anivideo.net", true)) { // Ignorar links da API
                            println("[AniTube-DEBUG] 🎬 M3U8 em script: $m3u8Url")
                            links.add(
                                newExtractorLink(
                                    name,
                                    "M3U8 Script",
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    referer = "$mainUrl/"
                                    quality = 720
                                }
                            )
                        }
                    }
                }
            }
            
            println("[AniTube-DEBUG] 📊 Total links M3U8 encontrados: ${links.size}")
            
        } catch (e: Exception) {
            println("[AniTube-DEBUG] 💥 Erro ao extrair M3U8: ${e.message}")
        }
        
        return links
    }

    // ============== FUNÇÕES AUXILIARES ==============
    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{3,})\\b".toRegex(),
            "\\b(\\d{1,2})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        clean = clean.replace("\\s*\\d+\\s*\$".toRegex(), "").trim()

        return clean.ifBlank { "Anime" }
    }

    private fun isDubbed(element: Element): Boolean {
        return element.selectFirst(AUDIO_BADGE_SELECTOR)
            ?.text()
            ?.contains("Dublado", true) ?: false
    }

    private fun extractM3u8FromUrl(url: String): String? {
        return if (url.contains("d=")) {
            try {
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) { 
                println("[AniTube-DEBUG] ❌ Erro ao decodificar m3u8: ${e.message}")
                null 
            }
        } else { url }
    }

    // ============== LOAD LINKS (PRINCIPAL) ==============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n" + "=".repeat(80))
        println("[AniTube-DEBUG] 🚀 ============== INICIANDO EXTRACTION ==============")
        println("[AniTube-DEBUG] 📌 URL: $actualUrl")
        println("[AniTube-DEBUG] 📌 Data completa: $data")
        println("=".repeat(80) + "\n")

        val document = app.get(actualUrl).document
        var linksFound = false

        // ============== LOG DE TODOS OS IFRAMES ==============
        val allIframes = document.select("iframe")
        println("[AniTube-DEBUG] 📊 Total iframes encontrados: ${allIframes.size}")
        allIframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            val id = iframe.attr("id")
            val cls = iframe.attr("class")
            println("[AniTube-DEBUG] 📋 Iframe $index: src='$src', id='$id', class='$cls'")
        }

        // ============== PRIMEIRA TENTATIVA: JW PLAYER ==============
        println("\n" + "🎯".repeat(40))
        println("[AniTube-DEBUG] 🎯 PRIMEIRA TENTATIVA: JW Player")
        println("[AniTube-DEBUG] 🎯".repeat(40))
        
        document.selectFirst(PLAYER_IFRAME)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            println("[AniTube-DEBUG] ✅ Iframe JW Player encontrado: ${src.take(100)}...")
            
            val jwLinks = extractJWPlayerLinks(src, actualUrl)
            if (jwLinks.isNotEmpty()) {
                println("\n[AniTube-DEBUG] 🎉🎉🎉 JW Player retornou ${jwLinks.size} links! 🎉🎉🎉")
                jwLinks.forEach { 
                    callback(it)
                    linksFound = true
                }
                return@loadLinks linksFound
            } else {
                println("[AniTube-DEBUG] ❌ JW Player não retornou links")
            }
        }

        // ============== SEGUNDA TENTATIVA: M3U8 ==============
        if (!linksFound) {
            println("\n" + "🎯".repeat(40))
            println("[AniTube-DEBUG] 🎯 SEGUNDA TENTATIVA: M3U8")
            println("[AniTube-DEBUG] 🎯".repeat(40))
            
            val m3u8Links = extractM3u8LinksFromPage(document)
            if (m3u8Links.isNotEmpty()) {
                println("\n[AniTube-DEBUG] 🎉🎉🎉 M3U8 retornou ${m3u8Links.size} links! 🎉🎉🎉")
                m3u8Links.forEach {
                    callback(it)
                    linksFound = true
                }
                return@loadLinks linksFound
            }
        }

        // ============== TERCEIRA TENTATIVA: PLAYER BACKUP ==============
        if (!linksFound) {
            println("\n" + "🎯".repeat(40))
            println("[AniTube-DEBUG] 🎯 TERCEIRA TENTATIVA: Player Backup")
            println("[AniTube-DEBUG] 🎯".repeat(40))
            
            document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("[AniTube-DEBUG] ✅ Player Backup encontrado: $src")
                
                callback(
                    newExtractorLink(
                        name,
                        "Player Backup", 
                        src,
                        ExtractorLinkType.VIDEO
                    ) {
                        referer = "$mainUrl/"
                        quality = 720
                    }
                )
                linksFound = true
            }
        }

        // ============== VERIFICAÇÃO FINAL ==============
        println("\n" + "=".repeat(80))
        println("[AniTube-DEBUG] 📊 ============== RESULTADO FINAL ==============")
        println("[AniTube-DEBUG] ✅ Links encontrados: $linksFound")
        if (linksFound) {
            println("[AniTube-DEBUG] 🎉 SUCESSO! Pelo menos 1 link foi encontrado.")
        } else {
            println("[AniTube-DEBUG] ❌ FALHA! Nenhum link válido encontrado.")
        }
        println("[AniTube-DEBUG] 🎬 Processo finalizado")
        println("=".repeat(80))

        return linksFound
    }

    // ============== RESTANTE DO CÓDIGO (SEM ALTERAÇÕES) ==============

    private val genresMap = mapOf(
        "Ação" to "acao", "Artes Marciais" to "artes%20marciais", "Aventura" to "aventura",
        "Comédia" to "comedia", "Comédia Romântica" to "comedia%20romantica", "Drama" to "drama",
        "Ecchi" to "ecchi", "Esporte" to "esporte", "Fantasia" to "fantasia",
        "Ficção Científica" to "ficcao%20cientifica", "Jogos" to "jogos", "Magia" to "magia",
        "Mecha" to "mecha", "Mistério" to "misterio", "Musical" to "musical",
        "Romance" to "romance", "Seinen" to "seinen", "Shoujo-ai" to "shoujo%20ai",
        "Shounen" to "shounen", "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural",
        "Superpoder" to "superpoder", "Terror" to "terror", "Vida Escolar" to "vida%20escolar",
        "Shoujo" to "shoujo", "Shounen-ai" to "shounen%20ai", "Yaoi" to "yaoi",
        "Yuri" to "yuri", "Harem" to "harem", "Isekai" to "isekai", "Militar" to "militar",
        "Policial" to "policial", "Psicológico" to "psicologico", "Samurai" to "samurai",
        "Vampiros" to "vampiros", "Zumbi" to "zumbi", "Histórico" to "historico",
        "Mágica" to "magica", "Cyberpunk" to "cyberpunk", "Espaço" to "espaco",
        "Demônios" to "demônios", "Vida Cotidiana" to "vida%20cotidiana"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos",
        "$mainUrl" to "Animes Recentes",
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/video/")) return null

        val episodeTitle = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        val displayName = cleanTitle(animeTitle)

        val urlWithPoster = if (posterUrl != null) {
            "$href|poster=$posterUrl"
        } else {
            href
        }

        return newAnimeSearchResponse(displayName, fixUrl(urlWithPoster)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime

            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
        }
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null

        val rawTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }

        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            addDubStatus(isDubbed, null)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data

        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) baseUrl.replace("/?s=", "/page/$page/?s=") else baseUrl
            val document = app.get(url).document

            val allItems = document.select("$ANIME_CARD, $EPISODE_CARD")
                .mapNotNull { 
                    val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                    if (isEpisode) {
                        it.toEpisodeSearchResponse()
                    } else {
                        it.toAnimeSearchResponse()
                    }
                }
                .distinctBy { it.url }

            return newHomePageResponse(request.name, allItems, hasNext = true)
        }

        val document = app.get(baseUrl).document

        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                var popularItems = listOf<AnimeSearchResponse>()

                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("Animes Mais Vistos", true)) {
                        popularItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }

                if (popularItems.isEmpty()) {
                    val slides = document.select("#splide01 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }

                    popularItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }

                newHomePageResponse(
                    list = HomePageList(request.name, popularItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                var recentItems = listOf<AnimeSearchResponse>()

                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("ANIMES RECENTES", true)) {
                        recentItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }

                if (recentItems.isEmpty()) {
                    val slides = document.select("#splide02 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }

                    recentItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }

                newHomePageResponse(
                    list = HomePageList(request.name, recentItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val document = app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document

        return document.select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { 
                val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                if (isEpisode) {
                    it.toEpisodeSearchResponse()
                } else {
                    it.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }

        val document = app.get(actualUrl).document

        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
        val title = cleanTitle(rawTitle)

        val poster = thumbPoster ?: document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }

        val siteSynopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()

        val synopsis = if (actualUrl.contains("/video/")) {
            siteSynopsis ?: "Episódio $episodeNumber de $title"
        } else {
            siteSynopsis ?: "Sinopse não disponível."
        }

        var year: Int? = null
        var episodes: Int? = null
        var genres = emptyList<String>()
        var audioType = ""

        document.select(ANIME_METADATA).forEach { element ->
            val text = element.text()
            when {
                text.contains("Gênero:", true) -> genres = text.substringAfter("Gênero:").split(",").map { it.trim() }
                text.contains("Ano:", true) -> year = text.substringAfter("Ano:").trim().toIntOrNull()
                text.contains("Episódios:", true) -> episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
                text.contains("Tipo de Episódio:", true) -> audioType = text.substringAfter("Tipo de Episódio:").trim()
            }
        }

        val isDubbed = rawTitle.contains("dublado", true) || audioType.contains("dublado", true)

        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val episodeTitle = element.text().trim()
            val episodeUrl = element.attr("href")
            val epNumber = extractEpisodeNumber(episodeTitle) ?: 1

            newEpisode(episodeUrl) {
                this.name = "Episódio $epNumber"
                this.episode = epNumber
                this.posterUrl = poster
            }
        }

        val allEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) {
            listOf(newEpisode(actualUrl) {
                this.name = "Episódio $episodeNumber"
                this.episode = episodeNumber
                this.posterUrl = poster
            })
        } else {
            episodesList
        }

        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        val showStatus = if (episodes != null && sortedEpisodes.size >= episodes) ShowStatus.Completed else ShowStatus.Ongoing

        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus

            if (sortedEpisodes.isNotEmpty()) addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }
}
