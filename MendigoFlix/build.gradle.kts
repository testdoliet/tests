plugins {
    id("com.android.library")
}

android {
    namespace = "com.MendigoFlix"
    compileSdk = 33

    defaultConfig {
        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "dummy"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }
}

cloudstream {
    version = 1
    description = "MendigoFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://mendigoflix.lol/assets/favicon.png"
}
