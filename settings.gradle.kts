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
        // JitPack: necesario para MPAndroidChart (gráfica de precio + SMA + volumen del screener)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "InversionAdvisor"
include(":app")
include(":scanner-cli")
