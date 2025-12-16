package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    // URL correta do site
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        
        // Cloudflare Workers AI para tradução
        private const val CF_WORKER_URL = "https://animefire.euluan1912.workers.dev/"
    }

    // 4 ABAS DA PÁGINA INICIAL
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ FUNÇÃO AUXILIAR PARA SEARCH RESPONSE ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair imagem
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        // Filtrar logo do site
        if (poster.contains("logo", ignoreCase = true)) return null
        
        // Limpar título
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        // Verificar se é filme
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = fixUrl(poster)
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ FUNÇÕES PARA EXTRACTION ============
    private fun extractFromCarousel(document: org.jsoup.nodes.Document, selector: String): List<AnimeSearchResponse> {
        return document.select(selector).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                extractFromCarousel(document, ".owl-carousel-home .divArticleLancamentos a.item")
            "Destaques da Semana" -> 
                extractFromCarousel(document, ".owl-carousel-semana .divArticleLancamentos a.item")
            "Últimos Animes Adicionados" -> 
                extractFromCarousel(document, ".owl-carousel-l_dia .divArticleLancamentos a.item")
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    val link = card.selectFirst("article.card a") ?: return@mapNotNull null
                    val href = link.attr("href") ?: return@mapNotNull null
                    
                    val titleElement = card.selectFirst("h3.animeTitle") ?: return@mapNotNull null
                    val title = titleElement.text().trim()
                    
                    val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                    
                    val imgElement = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                        imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                        else -> card.selectFirst("img:not([src*='logo'])")?.attr("src")
                    } ?: return@mapNotNull null
                    
                    val cleanTitle = "${title} - Episódio $epNumber"
                    
                    newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                        this.posterUrl = fixUrl(poster)
                        this.type = TvType.Anime
                    }
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item")
            .mapNotNull { it.toSearchResponse() }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse {
        println("\n" + "=".repeat(80))
        println("🚀 [INÍCIO] AnimeFire.load() chamado para URL: $url")
        println("=".repeat(80))
        
        val document = app.get(url).document

        // Título
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime
        
        println("📌 [METADATA] Título: $cleanTitle")
        println("📌 [METADATA] Ano: $year")
        println("📌 [METADATA] Tipo: ${if (isMovie) "Movie" else "Anime"}")

        // 1. TESTAR WORKER PRIMEIRO
        println("\n🧪 [TESTE] Testando conexão com Cloudflare Worker...")
        testWorkerConnection()
        println("\n🧪 [TESTE] Testando tradução simples...")
        testSimpleTranslation()

        // 2. BUSCAR MAL ID PELO NOME DO ANIME
        println("\n🔍 [ANIZIP] Buscando MAL ID para: $cleanTitle")
        val malId = searchMALIdByName(cleanTitle)
        println("📌 [ANIZIP] MAL ID encontrado: $malId")

        // 3. BUSCAR DADOS DA ANI.ZIP
        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 [ANIZIP] Buscando dados da ani.zip para MAL ID: $malId")
            aniZipData = fetchAniZipData(malId)
            if (aniZipData != null) {
                println("✅ [ANIZIP] Dados obtidos com sucesso!")
                println("   📊 Títulos: ${aniZipData.titles?.size ?: 0}")
                println("   📊 Imagens: ${aniZipData.images?.size ?: 0}")
                println("   📊 Episódios: ${aniZipData.episodes?.size ?: 0}")
            } else {
                println("❌ [ANIZIP] Não foi possível obter dados da ani.zip")
            }
        } else {
            println("⚠️ [ANIZIP] Nenhum MAL ID encontrado, pulando ani.zip")
        }

        // 4. EXTRAIR METADADOS DO SITE
        println("\n🔍 [SITE] Extraindo metadados do site...")
        val siteMetadata = extractSiteMetadata(document)
        
        // 5. EXTRAIR EPISÓDIOS (com dados da ani.zip e tradução)
        println("\n🔍 [EPISÓDIOS] Extraindo episódios...")
        val episodes = if (!isMovie) {
            extractEpisodesWithTranslation(document, aniZipData)
        } else {
            emptyList()
        }

        // 6. EXTRAIR RECOMENDAÇÕES
        val recommendations = extractRecommendations(document)

        // 7. CRIAR RESPOSTA COM DADOS COMBINADOS
        println("\n🏗️ [RESPONSE] Criando resposta final...")
        val response = createLoadResponseWithTranslation(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            episodes = episodes,
            recommendations = recommendations
        )
        
        println("\n" + "=".repeat(80))
        println("✅ [FIM] AnimeFire.load() concluído com sucesso!")
        println("=".repeat(80))
        
        return response
    }

    // ============ DEBUG DETALHADO DA TRADUÇÃO ============
    
    private suspend fun testWorkerConnection() {
        println("🔧 [WORKER TEST] Testando conexão básica...")
        
        try {
            val response = app.get(CF_WORKER_URL, timeout = 10_000)
            println("📡 [WORKER TEST] Status: ${response.code}")
            println("📡 [WORKER TEST] Headers: ${response.headers}")
            println("📡 [WORKER TEST] Body (primeiros 500 chars): ${response.text.take(500)}")
            
            if (response.code == 200) {
                println("✅ [WORKER TEST] Worker respondeu com sucesso!")
            } else {
                println("❌ [WORKER TEST] Worker retornou status não-200")
            }
        } catch (e: Exception) {
            println("💥 [WORKER TEST] Exception: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun testSimpleTranslation() {
        val testText = "Hello world, this is a test from Cloudstream3"
        println("🧪 [TRANSLATE TEST] Traduzindo: '$testText'")
        
        // Teste com método 1: JSON string direta
        println("\n1️⃣ Método 1: JSON string direta")
        val result1 = translateWithMethod1(testText)
        println("   Resultado: $result1")
        
        // Teste com método 2: Map de dados
        println("\n2️⃣ Método 2: Map de dados")
        val result2 = translateWithMethod2(testText)
        println("   Resultado: $result2")
        
        // Teste com método 3: Form encoded
        println("\n3️⃣ Método 3: Form encoded")
        val result3 = translateWithMethod3(testText)
        println("   Resultado: $result3")
    }
    
    private suspend fun translateWithMethod1(text: String): String? {
        println("   🛠️  Preparando payload JSON string...")
        
        // Escapar caracteres especiais
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        val payload = """{"text":"$escapedText","source_lang":"auto","target_lang":"pt"}"""
        
        println("   📦 Payload (${payload.length} chars):")
        println("   $payload")
        
        return try {
            val response = app.post(
                url = CF_WORKER_URL,
                data = payload,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 30_000,
                allowRedirects = true
            )
            
            println("   📡 Response Code: ${response.code}")
            println("   📡 Response Body (${response.text.length} chars): ${response.text.take(200)}")
            
            if (response.code == 200) {
                val parsed = response.parsedSafe<TranslationResponse>()
                parsed?.translatedText
            } else {
                null
            }
        } catch (e: Exception) {
            println("   💥 Exception: ${e.javaClass.name}: ${e.message}")
            null
        }
    }
    
    private suspend fun translateWithMethod2(text: String): String? {
        println("   🛠️  Preparando payload Map...")
        
        val payload = mapOf(
            "text" to text,
            "source_lang" to "auto",
            "target_lang" to "pt"
        )
        
        println("   📦 Payload Map: $payload")
        
        return try {
            val response = app.post(
                url = CF_WORKER_URL,
                data = payload,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 30_000
            )
            
            println("   📡 Response Code: ${response.code}")
            println("   📡 Response Body: ${response.text.take(200)}")
            
            if (response.code == 200) {
                val parsed = response.parsedSafe<TranslationResponse>()
                parsed?.translatedText
            } else {
                null
            }
        } catch (e: Exception) {
            println("   💥 Exception: ${e.javaClass.name}: ${e.message}")
            null
        }
    }
    
    private suspend fun translateWithMethod3(text: String): String? {
        println("   🛠️  Preparando payload Form encoded...")
        
        val form = listOf(
            "text" to text,
            "source_lang" to "auto",
            "target_lang" to "pt"
        )
        
        println("   📦 Form data: $form")
        
        return try {
            val response = app.post(
                url = CF_WORKER_URL,
                data = form,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json"
                ),
                timeout = 30_000
            )
            
            println("   📡 Response Code: ${response.code}")
            println("   📡 Response Body: ${response.text.take(200)}")
            
            if (response.code == 200) {
                val parsed = response.parsedSafe<TranslationResponse>()
                parsed?.translatedText
            } else {
                null
            }
        } catch (e: Exception) {
            println("   💥 Exception: ${e.javaClass.name}: ${e.message}")
            null
        }
    }
    
    private suspend fun translateText(text: String, sourceLang: String = "auto", targetLang: String = "pt"): String? {
        if (text.isBlank()) return null
        
        println("\n" + "-".repeat(60))
        println("🔤 [TRANSLATE] Iniciando tradução para: '${text.take(50)}${if (text.length > 50) "..." else ""}'")
        println("📏 Comprimento: ${text.length} caracteres")
        
        // Usar método que funcionar melhor
        val result = translateWithMethod1(text)
            ?: translateWithMethod2(text)
            ?: translateWithMethod3(text)
        
        if (result != null) {
            println("✅ [TRANSLATE] Traduzido: '${result.take(50)}${if (result.length > 50) "..." else ""}'")
        } else {
            println("❌ [TRANSLATE] Falha na tradução")
        }
        
        println("-".repeat(60))
        
        return result
    }
    
    // Tradução inteligente: detecta idioma e traduz se não for português
    private suspend fun smartTranslate(text: String): String {
        if (text.isBlank()) return text
        
        // Se o texto já parece estar em português, não traduz
        if (isProbablyPortuguese(text)) {
            println("🇧🇷 [SMART] Texto já em português, pulando tradução")
            return text
        }
        
        println("🌐 [SMART] Texto não-português detectado, tentando traduzir...")
        
        // Tenta traduzir
        return translateText(text) ?: text
    }
    
    private fun isProbablyPortuguese(text: String): Boolean {
        // Lista de palavras comuns em português
        val portugueseWords = setOf(
            "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas",
            "é", "são", "está", "estão", "para", "por", "com", "sem", "que",
            "como", "mas", "e", "ou", "se", "não", "sim", "o", "a", "os", "as",
            "um", "uma", "uns", "umas", "meu", "minha", "teu", "tua", "seu", "sua",
            "nosso", "nossa", "você", "vocês", "ele", "ela", "eles", "elas",
            "aquele", "aquela", "aquilo", "isto", "isso", "este", "esta",
            "onde", "quando", "porque", "porquê", "talvez", "sempre", "nunca",
            "também", "muito", "pouco", "grande", "pequeno", "bom", "mal",
            "hoje", "ontem", "amanhã", "agora", "antes", "depois"
        )
        
        val words = text.lowercase()
            .replace(Regex("[^a-záéíóúâêîôûàèìòùãõç\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 1 }
        
        if (words.isEmpty()) return false
        
        val portugueseCount = words.count { it in portugueseWords }
        val percentage = portugueseCount.toFloat() / words.size
        
        println("   📊 [LANG DETECT] Palavras: ${words.size}, PT: $portugueseCount, Percentual: ${"%.1f".format(percentage * 100)}%")
        
        return percentage > 0.2
    }

    // ============ BUSCA MAL ID ============
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            // Limpar nome para busca
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            println("🔍 [MAL] Buscando: '$cleanName'")
            
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
                            title { romaji english native }
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            println("📡 [MAL] Resposta: ${response.code}")
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                val malId = data?.data?.Page?.media?.firstOrNull()?.idMal
                println("📌 [MAL] ID encontrado: $malId")
                malId
            } else {
                println("❌ [MAL] Erro HTTP: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("❌ [MAL] Exception: ${e.message}")
            null
        }
    }

    // ============ BUSCA ANI.ZIP ============
    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                println("🔍 [ANIZIP] Tentativa $attempt para MAL ID: $malId")
                
                val response = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10_000)
                
                println("📡 [ANIZIP] Status: ${response.code}")
                
                if (response.code == 200) {
                    val data = response.parsedSafe<AniZipData>()
                    if (data != null) {
                        println("✅ [ANIZIP] Dados parseados com sucesso!")
                        return data
                    } else {
                        println("❌ [ANIZIP] Falha no parsing JSON")
                    }
                } else if (response.code == 404) {
                    println("❌ [ANIZIP] MAL ID não encontrado")
                    return null
                } else {
                    println("❌ [ANIZIP] Erro HTTP: ${response.code}")
                }
            } catch (e: Exception) {
                println("❌ [ANIZIP] Exception: ${e.message}")
            }
            
            if (attempt < MAX_TRIES) {
                delay(RETRY_DELAY * attempt)
            }
        }
        
        println("❌ [ANIZIP] Todas as tentativas falharam")
        return null
    }

    // ============ METADADOS DO SITE ============
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        // 1. POSTER
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> {
                val src = posterImg.attr("src")
                println("📸 [SITE] Poster src: $src")
                fixUrl(src)
            }
            posterImg?.hasAttr("data-src") == true -> {
                val dataSrc = posterImg.attr("data-src")
                println("📸 [SITE] Poster data-src: $dataSrc")
                fixUrl(dataSrc)
            }
            else -> {
                val fallback = document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                    ?.attr("src")
                println("⚠️ [SITE] Poster fallback: $fallback")
                fallback?.let { fixUrl(it) }
            }
        }

        // 2. SINOPSE
        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")
        println("📝 [SITE] Sinopse extraída: ${plot?.length ?: 0} caracteres")

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("🏷️ [SITE] Tags encontradas: ${tags?.size ?: 0}")

        // 4. ANO
        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        println("📅 [SITE] Ano: $year")

        return SiteMetadata(poster, plot, tags, year)
    }

    // ============ EPISÓDIOS COM TRADUÇÃO ============
    private suspend fun extractEpisodesWithTranslation(
        document: org.jsoup.nodes.Document,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 [EPISODES] Buscando episódios...")
        
        // Tentar múltiplos seletores
        val selectors = listOf(
            "a.lEp.epT",
            "a.lEp",
            ".divListaEps a",
            "[href*='/video/']",
            "[href*='/episodio/']",
            ".listaEp a"
        )
        
        val episodeElements = selectors.firstNotNullOfOrNull { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                println("✅ [EPISODES] Seletor '$selector' encontrou ${elements.size} elementos")
                elements
            } else {
                null
            }
        } ?: document.select("a").filter { 
            it.attr("href").contains("video") || it.attr("href").contains("episodio") 
        }
        
        println("📊 [EPISODES] Total encontrados: ${episodeElements.size}")
        
        // Limitar tradução para não sobrecarregar
        val maxEpisodesToTranslate = 5
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(text)
                
                // Buscar dados da ani.zip para este episódio
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                // Determinar nome do episódio
                var episodeName = if (aniZipEpisode?.title?.isNotEmpty() == true) {
                    // Prioridade: título da ani.zip
                    aniZipEpisode.title.values.firstOrNull() ?: "Episódio $episodeNumber"
                } else {
                    // Fallback: do site
                    val nameFromSite = text.substringAfterLast("-").trim()
                    if (nameFromSite.isNotBlank() && nameFromSite != text) {
                        nameFromSite
                    } else {
                        "Episódio $episodeNumber"
                    }
                }
                
                println("\n📺 [EP-$episodeNumber] Processando...")
                println("   📝 Nome original: '$episodeName'")
                
                // Traduzir nome do episódio se não for português (limitado aos primeiros)
                if (index < maxEpisodesToTranslate) {
                    val translatedName = smartTranslate(episodeName)
                    if (translatedName != episodeName) {
                        println("   🌐 Nome traduzido: '$translatedName'")
                        episodeName = translatedName
                    }
                }
                
                // Determinar descrição
                var description = aniZipEpisode?.overview
                
                episodes.add(newEpisode(fixUrl(href)) {
                    this.episode = episodeNumber
                    this.season = 1
                    this.name = episodeName
                    this.posterUrl = aniZipEpisode?.image
                    this.description = description
                })
                
                println("   ✅ Adicionado: $episodeName")
            } catch (e: Exception) {
                println("❌ [EPISODE ERROR] Erro ao extrair episódio ${index + 1}: ${e.message}")
            }
            
            // Delay para não sobrecarregar
            if (index < episodeElements.size - 1) {
                delay(100)
            }
        }
        
        println("\n📊 [EPISODES] Total processados: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    return num
                }
            }
        }
        
        // Fallback: extrair qualquer número no texto
        val anyNumber = Regex("\\d+").find(text)?.value?.toIntOrNull()
        return anyNumber ?: 1
    }

    // ============ CRIAR RESPOSTA COM TRADUÇÃO ============
    private suspend fun createLoadResponseWithTranslation(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        
        println("\n🏗️ [RESPONSE] Criando resposta final...")
        
        // DECISÕES FINAIS (prioridade: site > ani.zip)
        val finalPoster = siteMetadata.poster ?: 
            aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) }
        
        val finalBackdrop = aniZipData?.images?.find { 
            it.coverType.equals("Fanart", ignoreCase = true) 
        }?.url?.let { fixUrl(it) }
        
        // Sinopse: traduzir se não for português
        var finalPlot = siteMetadata.plot ?: 
            aniZipData?.episodes?.values?.firstOrNull()?.overview
        
        if (finalPlot != null && finalPlot.isNotBlank() && !isProbablyPortuguese(finalPlot)) {
            println("📝 [PLOT] Traduzindo sinopse...")
            finalPlot = smartTranslate(finalPlot)
        }
        
        val finalYear = year ?: siteMetadata.year
        
        val finalTags = siteMetadata.tags ?: emptyList()
        
        println("📊 [RESPONSE SUMMARY]")
        println("   🖼️  Poster: ${finalPoster ?: "Não encontrado"}")
        println("   🎬 Backdrop: ${finalBackdrop ?: "Não encontrado"}")
        println("   📖 Plot: ${finalPlot?.take(80)}...")
        println("   📅 Ano: $finalYear")
        println("   🏷️  Tags: ${finalTags.take(3).joinToString()}")
        println("   📺 Episódios: ${episodes.size}")
        
        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }
    }

    // ============ LOAD LINKS SIMPLIFICADO ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ [LOAD LINKS] Ainda não implementado")
        return false
    }

    // ============ CLASSES DE DADOS ============

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TranslationResponse(
        @JsonProperty("success") val success: Boolean? = false,
        @JsonProperty("translatedText") val translatedText: String? = null,
        @JsonProperty("originalText") val originalText: String? = null,
        @JsonProperty("originalLength") val originalLength: Int? = null,
        @JsonProperty("translatedLength") val translatedLength: Int? = null,
        @JsonProperty("sourceLang") val sourceLang: String? = null,
        @JsonProperty("targetLang") val targetLang: String? = null,
        @JsonProperty("error") val error: String? = null,
        @JsonProperty("details") val details: String? = null,
