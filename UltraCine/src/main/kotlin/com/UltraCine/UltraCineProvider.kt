package com.UltraCine

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
// ADICIONE AS IMPORTAÇÕES NECESSÁRIAS AQUI:
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.MainAPI

// Importa as classes de extratores que você criou no mesmo pacote
import com.UltraCine.PlayEmbedApiSite // Certifique-se que o nome do arquivo/classe é este
import com.UltraCine.EmbedPlayExtractor // Certifique-se que o nome do arquivo/classe é este

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        // CORRIGE UNRESOLVED REFERENCE PARA UltraCine
        registerMainAPI(UltraCine()) 
        
        // CORRIGE UNRESOLVED REFERENCE PARA PlayEmbedApiSite
        registerExtractorAPI(PlayEmbedApiSite())
        
        // CORRIGE UNRESOLVED REFERENCE PARA EmbedPlayExtractor
        registerExtractorAPI(EmbedPlayExtractor()) 
    } // <--- CHAVE DE FECHAMENTO CORRETA PARA O override fun load()
} // <--- CHAVE DE FECHAMENTO CORRETA PARA A CLASSE UltraCineProvider
