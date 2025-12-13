@file:Suppress("UnstableApiUsage")

version = 1

android {
    compileSdk = 34
    namespace = "com.SuperFlix"
    
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    
    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    // Configuração KotlinOptions dentro do android
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Adiciona esta configuração global para Kotlin
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
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
