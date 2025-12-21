package com.AnimeFire

// ============ FUNÇÕES UTILITÁRIAS PARA O ANIMEFIRE ============
object AnimeFireUtils {
    
    // Extrai o último episódio e tipo de áudio para mostrar na thumb
    fun extractLastEpisodeInfo(document: org.jsoup.nodes.Document): Pair<String, String?> {
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a")
        var lastEpNumber = 0
        var lastAudioType: String? = null
        
        episodeElements.forEach { element ->
            try {
                val text = element.text().trim()
                val epNumber = extractEpisodeNumber(text) ?: 0
                
                if (epNumber > lastEpNumber) {
                    lastEpNumber = epNumber
                    lastAudioType = when {
                        text.contains("dublado", ignoreCase = true) -> "Dub"
                        text.contains("legendado", ignoreCase = true) -> "Leg"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return Pair(if (lastEpNumber > 0) "Ep $lastEpNumber" else "", lastAudioType)
    }
    
    // Extrai tipo de áudio da página (Leg/Dub)
    fun extractAudioTypeFromPage(document: org.jsoup.nodes.Document): String? {
        val audioDiv = document.select("div.animeInfo").firstOrNull { 
            it.text().contains("Audio:", ignoreCase = true) 
        }
        
        val audioText = audioDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim() ?: ""
        
        return when {
            audioText.contains("dublado", ignoreCase = true) && audioText.contains("legendado", ignoreCase = true) -> "Both"
            audioText.contains("dublado", ignoreCase = true) -> "Dub"
            audioText.contains("legendado", ignoreCase = true) -> "Leg"
            else -> null
        }
    }
    
    // Extrai status da página (apenas o texto, não converte)
    fun extractStatusFromPage(document: org.jsoup.nodes.Document): String? {
        val statusDiv = document.select("div.animeInfo").firstOrNull { 
            it.text().contains("Status:", ignoreCase = true) 
        }
        
        return statusDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
    }
    
    // Extrai número do episódio de um texto
    fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
    
    // Formata título com info de episódio (como o AllWish)
    fun formatTitleWithEpisode(cleanTitle: String, audioType: String?, epNumber: Int?): String {
        return when {
            audioType != null && epNumber != null -> "$cleanTitle ($audioType Ep $epNumber)"
            epNumber != null -> "$cleanTitle - Episódio $epNumber"
            else -> cleanTitle
        }
    }
}
