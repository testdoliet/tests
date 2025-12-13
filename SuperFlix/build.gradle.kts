@file:Suppress("UnstableApiUsage")

version = 1

android {
    compileSdk = 34
    namespace = "com.SuperFlix"
    
    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://superflix21.lol/assets/logo.png"
    isCrossPlatform = true
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
