plugins {
    `kotlin-dsl`
}

dependencies {
    // plugins applied by the convention scripts (as plain dependencies so `plugins { id(...) }` resolves them)
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.shadow)
    implementation(libs.gradle.plugin.yml)
    implementation(libs.gradle.run.paper)
    implementation(libs.gradle.paperweight)
}
