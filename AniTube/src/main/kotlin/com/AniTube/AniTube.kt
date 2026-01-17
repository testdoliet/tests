package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
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

    // ============== FUNÇÃO DE UNPACK COM LOGS DETALHADOS ==============
    private fun unpack(p: String, a: Int, c: Int, k: String): String {
        println("\n" + "📦".repeat(50))
        println("📦 [AniTube-UNPACK] INICIANDO UNPACK DETALHADO")
        println("📦 [AniTube-UNPACK] Parâmetros recebidos:")
        println("📦 [AniTube-UNPACK]   - p (código compactado): ${p.length} chars")
        println("📦 [AniTube-UNPACK]   - a (base): $a")
        println("📦 [AniTube-UNPACK]   - c (contagem): $c")
        println("📦 [AniTube-UNPACK]   - k (dicionário): ${k.length} chars")
        
        // Preview do dicionário
        val dictPreview = if (k.length > 200) k.substring(0, 200) + "..." else k
        println("📦 [AniTube-UNPACK] Preview dicionário: $dictPreview")
        
        // Verificar se k contém "split('|')"
        val hasSplit = k.contains(".split('|')")
        println("📦 [AniTube-UNPACK] k tem split('|'): $hasSplit")
        
        // Processar dicionário
        var dict = if (hasSplit) {
            // Remover .split('|') e fazer split
            val cleanK = k.replace("'.split\\('\\|'\\)".toRegex(), "")
            cleanK.split("|")
        } else {
            // Fazer split normal
            k.split("|")
        }
        
        println("📦 [AniTube-UNPACK] Dict split em ${dict.size} partes")
        
        // Mostrar primeiros 10 itens do dicionário
        println("📦 [AniTube-UNPACK] Primeiros 10 itens do dicionário:")
        dict.take(10).forEachIndexed { index, item ->
            println("📦 [AniTube-UNPACK]   [$index]: ${if (item.length > 50) item.substring(0, 50) + "..." else item}")
        }
        
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
        println("\n📦 [AniTube-UNPACK] Construindo lookup table...")
        val lookup = mutableMapOf<String, String>()
        
        for (i in c downTo 1) {
            val key = e(i - 1)
            val value = dict.getOrElse(i - 1) { key }
            lookup[key] = value
            
            if (i >= c - 10) { // Mostrar últimos 10 mapeamentos
                println("📦 [AniTube-UNPACK]   lookup['$key'] = '$value'")
            }
        }
        
        println("📦 [AniTube-UNPACK] Lookup table size: ${lookup.size}")
        
        // Encontrar tokens no código compactado
        println("\n📦 [AniTube-UNPACK] Buscando tokens no código compactado...")
        val tokenPattern = Regex("""\b[a-zA-Z_$][a-zA-Z0-9_$]*\b""")
        val matches = tokenPattern.findAll(p).toList()
        
        println("📦 [AniTube-UNPACK] Tokens encontrados: ${matches.size}")
        if (matches.size > 0) {
            println("📦 [AniTube-UNPACK] Primeiros 10 tokens:")
            matches.take(10).forEachIndexed { index, match ->
                val token = match.value
                val replacement = lookup[token]
                println("📦 [AniTube-UNPACK]   [$index] '$token' → '${replacement ?: "N/A"}'")
            }
        }
        
        var result = p
        var replacements = 0
        
        // Substituir de trás para frente (evita problemas com índices)
        println("\n📦 [AniTube-UNPACK] Substituindo tokens...")
        matches.reversed().forEach { match ->
            val token = match.value
            val replacement = lookup[token]
            
            if (replacement != null && replacement != token) {
                val start = match.range.start
                val end = match.range.endInclusive + 1
                
                // Log detalhado para as primeiras substituições
                if (replacements < 5) {
                    val oldPart = if (result.length > start + 20) 
                        result.substring(start, min(start + 20, result.length)) + "..."
                    else result.substring(start)
                    
                    val newPart = if (replacement.length > 20) 
                        replacement.substring(0, 20) + "..."
                    else replacement
                    
                    println("📦 [AniTube-UNPACK]   #${replacements + 1}: '$token' → '$newPart'")
                    println("📦 [AniTube-UNPACK]     posição $start-$end, antigo: '$oldPart'")
                }
                
                result = result.substring(0, start) + replacement + result.substring(end)
                replacements++
            }
        }
        
        println("\n📦 [AniTube-UNPACK] ✅ UNPACK COMPLETO!")
        println("📦 [AniTube-UNPACK] Tokens substituídos: $replacements")
        println("📦 [AniTube-UNPACK] Resultado length: ${result.length}")
        
        // Preview do resultado decodificado
        val previewLength = min(500, result.length)
        val preview = result.substring(0, previewLength)
        println("📦 [AniTube-UNPACK] Preview (primeiros $previewLength chars):")
        println("-".repeat(50))
        println(preview)
        if (result.length > previewLength) {
            println("... [${result.length - previewLength} chars restantes]")
        }
        println("-".repeat(50))
        println("📦".repeat(50))
        
        return result
    }

    // ============== EXTRATOR DE LINKS COM LOGS DETALHADOS ==============
    private fun extractVideoLinksFromDecoded(decoded: String): List<String> {
        println("\n" + "🔍".repeat(50))
        println("🔍 [AniTube-EXTRACT] Analisando texto decodificado...")
        println("🔍 [AniTube-EXTRACT] Tamanho: ${decoded.length} caracteres")
        
        val links = mutableListOf<String>()
        
        try {
            // Primeiro, procurar por URLs googlevideo.com
            println("\n🔍 [AniTube-EXTRACT] Buscando URLs googlevideo.com...")
            val googlevideoPattern = Regex("""https?://[^\s'"]*\.googlevideo\.com/[^\s'"]*""", RegexOption.IGNORE_CASE)
            val googlevideoMatches = googlevideoPattern.findAll(decoded).toList()
            
            println("🔍 [AniTube-EXTRACT] Encontrados ${googlevideoMatches.size} URLs googlevideo.com")
            
            googlevideoMatches.forEachIndexed { index, match ->
                val url = match.value
                val hasItag = url.contains("itag=", true)
                println("🔍 [AniTube-EXTRACT]   [$index] ${url.take(80)}...")
                println("🔍 [AniTube-EXTRACT]        Tem itag? $hasItag")
                
                if (hasItag && !links.contains(url)) {
                    links.add(url)
                }
            }
            
            // Segundo, procurar por videoplayback
            println("\n🔍 [AniTube-EXTRACT] Buscando URLs videoplayback...")
            val videoplaybackPattern = Regex("""https?://[^\s'"]*videoplayback[^\s'"]*""", RegexOption.IGNORE_CASE)
            val videoplaybackMatches = videoplaybackPattern.findAll(decoded).toList()
            
            println("🔍 [AniTube-EXTRACT] Encontrados ${videoplaybackMatches.size} URLs videoplayback")
            
            videoplaybackMatches.forEachIndexed { index, match ->
                val url = match.value
                if (!links.contains(url)) {
                    links.add(url)
                    println("🔍 [AniTube-EXTRACT]   [$index] ${url.take(80)}...")
                }
            }
            
            // Terceiro, procurar por m3u8
            println("\n🔍 [AniTube-EXTRACT] Buscando URLs m3u8...")
            val m3u8Pattern = Regex("""https?://[^\s'"]*\.m3u8[^\s'"]*""", RegexOption.IGNORE_CASE)
            val m3u8Matches = m3u8Pattern.findAll(decoded).toList()
            
            println("🔍 [AniTube-EXTRACT] Encontrados ${m3u8Matches.size} URLs m3u8")
            
            m3u8Matches.forEachIndexed { index, match ->
                val url = match.value
                if (!links.contains(url)) {
                    links.add(url)
                    println("🔍 [AniTube-EXTRACT]   [$index] ${url.take(80)}...")
                }
            }
            
            // Quarto, procurar por mp4
            println("\n🔍 [AniTube-EXTRACT] Buscando URLs mp4...")
            val mp4Pattern = Regex("""https?://[^\s'"]*\.mp4[^\s'"]*""", RegexOption.IGNORE_CASE)
            val mp4Matches = mp4Pattern.findAll(decoded).toList()
            
            println("🔍 [AniTube-EXTRACT] Encontrados ${mp4Matches.size} URLs mp4")
            
            mp4Matches.forEachIndexed { index, match ->
                val url = match.value
                if (!links.contains(url)) {
                    links.add(url)
                    println("🔍 [AniTube-EXTRACT]   [$index] ${url.take(80)}...")
                }
            }
            
            // Busca genérica por URLs
            println("\n🔍 [AniTube-EXTRACT] Buscando URLs genéricas...")
            val urlPattern = Regex("""https?://[^\s'"]+""", RegexOption.IGNORE_CASE)
            val allUrlMatches = urlPattern.findAll(decoded).toList()
            
            println("🔍 [AniTube-EXTRACT] Encontradas ${allUrlMatches.size} URLs totais")
            
            // Filtrar URLs interessantes
            allUrlMatches.forEachIndexed { index, match ->
                val url = match.value
                if ((url.contains("video", true) || url.contains("stream", true) || 
                     url.contains("play", true)) && 
                    !links.contains(url)) {
                    
                    // Mostrar apenas algumas para não poluir logs
                    if (links.size < 20) {
                        println("🔍 [AniTube-EXTRACT]   [${links.size}] ${url.take(80)}...")
                    }
                    links.add(url)
                }
            }
            
        } catch (e: Exception) {
            println("🔍 [AniTube-EXTRACT] ❌ Erro durante extração: ${e.message}")
            e.printStackTrace()
        }
        
        // Remover duplicados
        val uniqueLinks = links.distinct()
        
        println("\n🔍 [AniTube-EXTRACT] 📊 RESUMO:")
        println("🔍 [AniTube-EXTRACT]   Links totais encontrados: ${links.size}")
        println("🔍 [AniTube-EXTRACT]   Links únicos: ${uniqueLinks.size}")
        
        if (uniqueLinks.isNotEmpty()) {
            println("🔍 [AniTube-EXTRACT]   Primeiros 5 links únicos:")
            uniqueLinks.take(5).forEachIndexed { index, url ->
                println("🔍 [AniTube-EXTRACT]     [$index] ${url.take(100)}...")
            }
        }
        
        println("🔍".repeat(50))
        
        return uniqueLinks
    }

    // ============== ANALISAR HTML PARA DEBUG ==============
    private fun analyzeHtmlForDebug(html: String, url: String) {
        println("\n" + "🔬".repeat(60))
        println("🔬 [AniTube-DEBUG] ANALISANDO HTML PARA DEBUG")
        println("🔬 [AniTube-DEBUG] URL: $url")
        println("🔬 [AniTube-DEBUG] HTML length: ${html.length} caracteres")
        
        try {
            // 1. Mostrar começo do HTML
            println("\n🔬 [AniTube-DEBUG] PRIMEIROS 500 CARACTERES:")
            println("-".repeat(60))
            val startPreview = if (html.length > 500) html.substring(0, 500) + "..." else html
            println(startPreview)
            println("-".repeat(60))
            
            // 2. Mostrar final do HTML
            println("\n🔬 [AniTube-DEBUG] ÚLTIMOS 500 CARACTERES:")
            println("-".repeat(60))
            val endPreview = if (html.length > 500) "..." + html.substring(html.length - 500) else html
            println(endPreview)
            println("-".repeat(60))
            
            // 3. Contar iframes
            val iframeCount = html.count { it == '<' && html.indexOf("iframe", html.indexOf('<')) != -1 }
            println("\n🔬 [AniTube-DEBUG] Iframes encontrados: $iframeCount")
            
            // 4. Buscar scripts
            val scriptCount = html.count { it == '<' && html.indexOf("script", html.indexOf('<')) != -1 }
            println("🔬 [AniTube-DEBUG] Scripts encontrados: $scriptCount")
            
            // 5. Procurar por padrões específicos
            println("\n🔬 [AniTube-DEBUG] PADRÕES ESPECÍFICOS:")
            
            val patterns = mapOf(
                "eval(function(p,a,c,k,e,d)" to "Packer code padrão",
                "jwplayer" to "JW Player",
                "googlevideo.com" to "Google Video",
                "videoplayback" to "Video Playback",
                ".m3u8" to "M3U8 links",
                "itag=" to "YouTube itags",
                "api.anivideo.net" to "AniVideo API",
                "bg.mp4" to "BG MP4 iframe",
                "metaframe" to "Metaframe iframe"
            )
            
            patterns.forEach { (pattern, description) ->
                val count = pattern.toRegex(RegexOption.IGNORE_CASE).findAll(html).count()
                println("🔬 [AniTube-DEBUG]   $description ('$pattern'): $count ocorrências")
            }
            
            // 6. Extrair URLs para análise
            println("\n🔬 [AniTube-DEBUG] URLs ENCONTRADAS NO HTML:")
            val urlRegex = Regex("""https?://[^\s'"]+""", RegexOption.IGNORE_CASE)
            val urls = urlRegex.findAll(html).toList()
            
            println("🔬 [AniTube-DEBUG] Total URLs: ${urls.size}")
            
            // Mostrar URLs interessantes
            val interestingUrls = urls.filter { 
                it.value.contains("googlevideo.com") || 
                it.value.contains("anivideo.net") || 
                it.value.contains(".m3u8") ||
                it.value.contains("videoplayback")
            }
            
            println("🔬 [AniTube-DEBUG] URLs interessantes: ${interestingUrls.size}")
            interestingUrls.take(10).forEachIndexed { index, match ->
                println("🔬 [AniTube-DEBUG]   [$index] ${match.value.take(100)}...")
            }
            
            // 7. Buscar iframes especificamente
            println("\n🔬 [AniTube-DEBUG] IFRAMES ESPECÍFICOS:")
            val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            val iframes = iframeRegex.findAll(html).toList()
            
            println("🔬 [AniTube-DEBUG] Iframes parseados: ${iframes.size}")
            iframes.forEachIndexed { index, match ->
                val src = match.groupValues.getOrNull(1)
                if (src != null) {
                    println("🔬 [AniTube-DEBUG]   [$index] src: ${src.take(100)}...")
                }
            }
            
        } catch (e: Exception) {
            println("🔬 [AniTube-DEBUG] ❌ Erro na análise: ${e.message}")
        }
        
        println("🔬".repeat(60))
    }

    // ============== JW PLAYER EXTRACTION COM DEBUG COMPLETO ==============
    private suspend fun extractJWPlayerLinks(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        println("\n" + "🎬".repeat(60))
        println("🎬 [AniTube-JW] INICIANDO EXTRACTION JW PLAYER COM DEBUG")
        println("🎬 [AniTube-JW] Iframe SRC original: $iframeSrc")
        println("🎬 [AniTube-JW] Video URL referência: $videoUrl")
        println("🎬".repeat(60))
        
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // PASSO 1: Primeira requisição (seguir redirecionamentos)
            println("\n🎬 [AniTube-JW] 📡 PASSO 1: Fazendo primeira requisição...")
            val response1 = app.get(
                iframeSrc,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to videoUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                ),
                allowRedirects = false
            )
            
            println("🎬 [AniTube-JW] 📊 Status: ${response1.code}")
            println("🎬 [AniTube-JW] 📊 Headers: ${response1.headers}")
            
            var playerUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null && (response1.code == 301 || response1.code == 302)) {
                playerUrl = location
                println("🎬 [AniTube-JW] 📍 Redirecionado para: $playerUrl")
            } else {
                println("🎬 [AniTube-JW] 📍 Sem redirecionamento, usando URL original")
            }
            
            // PASSO 2: Segunda requisição para obter HTML real
            println("\n🎬 [AniTube-JW] 📡 PASSO 2: Fazendo segunda requisição...")
            println("🎬 [AniTube-JW] 📡 URL final: $playerUrl")
            
            val response2 = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Origin" to "https://api.anivideo.net"
                ),
                timeout = 30000
            )
            
            val playerHtml = response2.text
            println("🎬 [AniTube-JW] 📄 HTML obtido: ${playerHtml.length} caracteres")
            
            // ANALISAR HTML PARA DEBUG
            analyzeHtmlForDebug(playerHtml, playerUrl)
            
            // PASSO 3: Buscar packer code com múltiplos regex
            println("\n🎬 [AniTube-JW] 🔍 PASSO 3: Buscando packer code com regex...")
            
            // Lista de padrões de regex CORRIGIDOS
            val regexPatterns = listOf(
                // Padrão 1: JavaScript padrão COM split('|')
                """eval\(function\(p,a,c,k,e,d\).*?}\('([^']+)',(\d+),(\d+),'([^']+)'\.split\('\|'\)""",
                
                // Padrão 2: JavaScript padrão SEM split
                """eval\(function\(p,a,c,k,e,d\).*?}\('([^']+)',(\d+),(\d+),'([^']+)'""",
                
                // Padrão 3: Com } escapado
                """\}\('([^']{100,})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']{100,})'""",
                
                // Padrão 4: Genérico para packer
                """function\(p,a,c,k,e,d\).*?\('([^']{50,})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']{50,})'""",
                
                // Padrão 5: Buscar qualquer coisa que pareça packer
                """\('([^']{100,})'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']{100,})'""",
                
                // Padrão 6: Para packer code em linha única
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\('([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'"""
            )
            
            var foundMatch: MatchResult? = null
            var usedPatternIndex = -1
            var usedPattern = ""
            
            // Testar cada padrão individualmente com debug
            println("\n🎬 [AniTube-JW] 🔬 TESTANDO CADA PADRÃO DE REGEX:")
            println("🎬 [AniTube-JW] " + "-".repeat(50))
            
            regexPatterns.forEachIndexed { index, pattern ->
                if (foundMatch == null) {
                    println("\n🎬 [AniTube-JW] 🔍 Testando padrão ${index + 1}:")
                    println("🎬 [AniTube-JW]   Regex: ${pattern.take(80)}...")
                    
                    try {
                        val regex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                        val match = regex.find(playerHtml)
                        
                        if (match != null) {
                            foundMatch = match
                            usedPatternIndex = index + 1
                            usedPattern = pattern
                            
                            println("🎬 [AniTube-JW]   ✅ MATCH ENCONTRADO!")
                            println("🎬 [AniTube-JW]   Grupos encontrados: ${match.groupValues.size}")
                            
                            match.groupValues.forEachIndexed { groupIndex, group ->
                                println("🎬 [AniTube-JW]     Grupo $groupIndex (${group.length} chars):")
                                if (group.length > 100) {
                                    println("🎬 [AniTube-JW]       ${group.take(100)}...")
                                } else {
                                    println("🎬 [AniTube-JW]       $group")
                                }
                            }
                        } else {
                            println("🎬 [AniTube-JW]   ❌ Nenhum match")
                        }
                    } catch (e: Exception) {
                        println("🎬 [AniTube-JW]   💥 ERRO no regex: ${e.message}")
                        println("🎬 [AniTube-JW]   Pattern problemático: $pattern")
                    }
                }
            }
            
            // PASSO 4: Processar packer code se encontrado
            if (foundMatch != null && foundMatch!!.groupValues.size >= 5) {
                println("\n🎬 [AniTube-JW] ✅ PACKER CODE ENCONTRADO!")
                println("🎬 [AniTube-JW]   Padrão usado: #$usedPatternIndex")
                println("🎬 [AniTube-JW]   Regex: ${usedPattern.take(100)}...")
                
                try {
                    val p = foundMatch!!.groupValues[1].replace("\\'", "'")
                    val a = foundMatch!!.groupValues[2].toIntOrNull() ?: 62
                    val c = foundMatch!!.groupValues[3].toIntOrNull() ?: 361
                    val k = foundMatch!!.groupValues[4]
                    
                    println("🎬 [AniTube-JW] 📦 Parâmetros unpack:")
                    println("🎬 [AniTube-JW]   - p (compactado): ${p.length} chars")
                    println("🎬 [AniTube-JW]   - a (base): $a")
                    println("🎬 [AniTube-JW]   - c (contagem): $c")
                    println("🎬 [AniTube-JW]   - k (dicionário): ${k.length} chars")
                    
                    // Preview do dicionário
                    val kPreview = if (k.length > 200) k.substring(0, 200) + "..." else k
                    println("🎬 [AniTube-JW]   - k preview: $kPreview")
                    
                    // Executar unpack
                    println("\n🎬 [AniTube-JW] 🚀 Executando unpack...")
                    val decoded = unpack(p, a, c, k)
                    
                    // Extrair links do resultado
                    println("\n🎬 [AniTube-JW] 🔍 Extraindo links do decoded...")
                    val videoLinks = extractVideoLinksFromDecoded(decoded)
                    
                    // Processar links
                    println("\n🎬 [AniTube-JW] ⚙️ Processando ${videoLinks.size} links...")
                    
                    videoLinks.forEachIndexed { index, url ->
                        try {
                            println("\n🎬 [AniTube-JW] 🔗 Processando link ${index + 1}:")
                            println("🎬 [AniTube-JW]   URL: ${url.take(100)}...")
                            
                            // Determinar qualidade baseada no itag
                            val quality = when {
                                url.contains("itag=37") || url.contains("itag=46") || url.contains("itag=299") -> 1080
                                url.contains("itag=22") || url.contains("itag=45") || url.contains("itag=298") -> 720
                                url.contains("itag=59") || url.contains("itag=44") || url.contains("itag=397") -> 480
                                url.contains("itag=18") || url.contains("itag=43") || url.contains("itag=396") -> 360
                                url.contains("itag=34") || url.contains("itag=35") -> 480
                                url.contains("itag=5") || url.contains("itag=36") -> 240
                                url.contains("1080") || url.contains("fullhd") -> 1080
                                url.contains("720") || url.contains("hd") -> 720
                                url.contains("480") || url.contains("sd") -> 480
                                url.contains("360") -> 360
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
                            
                            println("🎬 [AniTube-JW]   Qualidade detectada: $qualityLabel")
                            
                            // Criar ExtractorLink
                            val extractorLink = newExtractorLink(
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
                                    "Accept-Encoding" to "identity",
                                    "Range" to "bytes=0-"
                                )
                            }
                            
                            links.add(extractorLink)
                            println("🎬 [AniTube-JW]   ✅ Link $qualityLabel adicionado!")
                            
                        } catch (e: Exception) {
                            println("🎬 [AniTube-JW]   ❌ Erro processando link ${index + 1}: ${e.message}")
                        }
                    }
                    
                } catch (e: Exception) {
                    println("🎬 [AniTube-JW] 💥 ERRO durante unpack: ${e.message}")
                    e.printStackTrace()
                }
                
            } else {
                println("\n🎬 [AniTube-JW] ❌ NENHUM PACKER CODE ENCONTRADO COM NENHUM PADRÃO!")
                
                // PASSO 5: Buscar links diretamente no HTML (fallback)
                println("\n🎬 [AniTube-JW] 🔄 Fallback: Buscando links diretamente no HTML...")
                
                val directUrlPatterns = listOf(
                    """https?://[^\s'"]*\.googlevideo\.com/[^\s'"]*""",
                    """https?://[^\s'"]*videoplayback[^\s'"]*""",
                    """https?://[^\s'"]*\.m3u8[^\s'"]*""",
                    """file:[^\s'"]*\.mp4[^\s'"]*""",
                    """https?://[^\s'"]*\.mp4[^\s'"]*"""
                )
                
                val allDirectLinks = mutableListOf<String>()
                
                directUrlPatterns.forEachIndexed { index, pattern ->
                    println("\n🎬 [AniTube-JW]   Padrão ${index + 1}: '$pattern'")
                    try {
                        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                        val matches = regex.findAll(playerHtml).toList()
                        
                        println("🎬 [AniTube-JW]     Encontrados: ${matches.size}")
                        
                        matches.forEach { match ->
                            val url = match.value
                            if (!allDirectLinks.contains(url)) {
                                allDirectLinks.add(url)
                                
                                // Mostrar apenas algumas para não poluir
                                if (allDirectLinks.size <= 10) {
                                    println("🎬 [AniTube-JW]     [${allDirectLinks.size}] ${url.take(100)}...")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("🎬 [AniTube-JW]     ❌ Erro no padrão: ${e.message}")
                    }
                }
                
                // Processar links diretos
                println("\n🎬 [AniTube-JW] ⚙️ Processando ${allDirectLinks.size} links diretos...")
                
                allDirectLinks.forEachIndexed { index, url ->
                    try {
                        println("🎬 [AniTube-JW]   🔗 Link direto ${index + 1}: ${url.take(100)}...")
                        
                        // Determinar qualidade
                        val quality = when {
                            url.contains("1080") || url.contains("fullhd") || url.contains("itag=37") -> 1080
                            url.contains("720") || url.contains("hd") || url.contains("itag=22") -> 720
                            url.contains("480") || url.contains("sd") || url.contains("itag=59") -> 480
                            url.contains("360") || url.contains("itag=18") -> 360
                            else -> 360
                        }
                        
                        val qualityLabel = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            480 -> "480p"
                            360 -> "360p"
                            else -> "SD"
                        }
                        
                        // Criar ExtractorLink para link direto
                        val extractorLink = newExtractorLink(
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
                        
                        links.add(extractorLink)
                        println("🎬 [AniTube-JW]   ✅ Link direto $qualityLabel adicionado!")
                        
                    } catch (e: Exception) {
                        println("🎬 [AniTube-JW]   ❌ Erro processando link direto ${index + 1}: ${e.message}")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("\n🎬 [AniTube-JW] 💥 ERRO CRÍTICO: ${e.message}")
            e.printStackTrace()
        }
        
        println("\n🎬 [AniTube-JW] 📊 RESULTADO FINAL:")
        println("🎬 [AniTube-JW]   Total links retornados: ${links.size}")
        
        if (links.isNotEmpty()) {
            println("🎬 [AniTube-JW]   Lista de links:")
            links.forEachIndexed { index, link ->
                println("🎬 [AniTube-JW]     [$index] ${link.name} - ${link.url.take(80)}...")
            }
        } else {
            println("🎬 [AniTube-JW]   ⚠️ Nenhum link encontrado!")
        }
        
        println("🎬".repeat(60))
        return links
    }

    // ============== M3U8 EXTRACTION COM DEBUG ==============
    private suspend fun extractM3u8LinksFromPage(document: org.jsoup.nodes.Document): List<ExtractorLink> {
        println("\n" + "📺".repeat(50))
        println("📺 [AniTube-M3U8] Buscando links M3U8 com debug...")
        
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // 1. Player FHD
            println("\n📺 [AniTube-M3U8] 🔍 Buscando Player FHD...")
            val fhdIframe = document.selectFirst(PLAYER_FHD)
            if (fhdIframe != null) {
                val src = fhdIframe.attr("src")
                println("📺 [AniTube-M3U8]   ✅ Player FHD encontrado: ${src.take(100)}...")
                
                if (src.isNotBlank() && src.contains("m3u8", true)) {
                    println("📺 [AniTube-M3U8]   🎬 Contém m3u8!")
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
            } else {
                println("📺 [AniTube-M3U8]   ❌ Player FHD não encontrado")
            }
            
            // 2. Scripts com M3U8
            println("\n📺 [AniTube-M3U8] 🔍 Analisando scripts...")
            val scripts = document.select("script")
            println("📺 [AniTube-M3U8]   Total scripts: ${scripts.size}")
            
            var scriptWithM3U8 = 0
            scripts.forEachIndexed { index, script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8", true)) {
                    scriptWithM3U8++
                    
                    if (scriptWithM3U8 <= 3) { // Mostrar apenas primeiros 3
                        println("📺 [AniTube-M3U8]   Script $index contém 'm3u8'")
                        
                        val m3u8Regex = Regex("""https?://[^"'\s]*\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE)
                        val matches = m3u8Regex.findAll(scriptContent)
                        
                        matches.forEach { match ->
                            val m3u8Url = match.value
                            if (!m3u8Url.contains("anivideo.net", true)) {
                                println("📺 [AniTube-M3U8]     🎬 M3U8 encontrado: ${m3u8Url.take(100)}...")
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
            }
            
            println("📺 [AniTube-M3U8]   Scripts com m3u8: $scriptWithM3U8")
            
            // 3. Buscar em todo o HTML
            println("\n📺 [AniTube-M3U8] 🔍 Buscando m3u8 em todo HTML...")
            val html = document.html()
            val allM3U8Regex = Regex("""(https?://[^"'\s]*\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
            val allMatches = allM3U8Regex.findAll(html)
            
            val uniqueUrls = mutableSetOf<String>()
            allMatches.forEach { match ->
                uniqueUrls.add(match.value)
            }
            
            println("📺 [AniTube-M3U8]   URLs m3u8 únicas encontradas: ${uniqueUrls.size}")
            
            uniqueUrls.forEachIndexed { index, url ->
                if (index < 5) { // Mostrar apenas primeiras 5
                    println("📺 [AniTube-M3U8]     [$index] ${url.take(100)}...")
                }
                
                // Adicionar se não for da API
                if (!url.contains("anivideo.net", true)) {
                    links.add(
                        newExtractorLink(
                            name,
                            "M3U8 HTML",
                            url,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = "$mainUrl/"
                            quality = 720
                        }
                    )
                }
            }
            
        } catch (e: Exception) {
            println("📺 [AniTube-M3U8] ❌ Erro: ${e.message}")
        }
        
        println("\n📺 [AniTube-M3U8] 📊 Total M3U8 encontrados: ${links.size}")
        println("📺".repeat(50))
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

    // ============== LOAD LINKS PRINCIPAL COM DEBUG COMPLETO ==============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n" + "🚀".repeat(80))
        println("🚀 [AniTube] LOADLINKS INICIADO COM DEBUG COMPLETO!")
        println("🚀 [AniTube] Data completa: $data")
        
        val parts = data.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }
        
        println("🚀 [AniTube] URL real: $actualUrl")
        println("🚀 [AniTube] Thumb poster: $thumbPoster")
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
            println("\n📄 [AniTube] PASSO 1: Carregando página...")
            val response = app.get(actualUrl)
            val document = response.document
            
            println("📄 [AniTube] ✅ Página carregada")
            println("📄 [AniTube] Status: ${response.code}")
            println("📄 [AniTube] URL final: ${response.url}")
            
            // 2. Log de iframes
            val allIframes = document.select("iframe")
            println("\n📄 [AniTube] Total iframes: ${allIframes.size}")
            
            allIframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                val className = iframe.attr("class")
                val id = iframe.attr("id")
                
                println("📄 [AniTube] Iframe $index:")
                println("📄 [AniTube]   src: ${src.take(100)}...")
                println("📄 [AniTube]   class: $className")
                println("📄 [AniTube]   id: $id")
                
                // Verificar se é do tipo que procuramos
                val isMetaframe = className.contains("metaframe", true)
                val isBgMp4 = src.contains("bg.mp4", true)
                val isPlayer = src.contains("api.anivideo", true) || src.contains("anitube.news/n", true)
                
                println("📄 [AniTube]   isMetaframe: $isMetaframe")
                println("📄 [AniTube]   isBgMp4: $isBgMp4")
                println("📄 [AniTube]   isPlayer: $isPlayer")
            }
            
            // ============== TENTATIVA 1: JW PLAYER ==============
            println("\n" + "🎯".repeat(50))
            println("🎯 [AniTube] TENTATIVA 1: JW PLAYER (PRIORIDADE)")
            println("🎯".repeat(50))
            
            var jwLinksFound = false
            
            // Buscar iframes específicos para JW Player
            val jwIframes = document.select(PLAYER_IFRAME)
            println("🎯 [AniTube] Iframes JW encontrados: ${jwIframes.size}")
            
            if (jwIframes.isNotEmpty()) {
                jwIframes.forEachIndexed { index, iframe ->
                    if (!jwLinksFound) {
                        val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        println("\n🎯 [AniTube] ✅ Iframe JW $index encontrado: ${src.take(100)}...")
                        
                        val jwLinks = extractJWPlayerLinks(src, actualUrl)
                        if (jwLinks.isNotEmpty()) {
                            println("\n🎯 [AniTube] 🎉 JW Player retornou ${jwLinks.size} links!")
                            jwLinks.forEach { 
                                println("🎯 [AniTube]   ➡️  Enviando link: ${it.name}")
                                callback(it)
                                linksFound = true
                                jwLinksFound = true
                            }
                        } else {
                            println("🎯 [AniTube] ❌ JW Player não retornou links")
                        }
                    }
                }
            } else {
                println("🎯 [AniTube] ⚠️  Nenhum iframe JW Player encontrado com os seletores")
            }
            
            if (!jwLinksFound) {
                println("\n🎯 [AniTube] Tentando fallback: buscar qualquer iframe com player...")
                
                // Fallback: buscar qualquer iframe que pareça player
                document.select("iframe").forEachIndexed { index, iframe ->
                    if (!jwLinksFound) {
                        val src = iframe.attr("src")
                        if (src.isNotBlank() && 
                            (src.contains("api.anivideo", true) || 
                             src.contains("bg.mp4", true) || 
                             src.contains("/n", true))) {
                            
                            println("\n🎯 [AniTube] 🔄 Fallback iframe $index: ${src.take(100)}...")
                            val fallbackLinks = extractJWPlayerLinks(src, actualUrl)
                            
                            if (fallbackLinks.isNotEmpty()) {
                                println("\n🎯 [AniTube] 🎉 Fallback retornou ${fallbackLinks.size} links!")
                                fallbackLinks.forEach {
                                    callback(it)
                                    linksFound = true
                                    jwLinksFound = true
                                }
                            }
                        }
                    }
                }
            }
            
            // ============== TENTATIVA 2: M3U8 ==============
            if (!linksFound) {
                println("\n" + "🎯".repeat(50))
                println("🎯 [AniTube] TENTATIVA 2: M3U8")
                println("🎯".repeat(50))
                
                val m3u8Links = extractM3u8LinksFromPage(document)
                if (m3u8Links.isNotEmpty()) {
                    println("\n🎯 [AniTube] 🎉 M3U8 retornou ${m3u8Links.size} links!")
                    m3u8Links.forEach {
                        println("🎯 [AniTube]   ➡️  Enviando link M3U8: ${it.name}")
                        callback(it)
                        linksFound = true
                    }
                } else {
                    println("🎯 [AniTube] ❌ M3U8 não retornou links")
                }
            }
            
            // ============== TENTATIVA 3: PLAYER BACKUP ==============
            if (!linksFound) {
                println("\n" + "🎯".repeat(50))
                println("🎯 [AniTube] TENTATIVA 3: PLAYER BACKUP")
                println("🎯".repeat(50))
                
                document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                    println("🎯 [AniTube] ✅ Player Backup encontrado: ${src.take(100)}...")
                    
                    // IMPORTANTE: Tentar processar este iframe também!
                    println("🎯 [AniTube] 🔄 Tentando processar Player Backup como JW Player...")
                    val backupLinks = extractJWPlayerLinks(src, actualUrl)
                    
                    if (backupLinks.isNotEmpty()) {
                        println("🎯 [AniTube] 🎉 Player Backup processado retornou ${backupLinks.size} links!")
                        backupLinks.forEach {
                            println("🎯 [AniTube]   ➡️  Enviando link Backup: ${it.name}")
                            callback(it)
                            linksFound = true
                        }
                    } else {
                        println("🎯 [AniTube] ⚠️  Player Backup não pode ser processado")
                        
                        // Se não conseguir processar, tentar adicionar como link direto
                        println("🎯 [AniTube] ⚠️  Adicionando como link direto (pode não funcionar)...")
                        try {
                            val backupLink = newExtractorLink(
                                name,
                                "Player Backup", 
                                src,
                                ExtractorLinkType.VIDEO
                            ) {
                                referer = "$mainUrl/"
                                quality = 720
                            }
                            
                            callback(backupLink)
                            linksFound = true
                            println("🎯 [AniTube] ⚠️  Link Backup adicionado")
                        } catch (e: Exception) {
                            println("🎯 [AniTube] ❌ Erro ao adicionar link Backup: ${e.message}")
                        }
                    }
                } ?: run {
                    println("🎯 [AniTube] ❌ Player Backup não encontrado")
                }
            }
            
            // ============== TENTATIVA 4: BUSCA DIRETA NO HTML ==============
            if (!linksFound) {
                println("\n" + "🎯".repeat(50))
                println("🎯 [AniTube] TENTATIVA 4: BUSCA DIRETA NO HTML")
                println("🎯".repeat(50))
                
                try {
                    val html = document.html()
                    println("🎯 [AniTube] HTML size: ${html.length} chars")
                    
                    // Buscar URLs de vídeo diretamente
                    val videoUrlPatterns = listOf(
                        """https?://[^\s'"]*\.googlevideo\.com/[^\s'"]*""",
                        """https?://[^\s'"]*\.m3u8[^\s'"]*""",
                        """https?://[^\s'"]*\.mp4[^\s'"]*""",
                        """file:[^\s'"]*\.(mp4|m3u8)[^\s'"]*"""
                    )
                    
                    val foundUrls = mutableSetOf<String>()
                    
                    videoUrlPatterns.forEach { pattern ->
                        try {
                            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                            val matches = regex.findAll(html).toList()
                            
                            matches.forEach { match ->
                                val url = match.value
                                if (!foundUrls.contains(url)) {
                                    foundUrls.add(url)
                                    
                                    // Mostrar algumas URLs
                                    if (foundUrls.size <= 5) {
                                        println("🎯 [AniTube]   URL encontrada: ${url.take(100)}...")
                                    }
                                    
                                    // Tentar criar ExtractorLink
                                    try {
                                        val directLink = newExtractorLink(
                                            name,
                                            "Direto HTML",
                                            url,
                                            ExtractorLinkType.VIDEO
                                        ) {
                                            referer = actualUrl
                                            quality = 360
                                        }
                                        
                                        callback(directLink)
                                        linksFound = true
                                        println("🎯 [AniTube]   ✅ Link direto adicionado")
                                    } catch (e: Exception) {
                                        println("🎯 [AniTube]   ❌ Erro ao criar link: ${e.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("🎯 [AniTube] ❌ Erro no padrão '$pattern': ${e.message}")
                        }
                    }
                    
                    println("🎯 [AniTube] URLs únicas encontradas: ${foundUrls.size}")
                    
                } catch (e: Exception) {
                    println("🎯 [AniTube] ❌ Erro na busca direta: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("\n💥 [AniTube] ERRO CRÍTICO em loadLinks: ${e.message}")
            e.printStackTrace()
        }
        
        // ============== RESULTADO FINAL ==============
        println("\n" + "📊".repeat(80))
        println("📊 [AniTube] RESULTADO FINAL DO LOADLINKS")
        println("📊 [AniTube] Links encontrados: $linksFound")
        println("📊 [AniTube] URL processada: $actualUrl")
        
        if (linksFound) {
            println("📊 [AniTube] ✅ SUCESSO! Retornando TRUE")
        } else {
            println("📊 [AniTube] ❌ FALHA - Nenhum link encontrado, retornando FALSE")
        }
        
        println("📊".repeat(80) + "\n")
        
        return linksFound
    }

    // ============== RESTANTE DO CÓDIGO (INALTERADO) ==============

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
