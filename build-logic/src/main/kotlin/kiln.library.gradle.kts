import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Published Kotlin library with an explicit public API (protocol, server:api)
plugins {
    id("kiln.kotlin")
    `java-library`
}

extensions.configure<KotlinJvmProjectExtension> {
    explicitApi()
}
