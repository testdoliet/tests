package com.Doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DoramogoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Doramogo())
    }
}

class Doramogo : MainAPI() {
    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Em Destaque",
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Doramas Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Doramas Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-aventura" to "Aventura",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-crime" to "Crime",
        "$mainUrl/genero/dorama-drama" to "Drama",
        "$mainUrl/genero/dorama-familia" to "Família",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
        "$mainUrl/genero/dorama-ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/dorama-misterio" to "Mistério",
        "$mainUrl/genero/dorama-reality" to "Reality Shows",
        "$mainUrl/genero/dorama-sci-fi" to "Sci-Fi",
        "$mainUrl/filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            when {
                request.data.contains("/dorama?") || request.data.contains("/filmes") -> 
                    "${request.data}&pagina=$page"
                request.data.contains("/genero/") -> 
                    "${request.data}/pagina/$page"
                request.data.contains("/episodios") -> 
                    "${request.data}?pagina=$page"
                else -> "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        // Verificar se é a aba de episódios recentes
        val isEpisodesPage = request.data.contains("/episodios") || request.name.contains("Episódios")

        if (isEpisodesPage) {
            // Para página de episódios recentes - layout vertical
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3, .episode-title")
                val episodeTitle = titleElement?.text()?.trim() ?: return@forEach
                
                // Extrair nome do dorama
                val doramaName = episodeTitle.replace(Regex("\\s*-\\s*Episódio\\s*\\d+.*$"), "").trim()
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    else -> null
                }
                
                // Extrair número do episódio para título mais descritivo
                val episodeMatch = Regex("Episódio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(episodeTitle)
                val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // CORREÇÃO AQUI: Use uma variável local para o nome final
                val finalTitle = "$doramaName (Episódio $episodeNumber)"
                
                items.add(newTvSeriesSearchResponse(doramaName, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.name = finalTitle // Agora funciona
                })
            }
        } else {
            // Para outras páginas (doramas, filmes, gêneros) - layout horizontal
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3")
                val title = titleElement?.text()?.trim() 
                    ?: aTag.attr("title")?.trim()
                    ?: return@forEach
                
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    else -> null
                }
                
                val isMovie = href.contains("/filmes/") || request.name.contains("Filmes")
                val type = if (isMovie) TvType.Movie else TvType.TvSeries
                
                if (type == TvType.Movie) {
                    items.add(newMovieSearchResponse(title, fixUrl(href), type) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                        this.posterUrl = posterUrl
                    })
                }
            }
            
            // Fallback: tentar pegar itens de outras estruturas se necessário
            if (items.isEmpty()) {
                document.select(".grid .episode-card").forEach { card ->
                    val aTag = card.selectFirst("a") ?: return@forEach
                    val titleElement = card.selectFirst("h3")
                    val title = titleElement?.text()?.trim() 
                        ?: return@forEach
                    
                    val href = aTag.attr("href")
                    
                    val imgElement = card.selectFirst("img")
                    val posterUrl = when {
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        else -> null
                    }
                    
                    val type = if (href.contains("/filmes/")) TvType.Movie else TvType.TvSeries
                    
                    if (type == TvType.Movie) {
                        items.add(newMovieSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                        })
                    } else {
                        items.add(newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }

        val hasNextPage = document.select("""a[href*="pagina/"], a[href*="?page="], 
            .pagination a, .next-btn, a:contains(PRÓXIMA)""").isNotEmpty()
        
        // Criar HomePageList com configuração de layout
        val homePageList = HomePageList(
            request.name,
            items.distinctBy { it.url },
            // Episódios recentes são verticais (false), outros são horizontais (true)
            isHorizontalImages = !isEpisodesPage
        )
        
        return newHomePageResponse(listOf(homePageList), hasNextPage)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".episode-card, .search-result").mapNotNull { card ->
            val aTag = card.selectFirst("a") ?: return@mapNotNull null
            val title = card.selectFirst("h3, h2")?.text()?.trim() 
                ?: aTag.attr("title") 
                ?: return@mapNotNull null
            val href = aTag.attr("href")
            
            val imgElement = card.selectFirst("img")
            val posterUrl = when {
                imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                else -> null
            }
            
            val type = if (href.contains("/filmes/")) TvType.Movie else TvType.TvSeries
            
            if (type == TvType.Movie) {
                newMovieSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            } else {
                newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            }
        }
    }

    // --- Load ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título
        val fullTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null
        
        val title = fullTitle.replace(Regex("\\s*-\\s*(Dublado|Legendado|Online).*"), "").trim()
        
        // Descrição
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: ""
        
        // Poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".episode-image-container img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".thumbnail img")?.attr("src")?.let { fixUrl(it) }
        
        // Ano
        val infoText = document.selectFirst(".detail p.text-white")?.text() ?: ""
        val year = Regex("""/(\d{4})/""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
            ?: title.findYear()
        
        // Gêneros
        val tags = document.select(".gens a").map { it.text().trim() }
        
        // Verificar se é filme ou série
        val isMovie = url.contains("/filmes/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.TvSeries) {
            // Extrair episódios
            val episodes = mutableListOf<Episode>()
            document.select(".dorama-one-episode-item").forEach { episodeItem ->
                val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                
                val episodeNumber = extractEpisodeNumber(episodeTitle)
                val seasonNumber = extractSeasonNumberFromUrl(url) ?: 1
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = seasonNumber
                    this.episode = episodeNumber
                })
            }
            
            // Se não encontrar episódios estruturados, tentar outros métodos
            if (episodes.isEmpty()) {
                document.select("a[href*='/episodio-'], a[href*='/episode-']").forEach { episodeLink ->
                    val episodeUrl = episodeLink.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeLink.text().trim().ifEmpty { "Episódio" }
                    
                    val seasonEpisode = extractSeasonEpisodeFromUrl(episodeUrl)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonEpisode.first
                        this.episode = seasonEpisode.second
                    })
                }
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }
    
    private fun extractEpisodeNumber(text: String): Int {
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)|EP\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: match?.groupValues?.get(3)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumberFromUrl(url: String): Int? {
        val pattern = Regex("""temporada[_-](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractSeasonEpisodeFromUrl(url: String): Pair<Int, Int> {
        val seasonPattern = Regex("""temporada[_-](\d+)|season[_-](\d+)|/T(\d+)/""", RegexOption.IGNORE_CASE)
        val episodePattern = Regex("""episodio[_-](\d+)|episode[_-](\d+)|/E(\d+)/""", RegexOption.IGNORE_CASE)
        
        val seasonMatch = seasonPattern.find(url)
        val episodeMatch = episodePattern.find(url)
        
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.get(3)?.toIntOrNull()
            ?: 1
        
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: episodeMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: episodeMatch?.groupValues?.get(3)?.toIntOrNull()
            ?: 1
        
        return Pair(season, episode)
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- Load Links ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Temporariamente desativado
        return false
    }
}
