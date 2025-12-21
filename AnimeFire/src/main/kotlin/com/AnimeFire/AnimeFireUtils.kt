package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus

// ============ FUNÇÕES UTILITÁRIAS ============

fun getStatus(t: String?): ShowStatus {
    return when (t?.lowercase()?.trim()) {
        "em lançamento", "lançando", "em andamento", "ongoing", "atualizando" -> ShowStatus.Ongoing
        "concluído", "completo", "completado", "terminado", "finished" -> ShowStatus.Completed
        else -> ShowStatus.Completed
    }
}

// Classe para extrair informações de badges
object AnimeFireBadgeExtractor {
    
    // Extrair informações para badges dos cards
    fun extractBadgeInfoFromCard(element: org.jsoup.nodes.Element): BadgeInfo {
        val text = element.text().trim()
        
        // Detectar tipo de áudio
        val hasDub = text.contains("dublado", ignoreCase = true)
        val hasLeg = text.contains("legendado", ignoreCase = true)
        
        // Extrair número do episódio
        var lastEpNumber: Int? = null
        
        // Tentar de diferentes fontes
        val epSelectors = listOf(".numEp", ".episode", ".eps", ".badge", "span")
        for (selector in epSelectors) {
            val epText = element.selectFirst(selector)?.text()?.trim()
            if (epText != null) {
                val match = Regex("(\\d+)").find(epText)
                if (match != null) {
                    lastEpNumber = match.value.toIntOrNull()
                    if (lastEpNumber != null) {
                        // Sai do loop quando encontra
                        break
                    }
                }
            }
        }
        
        // Se não encontrou, tentar do texto completo
        if (lastEpNumber == null) {
            val patterns = listOf(
                Regex("Ep[\\s.]*(\\d+)", RegexOption.IGNORE_CASE),
                Regex("Epis[oó]dio\\s*(\\d+)"),
                Regex("(\\d{1,3})\\s*-"),
                Regex("#(\\d+)")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    lastEpNumber = match.groupValues[1].toIntOrNull()
                    if (lastEpNumber != null) break
                }
            }
        }
        
        return BadgeInfo(
            hasDub = hasDub,
            hasLeg = hasLeg,
            lastDubEp = if (hasDub) lastEpNumber else null,
            lastLegEp = if (hasLeg) lastEpNumber else null
        )
    }
    
    // Extrair informações de badge da página de detalhes
    fun extractBadgeInfoFromDetailPage(document: org.jsoup.nodes.Document): BadgeInfo {
        // Extrair tipo de áudio
        val animeInfoDivs = document.select("div.animeInfo")
        val audioDiv = if (animeInfoDivs.size >= 7) animeInfoDivs[6] 
                      else document.select("div.animeInfo:contains(Audio:)").firstOrNull()
        
        val audioText = audioDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim() ?: ""
        
        val hasDub = audioText.contains("dublado", ignoreCase = true)
        val hasLeg = audioText.contains("legendado", ignoreCase = true)
        
        // Extrair últimos episódios da lista de episódios
        var lastLegEp: Int? = null
        var lastDubEp: Int? = null
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a")
        episodeElements.forEach { element ->
            val text = element.text().trim()
            val epNumber = extractEpisodeNumber(text) ?: return@forEach
            
            val isDub = text.contains("dublado", ignoreCase = true)
            val isLeg = text.contains("legendado", ignoreCase = true)
            
            if (isLeg) {
                if (lastLegEp == null || epNumber > lastLegEp!!) {
                    lastLegEp = epNumber
                }
            }
            if (isDub) {
                if (lastDubEp == null || epNumber > lastDubEp!!) {
                    lastDubEp = epNumber
                }
            }
        }
        
        return BadgeInfo(
            hasDub = hasDub,
            hasLeg = hasLeg,
            lastDubEp = lastDubEp,
            lastLegEp = lastLegEp
        )
    }
    
    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("(\\d{1,4})").find(text)?.value?.toIntOrNull()
    }
}

// Data class para informações de badge
data class BadgeInfo(
    val hasDub: Boolean = false,
    val hasLeg: Boolean = false,
    val lastDubEp: Int? = null,
    val lastLegEp: Int? = null
)

// Funções para formatar badges
object BadgeFormatter {
    
    fun formatBadgeText(audioType: String?, episodeNumber: Int?): String? {
        if (audioType == null || episodeNumber == null) return null
        
        return when (audioType.lowercase()) {
            "dub" -> "Dub Ep $episodeNumber"
            "leg" -> "Leg Ep $episodeNumber"
            else -> null
        }
    }
    
    fun getBadgeTitle(cleanTitle: String, badgeInfo: BadgeInfo): String {
        val badges = mutableListOf<String>()
        
        badgeInfo.lastLegEp?.let {
            badges.add("Leg Ep $it")
        }
        
        badgeInfo.lastDubEp?.let {
            badges.add("Dub Ep $it")
        }
        
        return if (badges.isNotEmpty()) {
            "$cleanTitle (${badges.joinToString(", ")})"
        } else {
            cleanTitle
        }
    }
}
