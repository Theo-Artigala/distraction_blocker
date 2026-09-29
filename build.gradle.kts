// Script racine : on déclare seulement les plugins, sans les appliquer ici
// (`apply false`). Ils sont appliqués dans app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Depuis Kotlin 2.0, le compilateur Compose est un plugin Kotlin à part entière.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
