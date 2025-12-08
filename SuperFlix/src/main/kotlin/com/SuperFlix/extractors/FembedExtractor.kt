package com.SuperFlix.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class FembedExtractor : ExtractorApi() {
    override val name = "Fembed"
    override val mainUrl = "https://fembed.sx"
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            val videoId = extractVideoId(url) ?: return links
            
            // Usar fembed.com como domínio principal
            val domain = "www.fembed.com"
            val apiUrl = "https://$domain/api/source/$videoId"
            
            val response = app.post(
                apiUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to "https://$domain/",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("r" to "", "d" to domain)
            )
            
            val json = response.parsedSafe<FembedResponse>()
            if (json?.success == true) {
                json.data?.forEach { stream ->
                    val file = stream.file ?: return@forEach
                    val label = stream.label ?: "Unknown"
                    
                    // Criar link manualmente se newExtractorLink não funcionar
                    val link = ExtractorLink(
                        source = name,
                        name = label,
                        url = file,
                        referer = "https://$domain/",
                        quality = getQuality(label),
                        isM3u8 = file.contains(".m3u8")
                    )
                    
                    links.add(link)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return links
    }
    
    private fun extractVideoId(url: String): String? {
        return try {
            // Extrair a última parte da URL
            url.substringAfterLast("/")
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getQuality(label: String): Int {
        return when {
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    data class FembedResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("data") val data: List<FembedStream>? = null
    )
    
    data class FembedStream(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}