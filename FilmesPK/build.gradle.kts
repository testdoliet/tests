plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.FilmesPK"
    compileSdk = 33

    defaultConfig {
        minSdk = 24

        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "dummy_api_key"
        val tmdbAccessToken = System.getenv("TMDB_ACCESS_TOKEN") ?: "dummy_access_token"
        
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
    }
}

cloudstream {
    version = 1
    description = "Filmes PK. Site criado com o objetivo de reunir informações sobre séries e filmes de TV gratuitamente sem intuito de fins lucrativos. Rave PK"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://blogger.googleusercontent.com/img/a/AVvXsEgNF0FEoFIlzsxkDmJIqwACYHLFKf0M9XoinXL7dxraDkF8IO5hTgVrZid-odbrtRWS_4P2bdgXx-050Wy2MrvN3CCAdWGvUOd__wIj1feE-m0daGOr9VUReDdNAjeIViNEiW3F4-sUpb6OVfa6nsSDm3g3uj6dCpaStQH2lSKQ_4YI7IOOt_f6H1LrGwE=s817"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
