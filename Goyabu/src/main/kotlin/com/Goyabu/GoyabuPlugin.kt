package com.Goyabu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GoyabuProviderPlugin: Plugin() {
    override fun load(context: Context) {
        println("🎬 GOYABU: Iniciando plugin...")
        
        // Registrar o provider principal
        registerMainAPI(Goyabu())
        println("🎬 GOYABU: Provider registrado com sucesso")
        
        println("✅ GOYABU: Plugin carregado - 18 gêneros disponíveis")
    }
}
