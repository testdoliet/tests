package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire (com AniList)"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // URL do AniList
    private val aniListUrl = "https://anilist.co"
    
    // MainPage com abas do AniList APENAS por enquanto
    override val mainPage = mainPageOf(
        "anilist-trending" to "AniList: Em Alta",
        "anilist-season" to "AniList: Esta Temporada", 
        "anilist-popular" to "AniList: Populares",
        "anilist-top" to "AniList: Top 100"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "AniList: Em Alta" -> getAniListPage("search/anime", page, request.name)
            "AniList: Esta Temporada" -> getAniListPage("search/anime/this-season", page, request.name)
            "AniList: Populares" -> getAniListPage("search/anime/popular", page, request.name)
            "AniList: Top 100" -> getAniListPage("search/anime/top-100", page, request.name)
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    private suspend fun getAniListPage(endpoint: String, page: Int, pageName: String): HomePageResponse {
        println("🌐 [ANILIST] Carregando: $pageName")
        
        return try {
            val url = "$aniListUrl/$endpoint"
            val document = app.get(url, referer = aniListUrl).document
            
            // Verificar se estamos recebendo conteúdo HTML válido
            println("📄 [ANILIST] Página carregada: ${document.title()}")
            
            // Método 1: Procurar por media-cards (estrutura comum do AniList)
            val mediaCards = document.select("div.media-card")
            println("📊 [ANILIST] Media-cards encontrados: ${mediaCards.size}")
            
            val items = if (mediaCards.isNotEmpty()) {
                parseMediaCards(mediaCards)
            } else {
                // Método 2: Fallback - procurar por qualquer container de anime
                parseFallbackAnimeElements(document)
            }
            
            println("✅ [ANILIST] ${items.size} itens encontrados para $pageName")
            newHomePageResponse(pageName, items, false)
            
        } catch (e: Exception) {
            println("❌ [ANILIST] Erro ao carregar $pageName: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    private fun parseMediaCards(cards: List<Element>): List<AnimeSearchResponse> {
        return cards.take(20).mapNotNull { card ->
            try {
                // Título - múltiplas tentativas
                val titleElement = card.selectFirst("a.title") ?: 
                                  card.selectFirst("a[href*='/anime/']") ?:
                                  card.selectFirst(".title") ?:
                                  card.selectFirst("h3, h4")
                
                val title = titleElement?.text()?.trim() ?: "Sem Título"
                
                // Link
                val linkElement = card.selectFirst("a[href*='/anime/']") ?: titleElement
                val href = linkElement?.attr("href") ?: ""
                
                if (href.isBlank()) return@mapNotNull null
                
                // Poster
                val poster = card.selectFirst("img")?.attr("src") ?: 
                            card.selectFirst("img[src*='anilist']")?.attr("src") ?: ""
                
                // Criar URL especial para identificar que é do AniList
                val specialUrl = "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                
                newAnimeSearchResponse(title, specialUrl) {
                    this.posterUrl = poster
                    this.type = TvType.Anime
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseFallbackAnimeElements(doc: org.jsoup.nodes.Document): List<AnimeSearchResponse> {
        val items = mutableListOf<AnimeSearchResponse>()
        
        // Tentar encontrar qualquer link de anime
        val animeLinks = doc.select("a[href*='/anime/']")
        println("🔄 [FALLBACK] Links de anime encontrados: ${animeLinks.size}")
        
        animeLinks.take(20).forEach { link ->
            try {
                val href = link.attr("href")
                val title = link.text().trim()
                
                if (title.isNotBlank() && href.contains("/anime/")) {
                    // Procurar imagem próxima
                    val img = link.selectFirst("img") ?:
                             link.parent()?.selectFirst("img") ?:
                             link.closest("div")?.selectFirst("img")
                    
                    val poster = img?.attr("src") ?: ""
                    
                    val specialUrl = "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                    
                    items.add(
                        newAnimeSearchResponse(title, specialUrl) {
                            this.posterUrl = poster
                            this.type = TvType.Anime
                        }
                    )
                }
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        if (url.startsWith("anilist:")) {
            return loadAniListContent(url)
        }
        
        // Retornar um placeholder para conteúdo do AniList
        return newAnimeLoadResponse("Conteúdo do AniList", url, TvType.Anime) {
            this.plot = "Conteúdo carregado do AniList. Para assistir, busque este anime diretamente no AnimeFire."
            
            // Adicionar alguns episódios placeholder
            val placeholderEpisodes = (1..12).map { epNum ->
                newEpisode(url) {
                    this.name = "Episódio $epNum"
                    this.season = 1
                    this.episode = epNum
                    this.description = "Conteúdo do AniList - Episódio $epNum"
                }
            }
            addEpisodes(DubStatus.Subbed, placeholderEpisodes)
        }
    }

    private suspend fun loadAniListContent(url: String): LoadResponse {
        // Extrair a URL original do AniList
        val parts = url.split(":")
        val originalUrl = parts.drop(2).joinToString(":")
        
        println("🌐 Carregando conteúdo do AniList: $originalUrl")
        
        try {
            val document = app.get(originalUrl, referer = aniListUrl).document
            
            // Extrair título
            val title = document.selectFirst("h1")?.text()?.trim() ?: 
                       document.selectFirst(".title")?.text()?.trim() ?: 
                       "Anime do AniList"
            
            // Extrair descrição
            val description = document.selectFirst(".description")?.text()?.trim() ?:
                            document.selectFirst(".synopsis")?.text()?.trim() ?:
                            "Sem descrição disponível"
            
            // Extrair poster
            val poster = document.selectFirst("img.cover")?.attr("src") ?:
                        document.selectFirst("img[src*='anilist']")?.attr("src") ?: ""
            
            // Extrair ano se disponível
            val yearText = document.selectFirst(":contains(Year), :contains(Ano), :contains(Released)")?.text()
            val year = Regex("\\d{4}").find(yearText ?: "")?.value?.toIntOrNull()
            
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.plot = description
                this.posterUrl = poster
                this.year = year
                
                // Adicionar episódios placeholder
                val episodesCount = 12 // Default
                val placeholderEpisodes = (1..episodesCount).map { epNum ->
                    newEpisode(url) {
                        this.name = "Episódio $epNum"
                        this.season = 1
                        this.episode = epNum
                        this.description = "$title - Episódio $epNum"
                    }
                }
                addEpisodes(DubStatus.Subbed, placeholderEpisodes)
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao carregar do AniList: ${e.message}")
            
            // Retornar resposta de erro
            return newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar este conteúdo do AniList. Tente buscar o anime diretamente no AnimeFire."
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Por enquanto, retornar lista vazia para simplificar
        // Podemos implementar busca no AniList depois
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Por enquanto, retornar false para conteúdos do AniList
        // Podemos implementar extração depois
        println("⚠️ Extração de links para AniList não implementada ainda")
        return false
    }
}
