package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// 1. IMPORTAÇÕES EXPLÍCITAS DAS CLASSES DE EXTRATORES
// O compilador precisa ver onde essas classes estão definidas no mesmo pacote.
import com.UltraCine.PlayEmbedApiSite
import com.UltraCine.EmbedPlayExtractor

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        // 2. REGISTRO DO API PRINCIPAL
        registerMainAPI(UltraCine()) 
        
        // 3. REGISTRO DOS EXTRATORES (Usando as referências importadas)
        registerExtractorAPI(PlayEmbedApiSite())
        registerExtractorAPI(EmbedPlayExtractor()) 
    }
}
