plugins {
    id("langgraph.web-library")
    id("langgraph.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        webMain.dependencies {
            api(project(":langgraph-kt-core"))
            implementation(project(":langgraph-kt-serialization"))
        }
        webTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
