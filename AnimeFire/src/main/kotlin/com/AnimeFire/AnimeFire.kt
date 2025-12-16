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
        private const val CF_WORKER_URL = "https://animefire.euluan1912.workers.dev/" // SUBSTITUA COM SUA URL
        private const val CF_API_TOKEN = "" // SE PRECISAR DE TOKEN
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
        println("🔍 [DEBUG] AnimeFire: Carregando URL: $url")
        
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

        // 1. BUSCAR MAL ID PELO NOME DO ANIME
        println("🔍 [ANIZIP] Buscando MAL ID para: $cleanTitle")
        val malId = searchMALIdByName(cleanTitle)
        println("✅ [ANIZIP] MAL ID encontrado: $malId")

        // 2. BUSCAR DADOS DA ANI.ZIP
        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 [ANIZIP] Buscando dados da ani.zip para MAL ID: $malId")
            aniZipData = fetchAniZipData(malId)
            if (aniZipData != null) {
                println("✅ [ANIZIP] Dados obtidos com sucesso!")
                println("📊 [ANIZIP] Títulos: ${aniZipData.titles?.size ?: 0}")
                println("📊 [ANIZIP] Imagens: ${aniZipData.images?.size ?: 0}")
                println("📊 [ANIZIP] Episódios: ${aniZipData.episodes?.size ?: 0}")
            } else {
                println("❌ [ANIZIP] Não foi possível obter dados da ani.zip")
            }
        } else {
            println("⚠️ [ANIZIP] Nenhum MAL ID encontrado, pulando ani.zip")
        }

        // 3. EXTRAIR METADADOS DO SITE
        val siteMetadata = extractSiteMetadata(document)
        
        // 4. EXTRAIR EPISÓDIOS (com dados da ani.zip e tradução)
        val episodes = if (!isMovie) {
            extractEpisodesWithTranslation(document, aniZipData)
        } else {
            emptyList()
        }

        // 5. EXTRAIR RECOMENDAÇÕES
        val recommendations = extractRecommendations(document)

        // 6. CRIAR RESPOSTA COM DADOS COMBINADOS
        return createLoadResponseWithTranslation(
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
    }

    // ============ TRADUÇÃO COM CLOUDFLARE WORKERS AI ============
    
    private suspend fun translateText(text: String, sourceLang: String = "auto", targetLang: String = "pt"): String? {
        if (text.isBlank()) return null
        
        return try {
            println("🔤 [TRANSLATE] Traduzindo: ${text.take(50)}...")
            
            val payload = mapOf(
                "text" to text,
                "source_lang" to sourceLang,
                "target_lang" to targetLang
            )
            
            val headers = mutableMapOf(
                "Content-Type" to "application/json"
            )
            
            // Se precisar de token (opcional)
            if (CF_API_TOKEN.isNotBlank()) {
                headers["Authorization"] = "Bearer $CF_API_TOKEN"
            }
            
            val response = app.post(
                CF_WORKER_URL,
                data = payload,
                headers = headers,
                timeout = 15_000
            )
            
            if (response.code == 200) {
                val translated = response.parsedSafe<TranslationResponse>()?.translatedText
                println("✅ [TRANSLATE] Traduzido: ${translated?.take(50)}...")
                translated
            } else {
                println("❌ [TRANSLATE] Erro HTTP: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("❌ [TRANSLATE] Exception: ${e.message}")
            null
        }
    }
    
    // Tradução inteligente: detecta idioma e traduz se não for português
    private suspend fun smartTranslate(text: String): String {
        if (text.isBlank()) return text
        
        // Se o texto já parece estar em português, não traduz
        if (isProbablyPortuguese(text)) {
            return text
        }
        
        // Tenta traduzir
        return translateText(text) ?: text
    }
    
    private fun isProbablyPortuguese(text: String): Boolean {
        val portugueseWords = listOf(
            "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas",
            "é", "são", "está", "estão", "para", "por", "com", "sem", "que",
            "como", "mas", "e", "ou", "se", "não", "sim", "o", "a", "os", "as",
            "um", "uma", "uns", "umas", "meu", "minha", "teu", "tua", "seu", "sua",
            "nosso", "nossa", "você", "vocês", "ele", "ela", "eles", "elas"
        )
        
        val words = text.lowercase().split("\\s+".toRegex())
        val portugueseCount = words.count { it in portugueseWords }
        
        // Se mais de 30% das palavras são português, assume que já está em PT
        return (portugueseCount.toFloat() / words.size) > 0.3
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
                println("✅ [MAL] ID encontrado: $malId")
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
        println("🔍 [SITE] Extraindo metadados do site...")
        
        // 1. POSTER
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> {
                val src = posterImg.attr("src")
                println("✅ [SITE] Poster src: $src")
                fixUrl(src)
            }
            posterImg?.hasAttr("data-src") == true -> {
                val dataSrc = posterImg.attr("data-src")
                println("✅ [SITE] Poster data-src: $dataSrc")
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
        println("✅ [SITE] Sinopse: ${plot?.take(50)}...")

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("✅ [SITE] Tags: $tags")

        // 4. ANO
        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        println("✅ [SITE] Ano: $year")

        return SiteMetadata(poster, plot, tags, year)
    }

    // ============ EPISÓDIOS COM TRADUÇÃO ============
    private suspend fun extractEpisodesWithTranslation(
        document: org.jsoup.nodes.Document,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 [EPISODES] Buscando episódios...")
        val episodeElements = document.select("a.lEp.epT, a.lEp")
        println("✅ [EPISODES] Encontrados ${episodeElements.size} elementos")
        
        // Limitar tradução para os primeiros episódios (para não sobrecarregar)
        val maxEpisodesToTranslate = 10
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(text)
                
                // Buscar dados da ani.zip para este episódio
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                // Determinar nome do episódio
                var episodeName = if (aniZipEpisode?.title?.isNotEmpty() == true) {
                    // Prioridade: título da ani.zip
                    aniZipEpisode.title.values.firstOrNull()
                } else {
                    // Fallback: do site
                    text.substringAfterLast("-").trim()
                }
                
                // Traduzir nome do episódio se não for português (apenas para primeiros episódios)
                if (index < maxEpisodesToTranslate && episodeName != null && !isProbablyPortuguese(episodeName)) {
                    val translatedName = translateText(episodeName, targetLang = "pt")
                    episodeName = translatedName ?: episodeName
                }
                
                // Determinar descrição
                var description = aniZipEpisode?.overview
                
                // Traduzir descrição se não for português (apenas para primeiros episódios)
                if (index < maxEpisodesToTranslate && description != null && !isProbablyPortuguese(description)) {
                    val translatedDesc = translateText(description, targetLang = "pt")
                    description = translatedDesc ?: description
                }
                
                episodes.add(newEpisode(fixUrl(href)) {
                    this.episode = episodeNumber
                    this.season = 1
                    this.name = episodeName ?: "Episódio $episodeNumber"
                    this.posterUrl = aniZipEpisode?.image
                    this.description = description
                    
                    println("✅ [EPISODE] Adicionado ep $episodeNumber: ${this.name}")
                })
            } catch (e: Exception) {
                println("❌ Erro ao extrair episódio: ${e.message}")
            }
        }
        
        println("✅ [EPISODES] Total processados: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        return 1
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
        
        println("🏗️ [RESPONSE] Criando resposta com tradução...")
        
        // DECISÕES FINAIS (prioridade: site > ani.zip)
        val finalPoster = siteMetadata.poster ?: 
            aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) }
        
        val finalBackdrop = aniZipData?.images?.find { 
            it.coverType.equals("Fanart", ignoreCase = true) 
        }?.url?.let { fixUrl(it) }
        
        // Sinopse: traduzir se não for português
        var finalPlot = siteMetadata.plot ?: 
            aniZipData?.episodes?.values?.firstOrNull()?.overview
        
        if (finalPlot != null && !isProbablyPortuguese(finalPlot)) {
            finalPlot = smartTranslate(finalPlot)
        }
        
        val finalYear = year ?: siteMetadata.year
        
        val finalTags = siteMetadata.tags ?: emptyList()
        
        println("✅ [RESPONSE] Poster: $finalPoster")
        println("✅ [RESPONSE] Backdrop: $finalBackdrop")
        println("✅ [RESPONSE] Plot: ${finalPlot?.take(50)}...")
        println("✅ [RESPONSE] Ano: $finalYear")
        println("✅ [RESPONSE] Tags: $finalTags")
        
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
        @JsonProperty("translatedText") val translatedText: String?
    )
}
