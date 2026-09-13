plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jsoup:jsoup:1.17.2") // Necesario para FinvizPeParser (domain/indicators, reutilizado tal cual)
}

// NUEVO — pedido expresamente: este módulo NO es parte de la app Android, es un programa de
// línea de comandos independiente, pensado para correr en GitHub Actions (JVM normal, sin
// Android). Reutiliza DIRECTAMENTE (sin copiar) los ficheros de domain/model y
// domain/indicators del módulo :app — ver los sourceSets de abajo — así el cálculo que hace
// este script es LITERALMENTE el mismo código que usa la app, no una reimplementación aparte
// que habría que mantener sincronizada a mano.
kotlin {
    sourceSets {
        main {
            kotlin.srcDir("../app/src/main/java/com/inversionadvisor/domain/model")
            kotlin.srcDir("../app/src/main/java/com/inversionadvisor/domain/indicators")
            // Excluido — usa BuildConfig (generado por el plugin de Android, no existe aquí) y
            // no tiene nada que ver con el escaneo de patrones/puntuación.
            kotlin.exclude("**/NewsTranslator.kt")
            // Excluidos — dependen de DTOs de data.remote (SecCompanyConceptUnitDto,
            // YahooChartResultDto) que no se incluyen aquí, y ninguno de los dos lo usa el
            // núcleo de escaneo (UptrendDetector/ExhaustionDetector/Top10Calculator/
            // BuyOpportunityAnalyzer) — son para "Valor Empresa"/"Ingresos Netos" y el cambio
            // diario del Dashboard, fuera del alcance de esta primera fase.
            kotlin.exclude("**/YahooDailyChangeCalculator.kt")
            kotlin.exclude("**/SecEdgarFinancialsParser.kt")
        }
    }
}

application {
    mainClass.set("com.inversionadvisor.scanner.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.inversionadvisor.scanner.MainKt"
    }
    // Jar "fat" con todas las dependencias dentro, para poder ejecutarlo con
    // `java -jar scanner-cli.jar` directamente en GitHub Actions sin classpath aparte.
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicateFilesStrategy = DuplicatesStrategy.EXCLUDE
}
