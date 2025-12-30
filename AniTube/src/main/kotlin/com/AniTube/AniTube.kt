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
            "especial", "complete", "completo", "todos", "os", "online"
        )
        
        // Cache para evitar múltiplas requisições
        private val metadataCache = mutableMapOf<String, EnhancedMetadata?>()
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

    // 🔧 FUNÇÃO: Limpar título para busca (AGORA MAIS AGRESSIVA)
    private fun cleanTitleForSearch(dirtyTitle: String): String {
        debugLog("Limpando título: '$dirtyTitle'")
        
        var clean = dirtyTitle
            .replace("(?i)\\s*[–\\-]\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*\\[dublado\\]".toRegex(), "")
            .replace("(?i)\\s*\\[legendado\\]".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*[–\\-]\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*E\\d+".toRegex(), "")
            .replace("(?i)\\s*\\(\\d{4}\\)".toRegex(), "") // Remove anos
            .replace("(?i)\\s*assistir\\s*online".toRegex(), "")
            .replace("(?i)\\s*baixar".toRegex(), "")
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

        // Remove caracteres especiais e pontuação no final
        clean = clean.replace("[\\-\\.\\,;:]\\s*$".toRegex(), "").trim()
        
        debugLog("Título final limpo: '$clean'")
        return clean.ifBlank { 
            debugLog("Título ficou vazio, usando original")
            dirtyTitle 
        }
    }

    // 🔧 FUNÇÃO: Buscar no AniList com múltiplas tentativas
    private suspend fun searchAniListWithFallbacks(animeTitle: String): AniListResult? {
        debugLog("=== BUSCANDO NO ANILIST COM FALLBACKS ===")
        
        // Lista de variações do título para tentar
        val searchVariations = mutableListOf<String>()
        
        // 1. Título original limpo
        val cleanTitle = cleanTitleForSearch(animeTitle)
        searchVariations.add(cleanTitle)
        
        // 2. Remove ":" e outros caracteres
        val withoutColon = cleanTitle.replace(":", "").trim()
        if (withoutColon != cleanTitle) {
            searchVariations.add(withoutColon)
        }
        
        // 3. Remove "!" e "?"
        val withoutPunctuation = cleanTitle.replace("[!?]".toRegex(), "").trim()
        if (withoutPunctuation != cleanTitle) {
            searchVariations.add(withoutPunctuation)
        }
        
        // 4. Tenta dividir por " - " ou " – "
        val parts = cleanTitle.split(Regex("[–\\-]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            searchVariations.addAll(parts.take(2))
        }
        
        debugLog("Variações de busca: $searchVariations")
        
        // Tenta cada variação
        for (variation in searchVariations.distinct()) {
            if (variation.length < 2) continue
            
            debugLog("Tentando variação: '$variation'")
            val result = trySearchAniList(variation)
            if (result != null) {
                debugLog("✅ Encontrado com variação: '$variation'")
                return result
            }
        }
        
        debugLog("❌ Nenhuma variação funcionou")
        return null
    }

    // 🔧 FUNÇÃO: Tentar buscar no AniList
    private suspend fun trySearchAniList(searchTitle: String): AniListResult? {
        try {
            // Query GraphQL para AniList
            val query = """
                {
                    Page(page: 1, perPage: 5) {
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
                                medium
                            }
                            bannerImage
                            description
                            seasonYear
                            genres
                            averageScore
                            status
                        }
                    }
                }
            """.trimIndent()

            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 30
            )

            val responseText = response.text
            
            if (!responseText.contains("\"media\"")) {
                return null
            }

            // Parse da resposta
            return parseAniListResponse(responseText)
            
        } catch (e: Exception) {
            debugLog("Erro na busca AniList: ${e.message}")
            return null
        }
    }

    // 🔧 FUNÇÃO: Parse da resposta do AniList
    private fun parseAniListResponse(responseText: String): AniListResult? {
        try {
            // Extrai o array de media
            val mediaStart = responseText.indexOf("\"media\":[")
            if (mediaStart == -1) return null
            
            // Encontra o final do array
            var bracketCount = 0
            var i = mediaStart + 8
            var mediaContent = ""
            
            while (i < responseText.length) {
                val char = responseText[i]
                mediaContent += char
                
                when (char) {
                    '[' -> bracketCount++
                    ']' -> {
                        bracketCount--
                        if (bracketCount == 0) break
                    }
                }
                i++
            }

            if (mediaContent.isEmpty()) return null
            
            // Remove colchetes externos
            val mediaArray = mediaContent.substring(1, mediaContent.length - 1)
            
            // Pega o primeiro item
            val firstItem = extractFirstJsonObject(mediaArray) ?: return null
            
            // Extrai dados
            val title = extractJsonValue(firstItem, "romaji", "title") ?: 
                       extractJsonValue(firstItem, "english", "title") ?: 
                       extractJsonValue(firstItem, "native", "title") ?: ""
            
            if (title.isEmpty()) return null
            
            // Extrai coverImage
            val coverImageObj = extractJsonObject(firstItem, "coverImage")
            val posterUrl = if (coverImageObj != null) {
                extractJsonValue(coverImageObj, "extraLarge") ?: 
                extractJsonValue(coverImageObj, "large") ?:
                extractJsonValue(coverImageObj, "medium")
            } else {
                null
            }
            
            // Remove barras invertidas
            val cleanPosterUrl = posterUrl?.replace("\\/", "/")
            val bannerUrl = extractJsonValue(firstItem, "bannerImage")?.replace("\\/", "/")
            val description = extractJsonValue(firstItem, "description")
                ?.replace("\\/", "/")
                ?.replace("<br>", "\n")
                ?.replace(Regex("<.*?>"), "")
            
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
            
            // Extrai status
            val status = extractJsonValue(firstItem, "status")
            
            debugLog("✅ AniList parseado com sucesso: $title")
            
            return AniListResult(
                title = title,
                posterUrl = cleanPosterUrl,
                bannerUrl = bannerUrl,
                description = description,
                year = year,
                genres = genres,
                rating = rating,
                status = status
            )
            
        } catch (e: Exception) {
            debugLog("Erro parsing AniList: ${e.message}")
            return null
        }
    }

    // 🔧 FUNÇÕES AUXILIARES PARA MANIPULAÇÃO DE JSON
    private fun extractFirstJsonObject(jsonArray: String): String? {
        var braceCount = 0
        var startIndex = -1
        
        for (i in jsonArray.indices) {
            when (jsonArray[i]) {
                '{' -> {
                    if (braceCount == 0) startIndex = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        return jsonArray.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }
    
    private fun extractJsonObject(json: String, fieldName: String): String? {
        val pattern = "\"$fieldName\"\\s*:\\s*\\{"
        val regex = Regex(pattern)
        val match = regex.find(json) ?: return null
        
        var braceCount = 0
        val startIndex = match.range.first + match.value.length - 1
        
        for (i in startIndex until json.length) {
            when (json[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        return json.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }
    
    private fun extractJsonValue(json: String, fieldName: String, parentField: String? = null): String? {
        val pattern = if (parentField != null) {
            "\"$parentField\"\\s*:[^{]*?\"$fieldName\"\\s*:\\s*\"([^\"]*)\""
        } else {
            "\"$fieldName\"\\s*:\\s*\"([^\"]*)\""
        }
        
        return Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            .find(json)
            ?.groupValues
            ?.get(1)
    }

    // 🔧 FUNÇÃO: Buscar metadados com CACHE
    private suspend fun getEnhancedMetadata(animeTitle: String): EnhancedMetadata? {
        debugLog("\n🔍 === BUSCA DE METADADOS PARA: '$animeTitle' ===")
        
        if (animeTitle.length < 2) {
            debugLog("Título muito curto")
            return null
        }
        
        // Verifica cache primeiro
        val cacheKey = animeTitle.lowercase().trim()
        if (metadataCache.containsKey(cacheKey)) {
            debugLog("Usando resultado do cache")
            return metadataCache[cacheKey]
        }
        
        debugLog("Buscando no AniList...")
        val anilistResult = searchAniListWithFallbacks(animeTitle)
        
        val result = if (anilistResult != null) {
            debugLog("✅✅✅ METADADOS ENCONTRADOS NO ANILIST! ✅✅✅")
            
            EnhancedMetadata(
                title = anilistResult.title,
                posterUrl = anilistResult.posterUrl,
                bannerUrl = anilistResult.bannerUrl,
                description = anilistResult.description,
                year = anilistResult.year,
                genres = anilistResult.genres,
                rating = anilistResult.rating,
                status = anilistResult.status
            )
        } else {
            debugLog("❌ Nenhum metadado encontrado")
            null
        }
        
        // Armazena no cache
        metadataCache[cacheKey] = result
        return result
    }

    // 📦 Classes para armazenar resultados
    data class AniListResult(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Float?,
        val status: String?
    )

    data class EnhancedMetadata(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Float?,
        val status: String?
    )

    // 🔧 FUNÇÃO: Obter poster para item da lista (PRIORIDADE ANILIST)
    private suspend fun getPosterForListItem(animeTitle: String): String? {
        debugLog("Buscando poster para lista: '$animeTitle'")
        
        val metadata = getEnhancedMetadata(animeTitle)
        return metadata?.posterUrl
    }

    // 🔧 FUNÇÃO: Criar SearchResponse com metadados
    private suspend fun Element.toAnimeSearchResponseWithMetadata(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val rawTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitleForSearch(rawTitle).ifBlank { return null }
        
        val isDubbed = isDubbed(this)
        
        debugLog("Criando SearchResponse para: '$cleanedTitle'")
        
        // 🔥 PRIORIDADE ABSOLUTA: Busca poster no AniList primeiro
        var posterUrl: String? = null
        try {
            posterUrl = getPosterForListItem(cleanedTitle)
            debugLog("Poster do AniList: ${posterUrl?.take(30)}...")
        } catch (e: Exception) {
            debugLog("Erro buscando poster do AniList: ${e.message}")
        }
        
        // Se não encontrou no AniList, usa do site
        if (posterUrl == null) {
            posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
            debugLog("Usando poster do site: ${posterUrl?.take(30)}...")
        }
        
        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            addDubStatus(isDubbed, null)
        }
    }
    
    private suspend fun Element.toEpisodeSearchResponseWithMetadata(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/video/")) return null
        
        val episodeTitle = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val displayName = cleanTitleForSearch(animeTitle)
        val isDubbed = isDubbed(this)
        
        debugLog("Criando Episode SearchResponse para: '$displayName' (Ep $episodeNumber)")
        
        // 🔥 PRIORIDADE ABSOLUTA: Busca poster no AniList primeiro
        var posterUrl: String? = null
        try {
            posterUrl = getPosterForListItem(displayName)
            debugLog("Poster do AniList para episódio: ${posterUrl?.take(30)}...")
        } catch (e: Exception) {
            debugLog("Erro buscando poster: ${e.message}")
        }
        
        // Se não encontrou no AniList, usa do site
        if (posterUrl == null) {
            posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        }
        
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

    // 🏠 FUNÇÃO: Página principal (COM METADADOS)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        debugLog("\n📱=== GET MAIN PAGE COM METADADOS ===")
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
                        it.toEpisodeSearchResponseWithMetadata()
                    } else {
                        it.toAnimeSearchResponseWithMetadata()
                    }
                }
                .distinctBy { it.url }
            
            debugLog("✅ Encontrados ${allItems.size} itens COM METADADOS")
            return newHomePageResponse(request.name, allItems, hasNext = true)
        }
        
        debugLog("URL principal: $baseUrl")
        val document = app.get(baseUrl).document
        
        return when (request.name) {
            "Últimos Episódios" -> {
                debugLog("Carregando últimos episódios COM METADADOS...")
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponseWithMetadata() }
                    .distinctBy { it.url }
                
                debugLog("✅ ${items.size} episódios com metadados encontrados")
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                debugLog("Carregando animes mais vistos COM METADADOS...")
                var popularItems = listOf<AnimeSearchResponse>()
                
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("Animes Mais Vistos", true)) {
                        popularItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponseWithMetadata() }
                            .distinctBy { it.url }
                            .take(15)
                        break
                    }
                }
                
                if (popularItems.isEmpty()) {
                    debugLog("Não encontrou container, tentando slides...")
                    val slides = document.select("#splide01 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    popularItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponseWithMetadata()
                        }
                        .distinctBy { it.url }
                        .take(15)
                }
                
                debugLog("✅ ${popularItems.size} animes populares com metadados")
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                debugLog("Carregando animes recentes COM METADADOS...")
                var recentItems = listOf<AnimeSearchResponse>()
                
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("ANIMES RECENTES", true)) {
                        recentItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponseWithMetadata() }
                            .distinctBy { it.url }
                            .take(15)
                        break
                    }
                }
                
                if (recentItems.isEmpty()) {
                    debugLog("Não encontrou container, tentando slides...")
                    val slides = document.select("#splide02 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    recentItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponseWithMetadata()
                        }
                        .distinctBy { it.url }
                        .take(15)
                }
                
                debugLog("✅ ${recentItems.size} animes recentes com metadados")
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

    // 🔍 FUNÇÃO: Busca (COM METADADOS)
    override suspend fun search(query: String): List<SearchResponse> {
        debugLog("\n🔍=== SEARCH COM METADADOS ===")
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
                    it.toEpisodeSearchResponseWithMetadata()
                } else {
                    it.toAnimeSearchResponseWithMetadata()
                }
            }
            .distinctBy { it.url }
        
        debugLog("✅ Encontrados ${results.size} resultados COM METADADOS para '$query'")
        return results
    }

    // 📥 FUNÇÃO: Carregar anime (PRIORIDADE ABSOLUTA PARA METADADOS)
    override suspend fun load(url: String): LoadResponse {
        debugLog("\n🎬=== LOAD ANIME COM PRIORIDADE METADADOS ===")
        debugLog("URL recebida: $url")
        
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        
        debugLog("URL real: $actualUrl")
        
        val document = app.get(actualUrl).document
        
        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
        val title = cleanTitleForSearch(rawTitle)
        
        debugLog("📝 Título bruto: '$rawTitle'")
        debugLog("📝 Título para busca: '$title'")
        
        // 🔥🔥🔥 PRIORIDADE 1: Busca TODOS os metadados no AniList
        debugLog("\n🚀 BUSCANDO METADADOS COMPLETOS NO ANILIST...")
        val enhancedMetadata = getEnhancedMetadata(title)
        
        var poster: String? = null
        var banner: String? = null
        var synopsis: String? = null
        var year: Int? = null
        var genres = emptyList<String>()
        var rating: Float? = null
        var status: String? = null
        
        if (enhancedMetadata != null) {
            debugLog("🎉🎉🎉 METADADOS COMPLETOS ENCONTRADOS! 🎉🎉🎉")
            
            poster = enhancedMetadata.posterUrl
            banner = enhancedMetadata.bannerUrl
            synopsis = enhancedMetadata.description
            year = enhancedMetadata.year
            genres = enhancedMetadata.genres
            rating = enhancedMetadata.rating
            status = enhancedMetadata.status
            
            debugLog("📊 DADOS DO ANILIST:")
            debugLog("  - Poster: ${poster?.take(50)}...")
            debugLog("  - Banner: ${banner?.take(50)}...")
            debugLog("  - Ano: $year")
            debugLog("  - Gêneros: $genres")
            debugLog("  - Rating: $rating")
            debugLog("  - Status: $status")
        } else {
            debugLog("⚠️ Nenhum metadado encontrado no AniList")
        }
        
        // 🔥 PRIORIDADE 2: Se não tem poster do AniList, tenta do site
        if (poster == null) {
            debugLog("Buscando poster do site...")
            poster = document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
            debugLog("Poster do site: ${poster?.take(30)}...")
        }
        
        // Se não tem sinopse do AniList, usa do site
        if (synopsis == null || synopsis.isBlank()) {
            synopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()
        }
        
        // Se não tem sinopse ainda
        synopsis = when {
            synopsis != null && synopsis.isNotBlank() -> synopsis
            actualUrl.contains("/video/") -> "Episódio $episodeNumber de $title"
            else -> "Sinopse não disponível."
        }
        
        // Adiciona rating à sinopse se disponível
        if (rating != null) {
            synopsis = "⭐ **Avaliação:** ${String.format("%.1f", rating)}/10\n\n$synopsis"
        }
        
        // Se não tem dados do AniList, tenta do site
        if (genres.isEmpty() || year == null) {
            var audioType = ""
            var episodes: Int? = null
            
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
            
            debugLog("\n📋 RESUMO FINAL (com fallback do site):")
            debugLog("  - Poster: ${poster?.take(30)}...")
            debugLog("  - Banner: ${banner?.take(30)}...")
            debugLog("  - Episódios: ${sortedEpisodes.size}")
            debugLog("  - Status: $showStatus")
            
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
        
        // Se tem dados do AniList, completa com episódios do site
        val isDubbed = rawTitle.contains("dublado", true)
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
        val showStatus = when {
            status?.contains("FINISHED", true) == true -> ShowStatus.Completed
            status?.contains("RELEASING", true) == true -> ShowStatus.Ongoing
            sortedEpisodes.size >= 24 -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
        
        debugLog("\n🎯 RESUMO FINAL (COM PRIORIDADE ANILIST):")
        debugLog("  - Poster: ${poster?.take(50)}...")
        debugLog("  - Banner: ${banner?.take(50)}...")
        debugLog("  - Ano: $year")
        debugLog("  - Gêneros: $genres")
        debugLog("  - Rating: $rating")
        debugLog("  - Episódios: ${sortedEpisodes.size}")
        debugLog("  - Status: $showStatus (AniList: $status)")
        
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
