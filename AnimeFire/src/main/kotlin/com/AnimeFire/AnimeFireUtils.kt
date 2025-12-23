package com.AnimeFire

import com.lagradost.cloudstream3.ShowStatus

// Função getStatus MELHORADA
fun getStatus(t: String?): ShowStatus {
    if (t == null) return ShowStatus.Completed
    
    val status = t.trim().lowercase()
    println("DEBUG getStatus - Analisando: '$status'")
    
    return when {
        status.contains("em lançamento") ||
        status.contains("lançando") ||
        status.contains("em andamento") ||
        status.contains("atualizando") ||
        status.contains("ongoing") ||
        status.contains("lançamento") -> {
            println("DEBUG getStatus - Detectado como ONGOING")
            ShowStatus.Ongoing
        }
        
        status.contains("concluído") ||
        status.contains("completo") ||
        status.contains("completado") ||
        status.contains("terminado") ||
        status.contains("finished") -> {
            println("DEBUG getStatus - Detectado como COMPLETED")
            ShowStatus.Completed
        }
        
        else -> {
            println("DEBUG getStatus - Status desconhecido, padrão: COMPLETED")
            ShowStatus.Completed
        }
    }
}
