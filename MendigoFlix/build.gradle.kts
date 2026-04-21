plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    namespace = "com.MendigoFlix"
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 33

        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "dummy_api_key"
        val tmdbAccessToken = System.getenv("TMDB_ACCESS_TOKEN") ?: "dummy_access_token"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
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

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}
