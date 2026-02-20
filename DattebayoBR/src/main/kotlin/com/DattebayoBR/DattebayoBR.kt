package com.dattebayo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Element

@CloudstreamPlugin
class DattebayoBRPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DattebayoBR())
    }
}

class DattebayoBR : MainAPI() {
    override var mainUrl = "https://www.dattebayo-br.com"
    override var name = "Dattebayo BR"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        // Constantes de seletores
        private const val HOME_ITEM = ".ultimosAnimesHomeItem, .ultimosEpisodiosHomeItem"
        private const val HOME_LINK = "a"
        private const val HOME_TITLE = ".ultimosAnimesHomeItemInfosNome, .ultimosEpisodiosHomeItemInfosNome"
        private const val HOME_IMG = ".ultimosAnimesHomeItemImg img, .ultimosEpisodiosHomeItemImg img"
        private const val HOME_EP_NUM = ".ultimosEpisodiosHomeItemInfosNum"
        private const val HOME_EP_TOTAL = ".ultimosAnimesHomeItemQntEps"

        // Seletores de detalhes
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".aniInfosSingleCapa img"
        private const val DETAIL_SYNOPSIS = ".aniInfosSingleSinopse p"
        private const val DETAIL_GENRES = ".aniInfosSingleGeneros span"
        private const val DETAIL_STATUS = "#completed"
        private const val DETAIL_EPISODES_INFO = ".aniInfosSingleNumsItem"
        private const val EPISODE_CONTAINER = ".aniContainer"
        private const val EPISODE_ITEM = ".ultimosEpisodiosHomeItem"
        private const val EPISODE_LINK = "a"
        private const val EPISODE_TITLE_ATTR = "title"
        
