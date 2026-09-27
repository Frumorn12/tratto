import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// La chiave delle release sta fuori dal repository (~/.config/tratto/firma.properties). Senza,
// la release esce non firmata: e' quello che si aspetta F-Droid, che firma con la sua chiave.
val firma = File(System.getProperty("user.home"), ".config/tratto/firma.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "it.frumorn.tratto"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.frumorn.tratto"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (firma != null) create("release") {
            storeFile = File(firma.getProperty("storeFile"))
            storePassword = firma.getProperty("storePassword")
            keyAlias = firma.getProperty("keyAlias")
            keyPassword = firma.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (firma != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }

    // Due versioni con lo stesso applicationId. "completa" ha il riconoscimento della scrittura
    // (ML Kit) e il backup su Google Drive (Play services), librerie non libere. "libera" e' solo
    // software libero, per F-Droid: niente trascrizione ne' calcoli, backup automatico in una
    // cartella scelta dall'utente. Il codice che cambia sta in src/completa e src/libera.
    flavorDimensions += "distribuzione"
    productFlavors {
        create("completa") {
            dimension = "distribuzione"
            isDefault = true
            proguardFile("proguard-completa.pro")
        }
        create("libera") {
            dimension = "distribuzione"
        }
    }

    // Il blocco delle dipendenze cifrato con la chiave di Google: F-Droid rifiuta gli APK che lo contengono.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // PdfBox-Android porta circa 4,6 MB di asset (font, CMap, glyph list) che servono solo per
        // leggere o scrivere testo. L'esportazione disegna solo tracciati, quindi non li includiamo.
        // Se un giorno si usa PdfBox per il testo (estrazione, font), togliere questa riga.
        ignoreAssetsPatterns += "!tom_roush"
    }

    // I test JVM usano PdfBox-Android, che chiama android.util.Log: senza questo gli stub lanciano eccezioni.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.strokes)
    implementation(libs.ink.rendering)
    implementation(libs.ink.geometry)
    implementation(libs.androidx.motionprediction)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // Solo per l'esportazione in PDF. BouncyCastle serve solo ai PDF cifrati con certificato
    // (PublicKeySecurityHandler): quelli con password usano javax.crypto e funzionano lo stesso.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    implementation(libs.androidx.work.runtime.ktx)

    // Solo nella versione completa: librerie Google non libere.
    "completaImplementation"(libs.mlkit.digital.ink)
    "completaImplementation"(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
