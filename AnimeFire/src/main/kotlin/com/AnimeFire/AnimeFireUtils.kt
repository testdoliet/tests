package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus
import org.jsoup.nodes.Element

// Função getStatus atualizada para detectar melhor
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
        
        else -> {
            // DEBUG
            println("DEBUG - Status desconhecido: '$status', usando Completed como padrão")
            ShowStatus.Completed
        }
    }
}

// Outras funções utilitárias
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
            else -> "Sub"
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
}
