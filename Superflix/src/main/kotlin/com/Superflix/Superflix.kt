package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class SuperflixMain : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        /** TODAS as categorias reais do site (sem repetir) */
        private val GENRE_URLS = listOf(
            "/category/acao/" to "Ação",
            "/category/action-adventure/" to "Action & Adventure",
            "/category/animacao/" to "Animação",
            "/category/aventura/" to "Aventura",
            "/category/cinema-tv/" to "Cinema TV",
            "/category/comedia/" to "Comédia",
            "/category/crime/" to "Crime",
            "/category/documentario/" to "Documentário",
            "/category/drama/" to "Drama",
            "/category/familia/" to "Família",
            "/category/fantasia/" to "Fantasia",
            "/category/faroeste/" to "Faroeste",
            "/category/ficcao-cientifica/" to "Ficção Científica",
            "/category/guerra/" to "Guerra",
            "/category/historia/" to "História",
            "/category/kids/" to "Kids",
            "/category/misterio/" to "Mistério",
            "/category/musica/" to "Música",
            "/category/news/" to "News",
            "/category/reality/" to "Reality",
            "/category/romance/" to "Romance",
            "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
            "/category/soap/" to "Soap",
            "/category/talk/" to "Talk",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
        )

        /** Abas fixas da HOME */
        private val FIXED_TABS = listOf(
            "/" to "Filmes Recomendados",
            "/" to "Séries Recomendadas",
            "/category/lancamentos/" to "Lançamentos"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_TABS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray(),
        *GENRE_URLS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray()
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // ---------- RECOMENDADOS (HOME, SEM PAGINAÇÃO) ----------
        if (request.name == "Filmes Recomendados" || request.name == "Séries Recomendadas") {

            val document = app.get(mainUrl).document

            val selector = if (request.name == "Filmes Recomendados") {
                "#widget_list_movies_series-6 article.post"
            } else {
                "#widget_list_movies_series-8 article.post"
            }

            val home = document.select(selector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            return newHomePageResponse(
                request.name,
                home,
                false
            )
        }

        // ---------- GÊNEROS / LANÇAMENTOS (COM PAGINAÇÃO) ----------
        val baseUrl = if (request.data.endsWith("/")) {
            request.data
        } else {
            "${request.data}/"
        }

        val url = if (page > 1) {
            "${baseUrl}page/$page/"
        } else {
            baseUrl
        }

        val document = app.get(url).document

        val home = document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        // mesma lógica do plugin SuperFlix (mais permissiva)
        val hasNext =
            document.select("a:contains(Próxima)").isNotEmpty() ||
            document.select(".page-numbers a[href*='page']").isNotEmpty() ||
            document.select(".pagination").isNotEmpty()

        return newHomePageResponse(
            request.name,
            home,
            hasNext
        )
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return when {
            href.contains("/filme/") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            href.contains("/serie/") ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: throw ErrorLoadingException("Título não encontrado")

        val poster = document.selectFirst("div.post-thumbnail img")
            ?.attr("src")?.let { fixUrl(it) }

        // BANNER REAL - extraído das divs no final do HTML
        val banner = document.selectFirst("div.bghd img.TPostBg, div.bgft img.TPostBg")
            ?.attr("src")?.let { fixUrl(it) }

        // ANO - usando o método original do código anterior
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()

        // DURAÇÃO - como no exemplo do SuperFlix
        val duration = document.selectFirst("span:containsOwn(min.)")?.text()
            ?.replace(" min.", "")?.trim()?.toIntOrNull()

        // DESCRIÇÃO
        val description = document.selectFirst("div.description p")?.text()

        // TAGS - usando o método original do código anterior
        val genres = document.select("span.genres a").map { it.text() }.filter { it.isNotBlank() }

        // DIRETOR
        val director = document.selectFirst("p:contains(Diretor)")?.ownText()?.trim()
            ?: document.select("p").firstOrNull { it.text().contains("Diretor") }?.text()
                ?.replace("Diretor", "")?.trim()

        // ELENCO
        val actors = mutableListOf<String>()
        
        // Adiciona o diretor como primeiro item do elenco (se existir)
        director?.let { actors.add("Diretor: $it") }
        
        // Adiciona o restante do elenco
        document.selectFirst("p:contains(Elenco)")?.ownText()?.trim()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.let { actors.addAll(it) }

        // DETERMINAR SE É FILME OU SÉRIE
        return if (url.contains("/filme/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.duration = duration  // Duração para filmes
                this.plot = description
                this.tags = genres  // Tags como estava no código original
                addActors(actors)
            }
        } else {
            // Extrair episódios do iframe
            val episodes = extractEpisodes(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.plot = description
                this.tags = genres  // Tags como estava no código original
                addActors(actors)
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Encontrar o iframe que contém os episódios
            val iframeSrc = document.selectFirst("div#adangle-pop-iframe-container iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            
            if (iframeSrc != null) {
                println("[Superflix] Iframe encontrado: $iframeSrc")
                
                // Fazer requisição para o iframe
                val iframeDoc = app.get(fixUrl(iframeSrc)).document
                
                // Extrair todos os episódios
                val episodeElements = iframeDoc.select("div.card-body ul li")
                println("[Superflix] Encontrados ${episodeElements.size} elementos de episódio")
                
                episodeElements.forEach { episodeElement ->
                    try {
                        val episodeId = episodeElement.attr("data-episode-id")
                        val seasonId = episodeElement.attr("data-season-id")
                        
                        println("[Superflix] Processando episódio ID: $episodeId, temporada ID: $seasonId")
                        
                        // Extrair número do episódio do texto
                        val episodeText = episodeElement.text().trim()
                        val episodeNumber = extractEpisodeNumber(episodeText)
                        
                        // Extrair número da temporada do atributo ou do header
                        val seasonNumberAttr = episodeElement.attr("data-season-number")
                        val seasonNumber = if (seasonNumberAttr.isNotBlank()) {
                            seasonNumberAttr.toIntOrNull() ?: 1
                        } else {
                            // Tentar extrair do header
                            val headerItem = iframeDoc.select("li[data-season-id='$seasonId']").firstOrNull()
                            headerItem?.attr("data-season-number")?.toIntOrNull() ?: 1
                        }
                        
                        // Nome do episódio
                        val episodeName = episodeElement.selectFirst("a")?.text()?.trim()
                            ?: "Episódio $episodeNumber"
                        
                        // URL do episódio - será processada pelo extrator
                        // Usamos a URL do player + ID do episódio
                        val episodeUrl = "https://assistirseriesonline.icu/episodio/$episodeId"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = episodeName
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = null // Poderia extrair thumbnail se disponível
                            }
                        )
                        
                        println("[Superflix] Episódio adicionado: T${seasonNumber}E$episodeNumber - $episodeName")
                        
                    } catch (e: Exception) {
                        println("[Superflix] Erro ao processar episódio: ${e.message}")
                    }
                }
                
                // Ordenar por temporada e episódio
                episodes.sortWith(compareBy({ it.season }, { it.episode }))
            }
            
            // Fallback se não encontrar episódios
            if (episodes.isEmpty()) {
                println("[Superflix] Nenhum episódio encontrado, criando fallback")
                episodes.add(
                    newEpisode(seriesUrl) {
                        this.name = "Assistir Série"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            
        } catch (e: Exception) {
            println("[Superflix] Erro ao extrair episódios: ${e.message}")
            episodes.add(
                newEpisode(seriesUrl) {
                    this.name = "Assistir Série"
                    this.season = 1
                    this.episode = 1
                }
            )
        }
        
        println("[Superflix] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }
    
    private fun extractEpisodeNumber(text: String): Int {
        // Padrões: "1 - Episódio", "Episódio 1", "1º Episódio", "Ep. 1"
        val patterns = listOf(
            Regex("""^(\d+)\s*-"""),
            Regex("""Episódio\s*(\d+)"""),
            Regex("""Ep\.?\s*(\d+)"""),
            Regex("""(\d+)\s*º?\s*Episódio""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        return 1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[SuperflixMain] loadLinks chamado com data: $data")
        println("[SuperflixMain] isCasting: $isCasting")
        
        // Usar o extrator personalizado
        val extractor = SuperflixExtractor()
        try {
            extractor.getUrl(data, mainUrl, subtitleCallback, callback)
            return true
        } catch (e: Exception) {
            println("[SuperflixMain] Erro no extrator: ${e.message}")
            return false
        }
    }
}

@CloudstreamPlugin
class SuperflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperflixMain())
    }
}
