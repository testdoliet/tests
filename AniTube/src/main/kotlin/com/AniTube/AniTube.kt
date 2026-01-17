package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.math.max
import kotlin.math.min

class AniTube : MainAPI() {
    // ============== INICIALIZAÇÃO ==============
    init {
        println("\n" + "🔥".repeat(80))
        println("🔥 [AniTube-INIT] PROVIDER INICIALIZANDO!")
        println("🔥 [AniTube-INIT] Nome: AniTube")
        println("🔥 [AniTube-INIT] URL: https://www.anitube.news")
        println("🔥 [AniTube-INIT] Idioma: pt-br")
        println("🔥".repeat(80) + "\n")
    }
    
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
    }

    // ============== FUNÇÃO DE UNPACK COM LOGS ==============
    private fun unpack(p: String, a: Int, c: Int, k: String): String {
        println("\n📦 [AniTube-UNPACK] INICIANDO UNPACK")
        println("📦 [AniTube-UNPACK] Parâmetros:")
        println("📦 [AniTube-UNPACK]   - p length: ${p.length}")
        println("📦 [AniTube-UNPACK]   - a: $a")
        println("📦 [AniTube-UNPACK]   - c: $c")
        println("📦 [AniTube-UNPACK]   - k length: ${k.length}")
        
        val dict = k.split("|")
        println("📦 [AniTube-UNPACK] Dict split em ${dict.size} partes")
        
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
        
        // Construir lookup table
        println("📦 [AniTube-UNPACK] Construindo lookup table...")
        val lookup = mutableMapOf<String, String>()
        for (i in c downTo 1) {
            val key = e(i - 1)
            lookup[key] = dict.getOrElse(i - 1) { key }
        }
        
        println("📦 [AniTube-UNPACK] Lookup table size: ${lookup.size}")
        
        // Regex para tokens
        val tokenPattern = Regex("""\b[a-zA-Z_$][a-zA-Z0-9_$]*\b""")
        val matches = tokenPattern.findAll(p).toList()
        println("📦 [AniTube-UNPACK] Tokens encontrados: ${matches.size}")
        
        var result = p
        var replacements = 0
        
        // Substituir de trás para frente
        println("📦 [AniTube-UNPACK] Substituindo tokens...")
        matches.reversed().forEach { match ->
            val token = match.value
            val replacement = lookup[token]
            if (replacement != null && replacement != token) {
                val start = match.range.start
                val end = match.range.endInclusive + 1
                result = result.substring(0, start) + replacement + result.substring(end)
                replacements++
            }
        }
        
        println("📦 [AniTube-UNPACK] ✅ Unpack completo!")
        println("📦 [AniTube-UNPACK] Tokens substituídos: $replacements")
        println("📦 [AniTube-UNPACK] Resultado length: ${result.length}")
        
        // Preview do resultado
        val preview = if (result.length > 500) result.substring(0, 500) + "..." else result
        println("📦 [AniTube-UNPACK] Preview (500 chars):")
        println(preview)
        
        return result
    }

    // ============== EXTRATOR DE LINKS COM LOGS ==============
    private fun extractVideoLinksFromDecoded(decoded: String): List<String> {
        println("\n🔍 [AniTube-EXTRACT] Analisando decoded...")
        
        val links = mutableListOf<String>()
        
        try {
            // Padrão 1: URLs googlevideo.com
            val pattern1 = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""", RegexOption.IGNORE_CASE)
            
            // Padrão 2: URLs com videoplayback
            val pattern2 = Regex("""https?://[^"'\s]*videoplayback[^"'\s]*""", RegexOption.IGNORE_CASE)
            
            val allMatches = (pattern1.findAll(decoded) + pattern2.findAll(decoded)).toList()
            
            println("🔍 [AniTube-EXTRACT] Matchs encontrados: ${allMatches.size}")
            
            allMatches.forEachIndexed { index, match ->
                val url = match.value
                println("🔍 [AniTube-EXTRACT] Match $index: ${url.take(80)}...")
                
                if (url.contains("googlevideo.com") && url.contains("itag=")) {
                    println("🔍 [AniTube-EXTRACT] ✅ URL válida!")
                    links.add(url)
                }
            }
            
            println("🔍 [AniTube-EXTRACT] Total links válidos: ${links.size}")
            
        } catch (e: Exception) {
            println("🔍 [AniTube-EXTRACT] ❌ Erro: ${e.message}")
        }
        
        return links.distinct()
    }

    // ============== JW PLAYER EXTRACTION COM DEBUG COMPLETO ==============
    private suspend fun extractJWPlayerLinks(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        println("\n" + "🎬".repeat(50))
        println("🎬 [AniTube-JW] INICIANDO EXTRACTION JW PLAYER")
        println("🎬 [AniTube-JW] Iframe SRC: $iframeSrc")
        println("🎬".repeat(50))
        
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // 1. Primeira requisição
            println("🎬 [AniTube-JW] 📡 Fazendo primeira requisição...")
            val response1 = app.get(
                iframeSrc,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to videoUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                allowRedirects = false
            )
            
            println("🎬 [AniTube-JW] 📊 Status: ${response1.code}")
            
            var playerUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null && (response1.code == 301 || response1.code == 302)) {
                playerUrl = location
                println("🎬 [AniTube-JW] 📍 Redirecionado para: $playerUrl")
            }
            
            // 2. Segunda requisição
            println("🎬 [AniTube-JW] 📡 Fazendo segunda requisição...")
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
            println("🎬 [AniTube-JW] 📄 HTML obtido: ${playerHtml.length} caracteres")
            
            // ============== DEBUG EXTREMO DO HTML ==============
            println("\n🔍 [AniTube-JW] 🔥 DEBUG DO HTML 🔥")
            println("🔍 [AniTube-JW] Primeiros 500 chars do HTML:")
            println(playerHtml.take(500))
            println("\n🔍 [AniTube-JW] Últimos 500 chars do HTML:")
            println(playerHtml.takeLast(500))
            
            // Verificar se contém "eval(function(p,a,c,k,e,d)"
            val containsEval = playerHtml.contains("eval(function(p,a,c,k,e,d)")
            println("🔍 [AniTube-JW] Contém 'eval(function(p,a,c,k,e,d)': $containsEval")
            
            // Verificar se contém "eval(function"
            val containsEval3 = playerHtml.contains("eval(function")
            println("🔍 [AniTube-JW] Contém 'eval(function': $containsEval3")
            
            // Buscar TODOS os matches de eval
            val evalPattern = """eval\s*\(function""".toRegex(RegexOption.IGNORE_CASE)
            val evalMatches = evalPattern.findAll(playerHtml).toList()
            println("🔍 [AniTube-JW] Total 'eval(function' encontrados: ${evalMatches.size}")
            
            // Mostrar contexto ao redor de cada eval
            evalMatches.forEachIndexed { index, match ->
                val start = max(0, match.range.first - 100)
                val end = min(playerHtml.length, match.range.last + 300)
                val context = playerHtml.substring(start, end)
                println("\n🔍 [AniTube-JW] Eval $index contexto (${start}-${end}):")
                println("...${context}...")
            }
            // ============== FIM DEBUG ==============
            
            // 3. Buscar packer code - REGEX MELHORADO
            println("\n🎬 [AniTube-JW] 🔍 Buscando packer code com regex...")
            
            // TENTAR VÁRIOS REGEX DIFERENTES
            val regexPatterns = listOf(
                // Padrão 1: JavaScript padrão
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'""",
                
                // Padrão 2: Versão mais simples
                """eval\(function\(p,a,c,k,e,d\).*?\('([^']+)',(\d+),(\d+),'([^']+)'""",
                
                // Padrão 3: Com qualquer whitespace
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?}\s*\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'""",
                
                // Padrão 4: Buscar qualquer coisa que pareça packer
                """function\(p,a,c,k,e,d\).*?}\s*\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'""",
                
                // Padrão 5: Muito genérico
                """}\s*\(\s*'([^']{100,})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']{100,})'"""
            )
            
            var foundMatch: MatchResult? = null
            var usedPatternIndex = -1
            
            regexPatterns.forEachIndexed { index, pattern ->
                if (foundMatch == null) {
                    println("🎬 [AniTube-JW] Tentando padrão ${index + 1}...")
                    val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
                    val match = regex.find(playerHtml)
                    if (match != null) {
                        foundMatch = match
                        usedPatternIndex = index
                        println("🎬 [AniTube-JW] ✅ Padrão ${index + 1} encontrou match!")
                    }
                }
            }
            
            if (foundMatch != null && foundMatch!!.groupValues.size >= 5) {
                println("🎬 [AniTube-JW] ✅ Packer code encontrado! (Padrão ${usedPatternIndex + 1})")
                
                val p = foundMatch!!.groupValues[1].replace("\\'", "'")
                val a = foundMatch!!.groupValues[2].toIntOrNull() ?: 62
                val c = foundMatch!!.groupValues[3].toIntOrNull() ?: 361
                val k = foundMatch!!.groupValues[4]
                
                println("🎬 [AniTube-JW] 📦 Parâmetros unpack:")
                println("🎬 [AniTube-JW]   - p length: ${p.length}")
                println("🎬 [AniTube-JW]   - a: $a")
                println("🎬 [AniTube-JW]   - c: $c")
                println("🎬 [AniTube-JW]   - k length: ${k.length}")
                println("🎬 [AniTube-JW]   - k preview: ${k.take(100)}...")
                
                // Verificar se k tem pipes
                val hasPipes = k.contains("|")
                println("🎬 [AniTube-JW]   - k tem pipes (|): $hasPipes")
                if (!hasPipes) {
                    println("🎬 [AniTube-JW] ⚠️  AVISO: k não tem pipes - pode não ser um packer válido!")
                }
                
                println("🎬 [AniTube-JW] 📦 Executando unpack...")
                val decoded = unpack(p, a, c, k)
                
                println("🎬 [AniTube-JW] 🔍 Extraindo links do decoded...")
                val videoLinks = extractVideoLinksFromDecoded(decoded)
                
                println("🎬 [AniTube-JW] 📊 Links extraídos: ${videoLinks.size}")
                
                videoLinks.forEachIndexed { index, url ->
                    try {
                        println("🎬 [AniTube-JW] 🔗 Processando link ${index + 1}: ${url.take(60)}...")
                        
                        // Determinar qualidade
                        val quality = when {
                            url.contains("itag=37") || url.contains("itag=46") -> 1080
                            url.contains("itag=22") || url.contains("itag=45") -> 720
                            url.contains("itag=59") || url.contains("itag=44") -> 480
                            url.contains("itag=18") || url.contains("itag=43") -> 360
                            else -> 360
                        }
                        
                        val qualityLabel = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            480 -> "480p"
                            360 -> "360p"
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
                                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                                )
                            }
                        )
                        
                        println("🎬 [AniTube-JW] ✅ Link $qualityLabel adicionado!")
                        
                    } catch (e: Exception) {
                        println("🎬 [AniTube-JW] ❌ Erro processando link ${index + 1}: ${e.message}")
                    }
                }
                
            } else {
                println("🎬 [AniTube-JW] ❌ NENHUM packer code encontrado com nenhum padrão!")
                
                // MOSTRAR PARTES DO HTML QUE PODEM TER O PACKER
                println("\n🔍 [AniTube-JW] 🕵️‍♂️ ANALISANDO HTML PARA PACKER...")
                
                // Procurar por 'p,a,c,k' no HTML
                val packerIndicators = listOf("p,a,c,k", "'|'", "split('|')", ".split('|')")
                packerIndicators.forEach { indicator ->
                    if (playerHtml.contains(indicator)) {
                        println("🔍 [AniTube-JW] ✅ HTML contém '$indicator'")
                    }
                }
                
                // Procurar por texto que parece packer (muito código)
                val lines = playerHtml.split("\n")
                lines.forEachIndexed { index, line ->
                    if (line.length > 200 && line.contains("eval")) {
                        println("\n🔍 [AniTube-JW] 📄 Linha $index (${line.length} chars):")
                        println(line.take(200))
                    }
                }
                
                println("🎬 [AniTube-JW] 🔍 Buscando links diretos...")
                
                val directUrls = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""").findAll(playerHtml)
                val directUrlList = directUrls.toList()
                println("🎬 [AniTube-JW] Links googlevideo encontrados: ${directUrlList.size}")
                
                directUrlList.forEachIndexed { index, urlMatch ->
                    val url = urlMatch.value
                    if (url.contains("itag=")) {
                        println("🎬 [AniTube-JW] 🔗 Link direto $index: ${url.take(80)}...")
                        
                        // Adicionar link direto também
                        val quality = when {
                            url.contains("itag=37") || url.contains("itag=46") -> 1080
                            url.contains("itag=22") || url.contains("itag=45") -> 720
                            url.contains("itag=59") || url.contains("itag=44") -> 480
                            url.contains("itag=18") || url.contains("itag=43") -> 360
                            else -> 360
                        }
                        
                        val qualityLabel = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            480 -> "480p"
                            360 -> "360p"
                            else -> "SD"
                        }
                        
                        links.add(
                            newExtractorLink(
                                name,
                                "Direto ($qualityLabel)",
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
                                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                                )
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            println("🎬 [AniTube-JW] 💥 ERRO CRÍTICO: ${e.message}")
            e.printStackTrace()
        }
        
        println("🎬 [AniTube-JW] 📊 Total links retornados: ${links.size}")
        println("🎬".repeat(50))
        return links
    }

    // ============== M3U8 EXTRACTION ==============
    private suspend fun extractM3u8LinksFromPage(document: org.jsoup.nodes.Document): List<ExtractorLink> {
        println("\n📺 [AniTube-M3U8] Buscando links M3U8...")
        
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // Player FHD
            document.selectFirst(PLAYER_FHD)?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("m3u8", true)) {
                    println("📺 [AniTube-M3U8] ✅ Player FHD: $src")
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
            
            // Scripts com M3U8
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8", true)) {
                    val m3u8Regex = Regex("""https?://[^"'\s]*\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE)
                    val matches = m3u8Regex.findAll(scriptContent)
                    
                    matches.forEach { match ->
                        val m3u8Url = match.value
                        if (!m3u8Url.contains("anivideo.net", true)) {
                            println("📺 [AniTube-M3U8] 🎬 M3U8 em script: $m3u8Url")
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
            
        } catch (e: Exception) {
            println("📺 [AniTube-M3U8] ❌ Erro: ${e.message}")
        }
        
        println("📺 [AniTube-M3U8] 📊 Total M3U8: ${links.size}")
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

    // ============== LOAD LINKS PRINCIPAL COM LOGS DETALHADOS ==============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n" + "🚀".repeat(80))
        println("🚀 [AniTube] LOADLINKS INICIADO!")
        println("🚀 [AniTube] Data completa: $data")
        
        val actualUrl = data.split("|poster=")[0]
        println("🚀 [AniTube] URL real: $actualUrl")
        println("🚀 [AniTube] isCasting: $isCasting")
        println("🚀".repeat(80) + "\n")
        
        // Verificar se é YouTube (trailer)
        if (actualUrl.contains("youtube.com") || actualUrl.contains("youtu.be")) {
            println("🎬 [AniTube] ⚠️  URL do YouTube detectada - IGNORANDO (é trailer)")
            println("🎬 [AniTube] Retornando FALSE para não interferir")
            return false
        }
        
        var linksFound = false
        
        try {
            // 1. Carregar página
            println("📄 [AniTube] Carregando página...")
            val document = app.get(actualUrl).document
            println("📄 [AniTube] ✅ Página carregada")
            
            // 2. Log de iframes
            val allIframes = document.select("iframe")
            println("📄 [AniTube] Total iframes: ${allIframes.size}")
            allIframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                println("📄 [AniTube] Iframe $index: ${src.take(100)}...")
            }
            
            // ============== TENTATIVA 1: JW PLAYER ==============
            println("\n" + "🎯".repeat(40))
            println("🎯 [AniTube] TENTATIVA 1: JW PLAYER")
            println("🎯".repeat(40))
            
            document.selectFirst(PLAYER_IFRAME)?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("🎯 [AniTube] ✅ Iframe JW encontrado: ${src.take(100)}...")
                
                val jwLinks = extractJWPlayerLinks(src, actualUrl)
                if (jwLinks.isNotEmpty()) {
                    println("🎯 [AniTube] 🎉 JW Player retornou ${jwLinks.size} links!")
                    jwLinks.forEach { 
                        callback(it)
                        linksFound = true
                    }
                    println("🎯 [AniTube] Retornando TRUE (links encontrados)")
                    return@loadLinks true
                } else {
                    println("🎯 [AniTube] ❌ JW Player não retornou links")
                }
            }
            
            if (!linksFound) {
                println("🎯 [AniTube] Nenhum iframe JW Player encontrado")
            }
            
            // ============== TENTATIVA 2: M3U8 ==============
            if (!linksFound) {
                println("\n" + "🎯".repeat(40))
                println("🎯 [AniTube] TENTATIVA 2: M3U8")
                println("🎯".repeat(40))
                
                val m3u8Links = extractM3u8LinksFromPage(document)
                if (m3u8Links.isNotEmpty()) {
                    println("🎯 [AniTube] 🎉 M3U8 retornou ${m3u8Links.size} links!")
                    m3u8Links.forEach {
                        callback(it)
                        linksFound = true
                    }
                    println("🎯 [AniTube] Retornando TRUE (links M3U8 encontrados)")
                    return@loadLinks true
                } else {
                    println("🎯 [AniTube] ❌ M3U8 não retornou links")
                }
            }
            
            // ============== TENTATIVA 3: PLAYER BACKUP ==============
            if (!linksFound) {
                println("\n" + "🎯".repeat(40))
                println("🎯 [AniTube] TENTATIVA 3: PLAYER BACKUP")
                println("🎯".repeat(40))
                
                document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                    println("🎯 [AniTube] ✅ Player Backup encontrado: $src")
                    
                    // IMPORTANTE: Tentar processar este iframe também!
                    println("🎯 [AniTube] 🔄 Tentando processar Player Backup como JW Player...")
                    val backupLinks = extractJWPlayerLinks(src, actualUrl)
                    
                    if (backupLinks.isNotEmpty()) {
                        println("🎯 [AniTube] 🎉 Player Backup processado retornou ${backupLinks.size} links!")
                        backupLinks.forEach {
                            callback(it)
                            linksFound = true
                        }
                        println("🎯 [AniTube] Retornando TRUE (Player Backup processado)")
                        return@loadLinks true
                    } else {
                        println("🎯 [AniTube] ⚠️  Player Backup não pode ser processado")
                        println("🎯 [AniTube] ⚠️  AVISO: Usar este link diretamente causará erro no player!")
                        
                        // Se não conseguir processar, pelo menos tentar
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
                        println("🎯 [AniTube] ⚠️  Link Backup adicionado (pode não funcionar)")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("\n💥 [AniTube] ERRO CRÍTICO em loadLinks: ${e.message}")
            e.printStackTrace()
        }
        
        // ============== RESULTADO FINAL ==============
        println("\n" + "📊".repeat(80))
        println("📊 [AniTube] RESULTADO FINAL")
        println("📊 [AniTube] Links encontrados: $linksFound")
        if (linksFound) {
            println("📊 [AniTube] ✅ SUCESSO!")
        } else {
            println("📊 [AniTube] ❌ FALHA - Nenhum link encontrado")
        }
        println("📊".repeat(80) + "\n")
        
        return linksFound
    }

    // ============== RESTANTE DO CÓDIGO ==============

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
        println("\n🏠 [AniTube] getMainPage chamado")
        println("🏠 [AniTube] Página: $page, Request: ${request.name}")
        
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
        println("\n🔍 [AniTube] search chamado")
        println("🔍 [AniTube] Query: $query")
        
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
        println("\n📥 [AniTube] load chamado")
        println("📥 [AniTube] URL: $url")
        
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

        println("📥 [AniTube] ✅ Load concluído: $title (Ep: $episodeNumber)")
        
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
