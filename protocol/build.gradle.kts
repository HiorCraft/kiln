plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

base {
    archivesName.set("kiln-protocol")
}

kotlin {
    explicitApi()
}

dependencies {
    // provided at runtime by the shading server plugin resp. fabric-language-kotlin
    compileOnly(libs.kotlin.stdlib)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
