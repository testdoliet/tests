package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    // Mapa de qualidade com prioridade para altas qualidades
    private val itagQualityMap = mapOf(
        // Alta qualidade primeiro
        22 to Qualities.P720.value,   // MP4 720p (ALTA PRIORIDADE)
        37 to Qualities.P1080.value,  // MP4 1080p
        18 to Qualities.P360.value,   // MP4 360p (fallback)
        
        // Outras qualidades
        38 to Qualities.P2160.value,  // MP4 4K
        43 to Qualities.P360.value,   // WebM 360p
        44 to Qualities.P480.value,   // WebM 480p
        45 to Qualities.P720.value,   // WebM 720p
        46 to Qualities.P1080.value,  // WebM 1080p
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎬 [SuperFlix] Processando trailer do YouTube")
        
        try {
            val videoId = extractYouTubeId(url) ?: return
            println("📹 Video ID: $videoId")
            
            // Tentar múltiplas qualidades
            tryExtractMultipleQualities(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            // Fallback simples
            createSimpleFallback(url, callback)
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        getUrl(url, referer, {}, { link ->
            links.add(link)
        })
        
        return if (links.isNotEmpty()) links else null
    }
    
    private fun extractYouTube
