plugins {
    id("langgraph.kmp-library")
    id("langgraph.publishing")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":telar-core"))
            api(libs.kotlinx.io.core)
            implementation(project(":telar-serialization"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
