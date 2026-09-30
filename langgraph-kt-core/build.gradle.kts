plugins {
    id("langgraph.kmp-library")
    id("langgraph.publishing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
