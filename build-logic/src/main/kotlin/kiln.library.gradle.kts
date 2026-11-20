// Published Kotlin library with an explicit public API (protocol, server-api)
plugins {
    id("kiln.kotlin")
    `java-library`
}

kotlin {
    explicitApi()
}
