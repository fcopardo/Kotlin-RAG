// A standalone app that uses the library the way any app would: through Maven coordinates.
// Publish the library first, from the repository root: ./gradlew publishToMavenLocal
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("rag") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "rag-sample"
include(":app")
