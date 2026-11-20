pluginManagement {
    includeBuild("build-logic")
    includeBuild("gradle-plugin")
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "kiln"

include(
    "protocol",
    "server:api",
    "server:runtime",
    "client",
    "example:server",
    "example:embedded-host",
)
