package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus
import org.jsoup.nodes.Element

// Funções de utilidade para extração de episódios
object AnimeFireUtils {
    
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
    
    // Determina se é dublado ou legendado
    fun determineAudioType(element: Element): String {
        val text = element.text().lowercase()
        return when {
            text.contains("dublado") -> "Dub"
            text.contains("legendado") -> "Leg"
            else -> "Sub" // Padrão legendado
        }
    }
    
    // Extrai status do anime e converte para ShowStatus
    fun extractShowStatus(statusText: String?): ShowStatus {
        return when {
            statusText?.contains("Completo", ignoreCase = true) == true -> ShowStatus.Completed
            statusText?.contains("Em andamento", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Lançando", ignoreCase = true) == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }
    
    // Extrai tipo de áudio disponível
    fun extractAudioTypes(audioText: String?): Pair<Boolean, Boolean> {
        val text = audioText ?: "Legendado"
        
        val hasSub = text.contains("Legendado", ignoreCase = true)
        val hasDub = text.contains("Dublado", ignoreCase = true)
        
        return Pair(hasSub, hasDub)
    }
    
    // Formata título com info de episódio
    fun formatTitleWithEpisode(cleanTitle: String, audioType: String?, epNumber: Int?): String {
        return if (audioType != null && epNumber != null) {
            "$cleanTitle ($audioType Ep $epNumber)"
        } else if (epNumber != null) {
            "$cleanTitle - Episódio $epNumber"
        } else {
            cleanTitle
        }
    }
fun getStatus(t: String?): ShowStatus {
    return when {
        t == null -> ShowStatus.Completed
        t.contains("em andamento", ignoreCase = true) || 
        t.contains("lançando", ignoreCase = true) ||
        t.contains("lançamento", ignoreCase = true) ||
        t.contains("updating", ignoreCase = true) ||
        t.contains("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
        
        t.contains("concluído", ignoreCase = true) ||
        t.contains("completo", ignoreCase = true) ||
        t.contains("completado", ignoreCase = true) ||
        t.contains("finished", ignoreCase = true) -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
  }
}
