package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus
import org.jsoup.nodes.Element

// Função getStatus para detectar status do anime
fun getStatus(t: String?): ShowStatus {
    if (t == null) return ShowStatus.Completed
    
    val status = t.trim()
    
    return when {
        status.contains("em lançamento", ignoreCase = true) ||
        status.contains("lançando", ignoreCase = true) ||
        status.contains("em andamento", ignoreCase = true) ||
        status.contains("ongoing", ignoreCase = true) ||
        status.contains("atualizando", ignoreCase = true) -> ShowStatus.Ongoing
        
        status.contains("concluído", ignoreCase = true) ||
        status.contains("completo", ignoreCase = true) ||
        status.contains("completado", ignoreCase = true) ||
        status.contains("terminado", ignoreCase = true) ||
        status.contains("finished", ignoreCase = true) -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
}

// Funções utilitárias para o AnimeFire
object AnimeFireUtils {
    
    // Extrai o último episódio e tipo de áudio para mostrar na thumb
    fun extractLastEpisodeInfo(document: org.jsoup.nodes.Document): Pair<String, String?> {
        // Procurar por informações de episódios
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
        // Primeiro método: elemento específico
        val audioElement = document.select("div.animeInfo:nth-child(7) span.spanAnimeInfo").firstOrNull()
        val audioText = audioElement?.text()?.trim() ?: ""
        
        return when {
            audioText.contains("dublado", ignoreCase = true) && audioText.contains("legendado", ignoreCase = true) -> "Both"
            audioText.contains("dublado", ignoreCase = true) -> "Dub"
            audioText.contains("legendado", ignoreCase = true) -> "Leg"
            else -> null
        }
    }
    
    // Extrai status da página
    fun extractStatusFromPage(document: org.jsoup.nodes.Document): String? {
        // Seletor: div.animeInfo:nth-child(11)
        val statusElement = document.select("div.animeInfo:nth-child(11) span.spanAnimeInfo").firstOrNull()
        return statusElement?.text()?.trim()
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
