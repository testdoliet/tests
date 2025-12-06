package com.UltraCine.extractors

import com.lagradost.cloudstream3.extractors.VidStack

class PlayEmbedApiSite : VidStack() {
    override var name = "Play Embed API"
    override var mainUrl = "http://playembedapi.site" // Use http ou https conforme a URL do site
}