        // Seletores de vídeo
        private const val VIDEO_META_TAG = "meta[itemprop=\"contentURL\"]"
        private const val VIDEO_SCRIPT_VAR = "var vid = '(.*?)'"
    }

    // Página principal
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episódios",
        "$mainUrl/animes" to "Animes (AZ)",
        "$mainUrl/anime-dublado" to "Animes Dublados",
    )

    // === FUNÇÕES AUXILIARES ===
    private fun cleanTitle(title: String): String {
        return title.replace("(?i)\\s*ep\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*-\\s*final".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return "ep(?:is[oó]dio)?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractAnimeNameFromEpisode(episodeItem: Element): String {
        val fullTitle = episodeItem.selectFirst(EPISODE_LINK)?.attr(EPISODE_TITLE_ATTR) ?: return ""
        return fullTitle.replace("(?i)\\s+ep\\s+\\d+(\\s*-\\s*final)?".toRegex(), "").trim()
    }

    // === FUNÇÕES DE MAPEAMENTO ===
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val link = selectFirst(HOME_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst(HOME_TITLE)?.text()?.trim() ?: return null
        val poster = selectFirst(HOME_IMG)?.attr("src")?.let { fixUrl(it) }
        val episodeNum = selectFirst(HOME_EP_NUM)

        return if (episodeNum != null) {
            // É um episódio
            val epNumber = extractEpisodeNumber(title) ?: 1
            val animeName = extractAnimeNameFromEpisode(this)
            
            // Passa o poster junto na URL para usar em load()
            val urlWithPoster = if (poster != null) "$href|poster=$poster" else href
            
            newAnimeSearchResponse(animeName, urlWithPoster, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, epNumber)
            }
        } else {
            // É um anime
            newAnimeSearchResponse(cleanTitle(title), href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, null)
            }
        }
    }

    // === PÁGINA PRINCIPAL ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    // === PESQUISA ===
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        val searchUrl = "$mainUrl/busca?busca=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
    }

    // === CARREGAR DETALHES ===
    override suspend fun load(url: String): LoadResponse {
        val (actualUrl, thumbPoster) = url.split("|poster=").let { 
            it[0] to it.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val document = app.get(actualUrl).document
        val title = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem título"
        
        // Poster
        val poster = thumbPoster ?: document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }
        
        // Sinopse
        val synopsis = document.selectFirst(DETAIL_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível"
        
        // Gêneros
        val genres = document.select(DETAIL_GENRES).map { it.text() }.filter { it.isNotBlank() }
        
        // Ano e status
        var year: Int? = null
        var totalEpisodes: Int? = null
        // Removido ShowStatus.Unknown, vamos usar null

        document.select(DETAIL_EPISODES_INFO).forEach { element ->
            val text = element.text()
            when {
                text.contains("Ano") -> year = text.substringAfter("Ano").trim().toIntOrNull()
                text.contains("Episódios") -> {
                    "(\\d+)/(\\d+)".toRegex().find(text)?.let {
                        totalEpisodes = it.groupValues[2].toIntOrNull()
                    }
                }
            }
        }

        // Status (Completo ou Emissão)
        val showStatus = if (document.selectFirst(DETAIL_STATUS)?.text()?.contains("Completo") == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        // Lista de episódios
        val episodes = mutableListOf<Episode>()
        document.select(EPISODE_CONTAINER).select(EPISODE_ITEM).forEach { element ->
            val link = element.selectFirst(EPISODE_LINK) ?: return@forEach
            val episodeUrl = fixUrl(link.attr("href"))
            val episodeTitle = link.attr(EPISODE_TITLE_ATTR)
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: return@forEach
            val episodePoster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            episodes.add(
                newEpisode(episodeUrl) {
                    name = "Episódio $episodeNumber"
                    episode = episodeNumber
                    posterUrl = episodePoster ?: poster
                }
            )
        }

        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(cleanTitle(title), actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

// === CARREGAR LINKS DE VÍDEO (PARTE MAIS IMPORTANTE) ===
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Debug: URL recebida
    println("🔍 DEBUG LOADLINKS - URL recebida: $data")
    
    // 1. Pega a URL da página do episódio
    val episodePageUrl = data.split("|poster=")[0]
    println("🔍 DEBUG - URL da página do episódio: $episodePageUrl")
    
    // 2. FAZ UMA REQUISIÇÃO NOVA para a página (link fresco!)
    println("🔍 DEBUG - Fazendo requisição para: $episodePageUrl")
    val document = try {
        app.get(episodePageUrl, referer = mainUrl).document
    } catch (e: Exception) {
        println("❌ DEBUG - Erro ao fazer requisição: ${e.message}")
        e.printStackTrace()
        return false
    }
    println("🔍 DEBUG - Requisição OK, documento carregado")
    
    var linksFound = false

    // 3. Tenta extrair da META TAG (mais fácil e confiável)
    println("🔍 DEBUG - Procurando meta tag: $VIDEO_META_TAG")
    val metaElement = document.selectFirst(VIDEO_META_TAG)
    if (metaElement != null) {
        val metaVideoUrl = metaElement.attr("content")
        println("🔍 DEBUG - Meta tag encontrada: $metaVideoUrl")
        
        if (metaVideoUrl.isNotBlank()) {
            println("✅ DEBUG - Link da meta tag OK, adicionando callback")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Servidor Principal",
                    url = metaVideoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "https://playembedapi.site/"  // Header essencial!
                    quality = 1080
                    headers = mapOf(
                        "Range" to "bytes=0-",
                        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                }
            )
            linksFound = true
            println("✅ DEBUG - Callback da meta tag adicionado com sucesso")
        } else {
            println("⚠️ DEBUG - Meta tag encontrada mas está em branco")
        }
    } else {
        println("❌ DEBUG - Meta tag NÃO encontrada")
    }

    // 4. Fallback: extrair do SCRIPT (caso a meta tag não exista)
    if (!linksFound) {
        println("🔍 DEBUG - Procurando link em scripts...")
        val scripts = document.select("script")
        println("🔍 DEBUG - Total de scripts encontrados: ${scripts.size}")
        
        scripts.forEachIndexed { index, script ->
            val content = script.data()
            if (content.isNotBlank()) {
                println("🔍 DEBUG - Script $index: tamanho ${content.length}")
                
                val regex = VIDEO_SCRIPT_VAR.toRegex()
                val match = regex.find(content)
                
                if (match != null) {
                    val scriptUrl = match.groupValues[1]
                    println("✅ DEBUG - Link encontrado no script $index: $scriptUrl")
                    
                    if (scriptUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Servidor (Script)",
                                url = scriptUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = "https://playembedapi.site/"
                                quality = 720
                                headers = mapOf("Range" to "bytes=0-")
                            }
                        )
                        linksFound = true
                        println("✅ DEBUG - Callback do script adicionado com sucesso")
                    }
                } else {
                    // Debug: mostrar primeiros 200 caracteres do script que não deu match
                    if (content.contains("vid")) {
                        println("⚠️ DEBUG - Script $index contém 'vid' mas regex não capturou:")
                        println(content.take(200))
                    }
                }
            }
        }
    }

    // 5. Se ainda não achou, tenta os players por qualidade
    if (!linksFound) {
        println("🔍 DEBUG - Procurando links nos players específicos...")
        val players = listOf(
            "jwContainer_0" to "SD",
            "jwContainer_1" to "HD",
            "jwContainer_2" to "FULLHD"
        )
        
        players.forEach { (containerId, qualityName) ->
            println("🔍 DEBUG - Procurando player $containerId ($qualityName)")
            val playerScripts = document.select("#$containerId script")
            println("🔍 DEBUG - Scripts encontrados no player $containerId: ${playerScripts.size}")
            
            playerScripts.forEachIndexed { index, script ->
                val content = script.data()
                val regex = "var vid = '(.*?)'".toRegex()
                val match = regex.find(content)
                
                if (match != null) {
                    val playerUrl = match.groupValues[1]
                    println("✅ DEBUG - Link encontrado no player $containerId: $playerUrl")
                    
                    if (playerUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Player $qualityName",
                                url = playerUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = "https://playembedapi.site/"
                                quality = when (qualityName) {
                                    "FULLHD" -> 1080
                                    "HD" -> 720
                                    else -> 480
                                }
                                headers = mapOf("Range" to "bytes=0-")
                            }
                        )
                        linksFound = true
                        println("✅ DEBUG - Callback do player $qualityName adicionado")
                    }
                }
            }
        }
    }

    // 6. Resultado final
    if (linksFound) {
        println("✅✅✅ DEBUG SUCESSO - Links encontrados: $linksFound")
    } else {
        println("❌❌❌ DEBUG FALHA - Nenhum link encontrado")
        
        // Debug: salvar HTML para análise
        println("🔍 DEBUG - Primeiros 1000 caracteres do HTML:")
        println(document.html().take(1000))
    }

    return linksFound
}
}
