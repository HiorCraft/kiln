import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Kotlin/JVM on Java 25 with sources jar; shared by every Kiln module
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
