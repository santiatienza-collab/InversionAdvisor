import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Se carga aquí, FUERA del bloque android{}, porque dentro de defaultConfig
// el identificador "java" no resuelve al paquete java.util (choca con el DSL
// de Android) y da "Unresolved reference 'util'".
val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        load(FileInputStream(localPropsFile))
    }
}
val twelveDataApiKey: String = localProps.getProperty("TWELVE_DATA_API_KEY", "")
// NUEVO — clave de Financial Modeling Prep, para los ingresos netos de "Datos de Empresa"
// (sustituye al scraping de la página de estados financieros de Yahoo, ver
// FinancialModelingPrepApi/MarketRepository.refreshCompanyFinancialsInternal). Gratis en
// https://financialmodelingprep.com/developer/docs/ (hace falta registrarse).
val fmpApiKey: String = localProps.getProperty("FMP_API_KEY", "")
// NUEVO — contacto para el User-Agent de SEC EDGAR (ver SecEdgarApi/NetworkModule.secEdgarApi):
// la SEC pide identificarse con un email real en las peticiones a su API pública (política de
// "fair access", no es una clave secreta ni hace falta registro, pero sí un contacto de verdad
// — un valor genérico puede acabar limitado con más agresividad si hay abuso desde esa
// dirección). local.properties -> SEC_EDGAR_CONTACT_EMAIL=tu_email@real.com
val secEdgarContactEmail: String = localProps.getProperty("SEC_EDGAR_CONTACT_EMAIL", "")
// NUEVO — clave de DeepL para traducir los titulares de Noticias con mucha más calidad que
// el traductor on-device (ML Kit): DeepL tiene un plan gratuito (500.000 caracteres/mes, de
// sobra para esto). Regístrate en https://www.deepl.com/pro-api (plan "DeepL API Free"). Si
// se deja vacío, NewsTranslator cae automáticamente a ML Kit on-device (peor calidad pero
// funciona sin configurar nada).
// local.properties -> DEEPL_API_KEY=tu_clave_aqui
val deepLApiKey: String = localProps.getProperty("DEEPL_API_KEY", "")

android {
    namespace = "com.inversionadvisor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inversionadvisor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // La API key de Twelve Data se lee de local.properties (NO subir al repo)
        // local.properties -> TWELVE_DATA_API_KEY=tu_clave_aqui
        buildConfigField("String", "TWELVE_DATA_API_KEY", "\"$twelveDataApiKey\"")

        // local.properties -> FMP_API_KEY=tu_clave_aqui
        buildConfigField("String", "FMP_API_KEY", "\"$fmpApiKey\"")

        // local.properties -> SEC_EDGAR_CONTACT_EMAIL=tu_email@real.com
        buildConfigField("String", "SEC_EDGAR_CONTACT_EMAIL", "\"$secEdgarContactEmail\"")

        // local.properties -> DEEPL_API_KEY=tu_clave_aqui
        buildConfigField("String", "DEEPL_API_KEY", "\"$deepLApiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Parseo del HTML público de AAII (sentimiento alcistas/bajistas) — AAII no tiene API JSON
    implementation("org.jsoup:jsoup:1.18.1")

    // Room (persistencia / caché local)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager (refresco periódico en background)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Gráfica de detalle del screener (precio + SMA10 semanal + panel de volumen estilo TradingView)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // NUEVO — traducción EN->ES de los titulares de Noticias. ML Kit Translate traduce EN EL
    // DISPOSITIVO (sin mandar texto a ningún servidor, sin API key ni coste): la primera vez
    // que se usa descarga el modelo del par de idiomas (~30MB, necesita red esa única vez),
    // y a partir de ahí traduce offline.
    implementation("com.google.mlkit:translate:17.0.3")
}
