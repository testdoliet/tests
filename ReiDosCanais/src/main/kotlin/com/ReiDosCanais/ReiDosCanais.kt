package com.reidoscanais

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

@CloudstreamPlugin
class ReiDosCanaisProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosCanais())
    }
}

class ReiDosCanais : MainAPI() {
    override var name = "Rei dos Canais"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    
    // URLs da API
    private val apiBaseUrl = "https://api.reidoscanais.io"
    private val channelsEndpoint = "$apiBaseUrl/channels"
    private val sportsLiveEndpoint = "$apiBaseUrl/sports?status=live"
    
    // Domínio do player (para o embed)
    private val playerDomain = "p2player.live"
    
    // Constante mágica para decodificação
    private val magicNumber = 10659686
    
    private val mapper = jacksonObjectMapper()

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            // 1. Buscar eventos esportivos ao vivo (primeira seção)
            val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).text
            val sportsJson = mapper.readTree(sportsResponse)
            
            if (sportsJson.has("success") && sportsJson["success"].asBoolean()) {
                val sportsData = sportsJson["data"]
                if (sportsData.isArray) {
                    val liveEvents = sportsData.mapNotNull { node ->
                        parseSportEvent(node)
                    }.map { event ->
                        newLiveSearchResponse(
                            event.title,
                            "sport|${event.id}",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(event.poster)
                            // plot não é suportado em LiveSearchResponse
                        }
                    }
                    if (liveEvents.isNotEmpty()) {
                        homePageList.add(HomePageList("Eventos Ao Vivo", liveEvents, isHorizontalImages = true))
                    }
                }
            }

            // 2. Buscar todos os canais
            val channelsResponse = app.get(channelsEndpoint, timeout = 30).text
            val channelsJson = mapper.readTree(channelsResponse)
            
            if (channelsJson.has("success") && channelsJson["success"].asBoolean()) {
                val channelsData = channelsJson["data"]
                if (channelsData.isArray) {
                    // Agrupar canais por categoria
                    val channelsByCategory = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.filter { it.isActive }
                    .groupBy { it.category }
                    
                    // Para cada categoria, criar uma HomePageList
                    channelsByCategory.forEach { (categoryName, channels) ->
                        val channelList = channels.map { channel ->
                            newLiveSearchResponse(
                                channel.name,
                                "channel|${channel.id}|${channel.embedUrl}",
                                TvType.Live
                            ) {
                                this.posterUrl = fixUrl(channel.logoUrl)
                            }
                        }
                        
                        if (channelList.isNotEmpty()) {
                            homePageList.add(HomePageList(categoryName, channelList, isHorizontalImages = true))
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw ErrorLoadingException("Falha ao carregar canais: ${e.message}")
        }

        if (homePageList.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        return newHomePageResponse(homePageList)
    }
    
    private fun parseChannel(node: JsonNode): ApiChannel? {
        return try {
            ApiChannel(
                id = node["id"]?.asText() ?: return null,
                name = node["name"]?.asText() ?: return null,
                description = node["description"]?.asText(),
                logoUrl = node["logo_url"]?.asText() ?: "",
                embedUrl = node["embed_url"]?.asText() ?: return null,
                category = node["category"]?.asText() ?: "Sem Categoria",
                isActive = node["is_active"]?.asBoolean() ?: true
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseSportEvent(node: JsonNode): SportEvent? {
        return try {
            val embeds = mutableListOf<SportEmbed>()
            val embedsNode = node["embeds"]
            if (embedsNode.isArray) {
                for (embedNode in embedsNode) {
                    val embed = SportEmbed(
                        provider = embedNode["provider"]?.asText() ?: "Desconhecido",
                        quality = embedNode["quality"]?.asText() ?: "HD",
                        embedUrl = embedNode["embed_url"]?.asText() ?: continue
                    )
                    embeds.add(embed)
                }
            }
            
            SportEvent(
                id = node["id"]?.asText() ?: return null,
                title = node["title"]?.asText() ?: return null,
                description = node["description"]?.asText(),
                poster = node["poster"]?.asText() ?: "",
                startTime = node["start_time"]?.asText() ?: "",
                endTime = node["end_time"]?.asText() ?: "",
                status = node["status"]?.asText() ?: "live",
                category = node["category"]?.asText() ?: "Esportes",
                embeds = embeds
            )
        } catch (e: Exception) {
            null
        }
    }

    // ================== CARREGAR STREAM ==================
    override suspend fun load(data: String): LoadResponse {
        val parts = data.split("|", limit = 3)
        
        return when (parts[0]) {
            "sport" -> {
                val eventId = parts[1]
                loadSportEvent(eventId)
            }
            "channel" -> {
                if (parts.size < 3) throw ErrorLoadingException("Formato de dados inválido")
                val channelId = parts[1]
                val embedUrl = parts[2]
                loadChannel(channelId, embedUrl)
            }
            else -> throw ErrorLoadingException("Tipo de mídia desconhecido")
        }
    }
    
    private suspend fun loadSportEvent(eventId: String): LoadResponse {
        val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).text
        val sportsJson = mapper.readTree(sportsResponse)
        
        if (!sportsJson.has("success") || !sportsJson["success"].asBoolean()) {
            throw ErrorLoadingException("Não foi possível carregar o evento")
        }
        
        val sportsData = sportsJson["data"]
        var event: SportEvent? = null
        
        if (sportsData.isArray) {
            for (node in sportsData) {
                val id = node["id"]?.asText()
                if (id == eventId) {
                    event = parseSportEvent(node)
                    break
                }
            }
        }
        
        val foundEvent = event ?: throw ErrorLoadingException("Evento não encontrado")
        
        return newMovieLoadResponse(
            foundEvent.title,
            "sport|${foundEvent.id}",
            TvType.Live,
            "sport|${foundEvent.id}"
        ) {
            this.posterUrl = fixUrl(foundEvent.poster)
            this.plot = foundEvent.description ?: "Evento esportivo ao vivo"
            this.tags = listOf(foundEvent.category, foundEvent.status)
        }
    }
    
    private suspend fun loadChannel(channelId: String, embedUrl: String): LoadResponse {
        val channelsResponse = app.get(channelsEndpoint, timeout = 30).text
        val channelsJson = mapper.readTree(channelsResponse)
        
        if (!channelsJson.has("success") || !channelsJson["success"].asBoolean()) {
            throw ErrorLoadingException("Não foi possível carregar o canal")
        }
        
        val channelsData = channelsJson["data"]
        var channel: ApiChannel? = null
        
        if (channelsData.isArray) {
            for (node in channelsData) {
                val id = node["id"]?.asText()
                if (id == channelId) {
                    channel = parseChannel(node)
                    break
                }
            }
        }
        
        val foundChannel = channel ?: throw ErrorLoadingException("Canal não encontrado")
        
        return newMovieLoadResponse(
            foundChannel.name,
            "channel|${foundChannel.id}|${foundChannel.embedUrl}",
            TvType.Live,
            foundChannel.embedUrl
        ) {
            this.posterUrl = fixUrl(foundChannel.logoUrl)
            this.plot = foundChannel.description ?: "Assista ao canal ${foundChannel.name} ao vivo"
            this.tags = listOf(foundChannel.category)
        }
    }

    // ================== BUSCA ==================
    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        
        try {
            // Buscar em canais
            val channelsResponse = app.get(channelsEndpoint, timeout = 30).text
            val channelsJson = mapper.readTree(channelsResponse)
            
            if (channelsJson.has("success") && channelsJson["success"].asBoolean()) {
                val channelsData = channelsJson["data"]
                if (channelsData.isArray) {
                    val matchingChannels = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.filter { it.isActive && it.name.contains(query, ignoreCase = true) }
                    .map { channel ->
                        newLiveSearchResponse(
                            channel.name,
                            "channel|${channel.id}|${channel.embedUrl}",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(channel.logoUrl)
                        }
                    }
                    results.addAll(matchingChannels)
                }
            }
            
            // Buscar em eventos esportivos
            val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).text
            val sportsJson = mapper.readTree(sportsResponse)
            
            if (sportsJson.has("success") && sportsJson["success"].asBoolean()) {
                val sportsData = sportsJson["data"]
                if (sportsData.isArray) {
                    val matchingEvents = sportsData.mapNotNull { node ->
                        parseSportEvent(node)
                    }.filter { 
                        it.title.contains(query, ignoreCase = true) || 
                        (it.description?.contains(query, ignoreCase = true) == true) 
                    }
                    .map { event ->
                        newLiveSearchResponse(
                            event.title,
                            "sport|${event.id}",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(event.poster)
                        }
                    }
                    results.addAll(matchingEvents)
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        
        return results
    }

    // ================== EXTRAIR LINK M3U8 ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 3)
        
        return when (parts[0]) {
            "sport" -> {
                val eventId = parts[1]
                loadSportLinks(eventId, callback)
            }
            "channel" -> {
                if (parts.size < 3) return false
                val embedUrl = parts[2]
                loadChannelLinks(embedUrl, callback)
            }
            else -> {
                loadChannelLinks(data, callback)
            }
        }
    }
    
    private suspend fun loadSportLinks(
        eventId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).text
        val sportsJson = mapper.readTree(sportsResponse)
        
        if (!sportsJson.has("success") || !sportsJson["success"].asBoolean()) {
            return false
        }
        
        val sportsData = sportsJson["data"]
        var event: SportEvent? = null
        
        if (sportsData.isArray) {
            for (node in sportsData) {
                val id = node["id"]?.asText()
                if (id == eventId) {
                    event = parseSportEvent(node)
                    break
                }
            }
        }
        
        val foundEvent = event ?: return false
        var foundAny = false
        
        for (embed in foundEvent.embeds) {
            try {
                val success = extractFromEmbedUrl(embed.embedUrl, callback)
                if (success) {
                    foundAny = true
                }
            } catch (e: Exception) {
                // Ignorar erro em um embed específico
            }
        }
        
        return foundAny
    }
    
    private suspend fun loadChannelLinks(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return extractFromEmbedUrl(embedUrl, callback)
    }
    
    private suspend fun extractFromEmbedUrl(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(embedUrl, allowRedirects = true)
            val doc = response.document
            val html = doc.html()
            
            val iframeSrc = doc.select("iframe")
                .map { it.attr("src") }
                .firstOrNull { it.contains(playerDomain) || it.contains("player") || it.contains("embed") }
            
            if (iframeSrc.isNullOrEmpty()) {
                return false
            }
            
            val absoluteIframeUrl = if (iframeSrc.startsWith("//")) {
                "https:$iframeSrc"
            } else if (!iframeSrc.startsWith("http")) {
                "https://$playerDomain$iframeSrc"
            } else {
                iframeSrc
            }
            
            val iframeDoc = app.get(absoluteIframeUrl, referer = embedUrl).document
            val iframeHtml = iframeDoc.html()
            
            val hnoArray = extractHNOArray(iframeHtml)
            if (hnoArray == null) {
                val directUrl = extractM3u8Url(iframeHtml)
                if (directUrl != null) {
                    val link = newExtractorLink(
                        source = "Rei dos Canais",
                        name = "Rei dos Canais",
                        url = directUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = absoluteIframeUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to absoluteIframeUrl,
                            "Origin" to "https://reidoscanais.io"
                        )
                    }
                    callback.invoke(link)
                    return true
                }
                return false
            }
            
            val decodedHtml = decodeHNOArray(hnoArray)
            if (decodedHtml.isEmpty()) {
                return false
            }
            
            val finalM3u8Url = extractM3u8Url(decodedHtml)
            if (finalM3u8Url == null) {
                return false
            }
            
            val link = newExtractorLink(
                source = "Rei dos Canais",
                name = "Rei dos Canais",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = absoluteIframeUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to absoluteIframeUrl,
                    "Origin" to "https://reidoscanais.io"
                )
            }
            callback.invoke(link)
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    // ================== FUNÇÕES AUXILIARES ==================
    
    private fun extractHNOArray(html: String): List<String>? {
        val regex = Regex("""var\s+hNO\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        
        val arrayContent = match.groupValues[1]
        val itemRegex = Regex("""["']([^"']+)["']""")
        return itemRegex.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
    }
    
    private fun decodeHNOArray(items: List<String>): String {
        return buildString {
            for (encoded in items) {
                try {
                    val base64Decoded = String(Base64.getDecoder().decode(encoded))
                    val numbersOnly = base64Decoded.replace(Regex("\\D"), "")
                    val charCode = numbersOnly.toIntOrNull()?.minus(magicNumber)
                    
                    if (charCode != null && charCode in 0..0xFFFF) {
                        append(charCode.toChar())
                    }
                } catch (e: Exception) {
                    // Ignorar erros
                }
            }
        }
    }
    
    private fun extractM3u8Url(html: String): String? {
        val iframePattern = Regex("""<iframe.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        iframePattern.find(html)?.let { return it.groupValues[1] }
        
        val videoPattern = Regex("""<video.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        videoPattern.find(html)?.let { return it.groupValues[1] }
        
        val sourcePattern = Regex("""<source.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        sourcePattern.find(html)?.let { return it.groupValues[1] }
        
        val urlPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        return urlPattern.find(html)?.value
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$apiBaseUrl$url"
            else -> url
        }
    }
}

// ================== DATA CLASSES ==================

data class ApiChannel(
    val id: String,
    val name: String,
    val description: String?,
    val logoUrl: String,
    val embedUrl: String,
    val category: String,
    val isActive: Boolean
)

data class SportEvent(
    val id: String,
    val title: String,
    val description: String?,
    val poster: String,
    val startTime: String,
    val endTime: String,
    val status: String,
    val category: String,
    val embeds: List<SportEmbed>
)

data class SportEmbed(
    val provider: String,
    val quality: String,
    val embedUrl: String
)
