package com.dattebayo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.JsonUtils.json
import com.lagradost.cloudstream3.utils.JsonUtils.optString
import com.lagradost.cloudstream3.utils.JsonUtils.toJsonArray
import com.lagradost.cloudstream3.utils.JsonUtils.toJsonObject

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
        
        // URLs das requisições
        private const val OUTBRAIN_URL = "https://widgets.outbrain.com/outbrain.js"
        private const val ADS_API_URL = "https://ads.animeyabu.net/"
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

    // === FUNÇÃO PRINCIPAL PARA ENCONTRAR LINKS DE VÍDEO ===
    private fun findAllVideoUrls(document: Document): List<Pair<String, String>> {
        val videoUrls = mutableListOf<Pair<String, String>>()
        
        println("🔍 DEBUG - Varrendo HTML em busca de links do Cloudflare...")
        
        // 1. Procurar em scripts com var vid
        document.select("script").forEachIndexed { index, script ->
            val content = script.data()
            
            // Regex para encontrar var vid = 'URL' (Cloudflare R2)
            val regex = "var vid = '(https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[^']+\\.mp4)'".toRegex()
            val matches = regex.findAll(content)
            
            matches.forEach { match ->
                val url = match.groupValues[1]
                
                // Determinar qualidade pelo ID do container pai ou padrão na URL
                val quality = when {
                    script.parent()?.id() == "jwContainer_2" -> "FULLHD"
                    script.parent()?.id() == "jwContainer_1" -> "HD"
                    script.parent()?.id() == "jwContainer_0" -> "SD"
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }
                
                println("✅ DEBUG - Link encontrado no script $index: $quality - $url")
                videoUrls.add(Pair(url, quality))
            }
        }
        
        // 2. Procurar em qualquer lugar do HTML por URLs do Cloudflare (fallback)
        val html = document.html()
        val cloudflareRegex = "https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[a-zA-Z0-9]+/[0-9]+\\.mp4".toRegex()
        cloudflareRegex.findAll(html).forEach { match ->
            val url = match.value
            // Evitar duplicatas
            if (!videoUrls.any { it.first == url }) {
                val quality = when {
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }
                println("✅ DEBUG - Link encontrado no HTML: $quality - $url")
                videoUrls.add(Pair(url, quality))
            }
        }
        
        return videoUrls.distinctBy { it.first }
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

    // === CARREGAR LINKS DE VÍDEO (COM AS 3 REQUISIÇÕES) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageUrl = data.split("|poster=")[0]
        println("🔍 DEBUG LOADLINKS - URL recebida: $episodePageUrl")
        
        // 1. Pega a página do episódio para obter os links base
        val document = app.get(episodePageUrl, referer = mainUrl).document
        val baseUrls = findAllVideoUrls(document)
        println("🔍 DEBUG - Links base encontrados: ${baseUrls.size}")
        
        if (baseUrls.isEmpty()) {
            println("❌ DEBUG - Nenhum link base encontrado!")
            return false
        }
        
        // Prioriza os links por qualidade
        val priority = mapOf("FULLHD" to 4, "HD" to 3, "SD" to 2, "Unknown" to 1)
        val sortedUrls = baseUrls.sortedByDescending { priority[it.second] ?: 0 }
        
        var linksFound = false
        
        // Para cada link base, faz as 3 requisições
        sortedUrls.forEach { (baseUrl, quality) ->
            try {
                println("🔍 DEBUG - Processando link: $quality")
                
                // PASSO 1: GET outbrain.js
                println("🔍 DEBUG - Buscando outbrain.js")
                val outbrainJs = app.get(
                    OUTBRAIN_URL,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                // PASSO 2: POST para ads.animeyabu.net
                println("🔍 DEBUG - Enviando POST para ads.animeyabu.net")
                val firstResponse = app.post(
                    url = ADS_API_URL,
                    data = mapOf(
                        "category" to "client",
                        "type" to "premium",
                        "ad" to outbrainJs
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                // Parse da primeira resposta
                val firstArray = firstResponse.toJsonArray()
                val firstObj = firstArray[0].toJsonObject()
                val token = firstObj.optString("publicidade")
                
                if (token.isNullOrBlank()) {
                    println("❌ DEBUG - Token não encontrado na primeira resposta")
                    return@forEach
                }
                
                println("🔍 DEBUG - Token obtido: ${token.take(50)}...")
                
                // PASSO 3: GET com token + URL base
                val secondUrl = "$ADS_API_URL?token=$token&url=$baseUrl"
                println("🔍 DEBUG - Buscando parâmetros finais")
                
                val secondResponse = app.get(
                    secondUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                // Parse da segunda resposta
                val secondArray = secondResponse.toJsonArray()
                val secondObj = secondArray[0].toJsonObject()
                val authParams = secondObj.optString("publicidade")
                
                if (authParams.isNullOrBlank()) {
                    println("❌ DEBUG - Parâmetros não encontrados na segunda resposta")
                    return@forEach
                }
                
                println("🔍 DEBUG - Parâmetros obtidos: ${authParams.take(50)}...")
                
                // Link final = baseUrl + authParams (concatenação simples!)
                val finalUrl = baseUrl + authParams
                println("✅ DEBUG - Link final gerado: ${finalUrl.take(100)}...")
                
                val qualityValue = when (quality) {
                    "FULLHD" -> 1080
                    "HD" -> 720
                    "SD" -> 480
                    else -> 720
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Cloudflare $quality",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityValue
                        referer = mainUrl
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
                
                linksFound = true
                
            } catch (e: Exception) {
                println("❌ DEBUG - Erro ao processar link: ${e.message}")
                e.printStackTrace()
            }
        }
        
        println("✅✅✅ DEBUG - Links encontrados: $linksFound")
        return linksFound
    }
}
