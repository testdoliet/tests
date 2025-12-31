package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Hypeflix : MainAPI() {
    override var mainUrl = "https://hypeflix.info"
    override var name = "HypeFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false // Desativado por enquanto
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    companion object {
        // Categorias baseadas nas seções da página inicial
        private val HOME_SECTIONS = listOf(
            "popular" to "Os mais populares",
            "lancamentos" to "Séries em lançamentos", 
            "filmes-recentes" to "Filmes recentes",
            "atualizacoes" to "Últimas atualizações",
            "animes" to "Animes"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Se for a primeira página, pega da página inicial
        if (page == 1) {
            val document = app.get(mainUrl).document
            
            // Extrai a seção específica baseada no request.data
            val sectionId = request.data.removePrefix("home_")
            val items = extractHomeSection(document, sectionId)
            
            return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
        }
        
        // Para páginas subsequentes, retorna vazio por enquanto
        return newHomePageResponse(request.name, emptyList(), false)
    }

    private fun extractHomeSection(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        // Mapeia IDs de seção para os IDs reais do HTML
        val sectionMap = mapOf(
            "popular" to "os-mais-populares",
            "lancamentos" to "sries-em-lanamentos", 
            "filmes-recentes" to "filmes-recentes",
            "atualizacoes" to "ltimas-atualizaes",
            "animes" to "animes"
        )
        
        val realSectionId = sectionMap[sectionId] ?: return emptyList()
        
        // Encontra a seção pelo aria-labelledby
        val section = document.selectFirst("section[aria-labelledby='$realSectionId']")
            ?: document.selectFirst("section.carousels")
            ?: return emptyList()
        
        // Extrai todos os artigos da seção
        return section.select("article").mapNotNull { article ->
            article.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val href = linkElement.attr("href") ?: return null
        
        // Título do elemento h3 dentro do artigo
        val titleElement = selectFirst("h3")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Extrair ano da data de publicação
        val yearElement = selectFirst("time.release-date")
        val year = yearElement?.attr("datetime")?.substring(0, 4)?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Imagem do pôster
        val imgElement = selectFirst("img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // Determinar tipo pelo URL
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/tv/")
        
        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl
                this.year = year
            }
            isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Corrigido: usar URLEncoder do Java
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document
        
        // Procura por artigos na página de busca
        return document.select("article").mapNotNull { element ->
            element.toSearchResult()
        }.ifEmpty {
            // Fallback: procura em qualquer link que possa ser um resultado
            document.select("a[href*='/serie/'], a[href*='/filme/'], a[href*='/anime/']")
                .mapNotNull { link ->
                    val href = link.attr("href")
                    if (href.contains("/serie/") || href.contains("/filme/") || href.contains("/anime/")) {
                        val title = link.text().trim()
                        if (title.isNotEmpty()) {
                            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                                this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                            }
                        } else null
                    } else null
                }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título principal
        val titleElement = document.selectFirst("h1")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo pelo URL
        val isAnime = url.contains("/anime/")
        val isSerie = url.contains("/serie/")
        
        // Extrair sinopse
        val description = document.selectFirst("meta[name='description']")
            ?.attr("content")?.ifBlank { null } 
            ?: document.selectFirst("p.description, .sinopse")?.text()?.trim()
            ?: document.selectFirst(".description")?.text()?.trim()
        
        // Extrair imagem de fundo do hero section
        val heroSection = document.selectFirst("section.hero")
        val backgroundStyle = heroSection?.attr("style")
        val backdropUrl = backgroundStyle?.let {
            Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
        }?.let { fixUrl(it) }
        
        // Extrair poster da meta tag OG
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val posterUrl = ogImage?.let { fixUrl(it) }
        
        // Extrair episódios para séries
        val episodes = if (isAnime || isSerie) {
            extractEpisodesFromSite(document, url)
        } else {
            emptyList()
        }
        
        // Extrair gêneros se disponíveis
        val genres = document.select("a.chip, .chip, .genre, .tags").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        
        if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = genres
            }
        } else {
            // Para filmes - usar a própria URL como dataUrl por enquanto
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = genres
            }
        }
    }

    private fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extrair episódios da aba de episódios
        val episodeElements = document.select(".episode-item")
        
        episodeElements.forEachIndexed { index, episodeElement ->
            try {
                // O link está no atributo data-protected
                val dataProtected = episodeElement.attr("data-protected")
                if (dataProtected.isBlank()) return@forEachIndexed
                
                // Extrair título do episódio
                val titleElement = episodeElement.selectFirst(".episode-title")
                val title = titleElement?.text()?.trim() ?: "Episódio ${index + 1}"
                
                // Tentar extrair número do episódio
                val epNumber = episodeElement.attr("data-ep").toIntOrNull()
                    ?: Regex("Epis[oó]dio\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                
                // Extrair descrição
                val descriptionElement = episodeElement.selectFirst(".episode-description")
                val description = descriptionElement?.text()?.trim()
                
                // Extrair duração
                val durationElement = episodeElement.selectFirst(".episode-number")
                val durationText = durationElement?.text()
                val episodeDuration = durationText?.let {
                    Regex("(\\d+) min").find(it)?.groupValues?.get(1)?.toIntOrNull()
                }
                
                // Usar a URL data-protected como link do episódio
                val episode = newEpisode(fixUrl(dataProtected)) {
                    this.name = title
                    this.season = 1 // Assumir temporada 1 por padrão
                    this.episode = epNumber
                    this.description = description
                    
                    // Configurar duração do episódio se disponível
                    if (episodeDuration != null) {
                        this.duration = episodeDuration
                    }
                    
                    // Extrair imagem do episódio
                    val imgElement = episodeElement.selectFirst("img")
                    imgElement?.attr("src")?.let { imgUrl ->
                        this.posterUrl = fixUrl(imgUrl)
                    }
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar episódio com erro
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Retorna false - extrator desativado por enquanto
        return false
    }
}
