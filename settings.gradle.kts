// Déclaration des dépôts (équivalent d'un requirements.txt côté "où chercher les paquets").
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DistractionBlocker"
include(":app")
