package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
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

        // Lista de palavras para remover durante a busca
        private val REMOVE_WORDS = listOf(
            "dublado", "legendado", "episodio", "ep", "ep.", "temporada",
            "season", "part", "parte", "filme", "movie", "ova", "special",
            "especial", "complete", "completo"
        )
    }

    // 📊 Mapa de gêneros para a página principal
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

    // 🔧 FUNÇÃO: Debug log
    private fun debugLog(message: String) {
        println("🔍 [AniTube DEBUG] $message")
    }

    // 🔧 FUNÇÃO: Limpar título para busca
    private fun cleanTitleForSearch(dirtyTitle: String): String {
        debugLog("Limpando título: '$dirtyTitle'")
        
        var clean = dirtyTitle
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

        debugLog("Após remoção básica: '$clean'")

        // Remove números no final
        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()
        debugLog("Após remover números no final: '$clean'")

        // Remove palavras comuns que atrapalham a busca
        REMOVE_WORDS.forEach { word ->
            val regex = "(?i)\\b$word\\b".toRegex()
            val before = clean
            clean = clean.replace(regex, "").trim()
            if (before != clean) {
                debugLog("Removida palavra '$word': '$before' -> '$clean'")
            }
        }

        debugLog("Título final limpo: '$clean'")
        return clean.ifBlank { 
            debugLog("Título ficou vazio, usando original")
            dirtyTitle 
        }
    }

    // 🔧 FUNÇÃO: Buscar imagens no AniList - VERSÃO CORRIGIDA
    private suspend fun searchAniListSimple(animeTitle: String): AniListResult? {
        debugLog("=== BUSCANDO NO ANILIST ===")
        debugLog("Título para busca: '$animeTitle'")
        
        val searchTitle = cleanTitleForSearch(animeTitle)
        if (searchTitle.length < 2) {
            debugLog("Título muito curto para busca")
            return null
        }

        try {
            // Query GraphQL para AniList
            val query = """
                {
                    Page(page: 1, perPage: 3) {
                        media(search: "$searchTitle", type: ANIME) {
                            id
                            title {
                                romaji
                                english
                                native
                            }
                            coverImage {
                                extraLarge
                                large
                            }
                            bannerImage
                            description
                            seasonYear
                            genres
                            averageScore
                        }
                    }
                }
            """.trimIndent()

            debugLog("Enviando requisição para AniList...")
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 30
            )

            debugLog("Resposta recebida do AniList")
            
            val responseText = response.text
            debugLog("Resposta completa (1000 chars): ${responseText.take(1000)}...")

            // 🔧 CORREÇÃO: Parse MANUAL MELHORADO
            if (!responseText.contains("\"media\"")) {
                debugLog("Resposta não contém 'media'")
                return null
            }

            // Encontra o array de media
            val mediaStart = responseText.indexOf("\"media\":[")
            if (mediaStart == -1) {
                debugLog("Não encontrou 'media':[")
                return null
            }

            // Pega todo o conteúdo do array de media
            var bracketCount = 0
            var i = mediaStart + 8 // após "["
            var mediaContent = ""
            
            while (i < responseText.length) {
                val char = responseText[i]
                mediaContent += char
                
                when (char) {
                    '[' -> bracketCount++
                    ']' -> {
                        bracketCount--
                        if (bracketCount == 0) {
                            break
                        }
                    }
                }
                i++
            }

            debugLog("Media content (simplificado): ${mediaContent.take(500)}...")

            // Remove os colchetes externos
            val mediaItems = mediaContent.substring(1, mediaContent.length - 1)
            
            // Pega o primeiro item (remove {} externos)
            val firstItemStart = mediaItems.indexOf('{')
            val firstItemEnd = findMatchingBrace(mediaItems, firstItemStart)
            
            if (firstItemStart == -1 || firstItemEnd == -1) {
                debugLog("Não conseguiu extrair primeiro item")
                return null
            }

            val firstItem = mediaItems.substring(firstItemStart, firstItemEnd + 1)
            debugLog("Primeiro item completo: $firstItem")

            // 🔧 EXTRAÇÃO CORRETA DOS DADOS
            val title = extractJsonValue(firstItem, "romaji", "title") ?: 
                       extractJsonValue(firstItem, "english", "title") ?: 
                       extractJsonValue(firstItem, "native", "title") ?: searchTitle
            
            // CORREÇÃO: extraLarge está dentro de coverImage
            val coverImageContent = extractJsonObject(firstItem, "coverImage")
            val posterUrl = if (coverImageContent != null) {
                extractJsonValue(coverImageContent, "extraLarge") ?: 
                extractJsonValue(coverImageContent, "large")
            } else {
                null
            }
            
            // Remove barras invertidas da URL
            val cleanPosterUrl = posterUrl?.replace("\\/", "/")
            
            val bannerUrl = extractJsonValue(firstItem, "bannerImage")?.replace("\\/", "/")
            val description = extractJsonValue(firstItem, "description")?.replace("\\/", "/")?.replace("<br>", "\n")?.replace(Regex("<.*?>"), "")
            val yearStr = extractJsonValue(firstItem, "seasonYear")
            val year = yearStr?.toIntOrNull()
            
            // Extrai gêneros
            val genresMatch = Regex("\"genres\"\\s*:\\s*\\[([^\\]]*?)\\]").find(firstItem)
            val genres = genresMatch?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().replace("\"", "").replace("\\/", "/") }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            
            // Extrai rating
            val ratingStr = extractJsonValue(firstItem, "averageScore")
            val rating = ratingStr?.toFloatOrNull()?.div(10f)

            debugLog("✅✅✅ DADOS EXTRAÍDOS COM SUCESSO:")
            debugLog("  Título: $title")
            debugLog("  Poster URL: ${cleanPosterUrl?.take(50)}...")
            debugLog("  Banner URL: ${bannerUrl?.take(50)}...")
            debugLog("  Ano: $year")
            debugLog("  Gêneros: $genres")
            debugLog("  Rating: $rating")
            debugLog("  Descrição: ${description?.take(50)}...")

            return AniListResult(
                title = title,
                posterUrl = cleanPosterUrl,
                bannerUrl = bannerUrl,
                description = description,
                year = year,
                genres = genres,
                rating = rating
            )
            
        } catch (e: Exception) {
            debugLog("❌ ERRO na busca AniList: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }

    // 🔧 FUNÇÃO AUXILIAR: Encontrar chave correspondente
    private fun findMatchingBrace(text: String, startIndex: Int): Int {
        var count = 0
        for (i in startIndex until text.length) {
            when (text[i]) {
                '{' -> count++
                '}' -> {
                    count--
                    if (count == 0) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    // 🔧 FUNÇÃO AUXILIAR: Extrair valor JSON
    private fun extractJsonValue(json: String, fieldName: String, parentField: String? = null): String? {
        val pattern = if (parentField != null) {
            "\"$parentField\"\\s*:\\s*\\{[^}]*?\"$fieldName\"\\s*:\\s*\"([^\"]*)\""
        } else {
            "\"$fieldName\"\\s*:\\s*\"([^\"]*)\""
        }
        
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        return regex.find(json)?.groupValues?.get(1)
    }

    // 🔧 FUNÇÃO AUXILIAR: Extrair objeto JSON
    private fun extractJsonObject(json: String, fieldName: String): String? {
        val pattern = "\"$fieldName\"\\s*:\\s*(\\{[^}]*\\})"
        val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(json)
        
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Tenta padrão mais flexível
        val flexPattern = "\"$fieldName\"\\s*:\\s*\\{"
        val flexRegex = Regex(flexPattern)
        val flexMatch = flexRegex.find(json)
        
        if (flexMatch != null) {
            val startIndex = flexMatch.range.first + flexMatch.value.length - 1
            val endIndex = findMatchingBrace(json, startIndex)
            if (endIndex != -1) {
                return json.substring(startIndex, endIndex + 1)
            }
        }
        
        return null
    }

    // 🔧 FUNÇÃO: Buscar metadados de alta qualidade (APENAS ANILIST)
    private suspend fun getEnhancedMetadata(animeTitle: String): EnhancedMetadata? {
        debugLog("\n🔍 === BUSCA DE METADADOS PARA: '$animeTitle' ===")
        
        if (animeTitle.length < 3) {
            debugLog("Título muito curto, pulando busca")
            return null
        }

        // Apenas AniList
        debugLog("\n1. Tentando AniList...")
        val anilistResult = searchAniListSimple(animeTitle)
        
        if (anilistResult != null) {
            if (anilistResult.posterUrl != null) {
                debugLog("🎉🎉🎉 ANILIST ENCONTROU IMAGENS HD! 🎉🎉🎉")
                debugLog("📸 Poster HD: ${anilistResult.posterUrl.take(50)}...")
                debugLog("🎬 Banner HD: ${anilistResult.bannerUrl?.take(50) ?: "null"}...")
            } else {
                debugLog("⚠️ AniList encontrou anime mas sem imagens")
            }
            
            return EnhancedMetadata(
                title = anilistResult.title,
                posterUrl = anilistResult.posterUrl,
                bannerUrl = anilistResult.bannerUrl,
                description = anilistResult.description,
                year = anilistResult.year,
                genres = anilistResult.genres,
                rating = anilistResult.rating
            )
        } else {
            debugLog("❌ AniList não encontrou nada")
        }

        debugLog("❌ Nenhuma fonte encontrou metadados")
        return null
    }

    // 📦 Classes para armazenar resultados
    data class AniListResult(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Float?
    )

    data class EnhancedMetadata(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Float?
    )

    // 🔧 FUNÇÕES ORIGINAIS (mantidas)
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
        
        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()
        
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
            } catch (e: Exception) { null }
        } else { url }
    }
    
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

    // 🏠 FUNÇÃO: Página principal
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        debugLog("\n📱=== GET MAIN PAGE ===")
        debugLog("Página: $page | Seção: ${request.name}")
        
        val baseUrl = request.data
        
        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) baseUrl.replace("/?s=", "/page/$page/?s=") else baseUrl
            debugLog("URL de busca: $url")
            
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
            
            debugLog("✅ Encontrados ${allItems.size} itens")
            return newHomePageResponse(request.name, allItems, hasNext = true)
        }
        
        debugLog("URL principal: $baseUrl")
        val document = app.get(baseUrl).document
        
        return when (request.name) {
            "Últimos Episódios" -> {
                debugLog("Carregando últimos episódios...")
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }
                
                debugLog("✅ ${items.size} episódios encontrados")
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                debugLog("Carregando animes mais vistos...")
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
                    debugLog("Não encontrou container, tentando slides...")
                    val slides = document.select("#splide01 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    popularItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }
                
                debugLog("✅ ${popularItems.size} animes populares encontrados")
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                debugLog("Carregando animes recentes...")
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
                    debugLog("Não encontrou container, tentando slides...")
                    val slides = document.select("#splide02 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    recentItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }
                
                debugLog("✅ ${recentItems.size} animes recentes encontrados")
                newHomePageResponse(
                    list = HomePageList(request.name, recentItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> {
                debugLog("❌ Seção não reconhecida: ${request.name}")
                newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        }
    }

    // 🔍 FUNÇÃO: Busca
    override suspend fun search(query: String): List<SearchResponse> {
        debugLog("\n🔍=== SEARCH ===")
        debugLog("Query: '$query'")
        
        if (query.length < 2) {
            debugLog("Query muito curta")
            return emptyList()
        }
        
        val searchUrl = "$mainUrl$SEARCH_PATH${query.replace(" ", "+")}"
        debugLog("Search URL: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        val results = document.select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { 
                val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                if (isEpisode) {
                    it.toEpisodeSearchResponse()
                } else {
                    it.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
        
        debugLog("✅ Encontrados ${results.size} resultados para '$query'")
        return results
    }

    // 📥 FUNÇÃO: Carregar anime
    override suspend fun load(url: String): LoadResponse {
        debugLog("\n🎬=== LOAD ANIME ===")
        debugLog("URL recebida: $url")
        
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }
        
        debugLog("URL real: $actualUrl")
        
        val document = app.get(actualUrl).document
        
        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
        val title = cleanTitle(rawTitle)
        
        debugLog("📝 Título bruto: '$rawTitle'")
        debugLog("📝 Título limpo: '$title'")
        
        // Primeiro, pega a imagem do site
        val sitePoster = document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
        var poster = thumbPoster ?: sitePoster
        debugLog("🖼️ Poster do site: ${sitePoster?.take(30)}...")
        
        var banner: String? = null
        var enhancedSynopsis: String? = null
        var enhancedYear: Int? = null
        var enhancedGenres = emptyList<String>()
        var enhancedRating: Float? = null
        
        // 🔧 TENTA BUSCAR IMAGENS DE ALTA QUALIDADE
        debugLog("\n🚀 INICIANDO BUSCA DE IMAGENS HD")
        
        if (title.length >= 3 && !title.matches(Regex(".*\\d{3,}.*"))) {
            debugLog("✅ Título válido para busca HD: '$title'")
            
            val enhancedMetadata = getEnhancedMetadata(title)
            
            if (enhancedMetadata != null) {
                debugLog("📊 RESULTADO DA BUSCA HD:")
                debugLog("  - Poster encontrado: ${enhancedMetadata.posterUrl != null}")
                debugLog("  - Banner encontrado: ${enhancedMetadata.bannerUrl != null}")
                debugLog("  - Poster URL: ${enhancedMetadata.posterUrl?.take(50)}...")
                debugLog("  - Banner URL: ${enhancedMetadata.bannerUrl?.take(50)}...")
                
                // Usa imagens de alta qualidade se disponíveis
                if (enhancedMetadata.posterUrl != null) {
                    poster = enhancedMetadata.posterUrl
                    debugLog("✅✅✅ POSTER HD DEFINIDO!")
                } else {
                    debugLog("⚠️ Poster HD é null, mantendo original")
                }
                
                if (enhancedMetadata.bannerUrl != null) {
                    banner = enhancedMetadata.bannerUrl
                    debugLog("✅✅✅ BANNER HD DEFINIDO!")
                }
                
                enhancedSynopsis = enhancedMetadata.description
                enhancedYear = enhancedMetadata.year
                enhancedGenres = enhancedMetadata.genres
                enhancedRating = enhancedMetadata.rating
                
                // Adiciona rating à sinopse se disponível
                if (enhancedRating != null) {
                    val ratingText = "⭐ **Avaliação:** ${String.format("%.1f", enhancedRating)}/10\n\n"
                    enhancedSynopsis = if (enhancedSynopsis != null) {
                        ratingText + enhancedSynopsis
                    } else {
                        ratingText
                    }
                    debugLog("⭐ Rating adicionado: $enhancedRating")
                }
            } else {
                debugLog("😞 Nenhum metadado HD encontrado")
            }
        } else {
            debugLog("⏭️ Pulando busca HD - título inválido")
        }
    
        val siteSynopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()
        
        // Usa sinopse melhorada se disponível, senão usa a do site
        val synopsis = when {
            actualUrl.contains("/video/") -> {
                siteSynopsis ?: "Episódio $episodeNumber de $title"
            }
            enhancedSynopsis != null -> {
                enhancedSynopsis
            }
            else -> {
                siteSynopsis ?: "Sinopse não disponível."
            }
        }
        
        var year: Int? = enhancedYear
        var episodes: Int? = null
        var genres = enhancedGenres
        var audioType = ""
        
        // Complementa com dados do site se necessário
        if (genres.isEmpty() || year == null) {
            document.select(ANIME_METADATA).forEach { element ->
                val text = element.text()
                
                when {
                    text.contains("Gênero:", true) && genres.isEmpty() -> {
                        genres = text.substringAfter("Gênero:").split(",").map { it.trim() }
                    }
                    text.contains("Ano:", true) && year == null -> {
                        year = text.substringAfter("Ano:").trim().toIntOrNull()
                    }
                    text.contains("Episódios:", true) -> {
                        episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
                    }
                    text.contains("Tipo de Episódio:", true) -> {
                        audioType = text.substringAfter("Tipo de Episódio:").trim()
                    }
                }
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
        
        debugLog("\n📋 RESUMO FINAL:")
        debugLog("  - Poster: ${poster?.take(30)}...")
        debugLog("  - Banner: ${banner?.take(30)}...")
        debugLog("  - HD encontrado? ${banner != null || poster != sitePoster}")
        
        // 🔧 RETORNA COM IMAGENS DE ALTA QUALIDADE
        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            
            if (sortedEpisodes.isNotEmpty()) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
            }
        }
    }

    // ▶️ FUNÇÃO: Carregar links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("\n▶️=== LOAD LINKS ===")
        
        val actualUrl = data.split("|poster=")[0]
        
        val document = app.get(actualUrl).document
        
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            
            val m3u8Url = extractM3u8FromUrl(src) ?: src
            
            callback(newExtractorLink(name, "1080p", m3u8Url, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                quality = 1080
            })
            return true
        }
        
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            
            val isM3u8 = src.contains("m3u8", true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            
            callback(newExtractorLink(name, "Backup", src, linkType) {
                referer = "$mainUrl/"
                quality = 720
            })
            return true
        }
        
        document.select("iframe").forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true)) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                
                callback(newExtractorLink(name, "Auto", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                return true
            }
        }
        
        return false
    }
}
