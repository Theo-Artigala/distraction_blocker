plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.theo.distractionblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.theo.distractionblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // APK perso : pas d'obfuscation, on veut des stacktraces lisibles.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // Necessaire pour lire BuildConfig.DEBUG (debogage de la WebView).
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // collectAsStateWithLifecycle : arrete la collecte quand l'ecran est cache.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Stockage clé/valeur asynchrone (remplaçant moderne de SharedPreferences).
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Le BOM aligne toutes les versions Compose entre elles.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}
