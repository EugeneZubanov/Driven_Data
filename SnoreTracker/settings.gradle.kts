pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central; avoids Central's rate limiting on CI/cloud machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central; avoids Central's rate limiting on CI/cloud machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}
rootProject.name = "QuietNight"
include(":app")
