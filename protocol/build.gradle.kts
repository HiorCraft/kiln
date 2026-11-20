plugins {
    id("kiln.library")
    id("kiln.test")
}

base {
    archivesName.set("kiln-protocol")
}

dependencies {
    // provided at runtime by the shading server plugin resp. fabric-language-kotlin
    compileOnly(libs.kotlin.stdlib)
    testImplementation(libs.kotlin.stdlib)
}
