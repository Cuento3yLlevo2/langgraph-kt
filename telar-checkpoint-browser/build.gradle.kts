plugins {
    id("langgraph.web-library")
    id("langgraph.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        webMain.dependencies {
            api(project(":telar-core"))
            implementation(project(":telar-serialization"))
        }
        webTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
