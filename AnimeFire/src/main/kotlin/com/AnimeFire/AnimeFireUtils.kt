package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus

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
