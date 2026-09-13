pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // NUEVO — pedido expresamente tras el fallo real "Cannot find a Java installation...
    // Toolchain download repositories have not been configured": scanner-cli pide Java 17
    // específicamente (jvmToolchain(17), ver ese build.gradle.kts) para que compile y ejecute
    // siempre igual tanto en local como en GitHub Actions — pero sin este plugin, Gradle no
    // sabe DESCARGAR un JDK 17 por su cuenta si el ordenador no lo tiene ya instalado (como en
    // este caso, en Windows). Con este plugin, si hace falta, Gradle lo descarga solo, sin que
    // el usuario tenga que instalar nada a mano.
    // VERSIÓN 1.0.0 obligatoria (no una anterior como 0.8.0) — pedido tras el fallo real
    // "JvmVendorSpec does not have member field IBM_SEMERU": las versiones anteriores a 1.0.0
    // referencian una constante (IBM_SEMERU) que Gradle 9+ ya no tiene, así que rompen con
    // cualquier proyecto en Gradle 9 o superior (el caso de este proyecto).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
