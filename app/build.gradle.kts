plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.frumorn.tratto"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.frumorn.tratto"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }

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

    testImplementation(libs.junit)
}
