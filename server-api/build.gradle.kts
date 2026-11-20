plugins {
    id("kiln.publish")
    id("kiln.library")
}

base {
    archivesName.set("kiln-server-api")
}

dependencies {
    compileOnly(libs.paper.api)
    // provided at runtime by the Kiln plugin jar
    compileOnly(libs.kotlin.stdlib)
}
