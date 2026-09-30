plugins {
    id("langgraph.kotlin-library")
    id("langgraph.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
