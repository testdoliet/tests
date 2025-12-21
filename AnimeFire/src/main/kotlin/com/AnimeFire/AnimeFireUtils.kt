package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus

fun getStatus(t: String?): ShowStatus {
    return when {
        t == null -> ShowStatus.Completed
        t.contains("Em lançamento", ignoreCase = true) -> ShowStatus.Ongoing
        t.contains("Concluído", ignoreCase = true) -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
}
