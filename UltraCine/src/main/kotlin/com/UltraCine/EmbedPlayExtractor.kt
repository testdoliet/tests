package com.UltraCine

import com.lagradost.cloudstream3.extractors.VidStack

// Esta classe lida com o domínio upns.pro
class EmbedPlayUpnsPro : VidStack() {
    override var name = "EmbedPlay UpnsPro"
    override var mainUrl = "https://embedplay.upns.pro"
}

// Esta classe lida com o domínio upn.one
class EmbedPlayUpnOne : VidStack() {
    override var name = "EmbedPlay UpnOne"
    override var mainUrl = "https://embedplay.upn.one"
}