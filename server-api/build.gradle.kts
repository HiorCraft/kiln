plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

base {
    archivesName.set("kiln-server-api")
}

kotlin {
    explicitApi()
}

dependencies {
    compileOnly(libs.paper.api)
    // provided at runtime by the Kiln plugin jar
    compileOnly(libs.kotlin.stdlib)
}
