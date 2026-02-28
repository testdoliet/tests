package com.reidoscanais

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Request
import java.util.Base64

@CloudstreamPlugin
class ReiDosCanaisProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosCanais())
    }
}

// ================== DATA CLASSES PARA A API ==================

data class ApiChannel(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("logo_url") val logoUrl: String,
    @JsonProperty("embed_url") val embedUrl: String,
    @JsonProperty("category") val category: String,
    @JsonProperty("is_active") val isActive: Boolean
)

data class ApiCategory(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String
)

data class ApiResponse<T>(
    @JsonProperty("success") val success: Boolean,
    @JsonProperty("data") val data: T,
    @JsonProperty("total") val total: Int? = null
)

data class SportEvent(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("poster") val poster: String,
    @JsonProperty("start_time") val startTime: String,
    @JsonProperty("end_time") val endTime: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("category") val category: String,
    @JsonProperty("embeds") val embeds: List<SportEmbed>
)

data class SportEmbed(
    @JsonProperty("provider") val provider: String,
    @JsonProperty("quality") val quality: String,
    @JsonProperty("embed_url") val embedUrl: String
)

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
    private val categoriesEndpoint = "$apiBaseUrl/channels/categories"
    private val sportsLiveEndpoint = "$apiBaseUrl/sports?status=live"
    private val mainSiteUrl = "https://reidoscanais.io"
    
    // Domínio do player (para o embed)
    private val playerDomain = "p2player.live"
    
    // Constante mágica para decodificação
    private val magicNumber = 10659686

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            // 1. Buscar eventos esportivos ao vivo (primeira seção)
            val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).parsed<ApiResponse<List<SportEvent>>>()
            if (sportsResponse.success && sportsResponse.data.isNotEmpty()) {
                val liveEvents = sportsResponse.data.map { event ->
                    LiveSearchResponse(
                        name = event.title,
                        url = "sport|${event.id}",
                        apiName = this.name,
                        type = TvType.Live,
                        posterUrl = fixUrl(event.poster)
                    )
                }
                if (liveEvents.isNotEmpty()) {
                    homePageList.add(HomePageList("Eventos Ao Vivo", liveEvents, isHorizontalImages = true))
                }
            }

            // 2. Buscar todos os canais
            val channelsResponse = app.get(channelsEndpoint, timeout = 30).parsed<ApiResponse<List<ApiChannel>>>()
            
            if (channelsResponse.success) {
                // Agrupar canais por categoria
                val channelsByCategory = channelsResponse.data
                    .filter { it.isActive }
                    .groupBy { it.category }
                
                // Para cada categoria, criar uma HomePageList
                channelsByCategory.forEach { (categoryName, channels) ->
                    val channelList = channels.map { channel ->
                        LiveSearchResponse(
                            name = channel.name,
                            url = "channel|${channel.id}|${channel.embedUrl}",
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = fixUrl(channel.logoUrl)
                        )
                    }
                    
                    if (channelList.isNotEmpty()) {
                        homePageList.add(HomePageList(categoryName, channelList, isHorizontalImages = true))
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
        
        return HomePageResponse(homePageList)
    }

    // ================== CARREGAR STREAM ==================
    override suspend fun load(data: String): LoadResponse {
        val parts = data.split("|", limit = 3)
        
        return when (parts[0]) {
            "sport" -> {
                // É um evento esportivo
                val eventId = parts[1]
                loadSportEvent(eventId)
            }
            "channel" -> {
                // É um canal normal
                if (parts.size < 3) throw ErrorLoadingException("Formato de dados inválido")
                val channelId = parts[1]
                val embedUrl = parts[2]
                loadChannel(channelId, embedUrl)
            }
            else -> throw ErrorLoadingException("Tipo de mídia desconhecido")
        }
    }
    
    private suspend fun loadSportEvent(eventId: String): LoadResponse {
        // Para eventos esportivos, precisamos buscar os detalhes atualizados
        // Como não temos um endpoint específico para um evento, usamos a lista de ao vivo
        val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).parsed<ApiResponse<List<SportEvent>>>()
        
        if (!sportsResponse.success) {
            throw ErrorLoadingException("Não foi possível carregar o evento")
        }
        
        val event = sportsResponse.data.find { it.id == eventId }
            ?: throw ErrorLoadingException("Evento não encontrado")
        
        // Criar um LoadResponse para o evento
        return LiveStreamLoadResponse(
            name = event.title,
            url = "sport|${event.id}",
            apiName = this.name,
            dataUrl = "sport|${event.id}",
            posterUrl = fixUrl(event.poster),
            plot = event.description ?: "Evento esportivo ao vivo",
            tags = listOf(event.category, event.status)
        )
    }
    
    private suspend fun loadChannel(channelId: String, embedUrl: String): LoadResponse {
        // Buscar detalhes atualizados do canal
        val channelsResponse = app.get(channelsEndpoint, timeout = 30).parsed<ApiResponse<List<ApiChannel>>>()
        
        if (!channelsResponse.success) {
            throw ErrorLoadingException("Não foi possível carregar o canal")
        }
        
        val channel = channelsResponse.data.find { it.id == channelId }
            ?: throw ErrorLoadingException("Canal não encontrado")
        
        return LiveStreamLoadResponse(
            name = channel.name,
            url = "channel|${channel.id}|${channel.embedUrl}",
            apiName = this.name,
            dataUrl = channel.embedUrl,
            posterUrl = fixUrl(channel.logoUrl),
            plot = channel.description ?: "Assista ao canal ${channel.name} ao vivo",
            tags = listOf(channel.category)
        )
    }

    // ================== BUSCA ==================
    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        
        try {
            // Buscar em canais
            val channelsResponse = app.get(channelsEndpoint, timeout = 30).parsed<ApiResponse<List<ApiChannel>>>()
            if (channelsResponse.success) {
                val matchingChannels = channelsResponse.data
                    .filter { it.isActive && it.name.contains(query, ignoreCase = true) }
                    .map { channel ->
                        LiveSearchResponse(
                            name = channel.name,
                            url = "channel|${channel.id}|${channel.embedUrl}",
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = fixUrl(channel.logoUrl)
                        )
                    }
                results.addAll(matchingChannels)
            }
            
            // Buscar em eventos esportivos
            val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).parsed<ApiResponse<List<SportEvent>>>()
            if (sportsResponse.success) {
                val matchingEvents = sportsResponse.data
                    .filter { it.title.contains(query, ignoreCase = true) || (it.description?.contains(query, ignoreCase = true) == true) }
                    .map { event ->
                        LiveSearchResponse(
                            name = event.title,
                            url = "sport|${event.id}",
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = fixUrl(event.poster)
                        )
                    }
                results.addAll(matchingEvents)
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
                // Compatibilidade com versões antigas (se o data for apenas a URL)
                loadChannelLinks(data, callback)
            }
        }
    }
    
    private suspend fun loadSportLinks(
        eventId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buscar detalhes atualizados do evento
        val sportsResponse = app.get(sportsLiveEndpoint, timeout = 30).parsed<ApiResponse<List<SportEvent>>>()
        
        if (!sportsResponse.success) {
            return false
        }
        
        val event = sportsResponse.data.find { it.id == eventId }
            ?: return false
        
        var foundAny = false
        
        // Para cada embed do evento, tentar extrair o link
        for (embed in event.embeds) {
            try {
                val success = extractFromEmbedUrl(embed.embedUrl, callback)
                if (success) {
                    foundAny = true
                }
            } catch (e: Exception) {
                // Ignorar erro em um embed específico e tentar o próximo
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
            // 1. Acessar a URL do embed (pode ser um redirect)
            val response = app.get(embedUrl, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document
            val html = doc.html()
            
            // 2. Extrair URL do iframe do player
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
            
            // 3. Acessar página do iframe
            val iframeDoc = app.get(absoluteIframeUrl, referer = embedUrl).document
            val iframeHtml = iframeDoc.html()
            
            // 4. Extrair array hNO do JavaScript
            val hnoArray = extractHNOArray(iframeHtml)
            if (hnoArray == null) {
                // Se não encontrar hNO, tentar encontrar o link diretamente
                val directUrl = extractM3u8Url(iframeHtml)
                if (directUrl != null) {
                    createAndSendExtractorLink(directUrl, absoluteIframeUrl, callback)
                    return true
                }
                return false
            }
            
            // 5. Decodificar array para obter HTML real
            val decodedHtml = decodeHNOArray(hnoArray)
            if (decodedHtml.isEmpty()) {
                return false
            }
            
            // 6. Procurar link .m3u8 no HTML decodificado
            val finalM3u8Url = extractM3u8Url(decodedHtml)
            if (finalM3u8Url == null) {
                return false
            }
            
            // 7. Retornar link para o player
            createAndSendExtractorLink(finalM3u8Url, absoluteIframeUrl, callback)
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    private suspend fun createAndSendExtractorLink(
        url: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        val link = newExtractorLink(
            source = "Rei dos Canais",
            name = "Rei dos Canais",
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Origin" to "https://reidoscanais.io"
            )
        }
        callback.invoke(link)
    }
    
    // ================== FUNÇÕES AUXILIARES PARA DECODIFICAÇÃO ==================
    
    /**
     * Extrai o array hNO do JavaScript ofuscado
     */
    private fun extractHNOArray(html: String): List<String>? {
        // Procurar pelo padrão: var hNO = [ ... ];
        val regex = Regex("""var\s+hNO\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        
        val arrayContent = match.groupValues[1]
        
        // Extrair cada string entre aspas
        val itemRegex = Regex("""["']([^"']+)["']""")
        return itemRegex.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
    }
    
    /**
     * Decodifica o array hNO usando a lógica do site
     */
    private fun decodeHNOArray(items: List<String>): String {
        return buildString {
            for (encoded in items) {
                try {
                    // 1. Decodificar Base64
                    val base64Decoded = String(Base64.getDecoder().decode(encoded))
                    
                    // 2. Remover tudo que não é dígito
                    val numbersOnly = base64Decoded.replace(Regex("\\D"), "")
                    
                    // 3. Converter para número e subtrair o magic number
                    val charCode = numbersOnly.toIntOrNull()?.minus(magicNumber)
                    
                    // 4. Converter para caractere e anexar
                    if (charCode != null && charCode in 0..0xFFFF) {
                        append(charCode.toChar())
                    }
                } catch (e: Exception) {
                    // Ignorar erros
                }
            }
        }
    }
    
    /**
     * Encontra URL .m3u8 no HTML
     */
    private fun extractM3u8Url(html: String): String? {
        // Procurar em iframe src
        val iframePattern = Regex("""<iframe.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        iframePattern.find(html)?.let { return it.groupValues[1] }
        
        // Procurar em video src
        val videoPattern = Regex("""<video.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        videoPattern.find(html)?.let { return it.groupValues[1] }
        
        // Procurar em source src
        val sourcePattern = Regex("""<source.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        sourcePattern.find(html)?.let { return it.groupValues[1] }
        
        // Procurar qualquer URL .m3u8
        val urlPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        return urlPattern.find(html)?.value
    }
    
    // ================== MÉTODO DE FIX ==================
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$apiBaseUrl$url"
            else -> url // Assume que já é uma URL completa ou relativa à API
        }
    }
}
