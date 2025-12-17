package com.AnimeFire

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFireProvider: Plugin() {
    override fun load(context: Context) {
        println("AnimeFireProvider: load - INICIANDO PLUGIN")

        // Registrar o provider principal
        registerMainAPI(AnimeFire())
        println("AnimeFireProvider: load - AnimeFire provider registrado")

        // Não é necessário registrar extractors separadamente
        println("AnimeFireProvider: load - Plugin carregado com sucesso")
    }
}
