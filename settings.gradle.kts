// Este código deve estar no arquivo 'settings.gradle.kts' na raiz do seu repositório.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cloudstream-providers"

// ----------------------------------------------------------------------------------
// Inclusão dos Providers
// ----------------------------------------------------------------------------------

include(":SuperFlix")
project(":SuperFlix").projectDir = file("src/main/apis/SuperFlix")

