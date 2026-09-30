plugins {
    id("langgraph.jvm-library")
    id("langgraph.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":langgraph-kt-core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
